package com.bydmate.app.data.remote

import kotlin.math.abs

/**
 * Cloud commands written straight to `autoservice` (tx 6 `setInt`) instead of a di+ phrase
 * (B-34 slice 1). Every `(dev, SET fid, value)` below was exercised on car `way` (Leopard 3,
 * DiLink 3.0, CANFD) and is recorded in docs/AUTOSERVICE_ACTUATION.md; nothing here accepts a
 * caller-provided fid or value.
 *
 * Plain JVM with injected I/O: the shell-uid daemon supplies `service call` read/write, tests
 * supply fakes. The app process cannot `service call`, so its poller stays on di+ for now.
 *
 * The fleet has other models. `*_SET` fids are shared by every non-Toyota car, but the read fids
 * here are the CANFD branch, so each plan first reads its control and only proceeds when the
 * answer is a plausible value for that control. Anything else returns no plan and the caller
 * uses di+ exactly as before.
 */
object DirectCommand {
    data class Write(val dev: Int, val setFid: Int, val value: Int) {
        fun shellCommand(): String = "service call autoservice 6 i32 $dev i32 $setFid i32 $value"
    }

    data class Read(val dev: Int, val fid: Int) {
        fun shellCommand(): String = "service call autoservice 5 i32 $dev i32 $fid"
    }

    class Plan(
        val writes: List<Write>,
        /** Read back after the writes; every one must satisfy [reached]. */
        val checks: List<Read>,
        /** Values of [checks] read before the writes, same order. */
        val before: List<Int>,
        /** Ack `state.field`, same key the web reads for the di+ probe. */
        val field: String,
        val reached: (Int) -> Boolean,
        /**
         * True for absolute commands (on/off, level, position): repeating them through di+
         * after an unconfirmed direct write is harmless. False for ±1 steps, where a di+ retry
         * after a write that did land late would step twice.
         */
        val diplusRetrySafe: Boolean,
        val settleMs: Long = SETTLE_MS,
    )

    data class Outcome(
        /** Every write answered `Parcel(00000001)`. */
        val accepted: Boolean,
        val verified: Boolean,
        val before: List<Int>,
        val after: List<Int?>,
    )

    /**
     * `persist.sys.car.type` values whose fids below were exercised on a real car. A raw SET fid
     * can mean something else on another model, so every other car type stays on di+ until it
     * is validated the same way. 160 = `way` (元UP / Yuan Up, DiLink 3.0, CANFD), 2026-11-14.
     */
    val VALIDATED_CAR_TYPES: Set<String> = setOf("160")

    fun carTypeValidated(carType: String?): Boolean = carType?.trim() in VALIDATED_CAR_TYPES

    const val SETTLE_MS = 3_000L
    const val SLOW_SETTLE_MS = 6_000L
    const val POLL_MS = 250L

    // dev 1000 — climate.
    private const val DEV_AC = 1000
    private const val AC_POWER_SET = 501219364
    private const val AC_CTRL_MODE_SET = 501219352
    private const val AC_FAN_SET = 501219340
    private const val AC_RECIRC_SET = 501219355
    private const val AC_TEMP_DRIVER_SET = 501219368
    private val AC_POWER = Read(DEV_AC, 1077936144)
    private val AC_FAN = Read(DEV_AC, 1077936156)
    private val AC_RECIRC = Read(DEV_AC, 1077936148)
    private val AC_TEMP_DRIVER = Read(DEV_AC, 1077936168)

    /** `AC control mode`: 0 = AUTO, 1 = manual. Writing 0 with the A/C off turns it on in AUTO. */
    private const val AC_MODE_AUTO = 0
    private const val TEMP_MIN_C = 17
    private const val TEMP_MAX_C = 33
    private const val FAN_MIN = 1
    private const val FAN_MAX = 7

    // dev 1023 — front seats: 1 off · 2 level 1 · 3 level 2.
    private const val DEV_SEAT = 1023
    private const val SEAT_DRIVER_SET = 1125122068
    private const val SEAT_PASS_SET = 1125122076
    private val SEAT_DRIVER = Read(DEV_SEAT, 1335885835)
    private val SEAT_PASS = Read(DEV_SEAT, 1335885843)

