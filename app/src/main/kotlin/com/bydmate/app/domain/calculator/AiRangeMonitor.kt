package com.bydmate.app.domain.calculator

import kotlin.math.exp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AiRangeState(
    /** Smoothed for display; null when the estimator had no answer. */
    val rangeKm: Double?,
    val consumptionKwh100km: Double?,
    val trend: Trend,
    /** Unsmoothed estimator output — what goes to the cloud, byte-for-byte the web formula. */
    val rawRangeKm: Double?,
    val rawConsumptionKwh100km: Double?,
) {
    companion object {
        val EMPTY = AiRangeState(null, null, Trend.NONE, null, null)
    }
}

/**
 * Turns the per-second [AiRangeEstimator] output into something readable on the
 * widget: the instant power/speed term makes the raw number jump every tick, so the
 * display value is a time-based EMA, and the trend compares a short EMA of AI
 * consumption against a long one with the same hysteresis + debounce as
 * [ConsumptionAggregator] (so both arrows behave alike).
 *
 * Trend only moves while driving and stays [Trend.NONE] until [trendWarmupMs] of
 * driving has fed the long average.
 */
class AiRangeSmoother(
    private val displayTauMs: Long = 20_000L,
    private val shortTauMs: Long = 60_000L,
    private val longTauMs: Long = 600_000L,
    private val trendWarmupMs: Long = 120_000L,
    private val debounceMs: Long = 30_000L,
    /** A gap longer than this (service restart, long stop) restarts the averages. */
    private val maxGapMs: Long = 300_000L,
) {
    private var lastTs: Long = 0L
    private var displayRange: Double? = null
    private var displayConsumption: Double? = null
    private var shortConsumption: Double? = null
    private var longConsumption: Double? = null
    private var drivingMs: Long = 0L
    private var committedTrend = Trend.NONE
    private var candidateTrend = Trend.NONE
    private var candidateSince = 0L

    fun update(estimate: AiRangeEstimator.Estimate, nowMs: Long, moving: Boolean): AiRangeState {
        val range = estimate.rangeKm
        val consumption = estimate.consumptionKwh100km
        if (range == null || consumption == null) {
            // Keep the trend averages — one missing tick should not wipe ten minutes.
            displayRange = null
            displayConsumption = null
            return AiRangeState(null, null, committedTrend, null, null)
        }

        var dt = if (lastTs == 0L) 0L else nowMs - lastTs
        if (dt < 0 || dt > maxGapMs) {
            reset()
            dt = 0L
        }
        lastTs = nowMs

        displayRange = ema(displayRange, range, dt, displayTauMs)
        displayConsumption = ema(displayConsumption, consumption, dt, displayTauMs)

        if (moving) {
            drivingMs += dt
            shortConsumption = ema(shortConsumption, consumption, dt, shortTauMs)
            longConsumption = ema(longConsumption, consumption, dt, longTauMs)
            val short = shortConsumption
            val long = longConsumption
            if (drivingMs >= trendWarmupMs && short != null && long != null && long > 0.01) {
                updateDebounce(nowMs, candidateFor(committedTrend, short / long))
            }
        }

        return AiRangeState(displayRange, displayConsumption, committedTrend, range, consumption)
    }

    fun reset() {
        lastTs = 0L
        displayRange = null
        displayConsumption = null
        shortConsumption = null
        longConsumption = null
        drivingMs = 0L
        committedTrend = Trend.NONE
        candidateTrend = Trend.NONE
        candidateSince = 0L
    }

    private fun ema(previous: Double?, sample: Double, dtMs: Long, tauMs: Long): Double {
        if (previous == null) return sample
        if (dtMs <= 0) return previous
        val alpha = 1.0 - exp(-dtMs.toDouble() / tauMs)
        return previous + alpha * (sample - previous)
    }

    private fun candidateFor(current: Trend, ratio: Double): Trend = when (current) {
        Trend.DOWN -> if (ratio > EXIT_DOWN_TO_FLAT) flatOrUp(ratio) else Trend.DOWN
        Trend.UP -> if (ratio < EXIT_UP_TO_FLAT) flatOrDown(ratio) else Trend.UP
        else -> when {
            ratio < ENTER_DOWN -> Trend.DOWN
            ratio > ENTER_UP -> Trend.UP
            else -> Trend.FLAT
        }
    }

    private fun flatOrUp(ratio: Double) = if (ratio > ENTER_UP) Trend.UP else Trend.FLAT
    private fun flatOrDown(ratio: Double) = if (ratio < ENTER_DOWN) Trend.DOWN else Trend.FLAT

    private fun updateDebounce(now: Long, candidate: Trend) {
        if (committedTrend == Trend.NONE) {
            // First verdict after warm-up needs no debounce — there is nothing to flap from.
            committedTrend = candidate
            candidateTrend = candidate
            candidateSince = now
            return
        }
        if (candidate != candidateTrend) {
            candidateTrend = candidate
            candidateSince = now
        }
        if (candidate != committedTrend && now - candidateSince >= debounceMs) {
            committedTrend = candidate
        }
    }

    private companion object {
        const val ENTER_DOWN = 0.90
        const val ENTER_UP = 1.10
        const val EXIT_DOWN_TO_FLAT = 0.95
        const val EXIT_UP_TO_FLAT = 1.05
    }
}

/** Process-wide AI range state; fed by TrackingService, read by the floating widget. */
object AiRangeMonitor {
    private val smoother = AiRangeSmoother()
    private val _state = MutableStateFlow(AiRangeState.EMPTY)
    val state: StateFlow<AiRangeState> = _state

    @Synchronized
    fun update(estimate: AiRangeEstimator.Estimate, nowMs: Long, moving: Boolean) {
        _state.value = smoother.update(estimate, nowMs, moving)
    }

    @Synchronized
    fun reset() {
        smoother.reset()
        _state.value = AiRangeState.EMPTY
    }
}
