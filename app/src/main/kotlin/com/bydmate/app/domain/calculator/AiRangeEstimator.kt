package com.bydmate.app.domain.calculator

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot

/**
 * Kotlin port of the cloud "AI Range" estimate —
 * EvAcChargeTimer `src/lib/voltflowmate/range-estimate.ts` (`estimateVehicleRangeKm`,
 * `estimateConsumptionKwh100Km`, `environmentConsumptionFactor`).
 *
 * The car computes the number itself and (later) pushes it to the cloud, so the
 * web and the widget must agree. Keep the weights, thresholds and clamps below
 * in step with the TypeScript; AiRangeEstimatorTest mirrors the web's
 * range-estimate.test.mjs fixtures to catch drift.
 *
 * Deliberate input substitutions (local TripEntity lacks the cloud trip columns):
 *  - cloud `sample_count >= 3`          → local `source == "live"`
 *  - cloud `traction − regen` net kWh   → local `kwh_consumed` (already net)
 *  - the web feeds exactly ONE latest trip; callers must do the same.
 */
object AiRangeEstimator {

    const val DEFAULT_CONSUMPTION_KWH_100KM = 18.5
    const val MIN_FORECAST_CONSUMPTION_KWH_100KM = 8.0
    const val MAX_FORECAST_CONSUMPTION_KWH_100KM = 42.0
    private const val MIN_PLAUSIBLE_BATTERY_KWH = 10.0
    private const val MAX_PLAUSIBLE_BATTERY_KWH = 200.0
    private const val MIN_TRIP_DISTANCE_KM = 1.0

    data class Inputs(
        /** Same SOC the telemetry payload sends: di+ `socPrecise`, else the rounded SOC. */
        val soc: Double?,
        val sohPercent: Double? = null,
        val speedKmh: Double? = null,
        val powerKw: Double? = null,
        val outsideTempC: Double? = null,
        val batteryTempC: Double? = null,
        val acOn: Boolean = false,
        val tirePressuresKpa: List<Int?> = emptyList(),
        val currentTripConsumptionKwh100km: Double? = null,
        val currentTripDistanceKm: Double? = null,
    ) {
        companion object {
            fun from(snapshot: VehicleTelemetrySnapshot): Inputs {
                val di = snapshot.diPlusData
                return Inputs(
                    soc = di?.socPrecise ?: snapshot.soc?.toDouble(),
                    sohPercent = snapshot.sohPercent,
                    speedKmh = snapshot.speedKmh,
                    powerKw = snapshot.powerKw,
                    outsideTempC = snapshot.outsideTempC,
                    // Web: telemetry.battery_temp_c ?? diplus.avg_battery_temp_c
                    batteryTempC = snapshot.batteryTempC ?: di?.avgBatTemp?.toDouble(),
                    acOn = di?.acStatus == 1,
                    tirePressuresKpa = listOf(
                        snapshot.tirePressFL, snapshot.tirePressFR,
                        snapshot.tirePressRL, snapshot.tirePressRR,
                    ),
                    currentTripConsumptionKwh100km = snapshot.currentTripConsumptionKwh100km,
                    currentTripDistanceKm = snapshot.currentTripDistanceKm,
                )
            }
        }
    }

    /** The cloud trip-row fields the estimator reads, in local terms. */
    data class TripInput(
        val avgConsumptionKwh100km: Double?,
        val distanceKm: Double?,
        val netConsumptionKwh100km: Double? = null,
        /** Stands in for the cloud's `sample_count >= 3`. */
        val wellSampled: Boolean = true,
    ) {
        companion object {
            fun from(trip: TripEntity): TripInput {
                val distance = trip.distanceKm
                val kwh = trip.kwhConsumed
                val net = if (kwh != null && distance != null && distance > 0) kwh / distance * 100.0 else null
                return TripInput(
                    avgConsumptionKwh100km = trip.kwhPer100km,
                    distanceKm = distance,
                    netConsumptionKwh100km = net,
                    wellSampled = trip.source == "live",
                )
            }
        }
    }

    data class Estimate(val rangeKm: Double?, val consumptionKwh100km: Double?) {
        companion object {
            val NONE = Estimate(null, null)
        }
    }

    fun estimate(inputs: Inputs, recentTrips: List<TripInput>, batteryCapacityKwh: Double?): Estimate {
        val soc = valid(inputs.soc) ?: return Estimate.NONE
        val usableBatteryKwh = resolveUsableBatteryKwh(batteryCapacityKwh, inputs.sohPercent)
        if (usableBatteryKwh == null || usableBatteryKwh <= 0) return Estimate.NONE
        val usableEnergyKwh = usableBatteryKwh * (soc.coerceIn(0.0, 100.0) / 100.0)
        val consumption = estimateConsumption(inputs, recentTrips)
        if (consumption <= 0) return Estimate.NONE
        return Estimate(rangeKm = usableEnergyKwh / consumption * 100.0, consumptionKwh100km = consumption)
    }

    fun resolveUsableBatteryKwh(batteryCapacityKwh: Double?, sohPercent: Double?): Double? {
        val capacity = valid(batteryCapacityKwh) ?: return null
        if (capacity < MIN_PLAUSIBLE_BATTERY_KWH || capacity > MAX_PLAUSIBLE_BATTERY_KWH) return null
        val soh = valid(sohPercent)
        return capacity * (if (soh != null) soh.coerceIn(70.0, 105.0) / 100.0 else 1.0)
    }

