package com.bydmate.app.data.remote

import android.location.Location
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import java.time.Instant

data class VehicleTelemetrySnapshot(
    val capturedAtMs: Long,
    val deviceTimeIso: String,
    val soc: Int?,
    val speedKmh: Double?,
    val powerKw: Double?,
    val batteryTempC: Double?,
    val cabinTempC: Double?,
    val outsideTempC: Double?,
    val batteryVoltageV: Double?,
    val auxVoltageV: Double?,
    val odometerKm: Double?,
    val sohPercent: Double?,
    val isCharging: Boolean?,
    val chargePowerKw: Double?,
    val chargeType: String?,
    val kwhCharged: Double?,
    val rangeEstKm: Double?,
    val currentTripDistanceKm: Double?,
    val currentTripConsumptionKwh100km: Double?,
    val isParked: Boolean?,
    val tirePressFL: Int?,
    val tirePressFR: Int?,
    val tirePressRL: Int?,
    val tirePressRR: Int?,
    val location: TelemetryLocation?,
) {
    companion object {
        private const val POWER_MIN_KW = -300
        private const val POWER_MAX_KW = 500
        private val CHARGING_GUN_STATES = setOf(2, 3, 4, 5)
        private val DCFC_GUN_STATES = setOf(3, 4, 5)

        fun from(
            data: DiParsData?,
            battery: BatteryReading?,
            charging: ChargingReading?,
            enginePowerKw: Int?,
            capturedAtMs: Long,
            rangeEstKm: Double?,
            currentTripDistanceKm: Double?,
            currentTripConsumptionKwh100km: Double?,
            location: Location?,
        ): VehicleTelemetrySnapshot {
            val saneEnginePower = enginePowerKw?.takeIf { it in POWER_MIN_KW..POWER_MAX_KW }?.toDouble()
            val powerKw = saneEnginePower ?: data?.power
            val gunState = charging?.gunConnectState ?: data?.chargeGunState
            val isCharging = gunState?.let { it in CHARGING_GUN_STATES }
            val chargePower = if (isCharging == true) {
                powerKw?.takeIf { it < 0.0 }?.let { -it } ?: 0.0
            } else {
                0.0
            }
            return VehicleTelemetrySnapshot(
                capturedAtMs = capturedAtMs,
                deviceTimeIso = Instant.ofEpochMilli(capturedAtMs).toString(),
                soc = data?.soc,
                speedKmh = data?.speed?.toDouble(),
                powerKw = powerKw,
                batteryTempC = data?.avgBatTemp?.toDouble(),
                cabinTempC = data?.insideTemp?.toDouble(),
                outsideTempC = data?.exteriorTemp?.toDouble(),
                batteryVoltageV = charging?.chargeBatteryVoltV?.toDouble(),
                auxVoltageV = battery?.voltage12v?.toDouble() ?: data?.voltage12v,
                odometerKm = data?.mileage,
                sohPercent = battery?.sohPercent?.takeIf { it in 0f..100f }?.toDouble(),
                isCharging = isCharging,
                chargePowerKw = chargePower,
                chargeType = when (gunState) {
                    2 -> "AC"
                    in DCFC_GUN_STATES -> "DC"
                    else -> null
                },
                kwhCharged = charging?.chargingCapacityKwh?.takeIf { it >= 0f }?.toDouble(),
                rangeEstKm = rangeEstKm,
                currentTripDistanceKm = currentTripDistanceKm,
                currentTripConsumptionKwh100km = currentTripConsumptionKwh100km,
                isParked = data?.gear?.let { it == 1 },
                tirePressFL = data?.tirePressFL,
                tirePressFR = data?.tirePressFR,
                tirePressRL = data?.tirePressRL,
                tirePressRR = data?.tirePressRR,
                location = location?.let {
                    TelemetryLocation(
                        lat = it.latitude,
                        lon = it.longitude,
                        accuracyM = it.accuracy.takeIf { _ -> it.hasAccuracy() }?.toDouble(),
                        bearingDeg = it.bearing.takeIf { _ -> it.hasBearing() }?.toDouble(),
                    )
                },
            )
        }
    }
}

data class TelemetryLocation(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val bearingDeg: Double?,
)
