package com.bydmate.app.data.cloud

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import org.json.JSONObject

/**
 * Car-profile battery capacity sent down by the cloud as `battery_capacity_kwh`
 * (EvAcChargeTimer vehicle-battery-capacity.ts). The app has no settings screen and di+
 * reports no usable capacity (BatCapacity=4.2 on a 45 kWh Yuan Up), so this is the only
 * way the on-car AI Range can use the same capacity as the web instead of the 72.9 kWh
 * default.
 *
 * Two carriers: the telemetry ingest response (the live one — while remote commands are
 * suspended the command poll is a static rewrite that never reaches a function) and the
 * command poll response (for when commands come back).
 */
object CloudBatteryCapacity {
    private const val TAG = "CloudCapacity"

    /** Last value this process wrote or confirmed, so a flush every 15 s costs no settings read. */
    @Volatile private var lastAppliedKwh: Double? = null

    /** Null when absent (older server, unresolved car) or outside range-estimate.ts's 10–200 kWh. */
    fun fromJson(json: JSONObject): Double? {
        if (!json.has("battery_capacity_kwh")) return null
        val kwh = json.optDouble("battery_capacity_kwh", Double.NaN)
        return kwh.takeIf { it.isFinite() && it >= 10.0 && it <= 200.0 }
    }

    /** Writes the capacity setting only when it changes. */
    suspend fun apply(settings: SettingsRepository, kwh: Double, source: String) {
        if (lastAppliedKwh?.let { kotlin.math.abs(it - kwh) < 0.001 } == true) return
        val current = settings.getString(
            SettingsRepository.KEY_BATTERY_CAPACITY,
            SettingsRepository.DEFAULT_BATTERY_CAPACITY,
        ).toDoubleOrNull()
        if (current == null || kotlin.math.abs(current - kwh) >= 0.001) {
            settings.setString(SettingsRepository.KEY_BATTERY_CAPACITY, kwh.toString())
            Log.i(TAG, "battery capacity from cloud car profile ($source): $current -> $kwh kWh")
        }
        lastAppliedKwh = kwh
    }
}
