package com.bydmate.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class QueueStorageAccountingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<Pair<String, AppDatabase>>()

    @After
    fun tearDown() {
        databases.forEach { (name, database) ->
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `legacy byte backfill is bounded and reconciles payload database and wal accounting`() = runTest {
        val name = "queue-accounting-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        databases += name to database
        val sql = database.openHelper.writableDatabase
        QueueStorageSchema.installAccounting(sql)
        val payloads = (0 until 205).map { index ->
            JSONObject()
                .put("vehicle_id", "way")
                .put("device_time", "2026-09-03T08:00:${index % 60}.000Z")
                .put("note", "кириллица-$index")
                .toString()
        }
        payloads.forEachIndexed { index, payload ->
            sql.execSQL(
                "INSERT INTO cloud_sync_queue(" +
                    "createdAt,payloadJson,attempts,payloadBytes,capturedAt,origin,compactionTier) " +
                    "VALUES(?,?,0,0,?,'app',0)",
                arrayOf<Any?>(index.toLong(), payload, index.toLong()),
            )
        }

        val report = QueueStorageAccounting(
            context,
            database,
            UnconfinedTestDispatcher(testScheduler),
        ).backfillAndMeasure()

        val expectedBytes = payloads.sumOf(QueuePayloadMetrics::utf8Bytes)
        assertEquals(205, report.backfilledRows)
        assertEquals(3, report.backfillBatches)
        assertEquals(expectedBytes, report.totalPayloadBytes)
        assertEquals(0, report.unknownPayloadRows)
        assertTrue(report.databaseBytes > 0)
        assertTrue(report.walBytes >= 0)
        sql.query(
            "SELECT totalPayloadBytes,unknownPayloadRows,backfillComplete,databaseBytes,walBytes " +
                "FROM queue_storage_metadata WHERE id=1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedBytes, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(report.databaseBytes, cursor.getLong(3))
            assertEquals(report.walBytes, cursor.getLong(4))
        }
        sql.execSQL("DELETE FROM cloud_sync_queue WHERE id=1")
        sql.query("SELECT totalPayloadBytes FROM queue_storage_metadata WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedBytes - QueuePayloadMetrics.utf8Bytes(payloads.first()), cursor.getLong(0))
        }
    }

    @Test
    fun `payload metrics use utf8 bytes and payload device time`() {
        val payload = """{"device_time":"2026-09-03T08:00:00.123Z","note":"кириллица"}"""
        assertEquals(payload.toByteArray(Charsets.UTF_8).size.toLong(), QueuePayloadMetrics.utf8Bytes(payload))
        assertEquals(1_788_422_400_123L, QueuePayloadMetrics.capturedAt(payload, 7L))
        assertEquals(7L, QueuePayloadMetrics.capturedAt("{}", 7L))
        assertEquals(1_000, com.bydmate.app.data.cloud.CLOUD_QUEUE_MAX_ROWS)
    }
}
