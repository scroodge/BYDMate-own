package com.bydmate.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAllowlistTest {

    @Test
    fun lockPhrase() {
        val result = CommandAllowlist.buildPhrase("lock", emptyMap())
        assertTrue(result is CommandAllowlist.BuildResult.Ok)
        assertEquals("车门上锁", (result as CommandAllowlist.BuildResult.Ok).phrase)
    }

    @Test
    fun rejectsInvalidSoc() {
        val result = CommandAllowlist.buildPhrase("set_soc_limit", mapOf("value" to 40))
        assertTrue(result is CommandAllowlist.BuildResult.Rejected)
    }

    @Test
    fun windowDriverPhrase() {
        val result = CommandAllowlist.buildPhrase("window", mapOf("which" to "driver", "pct" to 10))
        assertTrue(result is CommandAllowlist.BuildResult.Ok)
        assertEquals("主驾车窗打开百分之10", (result as CommandAllowlist.BuildResult.Ok).phrase)
    }

    @Test
    fun windowsPresetVentPhrase() {
        val result = CommandAllowlist.buildPhrase("windows_preset", mapOf("preset" to "vent"))
        assertTrue(result is CommandAllowlist.BuildResult.Ok)
        assertEquals("车窗通风", (result as CommandAllowlist.BuildResult.Ok).phrase)
    }

    @Test
    fun acVentPhrases() {
        val on = CommandAllowlist.buildPhrase("ac_vent", mapOf("on" to true))
        assertTrue(on is CommandAllowlist.BuildResult.Ok)
        assertEquals("打开空调通风", (on as CommandAllowlist.BuildResult.Ok).phrase)
    }

    @Test
    fun movementGuardBlocksDriving() {
        val data = sampleDiPars(speed = 10, gear = 4)
        assertEquals("vehicle_moving", CommandAllowlist.movementBlockReason(data))
    }

    @Test
    fun movementGuardAllowsChargingWhenNotInPark() {
        val data = sampleDiPars(speed = 0, gear = 4, chargeGunState = 2)
        assertEquals(null, CommandAllowlist.movementBlockReason(data))
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
