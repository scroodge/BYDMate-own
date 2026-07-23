package com.bydmate.app.data.autoservice

/**
 * Read-only live state available directly from BYD's autoservice Binder.
 *
 * This deliberately contains only fids validated on the current vehicle. It is
 * the only foreground telemetry source. Fields outside [supportedFields] are
 * not inferred from di+ or reconstructed from stale data: consumers must treat
 * them as unavailable until we validate a direct fid for this vehicle.
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

    /** Foreground direct fids wired and validated on `way`; safe to display and upload. */
    val supportedFields: Set<DirectTelemetryField> = SUPPORTED_FIELDS

    /**
     * Product-visible gap list for this branch. `null` is not a zero and must
     * not be filled from di+; a future direct-fid validation moves a field from
     * this list to [supportedFields].
     */
    val unsupportedFields: Set<DirectTelemetryField> =
        DirectTelemetryField.entries.toSet() - SUPPORTED_FIELDS

    private companion object {
        val CHARGING_GUN_STATES = setOf(2, 3, 4, 5)
        val SUPPORTED_FIELDS = setOf(
            DirectTelemetryField.SOC,
            DirectTelemetryField.ENGINE_POWER,
            DirectTelemetryField.AUX_VOLTAGE,
            DirectTelemetryField.SOH,
            DirectTelemetryField.GUN_STATE,
            DirectTelemetryField.CHARGING_TYPE,
            DirectTelemetryField.CHARGING_CAPACITY,
            DirectTelemetryField.LIFETIME_MILEAGE,
            DirectTelemetryField.LIFETIME_ENERGY,
        )
    }
}

/**
 * Foreground direct-engine support contract for this vehicle. The daemon may
 * expose additional, separately validated shell-uid fields; adding a field to
 * this APK snapshot still requires an on-car validated autoservice read.
 */
enum class DirectTelemetryField {
    SOC,
    ENGINE_POWER,
    AUX_VOLTAGE,
    SOH,
    GUN_STATE,
    CHARGING_TYPE,
    CHARGING_CAPACITY,
    LIFETIME_MILEAGE,
    LIFETIME_ENERGY,
    SPEED,
    GEAR,
    ODOMETER,
    BATTERY_TEMPERATURE,
    CELL_VOLTAGE,
    CABIN_TEMPERATURE,
    OUTSIDE_TEMPERATURE,
    CLIMATE,
    DOOR_STATE,
    WINDOW_STATE,
    TIRE_PRESSURE,
    DRIVE_MODE,
}
