package com.bydmate.app.data.cloud

import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTelemetryPayloadTest {
    @Test
    fun `payload includes DiPlus cell voltages and delta`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = 3.31, minCellVoltage = 3.30, speed = 12, gear = 4),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val json = JSONObject(CloudTelemetryPayload.build("way", snapshot))
        val telemetry = json.getJSONObject("telemetry")

        assertEquals(3.30, telemetry.getDouble("cell_voltage_min_v"), 0.0001)
        assertEquals(3.31, telemetry.getDouble("cell_voltage_max_v"), 0.0001)
        assertEquals(0.01, telemetry.getDouble("cell_delta_v"), 0.0001)
    }

    @Test
    fun `idle parked payload includes soh_percent when snapshot has cached SoH`() {
        val snapshot = VehicleTelemetrySnapshot(
            capturedAtMs = 1_700_000_000_000L,
            deviceTimeIso = "2026-06-05T10:00:00Z",
            diPlusData = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 0, gear = 1),
            soc = 82,
            speedKmh = 0.0,
            powerKw = null,
            batteryTempC = null,
            cabinTempC = null,
            outsideTempC = null,
            batteryVoltageV = null,
            auxVoltageV = null,
            cellVoltageMinV = null,
            cellVoltageMaxV = null,
            cellDeltaV = null,
            odometerKm = null,
            sohPercent = 98.5,
            isCharging = false,
            chargePowerKw = null,
            chargeType = null,
            kwhCharged = null,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            isParked = true,
            tirePressFL = null,
            tirePressFR = null,
            tirePressRL = null,
            tirePressRR = null,
            location = null,
        )

        val telemetry = JSONObject(CloudTelemetryPayload.build("way", snapshot))
            .getJSONObject("telemetry")

        assertEquals(98.5, telemetry.getDouble("soh_percent"), 0.01)
    }

    @Test
    fun `idle parked payload omits soh_percent when unknown`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 0, gear = 1),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val telemetry = JSONObject(CloudTelemetryPayload.build("way", snapshot))
            .getJSONObject("telemetry")

        assertEquals(false, telemetry.has("soh_percent"))
    }

    @Test
    fun `idle parked payload includes gear in diplus`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 0, gear = 1),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val json = JSONObject(CloudTelemetryPayload.build("way", snapshot))
        val diPlus = json.getJSONObject("diplus")

        assertEquals(1, diPlus.getInt("gear"))
        assertEquals(1, diPlus.getInt("charge_gun_state"))
        assertEquals(240, diPlus.getInt("tire_press_fl_kpa"))
        assertEquals(241, diPlus.getInt("tire_press_fr_kpa"))
        assertEquals(239, diPlus.getInt("tire_press_rl_kpa"))
        assertEquals(242, diPlus.getInt("tire_press_rr_kpa"))
    }

    @Test
    fun `latched drive payload keeps speed at zero`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 0, gear = 1),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val telemetry = JSONObject(
            CloudTelemetryPayload.build(
                "way",
                snapshot,
                telemetryState = com.bydmate.app.data.remote.IternioIntervalPolicy.TelemetryState.DRIVING,
            ),
        ).getJSONObject("telemetry")

        assertEquals(0.0, telemetry.getDouble("speed_kmh"), 0.0001)
    }

    @Test
    fun `payload includes full DiPlus object when driving`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = 3.31, minCellVoltage = 3.30, speed = 12, gear = 4),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val json = JSONObject(CloudTelemetryPayload.build("way", snapshot))
        val diPlus = json.getJSONObject("diplus")

        assertEquals(73, diPlus.getInt("soc"))
        assertEquals(12345.0, diPlus.getDouble("mileage_km"), 0.0001)
        assertEquals(72.9, diPlus.getDouble("battery_capacity_kwh"), 0.0001)
        assertEquals(3456.0, diPlus.getDouble("total_elec_consumption_kwh"), 0.0001)
        assertEquals(0.01, diPlus.getDouble("cell_delta_v"), 0.0001)
        assertEquals(4, diPlus.getInt("gear"))
        assertEquals(240, diPlus.getInt("tire_press_fl_kpa"))
        assertEquals(241, diPlus.getInt("tire_press_fr_kpa"))
        assertEquals(239, diPlus.getInt("tire_press_rl_kpa"))
        assertEquals(242, diPlus.getInt("tire_press_rr_kpa"))
    }

    @Test
    fun `payload omits unknown tyre pressures`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(
                maxCellVoltage = null,
                minCellVoltage = null,
                tirePressFL = null,
                tirePressFR = null,
                tirePressRL = null,
                tirePressRR = null,
            ),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val diPlus = JSONObject(CloudTelemetryPayload.build("way", snapshot))
            .getJSONObject("diplus")

        assertEquals(false, diPlus.has("tire_press_fl_kpa"))
        assertEquals(false, diPlus.has("tire_press_fr_kpa"))
        assertEquals(false, diPlus.has("tire_press_rl_kpa"))
        assertEquals(false, diPlus.has("tire_press_rr_kpa"))
    }

    @Test
    fun `live_only flag is omitted by default and set when requested`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 0, gear = 1),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val normal = JSONObject(CloudTelemetryPayload.build("way", snapshot))
        assertEquals(false, normal.has("live_only"))

        val liveOnly = JSONObject(CloudTelemetryPayload.build("way", snapshot, liveOnly = true))
        assertEquals(true, liveOnly.getBoolean("live_only"))
    }

    @Test
    fun `live_only payload still carries the status diplus block the live view reads`() {
        // The server's live-snapshot persistence check asserts diplus survives, so a
        // live_only heartbeat must keep sending it even though no history row is stored.
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 0, gear = 1),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val diPlus = JSONObject(CloudTelemetryPayload.build("way", snapshot, liveOnly = true))
            .getJSONObject("diplus")

        assertEquals(1, diPlus.getInt("gear"))
        assertEquals(1, diPlus.getInt("charge_gun_state"))
        assertEquals(12.6, diPlus.getDouble("voltage_12v"), 0.0001)
    }

    @Test
    fun `cell delta is rounded rather than carrying raw double noise`() {
        // 3.31 - 3.30 in IEEE doubles is 0.010000000000000231; unrounded that
        // serializes to ~20 characters on every sample.
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = 3.31, minCellVoltage = 3.30, speed = 12, gear = 4),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val raw = CloudTelemetryPayload.build("way", snapshot)

        assertEquals(false, raw.contains("0.010000000000000"))
        assertEquals(
            0.01,
            JSONObject(raw).getJSONObject("telemetry").getDouble("cell_delta_v"),
            0.00001,
        )
    }

    @Test
    fun `trip_id and client_trip are omitted by default and set when a trip is open`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 40, gear = 4),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val normal = JSONObject(CloudTelemetryPayload.build("way", snapshot))
        assertEquals(false, normal.has("trip_id"))
        assertEquals(false, normal.has("client_trip"))

        val tripped = JSONObject(
            CloudTelemetryPayload.build("way", snapshot, tripId = "abc-123", clientTrip = true),
        )
        assertEquals("abc-123", tripped.getString("trip_id"))
        assertEquals(true, tripped.getBoolean("client_trip"))
    }

    @Test
    fun `client_trip is not set when a trip id is missing even if requested`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 40, gear = 4),
            battery = null,
            charging = null,
            enginePowerKw = null,
            capturedAtMs = 1_700_000_000_000L,
            rangeEstKm = null,
            currentTripDistanceKm = null,
            currentTripConsumptionKwh100km = null,
            location = null,
        )

        val json = JSONObject(CloudTelemetryPayload.build("way", snapshot, tripId = null, clientTrip = true))
        assertEquals(false, json.has("trip_id"))
        assertEquals(false, json.has("client_trip"))
    }

    @Test
    fun `buildBatch attaches a trips array alongside hourly`() {
        val samples = listOf(CloudTelemetryPayload.build("way", sampleSnapshot()))
        val hourly = JSONObject().apply { put("hour_start", "2026-07-17T10:00:00Z") }
        val trip = JSONObject().apply { put("trip_id", "abc-123") }

        val batch = JSONObject(CloudTelemetryPayload.buildBatch(samples, listOf(hourly), listOf(trip)))
        assertEquals(1, batch.getJSONArray("samples").length())
        assertEquals("2026-07-17T10:00:00Z", batch.getJSONArray("hourly").getJSONObject(0).getString("hour_start"))
        assertEquals("abc-123", batch.getJSONArray("trips").getJSONObject(0).getString("trip_id"))
    }

    @Test
    fun `buildBatch omits the trips array when there are no trip blocks`() {
        val samples = listOf(CloudTelemetryPayload.build("way", sampleSnapshot()))
        val batch = JSONObject(CloudTelemetryPayload.buildBatch(samples))
        assertEquals(false, batch.has("trips"))
    }

    private fun sampleSnapshot() = VehicleTelemetrySnapshot.from(
        data = diPlusData(maxCellVoltage = null, minCellVoltage = null, speed = 0, gear = 1),
        battery = null,
        charging = null,
        enginePowerKw = null,
        capturedAtMs = 1_700_000_000_000L,
        rangeEstKm = null,
        currentTripDistanceKm = null,
        currentTripConsumptionKwh100km = null,
        location = null,
    )

    private fun diPlusData(
        maxCellVoltage: Double?,
        minCellVoltage: Double?,
        speed: Int = 0,
        gear: Int = 1,
        tirePressFL: Int? = 240,
        tirePressFR: Int? = 241,
        tirePressRL: Int? = 239,
        tirePressRR: Int? = 242,
    ) = DiParsData(
        soc = 73,
        speed = speed,
        mileage = 12345.0,
        power = 0.0,
        chargeGunState = 1,
        maxBatTemp = 28,
        avgBatTemp = 26,
        minBatTemp = 24,
        chargingStatus = 0,
        batteryCapacityKwh = 72.9,
        totalElecConsumption = 3456.0,
        voltage12v = 12.6,
        maxCellVoltage = maxCellVoltage,
        minCellVoltage = minCellVoltage,
        exteriorTemp = 18,
        gear = gear,
        powerState = 1,
        insideTemp = 22,
        acStatus = 0,
        acTemp = 22,
        fanLevel = 0,
        acCirc = 0,
        doorFL = 0,
        doorFR = 0,
        doorRL = 0,
        doorRR = 0,
        windowFL = 0,
        windowFR = 0,
        windowRL = 0,
        windowRR = 0,
        sunroof = 0,
        trunk = 0,
        hood = 0,
        seatbeltFL = 1,
        lockFL = 2,
        tirePressFL = tirePressFL,
        tirePressFR = tirePressFR,
        tirePressRL = tirePressRL,
        tirePressRR = tirePressRR,
        driveMode = 1,
        workMode = 1,
        autoPark = 0,
        rain = 0,
        lightLow = 0,
        drl = 1,
        sunshade = null,
        sentryState = null,
        remoteLockState = null,
    )
}
