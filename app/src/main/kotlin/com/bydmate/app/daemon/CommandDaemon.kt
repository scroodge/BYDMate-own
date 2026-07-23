package com.bydmate.app.daemon

import com.bydmate.app.BuildConfig
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.IternioIntervalPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Headless direct-telemetry daemon that preserves the APK's wake/sleep-survival path.
 *
 * Why this exists: the in-app poller runs inside the `com.bydmate.app` process, which BYD's
 * power-off routine (`collectPowerOffEvent` → force-stop) kills when the head unit parks/sleeps.
 * This class is launched as a shell-uid `app_process` daemon (see `tools/start_voltflow_cmd.sh`),
 * which survives the force-stop exactly like DI+ (`aps_diplus`) does.
 *
 * It needs NO Android Context. Telemetry comes directly from the BYD autoservice
 * Binder; it never connects to DiPlus. Config (cloud URL / API key / vehicle ID)
 * comes from a properties-style file instead of SettingsRepository.
 *
 * Launch:  CLASSPATH=<base.apk> app_process /system/bin --nice-name=voltflow_cmd_daemon \
 *              com.bydmate.app.daemon.CommandDaemon [confPath]
 *
 * Remote-command polling is deliberately absent. The foreground APK and this daemon
 * therefore have no runtime dependency on DiPlus.
 */
object CommandDaemon {

    private const val DEFAULT_CONF = "/data/local/tmp/voltflow_cmd.conf"
    private const val BASE_POLL_MS = 6000L
    /** How often to refresh telemetry used for movement/voltage guards. */
    private const val TELEMETRY_TTL_MS = 5_000L

    /**
     * How stale `latestData` may be before di+ is judged unreachable and the autoservice-only
     * fallback becomes eligible (subject to [shouldUseAutoserviceFallback]). A failed
     * `diPars.fetch()` never nulls out `latestData` — it just stops refreshing it — so without
     * this bound the daemon would silently keep re-pushing an increasingly stale di+ snapshot
     * forever instead of ever noticing di+ is down. 3x [TELEMETRY_TTL_MS]: tolerates a couple of
     * transient fetch failures before treating it as a real outage.
     */
    private const val DIPLUS_STALE_MS = TELEMETRY_TTL_MS * 3

    /**
     * How long di+'s reported values may sit byte-identical, while there is positive evidence the
     * car isn't actually static, before [isDiPlusValueStale] flags it. Distinct from
     * [DIPLUS_STALE_MS]: that one catches `fetch()` failing; this one catches `fetch()` *succeeding
     * with a frozen answer* — the failure mode observed live on 2026-07-23 (di+ reported
     * `soc=55, power=0.0` unchanged for 11+ minutes during an active charge). 3 minutes: long
     * enough that normal poll jitter or a momentary di+ hiccup can't trip it.
     */
    private const val DIPLUS_VALUE_STALE_MS = 180_000L

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
     * Whether it's safe to push the autoservice-only fallback when di+ is unreachable — parked
     * or charging only, never a guess. The fallback has no `gear`/`speed` of its own, so pushing
     * it while actually driving would be a reduced-payload sample mid-drive, the same class of
     * bug the DRIVING guard above (`state == DRIVING -> skip`) already exists to prevent.
     * `gunState` is normally di+'s *last known* state before it went dark; when di+ has never
     * answered this run at all, the caller instead passes a fresh direct autoservice gun-state
     * read (gear has no autoservice equivalent, so a never-seen di+ still can't clear the gear
     * check). Both null stays false: err toward silence, not a guess, until we have real
     * evidence the car is parked/charging.
     */
    internal fun shouldUseAutoserviceFallback(lastKnownGear: Int?, gunState: Int?): Boolean =
        lastKnownGear == 1 || (gunState != null && gunState in 2..5)

