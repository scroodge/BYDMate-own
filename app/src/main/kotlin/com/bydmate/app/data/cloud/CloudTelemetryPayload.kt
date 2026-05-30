package com.bydmate.app.data.cloud

import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import com.bydmate.app.data.remote.DiParsData
import org.json.JSONArray
import org.json.JSONObject

object CloudTelemetryPayload {
    fun build(
        vehicleId: String,
        snapshot: VehicleTelemetrySnapshot,
        omitGps: Boolean = false,
    ): String {
        val moving = (snapshot.speedKmh ?: 0.0) > MOVING_SPEED_THRESHOLD_KMH
        val charging = snapshot.isCharging == true ||
            kotlin.math.abs(snapshot.chargePowerKw ?: snapshot.powerKw ?: 0.0) > CHARGING_POWER_THRESHOLD_KW
        val idleOnly = !moving && !charging

        val telemetry = JSONObject().apply {
            putIfPresent("soc", snapshot.soc)
            if (!idleOnly || snapshot.soc != null) {
                putIfPresent("is_charging", snapshot.isCharging)
            }
            if (moving || charging) {
                putIfPresent("speed_kmh", snapshot.speedKmh)
                putIfPresent("power_kw", snapshot.powerKw)
            }
            if (charging) {
                putIfPresent("charge_power_kw", snapshot.chargePowerKw)
                putIfPresent("charge_type", snapshot.chargeType)
                putIfPresent("kwh_charged", snapshot.kwhCharged)
            }
            if (!idleOnly) {
                putIfPresent("battery_temp_c", snapshot.batteryTempC)
                putIfPresent("cabin_temp_c", snapshot.cabinTempC)
                putIfPresent("outside_temp_c", snapshot.outsideTempC)
                putIfPresent("battery_voltage_v", snapshot.batteryVoltageV)
                putIfPresent("aux_voltage_v", snapshot.auxVoltageV)
            }
            if (!idleOnly || snapshot.cellVoltageMinV != null || snapshot.cellVoltageMaxV != null) {
                putIfPresent("cell_voltage_min_v", snapshot.cellVoltageMinV)
                putIfPresent("cell_voltage_max_v", snapshot.cellVoltageMaxV)
                putIfPresent("cell_delta_v", snapshot.cellDeltaV)
            }
            if (!idleOnly) {
                putIfPresent("odometer_km", snapshot.odometerKm)
                putIfPresent("soh_percent", snapshot.sohPercent)
                putIfPresent("range_est_km", snapshot.rangeEstKm)
                putIfPresent("current_trip_distance_km", snapshot.currentTripDistanceKm)
                putIfPresent(
                    "current_trip_consumption_kwh_100km",
                    snapshot.currentTripConsumptionKwh100km,
                )
            }
        }

        val location = if (omitGps) {
            JSONObject()
        } else {
            val loc = snapshot.location
            val accuracy = loc?.accuracyM
            val lat = loc?.lat
            val lon = loc?.lon
            val usableGps = lat != null && lon != null && (accuracy == null || accuracy <= MAX_GPS_ACCURACY_M)
            if (!usableGps) {
                JSONObject()
            } else {
                JSONObject().apply {
                    putIfPresent("lat", lat)
                    putIfPresent("lon", lon)
                    putIfPresent("accuracy_m", accuracy)
                    putIfPresent("bearing_deg", loc?.bearingDeg)
                }
            }
        }

        return JSONObject().apply {
            put("schema_version", 1)
            put("vehicle_id", vehicleId)
            put("device_time", snapshot.deviceTimeIso)
            put("source", "BYDMate")
            put("telemetry", telemetry)
            if (!idleOnly && snapshot.diPlusData != null) {
                put("diplus", snapshot.diPlusData.toJson(includePower = moving || charging))
            }
            put("location", location)
        }.toString()
    }

    fun buildBatch(payloads: List<String>): String {
        val samples = JSONArray()
        payloads.forEach { payload ->
            samples.put(JSONObject(payload))
        }
        return JSONObject()
            .put("samples", samples)
            .toString()
    }

    private fun JSONObject.putIfPresent(name: String, value: Any?) {
        if (value == null) return
        put(name, value)
    }

    private fun DiParsData.toJson(includePower: Boolean): JSONObject = JSONObject().apply {
        putIfPresent("soc", soc)
        if (includePower) {
            putIfPresent("speed_kmh", speed)
            putIfPresent("mileage_km", mileage)
            putIfPresent("power_kw", power)
            putIfPresent("charge_gun_state", chargeGunState)
            putIfPresent("charging_status", chargingStatus)
        }
        putIfPresent("max_battery_temp_c", maxBatTemp)
        putIfPresent("avg_battery_temp_c", avgBatTemp)
        putIfPresent("min_battery_temp_c", minBatTemp)
        putIfPresent("battery_capacity_kwh", batteryCapacityKwh)
        putIfPresent("total_elec_consumption_kwh", totalElecConsumption)
        putIfPresent("voltage_12v", voltage12v)
        putIfPresent("max_cell_voltage_v", maxCellVoltage)
        putIfPresent("min_cell_voltage_v", minCellVoltage)
        putIfPresent("cell_delta_v", if (maxCellVoltage != null && minCellVoltage != null) {
            maxCellVoltage - minCellVoltage
        } else {
            null
        })
    }

    private const val MOVING_SPEED_THRESHOLD_KMH = 0.5
    private const val CHARGING_POWER_THRESHOLD_KW = 0.1
    private const val MAX_GPS_ACCURACY_M = 30.0
}
