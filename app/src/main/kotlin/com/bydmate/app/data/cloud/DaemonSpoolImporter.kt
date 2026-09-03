package com.bydmate.app.data.cloud

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity.Companion.ORIGIN_DAEMON
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DaemonSpoolImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val database: AppDatabase,
) {
    private val engine = DaemonSpoolImportEngine(
        database,
        DaemonTelemetrySpool(
            context.getExternalFilesDir(null)?.resolve("telemetry/daemon-spool")
                ?: File(DaemonTelemetrySpool.EXTERNAL_DIRECTORY)
        ),
    )

    suspend fun importReady(): ImportResult = engine.importReady()
    suspend fun importRecord(record: DaemonSpoolRecord): Boolean = engine.importRecord(record)

    data class ImportResult(val imported: Int, val duplicates: Int, val invalid: Int)
}

internal class DaemonSpoolImportEngine(
    private val database: AppDatabase,
    private val spool: DaemonTelemetrySpool,
) {

    /** Test-only crash seam representing death after Room commit and before file deletion. */
    internal var afterCommit: (DaemonSpoolRecord) -> Unit = {}

    suspend fun importReady(): DaemonSpoolImporter.ImportResult {
        var imported = 0
        var duplicates = 0
        var invalid = 0
        for (file in spool.readyFiles()) {
            val recordResult = runCatching { spool.read(file) }
            if (recordResult.isFailure) {
                invalid++
                Log.e(TAG, "Keeping invalid daemon spool ${file.name}: ${recordResult.exceptionOrNull()?.message}")
                continue
            }
            val record = recordResult.getOrThrow()
            val insertResult = runCatching { importRecord(record) }
            if (insertResult.isFailure) {
                Log.e(TAG, "Keeping daemon spool ${file.name}: ${insertResult.exceptionOrNull()?.message}")
                continue
            }
            val inserted = insertResult.getOrThrow()
            if (inserted) imported++ else duplicates++
            afterCommit(record)
            check(spool.deleteImported(file)) { "Could not delete imported spool ${file.name}" }
        }
        return DaemonSpoolImporter.ImportResult(imported, duplicates, invalid)
    }

    /** ACKs only after the canonical Room transaction has committed. */
    suspend fun importRecord(record: DaemonSpoolRecord): Boolean {
        val payload = JSONObject(record.payloadJson)
        require(payload.optString("device_time") == record.deviceTime) {
            "Spool device_time differs from payload"
        }
        require(payload.optString("vehicle_id").isNotBlank()) { "Missing vehicle_id" }
        val capturedAt = Instant.parse(record.deviceTime).toEpochMilli()
        return database.withTransaction {
            database.cloudSyncQueueDao().insertDaemonIfAbsent(
                CloudSyncQueueEntity(
                    createdAt = capturedAt,
                    payloadJson = record.payloadJson,
                    sampleId = record.sampleId,
                    capturedAt = capturedAt,
                    origin = ORIGIN_DAEMON,
                )
            ) != -1L
        }
    }

    companion object {
        private const val TAG = "DaemonSpoolImporter"
    }
}
