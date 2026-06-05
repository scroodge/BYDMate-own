package com.bydmate.app.data.cloud

import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import kotlin.math.abs

/**
 * VoltFlow Cloud Sync cadence with a drive latch: after D / R / N or movement,
 * stay at 1 Hz for [DRIVE_LATCH_MS] even if DiPars briefly reports P in traffic.
 */
class CloudTelemetryCadence {
    @Volatile private var lastDriveSignalMs: Long = 0L

    fun effectiveState(
        snapshot: VehicleTelemetrySnapshot,
        nowMs: Long,
    ): IternioIntervalPolicy.TelemetryState {
        val raw = rawState(snapshot)
        if (raw == IternioIntervalPolicy.TelemetryState.CHARGING) {
            return raw
        }
        if (hasDriveSignal(snapshot)) {
            lastDriveSignalMs = nowMs
        }
        if (lastDriveSignalMs > 0L && nowMs - lastDriveSignalMs < DRIVE_LATCH_MS) {
            return IternioIntervalPolicy.TelemetryState.DRIVING
        }
        return raw
    }

    private fun rawState(snapshot: VehicleTelemetrySnapshot): IternioIntervalPolicy.TelemetryState {
        snapshot.diPlusData?.let { return IternioIntervalPolicy.classifyFromDiPars(it) }
        val charging = snapshot.isCharging == true ||
            abs(snapshot.chargePowerKw ?: snapshot.powerKw ?: 0.0) > CHARGING_POWER_THRESHOLD_KW
        val moving = (snapshot.speedKmh ?: 0.0) > MOVING_SPEED_THRESHOLD_KMH
        return IternioIntervalPolicy.classify(charging = charging, parked = !moving && !charging)
    }

    private fun hasDriveSignal(snapshot: VehicleTelemetrySnapshot): Boolean {
        val gear = snapshot.diPlusData?.gear
        if (gear != null && gear != 1) return true
        val speed = snapshot.speedKmh ?: snapshot.diPlusData?.speed?.toDouble()
        return speed != null && speed > MOVING_SPEED_THRESHOLD_KMH
    }

    internal companion object {
        const val MOVING_SPEED_THRESHOLD_KMH = 0.5
        const val CHARGING_POWER_THRESHOLD_KW = 0.1
        const val DRIVE_LATCH_MS = 10 * 60 * 1000L
    }
}
