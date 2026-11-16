package com.bydmate.app.data.remote

/**
 * One executor for cloud commands per car: the shell-uid daemon when it is polling, the app
 * otherwise.
 *
 * Both used to claim commands, and whichever polled first ran the command — through di+ in the
 * app, directly through `autoservice` in the daemon ([DirectCommand]). The daemon also survives
 * power-off, so it is the executor whenever it is up. It writes [BEACON_FILE] (epoch millis)
 * into the app's external files dir after every successful command poll; while that is fresh
 * the app polls with `X-Command-Standby: 1`, which still returns every config field but claims
 * no command. On a car without a daemon (no ADB) the file never appears and the app executes
 * commands as before. Mirror image of the app-alive beacon the daemon reads for telemetry.
 */
object CommandStandby {
    const val BEACON_FILE = "voltflow_daemon_commands"
    const val HEADER = "X-Command-Standby"

    /**
     * Longest the daemon may go between successful polls while healthy: the server's idle
     * cadence (90 s) plus the 30 s error backoff, with slack. Past this the app takes commands
     * back, so a dead daemon costs at most this long.
     */
    const val BEACON_TTL_MS = 150_000L

    /** True while [beaconRaw] (the file's content) is a recent daemon poll. */
    fun appShouldStandby(beaconRaw: String?, nowMs: Long): Boolean {
        val at = beaconRaw?.trim()?.toLongOrNull() ?: return false
        val age = nowMs - at
        return age in 0..BEACON_TTL_MS
    }
}
