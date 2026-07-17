package com.bydmate.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsCorridorFilterTest {
    /** ~1 deg of latitude is ~111.32 km, so this converts a metre offset to degrees. */
    private fun metresToLatDegrees(m: Double) = m / 111_320.0

    @Test
    fun `first point since reset is always kept`() {
        val filter = GpsCorridorFilter()
        assertTrue(filter.shouldKeep(BASE_LAT, BASE_LON, 0L))
    }

    @Test
    fun `straight line points are dropped after the corridor heading is established`() {
        val filter = GpsCorridorFilter()
        // Point 1 sets the anchor, point 2 establishes the heading — both kept.
        assertTrue(filter.shouldKeep(BASE_LAT, BASE_LON, 0L))
        assertTrue(filter.shouldKeep(BASE_LAT + metresToLatDegrees(20.0), BASE_LON, 1_000L))

        // Continuing due north along the same line: nothing new to say, so drop.
        var dropped = 0
        for (i in 2..10) {
            val lat = BASE_LAT + metresToLatDegrees(20.0 * i)
            if (!filter.shouldKeep(lat, BASE_LON, i * 1_000L)) dropped++
        }
        assertEquals(9, dropped)
    }

    @Test
    fun `a turn beyond the corridor tolerance is kept`() {
        val filter = GpsCorridorFilter()
        filter.shouldKeep(BASE_LAT, BASE_LON, 0L)
        filter.shouldKeep(BASE_LAT + metresToLatDegrees(20.0), BASE_LON, 1_000L)

        // Veer east far enough that the cross-track deviation clears the 12m tolerance.
        val lonOffset = metresToLatDegrees(60.0) / Math.cos(Math.toRadians(BASE_LAT))
        assertTrue(
            filter.shouldKeep(BASE_LAT + metresToLatDegrees(40.0), BASE_LON + lonOffset, 2_000L),
        )
    }

    @Test
    fun `a point is kept once the anchor ages out even on a straight line`() {
        val filter = GpsCorridorFilter()
        filter.shouldKeep(BASE_LAT, BASE_LON, 0L)
        filter.shouldKeep(BASE_LAT + metresToLatDegrees(20.0), BASE_LON, 1_000L)
        assertFalse(filter.shouldKeep(BASE_LAT + metresToLatDegrees(40.0), BASE_LON, 2_000L))

        // Same straight line, but past MAX_ANCHOR_AGE_MS since the last kept point.
        val aged = 2_000L + GpsCorridorFilter.MAX_ANCHOR_AGE_MS
        assertTrue(filter.shouldKeep(BASE_LAT + metresToLatDegrees(400.0), BASE_LON, aged))
    }

    @Test
    fun `reset makes the next point an anchor again`() {
        val filter = GpsCorridorFilter()
        filter.shouldKeep(BASE_LAT, BASE_LON, 0L)
        filter.shouldKeep(BASE_LAT + metresToLatDegrees(20.0), BASE_LON, 1_000L)
        assertFalse(filter.shouldKeep(BASE_LAT + metresToLatDegrees(40.0), BASE_LON, 2_000L))

        filter.reset()
        assertTrue(filter.shouldKeep(BASE_LAT + metresToLatDegrees(60.0), BASE_LON, 3_000L))
    }

    private companion object {
        const val BASE_LAT = 53.9023
        const val BASE_LON = 27.5619
    }
}
