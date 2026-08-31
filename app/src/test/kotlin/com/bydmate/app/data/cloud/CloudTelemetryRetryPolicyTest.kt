package com.bydmate.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTelemetryRetryPolicyTest {
    @Test
    fun `backoff ceiling grows exponentially and remains capped`() {
        assertEquals(5_001L, CloudTelemetryRetryPolicy.maxDelayMs(failureCount = 1))
        assertEquals(10_000L, CloudTelemetryRetryPolicy.maxDelayMs(failureCount = 2))
        assertEquals(20_000L, CloudTelemetryRetryPolicy.maxDelayMs(failureCount = 3))
        assertEquals(15 * 60_000L, CloudTelemetryRetryPolicy.maxDelayMs(failureCount = 30))
    }

    @Test
    fun `full jitter varies within the current backoff window`() {
        val first = CloudTelemetryRetryPolicy.delayMs(
            failureCount = 4,
            retryAfterMs = null,
            randomFraction = 0.10,
        )
        val second = CloudTelemetryRetryPolicy.delayMs(
            failureCount = 4,
            retryAfterMs = null,
            randomFraction = 0.90,
        )

        assertNotEquals(first, second)
        assertTrue(first in 0L..40_000L)
        assertTrue(second in 0L..40_000L)
    }

    @Test
    fun `retry after is a floor for the jittered delay`() {
        assertEquals(
            120_000L,
            CloudTelemetryRetryPolicy.delayMs(
                failureCount = 1,
                retryAfterMs = 120_000L,
                randomFraction = 0.50,
            ),
        )
    }
}
