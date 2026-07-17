package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bydmate.app.data.local.entity.HourlyRollupEntity

@Dao
interface HourlyRollupDao {
    @Upsert
    suspend fun upsert(entity: HourlyRollupEntity)

    @Query("SELECT * FROM cloud_hourly_rollup WHERE vehicleId = :vehicleId AND hourStart = :hourStart")
    suspend fun find(vehicleId: String, hourStart: Long): HourlyRollupEntity?

    /**
     * Blocks still owed to the server. Ordered oldest-first so a closed hour is settled before
     * the hour still filling up.
     */
    @Query("SELECT * FROM cloud_hourly_rollup WHERE dirty = 1 ORDER BY hourStart ASC LIMIT :limit")
    suspend fun getDirty(limit: Int): List<HourlyRollupEntity>

    /**
     * Clear the dirty flag only if no sample has landed since the block was serialized —
     * otherwise the in-flight ack would mask samples the server has not seen yet.
     */
    @Query(
        "UPDATE cloud_hourly_rollup SET dirty = 0 " +
            "WHERE vehicleId = :vehicleId AND hourStart = :hourStart AND sampleCount = :sampleCount"
    )
    suspend fun markClean(vehicleId: String, hourStart: Long, sampleCount: Int)

    /** Settled hours are dead weight once the server has them; keep a short window for debugging. */
    @Query("DELETE FROM cloud_hourly_rollup WHERE dirty = 0 AND hourStart < :hourStart")
    suspend fun pruneCleanBefore(hourStart: Long)
}