    fun estimateConsumption(inputs: Inputs, recentTrips: List<TripInput>): Double {
        val estimates = mutableListOf<Pair<Double, Double>>() // value to weight
        var reliableCount = 0

        val currentTrip = valid(inputs.currentTripConsumptionKwh100km)
        val currentTripDistance = valid(inputs.currentTripDistanceKm)
        if (currentTrip != null && inForecastBand(currentTrip)) {
            val weight = if (currentTripDistance != null) (currentTripDistance / 12.0).coerceIn(0.25, 1.8) else 0.7
            estimates += currentTrip to weight
            reliableCount++
        }

        val tripAverage = weightedAvgConsumption(recentTrips.filter(::isForecastTrip))
        if (tripAverage != null) {
            estimates += tripAverage to 1.2
            reliableCount++
        }

        val energyAverage = averageEnergyConsumption(recentTrips)
        if (energyAverage != null) {
            estimates += energyAverage.first to (energyAverage.second / 20.0).coerceIn(0.3, 2.0)
            reliableCount++
        }

        val speed = valid(inputs.speedKmh)
        val power = valid(inputs.powerKw)
        if (speed != null && speed >= 12 && power != null && power > 0) {
            val instant = (power / speed * 100.0)
                .coerceIn(MIN_FORECAST_CONSUMPTION_KWH_100KM, MAX_FORECAST_CONSUMPTION_KWH_100KM)
            estimates += instant to (if (speed >= 35) 0.9 else 0.45)
        }

        val fallbackWeight = when {
            reliableCount >= 2 -> 0.15
            reliableCount >= 1 -> 0.35
            else -> 0.8
        }
        estimates += userMedianConsumption(recentTrips) to fallbackWeight

        val weighted = estimates.sumOf { it.first * it.second } / estimates.sumOf { it.second }
        return (weighted * environmentFactor(inputs))
            .coerceIn(MIN_FORECAST_CONSUMPTION_KWH_100KM, MAX_FORECAST_CONSUMPTION_KWH_100KM)
    }

    fun environmentFactor(inputs: Inputs): Double {
        var factor = 1.0
        val outside = validTemp(inputs.outsideTempC)
        val battery = validTemp(inputs.batteryTempC)
        val speed = valid(inputs.speedKmh)

        if (outside != null) {
            when {
                outside < -10 -> factor += 0.28
                outside < 0 -> factor += 0.18
                outside < 8 -> factor += 0.08
                outside > 30 -> factor += 0.05
            }
        }
        if (battery != null) {
            when {
                battery < 5 -> factor += 0.12
                battery < 12 -> factor += 0.05
                battery > 42 -> factor += 0.04
            }
        }
        if (speed != null) {
            when {
                speed > 115 -> factor += 0.16
                speed > 95 -> factor += 0.08
                speed > 75 -> factor += 0.03
            }
        }
        if (inputs.acOn) {
            factor += if (outside != null && (outside < 8 || outside > 27)) 0.08 else 0.03
        }
        val tires = inputs.tirePressuresKpa.mapNotNull { it?.toDouble() }.filter { it > 100 }
        if (tires.isNotEmpty() && tires.average() < 220) factor += 0.05

        return factor.coerceIn(0.9, 1.45)
    }

    private fun isForecastTrip(trip: TripInput): Boolean {
        val consumption = valid(trip.avgConsumptionKwh100km) ?: return false
        val distance = valid(trip.distanceKm) ?: return false
        return inForecastBand(consumption) && distance >= MIN_TRIP_DISTANCE_KM && trip.wellSampled
    }

    /** trip-metrics.ts `weightedAvgConsumptionKwh100`: distance-weighted, positive only. */
    private fun weightedAvgConsumption(trips: List<TripInput>): Double? {
        var sum = 0.0
        var distanceSum = 0.0
        for (trip in trips) {
            val distance = valid(trip.distanceKm) ?: continue
            val consumption = valid(trip.avgConsumptionKwh100km) ?: continue
            if (distance < MIN_TRIP_DISTANCE_KM || consumption <= 0) continue
            sum += consumption * distance
            distanceSum += distance
        }
        return if (distanceSum > 0) sum / distanceSum else null
    }

    /** Returns (distance-weighted net consumption, total distance km). */
    private fun averageEnergyConsumption(trips: List<TripInput>): Pair<Double, Double>? {
        var sum = 0.0
        var distanceSum = 0.0
        for (trip in trips) {
            val distance = valid(trip.distanceKm) ?: continue
            if (distance < MIN_TRIP_DISTANCE_KM) continue
            val consumption = valid(trip.netConsumptionKwh100km) ?: continue
            if (consumption <= 0 || !inForecastBand(consumption)) continue
            sum += consumption * distance
            distanceSum += distance
        }
        return if (distanceSum > 0) (sum / distanceSum) to distanceSum else null
    }

    private fun userMedianConsumption(trips: List<TripInput>): Double {
        val sorted = trips.filter(::isForecastTrip).map { it.avgConsumptionKwh100km!! }.sorted()
        if (sorted.isEmpty()) return DEFAULT_CONSUMPTION_KWH_100KM
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun inForecastBand(value: Double) =
        value >= MIN_FORECAST_CONSUMPTION_KWH_100KM && value <= MAX_FORECAST_CONSUMPTION_KWH_100KM

    private fun valid(value: Double?): Double? = value?.takeIf { it.isFinite() }

    private fun validTemp(value: Double?): Double? = valid(value)?.takeIf { it >= -50 && it <= 90 }
}
