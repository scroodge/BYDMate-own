package com.bydmate.app.data.cloud

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import org.json.JSONObject

/**
 * Car-profile range reserve sent down by the cloud as `range_reserve_soc_percent`
 * (EvAcChargeTimer vehicle-battery-capacity.ts), next to `battery_capacity_kwh` and on the
 * same two carriers (telemetry ack, command poll). The web excludes the energy below this
 * SOC from AI Range (`range-estimate.ts` `validReserveSoc`); the on-car AiRangeEstimator
 * must do the same or the widget overstates range against the web.
 */
object CloudRangeReserve {
    private const val TAG = "CloudRangeReserve"

    @Volatile private var lastAppliedPercent: Double? = null

    /** Null when absent (older server, unresolved car); otherwise clamped 0–100 like the web. */
    fun fromJson(json: JSONObject): Double? {
        if (!json.has("range_reserve_soc_percent") || json.isNull("range_reserve_soc_percent")) return null
        val percent = json.optDouble("range_reserve_soc_percent", Double.NaN)
        return percent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    }

    /** Writes the setting only when it changes. */
    suspend fun apply(settings: SettingsRepository, percent: Double, source: String) {
        if (lastAppliedPercent?.let { kotlin.math.abs(it - percent) < 0.001 } == true) return
        val current = settings.getString(SettingsRepository.KEY_RANGE_RESERVE_SOC, "0").toDoubleOrNull()
        if (current == null || kotlin.math.abs(current - percent) >= 0.001) {
            settings.setString(SettingsRepository.KEY_RANGE_RESERVE_SOC, percent.toString())
            Log.i(TAG, "range reserve from cloud car profile ($source): $current -> $percent %")
        }
        lastAppliedPercent = percent
    }
}
