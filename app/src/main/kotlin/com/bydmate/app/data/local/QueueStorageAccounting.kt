package com.bydmate.app.data.local

import android.content.Context
import android.os.storage.StorageManager
import androidx.room.withTransaction
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

object QueuePayloadMetrics {
    fun utf8Bytes(payloadJson: String): Long =
        payloadJson.toByteArray(StandardCharsets.UTF_8).size.toLong()

    fun capturedAt(payloadJson: String, fallbackCreatedAt: Long): Long = runCatching {
        Instant.parse(JSONObject(payloadJson).getString("device_time")).toEpochMilli()
    }.getOrDefault(fallbackCreatedAt)
}

data class QueueStorageReport(
    val totalPayloadBytes: Long,
    val unknownPayloadRows: Int,
    val databaseBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
    val allocatableBytes: Long,
    val backfilledRows: Int,
    val backfillBatches: Int,
)

/** Bounded post-open legacy backfill plus persisted DB/WAL/free-space measurement. */
@Singleton
class QueueStorageAccounting @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun backfillAndMeasure(): QueueStorageReport = withContext(ioDispatcher) {
        var backfilledRows = 0
        var backfillBatches = 0
        while (true) {
            coroutineContext.ensureActive()
            val updated = database.withTransaction { backfillBatch() }
            backfilledRows += updated
            if (updated == 0) break
            backfillBatches++
            yield()
        }
        reconcile(backfilledRows, backfillBatches)
    }

    private fun backfillBatch(): Int {
        val db = database.openHelper.writableDatabase
        val rows = buildList {
            db.query(
                "SELECT id,payloadJson FROM cloud_sync_queue " +
                    "WHERE payloadBytes=0 ORDER BY id LIMIT $BACKFILL_BATCH_ROWS"
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
            }
        }
        rows.forEach { (id, payload) ->
            db.execSQL(
                "UPDATE cloud_sync_queue SET payloadBytes=? WHERE id=? AND payloadBytes=0",
                arrayOf(QueuePayloadMetrics.utf8Bytes(payload), id),
            )
        }
        return rows.size
    }

    private suspend fun reconcile(
        backfilledRows: Int,
        backfillBatches: Int,
    ): QueueStorageReport = database.withTransaction {
        val db = database.openHelper.writableDatabase
        val path = db.path.orEmpty()
        val databaseBytes = fileSize(path)
        val walBytes = fileSize("$path-wal")
        val shmBytes = fileSize("$path-shm")
        val allocatableBytes = runCatching {
            context.getSystemService(StorageManager::class.java)
                ?.getAllocatableBytes(StorageManager.UUID_DEFAULT) ?: 0L
        }.getOrDefault(0L)
        val totals = db.query(
            "SELECT COALESCE(SUM(payloadBytes),0)," +
                "COALESCE(SUM(CASE WHEN payloadBytes=0 THEN 1 ELSE 0 END),0) " +
                "FROM cloud_sync_queue"
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0) to cursor.getInt(1)
        }
        val measuredAt = System.currentTimeMillis()
        db.execSQL(
            """
            UPDATE queue_storage_metadata SET
              totalPayloadBytes=?,unknownPayloadRows=?,databaseBytes=?,walBytes=?,shmBytes=?,
              allocatableBytes=?,measuredAt=?,backfillComplete=? WHERE id=1
            """.trimIndent(),
            arrayOf<Any?>(
                totals.first, totals.second, databaseBytes, walBytes, shmBytes,
                allocatableBytes, measuredAt, if (totals.second == 0) 1 else 0,
            ),
        )
        QueueStorageReport(
            totalPayloadBytes = totals.first,
            unknownPayloadRows = totals.second,
            databaseBytes = databaseBytes,
            walBytes = walBytes,
            shmBytes = shmBytes,
            allocatableBytes = allocatableBytes,
            backfilledRows = backfilledRows,
            backfillBatches = backfillBatches,
        )
    }

    private fun fileSize(path: String): Long = File(path).takeIf(File::isFile)?.length() ?: 0L

    companion object {
        internal const val BACKFILL_BATCH_ROWS = 100
    }
}