    // dev 1004 — daytime running lights: 1 on · 2 off.
    private const val DEV_LIGHT = 1004
    private const val DRL_SET = 1125122118
    private val DRL = Read(DEV_LIGHT, 985661476)

    // dev 1001 — windows. SET values: 1 open full · 2 close · 4 half · 5 vent (3 = stop).
    private const val DEV_WINDOW = 1001
    private val WINDOW_SET_FIDS = listOf(1125122104, 1125122107, 1125122112, 1125122115)
    private val WINDOW_PCT = listOf(
        Read(DEV_WINDOW, 947912728),
        Read(DEV_WINDOW, 1267728400),
        Read(DEV_WINDOW, 947912736),
        Read(DEV_WINDOW, 1267728408),
    )

    /**
     * Value written and the % range accepted as "arrived", measured on `way`: vent 9–11 %,
     * half 43 %, full open 98 %, close 0 %.
     */
    private val WINDOW_PRESETS: Map<String, Pair<Int, IntRange>> = mapOf(
        "vent" to (5 to 3..25),
        "half" to (4 to 30..60),
        "open" to (1 to 90..100),
        "close" to (2 to 0..0),
    )

    /**
     * Decides how [type] would run directly, reading current state through [read].
     * Returns null — use di+ — for any type without a direct writer, for parameters the
     * allowlist would reject, and whenever a pre-read is missing or implausible.
     */
    suspend fun plan(type: String, params: Map<String, Any?>, read: suspend (Read) -> Int?): Plan? =
        when (type) {
            "ac" -> when (readBool(params["on"])) {
                true -> read(AC_POWER)?.takeIf { it in 0..1 }?.let { power ->
                    Plan(listOf(Write(DEV_AC, AC_CTRL_MODE_SET, AC_MODE_AUTO)), listOf(AC_POWER), listOf(power),
                        "ac_status", { it == 1 }, diplusRetrySafe = true)
                }
                false -> read(AC_POWER)?.takeIf { it in 0..1 }?.let { power ->
                    Plan(listOf(Write(DEV_AC, AC_POWER_SET, 0)), listOf(AC_POWER), listOf(power),
                        "ac_status", { it == 0 }, diplusRetrySafe = true)
                }
                null -> null
            }
            "ac_temp_step" -> step(params["direction"], AC_TEMP_DRIVER, AC_TEMP_DRIVER_SET, TEMP_MIN_C..TEMP_MAX_C,
                "ac_temp_c", read)
            "fan_speed_step" -> step(params["direction"], AC_FAN, AC_FAN_SET, FAN_MIN..FAN_MAX, "fan_level", read)
            "ac_recirculation" -> {
                val target = when (params["mode"]?.toString()?.trim()) {
                    "internal" -> 1
                    "external" -> 0
                    else -> null
                }
                target?.let { t ->
                    read(AC_RECIRC)?.takeIf { it in 0..1 }?.let { now ->
                        Plan(listOf(Write(DEV_AC, AC_RECIRC_SET, t)), listOf(AC_RECIRC), listOf(now),
                            "ac_circ", { it == t }, diplusRetrySafe = true, settleMs = SLOW_SETTLE_MS)
                    }
                }
            }
            "seat_heat_driver" -> seat(params["level"], SEAT_DRIVER, SEAT_DRIVER_SET, "seat_heat_driver", read)
            "seat_heat_pass" -> seat(params["level"], SEAT_PASS, SEAT_PASS_SET, "seat_heat_pass", read)
            "drl" -> {
                val target = when (readBool(params["on"])) {
                    true -> 1
                    false -> 2
                    null -> null
                }
                target?.let { t ->
                    read(DRL)?.takeIf { it in 1..2 }?.let { now ->
                        Plan(listOf(Write(DEV_LIGHT, DRL_SET, t)), listOf(DRL), listOf(now),
                            "drl", { it == t }, diplusRetrySafe = true)
                    }
                }
            }
            "windows_preset" -> WINDOW_PRESETS[params["preset"]?.toString()?.trim()]?.let { (value, arrived) ->
                val before = WINDOW_PCT.map { read(it)?.takeIf { pct -> pct in 0..100 } ?: return@let null }
                Plan(
                    writes = WINDOW_SET_FIDS.map { Write(DEV_WINDOW, it, value) },
                    checks = WINDOW_PCT,
                    before = before,
                    field = "window_pct",
                    reached = { it in arrived },
                    diplusRetrySafe = true,
                    settleMs = SLOW_SETTLE_MS,
                )
            }
            // `window` with an arbitrary pct has no direct value; it stays on di+.
            else -> null
        }

