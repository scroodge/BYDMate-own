package com.bydmate.app.data.remote

object CommandAllowlist {
    private val DENY_PATTERNS = listOf(
        "发送CAN", "执行SHELL", "执行TSHELL",
        "点击", "滑动", "按钮", "按键", "浮窗", "下电",
    )

    private const val GEAR_PARK = 1
    private const val MIN_AUX_VOLTAGE_V = 11.8
    private val CHARGING_GUN_STATES = setOf(2, 3, 4, 5)
    private val WINDOW_TARGETS = mapOf(
        "driver" to "主驾车窗",
        "pass" to "副驾车窗",
        "rl" to "左后车窗",
        "rr" to "右后车窗",
        "all" to "全部车窗",
    )
    private val WINDOW_PRESET_PHRASES = mapOf(
        "vent" to "车窗通风",
        "close" to "车窗关闭",
        "open" to "车窗全开",
        "half" to "车窗半开",
    )

    sealed class BuildResult {
        data class Ok(val phrase: String) : BuildResult()
        data class Rejected(val reason: String) : BuildResult()
    }

    /**
     * Climate commands only change cabin comfort and the driver can override them from the
     * screen, so they are allowed while driving. Windows and seat heat stay parked-only.
     */
    private val MOVEMENT_EXEMPT_TYPES = setOf(
        "ac", "ac_temp_step", "fan_speed_step", "ac_recirculation",
    )

    fun movementBlockReason(type: String, data: DiParsData?): String? {
        if (type in MOVEMENT_EXEMPT_TYPES) return null
        if (data == null) return null
        val speed = data.speed
        if (speed != null && speed != 0) return "vehicle_moving"
        if (data.gear == GEAR_PARK) return null
        if (isStationaryCharging(data)) return null
        val gear = data.gear ?: return "gear_unknown"
        if (gear != GEAR_PARK) return "gear_not_park"
        return null
    }

    private fun isStationaryCharging(data: DiParsData): Boolean {
        if (data.speed != null && data.speed != 0) return false
        if (data.chargeGunState in CHARGING_GUN_STATES) return true
        val status = data.chargingStatus
        return status != null && status > 0
    }

    fun auxVoltageBlockReason(data: DiParsData?): String? {
        val v = data?.voltage12v ?: return null
        if (v > 0 && v < MIN_AUX_VOLTAGE_V) return "aux_voltage_low"
        return null
    }

    fun buildPhrase(type: String, params: Map<String, Any?>): BuildResult {
        val phrase = when (type) {
            "window" -> {
                val target = WINDOW_TARGETS[params["which"]?.toString()?.trim()]
                    ?: return BuildResult.Rejected("window.which invalid")
                val pct = readInt(params["pct"])
                if (pct == null || pct !in 0..100) {
                    return BuildResult.Rejected("window.pct must be 0-100")
                }
                "${target}打开百分之${pct}"
            }
            "windows_preset" -> WINDOW_PRESET_PHRASES[params["preset"]?.toString()?.trim()]
                ?: return BuildResult.Rejected("windows_preset.preset invalid")
            "ac" -> {
                when (readBool(params["on"])) {
                    true -> "自动空调"
                    false -> "关空调"
                    null -> return BuildResult.Rejected("ac.on invalid")
                }
            }
            "ac_temp_step" -> when (params["direction"]?.toString()?.trim()) {
                "up" -> "空调升温"
                "down" -> "空调降温"
                else -> return BuildResult.Rejected("ac_temp_step.direction invalid")
            }
            "fan_speed_step" -> when (params["direction"]?.toString()?.trim()) {
                "up" -> "升风速"
                "down" -> "降风速"
                else -> return BuildResult.Rejected("fan_speed_step.direction invalid")
            }
            "ac_recirculation" -> when (params["mode"]?.toString()?.trim()) {
                "internal" -> "内循环"
                "external" -> "外循环"
                else -> return BuildResult.Rejected("ac_recirculation.mode invalid")
            }
            "seat_heat_driver" -> when (readInt(params["level"])) {
                0 -> "关闭主驾座椅加热"
                2 -> "主驾座椅加热2档"
                else -> return BuildResult.Rejected("seat_heat_driver.level must be 0 or 2")
            }
            // Verified on `way` 2026-11-04: {日行灯} flips 1 (on) <-> 2 (off).
            "drl" -> when (readBool(params["on"])) {
                true -> "打开日行灯"
                false -> "关闭日行灯"
                null -> return BuildResult.Rejected("drl.on must be boolean")
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
