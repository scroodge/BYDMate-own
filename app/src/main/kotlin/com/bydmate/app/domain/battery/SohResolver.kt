package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves BMS-reported SoH for UI and cloud telemetry.
 * Priority: live autoservice read → last charge snapshot → settings cache.
 */
@Singleton
class SohResolver @Inject constructor(
    private val batteryHealth: BatteryHealthRepository,
    private val settings: SettingsRepository,
) {
    suspend fun resolveSohPercent(liveReading: BatteryReading?): Double? {
        val live = liveReading?.sohPercent?.takeIf { it in 0f..100f }?.toDouble()
        if (live != null) {
            settings.setLastKnownSohPercent(live)
            return live
        }
        return batteryHealth.getLast()?.sohPercent?.takeIf { it in 0.0..100.0 }
            ?: settings.getLastKnownSohPercent()
    }
}
