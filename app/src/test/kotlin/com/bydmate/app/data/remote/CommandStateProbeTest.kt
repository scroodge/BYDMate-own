package com.bydmate.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandStateProbeTest {
    /** Fake di+: [values] are returned by successive reads; the last one repeats. */
    private class FakeCar(vararg values: Double?) {
        private val queue = values.toMutableList()
        var clock = 0L
        val reads = mutableListOf<String>()

        fun read(param: String): Double? {
            reads += param
            return if (queue.size > 1) queue.removeAt(0) else queue.firstOrNull()
        }

        fun observe(probe: CommandStateProbe.Probe, accept: Boolean = true) = runBlocking {
            CommandStateProbe.observe(
                probe = probe,
                read = { read(it) },
                send = { accept },
                nowMs = { clock },
                sleep = { clock += it },
            )
        }
    }

    private fun probe(type: String, vararg params: Pair<String, Any?>): CommandStateProbe.Probe {
        val p = CommandStateProbe.forCommand(type, mapOf(*params))
        assertNotNull(p)
        return p!!
    }

    @Test
    fun tempStepIsVerifiedWhenValueMovesTheRightWay() {
        val car = FakeCar(24.0, 24.0, 23.0)
        val obs = car.observe(probe("ac_temp_step", "direction" to "down"))!!
        assertTrue(obs.verified)
        assertEquals("ac_temp_c", obs.field)
        assertEquals(24.0, obs.before)
        assertEquals(23.0, obs.after)
        assertEquals(listOf("主驾驶空调温度", "主驾驶空调温度", "主驾驶空调温度"), car.reads)
    }

    @Test
    fun stepThatNeverMovesTimesOutUnverified() {
        val car = FakeCar(3.0)
        val obs = car.observe(probe("fan_speed_step", "direction" to "up"))!!
        assertFalse(obs.verified)
        assertEquals(3.0, obs.after)
        assertTrue(car.clock >= CommandStateProbe.SETTLE_TIMEOUT_MS)
        assertTrue(car.clock < CommandStateProbe.SETTLE_TIMEOUT_MS + 2 * CommandStateProbe.POLL_MS)
    }

    @Test
    fun wrongDirectionIsNotVerified() {
        assertFalse(FakeCar(3.0, 2.0).observe(probe("fan_speed_step", "direction" to "up"))!!.verified)
    }

    @Test
    fun acOnAndOffCheckTheTargetState() {
        assertTrue(FakeCar(0.0, 1.0).observe(probe("ac", "on" to true))!!.verified)
        assertTrue(FakeCar(1.0, 0.0).observe(probe("ac", "on" to false))!!.verified)
        assertFalse(FakeCar(0.0).observe(probe("ac", "on" to true))!!.verified)
    }

    @Test
    fun drlOnAndOffCheckTheDiplusState() {
        val car = FakeCar(1.0, 2.0)
        val off = car.observe(probe("drl", "on" to false))!!
        assertTrue(off.verified)
        assertEquals("drl", off.field)
        assertEquals("日行灯", car.reads.first())
        assertTrue(FakeCar(2.0, 1.0).observe(probe("drl", "on" to true))!!.verified)
        // 0 = invalid reading: never counts as "off".
        assertFalse(FakeCar(1.0, 0.0).observe(probe("drl", "on" to false))!!.verified)
        assertNull(CommandStateProbe.forCommand("drl", mapOf("on" to "maybe")))
    }

    @Test
    fun circulationChecksTheMeasuredModeValueAndWaitsLonger() {
        assertTrue(FakeCar(0.0, 1.0).observe(probe("ac_recirculation", "mode" to "internal"))!!.verified)
        assertTrue(FakeCar(1.0, 1.0, 0.0).observe(probe("ac_recirculation", "mode" to "external"))!!.verified)
        // Already internal: the value is at the target, so the command is confirmed.
        assertTrue(FakeCar(1.0).observe(probe("ac_recirculation", "mode" to "internal"))!!.verified)
        val car = FakeCar(1.0)
        assertFalse(car.observe(probe("ac_recirculation", "mode" to "external"))!!.verified)
        assertTrue(car.clock >= CommandStateProbe.CIRCULATION_SETTLE_MS)
    }

    @Test
    fun rejectedSendSkipsTheAfterRead() {
        val car = FakeCar(24.0)
        assertNull(car.observe(probe("ac_temp_step", "direction" to "up"), accept = false))
        assertEquals(1, car.reads.size)
    }

    @Test
    fun unknownBeforeValueCannotVerifyAStep() {
        assertFalse(FakeCar(null, 23.0).observe(probe("ac_temp_step", "direction" to "down"))!!.verified)
    }

    @Test
    fun commandsWithoutReadableStateHaveNoProbe() {
        assertNull(CommandStateProbe.forCommand("window", mapOf("which" to "driver", "pct" to 50)))
        assertNull(CommandStateProbe.forCommand("windows_preset", mapOf("preset" to "vent")))
        assertNull(CommandStateProbe.forCommand("seat_heat_driver", mapOf("level" to 2)))
        assertNull(CommandStateProbe.forCommand("fan_speed_step", mapOf("direction" to "sideways")))
    }

    @Test
    fun doneResultCarriesStateOnlyWhenProbed() {
        val obs = CommandStateProbe.Observation("fan_level", 2.0, 3.0, verified = true)
        val probed = CommandStateProbe.doneResult("升风速", obs)
        assertEquals(true, probed["verified"])
        assertEquals(mapOf("field" to "fan_level", "before" to 2.0, "after" to 3.0), probed["state"])

        val plain = CommandStateProbe.doneResult("车窗通风", null)
        assertEquals(false, plain["verified"])
        assertFalse(plain.containsKey("state"))
    }
}
