package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_sync_queue")
data class CloudSyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Long,
    val payloadJson: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    @ColumnInfo(index = true)
    val sentAt: Long? = null,
)
