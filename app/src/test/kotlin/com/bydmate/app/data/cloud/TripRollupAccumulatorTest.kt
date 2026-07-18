package com.bydmate.app.data.cloud

import com.bydmate.app.data.local.entity.TripRollupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The server keeps its own copy of trip open/extend/close arithmetic until the cloud-side RPC
 * ships and takes over `client_trip`-tagged samples, so these tests pin the client to the same
 * triggers and math `bydmate_ingest_telemetry`'s trip logic uses today.
 */
@RunWith(RobolectricTestRunner::class)
class TripRollupAccumulatorTest {

    @Test
    fun `open captures baselines and starts sample count at 1`() {
        val opened = TripRollupAccumulator.open(
            vehicleId = VEHICLE,
            tripId = TRIP_ID,
            startedAtMs = at(DEVICE_TIME),
            payloadJson = payload(soc = 80.0, speedKmh = 12.0, odometerKm = 1000.0, totalElecKwh = 500.0),
            deviceTimeMs = at(DEVICE_TIME),
            now = 0L,
        )

        assertEquals(TRIP_ID, opened.tripId)
        assertEquals(VEHICLE, opened.vehicleId)
        assertEquals(1, opened.sampleCount)
        assertEquals(1000.0, opened.distanceBaselineKm!!, 1e-9)
        assertEquals(500.0, opened.consumptionBaselineKwh!!, 1e-9)
        assertEquals(80.0, opened.socStart!!, 1e-9)
        assertEquals(80.0, opened.socEnd!!, 1e-9)
        assertEquals(12.0, opened.maxSpeedKmh!!, 1e-9)
        assertNull(opened.endedAt)
        assertTrue(opened.dirty)
    }

    @Test
    fun `fold extends sample count soc end and max speed without touching soc start`() {
        var trip = open()
        trip = fold(trip, payload(soc = 90.0, speedKmh = 40.0, odometerKm = 1005.0, totalElecKwh = 501.0))
        trip = fold(trip, payload(soc = 70.0, speedKmh = 20.0, odometerKm = 1010.0, totalElecKwh = 502.0))

        assertEquals(3, trip.sampleCount)
        assertEquals(50.0, trip.socStart!!, 1e-9) // open()'s default soc, unchanged by later folds
        assertEquals(70.0, trip.socEnd!!, 1e-9) // last sample wins
        assertEquals(40.0, trip.maxSpeedKmh!!, 1e-9) // max across all samples
        assertEquals(1010.0, trip.lastOdometerKm!!, 1e-9)
        assertEquals(502.0, trip.lastTotalElecConsumptionKwh!!, 1e-9)
    }

    @Test
    fun `toJson derives distance from the odometer baseline not GPS`() {
        var trip = open(odometerKm = 1000.0)
        trip = fold(trip, payload(odometerKm = 1012.5))

        val json = TripRollupAccumulator.toJson(trip)
        assertEquals(12.5, json.getDouble("distance_km"), 1e-9)
    }

    @Test
    fun `toJson derives avg consumption from energy over distance not a running mean`() {
        var trip = open(odometerKm = 1000.0, totalElecKwh = 500.0)
        trip = fold(trip, payload(odometerKm = 1010.0, totalElecKwh = 502.0)) // 2 kWh over 10 km = 20 kWh/100km

        val json = TripRollupAccumulator.toJson(trip)
        assertEquals(20.0, json.getDouble("avg_consumption_kwh_100km"), 1e-9)
    }

    @Test
    fun `toJson omits distance and consumption when a baseline never landed`() {
        val trip = open(odometerKm = null, totalElecKwh = null)
        val json = TripRollupAccumulator.toJson(trip)
        assertFalse(json.has("distance_km"))
        assertFalse(json.has("avg_consumption_kwh_100km"))
    }

