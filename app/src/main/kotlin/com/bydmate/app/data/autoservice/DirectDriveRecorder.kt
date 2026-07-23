package com.bydmate.app.data.autoservice

import android.content.Context
import android.location.Location
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded local evidence log for validating the direct telemetry engine during
 * a real drive. It never performs a Binder read itself: the caller supplies
 * the already validated [AutoserviceLiveSnapshot] from the foreground poll.
 *
 * GPS is intentionally represented only as a movement marker (speed/accuracy),
 * not coordinates. The file is readable through ADB after a drive and keeps at
 * most the current 2 MiB segment plus one previous segment.
 */
@Singleton
class DirectDriveRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun record(snapshot: AutoserviceLiveSnapshot, location: Location?) {
        append(
            DirectDriveRecord.from(
                capturedAtMs = snapshot.capturedAtMs,
                engineAvailable = true,
                snapshot = snapshot,
                location = location,
            ),
        )
    }

    fun recordEngineUnavailable(capturedAtMs: Long, location: Location?) {
        append(
            DirectDriveRecord.from(
                capturedAtMs = capturedAtMs,
                engineAvailable = false,
                snapshot = null,
                location = location,
            ),
        )
    }

    @Synchronized
    private fun append(record: DirectDriveRecord) {
        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val directory = File(root, DIRECTORY_NAME)
            if (!directory.exists() && !directory.mkdirs()) {
                Log.w(TAG, "cannot create ${directory.absolutePath}")
                return
            }
            val current = File(directory, FILE_NAME)
            if (current.length() >= MAX_CURRENT_BYTES) {
                val previous = File(directory, "$FILE_NAME.prev")
                if (previous.exists()) previous.delete()
                if (!current.renameTo(previous)) {
                    Log.w(TAG, "cannot rotate ${current.absolutePath}")
                    return
                }
            }
            current.appendText(record.toJsonLine() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "direct drive record failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DirectDriveRecorder"
        const val DIRECTORY_NAME = "telemetry"
        const val FILE_NAME = "direct_drive_recorder.jsonl"
        const val MAX_CURRENT_BYTES = 2L * 1024L * 1024L
    }
}

/** Raw direct-engine sample, deliberately independent of di+ and cloud schemas. */
internal data class DirectDriveRecord(
    val capturedAtMs: Long,
    val engineAvailable: Boolean,
    val gpsSpeedKmh: Double?,
    val gpsAccuracyM: Double?,
    val gpsMoving: Boolean?,
    val socPercent: Int?,
    val enginePowerKw: Int?,
    val auxVoltageV: Double?,
    val sohPercent: Double?,
    val gunState: Int?,
    val chargingType: Int?,
    val bmsState: Int?,
    val chargingCapacityKwh: Double?,
    val chargeBatteryVoltageV: Int?,
    val batteryType: Int?,
    val lifetimeMileageKm: Double?,
    val lifetimeKwh: Double?,
    val unsupportedFields: Set<DirectTelemetryField>,
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("schema_version", 1)
        put("source", "byd_autoservice_direct_drive_recorder")
        put("captured_at_ms", capturedAtMs)
        put("engine_available", engineAvailable)
        putNullable("gps_speed_kmh", gpsSpeedKmh)
        putNullable("gps_accuracy_m", gpsAccuracyM)
        putNullable("gps_moving", gpsMoving)
        putNullable("soc_percent", socPercent)
        putNullable("engine_power_kw", enginePowerKw)
        putNullable("aux_voltage_v", auxVoltageV)
        putNullable("soh_percent", sohPercent)
        putNullable("gun_state", gunState)
        putNullable("charging_type", chargingType)
        putNullable("bms_state", bmsState)
        putNullable("charging_capacity_kwh", chargingCapacityKwh)
        putNullable("charge_battery_voltage_v", chargeBatteryVoltageV)
        putNullable("battery_type", batteryType)
        putNullable("lifetime_mileage_km", lifetimeMileageKm)
        putNullable("lifetime_kwh", lifetimeKwh)
        put(
            "unsupported_fields",
            JSONArray(unsupportedFields.map { it.name.lowercase() }.sorted()),
        )
    }.toString()

    companion object {
        private const val MOVING_GPS_THRESHOLD_KMH = 3.0

        fun from(
            capturedAtMs: Long,
            engineAvailable: Boolean,
            snapshot: AutoserviceLiveSnapshot?,
            location: Location?,
        ): DirectDriveRecord = from(
            capturedAtMs = capturedAtMs,
            engineAvailable = engineAvailable,
            snapshot = snapshot,
            gpsMarker = GpsMovementMarker.from(location),
        )

        internal fun from(
            capturedAtMs: Long,
            engineAvailable: Boolean,
            snapshot: AutoserviceLiveSnapshot?,
            gpsMarker: GpsMovementMarker?,
        ): DirectDriveRecord {
            val gpsSpeedKmh = gpsMarker?.speedKmh
            return DirectDriveRecord(
                capturedAtMs = capturedAtMs,
                engineAvailable = engineAvailable,
                gpsSpeedKmh = gpsSpeedKmh,
                gpsAccuracyM = gpsMarker?.accuracyM,
                gpsMoving = gpsSpeedKmh?.let { it >= MOVING_GPS_THRESHOLD_KMH },
                socPercent = snapshot?.socPercent,
                enginePowerKw = snapshot?.enginePowerKw,
                auxVoltageV = snapshot?.auxVoltageV,
                sohPercent = snapshot?.sohPercent,
                gunState = snapshot?.gunState,
                chargingType = snapshot?.charging?.chargingType,
                bmsState = snapshot?.charging?.bmsState,
                chargingCapacityKwh = snapshot?.charging?.chargingCapacityKwh?.toDouble(),
                chargeBatteryVoltageV = snapshot?.charging?.chargeBatteryVoltV,
                batteryType = snapshot?.charging?.batteryType,
                lifetimeMileageKm = snapshot?.battery?.lifetimeMileageKm?.toDouble(),
                lifetimeKwh = snapshot?.battery?.lifetimeKwh?.toDouble(),
                unsupportedFields = snapshot?.unsupportedFields ?: DirectTelemetryField.entries.toSet(),
            )
        }

        private fun JSONObject.putNullable(name: String, value: Any?) {
            put(name, value ?: JSONObject.NULL)
        }
    }
}

internal data class GpsMovementMarker(
    val speedKmh: Double?,
    val accuracyM: Double?,
) {
    companion object {
        fun from(location: Location?): GpsMovementMarker? = location?.let {
            GpsMovementMarker(
                speedKmh = it.takeIf(Location::hasSpeed)?.speed?.times(3.6f)?.toDouble(),
                accuracyM = it.takeIf(Location::hasAccuracy)?.accuracy?.toDouble(),
            )
        }
    }
}
