package com.bydmate.app.data.cloud

import kotlin.math.pow

/** Retry timing shared by telemetry delivery and its deterministic unit tests. */
object CloudTelemetryRetryPolicy {
    private const val BASE_DELAY_MS = 5_000L
    private const val MAX_DELAY_MS = 15 * 60_000L

    fun maxDelayMs(failureCount: Int): Long {
        if (failureCount <= 0) return 0L
        val multiplier = 2.0.pow((failureCount - 1).coerceAtMost(30)).toLong()
        return (BASE_DELAY_MS * multiplier).coerceAtMost(MAX_DELAY_MS)
    }

    /** Full jitter in [0, maxDelay], with Retry-After acting as a mandatory floor. */
    fun delayMs(
        failureCount: Int,
        retryAfterMs: Long?,
        randomFraction: Double,
    ): Long {
        val boundedFraction = randomFraction.coerceIn(0.0, 1.0)
        val jittered = (maxDelayMs(failureCount) * boundedFraction).toLong()
        return maxOf(jittered, retryAfterMs?.coerceAtLeast(0L) ?: 0L)
    }
}