    /**
     * Car state for the safety guards, read straight from `autoservice` right before a direct
     * write. di+ data can be missing or stale while the direct path still works, and
     * [CommandAllowlist.movementBlockReason] lets a command through when it has no data —
     * harmless for di+ (no di+, no command), unsafe for a direct window write.
     */
    data class Guard(val speedKmh: Double?, val gear: Int?, val chargeGunState: Int?, val voltage12v: Double?)

    sealed class GuardResult {
        object Clear : GuardResult()
        data class Blocked(val reason: String) : GuardResult()
        /** Could not read enough to decide: do not write directly, let di+ and its guards decide. */
        object Unknown : GuardResult()
    }

    /** Same rules as [CommandAllowlist], but fail-closed: unreadable state is never "parked". */
    fun guard(type: String, g: Guard): GuardResult {
        val v = g.voltage12v
        if (v != null && v > 0 && v < MIN_AUX_VOLTAGE_V) return GuardResult.Blocked("aux_voltage_low")
        if (type in MOVEMENT_EXEMPT_TYPES) return GuardResult.Clear
        val speed = g.speedKmh ?: return GuardResult.Unknown
        if (speed >= 1.0) return GuardResult.Blocked("vehicle_moving")
        if (g.gear == GEAR_PARK) return GuardResult.Clear
        if (g.chargeGunState in CHARGING_GUN_STATES) return GuardResult.Clear
        return if (g.gear == null) GuardResult.Unknown else GuardResult.Blocked("gear_not_park")
    }

    /** Guard reads, all fixed: speed (float), gear, charge gun, 12 V (float). */
    val GUARD_SPEED = Read(1013, -1807745016)
    val GUARD_GEAR = Read(1011, 555745336)
    val GUARD_GUN = Read(1009, 876609586)
    val GUARD_12V = Read(1001, 1128267816)

    private const val MIN_AUX_VOLTAGE_V = 11.8
    private const val GEAR_PARK = 1
    private val CHARGING_GUN_STATES = setOf(2, 3, 4, 5)
    /** Climate is comfort-only and overridable from the screen, so it may run while driving. */
    private val MOVEMENT_EXEMPT_TYPES = setOf("ac", "ac_temp_step", "fan_speed_step", "ac_recirculation")

    /** Writes [plan], then re-reads every [POLL_MS] until all checks pass or it times out. */
    suspend fun execute(
        plan: Plan,
        write: suspend (Write) -> Boolean,
        read: suspend (Read) -> Int?,
        nowMs: () -> Long,
        sleep: suspend (Long) -> Unit,
    ): Outcome {
        var accepted = true
        for (w in plan.writes) {
            if (!write(w)) accepted = false
        }
        if (!accepted) return Outcome(false, false, plan.before, plan.checks.map { null })
        val deadline = nowMs() + plan.settleMs
        while (true) {
            sleep(POLL_MS)
            val after = plan.checks.map { read(it) }
            if (after.all { it != null && plan.reached(it) }) return Outcome(true, true, plan.before, after)
            if (nowMs() >= deadline) return Outcome(true, false, plan.before, after)
        }
    }

    // ---- window to N % (closed loop: move, watch %, stop) -------------------------------------

    private const val WINDOW_OPEN_FULL = 1
    private const val WINDOW_CLOSE = 2
    private const val WINDOW_STOP = 3
    /** Measured on `way` 2026-11-14: stop issued at 27 % settled at 23 % — ~4 % of coast. */
    const val WINDOW_STOP_LEAD_PCT = 3
    const val WINDOW_TRAVEL_TIMEOUT_MS = 8_000L
    const val WINDOW_POLL_MS = 100L

