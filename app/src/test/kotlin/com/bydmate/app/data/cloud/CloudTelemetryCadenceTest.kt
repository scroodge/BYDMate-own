package com.bydmate.app.data.cloud

import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTelemetryCadenceTest {
    private val cadence = CloudTelemetryCadence()

    @Test
    fun `brief P after D stays driving within latch window`() {
        val base = 1_700_000_000_000L
        val driving = snapshot(gear = 4, speedKmh = 40.0)
        assertEquals(
            IternioIntervalPolicy.TelemetryState.DRIVING,
            cadence.effectiveState(driving, base),
        )

        val briefPark = snapshot(gear = 1, speedKmh = 0.0)
        assertEquals(
            IternioIntervalPolicy.TelemetryState.DRIVING,
            cadence.effectiveState(briefPark, base + 60_000L),
        )
    }

    @Test
    fun `parked from cold start stays parked`() {
        val parked = snapshot(gear = 1, speedKmh = 0.0)
        assertEquals(
            IternioIntervalPolicy.TelemetryState.PARKED,
            cadence.effectiveState(parked, 1_700_000_000_000L),
        )
    }

    @Test
    fun `drive latch releases after ten minutes on P`() {
        val base = 1_700_000_000_000L
        cadence.effectiveState(snapshot(gear = 4, speedKmh = 30.0), base)

        val parked = snapshot(gear = 1, speedKmh = 0.0)
        assertEquals(
            IternioIntervalPolicy.TelemetryState.PARKED,
            cadence.effectiveState(parked, base + CloudTelemetryCadence.DRIVE_LATCH_MS + 1_000L),
        )
    }

    private fun snapshot(gear: Int, speedKmh: Double) = VehicleTelemetrySnapshot(
        capturedAtMs = 1_700_000_000_000L,
        deviceTimeIso = "2026-06-05T10:00:00Z",
        diPlusData = DiParsData(
            soc = 80,
            speed = speedKmh.toInt(),
            mileage = null,
            power = 0.0,
            chargeGunState = 1,
            maxBatTemp = null,
            avgBatTemp = null,
            minBatTemp = null,
            chargingStatus = null,
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
        ),
        soc = 80,
        speedKmh = speedKmh,
        powerKw = 0.0,
        batteryTempC = null,
        cabinTempC = null,
        outsideTempC = null,
        batteryVoltageV = null,
        auxVoltageV = null,
        cellVoltageMinV = null,
        cellVoltageMaxV = null,
        cellDeltaV = null,
        odometerKm = null,
        sohPercent = null,
        isCharging = false,
        chargePowerKw = null,
        chargeType = null,
        kwhCharged = null,
        rangeEstKm = null,
        currentTripDistanceKm = null,
        currentTripConsumptionKwh100km = null,
        isParked = gear == 1,
        tirePressFL = null,
        tirePressFR = null,
        tirePressRL = null,
        tirePressRR = null,
        location = null,
    )
}
