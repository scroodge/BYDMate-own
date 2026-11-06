package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of [AdbOnDeviceClient.ensureCommandDaemonRunning]. [launchAttempted] is
 * false when the daemon + watchdog were already alive (no relaunch needed) or when
 * the sequence stopped earlier (ADB unavailable / launcher deploy failed) — check
 * [adbConnected] and [daemonRunning]/[watchdogRunning] first to tell those apart.
 */
/** One read-only autoservice call: transact 5 = getInt, 7 = getFloat. */
data class AutoserviceRead(val tx: Int, val dev: Int, val fid: Int) {
    init {
        require(tx == FidRegistry.TX_GET_INT || tx == FidRegistry.TX_GET_FLOAT) { "read-only tx only: $tx" }
        require(dev >= 0) { "dev must be non-negative: $dev" }
    }

    fun command(): String = "service call autoservice $tx i32 $dev i32 $fid"
}

/**
 * The only direct writes the app may request through on-device ADB.
 *
 * This is deliberately an enum rather than `(dev, fid, value)` arguments: callers cannot turn
 * the ADB transport into a generic autoservice writer. Values below were individually verified
 * on car `way` on 2026-11-06; see docs/AUTOSERVICE_ACTUATION.md.
 */
enum class AutoserviceActuation(
    internal val dev: Int,
    internal val setFid: Int,
    internal val value: Int,
    internal val readBack: AutoserviceRead? = null,
    internal val expectedReadBack: Int? = null,
) {
    DRIVER_SEAT_HEAT_OFF(
        dev = 1023, setFid = 1125122068, value = 1,
        readBack = AutoserviceRead(FidRegistry.TX_GET_INT, 1023, 1335885835), expectedReadBack = 1,
    ),
    DRIVER_SEAT_HEAT_LEVEL_2(
        dev = 1023, setFid = 1125122068, value = 3,
        readBack = AutoserviceRead(FidRegistry.TX_GET_INT, 1023, 1335885835), expectedReadBack = 3,
    ),
    PASSENGER_SEAT_HEAT_OFF(
        dev = 1023, setFid = 1125122076, value = 1,
        readBack = AutoserviceRead(FidRegistry.TX_GET_INT, 1023, 1335885843), expectedReadBack = 1,
    ),
    PASSENGER_SEAT_HEAT_LEVEL_2(
        dev = 1023, setFid = 1125122076, value = 3,
        readBack = AutoserviceRead(FidRegistry.TX_GET_INT, 1023, 1335885843), expectedReadBack = 3,
    ),
    DRL_ON(dev = 1004, setFid = 1125122118, value = 1),
    DRL_OFF(dev = 1004, setFid = 1125122118, value = 2),
}

data class AutoserviceActuationResult(
    val accepted: Boolean,
    /** `null` means the action has no reliable read fid (currently DRL). */
    val verified: Boolean?,
    val observedValue: Int? = null,
)

data class DaemonSupervisorResult(
    val adbConnected: Boolean,
    val daemonRunning: Boolean,
    val watchdogRunning: Boolean,
    val launchAttempted: Boolean,
    val launchOk: Boolean,
)

/**
 * Connects to the on-device ADB daemon at 127.0.0.1:5555 (DiLink has WiFi
 * ADB enabled in dev settings) using a persistent RSA keypair stored in
 * `filesDir/adb_keys/`. Once paired, exposes `exec(cmd)` for one-shot
 * shell commands.
 *
 * Why on-device ADB? `service call autoservice ...` requires either system
 * UID, hidden API access, or shell UID. BYDMate runs as a normal app —
 * ADB shell uid is the only path. See `reference_adb_on_device_pattern.md`.
 *
 * Implementation: hand-rolled binary ADB protocol in [AdbProtocolClient]
 * (no external deps, no `adblib`). Auth flow uses standard RSA pubkey on
 * port 5555 — no TLS pairing / 6-digit code, the user accepts the «Allow USB
 * debugging?» dialog directly on DiLink.
 */
