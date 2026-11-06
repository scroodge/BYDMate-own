package com.bydmate.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAllowlistTest {

    @Test
    fun acPhrasesUseLiveVerifiedCommands() {
        val enabled = CommandAllowlist.buildPhrase("ac", mapOf("on" to true))
        val disabled = CommandAllowlist.buildPhrase("ac", mapOf("on" to false))

        assertTrue(enabled is CommandAllowlist.BuildResult.Ok)
        assertEquals("自动空调", (enabled as CommandAllowlist.BuildResult.Ok).phrase)
        assertTrue(disabled is CommandAllowlist.BuildResult.Ok)
        assertEquals("关空调", (disabled as CommandAllowlist.BuildResult.Ok).phrase)
    }

    @Test
    fun workingWindowCommandsRemainAvailable() {
        val driver = CommandAllowlist.buildPhrase("window", mapOf("which" to "driver", "pct" to 10))
        val close = CommandAllowlist.buildPhrase("windows_preset", mapOf("preset" to "close"))

        assertEquals("主驾车窗打开百分之10", (driver as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("车窗关闭", (close as CommandAllowlist.BuildResult.Ok).phrase)
    }

    @Test
    fun climateTemperatureAndRecirculationPhrasesUseLiveVerifiedCommands() {
        val up = CommandAllowlist.buildPhrase("ac_temp_step", mapOf("direction" to "up"))
        val down = CommandAllowlist.buildPhrase("ac_temp_step", mapOf("direction" to "down"))
        val internal = CommandAllowlist.buildPhrase("ac_recirculation", mapOf("mode" to "internal"))
        val external = CommandAllowlist.buildPhrase("ac_recirculation", mapOf("mode" to "external"))

        assertEquals("空调升温", (up as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("空调降温", (down as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("内循环", (internal as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("外循环", (external as CommandAllowlist.BuildResult.Ok).phrase)
    }

    @Test
    fun fanSpeedStepsUseLiveVerifiedCommands() {
        val up = CommandAllowlist.buildPhrase("fan_speed_step", mapOf("direction" to "up"))
        val down = CommandAllowlist.buildPhrase("fan_speed_step", mapOf("direction" to "down"))

        assertEquals("升风速", (up as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("降风速", (down as CommandAllowlist.BuildResult.Ok).phrase)
        assertTrue(CommandAllowlist.buildPhrase("fan_speed_step", mapOf("direction" to "3")) is CommandAllowlist.BuildResult.Rejected)
    }

    /**
     * DiParsControlClient prepends 迪加 itself. A phrase that already carries it reaches di+ as
     * 迪加迪加…: di+ answers success:true, does nothing, and brings its NaviActivity to the front
     * (on `way`, 2026-11-03, 0.6.3 fan_speed_step).
     */
    @Test
    fun noAllowlistedPhraseCarriesTheDiPlusPrefix() {
        val commands = listOf(
            "window" to mapOf("which" to "driver", "pct" to 50),
            "windows_preset" to mapOf("preset" to "vent"),
            "ac" to mapOf("on" to true),
            "ac" to mapOf("on" to false),
            "ac_temp_step" to mapOf("direction" to "up"),
            "ac_temp_step" to mapOf("direction" to "down"),
            "fan_speed_step" to mapOf("direction" to "up"),
            "fan_speed_step" to mapOf("direction" to "down"),
            "ac_recirculation" to mapOf("mode" to "internal"),
            "ac_recirculation" to mapOf("mode" to "external"),
            "seat_heat_driver" to mapOf("level" to 0),
            "seat_heat_driver" to mapOf("level" to 2),
        )
        for ((type, params) in commands) {
            val built = CommandAllowlist.buildPhrase(type, params)
            assertTrue("$type$params must build", built is CommandAllowlist.BuildResult.Ok)
            val phrase = (built as CommandAllowlist.BuildResult.Ok).phrase
            assertTrue("$type$params → '$phrase'", !phrase.startsWith("迪加"))
        }
    }

    @Test
    fun driverSeatHeatOnlyAcceptsOffOrVerifiedMaximum() {
        val maximum = CommandAllowlist.buildPhrase("seat_heat_driver", mapOf("level" to 2))
        val off = CommandAllowlist.buildPhrase("seat_heat_driver", mapOf("level" to 0))

        assertEquals("主驾座椅加热2档", (maximum as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("关闭主驾座椅加热", (off as CommandAllowlist.BuildResult.Ok).phrase)

        val unverifiedLevel = CommandAllowlist.buildPhrase("seat_heat_driver", mapOf("level" to 1))
        assertTrue(unverifiedLevel is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun passengerSeatHeatOnlyAcceptsOffOrVerifiedMaximum() {
        val maximum = CommandAllowlist.buildPhrase("seat_heat_pass", mapOf("level" to 2))
        val off = CommandAllowlist.buildPhrase("seat_heat_pass", mapOf("level" to 0))

        assertEquals("副驾座椅加热2档", (maximum as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("关闭副驾座椅加热", (off as CommandAllowlist.BuildResult.Ok).phrase)

        val unverifiedLevel = CommandAllowlist.buildPhrase("seat_heat_pass", mapOf("level" to 1))
        assertTrue(unverifiedLevel is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun mapsDaytimeRunningLightsToVerifiedPhrases() {
        val on = CommandAllowlist.buildPhrase("drl", mapOf("on" to true))
        val off = CommandAllowlist.buildPhrase("drl", mapOf("on" to false))
        assertEquals("打开日行灯", (on as CommandAllowlist.BuildResult.Ok).phrase)
        assertEquals("关闭日行灯", (off as CommandAllowlist.BuildResult.Ok).phrase)

        assertTrue(CommandAllowlist.buildPhrase("drl", emptyMap()) is CommandAllowlist.BuildResult.Rejected)
        assertTrue(CommandAllowlist.buildPhrase("drl", mapOf("on" to "maybe")) is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun rejectsUnverifiedCommandTypes() {
        val result = CommandAllowlist.buildPhrase("lock", emptyMap())
        assertTrue(result is CommandAllowlist.BuildResult.Rejected)
        assertEquals("unknown_type", (result as CommandAllowlist.BuildResult.Rejected).reason)

        assertTrue(CommandAllowlist.buildPhrase("fan_level", mapOf("level" to 3)) is CommandAllowlist.BuildResult.Rejected)
        assertTrue(CommandAllowlist.buildPhrase("ac_temp", mapOf("celsius" to 24)) is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun rejectsInvalidVerifiedCommandParameters() {
        val result = CommandAllowlist.buildPhrase("ac_temp_step", mapOf("direction" to "25"))
        assertTrue(result is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun rejectsInvalidSeatHeatLevel() {
        val result = CommandAllowlist.buildPhrase("seat_heat_driver", mapOf("level" to 1))
        assertTrue(result is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun legacyAcVentIsNotAvailable() {
        val result = CommandAllowlist.buildPhrase("ac_vent", mapOf("on" to true))
        assertTrue(result is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun movementGuardBlocksDriving() {
        val data = sampleDiPars(speed = 10, gear = 4)
        assertEquals("vehicle_moving", CommandAllowlist.movementBlockReason("window", data))
    }

    @Test
    fun movementGuardAllowsClimateWhileDriving() {
        val data = sampleDiPars(speed = 60, gear = 4)
        for (type in listOf("ac", "ac_temp_step", "fan_speed_step", "ac_recirculation")) {
            assertEquals(null, CommandAllowlist.movementBlockReason(type, data))
        }
    }

    @Test
    fun movementGuardStillBlocksSeatHeatWhileDriving() {
        val data = sampleDiPars(speed = 60, gear = 4)
        assertEquals("vehicle_moving", CommandAllowlist.movementBlockReason("seat_heat_driver", data))
        assertEquals("vehicle_moving", CommandAllowlist.movementBlockReason("seat_heat_pass", data))
    }

    @Test
    fun movementGuardAllowsChargingWhenNotInPark() {
        val data = sampleDiPars(speed = 0, gear = 4, chargeGunState = 2)
        assertEquals(null, CommandAllowlist.movementBlockReason("window", data))
    }

    private fun sampleDiPars(
        speed: Int?,
        gear: Int?,
        chargeGunState: Int? = null,
        chargingStatus: Int? = null,
    ) = DiParsData(
        soc = 50,
        speed = speed,
        mileage = null,
        power = null,
        chargeGunState = chargeGunState,
        maxBatTemp = null,
        avgBatTemp = null,
        minBatTemp = null,
        chargingStatus = chargingStatus,
        batteryCapacityKwh = null,
        totalElecConsumption = null,
        voltage12v = null,
        maxCellVoltage = null,
        minCellVoltage = null,
        exteriorTemp = null,
        gear = gear,
        powerState = null,
        insideTemp = null,
        acStatus = null,
        acTemp = null,
        fanLevel = null,
        acCirc = null,
        doorFL = null,
        doorFR = null,
        doorRL = null,
        doorRR = null,
        windowFL = null,
        windowFR = null,
        windowRL = null,
        windowRR = null,
        sunroof = null,
        trunk = null,
        hood = null,
        seatbeltFL = null,
        lockFL = null,
        tirePressFL = null,
        tirePressFR = null,
        tirePressRL = null,
        tirePressRR = null,
        driveMode = null,
        workMode = null,
        autoPark = null,
        rain = null,
        lightLow = null,
        drl = null,
        sunshade = null,
        sentryState = null,
        remoteLockState = null,
        stallSentryMode = null,
        powerStateLabel = null,
    )
}
