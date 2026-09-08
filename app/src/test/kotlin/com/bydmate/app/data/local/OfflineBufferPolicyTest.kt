package com.bydmate.app.data.local

import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineBufferPolicyTest {
    @Test
    fun `low free space never force allocates minimum through the reserve`() {
        val capacity = OfflineBufferPolicy.capacity(
            dataFilesystemCapacityBytes = 100L * OfflineBufferPolicy.GIB,
            allocatableBytes = 9L * OfflineBufferPolicy.GIB,
            remoteCapBytes = OfflineBufferPolicy.MAX_CAP_BYTES,
        )

        assertEquals(10L * OfflineBufferPolicy.GIB, capacity.reserveBytes)
        assertFalse(capacity.reserveAvailable)
        assertEquals(0L, capacity.dynamicCapBytes)
        assertEquals(0L, capacity.effectiveCapBytes)
    }

    @Test
    fun `remote cohort ceiling ramps below safe dynamic cap`() {
        val capacity = OfflineBufferPolicy.capacity(
            dataFilesystemCapacityBytes = 100L * OfflineBufferPolicy.GIB,
            allocatableBytes = 60L * OfflineBufferPolicy.GIB,
            remoteCapBytes = 64L * OfflineBufferPolicy.MIB,
        )

        assertTrue(capacity.reserveAvailable)
        assertEquals(4L * OfflineBufferPolicy.GIB, capacity.dynamicCapBytes)
        assertEquals(64L * OfflineBufferPolicy.MIB, capacity.effectiveCapBytes)
    }

    @Test
    fun `eviction selects oldest compacted bucket and never newest reconnect edge`() {
        val newest = 10L * OfflineBufferPolicy.PROTECTED_NEWEST_MS
        val cutoff = newest - OfflineBufferPolicy.PROTECTED_NEWEST_MS
        val oldBucket = listOf(
            row(1, cutoff - 600_000L, tier = 3),
            row(2, cutoff - 590_000L, tier = 3),
        )
        val newerCompacted = row(3, cutoff + 1L, tier = 3)
        val newestRow = row(4, newest, tier = 0)

        val evicted = QueueCompactionPlanner.oldestEvictionBucket(
            oldBucket + newerCompacted + newestRow,
            protectedCutoff = cutoff,
        )

        assertEquals(setOf(1L, 2L), evicted)
        assertFalse(3L in evicted)
        assertFalse(4L in evicted)
    }

    @Test
    fun `compaction preserves newest row and state boundaries`() {
        val rows = listOf(
            row(1, 0L, parked = true),
            row(2, 1_000L, parked = true),
            row(3, 2_000L, charging = true),
            row(4, 3_000L, charging = true),
        )

        val plan = QueueCompactionPlanner.compact(rows, tier = 3)

        assertTrue(1L in plan.keepIds)
        assertTrue(2L in plan.keepIds)
        assertTrue(3L in plan.keepIds)
        assertTrue(4L in plan.keepIds)
        assertFalse(4L in plan.deleteIds)
    }

    private fun row(
        id: Long,
        capturedAt: Long,
        tier: Int = 0,
        parked: Boolean = true,
        charging: Boolean = false,
    ): CloudSyncQueueEntity {
        val payload = JSONObject()
            .put("device_time", "2026-09-03T00:00:00Z")
            .put(
                "telemetry",
                JSONObject()
                    .put("is_parked", parked)
                    .put("is_charging", charging)
                    .put("soc", 50),
            )
            .toString()
        return CloudSyncQueueEntity(
            id = id,
            createdAt = capturedAt,
            capturedAt = capturedAt,
            payloadJson = payload,
            compactionTier = tier,
        )
    }
}
