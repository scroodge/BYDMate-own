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

    // ---- di+ 1.x / 2.x wire-format tolerance ----
    //
    // Captured live from car `way` on 2026-08-14 running di+ 2.0.0b1 (versionCode 158):
    //   SOC:76.1|Speed:0|...|InsideTemp:-2000|...|Rain:-2147482648|...|Sentry:{哨兵状态}
    // di+ 1.3.8b16 sent the same fields as whole integers. Both must parse.

    @Test
    fun fractionalSocFromDiPlus2Parsed() {
        // The regression: toIntOrNull("76.1") == null, so SOC vanished entirely.
        assertEquals(76.1, DiParsClient.parseNum("76.1"))
        assertEquals(76, DiParsClient.parseIntNum("76.1"))
    }

    @Test
    fun wholeSocFromDiPlus1Parsed() {
        assertEquals(37.0, DiParsClient.parseNum("37"))
        assertEquals(37, DiParsClient.parseIntNum("37"))
    }

    @Test
    fun commaDecimalLocaleParsed() {
        // di+ formats via NumberFormat.getInstance(), which is locale-sensitive; a head unit
        // with a comma-decimal locale emits "76,1".
        assertEquals(76.1, DiParsClient.parseNum("76,1"))
        assertEquals(76, DiParsClient.parseIntNum("76,1"))
    }

    @Test
    fun unsubstitutedPlaceholderIsNull() {
        // di+ 2.0 leaves the placeholder in place when a parameter is gated off, rather
        // than substituting a value. Must read as absent, never as a number.
        assertNull(DiParsClient.parseNum("{哨兵状态}"))
        assertNull(DiParsClient.parseIntNum("{哨兵状态}"))
        assertNull(DiParsClient.parseNum("[电源状态]"))
    }

    @Test
    fun sentinelsStillReachTheirSanitizers() {
        // Confirmed unchanged in di+ 2.0.0b1 — parseIntNum must pass them through so the
        // existing sanitizers, not the parser, remain the thing that drops them.
        assertEquals(-2000, DiParsClient.parseIntNum("-2000"))
        assertNull(DiParsClient.sanitizeTempC(DiParsClient.parseIntNum("-2000")))
        assertEquals(-2147482648, DiParsClient.parseIntNum("-2147482648"))
        assertNull(DiParsClient.sanitizeSentinelInt(DiParsClient.parseIntNum("-2147482648")))
    }

    @Test
    fun blankAndGarbageAreNull() {
        assertNull(DiParsClient.parseNum(null))
        assertNull(DiParsClient.parseNum(""))
        assertNull(DiParsClient.parseNum("   "))
        assertNull(DiParsClient.parseNum("开启缩时哨兵"))
    }

    @Test
    fun negativeChargePowerStillParses() {
        assertEquals(-4.0, DiParsClient.parseNum("-4"))
        assertEquals(-102.5, DiParsClient.parseNum("-102.5"))
    }

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
