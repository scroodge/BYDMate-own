package com.bydmate.app.data.cloud

import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import com.bydmate.app.data.remote.DiParsData
import org.json.JSONArray
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
            putNullable("cell_voltage_min_v", snapshot.cellVoltageMinV)
            putNullable("cell_voltage_max_v", snapshot.cellVoltageMaxV)
            putNullable("cell_delta_v", snapshot.cellDeltaV)
            putNullable("diplus_min_cell_voltage_v", snapshot.cellVoltageMinV)
            putNullable("diplus_max_cell_voltage_v", snapshot.cellVoltageMaxV)
            putNullable("diplus_cell_delta_v", snapshot.cellDeltaV)
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
            put("diplus", snapshot.diPlusData?.toJson() ?: JSONObject.NULL)
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

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun DiParsData.toJson(): JSONObject = JSONObject().apply {
        putNullable("soc", soc)
        putNullable("speed_kmh", speed)
        putNullable("mileage_km", mileage)
        putNullable("power_kw", power)
        putNullable("charge_gun_state", chargeGunState)
        putNullable("max_battery_temp_c", maxBatTemp)
        putNullable("avg_battery_temp_c", avgBatTemp)
        putNullable("min_battery_temp_c", minBatTemp)
        putNullable("charging_status", chargingStatus)
        putNullable("battery_capacity_kwh", batteryCapacityKwh)
        putNullable("total_elec_consumption_kwh", totalElecConsumption)
        putNullable("voltage_12v", voltage12v)
        putNullable("max_cell_voltage_v", maxCellVoltage)
        putNullable("min_cell_voltage_v", minCellVoltage)
        putNullable("cell_delta_v", if (maxCellVoltage != null && minCellVoltage != null) {
            maxCellVoltage - minCellVoltage
        } else {
            null
        })
        putNullable("exterior_temp_c", exteriorTemp)
        putNullable("gear", gear)
        putNullable("power_state", powerState)
        putNullable("inside_temp_c", insideTemp)
        putNullable("ac_status", acStatus)
        putNullable("ac_temp_c", acTemp)
        putNullable("fan_level", fanLevel)
        putNullable("ac_circ", acCirc)
        putNullable("door_fl", doorFL)
        putNullable("door_fr", doorFR)
        putNullable("door_rl", doorRL)
        putNullable("door_rr", doorRR)
        putNullable("window_fl_percent", windowFL)
        putNullable("window_fr_percent", windowFR)
        putNullable("window_rl_percent", windowRL)
        putNullable("window_rr_percent", windowRR)
        putNullable("sunroof_percent", sunroof)
        putNullable("trunk", trunk)
        putNullable("hood", hood)
        putNullable("seatbelt_fl", seatbeltFL)
        putNullable("lock_fl", lockFL)
        putNullable("tire_press_fl_kpa", tirePressFL)
        putNullable("tire_press_fr_kpa", tirePressFR)
        putNullable("tire_press_rl_kpa", tirePressRL)
        putNullable("tire_press_rr_kpa", tirePressRR)
        putNullable("drive_mode", driveMode)
        putNullable("work_mode", workMode)
        putNullable("auto_park", autoPark)
        putNullable("rain", rain)
        putNullable("light_low", lightLow)
        putNullable("drl", drl)
    }
}
