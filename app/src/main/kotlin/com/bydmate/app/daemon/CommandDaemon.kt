package com.bydmate.app.daemon

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.cloud.DaemonDurableIngress
import com.bydmate.app.data.cloud.DaemonTelemetrySpool
import com.bydmate.app.data.cloud.ShellContentQueueIpc
import com.bydmate.app.data.remote.CommandAllowlist
import com.bydmate.app.data.remote.CommandPollingCadence
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsControlClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.resolveTelemetrySoc
import com.bydmate.app.domain.SocSource
import com.bydmate.app.domain.ChargingStateClassifier
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
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
    private const val BASE_POLL_MS = CommandPollingCadence.BASE_POLL_MS
    private const val MAX_BACKOFF_MS = 30_000L
    private val durableIngress by lazy {
        DaemonDurableIngress(
            DaemonTelemetrySpool(File(DaemonTelemetrySpool.EXTERNAL_DIRECTORY)),
            ShellContentQueueIpc(),
        )
    }

    /**
     * Next command-poll delay from the server's `poll_after_seconds`, or [BASE_POLL_MS] when the
     * field is absent (older server) or nonsensical. A suspended server uses the shared 5-minute
     * floor so this client can discover when commands are enabled again.
     *
     * This loop is the daemon's most expensive habit by a wide margin: it is not gated on the
     * app being alive and runs whenever the head unit is powered, so at 6s it is ~14.4k cloud
     * invocations per car per day — and while remote commands are suspended every one of them
     * returns an empty list. Server-driven so the cadence can be restored to 6s without an APK
     * release when commands come back. Mirrors `VehicleCommandPoller.pollIntervalMs`.
     */
    internal fun commandPollIntervalMs(
        serverSeconds: Int,
        commandsEnabled: Boolean = true,
    ): Long = CommandPollingCadence.intervalMs(serverSeconds, commandsEnabled)

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
     *  2. positive evidence the car isn't actually static — either [charging] is true (di+'s own
     *     charging signal says power is flowing, so its numbers *should* be moving), or autoservice's
     *     SOC has changed more recently than the signature did ([autoserviceSocMovedSinceMs], null
     *     if it has never been observed to move).
     *
     * Without clause 2, a genuinely parked, non-charging car with a flat SOC would trip this for
     * every long park — a 13-hour overnight window in the field corpus had di+ static that whole
     * time for exactly that legitimate reason. Log-only: this never changes what gets pushed, it
     * only flags a di+ data-quality issue worth knowing about (see [pushTelemetry]).
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
     * The shell daemon has no [android.content.Context], so it cannot construct a normal
     * [android.os.PowerManager.WakeLock]. It can, however, bind to the platform power service
     * directly. The reflected interface keeps the daemon ABI-aware: DiLink 3 uses Android 10's
     * six-argument acquire call, while newer DiLink versions may append arguments.
     *
     * The sysfs nodes remain a compatibility fallback only. They are denied to shell on the
     * validated DiLink 3 unit (EACCES), whereas its `IPowerManager` route was proven on-car.
     */
    private const val WAKE_LOCK_TAG = "voltflow_daemon"
    private const val SHELL_PACKAGE_NAME = "com.android.shell"
    private const val WAKE_LOCK_NODE = "/sys/power/wake_lock"
    private const val WAKE_UNLOCK_NODE = "/sys/power/wake_unlock"

    /**
     * Deep sleep in one loop iteration past which the wake is reported as a platform suspend.
     * 30 s is comfortably above [BASE_POLL_MS] jitter and far below the ~900 s windows measured in
     * the field, so it flags real suspends without narrating normal operation.
     */
    internal const val SUSPEND_REPORT_THRESHOLD_MS = 30_000L

    /**
     * A single, deliberately non-renewing wake budget after an observed ignition-off transition.
     *
     * This restores responsive telemetry immediately after parking without treating an unplugged
     * head unit as an indefinitely powered server. It is intentionally in-memory: a daemon
     * restart while the car is already off must not create a fresh budget.
     */
    internal const val PARKED_UNPLUGGED_WAKE_WINDOW_MS = 30L * 60_000L

    /**
     * Total time the SoC has spent suspended since boot: `elapsedRealtime` counts deep sleep,
     * `uptimeMillis` does not, so their difference is exactly that. Both are Context-free static
     * reads, which is what makes suspend measurable from a daemon that has no Context.
     */
    internal fun deepSleepMs(elapsedRealtimeMs: Long, uptimeMs: Long): Long =
        (elapsedRealtimeMs - uptimeMs).coerceAtLeast(0L)

    /**
     * How long the device was suspended between two loop wakes. Never negative — a clock that
     * appears to go backwards is reported as "no suspend" rather than a nonsense negative window.
     */
    internal fun suspendedSinceLastWakeMs(previousDeepSleepMs: Long, currentDeepSleepMs: Long): Long =
        (currentDeepSleepMs - previousDeepSleepMs).coerceAtLeast(0L)

    /**
     * Whether this iteration should hold the suspend blocker.
     *
     * **Deliberately not "always".** The head unit runs off the 12 V battery, and a parked car that
     * never suspends will flatten it — the exact failure the 15-minute platform cadence is there to
     * prevent. So the blocker is held only where staying awake is both useful and safe:
     *
     *  - **gun connected** (`chargeGunState in 2..5`, matching [shouldUseAutoserviceFallback]) — the
     *    car is on shore power, so there is no 12 V cost, and this is the state whose SOC curve and
     *    cell-delta tail need every row.
     *  - **the first 30 minutes after an observed ignition-off transition** — the highest-value
     *    handoff window for the driver, bounded by [PARKED_UNPLUGGED_WAKE_WINDOW_MS] and never
     *    recreated by daemon restarts or later parked wakes.
     *  - **fast mode active** — someone has the live view open. Bounded by `liveFastUntilMs`, which
     *    is extend-only with an expiry, so a crashed tab cannot strand the car awake.
     *
     * After that bounded window, parked and unplugged, the daemon lets the platform suspend it.
     * Reporting then stays on the platform's own wake rhythm; the PWA is what must say "asleep"
     * rather than "offline" for that window. Freshness there is bounded by the platform, not by
     * anything this file can set.
     */
    internal fun shouldHoldWakeLock(
        now: Long,
        liveFastUntilMs: Long,
        gunState: Int?,
        parkedWakeUntilMs: Long = 0L,
    ): Boolean =
        now < liveFastUntilMs ||
            now < parkedWakeUntilMs ||
            (gunState != null && gunState in 2..5)

    /**
     * Starts the bounded parked wake window only when this daemon actually sees the car turn off.
     * A missing value is not treated as power-off: DiPars can be incomplete, and guessing here
     * would accidentally create a 12 V drain window. A later power-on clears the old budget.
     */
    internal fun nextParkedWakeUntilMs(
        now: Long,
        previousPowerState: Int?,
        currentPowerState: Int?,
        existingWakeUntilMs: Long,
    ): Long {
        if (currentPowerState != null && currentPowerState >= 1) return 0L
        val observedPowerOff =
            previousPowerState != null && previousPowerState >= 1 &&
                currentPowerState != null && currentPowerState < 1
        return if (observedPowerOff) now + PARKED_UNPLUGGED_WAKE_WINDOW_MS else existingWakeUntilMs
    }

    private enum class WakeLockBackend { POWER_MANAGER, SYSFS }

    private data class PowerManagerWakeLock(
        val service: Any,
        val acquire: Method,
        val release: Method,
    ) {
        fun acquire(token: IBinder) {
            val types = acquire.parameterTypes
            val args = Array<Any?>(types.size) { index ->
                when (index) {
                    0 -> token
                    1 -> PARTIAL_WAKE_LOCK
                    2 -> WAKE_LOCK_TAG
                    3 -> SHELL_PACKAGE_NAME
                    else -> defaultArgument(types[index])
                }
            }
            acquire.invoke(service, *args)
        }

        fun release(token: IBinder) {
            release.invoke(service, token, 0)
        }

        private companion object {
            const val PARTIAL_WAKE_LOCK = 0x00000001

            fun defaultArgument(type: Class<*>): Any? = when (type) {
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }
    }

    private val wakeLockToken = Binder()
    @Volatile private var wakeLockHeld = false
    private var activeWakeLockBackend: WakeLockBackend? = null
    private var powerManagerWakeLock: PowerManagerWakeLock? = null
    private var powerManagerWakeLockInitAttempted = false
    private var powerManagerWakeLockFailureLogged = false
    private var sysfsWakeLockFailureLogged = false

    /**
     * Resolve the hidden platform interface by reflection rather than compiling against a
     * version-specific `IPowerManager` stub. We validate the stable first five acquire arguments
     * before using it; appended platform arguments receive their AIDL defaults (null/zero).
     */
    private fun powerManagerWakeLock(): PowerManagerWakeLock? {
        powerManagerWakeLock?.let { return it }
        if (powerManagerWakeLockInitAttempted) return null
        powerManagerWakeLockInitAttempted = true
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "power") as? IBinder ?: error("power service missing")
            val stub = Class.forName("android.os.IPowerManager\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: error("IPowerManager unavailable")
            val iface = Class.forName("android.os.IPowerManager")
            val acquire = iface.methods.singleOrNull { it.name == "acquireWakeLock" }
                ?: error("IPowerManager.acquireWakeLock missing")
            val release = iface.methods.singleOrNull { it.name == "releaseWakeLock" }
                ?: error("IPowerManager.releaseWakeLock missing")
            val types = acquire.parameterTypes
            require(types.size >= 6 &&
                IBinder::class.java.isAssignableFrom(types[0]) &&
                types[1] == Int::class.javaPrimitiveType &&
                types[2] == String::class.java && types[3] == String::class.java
            ) { "unsupported acquireWakeLock signature (${types.joinToString { it.simpleName }})" }
            require(release.parameterTypes.contentEquals(arrayOf(IBinder::class.java, Int::class.javaPrimitiveType))) {
                "unsupported releaseWakeLock signature (${release.parameterTypes.joinToString { it.simpleName }})"
            }
            PowerManagerWakeLock(service, acquire, release).also {
                powerManagerWakeLock = it
                log(
                    "wakelock IPowerManager ready: android=${Build.VERSION.RELEASE} " +
                        "sdk=${Build.VERSION.SDK_INT} acquire_args=${types.size}; " +
                        "will verify on charger/live mode",
                )
            }
        } catch (e: Exception) {
            logPowerManagerWakeLockFailure("initialization", e)
            null
        }
    }

    /** Called once at startup so remote DiLink 5 logs expose the exact binder capability. */
    private fun logPowerManagerWakeLockCapability() {
        powerManagerWakeLock()
    }

    /** Acquire/release the Context-free service wakelock, falling back to legacy sysfs if needed. */
    private fun setWakeLock(hold: Boolean) {
        if (hold == wakeLockHeld) return
        if (hold) {
            val backend = powerManagerWakeLock()
            if (backend != null) {
                try {
                    backend.acquire(wakeLockToken)
                    wakeLockHeld = true
                    activeWakeLockBackend = WakeLockBackend.POWER_MANAGER
                    log("wakelock acquired via IPowerManager ($WAKE_LOCK_TAG)")
                    return
                } catch (e: Exception) {
                    logPowerManagerWakeLockFailure("acquire", e)
                }
            }
            setSysfsWakeLock(hold = true)
            return
        }

        when (activeWakeLockBackend) {
            WakeLockBackend.POWER_MANAGER -> {
                try {
                    powerManagerWakeLock()?.release(wakeLockToken)
                    wakeLockHeld = false
                    activeWakeLockBackend = null
                    log("wakelock released via IPowerManager ($WAKE_LOCK_TAG)")
                } catch (e: Exception) {
                    logPowerManagerWakeLockFailure("release", e)
                }
            }
            WakeLockBackend.SYSFS -> setSysfsWakeLock(hold = false)
            null -> Unit
        }
    }

    private fun setSysfsWakeLock(hold: Boolean) {
        val node = if (hold) WAKE_LOCK_NODE else WAKE_UNLOCK_NODE
        try {
            File(node).writeText(WAKE_LOCK_TAG)
            wakeLockHeld = hold
            activeWakeLockBackend = if (hold) WakeLockBackend.SYSFS else null
            log("wakelock ${if (hold) "acquired" else "released"} via sysfs ($WAKE_LOCK_TAG)")
        } catch (e: Exception) {
            if (!sysfsWakeLockFailureLogged) {
                sysfsWakeLockFailureLogged = true
                log("wakelock sysfs unavailable ($node): ${e.message} — platform suspend stays in charge")
            }
        }
    }

    private fun logPowerManagerWakeLockFailure(operation: String, error: Exception) {
        if (powerManagerWakeLockFailureLogged) return
        powerManagerWakeLockFailureLogged = true
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        log(
            "wakelock IPowerManager unavailable during $operation: " +
                "${cause.javaClass.simpleName}: ${cause.message}; falling back to sysfs",
        )
    }

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
     * and the write happens unconditionally before `enqueue` — deliberately ahead of it, so
     * a failure in the queue or settings layer cannot mute the beacon. Its age is therefore a
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

    // di+ value-staleness tracking (log-only — see isDiPlusValueStale). Updated once per
    // pushTelemetry call, alongside the existing autoservice SOC parity read.
    private var lastDiPlusSignature: String? = null
    private var diPlusSignatureUnchangedSinceMs: Long = 0L
    private var lastAutoserviceSocForStaleness: Int? = null
    private var autoserviceSocMovedAtMs: Long? = null

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
        // This is diagnostic as well as initialization: a remote DiLink 5 owner can return one
        // startup log line with Android/API and binder-signature evidence, without remote ADB.
        logPowerManagerWakeLockCapability()

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
        // Last gear/gun state di+ actually reported. `latestData` itself is never cleared to
        // null once di+ has answered once — a failed fetch() just leaves it stale — so these
        // are captured separately at the moment of each successful fetch and read back once
        // di+ is judged unreachable (see DIPLUS_STALE_MS below). Gates the autoservice-only
        // fallback — see shouldUseAutoserviceFallback. Both stay null for the life of the
        // process if di+ never answers even once (e.g. it's not running on this car at all);
        // that cold-start case is covered separately by a live autoservice read at the fallback
        // call site, not by these two.
        var lastKnownGear: Int? = null
        var lastKnownGunState: Int? = null
        // A post-park budget is only started by a witnessed power-on -> power-off transition.
        // Keeping both values process-local is intentional: a restarted daemon must stay safe.
        var lastSeenPowerState: Int? = null
        var parkedWakeUntilMs = 0L
        // Suspend accounting. Deep sleep is cumulative since boot, so the *difference* between
        // two wakes is how long the platform froze this loop. Without this the daemon cannot tell
        // "nothing happened for 15 minutes" from "I was not running for 15 minutes" — which is
        // exactly the ambiguity that made the field reports unattributable.
        var lastDeepSleepMs = deepSleepMs(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())

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

                    val deepSleepNow =
                        deepSleepMs(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
                    val suspendedMs = suspendedSinceLastWakeMs(lastDeepSleepMs, deepSleepNow)
                    lastDeepSleepMs = deepSleepNow
                    if (suspendedMs >= SUSPEND_REPORT_THRESHOLD_MS) {
                        log("platform suspend: loop frozen ${suspendedMs / 1000}s (wakelock held=$wakeLockHeld)")
                    }
                    if (shouldRefreshWifiKeepalive(now, lastWifiKeepAliveAt, conf.keepWifiAwake)) {
                        refreshWifiKeepalive()
                        lastWifiKeepAliveAt = now
                    }

                    // Refresh telemetry for guards if stale (cheap localhost call).
                    if (now - latestDataAt > TELEMETRY_TTL_MS) {
                        diPars.fetch()?.let {
                            latestData = it; latestDataAt = now
                            lastKnownGear = it.gear; lastKnownGunState = it.chargeGunState
                        }
                    }
                    // `latestData` itself may be a stale success from before di+ went dark (see
                    // DIPLUS_STALE_MS) — this is the actual "is di+ usable right now" signal.
                    val diPlusFresh = latestData != null && now - latestDataAt <= DIPLUS_STALE_MS

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
                    val powerNow = latestData?.powerState
                    if (powerNow != null) {
                        val nextWakeUntilMs = nextParkedWakeUntilMs(
                            now = now,
                            previousPowerState = lastSeenPowerState,
                            currentPowerState = powerNow,
                            existingWakeUntilMs = parkedWakeUntilMs,
                        )
                        if (nextWakeUntilMs > parkedWakeUntilMs) {
                            log(
                                "parked wake window started for " +
                                    "${PARKED_UNPLUGGED_WAKE_WINDOW_MS / 60_000}min after power-off",
                            )
                        } else if (parkedWakeUntilMs != 0L && nextWakeUntilMs == 0L) {
                            log("parked wake window ended: power restored")
                        }
                        parkedWakeUntilMs = nextWakeUntilMs
                        lastSeenPowerState = powerNow
                    }
                    // Hold the suspend blocker only where it is useful and safe — see
                    // [shouldHoldWakeLock]. Re-evaluated on every wake, not only when pushing, so
                    // a restored power state and the bounded parked window take effect promptly.
                    setWakeLock(
                        shouldHoldWakeLock(
                            now = now,
                            liveFastUntilMs = liveFastUntilMs,
                            gunState = gunNow,
                            parkedWakeUntilMs = parkedWakeUntilMs,
                        ),
                    )
                    val plan = planPush(
                        now = now,
                        lastTelemetryPushAt = lastTelemetryPushAt,
                        lastIntervalPushAt = lastIntervalPushAt,
                        liveFastUntilMs = liveFastUntilMs,
                        gunChanged = gunChanged,
                    )
                    if (plan.push) {
                        val pushed = latestData.takeIf { diPlusFresh }?.let { data ->
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
                        } ?: run {
                            // di+ is down or stuck stale (diPlusFresh == false). Only fall back
                            // to autoservice-only telemetry when there's real evidence the car is
                            // parked/charging — see shouldUseAutoserviceFallback for why this
                            // never guesses during a drive. Normally that evidence is di+'s last
                            // known state before it went dark. But if di+ has never answered at
                            // all this run (lastKnownGear/lastKnownGunState both still null —
                            // e.g. right after a daemon restart on a car where di+ never comes
                            // up), the last-known state can never clear the gate either, so the
                            // daemon would stay silent forever even while genuinely
                            // parked/charging. In that specific case only, take one fresh direct
                            // autoservice gun-state read as the evidence instead.
                            val neverSeenDiPlus = lastKnownGear == null && lastKnownGunState == null
                            val liveGunState = if (neverSeenDiPlus) {
                                readAutoserviceIntFid(1009, 876609586) // FID_GUN_CONNECT_STATE
                            } else {
                                null
                            }
                            when {
                                shouldDeferToApp(beaconAgeMs(now), liveOnly = false) -> {
                                    logSkip(now, "app alive — VoltFlow Mate is sending")
                                    false
                                }
                                shouldUseAutoserviceFallback(lastKnownGear, lastKnownGunState ?: liveGunState) -> {
                                    pushAutoserviceFallback(ok, conf)
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
                val interval = pollOnce(ok, conf, control, latestData)
                if (interval != null) {
                    backoffMs = BASE_POLL_MS
                    interval
                } else {
                    backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                    backoffMs
                }
            } catch (e: Exception) {
                log("poll error: ${e.message}")
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                backoffMs
            }
            Thread.sleep(waited)
        }
    }

    /**
     * Extends fast live-status mode from a `live_fast_seconds` grant on any server response.
     * A grant of 0 — the default on older servers and whenever nobody is watching — simply
     * lets the current window lapse rather than cancelling it early.
     */
    private fun applyLiveFastGrant(json: JSONObject) {
        val grantSeconds = json.optInt("live_fast_seconds", 0)
        if (grantSeconds > 0) {
            liveFastUntilMs = System.currentTimeMillis() + grantSeconds * 1000L
        }
    }

    /** Body-string overload for the telemetry pushes, which do not otherwise parse a response. */
    private fun applyLiveFastGrant(body: String?) {
        if (body.isNullOrBlank()) return
        runCatching { applyLiveFastGrant(JSONObject(body)) }
    }

    /** @return the next poll delay on a clean poll (HTTP ok), or null to trigger backoff. */
    private suspend fun pollOnce(
        ok: OkHttpClient,
        conf: Conf,
        control: DiParsControlClient,
        data: DiParsData?,
    ): Long? {
        val httpUrl = conf.commandsUrl.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            .header("X-API-Key", conf.apiKey)
            .header("X-Vehicle-Id", conf.vehicleId)
            .header("X-App", "VoltFlow-Mate-Daemon")
            .get()
            .build()

        return ok.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log("poll HTTP ${response.code}")
                return null
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            // Someone has the live view open. Read before the empty-queue return — an idle
            // command queue is the normal case and must not skip the grant. Absent on older
            // servers, which reads as 0 and leaves the current window to lapse.
            applyLiveFastGrant(json)
            val nextPollMs = commandPollIntervalMs(
                serverSeconds = json.optInt("poll_after_seconds", 0),
                commandsEnabled = json.optBoolean("commands_enabled", true),
            )
            val commands = json.optJSONArray("commands") ?: JSONArray()
            if (commands.length() == 0) return nextPollMs

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
            nextPollMs
        }
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
            // Keep the parity log and use the same validated fallback as the app sender
            // when a Di+ update omits SOC but the independent autoservice FID is available.
            val autoserviceSoc = readSocPercentAutoservice()
            val autoservicePowerKw = readEnginePowerKwAutoservice()
            val autoserviceGun = readAutoserviceIntFid(1009, 876609586) // FID_GUN_CONNECT_STATE
            if (autoserviceSoc != null || autoservicePowerKw != null) {
                log(
                    "autoservice check: soc=$autoserviceSoc (diplus=${data.soc}) " +
                        "power_kw=$autoservicePowerKw (diplus=${data.power}) " +
                        "gun=$autoserviceGun (diplus=${data.chargeGunState}, status=${data.chargingStatus})"
                )
            }
            // di+ value-staleness check (log-only — see isDiPlusValueStale). Reuses the
            // autoservice SOC read just above rather than reading it twice.
            run {
                val now = System.currentTimeMillis()
                val sig = diPlusValueSignature(data)
                if (sig != lastDiPlusSignature) {
                    lastDiPlusSignature = sig
                    diPlusSignatureUnchangedSinceMs = now
                }
                val socInt = autoserviceSoc?.toInt()
                if (socInt != null && socInt != lastAutoserviceSocForStaleness) {
                    autoserviceSocMovedAtMs = now
                    lastAutoserviceSocForStaleness = socInt
                }
                val charging = ChargingStateClassifier.isCharging(
                    autoserviceGun = autoserviceGun,
                    diPlusGun = data.chargeGunState,
                    chargingStatus = data.chargingStatus,
                )
                if (isDiPlusValueStale(now, diPlusSignatureUnchangedSinceMs, autoserviceSocMovedAtMs, charging)) {
                    val frozenMin = (now - diPlusSignatureUnchangedSinceMs) / 60_000
                    log("di+ value-stale=true (sig frozen ${frozenMin}m, autoservice soc=$autoserviceSoc)")
                }
            }
            val bodyworkStates = readBodyworkOpenStatesAutoservice()
            val tirePressures = readTirePressuresAutoservice()
            log(
                "autoservice check2: doors/trunk/hood(fl/fr/rl/rr/trunk/hood)=$bodyworkStates " +
                    "(diplus=${data.doorFL}/${data.doorFR}/${data.doorRL}/${data.doorRR}/${data.trunk}/${data.hood}) " +
                    "tires_kpa(fl/fr/rl/rr)=$tirePressures " +
                    "(diplus=${data.tirePressFL}/${data.tirePressFR}/${data.tirePressRL}/${data.tirePressRR})"
            )
            val payload = buildTelemetryPayload(
                conf.vehicleId,
                data,
                kwhCharged,
                sohPercent,
                liveOnly,
                autoserviceSoc,
                autoserviceGun,
            )
            if (!liveOnly) persistDaemonTelemetry(payload)
            val payloadJson = payload.toString()
            val request = Request.Builder()
                .url(conf.telemetryUrl)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-API-Key", conf.apiKey)
                .header("X-Vehicle-Id", conf.vehicleId)
                .header("X-App", "VoltFlow-Mate-Daemon")
                .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            ok.newCall(request).execute().use {
                val mode = if (liveOnly) " live_only" else ""
                log("telemetry HTTP ${it.code} (soc=${data.soc} pwr_state=${data.powerState}$mode)")
                // Second carrier for the fast-status grant, mirroring CloudTelemetrySender.
                // With the command poll idling at 60s this is what actually enters and renews
                // fast mode for a car the app is not running on.
                if (it.isSuccessful) applyLiveFastGrant(it.body?.string())
            }
        } catch (e: Exception) {
            log("telemetry push failed: ${e.message}")
        }
    }

    /**
     * Pushes a telemetry sample built entirely from autoservice reads — no di+ involved. Used
     * only when [shouldUseAutoserviceFallback] has already confirmed the car is parked/charging;
     * di+-only fields (speed, gear, climate, temps, etc.) are simply absent, same as any other
     * partial sample. See docs/EV_PRO_APP_ANALYSIS.md and the "Parked/charging telemetry
     * fallback" plan (2026-07-22) for why this exists and what it deliberately does not cover.
     */
    private fun pushAutoserviceFallback(ok: OkHttpClient, conf: Conf) {
        try {
            val payload = buildAutoserviceFallbackPayload(conf.vehicleId)
            val diplus = payload.optJSONObject("diplus")
            log(
                "telemetry (di+ down, autoservice fallback): soc=${diplus?.opt("soc")} " +
                    "gun=${diplus?.opt("charge_gun_state")}"
            )
            persistDaemonTelemetry(payload)
            val request = Request.Builder()
                .url(conf.telemetryUrl)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-API-Key", conf.apiKey)
                .header("X-Vehicle-Id", conf.vehicleId)
                .header("X-App", "VoltFlow-Mate-Daemon")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            ok.newCall(request).execute().use {
                log("telemetry HTTP ${it.code} (autoservice fallback)")
                if (it.isSuccessful) applyLiveFastGrant(it.body?.string())
            }
        } catch (e: Exception) {
            log("autoservice fallback push failed: ${e.message}")
        }
    }

    /** Durable ingress is authoritative; HTTP below remains only the low-latency lane. */
    private fun persistDaemonTelemetry(payload: JSONObject) {
        try {
            val deviceTime = payload.getString("device_time")
            val retained = durableIngress.capture(payload.toString(), deviceTime)
            log(if (retained == null) "telemetry committed to app queue" else "telemetry spooled (${retained.name})")
        } catch (e: Exception) {
            // Keep the daemon alive and retain the existing best-effort POST, but make the
            // durability failure unmistakable in the field log.
            log("URGENT telemetry durability failure: ${e.message}")
        }
    }

    /**
     * Assembles a telemetry payload purely from autoservice reads (dev=1001 bodywork/tyre,
     * dev=1009 charging, dev=1012 engine, dev=1014 statistic — see [FidRegistry] for the fid
     * catalog). Only fields already live-validated against di+ (2026-07-22) are populated;
     * everything di+-only is simply omitted via the same [putIfPresent] convention
     * [buildTelemetryPayload] already uses, so downstream consumers see a normal partial sample,
     * not a malformed one.
     */
    private fun buildAutoserviceFallbackPayload(vehicleId: String): JSONObject {
        val soc = readSocPercentAutoservice()?.toInt()
        val powerKw = readEnginePowerKwAutoservice()
        val gun = readAutoserviceIntFid(1009, 876609586) // FID_GUN_CONNECT_STATE
        val chargingType = readAutoserviceIntFid(1009, 876609592) // FID_CHARGING_TYPE
        val voltage12v = readAutoserviceFloatFid(1001, 1128267816) // FID_OTA_BATTERY_POWER_VOLTAGE
        val doorFL = readAutoserviceIntFid(1001, 692060168)
        val doorFR = readAutoserviceIntFid(1001, 692060170)
        val doorRL = readAutoserviceIntFid(1001, 692060172)
        val doorRR = readAutoserviceIntFid(1001, 692060174)
        val trunk = readAutoserviceIntFid(1001, 692060186)
        val hood = readAutoserviceIntFid(1001, 692060188)
        val tireFL = readAutoserviceIntFid(1001, -1728052956)
        val tireFR = readAutoserviceIntFid(1001, -1728052952)
        val tireRL = readAutoserviceIntFid(1001, -1728052948)
        val tireRR = readAutoserviceIntFid(1001, -1728052944)
        val sohPercent = readSohPercent()
        val kwhCharged = readKwhCharged()
        val isCharging = ChargingStateClassifier.isCharging(
            autoserviceGun = gun,
            diPlusGun = null,
            chargingStatus = null,
        )

        val diplus = JSONObject().apply {
            putIfPresent("soc", soc); putIfPresent("power_kw", powerKw); putIfPresent("charge_gun_state", gun)
            putIfPresent("voltage_12v", voltage12v)
            putIfPresent("door_fl", doorFL); putIfPresent("door_fr", doorFR); putIfPresent("door_rl", doorRL); putIfPresent("door_rr", doorRR)
            putIfPresent("trunk", trunk); putIfPresent("hood", hood)
            putIfPresent("tire_press_fl_kpa", tireFL); putIfPresent("tire_press_fr_kpa", tireFR)
            putIfPresent("tire_press_rl_kpa", tireRL); putIfPresent("tire_press_rr_kpa", tireRR)
        }
        val telemetry = JSONObject().apply {
            // Every SOC here is the autoservice display scale by construction — there is no
            // di+ read in this path. Tagged so a consumer can tell these samples apart from
            // raw-scale di+ ones; see SocScaleCalibration.
            putIfPresent("soc", soc); putIfPresent("soc_source", SocSource.AUTOSERVICE.wireName)
            putIfPresent("power_kw", powerKw); putIfPresent("aux_voltage_v", voltage12v)
            put("is_charging", isCharging)
            putIfPresent("charge_power_kw", if (isCharging) powerKw?.let { kotlin.math.abs(it) } else null)
            putRounded("kwh_charged", if (isCharging) kwhCharged?.toDouble() else null, KWH_CHARGED_DECIMALS)
            putIfPresent("charge_type", if (isCharging) when (chargingType) { 2 -> "AC"; in 3..5 -> "DC"; else -> null } else null)
            put("is_parked", !isCharging)
            putIfPresent("soh_percent", sohPercent?.toDouble())
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("vehicle_id", vehicleId)
            put("device_time", isoNow())
            put("source", "BYDMate")
            put("mate_version", BuildConfig.VERSION_NAME)
            put("telemetry", telemetry)
            put("diplus", diplus)
            put("location", JSONObject())
        }
    }

    internal fun buildTelemetryPayload(
        vehicleId: String,
        d: DiParsData,
        kwhCharged: Float? = null,
        sohPercent: Int? = null,
        liveOnly: Boolean = false,
        autoserviceSocPercent: Float? = null,
        autoserviceGun: Int? = null,
    ): JSONObject {
        val resolvedSoc = resolveTelemetrySoc(d.soc, autoserviceSocPercent)
        val telemetrySoc = resolvedSoc.percent
        val cellDelta = if (d.maxCellVoltage != null && d.minCellVoltage != null) {
            d.maxCellVoltage!! - d.minCellVoltage!!
        } else {
            null
        }
        val gun = autoserviceGun ?: d.chargeGunState
        val isCharging = ChargingStateClassifier.isCharging(
            autoserviceGun = autoserviceGun,
            diPlusGun = d.chargeGunState,
            chargingStatus = d.chargingStatus,
        )

        val diplus = JSONObject().apply {
            putIfPresent("soc", d.soc); putIfPresent("speed_kmh", d.speed); putIfPresent("mileage_km", d.mileage)
            putIfPresent("power_kw", d.power); putIfPresent("charge_gun_state", d.chargeGunState)
            putIfPresent("max_battery_temp_c", d.maxBatTemp); putIfPresent("avg_battery_temp_c", d.avgBatTemp)
            putIfPresent("min_battery_temp_c", d.minBatTemp); putIfPresent("charging_status", d.chargingStatus)
            putIfPresent("battery_capacity_kwh", d.batteryCapacityKwh)
            putIfPresent("total_elec_consumption_kwh", d.totalElecConsumption)
            putIfPresent("voltage_12v", d.voltage12v)
            putRounded("max_cell_voltage_v", d.maxCellVoltage, CELL_VOLTAGE_DECIMALS)
            putRounded("min_cell_voltage_v", d.minCellVoltage, CELL_VOLTAGE_DECIMALS)
            putRounded("cell_delta_v", cellDelta, CELL_VOLTAGE_DECIMALS)
            putIfPresent("exterior_temp_c", d.exteriorTemp); putIfPresent("gear", d.gear); putIfPresent("power_state", d.powerState)
            putIfPresent("inside_temp_c", d.insideTemp); putIfPresent("ac_status", d.acStatus); putIfPresent("ac_temp_c", d.acTemp)
            putIfPresent("fan_level", d.fanLevel); putIfPresent("ac_circ", d.acCirc)
            putIfPresent("door_fl", d.doorFL); putIfPresent("door_fr", d.doorFR); putIfPresent("door_rl", d.doorRL); putIfPresent("door_rr", d.doorRR)
            putIfPresent("window_fl_percent", d.windowFL); putIfPresent("window_fr_percent", d.windowFR)
            putIfPresent("window_rl_percent", d.windowRL); putIfPresent("window_rr_percent", d.windowRR)
            putIfPresent("sunroof_percent", d.sunroof); putIfPresent("trunk", d.trunk); putIfPresent("hood", d.hood)
            putIfPresent("seatbelt_fl", d.seatbeltFL); putIfPresent("lock_fl", d.lockFL)
            putIfPresent("tire_press_fl_kpa", d.tirePressFL); putIfPresent("tire_press_fr_kpa", d.tirePressFR)
            putIfPresent("tire_press_rl_kpa", d.tirePressRL); putIfPresent("tire_press_rr_kpa", d.tirePressRR)
            putIfPresent("drive_mode", d.driveMode); putIfPresent("work_mode", d.workMode); putIfPresent("auto_park", d.autoPark)
            putIfPresent("rain", d.rain); putIfPresent("light_low", d.lightLow); putIfPresent("drl", d.drl)
            putIfPresent("sunshade_percent", d.sunshade)
            putIfPresent("sentry_state", d.sentryState); putIfPresent("remote_lock_state", d.remoteLockState)
            putIfPresent("stall_sentry_mode", d.stallSentryMode)
        }

        val telemetry = JSONObject().apply {
            // Mirrors CloudTelemetryPayload: prefer di+ 2.0's 0.1 %-resolution value, and
            // tag which scale it is on (di+ raw BMS vs autoservice display — see
            // SocScaleCalibration).
            putIfPresent("soc", d.socPrecise ?: telemetrySoc)
            putIfPresent("soc_source", resolvedSoc.source?.wireName)
            putIfPresent("speed_kmh", d.speed?.toDouble()); putIfPresent("power_kw", d.power)
            putIfPresent("battery_temp_c", d.avgBatTemp?.toDouble()); putIfPresent("cabin_temp_c", d.insideTemp?.toDouble())
            putIfPresent("outside_temp_c", d.exteriorTemp?.toDouble()); putIfPresent("aux_voltage_v", d.voltage12v)
            putRounded("cell_voltage_min_v", d.minCellVoltage, CELL_VOLTAGE_DECIMALS)
            putRounded("cell_voltage_max_v", d.maxCellVoltage, CELL_VOLTAGE_DECIMALS)
            putRounded("cell_delta_v", cellDelta, CELL_VOLTAGE_DECIMALS)
            putIfPresent("odometer_km", d.mileage)
            put("is_charging", isCharging)
            putIfPresent("charge_power_kw", if (isCharging) d.power?.let { kotlin.math.abs(it) } else null)
            putRounded("kwh_charged", if (isCharging) kwhCharged?.toDouble() else null, KWH_CHARGED_DECIMALS)
            putIfPresent("charge_type", if (isCharging) when (gun) { 2 -> "AC"; in 3..5 -> "DC"; else -> null } else null)
            put("is_parked", d.gear == 1)
            putIfPresent("soh_percent", sohPercent?.toDouble())
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
            // Same optional wire shape as CloudTelemetryPayload. This is the exact value used
            // above by ChargingStateClassifier, not a second read that could race and diverge.
            if (autoserviceGun != null) {
                put("autoservice", JSONObject().put("gun_state", autoserviceGun))
            }
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

    /**
     * Omits the key entirely when the value is absent, matching
     * `CloudTelemetryPayload.putIfPresent` in the app.
     *
     * This used to write `JSONObject.NULL`, so every key was on the wire on every push —
     * roughly 30 of the 50 `diplus` keys as literal `null` on a parked car, ~800 bytes a push
     * and by far the largest remaining payload cost (an order of magnitude more than the
     * float rounding above). It also polluted the partial index
     * `bydmate_telemetry_samples_soh_analytics_idx`, whose predicate is
     * `telemetry ? 'soh_percent'`: jsonb key-existence is true even when the value is null, so
     * daemon rows with no SoH reading were indexed and then discarded by the query's
     * `between 0 and 100` check.
     *
     * **Safe because absent and null are equivalent everywhere downstream** (checked
     * 2026-08-06): the Zod fields are `.nullable().optional()`; `telemetry-sanitizer.ts` gates
     * on `value != null`, which catches `undefined` identically; and the only jsonb
     * key-existence checks on `telemetry`/`diplus` are the two `soh_percent` ones above. The
     * `location ? 'lat'` checks in the GPS-retention functions are unaffected — the daemon has
     * no GPS and already sends `location: {}` with no keys at all.
     */
    private fun JSONObject.putIfPresent(key: String, value: Any?) {
        if (value == null) return
        put(key, value)
    }

    /** Wire precision for cell voltages. Mirrors `CloudTelemetryPayload.CELL_VOLTAGE_DECIMALS`. */
    private const val CELL_VOLTAGE_DECIMALS = 4

    /** Wire precision for `kwh_charged`. Mirrors `CloudTelemetryPayload`'s 3 dp. */
    private const val KWH_CHARGED_DECIMALS = 3

    /**
     * Rounds a value before serializing, so raw-double artifacts don't bloat the JSON and the
     * cloud's telemetry jsonb column.
     *
     * The app got this in Phase 1 of `docs/CLOUD_OFFLOAD_PLAN.md`; the daemon never did, even
     * though it is the writer for most of the day. The worst offender is `cell_delta_v`, which
     * both builders compute as `maxCellVoltage - minCellVoltage` — exactly the subtraction that
     * produces `0.019999999999999` (~20 chars) on every sample.
     *
     * Matches the decimals `telemetry-sanitizer.ts` already applies server-side, so this is a
     * no-op for the backend and purely saves wire bytes.
     *
     * Non-finite input returns null rather than serializing `NaN`/`Infinity`, which are not
     * valid JSON numbers.
     */
    internal fun roundForWire(value: Double?, decimals: Int): Double? {
        if (value == null || !value.isFinite()) return null
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(value * factor) / factor
    }

    /** [putIfPresent] with [roundForWire] applied, so an absent reading omits its key as usual. */
    private fun JSONObject.putRounded(key: String, value: Double?, decimals: Int) {
        putIfPresent(key, roundForWire(value, decimals))
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
