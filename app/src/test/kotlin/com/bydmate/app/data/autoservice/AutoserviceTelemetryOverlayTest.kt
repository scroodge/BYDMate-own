package com.bydmate.app.data.autoservice

import com.bydmate.app.data.autoservice.AutoserviceTelemetryField as F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raw values are the ones read on car `way` 2026-11-04 (ignition off) next to di+'s
 * getDiPars — the overlay must reproduce di+'s numbers from them.
 */
class AutoserviceTelemetryOverlayTest {

    private val now = 1_000_000L

    private fun snapshot(vararg raw: Pair<F, Int>, at: Long = now): AutoserviceTelemetrySnapshot {
        val values = raw.associate { (f, r) -> f to f.decode(r)!! }
        return AutoserviceTelemetrySnapshot(values, values.keys.associateWith { at })
    }

    private val diPlusLive = AutoserviceTelemetryOverlay.EMPTY_DIPARS.copy(
        soc = 84, socPrecise = 83.6, speed = 0, mileage = 52100.8, power = null,
        maxBatTemp = 8, avgBatTemp = 7, minBatTemp = 6, voltage12v = 12.2,
        maxCellVoltage = 3.335, minCellVoltage = 3.33, totalElecConsumption = 8896.101,
        exteriorTemp = 8, gear = 1, tirePressRR = 220, sunshade = 100, drl = 1,
        maxChargePowerKw = 56.0, bmsState = 15,
    )

    @Test
    fun `live car raw values reproduce di+ numbers without mismatches`() {
        val snap = snapshot(
            F.SPEED to 0x00000000,
            F.MILEAGE to 0x0007f330,
            F.POWER to 0x00000c17, // 3095 = "PID unreadable" sentinel, same as di+
            F.MAX_BATTERY_TEMP to 0x30,
            F.MIN_BATTERY_TEMP to 0x2e,
            F.VOLTAGE_12V to 0x41433333,
            F.MAX_CELL_VOLTAGE to 0xd07,
            F.MIN_CELL_VOLTAGE to 0xd02,
            F.TOTAL_ELEC_CONSUMPTION to 0x460b0067,
            F.EXTERIOR_TEMP to 8,
            F.GEAR to 1,
            F.TIRE_RR to 0xdc,
            F.SUNSHADE to 0x64,
            F.MAX_CHARGE_POWER to 0x230,
            F.BMS_STATE to 0xf,
        )
        val r = AutoserviceTelemetryOverlay.apply(diPlusLive, snap, now)!!
        assertEquals(emptyList<String>(), r.mismatches)
        assertEquals(52100.8, r.data.mileage!!, 1e-9)
        assertNull("3095 sentinel must stay filtered", r.data.power)
        assertEquals(8, r.data.maxBatTemp)
        assertEquals(6, r.data.minBatTemp)
        assertEquals(12.2, r.data.voltage12v!!, 1e-9)
        assertEquals(3.335, r.data.maxCellVoltage!!, 1e-9)
        assertEquals(56.0, r.data.maxChargePowerKw!!, 1e-9)
        assertTrue(F.MILEAGE in r.fromAutoservice)
        assertTrue("power sentinel is not an autoservice value", F.POWER !in r.fromAutoservice)
    }

    @Test
    fun `soc and di+-only fields are never touched`() {
        val r = AutoserviceTelemetryOverlay.apply(diPlusLive, snapshot(F.GEAR to 4), now)!!
        assertEquals(84, r.data.soc)
        assertEquals(83.6, r.data.socPrecise!!, 1e-9)
        assertEquals(1, r.data.drl)
        assertEquals(4, r.data.gear)
        assertEquals(listOf("GEAR di+=1 as=4"), r.mismatches)
    }

    @Test
    fun `stale and implausible values leave the di+ value in place`() {
        val stale = snapshot(F.SPEED to 0x42c80000, at = now - AutoserviceTelemetrySnapshot.FAST_MAX_AGE_MS - 1)
        assertEquals(0, AutoserviceTelemetryOverlay.apply(diPlusLive, stale, now)!!.data.speed)
        val absurd = snapshot(F.EXTERIOR_TEMP to 500)
        assertEquals(8, AutoserviceTelemetryOverlay.apply(diPlusLive, absurd, now)!!.data.exteriorTemp)
    }

    @Test
    fun `sentinels decode to no value`() {
        assertNull(F.INSIDE_TEMP.decode(-10011))
        assertNull(F.VOLTAGE_12V.decode(java.lang.Float.floatToIntBits(-1.0f)))
    }

    @Test
    fun `without di+ the sample is built from autoservice alone`() {
        val r = AutoserviceTelemetryOverlay.apply(
            null,
            snapshot(F.SPEED to 0x42c80000, F.MAX_BATTERY_TEMP to 0x30, F.MIN_BATTERY_TEMP to 0x2e),
            now,
        )
        assertNotNull(r)
        assertEquals(100, r!!.data.speed)
        assertEquals("midpoint when di+ has no average", 7, r.data.avgBatTemp)
        assertNull(r.data.soc)
        assertEquals(emptyList<String>(), r.mismatches)
    }

    @Test
    fun `log line pairs di+ and autoservice per field and marks disagreements`() {
        val snap = snapshot(F.GEAR to 4, F.MILEAGE to 0x0007f330)
        val r = AutoserviceTelemetryOverlay.apply(diPlusLive, snap, now)
        val line = AutoserviceTelemetryLog.formatSample("04.11 14:03:05", diPlusLive, r, snap, null)
        assertTrue(line, line.startsWith("04.11 14:03:05 di+=ok as=2/${F.entries.size} raw=2 err=- |"))
        assertTrue(line, " GEAR=1/4*" in line)
        assertTrue(line, " MILEAGE=52100.8/52100.8 " in line)
        assertTrue(line, " SPEED=0/- " in line)
        assertTrue(line, " LIGHT_LOW=-/-" in line)
    }

    @Test
    fun `nothing at all yields null`() {
        assertNull(AutoserviceTelemetryOverlay.apply(null, AutoserviceTelemetrySnapshot.EMPTY, now))
    }

    @Test
    fun `fast group stays small and every field is in exactly one read chunk`() {
        val all = AutoserviceTelemetryReader.FAST_FIELDS + AutoserviceTelemetryReader.SLOW_CHUNKS.flatten()
        assertEquals(F.entries.toSet(), all.toSet())
        assertEquals(F.entries.size, all.size)
        assertTrue(AutoserviceTelemetryReader.SLOW_CHUNKS.all { it.size <= 6 })
        assertTrue(AutoserviceTelemetryReader.FAST_FIELDS.size <= 6)
    }
}
