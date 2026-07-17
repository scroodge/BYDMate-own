package com.bydmate.app.data.cloud

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming GPS point thinning for the 1 Hz driving telemetry stream. Full telemetry
 * (SOC, power, etc.) is still sent every sample; this only decides whether a sample's
 * *location* is worth sending, to cut trip-track storage and egress on straight-line
 * driving without losing turns or trip shape.
 *
 * Single-pass Reumann-Witkam-style simplification: keeps an anchor point plus the
 * heading to the last-kept point, and drops a new point's location when it falls
 * within [CORRIDOR_TOLERANCE_M] of the line the anchor+heading imply. A point is
 * always kept if it's the first since [reset], if the corridor deviation exceeds
 * tolerance (a turn), or if [MAX_ANCHOR_AGE_MS] has elapsed since the last kept point
 * (bounds gaps on long straight stretches).
 */
class GpsCorridorFilter {
    private var anchorLat: Double? = null
    private var anchorLon: Double? = null
    private var anchorBearingRad: Double? = null
    private var anchorAtMs: Long = 0L

    /** Call on trip start/stop or any driving<->non-driving transition. */
    fun reset() {
        anchorLat = null
        anchorLon = null
        anchorBearingRad = null
        anchorAtMs = 0L
    }

    /** true = keep this point's location in the payload; false = safe to omit. */
    fun shouldKeep(lat: Double, lon: Double, nowMs: Long): Boolean {
        val aLat = anchorLat
        val aLon = anchorLon
        if (aLat == null || aLon == null) {
            setAnchor(lat, lon, bearing = null, nowMs)
            return true
        }
        if (nowMs - anchorAtMs >= MAX_ANCHOR_AGE_MS) {
            setAnchor(lat, lon, bearing = bearingRad(aLat, aLon, lat, lon), nowMs)
            return true
        }
        val bearing = anchorBearingRad
        if (bearing == null) {
            // Second point since the anchor was set: establishes the corridor heading.
            setAnchor(lat, lon, bearing = bearingRad(aLat, aLon, lat, lon), nowMs)
            return true
        }
        val distanceFromAnchorM = haversineMeters(aLat, aLon, lat, lon)
        val keep = distanceFromAnchorM < MIN_ANCHOR_ADVANCE_M ||
            abs(crossTrackDistanceMeters(aLat, aLon, bearing, lat, lon)) > CORRIDOR_TOLERANCE_M
        if (keep) {
            setAnchor(lat, lon, bearing = bearingRad(aLat, aLon, lat, lon), nowMs)
        }
        return keep
    }

    private fun setAnchor(lat: Double, lon: Double, bearing: Double?, nowMs: Long) {
        anchorLat = lat
        anchorLon = lon
        anchorBearingRad = bearing
        anchorAtMs = nowMs
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Perpendicular deviation from the corridor line that forces a keep. */
        const val CORRIDOR_TOLERANCE_M = 12.0

        /** Force a keep after this much time even on a perfectly straight line. */
        const val MAX_ANCHOR_AGE_MS = 30_000L

        /** Below this distance from the anchor, GPS jitter alone can swing bearing/
         * cross-track wildly — always drop rather than false-triggering a keep. */
        private const val MIN_ANCHOR_ADVANCE_M = 2.0

        private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val dPhi = Math.toRadians(lat2 - lat1)
            val dLambda = Math.toRadians(lon2 - lon1)
            val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
            return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        private fun bearingRad(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val dLambda = Math.toRadians(lon2 - lon1)
            val y = sin(dLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
            return atan2(y, x)
        }

        /** Perpendicular distance (m) of (lat,lon) from the great-circle line through
         * (anchorLat, anchorLon) with initial bearing [anchorBearingRad]. */
        private fun crossTrackDistanceMeters(
            anchorLat: Double,
            anchorLon: Double,
            anchorBearingRad: Double,
            lat: Double,
            lon: Double,
        ): Double {
            val distanceToPointM = haversineMeters(anchorLat, anchorLon, lat, lon)
            if (distanceToPointM < 0.01) return 0.0
            val bearingToPointRad = bearingRad(anchorLat, anchorLon, lat, lon)
            val angularDistance = distanceToPointM / EARTH_RADIUS_M
            return asin(sin(angularDistance) * sin(bearingToPointRad - anchorBearingRad)) * EARTH_RADIUS_M
        }
    }
}
