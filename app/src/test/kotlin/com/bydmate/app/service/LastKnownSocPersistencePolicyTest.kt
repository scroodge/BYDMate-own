package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastKnownSocPersistencePolicyTest {
    @Test
    fun `persists the first reading and every SOC change`() {
        assertTrue(LastKnownSocPersistencePolicy.shouldPersist(80, null, 0L, 1_000L))
        assertTrue(LastKnownSocPersistencePolicy.shouldPersist(81, 80, 1_000L, 2_000L))
    }

    @Test
    fun `does not persist an unchanged SOC before the freshness boundary`() {
        assertFalse(
            LastKnownSocPersistencePolicy.shouldPersist(
                currentSoc = 80,
                previousSoc = 80,
                previousCapturedAtMs = 1_000L,
                nowMs = 60_999L,
            )
        )
    }

    @Test
    fun `refreshes an unchanged SOC at the freshness boundary`() {
        assertTrue(
            LastKnownSocPersistencePolicy.shouldPersist(
                currentSoc = 80,
                previousSoc = 80,
                previousCapturedAtMs = 1_000L,
                nowMs = 61_000L,
            )
        )
    }
}
