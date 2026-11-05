package com.bydmate.app.data.remote

/**
 * Reads the di+ value a command is meant to change right before and after sending it, so the
 * ack can tell the web what the car actually did (`state.before` → `state.after`) instead of
 * only "di+ accepted the phrase". `verified` is true once the value moved the expected way.
 *
 * di+ answers `success:true` even for phrases it does not act on (0.6.3's doubled prefix), so
 * this is the only signal that a command really landed. Plain logic + injected I/O: the app's
 * [VehicleCommandPoller] and the shell-uid daemon run the same code, and it is unit-testable.
 *
 * Climate and DRL for now. Windows take several seconds to travel (their final % arrives with the
 * live snapshot) and di+ exposes no seat-heat state, so those commands carry no probe.
 */
object CommandStateProbe {
    const val SETTLE_TIMEOUT_MS = 3_000L
    const val POLL_MS = 250L

    class Probe(
        /** di+ getDiPars parameter name, without braces. */
        val param: String,
        /** Key the web reads: same name as the live-snapshot field. */
        val field: String,
        /** How long to wait for the value to move. */
        val settleMs: Long = SETTLE_TIMEOUT_MS,
        val reached: (before: Double?, after: Double) -> Boolean,
    )

    /** [accepted]: di+ took the phrase. [observation]: null when no probe ran. */
    data class Sent(val accepted: Boolean, val observation: Observation?)

    data class Observation(val field: String, val before: Double?, val after: Double?, val verified: Boolean) {
        fun toResult(): Map<String, Any?> = mapOf(
            "field" to field,
            "before" to before,
            "after" to after,
        )
    }

    private const val AC_TEMP = "主驾驶空调温度"
    private const val FAN_LEVEL = "风量档位"
    private const val AC_STATUS = "空调状态"
    private const val AC_CIRC = "空调循环方式"
    private const val DRL = "日行灯"

    /**
     * `空调循环方式` value for each mode, measured on `way` 2026-11-04: `内循环` moved it 0 → 1.
     * The flap reports slower than the other climate values (`外循环` had not landed within
     * 3 s, but read 0 later), hence [CIRCULATION_SETTLE_MS].
     */
    internal val CIRCULATION_VALUES: Map<String, Double?> = mapOf("internal" to 1.0, "external" to 0.0)
    const val CIRCULATION_SETTLE_MS = 6_000L

    fun forCommand(type: String, params: Map<String, Any?>): Probe? = when (type) {
        "ac_temp_step" -> step(AC_TEMP, "ac_temp_c", params["direction"])
        "fan_speed_step" -> step(FAN_LEVEL, "fan_level", params["direction"])
        "ac" -> when (params["on"]?.toString()?.trim()?.lowercase()) {
            "true", "1" -> Probe(AC_STATUS, "ac_status") { _, after -> after != 0.0 }
            "false", "0" -> Probe(AC_STATUS, "ac_status") { _, after -> after == 0.0 }
            else -> null
        }
        "ac_recirculation" -> {
            val mode = params["mode"]?.toString()?.trim()
            if (mode !in CIRCULATION_VALUES) {
                null
            } else {
                val target = CIRCULATION_VALUES[mode]
                Probe(AC_CIRC, "ac_circ", reached = { before, after ->
                    if (target != null) after == target else before != null && after != before
                }, settleMs = CIRCULATION_SETTLE_MS)
            }
        }
        // `日行灯`: 1 = on, 2 = off (0 invalid, 3 undefined). Moved within 3 s on `way` 2026-11-04.
        "drl" -> when (params["on"]?.toString()?.trim()?.lowercase()) {
            "true", "1" -> Probe(DRL, "drl") { _, after -> after == 1.0 }
            "false", "0" -> Probe(DRL, "drl") { _, after -> after == 2.0 }
            else -> null
        }
        else -> null
    }

    /** Ack `result` for a command di+ accepted; `state` only when a probe ran. */
    fun doneResult(phrase: String, observation: Observation?): Map<String, Any?> = buildMap {
        put("phrase", phrase)
        put("verified", observation?.verified ?: false)
        observation?.let { put("state", it.toResult()) }
    }

    private fun step(param: String, field: String, direction: Any?): Probe? =
        when (direction?.toString()?.trim()) {
            "up" -> Probe(param, field) { before, after -> before != null && after > before }
            "down" -> Probe(param, field) { before, after -> before != null && after < before }
            else -> null
        }

    /**
     * Reads [Probe.param], runs [send], then re-reads every [POLL_MS] until the value reaches
     * the target or [SETTLE_TIMEOUT_MS] passes. Returns null when [send] failed.
     */
    suspend fun observe(
        probe: Probe,
        read: suspend (String) -> Double?,
        send: suspend () -> Boolean,
        nowMs: () -> Long,
        sleep: suspend (Long) -> Unit,
    ): Observation? {
        val before = read(probe.param)
        if (!send()) return null
        val deadline = nowMs() + probe.settleMs
        var after: Double?
        while (true) {
            sleep(POLL_MS)
            after = read(probe.param)
            if (after != null && probe.reached(before, after)) {
                return Observation(probe.field, before, after, verified = true)
            }
            if (nowMs() >= deadline) break
        }
        return Observation(probe.field, before, after, verified = false)
    }
}