    private val WINDOW_INDEX = mapOf("driver" to listOf(0), "pass" to listOf(1), "rl" to listOf(2), "rr" to listOf(3),
        "all" to listOf(0, 1, 2, 3))

    data class WindowTarget(val windows: List<Int>, val pct: Int)

    /** Parses `window` params the way [CommandAllowlist] accepts them; null = not a direct window command. */
    fun windowTarget(params: Map<String, Any?>): WindowTarget? {
        val windows = WINDOW_INDEX[params["which"]?.toString()?.trim()] ?: return null
        val pct = readInt(params["pct"])?.takeIf { it in 0..100 } ?: return null
        return WindowTarget(windows, pct)
    }

    data class WindowOutcome(val accepted: Boolean, val before: List<Int>, val after: List<Int?>, val verified: Boolean)

    /**
     * Drives each window in [target] to its %: 0 → close, ≥ 95 → open full (the car tops out at
     * ~98 %), anything else → move toward it and write stop [WINDOW_STOP_LEAD_PCT] early.
     * [readAll] reads the four % fids in one go (index = window); null for unreadable.
     * Returns null when a pre-read is missing — caller uses di+.
     */
    suspend fun executeWindow(
        target: WindowTarget,
        write: suspend (Write) -> Boolean,
        readAll: suspend () -> List<Int?>,
        nowMs: () -> Long,
        sleep: suspend (Long) -> Unit,
    ): WindowOutcome? {
        val start = readAll()
        val before = target.windows.map { i -> start.getOrNull(i)?.takeIf { it in 0..100 } ?: return null }
        val goal = target.pct
        // Per window: the value to write and the stop condition (null = runs to its end stop).
        val moving = mutableMapOf<Int, ((Int) -> Boolean)?>()
        var accepted = true
        target.windows.forEachIndexed { k, i ->
            val now = before[k]
            val (value, stopWhen) = when {
                goal == 0 -> WINDOW_CLOSE to null
                goal >= 95 -> WINDOW_OPEN_FULL to null
                abs(now - goal) <= WINDOW_STOP_LEAD_PCT -> return@forEachIndexed // already there
                goal > now -> WINDOW_OPEN_FULL to { pct: Int -> pct >= goal - WINDOW_STOP_LEAD_PCT }
                else -> WINDOW_CLOSE to { pct: Int -> pct <= goal + WINDOW_STOP_LEAD_PCT }
            }
            if (write(Write(DEV_WINDOW, WINDOW_SET_FIDS[i], value))) moving[i] = stopWhen else accepted = false
        }
        if (!accepted) {
            // Never leave a half-started set of windows travelling to an end stop.
            moving.keys.forEach { write(Write(DEV_WINDOW, WINDOW_SET_FIDS[it], WINDOW_STOP)) }
            return WindowOutcome(false, before, target.windows.map { null }, false)
        }
        val deadline = nowMs() + WINDOW_TRAVEL_TIMEOUT_MS
        val pending = moving.filterValues { it != null }.toMutableMap()
        var last = start
        while (pending.isNotEmpty() && nowMs() < deadline) {
            sleep(WINDOW_POLL_MS)
            last = readAll()
            for ((i, stopWhen) in pending.entries.toList()) {
                val pct = last.getOrNull(i) ?: continue
                if (stopWhen!!(pct)) {
                    write(Write(DEV_WINDOW, WINDOW_SET_FIDS[i], WINDOW_STOP))
                    pending.remove(i)
                }
            }
        }
        // Timed out mid-travel: stop rather than let it run to the end stop.
        pending.keys.forEach { write(Write(DEV_WINDOW, WINDOW_SET_FIDS[it], WINDOW_STOP)) }
        // Let the glass settle (end-stop runs take ~2 s from either end), then measure.
        val settleUntil = nowMs() + SETTLE_MS
        while (nowMs() < settleUntil) {
            sleep(POLL_MS)
            last = readAll()
            if (target.windows.all { i -> last.getOrNull(i)?.let { windowArrived(goal, it) } == true }) break
        }
        val after = target.windows.map { last.getOrNull(it) }
        val verified = after.all { it != null && windowArrived(goal, it) }
        return WindowOutcome(true, before, after, verified)
    }

