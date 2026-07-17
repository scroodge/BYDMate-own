package com.bydmate.app.data.cloud

import com.bydmate.app.data.local.entity.HourlyRollupEntity
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs

/**
 * Folds telemetry samples into per-hour aggregates on-device, so the ingest RPC can skip its
 * per-sample hourly upsert and its per-sample lookup into `bydmate_telemetry_samples`.
 *
 * Everything here mirrors what the server does today, and deliberately reads the **payload**
 * rather than the snapshot it was built from: a parked payload omits `battery_temp_c` and
 * friends, so folding the snapshot instead would silently count fields the server never saw.
 */
object HourlyRollupAccumulator {

    /** Interval energy split, mirroring `bydmate_interval_energy_kwh`. */
    data class IntervalEnergy(val tractionKwh: Double, val regenKwh: Double)

    /**
     * Longest gap the server integrates energy across (`bydmate_update_hourly_energy`). A larger
     * gap means the app or the car was off and the power in between is unknown, so it is skipped.
     */
    const val MAX_ENERGY_GAP_SECONDS = 180.0

    /** Hour containing [deviceTimeMs], truncated in UTC — the server's `date_trunc('hour', … at time zone 'utc')`. */
    fun hourStartOf(deviceTimeMs: Long): Long = Math.floorDiv(deviceTimeMs, HOUR_MS) * HOUR_MS

    /** Epoch millis of a payload's `device_time`, or null if absent/unparseable. */
    fun deviceTimeMsOf(payloadJson: String): Long? = try {
        JSONObject(payloadJson).optString("device_time").takeIf { it.isNotBlank() }
            ?.let { Instant.parse(it).toEpochMilli() }
    } catch (_: Exception) {
        null
    }

    /** A payload's `telemetry.power_kw`, or null when absent — the next interval's left edge. */
    fun powerKwOf(payloadJson: String): Double? = try {
        JSONObject(payloadJson).optJSONObject("telemetry")?.optDoubleOrNull("power_kw")
    } catch (_: Exception) {
        null
    }

    /**
     * Trapezoidal integration of power over an interval, split into traction (positive power)
     * and regen (negative). When the interval crosses zero, each side gets the triangle on its
     * own side of the crossing. Mirrors `bydmate_interval_energy_kwh` case for case.
     */
    fun intervalEnergy(fromPowerKw: Double?, toPowerKw: Double?, dtSeconds: Double): IntervalEnergy {
        if (fromPowerKw == null || toPowerKw == null || dtSeconds <= 0) return ZERO_ENERGY
        val hours = dtSeconds / 3600.0
        if (fromPowerKw >= 0 && toPowerKw >= 0) {
            return IntervalEnergy(tractionKwh = ((fromPowerKw + toPowerKw) / 2) * hours, regenKwh = 0.0)
        }
        if (fromPowerKw <= 0 && toPowerKw <= 0) {
            return IntervalEnergy(tractionKwh = 0.0, regenKwh = ((abs(fromPowerKw) + abs(toPowerKw)) / 2) * hours)
        }
        if (fromPowerKw == toPowerKw) return ZERO_ENERGY
        // Sign change: split the interval at the zero crossing and take the triangle on each side.
        val zeroFraction = fromPowerKw / (fromPowerKw - toPowerKw)
        val firstKwh = (abs(fromPowerKw) / 2) * (hours * zeroFraction)
        val secondKwh = (abs(toPowerKw) / 2) * (hours * (1 - zeroFraction))
        return if (fromPowerKw > 0) {
            IntervalEnergy(tractionKwh = firstKwh, regenKwh = secondKwh)
        } else {
            IntervalEnergy(tractionKwh = secondKwh, regenKwh = firstKwh)
        }
    }

