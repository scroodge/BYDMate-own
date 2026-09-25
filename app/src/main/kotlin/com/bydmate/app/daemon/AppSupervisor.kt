package com.bydmate.app.daemon

import java.io.File

/**
 * Keeps VoltFlow Mate's TrackingService alive from the shell-uid daemon (B-20, moved here by
 * B-22 stage 1).
 *
 * This used to live in the watchdog shell script, where it could not be updated: a running
 * `sh` parses its loop once, so the watchdog on car `way` ran a copy from 2026-09-09 for 16
 * days and the B-20 fix never executed. The daemon is respawned from the new APK on every
 * install, so logic here ships with the APK.
 *
 * `pidof` alone is not a liveness signal. On 2026-09-25 the daemon's own queue `content call`
 * respawned the app process after quickboot without TrackingService; the process looked alive
 * while nothing was being sent. The app's 1 Hz beacon is what says the service is turning.
 */
internal object AppSupervisor {

    const val PKG = "dev.scroodge.cloudevmate"
    const val MAIN_ACTIVITY = "$PKG/com.bydmate.app.MainActivity"
    const val SILENT_START_ACTIVITY = "$PKG/com.bydmate.app.SilentStartActivity"

    /** How often the daemon checks — the watchdog's old 30 s tick. */
    const val CHECK_INTERVAL_MS = 30_000L

    /** A beacon this old with the process still alive means the service is gone. */
    const val BEACON_STALE_SEC = 120L

    /**
     * Shared with watchdogs still running the pre-B-22 script, which make the same decision.
     * Both sides honour one cooldown, so a car mid-transition does not get two `am start`s.
     */
    const val RELAUNCH_TS_FILE = "/data/local/tmp/voltflow_app_relaunch_ts"
    const val RELAUNCH_COOLDOWN_SEC = 60L

    /**
     * Why the app must be relaunched, or null when it is fine. Mirrors the shell
     * `app_relaunch_reason` it replaces, case for case.
     *
     * An absent beacon with a live process is a graceful stop (`onDestroy` deletes the file),
     * so a gateway the owner turned off is left alone. A torn or foreign write (non-digits,
     * fewer than 13 digits of epoch millis) never restarts a live process, and neither does a
     * future-dated beacon.
     */
    fun relaunchReason(processAlive: Boolean, beaconRaw: String?, nowMs: Long): String? {
        if (!processAlive) return "not running"
        val raw = beaconRaw?.trim().orEmpty()
        if (raw.length < 13 || raw.any { !it.isDigit() }) return null
        val beaconMs = raw.toLongOrNull() ?: return null
        val ageSec = nowMs / 1000 - beaconMs / 1000
        return if (ageSec > BEACON_STALE_SEC) "service stale (beacon age ${ageSec}s)" else null
    }

    /** A process that is not running needs the UI; a live one gets the invisible starter. */
    fun activityFor(reason: String): String =
        if (reason == "not running") MAIN_ACTIVITY else SILENT_START_ACTIVITY

    fun cooldownElapsed(nowSec: Long, lastSec: Long?): Boolean =
        lastSec == null || nowSec - lastSec >= RELAUNCH_COOLDOWN_SEC

    /** One supervision pass. Never throws: it must not take down the telemetry loop. */
    fun tick(nowMs: Long, beaconPath: String, log: (String) -> Unit) {
        try {
            val reason = relaunchReason(
                processAlive = shell("pidof $PKG").isNotBlank(),
                beaconRaw = readOrNull(beaconPath),
                nowMs = nowMs,
            ) ?: return
            val nowSec = nowMs / 1000
            val lastSec = readOrNull(RELAUNCH_TS_FILE)?.trim()?.toLongOrNull()
            if (!cooldownElapsed(nowSec, lastSec)) return
            log("relaunch: $reason")
            shell("am start -n ${activityFor(reason)}")
            File(RELAUNCH_TS_FILE).writeText(nowSec.toString())
        } catch (e: Exception) {
            log("relaunch check failed: ${e.message}")
        }
    }

    private fun readOrNull(path: String): String? =
        File(path).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    private fun shell(cmd: String): String {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out.trim()
    }
}