    /** Close is exact; full open tops out near 98 %; a stop lands within ~5 % of the goal. */
    internal fun windowArrived(goal: Int, pct: Int): Boolean = when {
        goal == 0 -> pct == 0
        goal >= 95 -> pct >= 90
        else -> abs(pct - goal) <= 6
    }

    /** Ack for a finished direct window command. */
    fun windowDoneResult(phrase: String, outcome: WindowOutcome): Map<String, Any?> = mapOf(
        "phrase" to phrase,
        "path" to "direct",
        "verified" to outcome.verified,
        "state" to mapOf(
            "field" to "window_pct",
            "before" to single(outcome.before),
            "after" to single(outcome.after),
        ),
    )

    /** One shell line that reads all four window % fids; outputs one `Result: Parcel(...)` each. */
    fun windowReadAllCommand(): String = WINDOW_PCT.joinToString("; ") { it.shellCommand() }

    /** Decodes the multi-line output of [windowReadAllCommand], in window order. */
    fun parseReadLines(out: String?, count: Int): List<Int?> {
        val lines = out?.lines()?.filter { it.contains("Parcel(") } ?: emptyList()
        return List(count) { parseRead(lines.getOrNull(it)) }
    }

    /** True when the direct path should be abandoned and the same command sent through di+. */
    fun shouldRetryViaDiplus(plan: Plan, outcome: Outcome): Boolean =
        !outcome.accepted || (!outcome.verified && plan.diplusRetrySafe)

    /** Ack `result` for a command finished on the direct path. */
    fun doneResult(phrase: String, plan: Plan, outcome: Outcome): Map<String, Any?> = mapOf(
        "phrase" to phrase,
        "path" to "direct",
        "verified" to outcome.verified,
        "state" to mapOf(
            "field" to plan.field,
            "before" to single(outcome.before),
            "after" to single(outcome.after),
        ),
    )

    /** Write reply check: only `Parcel(00000001)` means the head unit accepted the request. */
    fun isAccepted(reply: String?): Boolean = reply?.contains("Parcel(00000001") == true

    private val PARCEL_INT = Regex("""Parcel\(00000000\s+([0-9a-fA-F]{8})""")

    /** Read reply decoder; null for any error status (e.g. -10011). */
    fun parseRead(reply: String?): Int? =
        reply?.let { PARCEL_INT.find(it)?.groupValues?.get(1)?.toLong(16)?.toInt() }

    private fun single(values: List<Int?>): Any? = if (values.size == 1) values[0] else values

    private suspend fun step(
        direction: Any?,
        check: Read,
        setFid: Int,
        range: IntRange,
        field: String,
        read: suspend (Read) -> Int?,
    ): Plan? {
        val delta = when (direction?.toString()?.trim()) {
            "up" -> 1
            "down" -> -1
            else -> return null
        }
        // A step only makes sense on a running A/C; off or unknown stays on di+.
        if (read(AC_POWER) != 1) return null
        val now = read(check)?.takeIf { it in range } ?: return null
        val target = now + delta
        if (target !in range) return null
        return Plan(listOf(Write(check.dev, setFid, target)), listOf(check), listOf(now), field,
            { it == target }, diplusRetrySafe = false)
    }

    private suspend fun seat(
        level: Any?,
        check: Read,
        setFid: Int,
        field: String,
        read: suspend (Read) -> Int?,
    ): Plan? {
        // Cloud levels 0 / 2 → autoservice 1 (off) / 3 (level 2). Level 1 is not a cloud value.
        val target = when (readInt(level)) {
            0 -> 1
            2 -> 3
            else -> return null
        }
        val now = read(check)?.takeIf { it in 1..3 } ?: return null
        return Plan(listOf(Write(DEV_SEAT, setFid, target)), listOf(check), listOf(now), field,
            { it == target }, diplusRetrySafe = true)
    }

    private fun readInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        is Float -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun readBool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Int -> when (value) {
            1 -> true
            0 -> false
            else -> null
        }
        is String -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }
}
