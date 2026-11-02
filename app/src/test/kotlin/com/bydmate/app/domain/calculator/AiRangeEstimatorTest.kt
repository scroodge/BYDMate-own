package com.bydmate.app.domain.calculator

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.domain.calculator.AiRangeEstimator.Estimate
import com.bydmate.app.domain.calculator.AiRangeEstimator.Inputs
import com.bydmate.app.domain.calculator.AiRangeEstimator.TripInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden values come from running the web's own
 * EvAcChargeTimer/src/lib/voltflowmate/range-estimate.ts (`estimateVehicleRangeKm`)
 * on identical inputs. If the web formula changes, regenerate them there — a
 * mismatch here means the widget and voltflow.life now disagree.
 */
class AiRangeEstimatorTest {

    private fun assertEstimate(expectedRange: Double, expectedConsumption: Double, actual: Estimate) {
        assertEquals(expectedRange, actual.rangeKm!!, 1e-6)
        assertEquals(expectedConsumption, actual.consumptionKwh100km!!, 1e-6)
    }

    @Test fun `web fixture - current trip plus one trip`() {
        val estimate = AiRangeEstimator.estimate(
            Inputs(soc = 100.0, speedKmh = 0.0, currentTripConsumptionKwh100km = 14.3702, currentTripDistanceKm = 12.0),
            listOf(TripInput(avgConsumptionKwh100km = 14.3702, distanceKm = 50.0)),
            batteryCapacityKwh = 45.1,
        )
        assertEstimate(313.84392701562956, 14.3702, estimate)
    }

    @Test fun `web fixture - car profile capacity`() {
        val estimate = AiRangeEstimator.estimate(
            Inputs(soc = 82.0, speedKmh = 0.0),
            listOf(TripInput(avgConsumptionKwh100km = 16.87, distanceKm = 26.9)),
            batteryCapacityKwh = 45.0,
        )
        assertEstimate(218.73147599288677, 16.87, estimate)
    }

    @Test fun `no trips falls back to the 18_5 default`() {
        val estimate = AiRangeEstimator.estimate(Inputs(soc = 50.0), emptyList(), batteryCapacityKwh = 60.48)
        assertEstimate(163.45945945945945, 18.5, estimate)
    }

    @Test fun `cold highway with climate and soft tires`() {
        val estimate = AiRangeEstimator.estimate(
            Inputs(
                soc = 63.4, sohPercent = 96.0, speedKmh = 104.0, powerKw = 21.5,
                outsideTempC = -5.0, batteryTempC = 9.0, acOn = true,
                tirePressuresKpa = listOf(210, 212, 215, 214),
                currentTripConsumptionKwh100km = 19.2, currentTripDistanceKm = 30.0,
            ),
            listOf(TripInput(avgConsumptionKwh100km = 17.1, distanceKm = 22.0, netConsumptionKwh100km = 3.8 / 22.0 * 100.0)),
            batteryCapacityKwh = 60.48,
        )
        assertEstimate(138.21288065511283, 26.63322479462285, estimate)
    }

    @Test fun `slow city with instant power term`() {
        val estimate = AiRangeEstimator.estimate(
            Inputs(
                soc = 30.0, speedKmh = 20.0, powerKw = 3.0, outsideTempC = 20.0,
                currentTripConsumptionKwh100km = 13.0, currentTripDistanceKm = 2.0,
            ),
            listOf(TripInput(avgConsumptionKwh100km = 15.0, distanceKm = 8.0, netConsumptionKwh100km = 15.0)),
            batteryCapacityKwh = 45.0,
        )
        assertEstimate(91.24137931034484, 14.795918367346937, estimate)
    }

    @Test fun `missing or implausible capacity omits the estimate`() {
        assertEquals(Estimate.NONE, AiRangeEstimator.estimate(Inputs(soc = 100.0), emptyList(), null))
        assertEquals(Estimate.NONE, AiRangeEstimator.estimate(Inputs(soc = 100.0), emptyList(), 500.0))
    }

    @Test fun `missing SOC omits the estimate`() {
        assertEquals(Estimate.NONE, AiRangeEstimator.estimate(Inputs(soc = null), emptyList(), 60.0))
    }

    @Test fun `environment factor is clamped to 1_45`() {
        val factor = AiRangeEstimator.environmentFactor(
            Inputs(
                soc = 50.0, speedKmh = 130.0, outsideTempC = -20.0, batteryTempC = 0.0, acOn = true,
                tirePressuresKpa = listOf(200, 200, 200, 200),
            ),
        )
        assertEquals(1.45, factor, 1e-9)
    }

