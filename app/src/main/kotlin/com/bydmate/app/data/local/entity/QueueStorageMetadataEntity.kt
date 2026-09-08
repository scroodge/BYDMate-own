package com.bydmate.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One persisted accounting row. Retry gates remain in stage-1 settings and are not duplicated. */
@Entity(tableName = "queue_storage_metadata")
data class QueueStorageMetadataEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val totalPayloadBytes: Long = 0,
    val unknownPayloadRows: Int = 0,
    val databaseBytes: Long = 0,
    val walBytes: Long = 0,
    val shmBytes: Long = 0,
    val allocatableBytes: Long = 0,
    val measuredAt: Long = 0,
    val backfillComplete: Boolean = false,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
