package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bydmate.app.data.local.entity.TripRollupEntity

@Dao
interface TripRollupDao {
    @Upsert
    suspend fun upsert(entity: TripRollupEntity)

    @Query("SELECT * FROM cloud_trip_rollup WHERE tripId = :tripId")
    suspend fun find(tripId: String): TripRollupEntity?

    /**
     * The currently-open trip for this vehicle, if any. Used to lazily hydrate in-memory state
     * after a process restart so a resumed mid-drive session extends the same trip instead of
     * forking a new one.
     */
    @Query("SELECT * FROM cloud_trip_rollup WHERE vehicleId = :vehicleId AND endedAt IS NULL LIMIT 1")
    suspend fun findOpen(vehicleId: String): TripRollupEntity?

    /**
     * Blocks still owed to the server. Ordered oldest-first so a closed trip is settled before
     * the trip still in progress.
     */
    @Query("SELECT * FROM cloud_trip_rollup WHERE dirty = 1 ORDER BY startedAt ASC LIMIT :limit")
    suspend fun getDirty(limit: Int): List<TripRollupEntity>

    /**
     * Clear the dirty flag only if no sample has landed since the block was serialized —
     * otherwise the in-flight ack would mask samples the server has not seen yet.
     */
    @Query(
        "UPDATE cloud_trip_rollup SET dirty = 0 " +
            "WHERE tripId = :tripId AND sampleCount = :sampleCount"
    )
    suspend fun markClean(tripId: String, sampleCount: Int)

    /** Settled trips are dead weight once the server has them; keep a short window for debugging. */
    @Query("DELETE FROM cloud_trip_rollup WHERE dirty = 0 AND endedAt IS NOT NULL AND updatedAt < :updatedAt")
    suspend fun pruneCleanBefore(updatedAt: Long)
}
