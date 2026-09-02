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
        val charging = snapshot.isCharging ?: (
            abs(snapshot.chargePowerKw ?: snapshot.powerKw ?: 0.0) > CHARGING_POWER_THRESHOLD_KW
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

    private fun hasDriveSignal(snapshot: VehicleTelemetrySnapshot): Boolean {
        val gear = snapshot.diPlusData?.gear
        if (gear != null && gear != 1) return true
        val speed = snapshot.speedKmh ?: snapshot.diPlusData?.speed?.toDouble()
        return speed != null && speed > MOVING_SPEED_THRESHOLD_KMH
    }

    internal companion object {
        const val MOVING_SPEED_THRESHOLD_KMH = 0.5
        const val CHARGING_POWER_THRESHOLD_KW = 0.1
        // C-3: latch keeping 1 Hz cloud cadence after the last drive signal so a
        // brief P at a traffic light doesn't drop us to the 30 s parked heartbeat
        // mid-drive. 10 min was far longer than any junction stop and made every real
        // stop cost 10 min of 1 Hz traffic before economy resumed; 2 min still absorbs
        // stop-and-go while restoring parked economy ~5× sooner. Tunable — validate on
        // a drive→park cycle (watch cloud cadence drop to 30 s ~2 min after stopping).
        const val DRIVE_LATCH_MS = 2 * 60 * 1000L
    }
}
