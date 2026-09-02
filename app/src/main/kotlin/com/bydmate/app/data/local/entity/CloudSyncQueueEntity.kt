package com.bydmate.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_sync_queue",
    indices = [
        Index(value = ["sentAt"]),
        Index(value = ["sampleId"], unique = true),
    ],
)
data class CloudSyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Long,
    val payloadJson: String,
    /** Stable only for daemon ingress; null keeps legacy/app rows migration-compatible. */
    val sampleId: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val sentAt: Long? = null,
)
