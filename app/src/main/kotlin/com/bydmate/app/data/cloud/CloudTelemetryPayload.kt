package com.bydmate.app.data.cloud

import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import org.json.JSONObject

object CloudTelemetryPayload {
    fun build(vehicleId: String, snapshot: VehicleTelemetrySnapshot): String {
        val telemetry = JSONObject().apply {
            putNullable("soc", snapshot.soc)
            putNullable("speed_kmh", snapshot.speedKmh)
            putNullable("power_kw", snapshot.powerKw)
            putNullable("battery_temp_c", snapshot.batteryTempC)
            putNullable("cabin_temp_c", snapshot.cabinTempC)
            putNullable("outside_temp_c", snapshot.outsideTempC)
            putNullable("battery_voltage_v", snapshot.batteryVoltageV)
            putNullable("aux_voltage_v", snapshot.auxVoltageV)
            putNullable("odometer_km", snapshot.odometerKm)
            putNullable("soh_percent", snapshot.sohPercent)
            putNullable("is_charging", snapshot.isCharging)
            putNullable("charge_power_kw", snapshot.chargePowerKw)
            putNullable("charge_type", snapshot.chargeType)
            putNullable("kwh_charged", snapshot.kwhCharged)
            putNullable("range_est_km", snapshot.rangeEstKm)
            putNullable("current_trip_distance_km", snapshot.currentTripDistanceKm)
            putNullable("current_trip_consumption_kwh_100km", snapshot.currentTripConsumptionKwh100km)
        }
        val location = JSONObject().apply {
            putNullable("lat", snapshot.location?.lat)
            putNullable("lon", snapshot.location?.lon)
            putNullable("accuracy_m", snapshot.location?.accuracyM)
            putNullable("bearing_deg", snapshot.location?.bearingDeg)
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("vehicle_id", vehicleId)
            put("device_time", snapshot.deviceTimeIso)
            put("source", "BYDMate")
            put("telemetry", telemetry)
            put("location", location)
        }.toString()
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }
}