    /**
     * Fold one sample's payload into [existing] (null starts a fresh hour).
     *
     * [previousPowerKw] / [previousDeviceTimeMs] are the previous *stored* sample's power and
     * time, used for the energy integration. They are held in memory by the caller, so an app
     * restart loses one interval — the same interval the server would drop anyway, since a
     * restart gap almost always exceeds [MAX_ENERGY_GAP_SECONDS].
     */
    fun fold(
        existing: HourlyRollupEntity?,
        vehicleId: String,
        payloadJson: String,
        deviceTimeMs: Long,
        previousPowerKw: Double?,
        previousDeviceTimeMs: Long?,
        now: Long,
    ): HourlyRollupEntity {
        val telemetry = JSONObject(payloadJson).optJSONObject("telemetry") ?: JSONObject()
        val soc = telemetry.optDoubleOrNull("soc")
        val speed = telemetry.optDoubleOrNull("speed_kmh")
        val power = telemetry.optDoubleOrNull("power_kw")
        val batteryTemp = telemetry.optDoubleOrNull("battery_temp_c")
        val cabinTemp = telemetry.optDoubleOrNull("cabin_temp_c")
        val outsideTemp = telemetry.optDoubleOrNull("outside_temp_c")

        val hourStart = hourStartOf(deviceTimeMs)
        val base = existing?.takeIf { it.hourStart == hourStart && it.vehicleId == vehicleId }
            ?: HourlyRollupEntity(vehicleId = vehicleId, hourStart = hourStart, sampleCount = 0)

        // Energy is attributed to the hour of the *current* sample, matching the server, so an
        // interval spanning an hour boundary lands wholly in the later hour.
        val energy = if (power != null && previousPowerKw != null && previousDeviceTimeMs != null) {
            val dtSeconds = (deviceTimeMs - previousDeviceTimeMs) / 1000.0
            if (dtSeconds > 0 && dtSeconds <= MAX_ENERGY_GAP_SECONDS) {
                intervalEnergy(previousPowerKw, power, dtSeconds)
            } else {
                ZERO_ENERGY
            }
        } else {
            ZERO_ENERGY
        }

        return base.copy(
            sampleCount = base.sampleCount + 1,
            socMin = minOfNullable(base.socMin, soc),
            socMax = maxOfNullable(base.socMax, soc),
            socLast = soc ?: base.socLast,
            speedMax = maxOfNullable(base.speedMax, speed),
            powerSum = base.powerSum + (power ?: 0.0),
            powerSampleCount = base.powerSampleCount + if (power == null) 0 else 1,
            batteryTempSum = base.batteryTempSum + (batteryTemp ?: 0.0),
            batteryTempSampleCount = base.batteryTempSampleCount + if (batteryTemp == null) 0 else 1,
            cabinTempSum = base.cabinTempSum + (cabinTemp ?: 0.0),
            cabinTempSampleCount = base.cabinTempSampleCount + if (cabinTemp == null) 0 else 1,
            outsideTempSum = base.outsideTempSum + (outsideTemp ?: 0.0),
            outsideTempSampleCount = base.outsideTempSampleCount + if (outsideTemp == null) 0 else 1,
            regenKwhSum = base.regenKwhSum + energy.regenKwh,
            tractionKwhSum = base.tractionKwhSum + energy.tractionKwh,
            dirty = true,
            updatedAt = now,
        )
    }

    /**
     * The wire form of one hour block. Averages are derived here (the server column is `*_avg`),
     * and a field with no contributing samples stays absent rather than becoming a spurious 0.
     */
    fun toJson(rollup: HourlyRollupEntity): JSONObject = JSONObject().apply {
        put("hour_start", Instant.ofEpochMilli(rollup.hourStart).toString())
        put("sample_count", rollup.sampleCount)
        putIfPresent("soc_min", rollup.socMin)
        putIfPresent("soc_max", rollup.socMax)
        putIfPresent("soc_last", rollup.socLast)
        putIfPresent("speed_max", rollup.speedMax)
        putAvg("power_avg", rollup.powerSum, rollup.powerSampleCount)
        putAvg("battery_temp_avg", rollup.batteryTempSum, rollup.batteryTempSampleCount)
        putAvg("cabin_temp_avg", rollup.cabinTempSum, rollup.cabinTempSampleCount)
        putAvg("outside_temp_avg", rollup.outsideTempSum, rollup.outsideTempSampleCount)
        put("power_sample_count", rollup.powerSampleCount)
        put("battery_temp_sample_count", rollup.batteryTempSampleCount)
        put("cabin_temp_sample_count", rollup.cabinTempSampleCount)
        put("outside_temp_sample_count", rollup.outsideTempSampleCount)
        put("regen_kwh_sum", round(rollup.regenKwhSum, ENERGY_DECIMALS))
        put("traction_kwh_sum", round(rollup.tractionKwhSum, ENERGY_DECIMALS))
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (isNull(name)) return null
        val value = optDouble(name, Double.NaN)
        return if (value.isFinite()) value else null
    }

    private fun JSONObject.putIfPresent(name: String, value: Double?) {
        if (value == null || !value.isFinite()) return
        put(name, value)
    }

    private fun JSONObject.putAvg(name: String, sum: Double, count: Int) {
        if (count <= 0) return
        put(name, round(sum / count, AVG_DECIMALS))
    }

    private fun round(value: Double, decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(value * factor) / factor
    }

    private fun minOfNullable(a: Double?, b: Double?): Double? =
        if (a == null) b else if (b == null) a else minOf(a, b)

    private fun maxOfNullable(a: Double?, b: Double?): Double? =
        if (a == null) b else if (b == null) a else maxOf(a, b)

    private val ZERO_ENERGY = IntervalEnergy(0.0, 0.0)
    private const val HOUR_MS = 3_600_000L
    private const val AVG_DECIMALS = 4
    private const val ENERGY_DECIMALS = 6
}
