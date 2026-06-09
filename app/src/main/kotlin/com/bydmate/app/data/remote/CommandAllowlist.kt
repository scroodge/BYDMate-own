package com.bydmate.app.data.remote

object CommandAllowlist {
    private val DENY_PATTERNS = listOf(
        "发送CAN", "执行SHELL", "执行TSHELL",
        "点击", "滑动", "按钮", "按键", "浮窗", "下电",
    )

    private val WINDOW_TARGETS = mapOf(
        "driver" to "主驾车窗",
        "pass" to "副驾车窗",
        "rl" to "左后车窗",
        "rr" to "右后车窗",
        "all" to "全部车窗",
    )

    private val WINDOWS_PRESET_PHRASES = mapOf(
        "vent" to "车窗通风",
        "close" to "车窗关闭",
        "open" to "车窗全开",
        "half" to "车窗半开",
    )

    private const val GEAR_PARK = 1
    private const val MIN_AUX_VOLTAGE_V = 11.8

    sealed class BuildResult {
        data class Ok(val phrase: String) : BuildResult()
        data class Rejected(val reason: String) : BuildResult()
    }

    fun movementBlockReason(data: DiParsData?): String? {
        if (data == null) return null
        val speed = data.speed ?: return "speed_unknown"
        if (speed != 0) return "vehicle_moving"
        val gear = data.gear ?: return "gear_unknown"
        if (gear != GEAR_PARK) return "gear_not_park"
        return null
    }

    fun auxVoltageBlockReason(data: DiParsData?): String? {
        val v = data?.voltage12v ?: return null
        if (v > 0 && v < MIN_AUX_VOLTAGE_V) return "aux_voltage_low"
        return null
    }

    fun buildPhrase(type: String, params: Map<String, Any?>): BuildResult {
        val phrase = when (type) {
            "lock" -> "车门上锁"
            "unlock" -> "车门解锁"
            "set_soc_limit" -> {
                val value = readInt(params["value"])
                    ?: return BuildResult.Rejected("set_soc_limit.value invalid")
                if (value !in 50..100) return BuildResult.Rejected("set_soc_limit.value out of range")
                "设置SOC$value"
            }
            "schedule_charge" -> {
                val hh = readInt(params["hh"]) ?: return BuildResult.Rejected("schedule_charge.hh invalid")
                val mm = readInt(params["mm"]) ?: return BuildResult.Rejected("schedule_charge.mm invalid")
                val end = readInt(params["end"]) ?: return BuildResult.Rejected("schedule_charge.end invalid")
                if (hh !in 0..23 || mm !in 0..59 || end !in 0..23) {
                    return BuildResult.Rejected("schedule_charge params out of range")
                }
                "预约充电$hh:${mm.toString().padStart(2, '0')}-$end"
            }
            "window" -> {
                val which = params["which"]?.toString()?.trim().orEmpty()
                val cn = WINDOW_TARGETS[which]
                    ?: return BuildResult.Rejected("window.which invalid")
                val pct = readInt(params["pct"])
                    ?: return BuildResult.Rejected("window.pct invalid")
                if (pct !in 0..100) return BuildResult.Rejected("window.pct out of range")
                "${cn}打开百分之$pct"
            }
            "windows_preset" -> {
                val preset = params["preset"]?.toString()?.trim().orEmpty()
                WINDOWS_PRESET_PHRASES[preset]
                    ?: return BuildResult.Rejected("windows_preset.preset invalid")
            }
            "ac" -> {
                when (readBool(params["on"])) {
                    true -> "自动空调"
                    false -> "关闭空调"
                    null -> return BuildResult.Rejected("ac.on invalid")
                }
            }
            "ac_vent" -> {
                when (readBool(params["on"])) {
                    true -> "打开空调通风"
                    false -> "关闭空调"
                    null -> return BuildResult.Rejected("ac_vent.on invalid")
                }
            }
            "sunroof" -> {
                val pct = readInt(params["pct"])
                    ?: return BuildResult.Rejected("sunroof.pct invalid")
                if (pct !in 0..100) return BuildResult.Rejected("sunroof.pct out of range")
                "天窗打开百分之$pct"
            }
            "sunshade" -> {
                val pct = readInt(params["pct"])
                    ?: return BuildResult.Rejected("sunshade.pct invalid")
                if (pct !in 0..100) return BuildResult.Rejected("sunshade.pct out of range")
                "遮阳帘打开百分之$pct"
            }
            "hud" -> {
                when (readBool(params["on"])) {
                    true -> "打开HUD"
                    false -> "关闭HUD"
                    null -> return BuildResult.Rejected("hud.on invalid")
                }
            }
            "auto_highbeam" -> {
                when (readBool(params["on"])) {
                    true -> "打开自动远光"
                    false -> "关闭自动远光"
                    null -> return BuildResult.Rejected("auto_highbeam.on invalid")
                }
            }
            "child_lock_left" -> "打开左童锁"
            "tts" -> {
                val text = params["text"]?.toString()?.trim().orEmpty()
                if (text.isEmpty() || text.length > 80) return BuildResult.Rejected("tts.text invalid")
                if (text.contains('[') || text.contains(']')) return BuildResult.Rejected("tts.text invalid")
                "播报$text"
            }
            else -> return BuildResult.Rejected("unknown_type")
        }

        if (DENY_PATTERNS.any { phrase.contains(it) }) {
            return BuildResult.Rejected("deny_list")
        }

        return BuildResult.Ok(phrase)
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
