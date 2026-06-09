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
    private val CHARGING_GUN_STATES = setOf(2, 3, 4, 5)

    sealed class BuildResult {
        data class Ok(val phrase: String) : BuildResult()
        data class Rejected(val reason: String) : BuildResult()
    }

    fun movementBlockReason(data: DiParsData?): String? {
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
            // --- Extended set (keep in sync with command-allowlist.ts + BYD_MA COMMAND_ALLOWLIST.md) ---
            // Confidence apk = present in decompiled D+ (cmd/f.java, service/KDService.java).
            "sentry" -> when (readBool(params["on"])) {            // apk
                true -> "开启哨兵模式"
                false -> "关闭哨兵模式"
                null -> return BuildResult.Rejected("sentry.on invalid")
            }
            "sentry_autostart" -> when (readBool(params["on"])) {  // apk
                true -> "开启自动启动哨兵模式"
                false -> "关闭自动启动哨兵模式"
                null -> return BuildResult.Rejected("sentry_autostart.on invalid")
            }
            "screen_off" -> "屏幕关闭"                              // apk
            "windows_close" -> "一键关窗"                          // apk
            "ac_temp_up" -> "空调升温"                            // apk (KDService)
            "ac_temp_down" -> "空调降温"                          // apk (KDService)
            // Comfort set below — BYD voice vocabulary, UNVERIFIED (confidence guess): live-test then mark.
            "ac_temp" -> {
                val v = readInt(params["value"]) ?: return BuildResult.Rejected("ac_temp.value invalid")
                if (v !in 16..32) return BuildResult.Rejected("ac_temp.value out of range")
                "空调温度${v}度"
            }
            "fan_level" -> {
                val v = readInt(params["value"]) ?: return BuildResult.Rejected("fan_level.value invalid")
                if (v !in 0..7) return BuildResult.Rejected("fan_level.value out of range")
                "风量${v}档"
            }
            "trunk" -> "打开后备箱"
            "defrost" -> when (readBool(params["on"])) {
                true -> "打开除雾"
                false -> "关闭除雾"
                null -> return BuildResult.Rejected("defrost.on invalid")
            }
            "rear_defrost" -> when (readBool(params["on"])) {
                true -> "打开后除霜"
                false -> "关闭后除霜"
                null -> return BuildResult.Rejected("rear_defrost.on invalid")
            }
            "seat_heat_driver" -> when (readBool(params["on"])) {
                true -> "打开主驾座椅加热"
                false -> "关闭主驾座椅加热"
                null -> return BuildResult.Rejected("seat_heat_driver.on invalid")
            }
            "seat_heat_pass" -> when (readBool(params["on"])) {
                true -> "打开副驾座椅加热"
                false -> "关闭副驾座椅加热"
                null -> return BuildResult.Rejected("seat_heat_pass.on invalid")
            }
            "steering_heat" -> when (readBool(params["on"])) {
                true -> "打开方向盘加热"
                false -> "关闭方向盘加热"
                null -> return BuildResult.Rejected("steering_heat.on invalid")
            }
            "mirror_fold" -> when (readBool(params["on"])) {
                true -> "折叠后视镜"
                false -> "展开后视镜"
                null -> return BuildResult.Rejected("mirror_fold.on invalid")
            }
            "find_car" -> "寻车"
            "honk" -> "鸣笛"
            "flash_lights" -> "闪灯"
            "charge_port" -> "打开充电口"
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
