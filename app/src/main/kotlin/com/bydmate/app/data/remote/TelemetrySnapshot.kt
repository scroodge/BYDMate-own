package com.bydmate.app.data.remote

import android.location.Location
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.domain.SocScaleCalibration
import com.bydmate.app.domain.SocSource
import com.bydmate.app.domain.ChargingStateClassifier
import com.bydmate.app.domain.autoserviceToRawPercent
import java.time.Instant
import kotlin.math.roundToInt

/** A resolved SOC together with the scale it came from. */
internal data class ResolvedSoc(val percent: Int?, val source: SocSource?)

/**
 * The cloud contract carries whole-percent SOC. Prefer Di+ while it provides a valid
 * value, then fall back to the autoservice read when Di+ omits SOC.
 *
 * The two are **not the same scale** — autoservice serves the display SOC, Di+ 2.0 the
 * raw BMS SOC (see [SocScaleCalibration]). The fallback therefore converts before
 * substituting, and the result records which source won so downstream consumers can tell
 * a raw-scale sample from a converted one. Under [SocScaleCalibration.IDENTITY] the
 * conversion is a no-op and the value is unchanged from previous releases.
 */
internal fun resolveTelemetrySoc(
    diPlusSoc: Int?,
    autoserviceSocPercent: Float?,
    calibration: SocScaleCalibration = SocScaleCalibration.IDENTITY,
): ResolvedSoc {
    diPlusSoc?.takeIf { it in 0..100 }?.let { return ResolvedSoc(it, SocSource.DIPLUS) }
    val converted = calibration.autoserviceToRawPercent(autoserviceSocPercent)
    return ResolvedSoc(converted, converted?.let { SocSource.AUTOSERVICE })
}

data class VehicleTelemetrySnapshot(
    val capturedAtMs: Long,
    val deviceTimeIso: String,
    val diPlusData: DiParsData?,
    val soc: Int?,
    /** Which scale [soc] came from; null when no SOC was resolved. */
    val socSource: SocSource? = null,
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
            socCalibration: SocScaleCalibration = SocScaleCalibration.IDENTITY,
        ): VehicleTelemetrySnapshot {
            val resolvedSoc = resolveTelemetrySoc(data?.soc, battery?.socPercent, socCalibration)
            val saneEnginePower = enginePowerKw?.takeIf { it in POWER_MIN_KW..POWER_MAX_KW }?.toDouble()
            val powerKw = saneEnginePower ?: data?.power
            val gunState = charging?.gunConnectState ?: data?.chargeGunState
            val isCharging = ChargingStateClassifier.isCharging(
                autoserviceGun = charging?.gunConnectState,
                diPlusGun = data?.chargeGunState,
                chargingStatus = data?.chargingStatus,
            )
            val chargePower = if (isCharging == true) {
                powerKw?.takeIf { it < 0.0 }?.let { -it } ?: 0.0
            } else {
                0.0
            }
            return VehicleTelemetrySnapshot(
                capturedAtMs = capturedAtMs,
                deviceTimeIso = Instant.ofEpochMilli(capturedAtMs).toString(),
                diPlusData = data,
                soc = resolvedSoc.percent,
                socSource = resolvedSoc.source,
                speedKmh = data?.speed?.toDouble(),
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
