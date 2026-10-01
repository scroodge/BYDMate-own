package com.bydmate.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the di+ 2.0 `/api/historyStatus` pack reading used to derive charge power as V × I.
 *
 * The bodies below are trimmed copies of what car `way` returned on 2026-10-01 (di+ 2.0.0b8-2):
 * AC charging at 316 V / −17.9 A reported `batteryPower 5.656`, while di+'s integer engine-power
 * parameter read `-5` — the ~11 % under-report this reading replaces.
 */
class HistoryStatusClientTest {

    private val now = 1_790_000_000_000L

    private fun body(voltage: String, current: String) =
        """{"available":true,"schemaVersion":4,"vehicle":{"databaseOpen":true,""" +
            """"batteryPackVoltage":$voltage,"batteryPackCurrent":$current,"batterySoh":97}}"""

    @Test
    fun parsesAChargingReadingWithNegativeCurrent() {
        val pack = HistoryStatusClient.parse(body("316.0", "-17.899994"), now)

        assertEquals(PackReading(316.0, -17.899994, now), pack)
    }

    @Test
    fun parsesTheIdleReadingWithSmallPositiveCurrent() {
        val pack = HistoryStatusClient.parse(body("317.0", "0.3000183"), now)

        assertEquals(PackReading(317.0, 0.3000183, now), pack)
    }

    @Test
    fun anUnparseableBodyOrMissingVehicleObjectIsNoReading() {
        assertNull(HistoryStatusClient.parse("", now))
        assertNull(HistoryStatusClient.parse("<html>403</html>", now))
        assertNull(HistoryStatusClient.parse("""{"available":true}""", now))
        // di+ 1.x has no such endpoint and answers with an error object.
        assertNull(HistoryStatusClient.parse("""{"success":false}""", now))
    }

    @Test
    fun aMissingOrNonFiniteValueIsNoReading() {
        assertNull(HistoryStatusClient.parse("""{"vehicle":{"batteryPackVoltage":316.0}}""", now))
        assertNull(HistoryStatusClient.parse("""{"vehicle":{"batteryPackCurrent":-17.9}}""", now))
        // di+ yields NaN when the validity bit (VALID_BATTERY_PACK_CURRENT) is clear.
        assertNull(HistoryStatusClient.parse(body("316.0", "NaN"), now))
        assertNull(HistoryStatusClient.parse(body("316.0", "\"NaN\""), now))
        assertNull(HistoryStatusClient.parse(body("null", "-17.9"), now))
    }

    @Test
    fun physicallyImplausibleValuesAreNoReading() {
        assertNull(HistoryStatusClient.sanitize(5.0, -17.9, now))
        assertNull(HistoryStatusClient.sanitize(5_000.0, -17.9, now))
        assertNull(HistoryStatusClient.sanitize(316.0, -5_000.0, now))
        assertNull(HistoryStatusClient.sanitize(316.0, 5_000.0, now))
    }

    @Test
    fun chargePowerIsPackVoltageTimesCurrentIntoThePack() {
        // The AC capture: 316 V × 17.899994 A = 5.656 kW (di+ itself reported 5.656398104).
        assertEquals(5.656, HistoryStatusClient.chargePowerKw(PackReading(316.0, -17.899994, now))!!, 1e-9)
        // The DC session in DIPLUS_DATA.md: 332 V × 198.5 A = 65.902 kW = batteryPowerMax.
        assertEquals(65.902, HistoryStatusClient.chargePowerKw(PackReading(332.0, -198.5, now))!!, 1e-9)
    }

    @Test
    fun anIdlePositiveCurrentIsZeroChargePowerNotAFalseStart() {
        // abs() would give 317 × 0.3 / 1000 = 0.095 kW — right at the auto-start threshold
        // (charge_power_kw > 0.1). Only the charging direction counts.
        assertEquals(0.0, HistoryStatusClient.chargePowerKw(PackReading(317.0, 0.3000183, now))!!, 0.0)
        assertEquals(0.0, HistoryStatusClient.chargePowerKw(PackReading(317.0, 0.0, now))!!, 0.0)
    }

    @Test
    fun noReadingIsNullNeverZeroPower() {
        // A missing measurement must not be mistaken for "the charger stopped".
        assertNull(HistoryStatusClient.chargePowerKw(null))
    }

    @Test
    fun aHeldReadingExpiresAfterTheMaxAge() {
        val pack = PackReading(316.0, -17.9, readAtMs = now)

        assertTrue(HistoryStatusClient.isUsable(pack, now))
        assertTrue(HistoryStatusClient.isUsable(pack, now + HistoryStatusClient.MAX_AGE_MS))
        assertFalse(HistoryStatusClient.isUsable(pack, now + HistoryStatusClient.MAX_AGE_MS + 1))
        // A clock that moved backwards is not trusted either.
        assertFalse(HistoryStatusClient.isUsable(pack, now - 1))
        assertFalse(HistoryStatusClient.isUsable(null, now))
    }

    @Test
    fun localhostReadsAreRateLimited() {
        val gap = HistoryStatusClient.MIN_REFETCH_MS

        assertFalse(HistoryStatusClient.shouldRefetch(lastAttemptMs = now, nowMs = now))
        assertFalse(HistoryStatusClient.shouldRefetch(lastAttemptMs = now, nowMs = now + gap - 1))
        assertTrue(HistoryStatusClient.shouldRefetch(lastAttemptMs = now, nowMs = now + gap))
        // First call ever (lastAttemptMs = 0) and a clock that went backwards both refetch.
        assertTrue(HistoryStatusClient.shouldRefetch(lastAttemptMs = 0L, nowMs = now))
        assertTrue(HistoryStatusClient.shouldRefetch(lastAttemptMs = now, nowMs = now - 1))
    }
}
