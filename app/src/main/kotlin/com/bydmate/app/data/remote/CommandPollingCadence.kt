package com.bydmate.app.data.remote

internal object CommandPollingCadence {
    const val BASE_POLL_MS = 6_000L
    const val SUSPENDED_POLL_MS = 300_000L

    fun intervalMs(serverSeconds: Int, commandsEnabled: Boolean = true): Long {
        if (!commandsEnabled) return SUSPENDED_POLL_MS
        if (serverSeconds <= 0) return BASE_POLL_MS
        return (serverSeconds * 1000L).coerceIn(BASE_POLL_MS, SUSPENDED_POLL_MS)
    }
}
