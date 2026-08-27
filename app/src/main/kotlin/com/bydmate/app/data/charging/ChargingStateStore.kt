package com.bydmate.app.data.charging

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.SocSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-known autoservice state snapshot so that runCatchUp can
 * detect SOC / capacity changes across DiLink power-cycles.
 *
 * Replaces ChargingBaselineStore (v2.4.16 and earlier used lifetime_kwh as the
 * baseline signal; v2.4.17 cascade uses SOC + per-session chargingCapacityKwh).
 */
@Singleton
class ChargingStateStore @Inject constructor(
    private val settings: SettingsRepository
) {
    data class State(
        val socPercent: Int?,
        /**
         * Which SOC scale [socPercent] was read on. Null for a baseline written before
         * this was tracked — treated as "unknown", which suppresses cross-scale
         * correction rather than guessing. See [com.bydmate.app.domain.SocScaleCalibration].
         */
        val socSource: SocSource?,
        val mileageKm: Float?,
        val capacityKwh: Float?,
        val ts: Long
    )

    suspend fun load(): State = State(
        socPercent = settings.getChargingBaselineSoc(),
        socSource = settings.getChargingBaselineSocSource(),
        mileageKm = settings.getLastMileageKm(),
        capacityKwh = settings.getLastCapacityKwh(),
        ts = settings.getLastStateTs()
    )

    suspend fun save(
        socPercent: Int?,
        socSource: SocSource?,
        mileageKm: Float?,
        capacityKwh: Float?,
        ts: Long
    ) {
        socPercent?.let { settings.setChargingBaselineSoc(it) }
        settings.setChargingBaselineSocSource(socSource)
        settings.setLastMileageKm(mileageKm)
        settings.setLastCapacityKwh(capacityKwh)
        settings.setLastStateTs(ts)
    }
}
