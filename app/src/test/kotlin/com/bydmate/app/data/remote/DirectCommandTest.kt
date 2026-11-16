package com.bydmate.app.data.remote

import com.bydmate.app.data.remote.DirectCommand.Read
import com.bydmate.app.data.remote.DirectCommand.Write
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCommandTest {
    /**
     * Fake head unit: a fid → value table. A write to a SET fid lands on [landsOn] (its read fid)
     * after [lag] reads, unless [accept] is false.
     */
    private class FakeCar(
        initial: Map<Int, Int?>,
        private val landsOn: Map<Int, Int> = emptyMap(),
        private val accept: Boolean = true,
        private val lag: Int = 0,
    ) {
        val values = initial.toMutableMap()
        val writes = mutableListOf<Write>()
        private val pending = mutableListOf<Pair<Int, Int>>()
        private var readsUntilLand = 0
        var clock = 0L

        fun read(r: Read): Int? {
            if (pending.isNotEmpty()) {
                if (readsUntilLand <= 0) {
                    pending.forEach { (fid, v) -> values[fid] = v }
                    pending.clear()
                } else {
                    readsUntilLand--
                }
            }
            return values[r.fid]
        }

        fun write(w: Write): Boolean {
            writes += w
            if (!accept) return false
            landsOn[w.setFid]?.let { pending += it to w.value }
            readsUntilLand = lag
            return true
        }

        fun run(plan: DirectCommand.Plan) = runBlocking {
            DirectCommand.execute(plan, { write(it) }, { read(it) }, { clock }, { clock += it })
        }

        fun plan(type: String, params: Map<String, Any?>) = runBlocking {
            DirectCommand.plan(type, params) { read(it) }
        }
    }

    private val acPower = 1077936144
    private val acTemp = 1077936168
    private val acFan = 1077936156
    private val seatDriver = 1335885835
    private val drl = 985661476
    private val windows = listOf(947912728, 1267728400, 947912736, 1267728408)

    @Test
    fun `ac on writes AUTO, the single write that also powers the A-C on`() {
        val car = FakeCar(mapOf(acPower to 0))
        val plan = car.plan("ac", mapOf("on" to true))!!
        assertEquals(listOf(Write(1000, 501219352, 0)), plan.writes)
        assertEquals("service call autoservice 6 i32 1000 i32 501219352 i32 0", plan.writes[0].shellCommand())
        // On the car AUTO = 0 powers the A/C on; the fake only stores values, so seed that effect.
        car.values[acPower] = 1
        assertTrue(car.run(plan).verified)
    }

    @Test
    fun `ac off writes power 0 and verifies by read-back`() {
        val car = FakeCar(mapOf(acPower to 1), landsOn = mapOf(501219364 to acPower), lag = 2)
        val plan = car.plan("ac", mapOf("on" to "false"))!!
        assertEquals(listOf(Write(1000, 501219364, 0)), plan.writes)
        val out = car.run(plan)
        assertTrue(out.accepted)
        assertTrue(out.verified)
        assertEquals(listOf(1), out.before)
        assertEquals(listOf(0), out.after)
    }

    @Test
    fun `temperature step writes current plus one`() {
        val car = FakeCar(mapOf(acPower to 1, acTemp to 24), landsOn = mapOf(501219368 to acTemp))
        val plan = car.plan("ac_temp_step", mapOf("direction" to "up"))!!
        assertEquals(listOf(Write(1000, 501219368, 25)), plan.writes)
        assertTrue(car.run(plan).verified)
    }

    @Test
    fun `step at the limit or with the A-C off has no direct plan`() {
        assertNull(FakeCar(mapOf(acPower to 1, acTemp to 33)).plan("ac_temp_step", mapOf("direction" to "up")))
        assertNull(FakeCar(mapOf(acPower to 1, acFan to 1)).plan("fan_speed_step", mapOf("direction" to "down")))
        assertNull(FakeCar(mapOf(acPower to 0, acTemp to 24)).plan("ac_temp_step", mapOf("direction" to "up")))
    }

    @Test
    fun `an unconfirmed step is not retried through di+ - it could step twice`() {
        val car = FakeCar(mapOf(acPower to 1, acFan to 3)) // write never lands
        val plan = car.plan("fan_speed_step", mapOf("direction" to "up"))!!
        val out = car.run(plan)
        assertTrue(out.accepted)
        assertFalse(out.verified)
        assertFalse(DirectCommand.shouldRetryViaDiplus(plan, out))
    }

    @Test
    fun `an unconfirmed absolute command is retried through di+`() {
        val car = FakeCar(mapOf(seatDriver to 1)) // write never lands
        val plan = car.plan("seat_heat_driver", mapOf("level" to 2))!!
        assertEquals(listOf(Write(1023, 1125122068, 3)), plan.writes)
        val out = car.run(plan)
        assertFalse(out.verified)
        assertTrue(DirectCommand.shouldRetryViaDiplus(plan, out))
    }

    @Test
    fun `a refused write is always retried through di+`() {
        val car = FakeCar(mapOf(acPower to 1, acTemp to 24), accept = false)
        val plan = car.plan("ac_temp_step", mapOf("direction" to "down"))!!
        val out = car.run(plan)
        assertFalse(out.accepted)
        assertTrue(DirectCommand.shouldRetryViaDiplus(plan, out))
    }

    @Test
    fun `implausible pre-read means another model - stay on di+`() {
        // 0xFFFF = feature not fitted; null = read refused (-10011).
        assertNull(FakeCar(mapOf(acPower to 0xFFFF)).plan("ac", mapOf("on" to true)))
        assertNull(FakeCar(mapOf(acPower to null)).plan("ac", mapOf("on" to false)))
        assertNull(FakeCar(mapOf(seatDriver to 0xFFFF)).plan("seat_heat_driver", mapOf("level" to 0)))
        assertNull(FakeCar(mapOf(drl to 0)).plan("drl", mapOf("on" to true)))
    }

    @Test
    fun `seat and drl map cloud values to autoservice values`() {
        val seat = FakeCar(mapOf(seatDriver to 3)).plan("seat_heat_driver", mapOf("level" to 0))!!
        assertEquals(1, seat.writes.single().value)
        assertNull(FakeCar(mapOf(seatDriver to 1)).plan("seat_heat_driver", mapOf("level" to 1)))
        val pass = FakeCar(mapOf(1335885843 to 1)).plan("seat_heat_pass", mapOf("level" to 2))!!
        assertEquals(Write(1023, 1125122076, 3), pass.writes.single())
        val off = FakeCar(mapOf(drl to 1)).plan("drl", mapOf("on" to false))!!
        assertEquals(Write(1004, 1125122118, 2), off.writes.single())
    }

    @Test
    fun `window preset writes all four windows and waits for every one`() {
        val set = listOf(1125122104, 1125122107, 1125122112, 1125122115)
        val car = FakeCar(windows.associateWith { 30 })
        val plan = car.plan("windows_preset", mapOf("preset" to "close"))!!
        assertEquals(set.map { Write(1001, it, 2) }, plan.writes)
        // Three windows close, the rear-right one stays at 30 %: not verified until all four arrive.
        windows.take(3).forEach { car.values[it] = 0 }
        assertFalse(car.run(plan).verified)
        car.values[windows[3]] = 0
        assertTrue(car.run(plan).verified)

        val half = FakeCar(windows.associateWith { 0 }).plan("windows_preset", mapOf("preset" to "half"))!!
        assertEquals(4, half.writes.first().value)
        assertTrue(half.reached(43))
        assertFalse(half.reached(98))
    }

    @Test
    fun `window preset with one unreadable window stays on di+`() {
        val partial = windows.associateWith<Int, Int?> { 0 }.toMutableMap().apply { this[windows[2]] = null }
        assertNull(FakeCar(partial).plan("windows_preset", mapOf("preset" to "vent")))
    }

    @Test
    fun `types without a direct writer and bad params have no plan`() {
        val car = FakeCar(mapOf(acPower to 1, 1077936148 to 0))
        assertNull(car.plan("window", mapOf("which" to "driver", "pct" to 25)))
        assertNull(car.plan("camera_snapshot", emptyMap()))
        assertNull(car.plan("ac_recirculation", mapOf("mode" to "sideways")))
        assertNull(car.plan("ac", mapOf("on" to "maybe")))
        assertNotNull(car.plan("ac_recirculation", mapOf("mode" to "internal")))
    }

    @Test
    fun `guard is fail-closed for windows and seats but lets climate run while driving`() {
        val parked = DirectCommand.Guard(speedKmh = 0.0, gear = 1, chargeGunState = 1, voltage12v = 12.3)
        assertEquals(DirectCommand.GuardResult.Clear, DirectCommand.guard("windows_preset", parked))

        val driving = DirectCommand.Guard(speedKmh = 42.0, gear = 4, chargeGunState = 1, voltage12v = 13.9)
        assertEquals(DirectCommand.GuardResult.Blocked("vehicle_moving"), DirectCommand.guard("windows_preset", driving))
        assertEquals(DirectCommand.GuardResult.Clear, DirectCommand.guard("ac_temp_step", driving))

        val inDrive = DirectCommand.Guard(speedKmh = 0.0, gear = 4, chargeGunState = 1, voltage12v = 12.5)
        assertEquals(DirectCommand.GuardResult.Blocked("gear_not_park"), DirectCommand.guard("seat_heat_driver", inDrive))

        val chargingInN = DirectCommand.Guard(speedKmh = 0.0, gear = 3, chargeGunState = 2, voltage12v = 12.5)
        assertEquals(DirectCommand.GuardResult.Clear, DirectCommand.guard("drl", chargingInN))

        // di+ down is no longer "no data → allowed": unreadable speed or gear means do not write.
        val blind = DirectCommand.Guard(speedKmh = null, gear = null, chargeGunState = null, voltage12v = null)
        assertEquals(DirectCommand.GuardResult.Unknown, DirectCommand.guard("windows_preset", blind))
        assertEquals(DirectCommand.GuardResult.Unknown,
            DirectCommand.guard("windows_preset", blind.copy(speedKmh = 0.0)))

        val flat = parked.copy(voltage12v = 11.2)
        assertEquals(DirectCommand.GuardResult.Blocked("aux_voltage_low"), DirectCommand.guard("ac", flat))
    }

    /** Glass model: moves [step] % per read toward its end stop, coasts [coast] % after a stop. */
    private class FakeGlass(start: List<Int>, private val step: Int = 4, private val coast: Int = 4) {
        val pct = start.toMutableList()
        private val dir = MutableList(start.size) { 0 }
        val writes = mutableListOf<Write>()
        var clock = 0L
        private val setFids = listOf(1125122104, 1125122107, 1125122112, 1125122115)

        fun write(w: Write): Boolean {
            writes += w
            val i = setFids.indexOf(w.setFid)
            when (w.value) {
                1 -> dir[i] = 1
                2 -> dir[i] = -1
                3 -> {
                    if (dir[i] != 0) pct[i] = (pct[i] + dir[i] * coast).coerceIn(0, 98)
                    dir[i] = 0
                }
            }
            return true
        }

        fun readAll(): List<Int?> {
            for (i in pct.indices) if (dir[i] != 0) pct[i] = (pct[i] + dir[i] * step).coerceIn(0, 98)
            return pct.toList()
        }

        fun run(target: DirectCommand.WindowTarget) = runBlocking {
            DirectCommand.executeWindow(target, { write(it) }, { readAll() }, { clock }, { clock += it })
        }
    }

    @Test
    fun `window to 25 percent closes, stops early and lands near the goal`() {
        val glass = FakeGlass(listOf(98, 0, 0, 0))
        val target = DirectCommand.windowTarget(mapOf("which" to "driver", "pct" to 25))!!
        val out = glass.run(target)!!
        assertTrue(out.accepted)
        assertEquals(Write(1001, 1125122104, 2), glass.writes.first())
        assertEquals(3, glass.writes.last().value)
        assertTrue("landed at ${glass.pct[0]}", glass.pct[0] in 19..31)
        assertTrue(out.verified)
    }

    @Test
    fun `window to 0 or 100 uses the end-stop values without a stop`() {
        val closing = FakeGlass(listOf(40, 0, 0, 0))
        val out = closing.run(DirectCommand.windowTarget(mapOf("which" to "driver", "pct" to 0))!!)!!
        assertEquals(listOf(Write(1001, 1125122104, 2)), closing.writes)
        assertTrue(out.verified)

        val opening = FakeGlass(listOf(0, 0, 0, 0))
        opening.run(DirectCommand.windowTarget(mapOf("which" to "pass", "pct" to 100))!!)
        assertEquals(listOf(Write(1001, 1125122107, 1)), opening.writes)
    }

    @Test
    fun `all windows to 50 percent stop independently`() {
        val glass = FakeGlass(listOf(0, 98, 0, 49))
        val out = glass.run(DirectCommand.windowTarget(mapOf("which" to "all", "pct" to 50))!!)!!
        // Window 3 is already within the lead: never written.
        assertTrue(glass.writes.none { it.setFid == 1125122115 })
        assertTrue(glass.pct.all { it in 44..56 })
        assertTrue(out.verified)
    }

    @Test
    fun `unreadable window percent stays on di+, bad params are not a window target`() {
        val target = DirectCommand.windowTarget(mapOf("which" to "rl", "pct" to 30))!!
        val out = runBlocking {
            DirectCommand.executeWindow(target, { true }, { listOf(0, 0, null, 0) }, { 0L }, {})
        }
        assertNull(out)
        assertNull(DirectCommand.windowTarget(mapOf("which" to "roof", "pct" to 30)))
        assertNull(DirectCommand.windowTarget(mapOf("which" to "driver", "pct" to 130)))
    }

    @Test
    fun `batched window read decodes four parcel lines in order`() {
        val out = """
            Result: Parcel(00000000 00000009   '........')
            Result: Parcel(ffffd8e5 '....')
            Result: Parcel(00000000 0000002b   '........')
            Result: Parcel(00000000 00000062   '........')
        """.trimIndent()
        assertEquals(listOf(9, null, 43, 98), DirectCommand.parseReadLines(out, 4))
        assertEquals(4, DirectCommand.windowReadAllCommand().split("; ").size)
    }

    @Test
    fun `only validated car types take the direct path`() {
        assertTrue(DirectCommand.carTypeValidated("160"))
        assertTrue(DirectCommand.carTypeValidated("160\n"))
        assertFalse(DirectCommand.carTypeValidated("161"))
        assertFalse(DirectCommand.carTypeValidated(""))
        assertFalse(DirectCommand.carTypeValidated(null))
    }

    @Test
    fun `binder replies decode like the daemon reads`() {
        assertTrue(DirectCommand.isAccepted("Result: Parcel(00000001    '....')"))
        assertFalse(DirectCommand.isAccepted("Result: Parcel(ffffd8e5    '....')"))
        assertFalse(DirectCommand.isAccepted(null))
        assertEquals(24, DirectCommand.parseRead("Result: Parcel(00000000 00000018   '........')"))
        assertNull(DirectCommand.parseRead("Result: Parcel(ffffd8e5 '....')"))
    }

    @Test
    fun `done result carries path, verification and state`() {
        val car = FakeCar(mapOf(acPower to 1), landsOn = mapOf(501219364 to acPower))
        val plan = car.plan("ac", mapOf("on" to false))!!
        val result = DirectCommand.doneResult("关空调", plan, car.run(plan))
        assertEquals("direct", result["path"])
        assertEquals(true, result["verified"])
        assertEquals(mapOf("field" to "ac_status", "before" to 1, "after" to 0), result["state"])
    }
}
