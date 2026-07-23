package com.bydmate.app.data.remote

import android.location.Location
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import java.time.Instant

data class VehicleTelemetrySnapshot(
    val capturedAtMs: Long,
    val deviceTimeIso: String,
    val diPlusData: DiParsData?,
    val soc: Int?,
    val speedKmh: Double?,
    val powerKw: Double?,
    val batteryTempC: Double?,
    val cabinTempC: Double?,
    val outsideTempC: Double?,
    val batteryVoltageV: Double?,
    val auxVoltageV: Double?,
    val cellVoltageMinV: Double?,
    val cellVoltageMaxV: Double?,
    val cellDeltaV: Double?,
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
    val autoserviceSocPercent: Float? = null,
    val autoservicePowerKw: Int? = null,
    val autoserviceGunState: Int? = null,
    val autoserviceBmsState: Int? = null,
    val autoserviceChargeCapacityKwh: Float? = null,
    val autoserviceChargeBatteryVolt: Int? = null,
    val autoserviceBatteryType: Int? = null,
    val autoserviceLifetimeMileageKm: Float? = null,
    val autoserviceLifetimeKwh: Float? = null,
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
            // Vehicle speed has no validated direct fid on this car. GPS is a separate signal
            // with its own accuracy/error characteristics — it must not be presented as vehicle
            // telemetry, so this stays unknown rather than a GPS estimate (docs/DIRECT_TELEMETRY_FIELD_MATRIX.md).
            val speedKmh = data?.speed?.toDouble()
            val chargePower = if (isCharging == true) {
                powerKw?.takeIf { it < 0.0 }?.let { -it } ?: 0.0
            } else {
                0.0
            }
            return VehicleTelemetrySnapshot(
                capturedAtMs = capturedAtMs,
                deviceTimeIso = Instant.ofEpochMilli(capturedAtMs).toString(),
                diPlusData = data,
                soc = data?.soc ?: battery?.socPercent?.toInt()?.takeIf { it in 0..100 },
                speedKmh = speedKmh,
                powerKw = powerKw,
                batteryTempC = data?.avgBatTemp?.toDouble(),
                cabinTempC = data?.insideTemp?.toDouble(),
                outsideTempC = data?.exteriorTemp?.toDouble(),
                batteryVoltageV = charging?.chargeBatteryVoltV?.toDouble(),
                auxVoltageV = battery?.voltage12v?.toDouble() ?: data?.voltage12v,
                cellVoltageMinV = data?.minCellVoltage,
                cellVoltageMaxV = data?.maxCellVoltage,
                cellDeltaV = if (data?.maxCellVoltage != null && data.minCellVoltage != null) {
                    data.maxCellVoltage - data.minCellVoltage
                } else {
                    null
                },
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
                // Direct autoservice has no validated gear/park fid on this car.
                // GPS speed is useful as a separate signal but cannot prove park,
                // so direct-only telemetry leaves this unknown rather than guessing.
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
                autoserviceSocPercent = battery?.socPercent,
                autoservicePowerKw = enginePowerKw,
                autoserviceGunState = charging?.gunConnectState,
                autoserviceBmsState = charging?.bmsState,
                autoserviceChargeCapacityKwh = charging?.chargingCapacityKwh,
                autoserviceChargeBatteryVolt = charging?.chargeBatteryVoltV,
                autoserviceBatteryType = charging?.batteryType,
                autoserviceLifetimeMileageKm = battery?.lifetimeMileageKm,
                autoserviceLifetimeKwh = battery?.lifetimeKwh,
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
