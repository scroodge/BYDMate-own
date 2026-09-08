package com.bydmate.app.data.local

import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class OfflineBufferCapacity(
    val reserveBytes: Long,
    val dynamicCapBytes: Long,
    val effectiveCapBytes: Long,
    val reserveAvailable: Boolean,
)

/** Pure capacity policy shared by production enforcement and low-space unit tests. */
object OfflineBufferPolicy {
    const val GIB = 1024L * 1024L * 1024L
    const val MIB = 1024L * 1024L
    const val MAX_CAP_BYTES = 4L * GIB
    const val MIN_DYNAMIC_CAP_BYTES = 256L * MIB
    const val MIN_RESERVE_BYTES = 5L * GIB
    const val DEFAULT_MAX_ROWS = 1_000
    const val PROTECTED_NEWEST_MS = 72L * 60L * 60L * 1_000L
    const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1_000L

    fun capacity(
        dataFilesystemCapacityBytes: Long,
        allocatableBytes: Long,
        remoteCapBytes: Long,
    ): OfflineBufferCapacity {
        val reserve = max(MIN_RESERVE_BYTES, dataFilesystemCapacityBytes.coerceAtLeast(0L) / 10L)
        if (allocatableBytes <= reserve) {
            // Never manufacture the 256 MiB minimum out of space already owed to the reserve.
            return OfflineBufferCapacity(reserve, 0L, 0L, reserveAvailable = false)
        }
        val dynamic = min(
            MAX_CAP_BYTES,
            max(MIN_DYNAMIC_CAP_BYTES, allocatableBytes - reserve),
        )
        return OfflineBufferCapacity(
            reserveBytes = reserve,
            dynamicCapBytes = dynamic,
            effectiveCapBytes = min(dynamic, remoteCapBytes.coerceIn(1L, MAX_CAP_BYTES)),
            reserveAvailable = true,
        )
    }
}

data class QueueCompactionPlan(
    val keepIds: Set<Long>,
    val deleteIds: Set<Long>,
    val discardedByState: Map<String, QueueDiscardStats> = emptyMap(),
)

data class QueueDiscardStats(val rows: Int, val payloadBytes: Long)

/**
 * Deterministic temporal compaction. Original payloads are retained; no synthetic telemetry is
 * created. State/run edges, extrema and the already corridor-filtered GPS anchors always survive.
 */
object QueueCompactionPlanner {
    fun compact(rows: List<CloudSyncQueueEntity>, tier: Int): QueueCompactionPlan {
        if (rows.size < 3) return QueueCompactionPlan(rows.mapTo(mutableSetOf()) { it.id }, emptySet())
        val sorted = rows.sortedWith(compareBy<CloudSyncQueueEntity> { it.capturedAt }.thenBy { it.id })
        val parsed = sorted.map(::parse)
        val keep = mutableSetOf<Long>()
        keep += sorted.first().id
        keep += sorted.last().id

        parsed.forEachIndexed { index, current ->
            val previous = parsed.getOrNull(index - 1)
            val next = parsed.getOrNull(index + 1)
            if (previous?.signature != current.signature || next?.signature != current.signature) keep += current.row.id
            if (current.hasLocation) keep += current.row.id
        }

        parsed.groupBy { item ->
            val width = bucketWidthMs(item.kind, item.soc, tier)
            Bucket(item.signature, item.row.capturedAt / width)
        }.values.forEach { bucket ->
            keep += bucket.first().row.id
            keep += bucket.last().row.id
            bucket.minByOrNull { it.soc ?: Double.POSITIVE_INFINITY }?.takeIf { it.soc != null }?.let { keep += it.row.id }
            bucket.maxByOrNull { it.soc ?: Double.NEGATIVE_INFINITY }?.takeIf { it.soc != null }?.let { keep += it.row.id }
            bucket.minByOrNull { it.auxVoltage ?: Double.POSITIVE_INFINITY }?.takeIf { it.auxVoltage != null }?.let { keep += it.row.id }
            bucket.maxByOrNull { it.auxVoltage ?: Double.NEGATIVE_INFINITY }?.takeIf { it.auxVoltage != null }?.let { keep += it.row.id }
            bucket.minByOrNull { it.cellMin ?: Double.POSITIVE_INFINITY }?.takeIf { it.cellMin != null }?.let { keep += it.row.id }
            bucket.maxByOrNull { it.cellMax ?: Double.NEGATIVE_INFINITY }?.takeIf { it.cellMax != null }?.let { keep += it.row.id }
        }
        val deleted = parsed.filter { it.row.id !in keep }
        return QueueCompactionPlan(
            keepIds = keep,
            deleteIds = deleted.mapTo(mutableSetOf()) { it.row.id },
            discardedByState = deleted.groupBy { it.kind }.mapValues { (_, values) ->
                QueueDiscardStats(values.size, values.sumOf { it.row.payloadBytes })
            },
        )
    }