    @Test fun `local trip maps kwh_consumed to net and live source to well-sampled`() {
        val live = TripInput.from(
            TripEntity(startTs = 0L, distanceKm = 20.0, kwhConsumed = 3.4, kwhPer100km = 17.0, source = "live"),
        )
        assertEquals(17.0, live.netConsumptionKwh100km!!, 1e-9)
        assertTrue(live.wellSampled)

        val imported = TripInput.from(
            TripEntity(startTs = 0L, distanceKm = null, kwhConsumed = 3.4, kwhPer100km = 17.0, source = "energydata"),
        )
        assertNull(imported.netConsumptionKwh100km)
        assertFalse(imported.wellSampled)
    }

    // --- Mirrors of range-estimate.test.mjs "reserve" cases ---

    private val reserveTrips = listOf(TripInput(avgConsumptionKwh100km = 15.0, distanceKm = 50.0))

    @Test fun `web - reserve SOC scales the estimate by the usable SOC window`() {
        val inputs = Inputs(soc = 100.0, speedKmh = 0.0)
        val full = AiRangeEstimator.estimate(inputs, reserveTrips, 45.0)
        val reserved = AiRangeEstimator.estimate(inputs, reserveTrips, 45.0, reserveSocPercent = 20.0)
        assertEquals(full.rangeKm!! * 0.8, reserved.rangeKm!!, 1e-9)
    }

    @Test fun `web - reserve at or above current SOC yields zero range, never negative`() {
        val estimate = AiRangeEstimator.estimate(Inputs(soc = 12.0, speedKmh = 0.0), reserveTrips, 45.0, 20.0)
        assertEquals(0.0, estimate.rangeKm!!, 0.0)
    }

    @Test fun `web - missing or invalid reserve keeps the drain-to-empty estimate`() {
        val inputs = Inputs(soc = 60.0, speedKmh = 0.0)
        val baseline = AiRangeEstimator.estimate(inputs, reserveTrips, 45.0)
        assertEquals(baseline, AiRangeEstimator.estimate(inputs, reserveTrips, 45.0, null))
        assertEquals(baseline, AiRangeEstimator.estimate(inputs, reserveTrips, 45.0, Double.NaN))
    }

    // --- Mirror of hero-drive-metrics.test.mjs selectTripsWithinDistanceWindow ---

    @Test fun `web - window stops once covered and keeps a distance-less trip along the way`() {
        val t3 = TripInput(avgConsumptionKwh100km = 16.0, distanceKm = 20.0)
        val junk = TripInput(avgConsumptionKwh100km = 16.0, distanceKm = 0.0)
        val t2 = TripInput(avgConsumptionKwh100km = 16.0, distanceKm = 35.0)
        val t1 = TripInput(avgConsumptionKwh100km = 16.0, distanceKm = 100.0)
        val selected = AiRangeEstimator.selectTripsWithinDistanceWindow(listOf(t3, junk, t2, t1), 50.0)
        assertEquals(listOf(t3, junk, t2), selected)
    }

    // --- Cloud trip block → the same fields the web reads from bydmate_trips ---

    @Test fun `cloud trip block maps to the web trip row`() {
        val trip = TripInput.fromCloudTripJson(
            org.json.JSONObject(
                """{"sample_count":120,"distance_km":20.0,"avg_consumption_kwh_100km":17.0,""" +
                    """"traction_energy_kwh":4.0,"regen_energy_kwh":0.6}""",
            ),
        )
        assertEquals(17.0, trip.avgConsumptionKwh100km!!, 1e-9)
        assertEquals(20.0, trip.distanceKm!!, 1e-9)
        // trip-metrics.ts tripNetConsumptionKwh100: (4.0 − 0.6) / 20 × 100
        assertEquals(17.0, trip.netConsumptionKwh100km!!, 1e-9)
        assertTrue(trip.wellSampled)
    }

    @Test fun `cloud trip block - traction fallback, implausible distance, thin samples`() {
        val noTraction = TripInput.fromCloudTripJson(
            org.json.JSONObject("""{"sample_count":2,"distance_km":10.0,"avg_consumption_kwh_100km":15.0,"regen_energy_kwh":0.5}"""),
        )
        // traction falls back to 10 × 15 / 100 = 1.5 kWh → (1.5 − 0.5) / 10 × 100
        assertEquals(10.0, noTraction.netConsumptionKwh100km!!, 1e-9)
        assertFalse(noTraction.wellSampled)

        val odometerScale = TripInput.fromCloudTripJson(
            org.json.JSONObject("""{"sample_count":50,"distance_km":48210.0,"avg_consumption_kwh_100km":15.0}"""),
        )
        assertNull(odometerScale.distanceKm)
    }
}
