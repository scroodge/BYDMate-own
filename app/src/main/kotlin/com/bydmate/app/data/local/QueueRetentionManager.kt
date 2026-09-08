package com.bydmate.app.data.local

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueRetentionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val queueDao: CloudSyncQueueDao,
) {
    internal var filesystemCapacityProvider: () -> Long = {
        runCatching { StatFs(context.dataDir.absolutePath).totalBytes }.getOrDefault(0L)
    }
    internal var allocatableBytesProvider: () -> Long = {
        runCatching {
            context.getSystemService(StorageManager::class.java)
                ?.getAllocatableBytes(StorageManager.UUID_DEFAULT) ?: 0L
        }.getOrDefault(0L)
    }
    suspend fun updateRemoteCap(remoteCapBytes: Long?) {
        if (remoteCapBytes == null) return
        settingsRepository.setString(
            SettingsRepository.KEY_CLOUD_SYNC_OFFLINE_BUFFER_CAP_BYTES,
            remoteCapBytes.coerceIn(0L, OfflineBufferPolicy.MAX_CAP_BYTES).toString(),
        )
    }

    suspend fun enforce(now: Long) {
        val remoteCap = settingsRepository.getString(
            SettingsRepository.KEY_CLOUD_SYNC_OFFLINE_BUFFER_CAP_BYTES,
            "0",
        ).toLongOrNull()?.takeIf { it > 0L }
        if (remoteCap == null) {
            queueDao.pruneToMaxRows(OfflineBufferPolicy.DEFAULT_MAX_ROWS)
            return
        }

        queueDao.deleteAcknowledgedBefore(now - ACKNOWLEDGED_GRACE_MS)
        queueDao.deleteQuarantinedBefore(now - QUARANTINE_MAX_AGE_MS)
        while (queueDao.quarantinedPayloadBytes() > QUARANTINE_MAX_BYTES) {
            queueDao.deleteOldestQuarantined(QUARANTINE_PRUNE_BATCH_ROWS)
        }

        val capacity = OfflineBufferPolicy.capacity(
            dataFilesystemCapacityBytes = filesystemCapacityProvider(),
            allocatableBytes = allocatableBytesProvider(),
            remoteCapBytes = remoteCap,
        )
        val newest = queueDao.newestUnsentCapturedAt() ?: return
        val protectedCutoff = newest - OfflineBufferPolicy.PROTECTED_NEWEST_MS
        val ageCutoff = now - OfflineBufferPolicy.MAX_AGE_MS
        var allocated = allocatedQueueBytes()
        var oldest = queueDao.oldestUnsentCapturedAt()
        val pressure = !capacity.reserveAvailable || allocated > capacity.effectiveCapBytes ||
            (oldest != null && oldest < ageCutoff)
        if (!pressure) return

        // Bounded work per capture tick: persistence stays responsive while repeated ticks make
        // forward progress through a large offline queue.
        for (tier in 1..MAX_COMPACTION_TIER) {
            val candidates = queueDao.getCompactionCandidates(
                cutoff = protectedCutoff,
                targetTier = tier,
                limit = COMPACTION_BATCH_ROWS,
            )
            if (candidates.isEmpty()) continue
            val plan = QueueCompactionPlanner.compact(candidates, tier)
            if (plan.keepIds.isNotEmpty()) queueDao.markCompacted(plan.keepIds.toList(), tier)
            if (plan.deleteIds.isNotEmpty()) {
                queueDao.deleteByIds(plan.deleteIds.toList())
                val detail = plan.discardedByState.entries.joinToString { (state, stats) ->
                    "$state=${stats.rows}/${stats.payloadBytes}B"
                }
                Log.w(TAG, "offline queue compacted tier=$tier $detail")
            }
            allocated = allocatedQueueBytes()
            oldest = queueDao.oldestUnsentCapturedAt()
            if (capacity.reserveAvailable && allocated <= capacity.effectiveCapBytes &&
                (oldest == null || oldest >= ageCutoff)
            ) return
        }

        // Only rows already compacted are eligible for lossy eviction. The cutoff makes the
        // newest 72 hours—including the reconnect edge and newest row—untouchable.
        repeat(MAX_EVICTION_BUCKETS_PER_PASS) {
            val compacted = queueDao.getOldestCompacted(protectedCutoff, COMPACTION_BATCH_ROWS)
            val evict = QueueCompactionPlanner.oldestEvictionBucket(compacted, protectedCutoff)
            if (evict.isEmpty()) return
            val evictedRows = compacted.filter { it.id in evict }
            queueDao.deleteByIds(evict.toList())
            val detail = evictedRows.groupBy(QueueCompactionPlanner::stateOf)
                .entries.joinToString { (state, rows) ->
                    "$state=${rows.size}/${rows.sumOf { it.payloadBytes }}B"
                }
            val diagnostic = "offline telemetry lost: evicted oldest compacted bucket ($detail)"
            Log.e(TAG, diagnostic)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_BUFFER_DIAGNOSTIC, diagnostic)
            allocated = allocatedQueueBytes()
            oldest = queueDao.oldestUnsentCapturedAt()
            if (capacity.reserveAvailable && allocated <= capacity.effectiveCapBytes &&
                (oldest == null || oldest >= ageCutoff)
            ) return
        }
    }

    private suspend fun allocatedQueueBytes(): Long = queueDao.unsentPayloadBytes()

    companion object {
        internal const val MAX_COMPACTION_TIER = 3
        internal const val COMPACTION_BATCH_ROWS = 500
        internal const val MAX_EVICTION_BUCKETS_PER_PASS = 8
        internal const val ACKNOWLEDGED_GRACE_MS = 60L * 60L * 1_000L
        internal const val QUARANTINE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
        internal const val QUARANTINE_MAX_BYTES = 16L * OfflineBufferPolicy.MIB
        internal const val QUARANTINE_PRUNE_BATCH_ROWS = 100
        private const val TAG = "QueueRetention"
    }
}