    /** Selects only the oldest complete compacted time bucket below the protected cutoff. */
    fun oldestEvictionBucket(
        rows: List<CloudSyncQueueEntity>,
        protectedCutoff: Long,
    ): Set<Long> {
        val eligible = rows
            .filter { it.compactionTier > 0 && it.capturedAt < protectedCutoff }
            .sortedWith(compareBy<CloudSyncQueueEntity> { it.capturedAt }.thenBy { it.id })
        val first = eligible.firstOrNull() ?: return emptySet()
        val firstParsed = parse(first)
        val width = bucketWidthMs(firstParsed.kind, firstParsed.soc, first.compactionTier)
        val bucket = first.capturedAt / width
        return eligible.takeWhile { row ->
            val parsed = parse(row)
            parsed.signature == firstParsed.signature && row.compactionTier == first.compactionTier && row.capturedAt / width == bucket
        }.mapTo(mutableSetOf()) { it.id }
    }

    private fun bucketWidthMs(state: String, soc: Double?, tier: Int): Long = when (state) {
        "parked" -> 5L * 60L * 1_000L
        "charging" -> if ((soc ?: 0.0) >= 98.0) 10_000L else if (tier <= 1) 10_000L else 30_000L
        else -> when (tier) {
            1 -> 5_000L
            2 -> 15_000L
            else -> 60_000L
        }
    }

    private fun parse(row: CloudSyncQueueEntity): Parsed {
        val json = runCatching { JSONObject(row.payloadJson) }.getOrNull()
        val telemetry = json?.optJSONObject("telemetry")
        val diplus = json?.optJSONObject("diplus")
        val charging = telemetry?.optBoolean("is_charging", false) == true
        val parked = telemetry?.optBoolean("is_parked", false) == true
        val kind = when {
            charging -> "charging"
            parked -> "parked"
            else -> "driving"
        }
        return Parsed(
            row = row,
            kind = kind,
            signature = "$kind:${diplus?.optInt("gear", Int.MIN_VALUE)}:${diplus?.optInt("power_state", Int.MIN_VALUE)}:${row.origin}",
            soc = optionalDouble(telemetry, "soc"),
            auxVoltage = optionalDouble(telemetry, "aux_voltage_v"),
            cellMin = optionalDouble(telemetry, "cell_voltage_min_v"),
            cellMax = optionalDouble(telemetry, "cell_voltage_max_v"),
            hasLocation = json?.optJSONObject("location")?.has("lat") == true,
        )
    }

    internal fun stateOf(row: CloudSyncQueueEntity): String = parse(row).kind

    private fun optionalDouble(json: JSONObject?, key: String): Double? =
        json?.takeIf { it.has(key) && !it.isNull(key) }
            ?.optDouble(key, Double.NaN)
            ?.takeUnless(Double::isNaN)

    private data class Parsed(
        val row: CloudSyncQueueEntity,
        val kind: String,
        val signature: String,
        val soc: Double?,
        val auxVoltage: Double?,
        val cellMin: Double?,
        val cellMax: Double?,
        val hasLocation: Boolean,
    )

    private data class Bucket(val state: String, val number: Long)
}
