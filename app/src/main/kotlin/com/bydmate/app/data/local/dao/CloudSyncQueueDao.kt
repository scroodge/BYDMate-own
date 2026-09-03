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
}