    /**
     * One round of autoservice reads taken together, so a single push sees a consistent view and
     * the same values feed both the payload and the parity log line. Every field is nullable
     * because each underlying read returns null on any failure — that is what lets the merge in
     * [buildTelemetryPayload] fall back to di+ *field by field* rather than all-or-nothing.
     */
    internal data class AutoserviceSnapshot(
        val socPercent: Int? = null,
        val powerKw: Int? = null,
        val gun: Int? = null,
        val chargingType: Int? = null,
        /** Raw BMS state. Diagnostic only; it is not di+'s ChargingStatus encoding. */
        val chargingBmsState: Int? = null,
        val voltage12v: Double? = null,
        val doorFL: Int? = null, val doorFR: Int? = null,
        val doorRL: Int? = null, val doorRR: Int? = null,
        val trunk: Int? = null, val hood: Int? = null,
        val tireFL: Int? = null, val tireFR: Int? = null,
        val tireRL: Int? = null, val tireRR: Int? = null,
        val sohPercent: Int? = null,
        val kwhCharged: Float? = null,
    )

    /**
     * Whether the car is charging. **The autoservice gun state decides alone whenever it is
     * readable**; [diPlusChargingStatus] is consulted only when autoservice gave us nothing.
     *
     * The old formula OR-ed the two (`chargingStatus > 0 || gun in 2..5`), which latches: di+ is
     * known to freeze its values while parked/charging (2026-07-23 field evidence — `soc` and
     * `power` static for 11 min while the pack actually charged), and a frozen `chargingStatus > 0`
     * would then keep reporting "charging" forever after the gun was physically unplugged.
     */
    internal fun isChargingFrom(gun: Int?, diPlusChargingStatus: Int?): Boolean =
        if (gun != null) gun in 2..5 else (diPlusChargingStatus != null && diPlusChargingStatus > 0)

    /**
     * Whether the car is parked. Gear is authoritative when known (`1` = P); only when di+ is down
     * — gear has no autoservice equivalent — does this fall back to "not charging".
     *
     * The two payload builders used to disagree here: the di+ path said `gear == 1` while the
     * autoservice fallback said `!isCharging`, so a car parked *and* charging was reported parked
     * by one and not-parked by the other.
     */
    internal fun isParkedFrom(gear: Int?, isCharging: Boolean): Boolean =
        if (gear != null) gear == 1 else !isCharging

    /**
     * AC/DC label from a charge-type code. Both sources use the same 2 = AC / 3..5 = DC encoding
     * but read *different* fids — autoservice `FID_CHARGING_TYPE` vs di+ `chargeGunState` — so the
     * caller passes whichever it has rather than this function picking a source.
     */
    internal fun chargeTypeFrom(code: Int?): String? = when (code) {
        2 -> "AC"
        in 3..5 -> "DC"
        else -> null
    }

    /**
     * Cheap change-detector for di+: the fields that must move whenever the car is doing
     * anything (charging, driving, or even just sitting on a slowly draining 12V battery).
     * Byte-identical signatures across polls, held long enough, are the "fetch() succeeds but
     * the answer never changes" failure mode [DIPLUS_VALUE_STALE_MS] exists to catch.
     */
    internal fun diPlusValueSignature(d: DiParsData): String =
        "${d.soc}|${d.power}|${d.mileage}|${d.voltage12v}|${d.chargeGunState}"

    /**
     * Whether di+'s current signature looks frozen rather than genuinely idle. Two conditions,
     * both required:
     *  1. the signature has held for at least [DIPLUS_VALUE_STALE_MS] ([signatureUnchangedSinceMs]
     *     is when it last *changed*, so `now - signatureUnchangedSinceMs` is how long it's held), and
     *  2. positive evidence the car isn't actually static — either [charging] is true (autoservice's
     *     gun state says power is flowing, so di+'s numbers *should* be moving), or autoservice's
     *     SOC has changed more recently than the signature did ([autoserviceSocMovedSinceMs], null
     *     if it has never been observed to move).
     *
     * Without clause 2, a genuinely parked, non-charging car with a flat SOC would trip this for
     * every long park — a 13-hour overnight window in the field corpus had di+ static that whole
     * time for exactly that legitimate reason.
     */
    internal fun isDiPlusValueStale(
        now: Long,
        signatureUnchangedSinceMs: Long,
        autoserviceSocMovedSinceMs: Long?,
        charging: Boolean,
    ): Boolean {
        val frozenLongEnough = now - signatureUnchangedSinceMs >= DIPLUS_VALUE_STALE_MS
        val hasMovementEvidence = charging ||
            (autoserviceSocMovedSinceMs != null && autoserviceSocMovedSinceMs > signatureUnchangedSinceMs)
        return frozenLongEnough && hasMovementEvidence
    }

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
        // Last gear/gun state exposed to the command guard. In the direct-only
        // branch gear remains null until a safe autoservice fid is validated; the
        // guard then fails closed rather than consulting di+.
        // `latestData` itself is never cleared to
        // null once di+ has answered once — a failed fetch() just leaves it stale — so these
        // are captured separately at the moment of each successful fetch and read back once
        // di+ is judged unreachable (see DIPLUS_STALE_MS below). Gates the autoservice-only
        // fallback — see shouldUseAutoserviceFallback. Both stay null for the life of the
        // process if di+ never answers even once (e.g. it's not running on this car at all);
        // that cold-start case is covered separately by a live autoservice read at the fallback
        // call site, not by these two.
        var lastKnownGear: Int? = null
        var lastKnownGunState: Int? = null
        // Step 2 (log-only) staleness tracking — see isDiPlusValueStale. Only touched while di+ is
        // fresh, since that's the only time there is a di+ signature to compare against.
        var lastDiPlusSignature: String? = null
        var diPlusSignatureUnchangedSinceMs: Long = 0L
        var lastAutoserviceSocForStaleness: Int? = null
        var autoserviceSocMovedAtMs: Long? = null

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

