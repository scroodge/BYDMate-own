package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity

@Dao
interface CloudSyncQueueDao {
    @Insert
    suspend fun insert(entity: CloudSyncQueueEntity): Long

    /** Duplicate daemon imports are successful no-ops after a commit/delete crash. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDaemonIfAbsent(entity: CloudSyncQueueEntity): Long

    @Query("SELECT * FROM cloud_sync_queue WHERE sentAt IS NULL ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getUnsent(limit: Int): List<CloudSyncQueueEntity>

    @Query("SELECT COUNT(*) FROM cloud_sync_queue WHERE sentAt IS NULL")
    suspend fun countUnsent(): Int

    @Query("UPDATE cloud_sync_queue SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markAttempt(id: Long, error: String)

    @Query("UPDATE cloud_sync_queue SET attempts = attempts + 1, lastError = :error, sentAt = :sentAt WHERE id = :id")
    suspend fun markFinished(id: Long, error: String?, sentAt: Long)

    @Query("DELETE FROM cloud_sync_queue WHERE id NOT IN (SELECT id FROM cloud_sync_queue ORDER BY createdAt DESC LIMIT :maxRows)")
    suspend fun pruneToMaxRows(maxRows: Int)

    @Query("SELECT COALESCE(SUM(payloadBytes),0) FROM cloud_sync_queue WHERE sentAt IS NULL")
    suspend fun unsentPayloadBytes(): Long

    @Query("SELECT MIN(capturedAt) FROM cloud_sync_queue WHERE sentAt IS NULL")
    suspend fun oldestUnsentCapturedAt(): Long?

    @Query("SELECT MAX(capturedAt) FROM cloud_sync_queue WHERE sentAt IS NULL")
    suspend fun newestUnsentCapturedAt(): Long?

    @Query(
        "SELECT * FROM cloud_sync_queue WHERE sentAt IS NULL AND capturedAt < :cutoff " +
            "AND compactionTier < :targetTier ORDER BY capturedAt ASC, id ASC LIMIT :limit"
    )
    suspend fun getCompactionCandidates(cutoff: Long, targetTier: Int, limit: Int): List<CloudSyncQueueEntity>

    @Query(
        "SELECT * FROM cloud_sync_queue WHERE sentAt IS NULL AND compactionTier > 0 " +
            "AND capturedAt < :cutoff ORDER BY capturedAt ASC, id ASC LIMIT :limit"
    )
    suspend fun getOldestCompacted(cutoff: Long, limit: Int): List<CloudSyncQueueEntity>

    @Query("UPDATE cloud_sync_queue SET compactionTier = :tier WHERE id IN (:ids)")
    suspend fun markCompacted(ids: List<Long>, tier: Int)

    @Query("DELETE FROM cloud_sync_queue WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM cloud_sync_queue WHERE sentAt IS NOT NULL AND lastError IS NULL AND sentAt < :cutoff")
    suspend fun deleteAcknowledgedBefore(cutoff: Long)

    @Query("DELETE FROM cloud_sync_queue WHERE sentAt IS NOT NULL AND lastError IS NOT NULL AND sentAt < :cutoff")
    suspend fun deleteQuarantinedBefore(cutoff: Long)

    @Query("SELECT COALESCE(SUM(payloadBytes),0) FROM cloud_sync_queue WHERE sentAt IS NOT NULL AND lastError IS NOT NULL")
    suspend fun quarantinedPayloadBytes(): Long

    @Query(
        "DELETE FROM cloud_sync_queue WHERE id IN (SELECT id FROM cloud_sync_queue " +
            "WHERE sentAt IS NOT NULL AND lastError IS NOT NULL ORDER BY sentAt ASC, id ASC LIMIT :limit)"
    )
    suspend fun deleteOldestQuarantined(limit: Int)
}