interface AdbOnDeviceClient {
    /** Initiates the ADB handshake. Suspends until handshake completes or fails. */
    suspend fun connect(): Result<Unit>
    suspend fun isConnected(): Boolean
    /** Executes a one-shot shell command and returns stdout, or null on failure. */
    suspend fun exec(cmd: String): String?
    /**
     * Runs several read-only autoservice calls in ONE shell session — one ADB round trip
     * instead of one per fid (~28 ms each on the head unit). Returns the raw 32-bit results
     * in input order, null per call that produced no parcel, or null for the whole batch on
     * transport failure. Keep batches short: [AdbProtocolClient.exec] is synchronized, so a
     * long batch holds up every other autoservice read (e.g. the charging-gun poll).
     */
    suspend fun execAutoserviceReads(reads: List<AutoserviceRead>): List<Int?>? = null
    /**
     * Executes exactly one [AutoserviceActuation] as ADB shell UID. No arbitrary `(fid, value)`
     * path exists here; this is intentionally separate from the read-only [exec] boundary.
     */
    suspend fun executeAutoserviceActuation(action: AutoserviceActuation): AutoserviceActuationResult? = null
    /**
     * Grants PACKAGE_USAGE_STATS appop to our own package via shell uid. The
     * only non-autoservice write we permit through this client — needed so
     * UsageStatsManager.queryEvents returns data for camera-overlay detection.
     * No-op effect if already granted. Returns true on success.
     */
    suspend fun grantUsageStatsAppop(packageName: String): Boolean
    /**
     * Grants SYSTEM_ALERT_WINDOW appop to our own package via shell uid, for the
     * floating widget — DiLink's settings UI is gutted, so the stock "display over
     * other apps" screen may not exist. Returns true on success.
     */
    suspend fun grantOverlayAppop(packageName: String): Boolean = false
    /**
     * Starts D+ MainService directly via shell uid `am start-foreground-service`.
     * Used by the watchdog when D+ has gone silent — `startActivity` against
     * StartMainServiceActivity crashes on Android 12+ due to background-service
     * restrictions in cached/idle state, but the shell-uid path bypasses them.
     * Returns true when am succeeded.
     */
    suspend fun launchDiPlusService(): Boolean
    /**
     * Returns true if the shell-uid survival daemon (nice-name `voltflow_cmd_daemon`,
     * spawned by `start_voltflow_cmd.sh`) is currently alive. Uses `pidof` over shell uid.
     */
    suspend fun isCommandDaemonRunning(): Boolean
    /**
     * Returns true only when the launcher watchdog that respawns [CommandDaemon]
     * is alive. A daemon process without its watchdog is not sleep-safe.
     */
    suspend fun isCommandDaemonWatchdogRunning(): Boolean
    /**
     * (Re)starts the shell-uid survival daemon by launching its watchdog script
     * detached via `setsid` (so it outlives BYD's quickboot force-stop and this one-shot
     * ADB session). [scriptPath] is an app-controlled absolute path to the bundled
     * launcher in the app's external files dir — not user input. Returns true if the
     * launch command was issued.
     */
    suspend fun launchCommandDaemon(scriptPath: String): Boolean
    /**
     * Copies the bundled `start_voltflow_cmd.sh` launcher from assets to the app's
     * external files dir (app-writable, shell-readable) so it can be started under the
     * shell uid without a manual `adb push`. Returns the absolute path, or null on failure.
     */
    suspend fun deployDaemonLauncher(): String?
    /**
     * Full daemon-supervisor sequence: connect on-device ADB if needed, deploy the
     * bundled launcher, and (re)launch the daemon if it or its watchdog isn't already
     * running. Safe no-op on any failure (e.g. ADB not authorised) — see
     * [DaemonSupervisorResult].
     */
    suspend fun ensureCommandDaemonRunning(): DaemonSupervisorResult
    /**
     * Explicit user control for the shell-uid survival daemon. Disabling is deliberately
     * narrower than [exec]: it can only set/remove our fixed sentinel and terminate the
     * fixed daemon/watchdog processes. This is what makes Gateway "Stop" survive car-off.
     */
    suspend fun setCommandDaemonEnabled(enabled: Boolean): Boolean = false
    /** Closes any underlying socket. Idempotent. */
    suspend fun shutdown()
}

