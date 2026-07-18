package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Client-side mirror of one `bydmate_trips` row, accumulated on-device so the ingest RPC does not
 * have to run its own open/extend/trip-meter-baseline arithmetic on every driving sample. Distinct
 * from [com.bydmate.app.data.local.entity.TripEntity] (BYD `energydata`-imported trip history) and
 * from the [TripSummaryCloudSync-fed][com.bydmate.app.data.cloud.TripSummaryCloudSync] endpoint —
 * this is the live-telemetry cloud trip, named `TripRollup*` (not `TripSummary*`) to avoid clashing
 * with that unrelated feature.
 *
 * Holds **cumulative** values for the whole trip, not a delta since the last flush, for the same
 * retry-safety reason as [HourlyRollupEntity]: the block is re-sent in full on every flush and the
 * (future) server RPC replaces its row only when [sampleCount] is at least what it already has.
 *
 * [distanceBaselineKm] / [consumptionBaselineKwh] are the vehicle's real odometer and lifetime
 * consumption readings captured at trip-open — monotonic counters, unlike BYD's own internal trip
 * meter (which the server's current logic has to guard against resetting mid-drive). Distance and
 * average consumption are derived from these baselines plus the latest reading at serialization,
 * not stored as running values here.
 */
@Entity(tableName = "cloud_trip_rollup", primaryKeys = ["tripId"])
data class TripRollupEntity(
    val tripId: String,
    val vehicleId: String,
    /** Epoch millis of the confirmed IDLE->DRIVING transition that opened this trip. */
    val startedAt: Long,
    /** Epoch millis of the most recently folded sample. Drives the next-boot staleness check. */
    val lastDeviceTime: Long,
    /** Null while the trip is open. */
    val endedAt: Long? = null,
    val sampleCount: Int = 0,
    val distanceBaselineKm: Double? = null,
    val consumptionBaselineKwh: Double? = null,
    val lastOdometerKm: Double? = null,
    val lastTotalElecConsumptionKwh: Double? = null,
    val socStart: Double? = null,
    val socEnd: Double? = null,
    val maxSpeedKmh: Double? = null,
    val speedSum: Double = 0.0,
    val speedSampleCount: Int = 0,
    val regenKwhSum: Double = 0.0,
    val tractionKwhSum: Double = 0.0,
    /** Set whenever a sample folds into this trip, or it closes; cleared once the server acks it. */
    @ColumnInfo(index = true)
    val dirty: Boolean = true,
    val updatedAt: Long = 0L,
)
