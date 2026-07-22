package com.bydmate.app.daemon

import com.bydmate.app.BuildConfig
import com.bydmate.app.data.remote.CommandAllowlist
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsControlClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.IternioIntervalPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * Headless command-poll daemon — the survival-proof twin of [com.bydmate.app.data.remote.VehicleCommandPoller].
 *
 * Why this exists: the in-app poller runs inside the `com.bydmate.app` process, which BYD's
 * power-off routine (`collectPowerOffEvent` → force-stop) kills when the head unit parks/sleeps.
 * This class is launched as a shell-uid `app_process` daemon (see `tools/start_voltflow_cmd.sh`),
 * which survives the force-stop exactly like DI+ (`aps_diplus`) does.
 *
 * It needs NO Android Context: both actuation and telemetry go over plain localhost HTTP to
 * DiPlus on 127.0.0.1:8988, and [DiParsClient] / [DiParsControlClient] / [CommandAllowlist]
 * are all constructible from just an [OkHttpClient]. Config (cloud URL / api key / vehicle id)
 * comes from a properties-style file instead of SettingsRepository.
 *
 * Launch:  CLASSPATH=<base.apk> app_process /system/bin --nice-name=voltflow_cmd_daemon \
 *              com.bydmate.app.daemon.CommandDaemon [confPath]
 *
 * Safety: reuses [CommandAllowlist] (movement / aux-voltage guards + phrase allowlist) and
 * [DiParsControlClient]'s blocked-pattern filter verbatim — no command logic is reinvented here.
 */
object CommandDaemon {

    private const val DEFAULT_CONF = "/data/local/tmp/voltflow_cmd.conf"
    private const val BASE_POLL_MS = 6000L
    private const val MAX_BACKOFF_MS = 30_000L

    /** How often to refresh telemetry used for movement/voltage guards. */
    private const val TELEMETRY_TTL_MS = 5_000L

    /** How often the daemon pushes a telemetry sample to the cloud (keeps data flowing when the app is dead). */
    private const val TELEMETRY_PUSH_MS = 60_000L

    /**
     * How often to re-assert WiFi via `svc wifi enable` when [Conf.keepWifiAwake] is set.
     * DiLink drops WiFi ~9 minutes after park unless "Keep network on while parked" is enabled
     * in head-unit settings; this automates that from the daemon (shell uid, no ADB needed —
     * see docs/EV_PRO_APP_ANALYSIS.md section 4) instead of relying on the user finding that
     * toggle. 60s keeps well under the 9-minute drop window with negligible overhead.
     */
    private const val WIFI_KEEPALIVE_INTERVAL_MS = 60_000L

    /**
     * Status push interval while the live view is open, mirroring
     * `CloudTelemetrySender.LIVE_FAST_PING_INTERVAL_MS`. The daemon needs its own copy
     * because it builds its own payload and bypasses that class entirely — the same reason
     * the parked `live_only` logic had to be duplicated here.
     */
    private const val LIVE_FAST_PUSH_MS = 3_000L

    /** Device time until which the live view is known to be open; see [LIVE_FAST_PUSH_MS]. */
    @Volatile private var liveFastUntilMs = 0L

    /**
     * Latest DiPars read, published by the status loop and consumed by the command loop
     * (commands are evaluated against current vehicle state). `@Volatile` because those are
     * two threads — see [main].
     */
    @Volatile private var latestData: DiParsData? = null
    @Volatile private var latestDataAt = 0L

    /**
     * Why this iteration is pushing, and what that implies. Extracted from the loop so the
     * gating can be unit-tested — the loop itself is unreachable from tests (it blocks on
     * DiPars, HTTP and Thread.sleep forever).
     */
    internal data class PushPlan(
        val push: Boolean,
        val dueByInterval: Boolean,
        val gunChanged: Boolean,
    ) {
        /**
         * Only a real cadence push (or a stored plug/unplug edge) advances the history
         * rhythm; fast-mode status pushes must leave it alone or they starve it entirely.
         */
        val advancesIntervalTimer: Boolean get() = dueByInterval || gunChanged

        /**
         * @param unchanged parked and nothing material moved (the phase-2 idle test)
         * @param runExpired the 15-minute forced-full rule for phantom-drain analytics
         */
        fun liveOnly(unchanged: Boolean, runExpired: Boolean): Boolean = when {
            // A plug/unplug is a real event: store it, and re-baseline.
            gunChanged -> false
            // Status-only. The parked `unchanged` test is false while charging, so without
            // this a watched charge would write a history row every push.
            !dueByInterval -> true
            else -> unchanged && !runExpired
        }
    }

