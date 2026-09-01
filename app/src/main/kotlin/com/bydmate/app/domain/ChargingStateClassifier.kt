package com.bydmate.app.domain

/**
 * One charging-state rule for foreground telemetry and both daemon send paths.
 *
 * An explicit gun reading is authoritative. Prefer autoservice because DiPlus can omit
 * ChargeGun in Leopard 3's reduced payload; fall back to DiPlus when autoservice is
 * unavailable. [chargingStatus] survives only as a last resort when neither source knows
 * the gun, so an explicit NONE (1) can never be overridden by a stale positive status.
 */
object ChargingStateClassifier {
    private val CONNECTED_GUN_STATES = 2..5

    fun isCharging(
        autoserviceGun: Int?,
        diPlusGun: Int?,
        chargingStatus: Int?,
    ): Boolean {
        val gun = autoserviceGun ?: diPlusGun
        return if (gun != null) {
            gun in CONNECTED_GUN_STATES
        } else {
            chargingStatus != null && chargingStatus > 0
        }
    }
}