                    // Direct-engine-only telemetry. The DiPars-shaped object is a
                    // compatibility carrier for the existing command guard; it is
                    // populated exclusively from autoservice and leaves unknown
                    // fields null.
                    if (now - latestDataAt > TELEMETRY_TTL_MS) {
                        val direct = readAutoserviceSnapshot()
                        latestData = direct.toSafetyData()
                        latestDataAt = now
                        lastKnownGear = null
                        lastKnownGunState = direct.gun
                    }
                    val directFresh = latestData != null && now - latestDataAt <= DIPLUS_STALE_MS

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
                        // Read once per push attempt: feeds the merged payload in both branches
                        // below, and (per buildTelemetryPayload's precedence) autoservice wins the
                        // shared fields whenever di+ is fresh too, not only when di+ is down.
                        val autoSnap = readAutoserviceSnapshot()
                        val pushed = latestData.takeIf { directFresh }?.let { data ->
                            // Step 2, log-only: detect di+ answering with a frozen value (as
                            // opposed to not answering at all, which diPlusFresh already covers).
                            val sig = diPlusValueSignature(data)
                            if (sig != lastDiPlusSignature) {
                                lastDiPlusSignature = sig
                                diPlusSignatureUnchangedSinceMs = now
                            }
                            if (autoSnap.socPercent != null && autoSnap.socPercent != lastAutoserviceSocForStaleness) {
                                autoserviceSocMovedAtMs = now
                                lastAutoserviceSocForStaleness = autoSnap.socPercent
                            }
                            val chargingNow = isChargingFrom(autoSnap.gun, data.chargingStatus)
                            if (isDiPlusValueStale(now, diPlusSignatureUnchangedSinceMs, autoserviceSocMovedAtMs, chargingNow)) {
                                val frozenMin = (now - diPlusSignatureUnchangedSinceMs) / 60_000
                                log("di+ value-stale=true (sig frozen ${frozenMin}m, autoservice soc=${autoSnap.socPercent})")
                            }
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
                                    pushTelemetry(ok, conf, data, autoSnap, liveOnly)
                                    true
                                }
                            }
                        } ?: run {
                            // di+ is down or stuck stale (diPlusFresh == false). Only push an
                            // autoservice-only sample when there's real evidence the car is
                            // parked/charging — see shouldUseAutoserviceFallback for why this
                            // never guesses during a drive. Normally that evidence is di+'s last
                            // known state before it went dark. But if di+ has never answered at
                            // all this run (lastKnownGear/lastKnownGunState both still null —
                            // e.g. right after a daemon restart on a car where di+ never comes
                            // up), the last-known state can never clear the gate either, so the
                            // daemon would stay silent forever even while genuinely
                            // parked/charging. In that specific case only, the just-read
                            // autoSnap.gun stands in as the evidence instead.
                            val neverSeenDiPlus = lastKnownGear == null && lastKnownGunState == null
                            val liveGunState = if (neverSeenDiPlus) autoSnap.gun else null
                            when {
                                shouldDeferToApp(beaconAgeMs(now), liveOnly = false) -> {
                                    logSkip(now, "app alive — VoltFlow Mate is sending")
                                    false
                                }
                                shouldUseAutoserviceFallback(lastKnownGear, lastKnownGunState ?: liveGunState) -> {
                                    pushTelemetry(ok, conf, data = null, auto = autoSnap, liveOnly = false)
                                    true
                                }
                                else -> {
                                    logSkip(now, "di+ unreachable, last known state not parked/charging")
                                    false
                                }
                            }
                        }
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
     * — same fid as [com.bydmate.app.data.autoservice.FidRegistry.FID_SOC]. Primary source of
     * `soc` in [buildTelemetryPayload] as of 2026-07-23 (backlog B-07): a 1236-sample parity
     * corpus off this car showed di+ freezing this exact value for 11+ minutes during a live
     * charge while this read kept tracking reality, confirmed against the dash (autoservice
     * 74%/dash 75% vs di+'s stale 55%).
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
     * TX_GET_INT=5) — same fid di+'s 发动机功率 ultimately reads. Primary source of `power_kw` in
     * [buildTelemetryPayload]; see [readSocPercentAutoservice] for the promotion rationale.
     * Sanity envelope matches IternioTelemetryClient's [-300, +500] range, tightened to the ±350
     * the FidRegistry doc note already used for this fid.
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

    /** Runs `service call autoservice 7 i32 <dev> i32 <fid>` and decodes the raw float, or null. */
    private fun readAutoserviceFloatFid(dev: Int, fid: Int): Double? = try {
        val proc = Runtime.getRuntime().exec("service call autoservice 7 i32 $dev i32 $fid")
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val bits = PARCEL_REGEX.find(out)?.groupValues?.getOrNull(1)?.toLong(16)?.toInt() ?: return null
        val f = java.lang.Float.intBitsToFloat(bits)
        if (f.isNaN() || f.isInfinite()) null else f.toDouble()
    } catch (_: Exception) {
        null
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

    /**
     * Push one direct-engine telemetry sample to the cloud ingest endpoint.
     * [data] is a command-safety carrier only and is never serialized as di+.
     */
    private fun pushTelemetry(
        ok: OkHttpClient,
        conf: Conf,
        data: DiParsData?,
        auto: AutoserviceSnapshot,
        liveOnly: Boolean = false,
    ) {
        try {
            val payload = buildTelemetryPayload(conf.vehicleId, data, auto, liveOnly).toString()
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
                log("telemetry HTTP ${it.code} (direct soc=${auto.socPercent} power=${auto.powerKw}$mode)")
            }
        } catch (e: Exception) {
            log("telemetry push failed: ${e.message}")
        }
    }

    /** Takes one consistent round of autoservice reads. See [AutoserviceSnapshot]. */
    private fun readAutoserviceSnapshot(): AutoserviceSnapshot = AutoserviceSnapshot(
        socPercent = readSocPercentAutoservice()?.toInt(),
        powerKw = readEnginePowerKwAutoservice(),
        gun = readAutoserviceIntFid(1009, 876609586), // FID_GUN_CONNECT_STATE
        chargingType = readAutoserviceIntFid(1009, 876609592), // FID_CHARGING_TYPE
        chargingBmsState = readAutoserviceIntFid(1009, 876609560), // FID_CHARGING_BMS_STATE
        voltage12v = readAutoserviceFloatFid(1001, 1128267816), // FID_OTA_BATTERY_POWER_VOLTAGE
        doorFL = readAutoserviceIntFid(1001, 692060168),
        doorFR = readAutoserviceIntFid(1001, 692060170),
        doorRL = readAutoserviceIntFid(1001, 692060172),
        doorRR = readAutoserviceIntFid(1001, 692060174),
        trunk = readAutoserviceIntFid(1001, 692060186),
        hood = readAutoserviceIntFid(1001, 692060188),
        tireFL = readAutoserviceIntFid(1001, -1728052956),
        tireFR = readAutoserviceIntFid(1001, -1728052952),
        tireRL = readAutoserviceIntFid(1001, -1728052948),
        tireRR = readAutoserviceIntFid(1001, -1728052944),
        sohPercent = readSohPercent(),
        kwhCharged = readKwhCharged(),
    )

    /**
     * Compatibility carrier for command safety only. It contains no di+ read:
     * unsupported speed and gear are deliberately null, which makes the
     * allowlist reject commands unless direct charging evidence is available.
     */
    private fun AutoserviceSnapshot.toSafetyData(): DiParsData = DiParsData(
        soc = socPercent,
        speed = null,
        mileage = null,
        power = powerKw?.toDouble(),
        chargeGunState = gun,
        maxBatTemp = null,
        avgBatTemp = null,
        minBatTemp = null,
        chargingStatus = null,
        batteryCapacityKwh = null,
        totalElecConsumption = null,
        voltage12v = voltage12v,
        maxCellVoltage = null,
        minCellVoltage = null,
        exteriorTemp = null,
        gear = null,
        powerState = null,
        insideTemp = null,
        acStatus = null,
        acTemp = null,
        fanLevel = null,
        acCirc = null,
        doorFL = doorFL,
        doorFR = doorFR,
        doorRL = doorRL,
        doorRR = doorRR,
        windowFL = null,
        windowFR = null,
        windowRL = null,
        windowRR = null,
        sunroof = null,
        trunk = trunk,
        hood = hood,
        seatbeltFL = null,
        lockFL = null,
        tirePressFL = tireFL,
        tirePressFR = tireFR,
        tirePressRL = tireRL,
        tirePressRR = tireRR,
        driveMode = null,
        workMode = null,
        autoPark = null,
        rain = null,
        lightLow = null,
        drl = null,
        sunshade = null,
        sentryState = null,
        remoteLockState = null,
    )

    /**
     * Builds a direct-engine-only cloud telemetry payload.
     *
     * Every unsupported direct field is emitted as JSON null/omitted; it is never
     * filled from di+. [d] remains only for binary-compatible unit-test callers
     * and must be null in production telemetry paths.
     * @param mateVersion injectable so unit tests need not touch [BuildConfig].
     */
    internal fun buildTelemetryPayload(
        vehicleId: String,
        d: DiParsData?,
        auto: AutoserviceSnapshot?,
        liveOnly: Boolean = false,
        mateVersion: String = BuildConfig.VERSION_NAME,
    ): JSONObject {
        val cellDelta: Double? = null

        val soc = auto?.socPercent
        val powerKw = auto?.powerKw?.toDouble()
        val gun = auto?.gun
        val voltage12v = auto?.voltage12v
        val doorFL = auto?.doorFL
        val doorFR = auto?.doorFR
        val doorRL = auto?.doorRL
        val doorRR = auto?.doorRR
        val trunk = auto?.trunk
        val hood = auto?.hood
        val tireFL = auto?.tireFL
        val tireFR = auto?.tireFR
        val tireRL = auto?.tireRL
        val tireRR = auto?.tireRR

        val isCharging = isChargingFrom(auto?.gun, null)
        val chargeType = chargeTypeFrom(auto?.chargingType)

        val diplus = JSONObject().apply {
            putN("soc", soc); putN("speed_kmh", d?.speed); putN("mileage_km", d?.mileage)
            putN("power_kw", powerKw); putN("charge_gun_state", gun)
            putN("max_battery_temp_c", d?.maxBatTemp); putN("avg_battery_temp_c", d?.avgBatTemp)
            putN("min_battery_temp_c", d?.minBatTemp); putN("charging_status", d?.chargingStatus)
            putN("battery_capacity_kwh", d?.batteryCapacityKwh)
            putN("total_elec_consumption_kwh", d?.totalElecConsumption)
            putN("voltage_12v", voltage12v); putN("max_cell_voltage_v", d?.maxCellVoltage)
            putN("min_cell_voltage_v", d?.minCellVoltage); putN("cell_delta_v", cellDelta)
            putN("exterior_temp_c", d?.exteriorTemp); putN("gear", d?.gear); putN("power_state", d?.powerState)
            putN("inside_temp_c", d?.insideTemp); putN("ac_status", d?.acStatus); putN("ac_temp_c", d?.acTemp)
            putN("fan_level", d?.fanLevel); putN("ac_circ", d?.acCirc)
            putN("door_fl", doorFL); putN("door_fr", doorFR); putN("door_rl", doorRL); putN("door_rr", doorRR)
            putN("window_fl_percent", d?.windowFL); putN("window_fr_percent", d?.windowFR)
            putN("window_rl_percent", d?.windowRL); putN("window_rr_percent", d?.windowRR)
            putN("sunroof_percent", d?.sunroof); putN("trunk", trunk); putN("hood", hood)
            putN("seatbelt_fl", d?.seatbeltFL); putN("lock_fl", d?.lockFL)
            putN("tire_press_fl_kpa", tireFL); putN("tire_press_fr_kpa", tireFR)
            putN("tire_press_rl_kpa", tireRL); putN("tire_press_rr_kpa", tireRR)
            putN("drive_mode", d?.driveMode); putN("work_mode", d?.workMode); putN("auto_park", d?.autoPark)
            putN("rain", d?.rain); putN("light_low", d?.lightLow); putN("drl", d?.drl)
            putN("sunshade_percent", d?.sunshade)
            putN("sentry_state", d?.sentryState); putN("remote_lock_state", d?.remoteLockState)
            putN("stall_sentry_mode", d?.stallSentryMode)
        }

        val telemetry = JSONObject().apply {
            putN("soc", soc); putN("speed_kmh", d?.speed?.toDouble()); putN("power_kw", powerKw)
            putN("battery_temp_c", d?.avgBatTemp?.toDouble()); putN("cabin_temp_c", d?.insideTemp?.toDouble())
            putN("outside_temp_c", d?.exteriorTemp?.toDouble()); putN("aux_voltage_v", voltage12v)
            putN("cell_voltage_min_v", d?.minCellVoltage); putN("cell_voltage_max_v", d?.maxCellVoltage)
            putN("cell_delta_v", cellDelta); putN("odometer_km", d?.mileage)
            put("is_charging", isCharging)
            putN("charge_power_kw", if (isCharging) powerKw?.let { abs(it) } else null)
            putN("kwh_charged", if (isCharging) auto?.kwhCharged?.toDouble() else null)
            putN("charge_type", if (isCharging) chargeType else null)
            // Gear has no validated direct fid on this vehicle. Keep this
            // unknown instead of deriving a parked state from di+ or silence.
            putN("is_parked", null)
            putN("soh_percent", auto?.sohPercent?.toDouble())
        }

        return JSONObject().apply {
            put("schema_version", 1)
            put("vehicle_id", vehicleId)
            put("device_time", isoNow())
            put("source", "BYDMate")
            put("mate_version", mateVersion)
            // Parked heartbeat with nothing material changed: server refreshes live state only.
            // Omitted (not false) when unset so a normal sample keeps its exact current shape.
            if (liveOnly) put("live_only", true)
            put("telemetry", telemetry)
            // No `diplus` block: all fields in this payload came from the
            // direct autoservice engine. Unsupported direct fields above stay
            // null rather than being mislabelled as di+ telemetry.
            put("autoservice", JSONObject().apply {
                putN("soc_percent", soc)
                putN("power_kw", powerKw)
                putN("gun_state", gun)
                putN("charging_type", auto?.chargingType)
                // Keep the raw BMS code visible for on-car parity capture. It must not be
                // relabelled as di+'s ChargingStatus until their state machines are mapped.
                putN("charging_bms_state", auto?.chargingBmsState)
                putN("voltage_12v", voltage12v)
                putN("soh_percent", auto?.sohPercent)
                putN("charging_capacity_kwh", auto?.kwhCharged)
                putN("door_fl", doorFL); putN("door_fr", doorFR)
                putN("door_rl", doorRL); putN("door_rr", doorRR)
                putN("trunk", trunk); putN("hood", hood)
                putN("tire_press_fl_kpa", tireFL); putN("tire_press_fr_kpa", tireFR)
                putN("tire_press_rl_kpa", tireRL); putN("tire_press_rr_kpa", tireRR)
            })
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
        val telemetryUrl = telemetryUrlFromAny(rawUrl) ?: return null
        return Conf(
            telemetryUrl = telemetryUrl,
            apiKey = apiKey,
            vehicleId = vehicleId,
            keepWifiAwake = props["keep_wifi_awake"] == "1",
        )
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