    internal fun planPush(
        now: Long,
        lastTelemetryPushAt: Long,
        lastIntervalPushAt: Long,
        liveFastUntilMs: Long,
        gunChanged: Boolean,
    ): PushPlan {
        val dueByInterval = now - lastIntervalPushAt >= TELEMETRY_PUSH_MS
        val dueByFast = now < liveFastUntilMs &&
            now - lastTelemetryPushAt >= LIVE_FAST_PUSH_MS
        return PushPlan(
            push = dueByInterval || dueByFast || gunChanged,
            dueByInterval = dueByInterval,
            gunChanged = gunChanged,
        )
    }

    /**
     * How often the status loop should wake. The wake rate — not the push interval — is the
     * real floor on latency: a 3s push interval is meaningless if the thread only wakes every
     * 6s. Off fast mode this stays at [BASE_POLL_MS] rather than the 60s push cadence, so
     * DiPars stays fresh for the command loop and a plug/unplug edge is still caught quickly.
     */
    internal fun statusIntervalMs(now: Long, liveFastUntilMs: Long): Long =
        if (now < liveFastUntilMs) LIVE_FAST_PUSH_MS else BASE_POLL_MS

    /**
     * Gates the `svc wifi enable` tick: only when the user opted in ([enabled], mirrors
     * [Conf.keepWifiAwake]) and at least [WIFI_KEEPALIVE_INTERVAL_MS] has passed since the last
     * attempt. Extracted as a pure function so it's testable without a real shell exec.
     */
    internal fun shouldRefreshWifiKeepalive(now: Long, lastAttemptAt: Long, enabled: Boolean): Boolean =
        enabled && now - lastAttemptAt >= WIFI_KEEPALIVE_INTERVAL_MS

    /**
     * Fixed-rate pacing. Sleeping the full interval after doing the work makes the period
     * interval + work — measured 8-9s for a 3s interval once DiPars, the telemetry POST and
     * the command poll were all counted. Never returns negative (a slow iteration simply
     * runs the next one immediately).
     */
    internal fun pacedSleepMs(intervalMs: Long, elapsedMs: Long): Long =
        (intervalMs - elapsedMs).coerceAtLeast(0L)

    /**
     * Longest a parked `live_only` run may last before a full sample is forced. Car-off is the
     * daemon's whole reason to exist, so without this an overnight park with flat SOC would store
     * no parked rows at all and leave a single >6h gap — which `bydmate_phantom_drain_daily`
     * discards, zeroing `idle_hours` for the day. Mirrors CloudTelemetrySender.LIVE_ONLY_MAX_RUN_MS.
     */
    private const val LIVE_ONLY_MAX_RUN_MS = 15 * 60 * 1000L

    /** 12V drift below this is noise, not a state change worth a history row. */
    private const val LIVE_ONLY_12V_EPSILON_V = 0.3

    /**
     * App-liveness beacon written by [com.bydmate.app.service.TrackingService.writeAppAliveHeartbeat].
     * While it is fresh the app is actively sending and the daemon must not duplicate it
     * (that would store duplicate samples and risk phantom trips). Shell uid can read the
     * external-files dir.
     *
     * The beacon is written at **1 Hz**: `TrackingService`'s poll loop calls
     * `maybeSendCloudTelemetry` on every successful DiPars read (`POLL_INTERVAL_MS = 1000`),
     * and the write happens unconditionally after `enqueue`. Its age is therefore a
     * fine-grained "is the app's loop still turning" signal, not a coarse per-flush one —
     * which is what lets the thresholds below be seconds rather than minutes.
     */
    private const val APP_HEARTBEAT_FILE =
        "/storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/voltflow_mate_heartbeat"

    /**
     * Beacon age past which a *history-writing* push may proceed: 20 missed 1 Hz beacons.
     * The more conservative of the two — a false "app is dead" here stores a duplicate
     * sample, so it keeps margin against a GC pause or a slow DiPars round trip.
     */
    internal const val APP_ALIVE_FULL_TTL_MS = 20_000L

    /**
     * Beacon age past which a *status-only* (`live_only`) push may proceed: 5 missed beacons.
     * Shorter because a `live_only` push writes no history row at all — the server refreshes
     * `bydmate_live_snapshots` and skips samples/hourly/trips (docs/cloud-telemetry-contract-ru.md).
     * The only cost of being wrong is a few seconds of the daemon's reduced snapshot (no GPS,
     * no range/trip fields) replacing the app's richer one.
     */
    internal const val APP_ALIVE_LIVE_TTL_MS = 5_000L

    internal fun appAliveTtlMs(liveOnly: Boolean): Long =
        if (liveOnly) APP_ALIVE_LIVE_TTL_MS else APP_ALIVE_FULL_TTL_MS

    /**
     * Whether to stay silent because VoltFlow Mate is still sending. Pure so the gate is
     * unit-testable — the loop itself is unreachable from tests.
     *
     * This was one flat 120 s TTL applied to every push, which is why the owner saw ~2.5-3
     * minutes of stale status after every park: the head unit force-stops the app, the beacon
     * freezes mid-TTL, and the daemon deliberately said nothing for the remainder. Grading it
     * by what the push would actually write is what collapses that window.
     *
     * @param beaconAgeMs null when the beacon is absent or unreadable — then never defer;
     *   an app that has never written one is not sending.
     */
    internal fun shouldDeferToApp(beaconAgeMs: Long?, liveOnly: Boolean): Boolean =
        beaconAgeMs != null && beaconAgeMs in 0..appAliveTtlMs(liveOnly)

