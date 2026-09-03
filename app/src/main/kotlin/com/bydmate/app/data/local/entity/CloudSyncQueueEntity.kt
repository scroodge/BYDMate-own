package com.bydmate.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.charset.StandardCharsets

@Entity(
    tableName = "cloud_sync_queue",
    indices = [
        Index(value = ["sentAt"]),
        Index(value = ["sampleId"], unique = true),
        Index(value = ["sentAt", "capturedAt"]),
        Index(value = ["compactionTier", "capturedAt"]),
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
    /** Exact UTF-8 payload size; legacy v17 rows use 0 only until bounded backfill completes. */
    val payloadBytes: Long = payloadJson.toByteArray(StandardCharsets.UTF_8).size.toLong(),
    /** Capture time from payload.device_time, never upload/attempt time. */
    val capturedAt: Long = createdAt,
    /** Queue writer identity used by future retention/compaction policy. */
    val origin: String = ORIGIN_APP,
    /** Zero means an original, uncompacted payload. Stage 3 does not compact or evict. */
    val compactionTier: Int = 0,
) {
    companion object {
        const val ORIGIN_APP = "app"
        const val ORIGIN_DAEMON = "daemon"
    }
}
