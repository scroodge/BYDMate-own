package com.bydmate.app.data.cloud

import com.bydmate.app.data.local.entity.HourlyRollupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The server keeps its own copy of this arithmetic until Phase 6 retires it, so these tests pin
 * the client to what `bydmate_ingest_telemetry` and `bydmate_interval_energy_kwh` do today.
 */
@RunWith(RobolectricTestRunner::class)
class HourlyRollupAccumulatorTest {

    @Test
    fun `hourStartOf truncates to the utc hour`() {
        val t = Instant.parse("2026-07-17T10:23:41.512Z").toEpochMilli()
        assertEquals(
            Instant.parse("2026-07-17T10:00:00Z").toEpochMilli(),
            HourlyRollupAccumulator.hourStartOf(t),
        )
    }

    @Test
    fun `hourStartOf truncates toward the past before the epoch`() {
        // floorDiv, not integer division: a naive `/` would round a pre-1970 instant up an hour.
        val t = Instant.parse("1969-12-31T23:30:00Z").toEpochMilli()
        assertEquals(
            Instant.parse("1969-12-31T23:00:00Z").toEpochMilli(),
            HourlyRollupAccumulator.hourStartOf(t),
        )
    }

    @Test
    fun `fold accumulates counts extremes and sums`() {
        var rollup: HourlyRollupEntity? = null
        listOf(50.0 to 10.0, 52.0 to 20.0, 48.0 to 30.0).forEach { (soc, power) ->
            rollup = fold(rollup, payload(soc = soc, powerKw = power, speedKmh = power))
        }

        val result = rollup!!
        assertEquals(3, result.sampleCount)
        assertEquals(48.0, result.socMin!!, 1e-9)
        assertEquals(52.0, result.socMax!!, 1e-9)
        assertEquals(48.0, result.socLast!!, 1e-9)
        assertEquals(30.0, result.speedMax!!, 1e-9)
        assertEquals(60.0, result.powerSum, 1e-9)
        assertEquals(3, result.powerSampleCount)
    }

    @Test
    fun `fold does not count fields the payload omits`() {
        // A parked payload carries no battery_temp_c; the server's per-field sample counts stay
        // at 0 for it, so the client must not fold a 0 in and drag the average down.
        val rollup = fold(null, """{"device_time":"$DEVICE_TIME","telemetry":{"soc":50}}""")

        assertEquals(0, rollup.batteryTempSampleCount)
        assertEquals(0.0, rollup.batteryTempSum, 1e-9)
        assertEquals(0, rollup.powerSampleCount)

        val json = HourlyRollupAccumulator.toJson(rollup)
        assertFalse(json.has("battery_temp_avg"))
        assertFalse(json.has("power_avg"))
        assertEquals(0, json.getInt("battery_temp_sample_count"))
    }

    @Test
    fun `toJson emits the mean and the utc hour`() {
        var rollup: HourlyRollupEntity? = null
        listOf(10.0, 20.0, 30.0).forEach { rollup = fold(rollup, payload(powerKw = it)) }

        val json = HourlyRollupAccumulator.toJson(rollup!!)
        assertEquals("2026-07-17T10:00:00Z", json.getString("hour_start"))
        assertEquals(3, json.getInt("sample_count"))
        assertEquals(20.0, json.getDouble("power_avg"), 1e-9)
        assertEquals(3, json.getInt("power_sample_count"))
    }

    @Test
    fun `a new hour starts a fresh block`() {
        val first = fold(null, payload(soc = 50.0), at = "2026-07-17T10:59:59Z")
        val second = fold(first, payload(soc = 60.0), at = "2026-07-17T11:00:01Z")

        assertEquals(1, second.sampleCount)
        assertEquals(60.0, second.socMin!!, 1e-9)
        assertEquals(
            Instant.parse("2026-07-17T11:00:00Z").toEpochMilli(),
            second.hourStart,
        )
    }

    @Test
    fun `interval energy integrates positive power as traction`() {
        val energy = HourlyRollupAccumulator.intervalEnergy(10.0, 20.0, 3600.0)
        assertEquals(15.0, energy.tractionKwh, 1e-9)
        assertEquals(0.0, energy.regenKwh, 1e-9)
    }