@Singleton
class AdbOnDeviceClientImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val keyStore: AdbKeyStore,
) : AdbOnDeviceClient {

    /**
     * Test seam — UNIT TESTS ONLY. Lets the test layer swap the real
     * socket-backed protocol for a fake. Default factory creates the real
     * [AdbProtocolClient] with the persisted keypair.
     */
    @Suppress("unused")  // assigned via internal setter from tests
    internal var protocolFactory: () -> AdbProtocol = {
        AdbProtocolClient(keyStore.loadOrGenerate())
    }

    @Volatile private var protocol: AdbProtocol? = null

    @Suppress("unused")  // kept for future-proofing; AdbKeyStore already has Context.
    private val ctx = context

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val p = protocol ?: protocolFactory().also { protocol = it }
            val ok = p.connect()
            if (ok) Result.success(Unit) else Result.failure(IOException("ADB connect refused"))
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        protocol?.isConnected() ?: false
    }

    override suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        // Structural barrier against accidental WRITE — only allow GETs to autoservice.
        require(cmd.matches(WRITE_BARRIER_REGEX)) {
            "AdbOnDeviceClient: refused command (write barrier): $cmd"
        }
        val p = protocol ?: return@withContext null
        try {
            // runInterruptible bridges coroutine cancellation to the blocking
            // ADB socket read: a parent withTimeout/withTimeoutOrNull will
            // post a Thread.interrupt(), which the socket implementation
            // raises as InterruptedIOException and unwinds out of p.exec.
            // Without this wrapper, withTimeoutOrNull(900ms) abandons the
            // coroutine but the socket read keeps blocking up to 5 s
            // (SOCKET_TIMEOUT_MS), pinning single-flight in TrackingService.
            kotlinx.coroutines.runInterruptible { p.exec(cmd) }
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            null
        }
    }

    override suspend fun execAutoserviceReads(reads: List<AutoserviceRead>): List<Int?>? = withContext(Dispatchers.IO) {
        if (reads.isEmpty()) return@withContext emptyList()
        // Every call still passes the single-call write barrier; the only glue added
        // between them is a fixed `echo @<index>` marker, built from an Int.
        val cmd = reads.mapIndexed { i, r ->
            val call = r.command()
            require(call.matches(WRITE_BARRIER_REGEX)) {
                "AdbOnDeviceClient: refused command (write barrier): $call"
            }
            "echo $BATCH_MARKER$i;$call"
        }.joinToString(";")
        val p = protocol ?: return@withContext null
        val out = try {
            kotlinx.coroutines.runInterruptible { p.exec(cmd) }
        } catch (e: Exception) {
            Log.w(TAG, "execAutoserviceReads failed: ${e.message}")
            null
        } ?: return@withContext null
        parseBatchOutput(out, reads.size)
    }

    override suspend fun executeAutoserviceActuation(
        action: AutoserviceActuation,
    ): AutoserviceActuationResult? = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext null
        try {
            // All three fields are compile-time enum values. Never accept a caller-built shell
            // string or fid/value pair here: this is a deliberately tiny write capability.
            val write = p.exec(
                "service call autoservice 6 i32 ${action.dev} i32 ${action.setFid} i32 ${action.value}",
            ) ?: return@withContext null
            val accepted = SET_INT_ACCEPTED_REGEX.containsMatchIn(write)
            if (!accepted) return@withContext AutoserviceActuationResult(accepted = false, verified = false)

            val read = action.readBack ?: return@withContext AutoserviceActuationResult(
                accepted = true,
                verified = null,
            )
            val raw = p.exec(read.command()) ?: return@withContext AutoserviceActuationResult(
                accepted = true,
                verified = false,
            )
            val observed = PARCEL_INT_REGEX.find(raw)?.groupValues?.get(1)?.toLong(16)?.toInt()
            AutoserviceActuationResult(
                accepted = true,
                verified = observed == action.expectedReadBack,
                observedValue = observed,
            )
        } catch (e: Exception) {
            Log.w(TAG, "executeAutoserviceActuation($action) failed: ${e.message}")
            null
        }
    }

    override suspend fun grantUsageStatsAppop(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Only permit our own package — never grant appops to anything else.
        require(packageName == ctx.packageName) {
            "grantUsageStatsAppop: refused package $packageName"
        }
        val cmd = "appops set $packageName GET_USAGE_STATS allow"
        val p = protocol ?: return@withContext false
        try {
            // appops prints nothing on success; null means transport error.
            // Treat any non-null output as failure (e.g. "Bad permission") too.
            val out = p.exec(cmd) ?: return@withContext false
            out.isBlank()
        } catch (e: Exception) {
            Log.w(TAG, "grantUsageStatsAppop failed: ${e.message}")
            false
        }
    }

    override suspend fun grantOverlayAppop(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Only permit our own package — never grant appops to anything else.
        require(packageName == ctx.packageName) {
            "grantOverlayAppop: refused package $packageName"
        }
        val p = protocol ?: return@withContext false
        try {
            val out = p.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow") ?: return@withContext false
            out.isBlank()
        } catch (e: Exception) {
            Log.w(TAG, "grantOverlayAppop failed: ${e.message}")
            false
        }
    }

    override suspend fun launchDiPlusService(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        try {
            // am start-foreground-service prints "Starting service: Intent { ... }"
            // on success and "Error: ..." on failure. Treat any output starting
            // with "Error" as a failure.
            val out = p.exec(LAUNCH_DIPLUS_CMD) ?: return@withContext false
            !out.trimStart().startsWith("Error")
        } catch (e: Exception) {
            Log.w(TAG, "launchDiPlusService failed: ${e.message}")
            false
        }
    }

    override suspend fun isCommandDaemonRunning(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        try {
            // pidof prints the PID(s) when alive, nothing when dead.
            !p.exec("pidof $DAEMON_PROCESS_NAME").isNullOrBlank()
        } catch (e: Exception) {
            Log.w(TAG, "isCommandDaemonRunning failed: ${e.message}")
            false
        }
    }

    override suspend fun isCommandDaemonWatchdogRunning(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        try {
            val out = p.exec(WATCHDOG_HEALTH_CMD) ?: return@withContext false
            out.trim() == "ok"
        } catch (e: Exception) {
            Log.w(TAG, "isCommandDaemonWatchdogRunning failed: ${e.message}")
            false
        }
    }

    override suspend fun launchCommandDaemon(scriptPath: String): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        try {
            // setsid + detached redirect so the watchdog survives this exec session and
            // BYD's quickboot force-stop (shell uid). exec returns at EOF (immediately,
            // since stdout is redirected to /dev/null and the job is backgrounded).
            val out = p.exec("setsid sh $scriptPath >/dev/null 2>&1 &") ?: return@withContext false
            !out.trimStart().startsWith("Error") && !out.contains("not found")
        } catch (e: Exception) {
            Log.w(TAG, "launchCommandDaemon failed: ${e.message}")
            false
        }
    }

    override suspend fun deployDaemonLauncher(): String? = withContext(Dispatchers.IO) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: return@withContext null
            val script = java.io.File(dir, DAEMON_LAUNCHER_ASSET)
            ctx.assets.open(DAEMON_LAUNCHER_ASSET).use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
            script.setReadable(true, false)
            script.setExecutable(true, false)
            script.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "deployDaemonLauncher failed: ${e.message}")
            null
        }
    }

    override suspend fun ensureCommandDaemonRunning(): DaemonSupervisorResult {
        if (!isConnected() && connect().isFailure) {
            return DaemonSupervisorResult(
                adbConnected = false, daemonRunning = false, watchdogRunning = false,
                launchAttempted = false, launchOk = false,
            )
        }
        val launcher = deployDaemonLauncher() ?: return DaemonSupervisorResult(
            adbConnected = true, daemonRunning = false, watchdogRunning = false,
            launchAttempted = false, launchOk = false,
        )
        val daemonRunning = isCommandDaemonRunning()
        val watchdogRunning = isCommandDaemonWatchdogRunning()
        if (daemonRunning && watchdogRunning) {
            return DaemonSupervisorResult(
                adbConnected = true, daemonRunning = true, watchdogRunning = true,
                launchAttempted = false, launchOk = false,
            )
        }
        val ok = launchCommandDaemon(launcher)
        return DaemonSupervisorResult(
            adbConnected = true, daemonRunning = daemonRunning, watchdogRunning = watchdogRunning,
            launchAttempted = true, launchOk = ok,
        )
    }

    override suspend fun setCommandDaemonEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected() && connect().isFailure) return@withContext false
        val p = protocol ?: return@withContext false
        val command = if (enabled) ENABLE_COMMAND_DAEMON_CMD else DISABLE_COMMAND_DAEMON_CMD
        try {
            // Intentionally does not go through exec(): that method is a read-only autoservice
            // boundary. These two fixed commands are the only shell writes exposed here.
            p.exec(command)?.trim() == COMMAND_DAEMON_CONTROL_OK
        } catch (e: Exception) {
            Log.w(TAG, "setCommandDaemonEnabled($enabled) failed: ${e.message}")
            false
        }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            try {
                protocol?.disconnect()
            } catch (_: Exception) { /* idempotent */ }
            protocol = null
        }
    }

    companion object {
        private const val TAG = "AdbOnDevice"

        // Block ANY write attempt at the boundary.
        // Allow only: service call autoservice <5|7|9> i32 <dev> i32 <fid>
        // Rejects tx=6 (setInt), tx=8 (setBuffer), and arbitrary shell.
        private val WRITE_BARRIER_REGEX = Regex("""^service call autoservice [579] i32 \d+ i32 -?\d+$""")

        private const val BATCH_MARKER = "@vfas"
        private val BATCH_MARKER_REGEX = Regex("""^$BATCH_MARKER(\d+)$""")
        private val PARCEL_INT_REGEX = Regex("""Parcel\(00000000\s+([0-9a-fA-F]{8})""")
        private val SET_INT_ACCEPTED_REGEX = Regex("""Parcel\(00000001\b""")

        /**
         * Splits `echo @vfas<i>` / `Result: Parcel(00000000 xxxxxxxx ...)` output back into
         * per-call raw ints. Marker-keyed rather than positional, so one call that prints an
         * error instead of a parcel can't shift every value after it onto the wrong field.
         */
        internal fun parseBatchOutput(out: String, count: Int): List<Int?> {
            val result = arrayOfNulls<Int>(count)
            var current = -1
            for (line in out.lineSequence()) {
                val trimmed = line.trim()
                val marker = BATCH_MARKER_REGEX.find(trimmed)
                if (marker != null) {
                    current = marker.groupValues[1].toIntOrNull() ?: -1
                    continue
                }
                if (current !in 0 until count || result[current] != null) continue
                val m = PARCEL_INT_REGEX.find(trimmed) ?: continue
                result[current] = m.groupValues[1].toLong(16).toInt()
            }
            return result.toList()
        }

        // Hardcoded — no params, so no injection surface.
        private const val LAUNCH_DIPLUS_CMD =
            "am start-foreground-service -n com.van.diplus/com.van.diplus.service.MainService"

        // nice-name of the shell-uid survival daemon spawned by start_voltflow_cmd.sh.
        private const val DAEMON_PROCESS_NAME = "voltflow_cmd_daemon"
        private const val DAEMON_LAUNCHER_ASSET = "start_voltflow_cmd.sh"
        private const val WATCHDOG_HEALTH_CMD =
            "pid=\$(cat /data/local/tmp/voltflow_cmd_watchdog.pid 2>/dev/null); " +
                "if [ -n \"\$pid\" ] && kill -0 \"\$pid\" 2>/dev/null && " +
                "tr '\\0' ' ' < /proc/\"\$pid\"/cmdline 2>/dev/null | grep -q start_voltflow_cmd.sh; " +
                "then echo ok; fi"
        private const val COMMAND_DAEMON_SENTINEL = "/data/local/tmp/voltflow_cmd_daemon.disabled"
        private const val COMMAND_DAEMON_CONTROL_OK = "voltflow_cmd_control_ok"
        // Fixed commands only: no user input reaches this shell boundary. Touch the sentinel
        // first so a concurrently waking watchdog observes it before its processes are killed.
        // Do not use `pkill -f`: on some Android shells it matches this very adb-shell command
        // line and can terminate the control command before it removes the stale lock / replies.
        private const val DISABLE_COMMAND_DAEMON_CMD =
            "touch $COMMAND_DAEMON_SENTINEL; " +
                "for pid in \$(pidof voltflow_cmd_daemon 2>/dev/null); do kill \"\$pid\"; done; " +
                "watchdog=\$(cat /data/local/tmp/voltflow_cmd_watchdog.pid 2>/dev/null); " +
                "if [ -n \"\$watchdog\" ]; then kill \"\$watchdog\"; fi; " +
                "rm -f /data/local/tmp/voltflow_cmd_watchdog.pid; " +
                "echo $COMMAND_DAEMON_CONTROL_OK"
        private const val ENABLE_COMMAND_DAEMON_CMD =
            "rm -f $COMMAND_DAEMON_SENTINEL; echo $COMMAND_DAEMON_CONTROL_OK"
    }
}
