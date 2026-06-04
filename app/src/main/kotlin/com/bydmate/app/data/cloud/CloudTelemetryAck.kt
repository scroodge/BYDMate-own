package com.bydmate.app.data.cloud

import org.json.JSONObject

data class CloudTelemetryAck(
    val ok: Boolean,
    val sentCount: Int,
    val insertedCount: Int,
    val duplicateCount: Int,
    val skippedStaleCount: Int,
    val error: String?,
    val parseError: String? = null,
) {
    fun isFullyAcknowledged(): Boolean =
        parseError == null &&
            ok &&
            skippedStaleCount == 0 &&
            insertedCount + duplicateCount >= sentCount

    fun formatDiagnostics(): String =
        "$sentCount sent, $insertedCount ins, $duplicateCount dup, $skippedStaleCount skip"
}

object CloudTelemetryAckParser {
    fun parse(responseBody: String?, sentCount: Int): CloudTelemetryAck {
        if (responseBody.isNullOrBlank()) {
            return CloudTelemetryAck(
                ok = false,
                sentCount = sentCount,
                insertedCount = 0,
                duplicateCount = 0,
                skippedStaleCount = 0,
                error = null,
                parseError = "empty response body",
            )
        }

        return try {
            val json = JSONObject(responseBody)
            val ok = json.optBoolean("ok", false)
            val inserted = json.optInt("inserted_count", -1)
            val duplicate = json.optInt("duplicate_count", -1)
            val skippedStale = json.optInt("skipped_stale_count", 0)
            val sampleCount = json.optInt("sample_count", sentCount)

            val resolvedInserted = if (inserted >= 0) {
                inserted
            } else if (json.optBoolean("duplicate", false)) {
                0
            } else {
                (sampleCount - skippedStale).coerceAtLeast(0)
            }
            val resolvedDuplicate = if (duplicate >= 0) {
                duplicate
            } else {
                (sentCount - resolvedInserted - skippedStale).coerceAtLeast(0)
            }

            CloudTelemetryAck(
                ok = ok,
                sentCount = sentCount,
                insertedCount = resolvedInserted,
                duplicateCount = resolvedDuplicate,
                skippedStaleCount = skippedStale,
                error = json.optString("error", null)?.takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            CloudTelemetryAck(
                ok = false,
                sentCount = sentCount,
                insertedCount = 0,
                duplicateCount = 0,
                skippedStaleCount = 0,
                error = null,
                parseError = e.message ?: "invalid JSON",
            )
        }
    }
}
