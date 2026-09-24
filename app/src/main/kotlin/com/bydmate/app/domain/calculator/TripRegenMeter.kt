package com.bydmate.app.domain.calculator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Regenerated energy for the current widget session (ignition-on → off), integrated
 * from live power exactly as the cloud integrates a trip — the regen half of
 * EvAcChargeTimer `src/lib/voltflowmate/trip-energy.ts` (`intervalEnergyKwh`,
 * `calculateTripEnergy`): trapezoids over consecutive samples, split at the zero
 * crossing, gaps longer than [MAX_GAP_MS] skipped. Negative power is regen.
 */
class TripRegenIntegrator {
    private var sessionStartedAt: Long? = null
    private var lastPowerKw: Double? = null
    private var lastTs: Long = 0L
    private var regenKwh: Double = 0.0

    /** Returns the session's regen so far, or null before any session has started. */
    fun update(sessionStartedAt: Long?, powerKw: Double?, nowMs: Long): Double? {
        if (sessionStartedAt != null && sessionStartedAt != this.sessionStartedAt) {
            this.sessionStartedAt = sessionStartedAt
            regenKwh = 0.0
            lastPowerKw = null
            lastTs = 0L
        }
        if (this.sessionStartedAt == null) return null
        // Idle between sessions: keep showing the finished session's total.
        if (sessionStartedAt == null) return regenKwh

        val power = powerKw?.takeIf { it.isFinite() }
        val previous = lastPowerKw
        val dtMs = nowMs - lastTs
        if (power != null && previous != null && dtMs in 1..MAX_GAP_MS) {
            regenKwh += intervalRegenKwh(previous, power, dtMs / 1000.0)
        }
        if (power != null) {
            lastPowerKw = power
            lastTs = nowMs
        }
        return regenKwh
    }

    companion object {
        const val MAX_GAP_MS = 180_000L

        fun intervalRegenKwh(fromKw: Double, toKw: Double, dtSeconds: Double): Double {
            if (fromKw >= 0 && toKw >= 0) return 0.0
            if (fromKw <= 0 && toKw <= 0) return (-(fromKw + toKw) / 2) * (dtSeconds / 3600)
            // Sign change: only the negative triangle after/before the zero crossing counts.
            val zeroFraction = (fromKw / (fromKw - toKw)).coerceIn(0.0, 1.0)
            return if (fromKw > 0) {
                (-toKw * (1 - zeroFraction) * dtSeconds) / 2 / 3600
            } else {
                (-fromKw * zeroFraction * dtSeconds) / 2 / 3600
            }
        }
    }
}

/** Process-wide current-trip regen; fed by TrackingService, read by the floating widget. */
object TripRegenMeter {
    private val integrator = TripRegenIntegrator()
    private val _regenKwh = MutableStateFlow<Double?>(null)
    val regenKwh: StateFlow<Double?> = _regenKwh

    @Synchronized
    fun update(sessionStartedAt: Long?, powerKw: Double?, nowMs: Long) {
        _regenKwh.value = integrator.update(sessionStartedAt, powerKw, nowMs)
    }
}
