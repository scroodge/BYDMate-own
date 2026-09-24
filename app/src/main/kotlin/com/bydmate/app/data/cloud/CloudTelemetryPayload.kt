package com.bydmate.app.data.cloud

import com.bydmate.app.BuildConfig
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single builder for the cloud telemetry payload — the app's 1 Hz poll loop and the
 * daemon's parked/charging push both go through here (B-19). Previously each process
 * hand-maintained its own field mapping; a fix landing in one and not the other already
 * shipped two production defects. [VehicleTelemetrySnapshot] is the shared port: the app
 * builds one via [VehicleTelemetrySnapshot.from], the daemon via its own plain-constructor
 * call (it has no Context/DI for the `.from()` factory's `android.location.Location` input).
 */
object CloudTelemetryPayload {

    /**
     * How a payload's shape is selected. [Mode.Standard] now applies the same
     * vehicle-state-derived thinning and field rounding to every caller — app or daemon —
     * so the wire shape for equivalent underlying state cannot drift between the two
     * processes again. Previously the daemon's normal push never thinned and never rounded.
     * [Mode.AutoserviceFallback] is a genuinely different, smaller shape (no di+ fields at
     * all) used only when di+ is unreachable; it is never inferred from
     * `snapshot.diPlusData == null` — a caller must ask for it explicitly.
     */
    sealed interface Mode {
        data class Standard(
            val liveOnly: Boolean = false,
            val omitGps: Boolean = false,
            val dropLocationForThinning: Boolean = false,
            val telemetryState: IternioIntervalPolicy.TelemetryState? = null,
            val clientHourly: Boolean = false,
            val tripId: String? = null,
            val clientTrip: Boolean = false,
        ) : Mode

        object AutoserviceFallback : Mode
    }

    fun build(vehicleId: String, snapshot: VehicleTelemetrySnapshot, mode: Mode = Mode.Standard()): String =
        when (mode) {
            is Mode.Standard -> buildStandard(vehicleId, snapshot, mode)
            Mode.AutoserviceFallback -> buildAutoserviceFallback(vehicleId, snapshot)
        }

    private fun buildStandard(vehicleId: String, snapshot: VehicleTelemetrySnapshot, mode: Mode.Standard): String {
        val telemetryState = mode.telemetryState ?: classifyPayloadState(snapshot)
        val moving = telemetryState == IternioIntervalPolicy.TelemetryState.DRIVING
        val charging = telemetryState == IternioIntervalPolicy.TelemetryState.CHARGING
        val idleOnly = telemetryState == IternioIntervalPolicy.TelemetryState.PARKED

        val telemetry = JSONObject().apply {
            // Prefer di+ 2.0's 0.1 %-resolution SOC; `diplus_soc` is numeric cloud-side so
            // the decimal survives. Falls back to the rounded value for di+ 1.x and for
            // the autoservice-sourced SOC, keeping the key's type stable either way.
            putIfPresent("soc", snapshot.diPlusData?.socPrecise ?: snapshot.soc)
            // Which scale `soc` is on. autoservice serves the display SOC, di+ 2.0 the raw
            // BMS SOC, and the two differ by up to ~2 pp — see SocScaleCalibration. Without
            // this tag a fallback sample is indistinguishable from a di+ one.
            putIfPresent("soc_source", snapshot.socSource?.wireName)
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
                putRounded("kwh_charged", snapshot.kwhCharged, KWH_CHARGED_DECIMALS)
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
            // Daemon-normal samples carried this field; the app's own samples never did.
            // Unifying the two builders means it now goes out on both — additive, optional
            // on the wire (ADR-0003), never previously read on the app path.
            putIfPresent("is_parked", snapshot.isParked)
        }

        val location = if (mode.omitGps || mode.dropLocationForThinning) {
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
            if (mode.liveOnly) put("live_only", true)
            // The hourly rollup for this sample is accumulated on-device and shipped once per
            // flush in the batch envelope's "hourly" block, so the server must not also fold
            // the sample in per-sample or the hour would be counted twice. Omitted (never
            // false) so a sample's shape is unchanged when the client isn't doing rollups.
            if (mode.clientHourly) put("client_hourly", true)
            // The trip rollup for this sample is accumulated on-device and shipped once per flush
            // in the batch envelope's "trips" block, so the server must not also run its own trip
            // open/extend logic for it. Omitted (never false) so a sample's shape is unchanged
            // when the client isn't tracking a trip (parked/charging/idle samples).
            if (mode.clientTrip && mode.tripId != null) {
                put("trip_id", mode.tripId)
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

    /**
     * The reduced shape used only when di+ is unreachable — no `diplus`-from-di+ fields,
     * sourced entirely from autoservice reads on [snapshot]. Matches
     * `CommandDaemon.buildAutoserviceFallbackPayload`'s wire shape exactly: same field set,
     * same "soc" left uncalibrated (unlike [buildStandard]'s `resolveTelemetrySoc` path —
     * this asymmetry already existed and is preserved, not introduced here).
     */
    private fun buildAutoserviceFallback(vehicleId: String, snapshot: VehicleTelemetrySnapshot): String {
        val isCharging = snapshot.isCharging ?: false

        val telemetry = JSONObject().apply {
            // Every SOC here is the autoservice display scale by construction — there is no
            // di+ read in this path. Tagged so a consumer can tell these samples apart from
            // raw-scale di+ ones; see SocScaleCalibration.
            putIfPresent("soc", snapshot.soc)
            putIfPresent("soc_source", snapshot.socSource?.wireName)
            putIfPresent("power_kw", snapshot.autoservicePowerKw?.toDouble())
            putIfPresent("aux_voltage_v", snapshot.auxVoltageV)
            put("is_charging", isCharging)
            putIfPresent("charge_power_kw", if (isCharging) snapshot.chargePowerKw else null)
            putRounded("kwh_charged", if (isCharging) snapshot.kwhCharged else null, KWH_CHARGED_DECIMALS)
            putIfPresent("charge_type", if (isCharging) snapshot.chargeType else null)
            put("is_parked", !isCharging)
            putIfPresent("soh_percent", snapshot.sohPercent)
        }
        val diplus = JSONObject().apply {
            putIfPresent("soc", snapshot.soc)
            putIfPresent("power_kw", snapshot.autoservicePowerKw)
            putIfPresent("charge_gun_state", snapshot.autoserviceGunState)
            putIfPresent("voltage_12v", snapshot.auxVoltageV)
            putIfPresent("door_fl", snapshot.autoserviceDoorFL)
            putIfPresent("door_fr", snapshot.autoserviceDoorFR)
            putIfPresent("door_rl", snapshot.autoserviceDoorRL)
            putIfPresent("door_rr", snapshot.autoserviceDoorRR)
            putIfPresent("trunk", snapshot.autoserviceTrunk)
            putIfPresent("hood", snapshot.autoserviceHood)
            putIfPresent("tire_press_fl_kpa", snapshot.tirePressFL)
            putIfPresent("tire_press_fr_kpa", snapshot.tirePressFR)
            putIfPresent("tire_press_rl_kpa", snapshot.tirePressRL)
            putIfPresent("tire_press_rr_kpa", snapshot.tirePressRR)
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("vehicle_id", vehicleId)
            put("device_time", snapshot.deviceTimeIso)
            put("source", "BYDMate")
            put("mate_version", BuildConfig.VERSION_NAME)
            put("telemetry", telemetry)
            put("diplus", diplus)
            put("location", JSONObject())
        }.toString()
    }

    private fun classifyPayloadState(
        snapshot: VehicleTelemetrySnapshot,
    ): IternioIntervalPolicy.TelemetryState {
        val charging = snapshot.isCharging ?: (
            kotlin.math.abs(snapshot.chargePowerKw ?: snapshot.powerKw ?: 0.0) > CHARGING_POWER_THRESHOLD_KW
        )
        snapshot.diPlusData?.let { data ->
            if (charging) return IternioIntervalPolicy.TelemetryState.CHARGING
            val gear = data.gear
            val parked = when {
                gear == 1 -> true
                gear != null -> false
                else -> (data.speed ?: 0) <= 0
            }
            return IternioIntervalPolicy.classify(charging = false, parked = parked)
        }
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

    /**
     * Omits the key entirely when [value] is absent — never writes `JSONObject.NULL`. Also
     * matters for `bydmate_telemetry_samples_soh_analytics_idx` (`telemetry ? 'soh_percent'`):
     * jsonb key-existence is true even for an explicit null, so a literal-null `soh_percent`
     * would index a row the query's `between 0 and 100` check then discards. Absent and null
     * are equivalent everywhere else downstream (Zod fields are `.nullable().optional()`,
     * `telemetry-sanitizer.ts` gates on `value != null`) — this is the one place it isn't.
     */
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
     *
     * Now applied uniformly for every caller — previously only the app's own path rounded;
     * the daemon's normal push wrote raw doubles straight to the wire.
     */
    private fun JSONObject.putRounded(name: String, value: Double?, decimals: Int) {
        if (value == null || !value.isFinite()) return
        val factor = Math.pow(10.0, decimals.toDouble())
        put(name, Math.round(value * factor) / factor)
    }

    /**
     * Common to both [toStatusJson] (parked/idle) and [toJson] (driving/charging) — fields
     * the daemon's pre-B-19 `diplus` block always sent regardless of state, but that never
     * made it into either app-side extension. Restored here (B-19 follow-up) rather than left
     * dropped: `stall_sentry_mode` gates a remote-command guard
     * (`vehicle-control-guards.ts:29`) and is shown live in the comfort-controls UI; the rest
     * feed the `/dev/bydmate-diplus` diagnostic page. All are additive/optional on the wire
     * (ADR-0003), so restoring them is safe for old APKs and for the app path, which never
     * sent them either.
     */
    private fun JSONObject.putCommonDiPlusFields(d: DiParsData) = apply {
        putIfPresent("fan_level", d.fanLevel)
        putIfPresent("ac_circ", d.acCirc)
        putIfPresent("door_fl", d.doorFL)
        putIfPresent("door_fr", d.doorFR)
        putIfPresent("door_rl", d.doorRL)
        putIfPresent("door_rr", d.doorRR)
        putIfPresent("trunk", d.trunk)
        putIfPresent("hood", d.hood)
        putIfPresent("seatbelt_fl", d.seatbeltFL)
        putIfPresent("drive_mode", d.driveMode)
        putIfPresent("work_mode", d.workMode)
        putIfPresent("auto_park", d.autoPark)
        putIfPresent("rain", d.rain)
        putIfPresent("light_low", d.lightLow)
        putIfPresent("drl", d.drl)
    }

    private fun DiParsData.toStatusJson(): JSONObject = JSONObject().apply {
        putIfPresent("soc", socPrecise ?: soc)
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
        putCommonDiPlusFields(this@toStatusJson)
    }

    private fun DiParsData.toJson(includePower: Boolean): JSONObject = JSONObject().apply {
        putIfPresent("soc", socPrecise ?: soc)
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
        putIfPresent("power_state", powerStateLabel ?: powerState)
        putIfPresent("stall_sentry_mode", stallSentryMode)
        putCommonDiPlusFields(this@toJson)
    }

    private const val MOVING_SPEED_THRESHOLD_KMH = 0.5
    private const val CHARGING_POWER_THRESHOLD_KW = 0.1
    private const val MAX_GPS_ACCURACY_M = 30.0
    private const val CELL_VOLTAGE_DECIMALS = 4
    private const val KWH_CHARGED_DECIMALS = 3
}