    @Test
    fun `toJson field names match the bydmate_trips columns`() {
        var trip = open()
        trip = fold(trip, payload(speedKmh = 30.0))

        val json = TripRollupAccumulator.toJson(trip)
        assertEquals(TRIP_ID, json.getString("trip_id"))
        assertEquals(DEVICE_TIME, json.getString("started_at"))
        assertTrue(json.has("last_device_time"))
        assertFalse(json.has("ended_at")) // still open
        assertEquals(2, json.getInt("sample_count"))
        assertTrue(json.has("avg_speed_kmh"))
        assertTrue(json.has("max_speed_kmh"))
        assertTrue(json.has("regen_energy_kwh"))
        assertTrue(json.has("traction_energy_kwh"))
    }

    @Test
    fun `close stamps ended_at from the trip's own last device time not now`() {
        var trip = open(at = "2026-07-18T09:00:00Z")
        trip = fold(trip, payload(), at = "2026-07-18T09:05:00Z")
        val closed = TripRollupAccumulator.close(trip, now = at("2026-07-18T09:20:00Z"))

        assertEquals(at("2026-07-18T09:05:00Z"), closed.endedAt)
        assertTrue(closed.dirty)

        val json = TripRollupAccumulator.toJson(closed)
        assertEquals("2026-07-18T09:05:00Z", json.getString("ended_at"))
    }

    @Test
    fun `fold integrates energy against the previous sample like the hourly accumulator`() {
        var trip = open(at = DEVICE_TIME)
        // 10kW -> 20kW over 180s: 15kW mean * 0.05h = 0.75 kWh traction.
        trip = TripRollupAccumulator.fold(
            existing = trip,
            payloadJson = payload(powerKw = 20.0),
            deviceTimeMs = at(DEVICE_TIME) + 180_000L,
            previousPowerKw = 10.0,
            previousDeviceTimeMs = at(DEVICE_TIME),
            now = 0L,
        )
        assertEquals(0.75, trip.tractionKwhSum, 1e-9)
    }

    @Test
    fun `folding marks a settled trip dirty again`() {
        val settled = open().copy(dirty = false)
        assertTrue(fold(settled, payload()).dirty)
    }

    private fun open(
        odometerKm: Double? = 1000.0,
        totalElecKwh: Double? = 500.0,
        at: String = DEVICE_TIME,
    ) = TripRollupAccumulator.open(
        vehicleId = VEHICLE,
        tripId = TRIP_ID,
        startedAtMs = at(at),
        payloadJson = payload(odometerKm = odometerKm, totalElecKwh = totalElecKwh),
        deviceTimeMs = at(at),
        now = 0L,
    )

    private fun fold(existing: TripRollupEntity, payloadJson: String, at: String = DEVICE_TIME) =
        TripRollupAccumulator.fold(
            existing = existing,
            payloadJson = payloadJson,
            deviceTimeMs = at(at),
            previousPowerKw = null,
            previousDeviceTimeMs = null,
            now = 0L,
        )

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun payload(
        soc: Double? = 50.0,
        powerKw: Double? = null,
        speedKmh: Double? = null,
        odometerKm: Double? = null,
        totalElecKwh: Double? = null,
    ): String {
        val telemetryFields = buildList {
            soc?.let { add(""""soc":$it""") }
            powerKw?.let { add(""""power_kw":$it""") }
            speedKmh?.let { add(""""speed_kmh":$it""") }
            odometerKm?.let { add(""""odometer_km":$it""") }
        }.joinToString(",")
        val diplusFields = buildList {
            totalElecKwh?.let { add(""""total_elec_consumption_kwh":$it""") }
        }.joinToString(",")
        val diplus = if (diplusFields.isEmpty()) "" else ""","diplus":{$diplusFields}"""
        return """{"device_time":"$DEVICE_TIME","telemetry":{$telemetryFields}$diplus}"""
    }

    private companion object {
        const val VEHICLE = "way"
        const val TRIP_ID = "11111111-1111-1111-1111-111111111111"
        const val DEVICE_TIME = "2026-07-17T10:23:41Z"
    }
}
