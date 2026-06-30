package com.bydmate.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * di+ emits magic "no data" sentinels when a signal is unreadable. These were seen live on
 * the head unit (DiLink3.0) and were being forwarded verbatim into the cloud telemetry,
 * polluting analytics (16k+ rows of Power=3095). These tests pin the sanitizers that drop them.
 */
class DiParsClientSanitizeTest {

    // ---- power ----

    @Test
    fun powerSentinel3095Dropped() {
        assertNull(DiParsClient.sanitizePowerKw(3095.0))
    }

    @Test
    fun realChargePowerKept() {
        assertEquals(-4.0, DiParsClient.sanitizePowerKw(-4.0))
    }

    @Test
    fun realDcFastChargePowerKept() {
        // -102 kW DC fast charge is plausible and must survive.
        assertEquals(-102.0, DiParsClient.sanitizePowerKw(-102.0))
    }

    @Test
    fun highButPlausibleDrivePowerKept() {
        assertEquals(180.0, DiParsClient.sanitizePowerKw(180.0))
    }

    @Test
    fun absurdPositivePowerDropped() {
        assertNull(DiParsClient.sanitizePowerKw(500.0))
    }

    @Test
    fun nullPowerStaysNull() {
        assertNull(DiParsClient.sanitizePowerKw(null))
    }

    // ---- int sentinel (rain etc.) ----

    @Test
    fun rainIntMinSentinelDropped() {
        assertNull(DiParsClient.sanitizeSentinelInt(-2147482648))
    }

    @Test
    fun rainRealValueKept() {
        assertEquals(0, DiParsClient.sanitizeSentinelInt(0))
        assertEquals(255, DiParsClient.sanitizeSentinelInt(255))
    }

    // ---- temps ----

    @Test
    fun cabinTempSentinelMinus2000Dropped() {
        assertNull(DiParsClient.sanitizeTempC(-2000))
    }

    @Test
    fun realTempsKept() {
        assertEquals(27, DiParsClient.sanitizeTempC(27))
        assertEquals(-15, DiParsClient.sanitizeTempC(-15))
        assertEquals(32, DiParsClient.sanitizeTempC(32))
    }

    @Test
    fun nullTempStaysNull() {
        assertNull(DiParsClient.sanitizeTempC(null))
    }
}
