package com.bydmate.app.domain.calculator

import com.bydmate.app.domain.calculator.TripRegenIntegrator.Companion.intervalRegenKwh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripRegenIntegratorTest {

    @Test fun `traction only counts nothing`() {
        assertEquals(0.0, intervalRegenKwh(20.0, 10.0, 3600.0), 1e-12)
    }

    @Test fun `steady regen is the full trapezoid in both directions`() {
        // The cloud formula gives 2.5 and 0.0 here — see the TripRegenMeter KDoc.
        assertEquals(4.0, intervalRegenKwh(-5.0, -3.0, 3600.0), 1e-12)
        assertEquals(4.0, intervalRegenKwh(-3.0, -5.0, 3600.0), 1e-12)
    }

    @Test fun `zero crossing counts only the negative triangle`() {
        // +10 → −10 over 1 h: crossing at the midpoint, regen triangle = 10 × 0.5 / 2.
        assertEquals(2.5, intervalRegenKwh(10.0, -10.0, 3600.0), 1e-12)
        assertEquals(2.5, intervalRegenKwh(-10.0, 10.0, 3600.0), 1e-12)
    }

    @Test fun `integrates a session and resets on the next one`() {
        val integrator = TripRegenIntegrator()
        assertNull(integrator.update(sessionStartedAt = null, powerKw = -10.0, nowMs = 0L))

        var t = 1_000_000L
        integrator.update(sessionStartedAt = t, powerKw = -36.0, nowMs = t)
        repeat(100) {
            t += 1_000L
            integrator.update(sessionStartedAt = 1_000_000L, powerKw = -36.0, nowMs = t)
        }
        // 36 kW for 100 s = 1.0 kWh.
        assertEquals(1.0, integrator.update(1_000_000L, -36.0, t)!!, 1e-9)

        // Ignition off: the finished session's total stays visible.
        assertEquals(1.0, integrator.update(null, null, t + 5_000L)!!, 1e-9)

        // Next session starts from zero.
        assertEquals(0.0, integrator.update(t + 60_000L, -36.0, t + 60_000L)!!, 1e-12)
    }

    @Test fun `gaps longer than three minutes are skipped`() {
        val integrator = TripRegenIntegrator()
        integrator.update(1L, -36.0, 1_000L)
        assertEquals(0.0, integrator.update(1L, -36.0, 1_000L + 181_000L)!!, 1e-12)
    }
}