    private val ts get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    private fun log(msg: String) {
        println("[$ts] $msg")
        System.out.flush()
    }

    private var lastSkipLogAt = 0L
    private var lastSkipReason: String? = null

    /**
     * Skip logging, throttled to one line per reason per [TELEMETRY_PUSH_MS]. A change of
     * reason always logs, so transitions stay visible.
     *
     * Needed because the push timers no longer advance on a skip: while the app is alive the
     * history cadence is permanently due, so the guard is re-evaluated on every BASE_POLL_MS
     * wake. Unthrottled that is ~10× more lines an hour in
     * `/data/local/tmp/voltflow_cmd_daemon.log`, which is the file used for field diagnosis.
     */
    private fun logSkip(now: Long, reason: String) {
        if (reason == lastSkipReason && now - lastSkipLogAt < TELEMETRY_PUSH_MS) return
        lastSkipReason = reason
        lastSkipLogAt = now
        log("telemetry push skipped ($reason)")
    }

    private data class Conf(
        val commandsUrl: String,
        val ackUrl: String,
        val telemetryUrl: String,
        val apiKey: String,
        val vehicleId: String,
        /** Experimental: see [WIFI_KEEPALIVE_INTERVAL_MS] and `keep_wifi_awake` in voltflow_cmd.conf. */
        val keepWifiAwake: Boolean = false,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val confPath = args.getOrNull(0) ?: DEFAULT_CONF
        log("CommandDaemon starting (conf=$confPath)")

        val ok = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        val diPars = DiParsClient(ok)
        val control = DiParsControlClient(ok)

        // Commands poll on their own thread. Previously a single loop did DiPars + telemetry
        // POST + command poll in series, so every one of those round trips was added to the
        // status period — which is why a 3s push interval measured 8-9s on the car. Split,
        // the status loop only pays for its own work, and the command poll can stay at its
        // relaxed 6s even while the live view is open.
        Thread({ runBlocking { commandLoop(ok, control, confPath) } }, "voltflow-commands")
            .apply { isDaemon = true }
            .start()

        var lastTelemetryPushAt = 0L
        // Separate from [lastTelemetryPushAt] on purpose: fast-mode status pushes must not
        // keep resetting the history rhythm, or a car watched for an hour would store no
        // rows at all. This timer alone gates the normal TELEMETRY_PUSH_MS cadence.
        var lastIntervalPushAt = 0L
        // Last gun state the loop observed, for plug/unplug edge detection. Tracked every
        // iteration (even when the push is skipped) so a stale edge cannot fire later.
        var lastSeenGun: Int? = null
        // Baseline for the parked live_only decision, mirroring CloudTelemetrySender.decide().
        // Only a *full* push refreshes these, so the comparison stays against the last stored row.
        var lastFullPushAt = 0L
        var baseSoc: Int? = null
        var baseGun: Int? = null
        var baseGear: Int? = null
        var base12v: Double? = null
        // Independent of push cadence: ticks every WIFI_KEEPALIVE_INTERVAL_MS whenever
        // conf.keepWifiAwake is set, regardless of whether this iteration also pushes telemetry.
        var lastWifiKeepAliveAt = 0L

        runBlocking {
            while (true) {
                val conf = loadConf(confPath)
                if (conf == null) {
                    Thread.sleep(BASE_POLL_MS)
                    continue
                }

                val startedAt = System.currentTimeMillis()
                try {
                    val now = startedAt
                    if (shouldRefreshWifiKeepalive(now, lastWifiKeepAliveAt, conf.keepWifiAwake)) {
                        refreshWifiKeepalive()
                        lastWifiKeepAliveAt = now
                    }

                    // Refresh telemetry for guards if stale (cheap localhost call).
                    if (now - latestDataAt > TELEMETRY_TTL_MS) {
                        diPars.fetch()?.let { latestData = it; latestDataAt = now }
                    }

                    // Push telemetry to the cloud so data keeps flowing while the app process is dead.
                    // Two guards prevent duplicating the app's stream:
                    //  1. App-alive beacon — if VoltFlow Mate is actively sending, stay silent.
                    //     The daemon exists only to cover the window when BYD force-stops the app.
                    //     Graded by what the push would write, see [shouldDeferToApp].
                    //  2. Driving — belt-and-suspenders: never push a reduced-payload gear=1
                    //     heartbeat mid-drive (would split the live trip).
                    // Three reasons to push, in priority order:
                    //  * the normal history cadence,
                    //  * a plug/unplug edge — the event the owner is waiting to see, and the
                    //    reason plugging in with the car off used to take a minute,
                    //  * the live view being open (fast mode), which is status-only.
                    val gunNow = latestData?.chargeGunState
                    val gunChanged = lastSeenGun != null && gunNow != null && gunNow != lastSeenGun
                    if (gunNow != null) lastSeenGun = gunNow
                    val plan = planPush(
                        now = now,
                        lastTelemetryPushAt = lastTelemetryPushAt,
                        lastIntervalPushAt = lastIntervalPushAt,
                        liveFastUntilMs = liveFastUntilMs,
                        gunChanged = gunChanged,
                    )
                    if (plan.push) {
                        val pushed = latestData?.let { data ->
                            val state = IternioIntervalPolicy.classifyFromDiPars(data)
                            // Parked + nothing material moved => live_only: the server refreshes
                            // live state and skips the history/hourly/trip writes. Charging is
                            // deliberately excluded — its SOC curve and cell-delta tail need
                            // every row. Any change, or an expired run, sends a full sample and
                            // re-baselines.
                            //
                            // Computed *before* the guards, not inside the push branch: the
                            // app-alive gate is graded by whether this push would write history,
                            // so it has to know first.
                            val v12 = data.voltage12v
                            val b12 = base12v
                            val unchanged =
                                state == IternioIntervalPolicy.TelemetryState.PARKED &&
                                    data.soc != null && data.soc == baseSoc &&
                                    data.chargeGunState == baseGun &&
                                    data.gear == baseGear &&
                                    v12 != null && b12 != null &&
                                    abs(v12 - b12) < LIVE_ONLY_12V_EPSILON_V
                            val runExpired = lastFullPushAt != 0L &&
                                now - lastFullPushAt >= LIVE_ONLY_MAX_RUN_MS
                            val liveOnly = plan.liveOnly(unchanged, runExpired)
                            when {
                                shouldDeferToApp(beaconAgeMs(now), liveOnly) -> {
                                    logSkip(now, "app alive — VoltFlow Mate is sending")
                                    false
                                }
                                // NOT relaxed for live_only, unlike the app-alive guard above.
                                // The daemon's payload has no GPS (`location = {}`) and none of the
                                // app-only range/trip fields, and its device_time is always `now`
                                // while the app's batched samples lag — so it would win the server's
                                // stale-guard and blank the live map for the whole drive. The
                                // blackout this file fixes is a *park* transition (gear=1 => PARKED),
                                // so nothing here needs to give.
                                state == IternioIntervalPolicy.TelemetryState.DRIVING -> {
                                    logSkip(now, "driving — VoltFlow Mate is active")
                                    false
                                }
                                else -> {
                                    if (!liveOnly) {
                                        baseSoc = data.soc
                                        baseGun = data.chargeGunState
                                        baseGear = data.gear
                                        base12v = v12
                                        lastFullPushAt = now
                                    }
                                    pushTelemetry(ok, conf, data, liveOnly)
                                    true
                                }
                            }
                        } ?: false
                        // Only a push that actually went out may advance the timers. Advancing on a
                        // skip is what stretched the post-park blackout by up to a further 60 s: the
                        // history cadence kept "firing" into the void while the app was assumed
                        // alive, so when the beacon finally aged out the daemon had just reset its
                        // own clock. Same for a DiPars read that returned nothing.
                        if (pushed) {
                            lastTelemetryPushAt = now
                            if (plan.advancesIntervalTimer) lastIntervalPushAt = now
                            // A push breaks the skip run, so the next skip is worth a line.
                            lastSkipReason = null
                        }
                    }

                } catch (e: Exception) {
                    log("status error: ${e.message}")
                }
                // Fixed-rate, not fixed-delay: sleeping a whole interval *after* the work
                // makes the period interval + work. Subtracting the elapsed time is what
                // actually delivers the interval the live view was promised.
                val interval = statusIntervalMs(System.currentTimeMillis(), liveFastUntilMs)
                Thread.sleep(pacedSleepMs(interval, System.currentTimeMillis() - startedAt))
            }
        }
    }

