package com.bydmate.app.data.cloud

import com.bydmate.app.data.local.entity.TripRollupEntity
import org.json.JSONObject
import java.time.Instant

/**
 * Folds telemetry samples into a per-trip aggregate on-device, so the (future) ingest RPC can skip
 * its own trip open/extend logic and BYD trip-meter-baseline arithmetic for `client_trip`-tagged
 * samples.
 *
 * Distance and average consumption are derived from real odometer / lifetime-consumption baselines
 * captured at [open] rather than integrated from GPS or the car's own internal trip meter (which
 * the server's current logic has to guard against resetting mid-drive) — the odometer only ever
 * increases, so `last - baseline` is exact.
 *
 * Reuses [HourlyRollupAccumulator.intervalEnergy] for the regen/traction split: same trapezoidal
 * math, but the caller must pass a continuity pair (`previousPowerKw`/`previousDeviceTimeMs`) that
 * is reset at trip-open, so the first in-trip sample never integrates energy from before the trip
 * started.
 */
object TripRollupAccumulator {

    /** A payload's `telemetry.odometer_km` (present while driving/charging), or null if absent. */
    fun odometerKmOf(payloadJson: String): Double? = try {
        JSONObject(payloadJson).optJSONObject("telemetry")?.optDoubleOrNull("odometer_km")
    } catch (_: Exception) {
        null
    }

    /** A payload's `diplus.total_elec_consumption_kwh` (present while driving/charging), or null. */
    fun totalElecConsumptionKwhOf(payloadJson: String): Double? = try {
        JSONObject(payloadJson).optJSONObject("diplus")?.optDoubleOrNull("total_elec_consumption_kwh")
    } catch (_: Exception) {
        null
    }

    /** Start a new trip from its first payload. */
    fun open(
        vehicleId: String,
        tripId: String,
        startedAtMs: Long,
        payloadJson: String,
        deviceTimeMs: Long,
        now: Long,
    ): TripRollupEntity {
        val telemetry = JSONObject(payloadJson).optJSONObject("telemetry") ?: JSONObject()
        val soc = telemetry.optDoubleOrNull("soc")
        val speed = telemetry.optDoubleOrNull("speed_kmh")
        val odometerKm = odometerKmOf(payloadJson)
        val consumptionKwh = totalElecConsumptionKwhOf(payloadJson)
        return TripRollupEntity(
            tripId = tripId,
            vehicleId = vehicleId,
            startedAt = startedAtMs,
            lastDeviceTime = deviceTimeMs,
            sampleCount = 1,
            distanceBaselineKm = odometerKm,
            consumptionBaselineKwh = consumptionKwh,
            lastOdometerKm = odometerKm,
            lastTotalElecConsumptionKwh = consumptionKwh,
            socStart = soc,
            socEnd = soc,
            maxSpeedKmh = speed,
            speedSum = speed ?: 0.0,
            speedSampleCount = if (speed == null) 0 else 1,
            regenKwhSum = 0.0,
            tractionKwhSum = 0.0,
            dirty = true,
            updatedAt = now,
        )
    }

    /**
     * Fold one sample's payload into [existing]. The sample that triggers the trip's close
     * (gear->P or charging-start) does not join the trip — it doesn't reach [fold] at all,
     * mirroring the server, which inserts no track point for that sample either; the caller
     * closes the trip separately using its existing [TripRollupEntity.lastDeviceTime].
     */
    fun fold(
        existing: TripRollupEntity,
        payloadJson: String,
        deviceTimeMs: Long,
        previousPowerKw: Double?,
        previousDeviceTimeMs: Long?,
        now: Long,
    ): TripRollupEntity {
        val telemetry = JSONObject(payloadJson).optJSONObject("telemetry") ?: JSONObject()
        val soc = telemetry.optDoubleOrNull("soc")
        val speed = telemetry.optDoubleOrNull("speed_kmh")
        val power = telemetry.optDoubleOrNull("power_kw")
        val odometerKm = odometerKmOf(payloadJson) ?: existing.lastOdometerKm
        val consumptionKwh = totalElecConsumptionKwhOf(payloadJson) ?: existing.lastTotalElecConsumptionKwh

        val energy = if (power != null && previousPowerKw != null && previousDeviceTimeMs != null) {
            val dtSeconds = (deviceTimeMs - previousDeviceTimeMs) / 1000.0
            if (dtSeconds > 0 && dtSeconds <= HourlyRollupAccumulator.MAX_ENERGY_GAP_SECONDS) {
                HourlyRollupAccumulator.intervalEnergy(previousPowerKw, power, dtSeconds)
            } else {
                ZERO_ENERGY
            }
        } else {
            ZERO_ENERGY
        }

        return existing.copy(
            lastDeviceTime = deviceTimeMs,
            sampleCount = existing.sampleCount + 1,
            lastOdometerKm = odometerKm,
            lastTotalElecConsumptionKwh = consumptionKwh,
            socEnd = soc ?: existing.socEnd,
            maxSpeedKmh = maxOfNullable(existing.maxSpeedKmh, speed),
            speedSum = existing.speedSum + (speed ?: 0.0),
            speedSampleCount = existing.speedSampleCount + if (speed == null) 0 else 1,
            regenKwhSum = existing.regenKwhSum + energy.regenKwh,
            tractionKwhSum = existing.tractionKwhSum + energy.tractionKwh,
            dirty = true,
            updatedAt = now,
        )
    }

