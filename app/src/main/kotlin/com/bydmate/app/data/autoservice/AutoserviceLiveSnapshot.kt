package com.bydmate.app.data.autoservice

/**
 * Read-only live state available directly from BYD's autoservice Binder.
 *
 * This deliberately contains only fids validated on the current vehicle. It is
 * used when di+ is unavailable so the foreground app can keep showing and
 * uploading trustworthy core telemetry without pretending that di+-only
 * signals (gear, climate, odometer, etc.) are available.
 */
data class AutoserviceLiveSnapshot(
    val battery: BatteryReading?,
    val charging: ChargingReading?,
    val enginePowerKw: Int?,
    val capturedAtMs: Long,
) {
    val socPercent: Int? get() = battery?.socPercent?.toInt()?.takeIf { it in 0..100 }
    val auxVoltageV: Double? get() = battery?.voltage12v?.toDouble()
    val sohPercent: Double? get() = battery?.sohPercent?.takeIf { it in 0f..100f }?.toDouble()
    val gunState: Int? get() = charging?.gunConnectState
    val isCharging: Boolean get() = gunState in CHARGING_GUN_STATES

    fun hasUsableTelemetry(): Boolean =
        socPercent != null || enginePowerKw != null || auxVoltageV != null || gunState != null

    private companion object {
        val CHARGING_GUN_STATES = setOf(2, 3, 4, 5)
    }
}