    /**
     * Command polling, on its own thread. Kept at [BASE_POLL_MS] regardless of fast mode:
     * grants last far longer than one poll, so there is nothing to gain by polling faster —
     * and the status loop no longer waits on this round trip.
     */
    private suspend fun commandLoop(
        ok: OkHttpClient,
        control: DiParsControlClient,
        confPath: String,
    ) {
        var backoffMs = BASE_POLL_MS
        while (true) {
            val conf = loadConf(confPath)
            if (conf == null) {
                Thread.sleep(BASE_POLL_MS)
                continue
            }
            val waited = try {
                val result = pollOnce(ok, conf, control, latestData)
                backoffMs = if (result) BASE_POLL_MS else min(backoffMs * 2, MAX_BACKOFF_MS)
                if (result) BASE_POLL_MS else backoffMs
            } catch (e: Exception) {
                log("poll error: ${e.message}")
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                backoffMs
            }
            Thread.sleep(waited)
        }
    }

    /** @return true on a clean poll (HTTP ok), false to trigger backoff. */
    private suspend fun pollOnce(
        ok: OkHttpClient,
        conf: Conf,
        control: DiParsControlClient,
        data: DiParsData?,
    ): Boolean {
        val httpUrl = conf.commandsUrl.toHttpUrlOrNull() ?: return false
        val request = Request.Builder()
            .url(httpUrl)
            .header("X-API-Key", conf.apiKey)
            .header("X-Vehicle-Id", conf.vehicleId)
            .header("X-App", "VoltFlow-Mate-Daemon")
            .get()
            .build()

        ok.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log("poll HTTP ${response.code}")
                return false
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            // Someone has the live view open. Read before the empty-queue return — an idle
            // command queue is the normal case and must not skip the grant. Absent on older
            // servers, which reads as 0 and leaves the current window to lapse.
            val grantSeconds = json.optInt("live_fast_seconds", 0)
            if (grantSeconds > 0) {
                liveFastUntilMs = System.currentTimeMillis() + grantSeconds * 1000L
            }
            val commands = json.optJSONArray("commands") ?: JSONArray()
            if (commands.length() == 0) return true

            log("received ${commands.length()} command(s)")
            val acks = JSONArray()
            for (i in 0 until commands.length()) {
                val cmd = commands.getJSONObject(i)
                val id = cmd.getString("id")
                val type = cmd.getString("type")
                val params = jsonObjectToMap(cmd.optJSONObject("params") ?: JSONObject())
                acks.put(executeCommand(control, data, id, type, params))
            }
            postAck(ok, conf, acks)
        }
        return true
    }

    private suspend fun executeCommand(
        control: DiParsControlClient,
        data: DiParsData?,
        id: String,
        type: String,
        params: Map<String, Any?>,
    ): JSONObject {
        CommandAllowlist.movementBlockReason(data)?.let { return ack(id, "rejected", mapOf("error" to it)) }
        CommandAllowlist.auxVoltageBlockReason(data)?.let { return ack(id, "rejected", mapOf("error" to it)) }

        return when (val built = CommandAllowlist.buildPhrase(type, params)) {
            is CommandAllowlist.BuildResult.Rejected ->
                ack(id, "rejected", mapOf("error" to built.reason))
            is CommandAllowlist.BuildResult.Ok -> {
                val sent = control.sendAllowlistedPhrase(built.phrase)
                if (sent) {
                    log("executed '$type' → '${built.phrase}'")
                    ack(id, "done", mapOf("phrase" to built.phrase, "verified" to false))
                } else {
                    log("FAILED '$type' → '${built.phrase}'")
                    ack(id, "failed", mapOf("error" to "sendCmd_failed", "phrase" to built.phrase))
                }
            }
        }
    }

    private fun postAck(ok: OkHttpClient, conf: Conf, acks: JSONArray) {
        try {
            val payload = JSONObject().put("acks", acks).toString()
            val request = Request.Builder()
                .url(conf.ackUrl)
                .header("Content-Type", "application/json")
                .header("X-API-Key", conf.apiKey)
                .header("X-Vehicle-Id", conf.vehicleId)
                .header("X-App", "VoltFlow-Mate-Daemon")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            ok.newCall(request).execute().use { log("ack HTTP ${it.code} (${acks.length()} items)") }
        } catch (e: Exception) {
            log("ack failed: ${e.message}")
        }
    }

    private fun ack(id: String, status: String, result: Map<String, Any?>): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("status", status)
            put("result", JSONObject(result))
        }

    /**
     * Read FID_CHARGING_CAPACITY (dev=1009, fid=666894360) from the autoservice Binder
     * via a shell exec. The daemon runs as shell uid, so `service call autoservice` is
     * accessible without ADB or Context — the same privilege path as [AdbOnDeviceClient].
     * Returns null on sentinel (-1.0f / NaN / Infinite) or any exec failure.
     */
    /**
     * BMS State of Health, percent (DEV_STATISTIC=1014, FID_SOH=1145045032, TX_GET_INT=5).
     * Validates 0..100; collapses known sentinels to null.
     */
    private fun readSohPercent(): Int? = try {
        val proc = Runtime.getRuntime().exec("service call autoservice 5 i32 1014 i32 1145045032")
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val v = PARCEL_REGEX.find(out)?.groupValues?.getOrNull(1)?.toLong(16)?.toInt()
            ?: return null
        when (v) {
            0x0000FFFF, 0x000FFFFF, -10013, -10011 -> null
            else -> if (v in 0..100) v else null
        }
    } catch (_: Exception) {
        null
    }

    private fun readKwhCharged(): Float? = try {
        val proc = Runtime.getRuntime().exec("service call autoservice 7 i32 1009 i32 666894360")
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val bits = PARCEL_REGEX.find(out)?.groupValues?.getOrNull(1)?.toLong(16)?.toInt()
            ?: return null
        val f = java.lang.Float.intBitsToFloat(bits)
        if (f.isNaN() || f.isInfinite() || f == -1.0f) null else f
    } catch (_: Exception) {
        null
    }

    /**
     * Live SOC via autoservice directly (DEV_STATISTIC=1014, FID_SOC=1246777400, TX_GET_FLOAT=7)
     * — same fid as [com.bydmate.app.data.autoservice.FidRegistry.FID_SOC]. Read-only, used only
     * to log a parity check against di+'s SOC while evaluating dropping the di+ dependency
     * (docs/EV_PRO_APP_ANALYSIS.md, backlog B-07). Never fed into the cloud payload.
     */
    private fun readSocPercentAutoservice(): Float? = try {
        val proc = Runtime.getRuntime().exec("service call autoservice 7 i32 1014 i32 1246777400")
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val bits = PARCEL_REGEX.find(out)?.groupValues?.getOrNull(1)?.toLong(16)?.toInt()
            ?: return null
        val f = java.lang.Float.intBitsToFloat(bits)
        if (f.isNaN() || f.isInfinite() || f !in 0f..100f) null else f
    } catch (_: Exception) {
        null
    }

    /**
     * Live engine power (kW) via autoservice directly (DEV_ENGINE=1012, FID_ENGINE_POWER=339738656,
     * TX_GET_INT=5) — same fid di+'s 发动机功率 ultimately reads. Read-only parity-check twin of
     * [readSocPercentAutoservice]; see that doc for why. Sanity envelope matches
     * IternioTelemetryClient's [-300, +500] range, tightened to the ±350 the FidRegistry doc note
     * already used for this fid.
     */
    private fun readEnginePowerKwAutoservice(): Int? = try {
        val proc = Runtime.getRuntime().exec("service call autoservice 5 i32 1012 i32 339738656")
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val v = PARCEL_REGEX.find(out)?.groupValues?.getOrNull(1)?.toLong(16)?.toInt()
            ?: return null
        if (kotlin.math.abs(v) > 350) null else v
    } catch (_: Exception) {
        null
    }

    /** Runs `service call autoservice 5 i32 <dev> i32 <fid>` and decodes the raw int, or null. */
    private fun readAutoserviceIntFid(dev: Int, fid: Int): Int? = try {
        val proc = Runtime.getRuntime().exec("service call autoservice 5 i32 $dev i32 $fid")
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        PARCEL_REGEX.find(out)?.groupValues?.getOrNull(1)?.toLong(16)?.toInt()
    } catch (_: Exception) {
        null
    }

    /**
     * Door/trunk/hood open state via autoservice directly (dev=1001, transact 5) — same fids as
     * [com.bydmate.app.data.autoservice.FidRegistry.FID_DOOR_FL] and friends (CANFD branch,
     * live-validated against di+ 2026-07-22, see docs/BACKLOG.md B-07). Read-only parity check,
     * never fed into the cloud payload — same rationale as [readSocPercentAutoservice]. Returns
     * a compact "fl/fr/rl/rr/trunk/hood" string (each 0/1, "?" on read failure) to keep the log
     * line short rather than six separate fields.
     */
    private fun readBodyworkOpenStatesAutoservice(): String {
        fun bit(fid: Int) = readAutoserviceIntFid(1001, fid)?.toString() ?: "?"
        return "${bit(692060168)}/${bit(692060170)}/${bit(692060172)}/${bit(692060174)}/" +
            "${bit(692060186)}/${bit(692060188)}"
    }

    /**
     * Tire pressure (kPa) via autoservice directly (dev=1001, transact 5) — same fids as
     * [com.bydmate.app.data.autoservice.FidRegistry.FID_TIRE_PRESSURE_FL] and friends (not
     * platform-conditional, live-validated against di+ 2026-07-22).
     */
    private fun readTirePressuresAutoservice(): String {
        fun v(fid: Int) = readAutoserviceIntFid(1001, fid)?.toString() ?: "?"
        return "${v(-1728052956)}/${v(-1728052952)}/${v(-1728052948)}/${v(-1728052944)}"
    }

    /**
     * Re-asserts WiFi via shell-uid `svc wifi enable` — idempotent no-op if already on. Never
     * throws: a failure here must not take down the daemon's poll/telemetry loops.
     */
    private fun refreshWifiKeepalive() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "svc wifi enable"))
            proc.waitFor()
            log("wifi keepalive: svc wifi enable (exit=${proc.exitValue()})")
        } catch (e: Exception) {
            log("wifi keepalive failed: ${e.message}")
        }
    }

    /** Push one telemetry sample to the cloud ingest endpoint (contract: docs/cloud-telemetry-contract-ru.md). */
    private fun pushTelemetry(ok: OkHttpClient, conf: Conf, data: DiParsData, liveOnly: Boolean = false) {
        try {
            val kwhCharged = readKwhCharged()
            val sohPercent = readSohPercent()
            // Parity check only — logged, never sent to the cloud. See readSocPercentAutoservice.
            val autoserviceSoc = readSocPercentAutoservice()
            val autoservicePowerKw = readEnginePowerKwAutoservice()
            if (autoserviceSoc != null || autoservicePowerKw != null) {
                log(
                    "autoservice check: soc=$autoserviceSoc (diplus=${data.soc}) " +
                        "power_kw=$autoservicePowerKw (diplus=${data.power})"
                )
            }
            val bodyworkStates = readBodyworkOpenStatesAutoservice()
            val tirePressures = readTirePressuresAutoservice()
            log(
                "autoservice check2: doors/trunk/hood(fl/fr/rl/rr/trunk/hood)=$bodyworkStates " +
                    "(diplus=${data.doorFL}/${data.doorFR}/${data.doorRL}/${data.doorRR}/${data.trunk}/${data.hood}) " +
                    "tires_kpa(fl/fr/rl/rr)=$tirePressures " +
                    "(diplus=${data.tirePressFL}/${data.tirePressFR}/${data.tirePressRL}/${data.tirePressRR})"
            )
            val payload = buildTelemetryPayload(conf.vehicleId, data, kwhCharged, sohPercent, liveOnly).toString()
            val request = Request.Builder()
                .url(conf.telemetryUrl)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-API-Key", conf.apiKey)
                .header("X-Vehicle-Id", conf.vehicleId)
                .header("X-App", "VoltFlow-Mate-Daemon")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            ok.newCall(request).execute().use {
                val mode = if (liveOnly) " live_only" else ""
                log("telemetry HTTP ${it.code} (soc=${data.soc} pwr_state=${data.powerState}$mode)")
            }
        } catch (e: Exception) {
            log("telemetry push failed: ${e.message}")
        }
    }

    private fun buildTelemetryPayload(
        vehicleId: String,
        d: DiParsData,
        kwhCharged: Float? = null,
        sohPercent: Int? = null,
        liveOnly: Boolean = false,
    ): JSONObject {
        val cellDelta = if (d.maxCellVoltage != null && d.minCellVoltage != null) {
            d.maxCellVoltage!! - d.minCellVoltage!!
        } else {
            null
        }
        val gun = d.chargeGunState
        val isCharging = (d.chargingStatus != null && d.chargingStatus!! > 0) ||
            (gun != null && gun in 2..5)

        val diplus = JSONObject().apply {
            putN("soc", d.soc); putN("speed_kmh", d.speed); putN("mileage_km", d.mileage)
            putN("power_kw", d.power); putN("charge_gun_state", d.chargeGunState)
            putN("max_battery_temp_c", d.maxBatTemp); putN("avg_battery_temp_c", d.avgBatTemp)
            putN("min_battery_temp_c", d.minBatTemp); putN("charging_status", d.chargingStatus)
            putN("battery_capacity_kwh", d.batteryCapacityKwh)
            putN("total_elec_consumption_kwh", d.totalElecConsumption)
            putN("voltage_12v", d.voltage12v); putN("max_cell_voltage_v", d.maxCellVoltage)
            putN("min_cell_voltage_v", d.minCellVoltage); putN("cell_delta_v", cellDelta)
            putN("exterior_temp_c", d.exteriorTemp); putN("gear", d.gear); putN("power_state", d.powerState)
            putN("inside_temp_c", d.insideTemp); putN("ac_status", d.acStatus); putN("ac_temp_c", d.acTemp)
            putN("fan_level", d.fanLevel); putN("ac_circ", d.acCirc)
            putN("door_fl", d.doorFL); putN("door_fr", d.doorFR); putN("door_rl", d.doorRL); putN("door_rr", d.doorRR)
            putN("window_fl_percent", d.windowFL); putN("window_fr_percent", d.windowFR)
            putN("window_rl_percent", d.windowRL); putN("window_rr_percent", d.windowRR)
            putN("sunroof_percent", d.sunroof); putN("trunk", d.trunk); putN("hood", d.hood)
            putN("seatbelt_fl", d.seatbeltFL); putN("lock_fl", d.lockFL)
            putN("tire_press_fl_kpa", d.tirePressFL); putN("tire_press_fr_kpa", d.tirePressFR)
            putN("tire_press_rl_kpa", d.tirePressRL); putN("tire_press_rr_kpa", d.tirePressRR)
            putN("drive_mode", d.driveMode); putN("work_mode", d.workMode); putN("auto_park", d.autoPark)
            putN("rain", d.rain); putN("light_low", d.lightLow); putN("drl", d.drl)
            putN("sunshade_percent", d.sunshade)
            putN("sentry_state", d.sentryState); putN("remote_lock_state", d.remoteLockState)
            putN("stall_sentry_mode", d.stallSentryMode)
        }

        val telemetry = JSONObject().apply {
            putN("soc", d.soc); putN("speed_kmh", d.speed?.toDouble()); putN("power_kw", d.power)
            putN("battery_temp_c", d.avgBatTemp?.toDouble()); putN("cabin_temp_c", d.insideTemp?.toDouble())
            putN("outside_temp_c", d.exteriorTemp?.toDouble()); putN("aux_voltage_v", d.voltage12v)
            putN("cell_voltage_min_v", d.minCellVoltage); putN("cell_voltage_max_v", d.maxCellVoltage)
            putN("cell_delta_v", cellDelta); putN("odometer_km", d.mileage)
            put("is_charging", isCharging)
            putN("charge_power_kw", if (isCharging) d.power?.let { kotlin.math.abs(it) } else null)
            putN("kwh_charged", if (isCharging) kwhCharged?.toDouble() else null)
            putN("charge_type", if (isCharging) when (gun) { 2 -> "AC"; in 3..5 -> "DC"; else -> null } else null)
            put("is_parked", d.gear == 1)
            putN("soh_percent", sohPercent?.toDouble())
        }

        return JSONObject().apply {
            put("schema_version", 1)
            put("vehicle_id", vehicleId)
            put("device_time", isoNow())
            put("source", "BYDMate")
            put("mate_version", BuildConfig.VERSION_NAME)
            // Parked heartbeat with nothing material changed: server refreshes live state only.
            // Omitted (not false) when unset so a normal sample keeps its exact current shape.
            if (liveOnly) put("live_only", true)
            put("telemetry", telemetry)
            put("diplus", diplus)
            // location is required by the ingest schema; the daemon has no GPS → empty (fields are nullable).
            put("location", JSONObject())
        }
    }

    /**
     * How long ago VoltFlow Mate last wrote its liveness beacon, or null when there is no
     * readable beacon at all. The whole impure part of the app-alive gate lives here; the
     * decision is [shouldDeferToApp].
     */
    private fun beaconAgeMs(now: Long): Long? = try {
        File(APP_HEARTBEAT_FILE).takeIf { it.exists() }
            ?.readText()?.trim()?.toLongOrNull()
            ?.let { now - it }
    } catch (_: Exception) {
        null
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun JSONObject.putN(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    /** Same regex as AutoserviceClientImpl — parses `Result: Parcel(00000000 <8hex> ...)`. */
    private val PARCEL_REGEX = Regex("""Parcel\(00000000\s+([0-9a-fA-F]{8})""")

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.get(key)
        }
        return map
    }

    /**
     * Config file is simple `key=value` lines:
     *   url=https://<host>/api/bydmate/telemetry   (or .../commands — either is accepted)
     *   api_key=...
     *   vehicle_id=...
     * Returns null (caller retries) if the file is missing/incomplete.
     */
    private fun loadConf(path: String): Conf? {
        val file = File(path)
        if (!file.isFile) return null
        val props = HashMap<String, String>()
        runCatching {
            file.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEachLine
                val idx = t.indexOf('=')
                if (idx > 0) props[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
            }
        }.onFailure { return null }

        val rawUrl = props["url"]?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = props["api_key"]?.takeIf { it.isNotBlank() } ?: return null
        val vehicleId = props["vehicle_id"]?.takeIf { it.isNotBlank() } ?: return null
        val commandsUrl = commandsUrlFromTelemetry(rawUrl) ?: return null
        val telemetryUrl = telemetryUrlFromAny(rawUrl) ?: return null
        return Conf(
            commandsUrl = commandsUrl,
            ackUrl = "$commandsUrl/ack",
            telemetryUrl = telemetryUrl,
            apiKey = apiKey,
            vehicleId = vehicleId,
            keepWifiAwake = props["keep_wifi_awake"] == "1",
        )
    }

    /** Mirror of VehicleCommandPoller.commandsUrlFromTelemetry — keep in sync. */
    private fun commandsUrlFromTelemetry(telemetryUrl: String): String? {
        val trimmed = telemetryUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/commands") -> trimmed
            trimmed.endsWith("/telemetry") -> trimmed.removeSuffix("/telemetry") + "/commands"
            trimmed.contains("/api/bydmate") -> trimmed.substringBeforeLast('/') + "/commands"
            else -> null
        }
    }

    /** Normalize the configured base URL to the `…/api/bydmate/telemetry` ingest endpoint. */
    private fun telemetryUrlFromAny(url: String): String? {
        val trimmed = url.trimEnd('/')
        return when {
            trimmed.endsWith("/telemetry") -> trimmed
            trimmed.endsWith("/commands") -> trimmed.removeSuffix("/commands") + "/telemetry"
            trimmed.contains("/api/bydmate") -> trimmed.substringBeforeLast('/') + "/telemetry"
            else -> null
        }
    }
}
