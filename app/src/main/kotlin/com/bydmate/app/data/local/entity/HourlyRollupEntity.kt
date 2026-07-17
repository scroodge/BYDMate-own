package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Client-side mirror of one `bydmate_telemetry_hourly` row, accumulated on-device so the
 * ingest RPC does not have to redo the arithmetic on every sample.
 *
 * Holds **cumulative** values for the whole hour, not a delta since the last flush. The block
 * is re-sent in full on every flush and the server replaces its row when [sampleCount] is at
 * least what it already has, which makes a retried flush (or a lost ack) idempotent — an
 * additive delta would double-count the hour in exactly that case.
 *
 * Sums are stored rather than averages: the wire format and the server column are `*_avg`, but
 * carrying the sum and dividing once at serialization avoids the drift of an incrementally
 * updated mean over the ~3600 samples a driving hour produces.
 */
@Entity(tableName = "cloud_hourly_rollup", primaryKeys = ["vehicleId", "hourStart"])
data class HourlyRollupEntity(
    val vehicleId: String,
    /** UTC hour truncated to the hour, epoch millis. Matches the server's `date_trunc('hour', …)`. */
    val hourStart: Long,
    val sampleCount: Int,
    val socMin: Double? = null,
    val socMax: Double? = null,
    val socLast: Double? = null,
    val speedMax: Double? = null,
    val powerSum: Double = 0.0,
    val powerSampleCount: Int = 0,
    val batteryTempSum: Double = 0.0,
    val batteryTempSampleCount: Int = 0,
    val cabinTempSum: Double = 0.0,
    val cabinTempSampleCount: Int = 0,
    val outsideTempSum: Double = 0.0,
    val outsideTempSampleCount: Int = 0,
    val regenKwhSum: Double = 0.0,
    val tractionKwhSum: Double = 0.0,
    /** Set whenever a sample lands in this hour; cleared once the server acks the block. */
    @ColumnInfo(index = true)
    val dirty: Boolean = true,
    val updatedAt: Long = 0L,
)
