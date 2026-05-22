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
            data = diPlusData(maxCellVoltage = 3.31, minCellVoltage = 3.30),
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
        assertEquals(3.30, telemetry.getDouble("diplus_min_cell_voltage_v"), 0.0001)
        assertEquals(3.31, telemetry.getDouble("diplus_max_cell_voltage_v"), 0.0001)
        assertEquals(0.01, telemetry.getDouble("diplus_cell_delta_v"), 0.0001)
    }

    @Test
    fun `payload includes full DiPlus object`() {
        val snapshot = VehicleTelemetrySnapshot.from(
            data = diPlusData(maxCellVoltage = 3.31, minCellVoltage = 3.30),
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
        assertEquals(240, diPlus.getInt("tire_press_fl_kpa"))
        assertEquals(1, diPlus.getInt("seatbelt_fl"))
        assertEquals(2, diPlus.getInt("lock_fl"))
        assertEquals(1, diPlus.getInt("drl"))
    }

    private fun diPlusData(
        maxCellVoltage: Double?,
        minCellVoltage: Double?,
    ) = DiParsData(
        soc = 73,
        speed = 0,
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
        gear = 1,
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
        tirePressFL = 240,
        tirePressFR = 241,
        tirePressRL = 239,
        tirePressRR = 242,
        driveMode = 1,
        workMode = 1,
        autoPark = 0,
        rain = 0,
        lightLow = 0,
        drl = 1,
    )
}
