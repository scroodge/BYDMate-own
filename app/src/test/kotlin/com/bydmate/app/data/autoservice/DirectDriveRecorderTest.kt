package com.bydmate.app.data.autoservice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDriveRecorderTest {

    @Test
    fun `record keeps direct values and marks GPS movement without coordinates`() {
        val record = DirectDriveRecord.from(
            capturedAtMs = 1_000L,
            engineAvailable = true,
            snapshot = AutoserviceLiveSnapshot(
                battery = BatteryReading(
                    sohPercent = 99f,
                    socPercent = 87f,
                    lifetimeKwh = 1234.5f,
                    lifetimeMileageKm = 12_345.6f,
                    voltage12v = 13.7f,
                    readAtMs = 1_000L,
                ),
                charging = ChargingReading(
                    gunConnectState = 1,
                    chargingType = 1,
                    chargeBatteryVoltV = 0,
                    batteryType = 1,
                    chargingCapacityKwh = 3.379f,
                    bmsState = null,
                    readAtMs = 1_000L,
                ),
                enginePowerKw = 24,
                capturedAtMs = 1_000L,
            ),
            gpsMarker = GpsMovementMarker(speedKmh = 43.2, accuracyM = 8.0),
        )

        val json = JSONObject(record.toJsonLine())

        assertTrue(json.getBoolean("engine_available"))
        assertEquals(87, json.getInt("soc_percent"))
        assertEquals(24, json.getInt("engine_power_kw"))
        assertEquals(43.2, json.getDouble("gps_speed_kmh"), 0.01)
        assertTrue(json.getBoolean("gps_moving"))
        assertFalse(json.has("latitude"))
        assertFalse(json.has("longitude"))
        val unsupported = json.getJSONArray("unsupported_fields").toString()
        assertTrue(unsupported.contains("speed"))
        assertTrue(unsupported.contains("gear"))
    }

    @Test
    fun `engine outage writes an explicit unavailable sample`() {
        val record = DirectDriveRecord.from(
            capturedAtMs = 2_000L,
            engineAvailable = false,
            snapshot = null,
            location = null,
        )

        val json = JSONObject(record.toJsonLine())

        assertFalse(json.getBoolean("engine_available"))
        assertEquals(JSONObject.NULL, json.get("soc_percent"))
        assertTrue(json.getJSONArray("unsupported_fields").length() > 0)
    }
}
