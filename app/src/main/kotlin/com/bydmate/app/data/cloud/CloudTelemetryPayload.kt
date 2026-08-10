package com.bydmate.app.data.cloud

import com.bydmate.app.BuildConfig
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import org.json.JSONArray
import org.json.JSONObject

object CloudTelemetryPayload {
    fun build(
        vehicleId: String,
        snapshot: VehicleTelemetrySnapshot,
        omitGps: Boolean = false,
        telemetryState: IternioIntervalPolicy.TelemetryState? = null,
        dropLocationForThinning: Boolean = false,
        liveOnly: Boolean = false,
        clientHourly: Boolean = false,
        tripId: String? = null,
        clientTrip: Boolean = false,
    ): String {
        val telemetryState = telemetryState ?: classifyPayloadState(snapshot)
        val moving = telemetryState == IternioIntervalPolicy.TelemetryState.DRIVING
        val charging = telemetryState == IternioIntervalPolicy.TelemetryState.CHARGING
        val idleOnly = telemetryState == IternioIntervalPolicy.TelemetryState.PARKED

        val telemetry = JSONObject().apply {
            putIfPresent("soc", snapshot.soc)
            if (!idleOnly || snapshot.soc != null) {
                putIfPresent("is_charging", snapshot.isCharging)
            }
            if (moving || charging || snapshot.speedKmh != null || snapshot.powerKw != null) {
                putIfPresent("speed_kmh", snapshot.speedKmh)
                putIfPresent("power_kw", snapshot.powerKw)
            }
            if (charging) {
                putIfPresent("charge_power_kw", snapshot.chargePowerKw)
                putIfPresent("charge_type", snapshot.chargeType)
                putRounded("kwh_charged", snapshot.kwhCharged, 3)
            }
            if (!idleOnly) {
                putIfPresent("battery_temp_c", snapshot.batteryTempC)
                putIfPresent("cabin_temp_c", snapshot.cabinTempC)
                putIfPresent("outside_temp_c", snapshot.outsideTempC)
                putIfPresent("battery_voltage_v", snapshot.batteryVoltageV)
                putIfPresent("aux_voltage_v", snapshot.auxVoltageV)
            }
            if (!idleOnly || snapshot.cellVoltageMinV != null || snapshot.cellVoltageMaxV != null) {
                putRounded("cell_voltage_min_v", snapshot.cellVoltageMinV, CELL_VOLTAGE_DECIMALS)
                putRounded("cell_voltage_max_v", snapshot.cellVoltageMaxV, CELL_VOLTAGE_DECIMALS)
                putRounded("cell_delta_v", snapshot.cellDeltaV, CELL_VOLTAGE_DECIMALS)
            }
            // SoH changes slowly — include cached BMS value even when parked.
            putIfPresent("soh_percent", snapshot.sohPercent)
            if (!idleOnly) {
                putIfPresent("odometer_km", snapshot.odometerKm)
                putRounded("range_est_km", snapshot.rangeEstKm, 1)
                putRounded("current_trip_distance_km", snapshot.currentTripDistanceKm, 3)
                putRounded(
                    "current_trip_consumption_kwh_100km",
                    snapshot.currentTripConsumptionKwh100km,
                    2,
                )
            }
        }

        val location = if (omitGps || dropLocationForThinning) {
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
            put("mate_version", BuildConfig.VERSION_NAME)
            // Parked heartbeat with nothing material changed: the server refreshes
            // live state only and skips the history/hourly/trip writes. Omitted
            // (rather than sent as false) so normal samples keep their exact
            // current shape on the wire.
            if (liveOnly) put("live_only", true)
            // The hourly rollup for this sample is accumulated on-device and shipped once per
            // flush in the batch envelope's "hourly" block, so the server must not also fold
            // the sample in per-sample or the hour would be counted twice. Omitted (never
            // false) so a sample's shape is unchanged when the client isn't doing rollups.
            if (clientHourly) put("client_hourly", true)
            // The trip rollup for this sample is accumulated on-device and shipped once per flush
            // in the batch envelope's "trips" block, so the server must not also run its own trip
            // open/extend logic for it. Omitted (never false) so a sample's shape is unchanged
            // when the client isn't tracking a trip (parked/charging/idle samples).
            if (clientTrip && tripId != null) {
                put("trip_id", tripId)
                put("client_trip", true)
            }
            put("telemetry", telemetry)
            when {
                snapshot.diPlusData != null && idleOnly ->
                    put("diplus", snapshot.diPlusData.toStatusJson())
                snapshot.diPlusData != null && !idleOnly ->
                    put("diplus", snapshot.diPlusData.toJson(includePower = moving || charging))
            }
            val autoservice = JSONObject().apply {
                putIfPresent("soc_percent", snapshot.autoserviceSocPercent?.toDouble())
                putIfPresent("power_kw", snapshot.autoservicePowerKw)
                putIfPresent("gun_state", snapshot.autoserviceGunState)
                putIfPresent("bms_state", snapshot.autoserviceBmsState)
                putIfPresent("charge_capacity_kwh", snapshot.autoserviceChargeCapacityKwh?.toDouble())
                putIfPresent("charge_battery_volt", snapshot.autoserviceChargeBatteryVolt)
                putIfPresent("battery_type", snapshot.autoserviceBatteryType)
                putIfPresent("lifetime_mileage_km", snapshot.autoserviceLifetimeMileageKm?.toDouble())
                putIfPresent("lifetime_kwh", snapshot.autoserviceLifetimeKwh?.toDouble())
            }
            if (autoservice.length() > 0) put("autoservice", autoservice)
            put("location", location)
        }.toString()
    }