    @Test
    fun `interval energy integrates negative power as regen`() {
        val energy = HourlyRollupAccumulator.intervalEnergy(-10.0, -20.0, 3600.0)
        assertEquals(0.0, energy.tractionKwh, 1e-9)
        assertEquals(15.0, energy.regenKwh, 1e-9)
    }

    @Test
    fun `interval energy splits a zero crossing between traction and regen`() {
        // +10 -> -10 over 1h: crosses zero at the midpoint, leaving a triangle each side.
        val energy = HourlyRollupAccumulator.intervalEnergy(10.0, -10.0, 3600.0)
        assertEquals(2.5, energy.tractionKwh, 1e-9)
        assertEquals(2.5, energy.regenKwh, 1e-9)

        val reversed = HourlyRollupAccumulator.intervalEnergy(-10.0, 10.0, 3600.0)
        assertEquals(2.5, reversed.tractionKwh, 1e-9)
        assertEquals(2.5, reversed.regenKwh, 1e-9)
    }

    @Test
    fun `interval energy is zero without a previous reading`() {
        assertEquals(0.0, HourlyRollupAccumulator.intervalEnergy(null, 10.0, 60.0).tractionKwh, 1e-9)
        assertEquals(0.0, HourlyRollupAccumulator.intervalEnergy(10.0, 20.0, 0.0).tractionKwh, 1e-9)
    }

    @Test
    fun `fold integrates energy against the previous sample`() {
        // 10kW -> 20kW over 180s (the longest gap the server integrates): 15kW mean * 0.05h.
        val start = Instant.parse(DEVICE_TIME).toEpochMilli()
        val rollup = HourlyRollupAccumulator.fold(
            existing = null,
            vehicleId = VEHICLE,
            payloadJson = payload(powerKw = 20.0),
            deviceTimeMs = start,
            previousPowerKw = 10.0,
            previousDeviceTimeMs = start - 180_000L,
            now = 0L,
        )
        assertEquals(0.75, rollup.tractionKwhSum, 1e-9)
    }

    @Test
    fun `fold skips energy across a gap longer than the server integrates`() {
        // bydmate_update_hourly_energy discards gaps > 180s; power in between is unknown.
        val start = Instant.parse(DEVICE_TIME).toEpochMilli()
        val rollup = HourlyRollupAccumulator.fold(
            existing = null,
            vehicleId = VEHICLE,
            payloadJson = payload(powerKw = 20.0),
            deviceTimeMs = start,
            previousPowerKw = 10.0,
            previousDeviceTimeMs = start - 181_000L,
            now = 0L,
        )
        assertEquals(0.0, rollup.tractionKwhSum, 1e-9)
        assertEquals(0.0, rollup.regenKwhSum, 1e-9)
    }

    @Test
    fun `folding marks the hour dirty again`() {
        val settled = fold(null, payload()).copy(dirty = false)
        assertTrue(fold(settled, payload()).dirty)
    }

    @Test
    fun `deviceTimeMsOf reads the payload timestamp and tolerates junk`() {
        assertEquals(Instant.parse(DEVICE_TIME).toEpochMilli(), HourlyRollupAccumulator.deviceTimeMsOf(payload()))
        assertNull(HourlyRollupAccumulator.deviceTimeMsOf("""{"device_time":"not-a-time"}"""))
        assertNull(HourlyRollupAccumulator.deviceTimeMsOf("{"))
    }

    private fun fold(
        existing: HourlyRollupEntity?,
        payloadJson: String,
        at: String = DEVICE_TIME,
    ) = HourlyRollupAccumulator.fold(
        existing = existing,
        vehicleId = VEHICLE,
        payloadJson = payloadJson,
        deviceTimeMs = Instant.parse(at).toEpochMilli(),
        previousPowerKw = null,
        previousDeviceTimeMs = null,
        now = 0L,
    )

    private fun payload(
        soc: Double? = 50.0,
        powerKw: Double? = null,
        speedKmh: Double? = null,
    ): String {
        val fields = buildList {
            soc?.let { add(""""soc":$it""") }
            powerKw?.let { add(""""power_kw":$it""") }
            speedKmh?.let { add(""""speed_kmh":$it""") }
        }.joinToString(",")
        return """{"device_time":"$DEVICE_TIME","telemetry":{$fields}}"""
    }

    private companion object {
        const val VEHICLE = "way"
        const val DEVICE_TIME = "2026-07-17T10:23:41Z"
    }
}