    /**
     * Close [existing] using its own last known device time, not [now] — nothing is computed at
     * close, it is only a marker. Shared by the gear->P/charging-start trigger and the next-boot
     * finalizer for a trip orphaned by a process death mid-drive.
     */
    fun close(existing: TripRollupEntity, now: Long): TripRollupEntity =
        existing.copy(endedAt = existing.lastDeviceTime, dirty = true, updatedAt = now)

    /**
     * The wire form of one trip block. Field names match the real `bydmate_trips` columns so the
     * (future) cloud RPC can consume this directly. A field with no basis to compute stays absent
     * rather than becoming a spurious 0.
     */
    fun toJson(rollup: TripRollupEntity): JSONObject = JSONObject().apply {
        put("trip_id", rollup.tripId)
        put("started_at", Instant.ofEpochMilli(rollup.startedAt).toString())
        put("last_device_time", Instant.ofEpochMilli(rollup.lastDeviceTime).toString())
        rollup.endedAt?.let { put("ended_at", Instant.ofEpochMilli(it).toString()) }
        put("sample_count", rollup.sampleCount)
        val distanceKm = distanceKmOf(rollup)
        putIfPresent("distance_km", distanceKm?.let { round(it, DISTANCE_DECIMALS) })
        putIfPresent("soc_start", rollup.socStart)
        putIfPresent("soc_end", rollup.socEnd)
        putIfPresent("max_speed_kmh", rollup.maxSpeedKmh)
        putAvg("avg_speed_kmh", rollup.speedSum, rollup.speedSampleCount)
        putIfPresent(
            "avg_consumption_kwh_100km",
            avgConsumptionKwh100kmOf(rollup, distanceKm)?.let { round(it, AVG_DECIMALS) },
        )
        put("regen_energy_kwh", round(rollup.regenKwhSum, ENERGY_DECIMALS))
        put("traction_energy_kwh", round(rollup.tractionKwhSum, ENERGY_DECIMALS))
    }

    /** The real odometer only increases, so `last - baseline` is exact; a negative delta is noise. */
    private fun distanceKmOf(rollup: TripRollupEntity): Double? {
        val baseline = rollup.distanceBaselineKm ?: return null
        val last = rollup.lastOdometerKm ?: return null
        val delta = last - baseline
        return delta.takeIf { it >= 0.0 }
    }

    /**
     * Energy-over-distance rather than a running mean of the per-sample instantaneous rate — more
     * accurate than the server's current weighted-mean approach, and free since both baselines are
     * already tracked.
     */
    private fun avgConsumptionKwh100kmOf(rollup: TripRollupEntity, distanceKm: Double?): Double? {
        val baseline = rollup.consumptionBaselineKwh ?: return null
        val last = rollup.lastTotalElecConsumptionKwh ?: return null
        val dist = distanceKm?.takeIf { it > 0.0 } ?: return null
        val consumedKwh = last - baseline
        return consumedKwh.takeIf { it >= 0.0 }?.let { (it / dist) * 100.0 }
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

    private fun maxOfNullable(a: Double?, b: Double?): Double? =
        if (a == null) b else if (b == null) a else maxOf(a, b)

    private val ZERO_ENERGY = HourlyRollupAccumulator.IntervalEnergy(0.0, 0.0)
    private const val AVG_DECIMALS = 2
    private const val DISTANCE_DECIMALS = 3
    private const val ENERGY_DECIMALS = 6
}