    private fun classifyPayloadState(
        snapshot: VehicleTelemetrySnapshot,
    ): IternioIntervalPolicy.TelemetryState {
        snapshot.diPlusData?.let { return IternioIntervalPolicy.classifyFromDiPars(it) }
        val charging = snapshot.isCharging == true ||
            kotlin.math.abs(snapshot.chargePowerKw ?: snapshot.powerKw ?: 0.0) > CHARGING_POWER_THRESHOLD_KW
        val moving = (snapshot.speedKmh ?: 0.0) > MOVING_SPEED_THRESHOLD_KMH
        return IternioIntervalPolicy.classify(charging = charging, parked = !moving && !charging)
    }

    /**
     * The flush envelope. [hourly] carries the cumulative per-hour aggregates for the samples
     * marked `client_hourly`; [trips] carries the cumulative trip aggregates for samples marked
     * `client_trip`. Both can only be attached here, at flush time, because a block spans multiple
     * samples while a payload is built per sample at enqueue time.
     */
    fun buildBatch(
        payloads: List<String>,
        hourly: List<JSONObject> = emptyList(),
        trips: List<JSONObject> = emptyList(),
    ): String {
        val samples = JSONArray()
        payloads.forEach { payload ->
            samples.put(JSONObject(payload))
        }
        return JSONObject().apply {
            put("samples", samples)
            if (hourly.isNotEmpty()) {
                put("hourly", JSONArray().also { array -> hourly.forEach { array.put(it) } })
            }
            if (trips.isNotEmpty()) {
                put("trips", JSONArray().also { array -> trips.forEach { array.put(it) } })
            }
        }.toString()
    }

    /**
     * The `vehicle_id` a queued payload was recorded with. Queued rows keep the id configured
     * at enqueue time, which can differ from the current setting — the sender must send each
     * batch under the id its bodies actually carry, or the server rejects the whole batch.
     * Returns null for malformed or id-less payloads so the caller can fall back.
     */
    fun vehicleIdOf(payloadJson: String): String? = try {
        JSONObject(payloadJson).optString("vehicle_id").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.putIfPresent(name: String, value: Any?) {
        if (value == null) return
        put(name, value)
    }

    /**
     * Round before serializing so raw-double artifacts (e.g. a subtraction like
     * maxCellVoltage - minCellVoltage producing "0.019999999999999") don't bloat the
     * JSON payload and the cloud's telemetry jsonb column. Mirrors the decimal places
     * the cloud sanitizer (telemetry-sanitizer.ts) already applies server-side, so
     * rounding here is a no-op for the server and only saves bytes on the wire.
     */
    private fun JSONObject.putRounded(name: String, value: Double?, decimals: Int) {
        if (value == null || !value.isFinite()) return
        val factor = Math.pow(10.0, decimals.toDouble())
        put(name, Math.round(value * factor) / factor)
    }

    private fun DiParsData.toStatusJson(): JSONObject = JSONObject().apply {
        putIfPresent("soc", soc)
        putIfPresent("gear", gear)
        putIfPresent("charge_gun_state", chargeGunState)
        putIfPresent("speed_kmh", speed)
        putIfPresent("power_state", powerStateLabel ?: powerState)
        putIfPresent("voltage_12v", voltage12v)
        putIfPresent("tire_press_fl_kpa", tirePressFL)
        putIfPresent("tire_press_fr_kpa", tirePressFR)
        putIfPresent("tire_press_rl_kpa", tirePressRL)
        putIfPresent("tire_press_rr_kpa", tirePressRR)
        putIfPresent("sentry_state", sentryState)
        putIfPresent("stall_sentry_mode", stallSentryMode)
    }

    private fun DiParsData.toJson(includePower: Boolean): JSONObject = JSONObject().apply {
        putIfPresent("soc", soc)
        putIfPresent("gear", gear)
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
        putRounded("max_cell_voltage_v", maxCellVoltage, CELL_VOLTAGE_DECIMALS)
        putRounded("min_cell_voltage_v", minCellVoltage, CELL_VOLTAGE_DECIMALS)
        putRounded(
            "cell_delta_v",
            if (maxCellVoltage != null && minCellVoltage != null) {
                maxCellVoltage - minCellVoltage
            } else {
                null
            },
            CELL_VOLTAGE_DECIMALS,
        )
        putIfPresent("sunshade_percent", sunshade)
        putIfPresent("sentry_state", sentryState)
        putIfPresent("remote_lock_state", remoteLockState)
        putIfPresent("window_fl_percent", windowFL)
        putIfPresent("window_fr_percent", windowFR)
        putIfPresent("window_rl_percent", windowRL)
        putIfPresent("window_rr_percent", windowRR)
        putIfPresent("sunroof_percent", sunroof)
        putIfPresent("lock_fl", lockFL)
        putIfPresent("tire_press_fl_kpa", tirePressFL)
        putIfPresent("tire_press_fr_kpa", tirePressFR)
        putIfPresent("tire_press_rl_kpa", tirePressRL)
        putIfPresent("tire_press_rr_kpa", tirePressRR)
        putIfPresent("ac_status", acStatus)
        putIfPresent("ac_temp_c", acTemp)
        putIfPresent("inside_temp_c", insideTemp)
    }

    private const val MOVING_SPEED_THRESHOLD_KMH = 0.5
    private const val CHARGING_POWER_THRESHOLD_KW = 0.1
    private const val MAX_GPS_ACCURACY_M = 30.0
    private const val CELL_VOLTAGE_DECIMALS = 4
}
