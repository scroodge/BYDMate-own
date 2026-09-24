package com.bydmate.app.domain.calculator

import com.bydmate.app.domain.calculator.AiRangeEstimator.Estimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRangeSmootherTest {

    private fun est(consumption: Double) = Estimate(rangeKm = 6000.0 / consumption, consumptionKwh100km = consumption)

    /** Feeds one sample per second for [seconds], returns (last timestamp, last state). */
    private fun AiRangeSmoother.drive(
        startMs: Long, seconds: Int, consumption: Double, moving: Boolean = true,
    ): Pair<Long, AiRangeState> {
        var t = startMs
        var s = AiRangeState.EMPTY
        repeat(seconds) {
            t += 1_000L
            s = update(est(consumption), t, moving)
        }
        return t to s
    }

    @Test fun `first sample is shown as is`() {
        val s = AiRangeSmoother().update(est(15.0), 1_000L, moving = true)
        assertEquals(15.0, s.consumptionKwh100km!!, 1e-9)
        assertEquals(15.0, s.rawConsumptionKwh100km!!, 1e-9)
    }

    @Test fun `display damps a one-second spike`() {
        val smoother = AiRangeSmoother()
        val (t, _) = smoother.drive(0L, 60, 15.0)
        val spike = smoother.update(est(30.0), t + 1_000L, moving = true)
        assertEquals(30.0, spike.rawConsumptionKwh100km!!, 1e-9)
        assertTrue("spike leaked: ${spike.consumptionKwh100km}", spike.consumptionKwh100km!! < 16.0)
    }

    @Test fun `trend stays NONE until warm-up`() {
        val (_, s) = AiRangeSmoother().drive(0L, 100, 15.0)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `steady driving settles FLAT`() {
        val (_, s) = AiRangeSmoother().drive(0L, 180, 15.0)
        assertEquals(Trend.FLAT, s.trend)
    }

    @Test fun `sustained higher consumption turns trend UP, lower turns DOWN`() {
        val smoother = AiRangeSmoother()
        val (t, _) = smoother.drive(0L, 600, 15.0)
        val up = smoother.drive(t, 180, 20.0)
        assertEquals(Trend.UP, up.second.trend)
        val down = smoother.drive(up.first, 900, 11.0)
        assertEquals(Trend.DOWN, down.second.trend)
    }

    @Test fun `parked samples do not move the trend`() {
        val smoother = AiRangeSmoother()
        val (t, flat) = smoother.drive(0L, 180, 15.0)
        assertEquals(Trend.FLAT, flat.trend)
        val (_, parked) = smoother.drive(t, 240, 30.0, moving = false)
        assertEquals(Trend.FLAT, parked.trend)
    }

    @Test fun `long gap restarts averages and trend`() {
        val smoother = AiRangeSmoother()
        val (t, _) = smoother.drive(0L, 180, 15.0)
        val after = smoother.update(est(25.0), t + 600_000L, moving = true)
        assertEquals(25.0, after.consumptionKwh100km!!, 1e-9)
        assertEquals(Trend.NONE, after.trend)
    }

    @Test fun `missing estimate blanks the display but keeps the trend`() {
        val smoother = AiRangeSmoother()
        val (t, _) = smoother.drive(0L, 180, 15.0)
        val blank = smoother.update(Estimate.NONE, t + 1_000L, moving = true)
        assertNull(blank.rangeKm)
        assertNull(blank.consumptionKwh100km)
        assertEquals(Trend.FLAT, blank.trend)
    }
}
