package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration17to18Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `migration preserves realistic queue rows stage 2 ids and stage 1 retry state`() {
        val rows = listOf(
            SeedRow(
                createdAt = 1_788_350_000_123L,
                payload = payload("2026-09-02T12:46:40.123Z", "driving", 22.4),
                sampleId = null,
                attempts = 2,
                lastError = "network timeout",
                sentAt = null,
            ),
            SeedRow(
                createdAt = 1_788_350_010_456L,
                payload = payload("2026-09-02T12:46:50.456Z", "charging", -6.8),
                sampleId = "5559bc1a-d427-48a6-887a-edaa155f65a7",
                attempts = 1,
                lastError = null,
                sentAt = 1_788_350_011_000L,
            ),
            SeedRow(
                createdAt = 1_788_350_020_789L,
                payload = payload("2026-09-02T12:47:00.789Z", "parked", 0.0),
                sampleId = null,
                attempts = 3,
                lastError = "HTTP 422 rejected payload",
                sentAt = 1_788_350_021_000L,
            ),
        )
        val settings = mapOf(
            "cloud_sync_next_attempt_at" to "1788350099000",
            "cloud_sync_failure_count" to "7",
            "cloud_sync_next_drain_at" to "1788350199000",
        )
        helper.createDatabase(DB_NAME, 17).apply {
            rows.forEach { row ->
                execSQL(
                    "INSERT INTO cloud_sync_queue(" +
                        "createdAt,payloadJson,sampleId,attempts,lastError,sentAt) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        row.createdAt, row.payload, row.sampleId, row.attempts, row.lastError, row.sentAt,
                    ),
                )
            }
            settings.forEach { (key, value) ->
                execSQL("INSERT INTO settings(`key`,value) VALUES(?,?)", arrayOf(key, value))
            }
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 18, true, AppModule.MIGRATION_17_18)
        db.query(
            "SELECT createdAt,payloadJson,sampleId,attempts,lastError,sentAt," +
                "payloadBytes,capturedAt,origin,compactionTier " +
                "FROM cloud_sync_queue ORDER BY createdAt"
        ).use { cursor ->
            assertEquals(rows.size, cursor.count)
            rows.forEach { expected ->
                assertTrue(cursor.moveToNext())
                assertEquals(expected.createdAt, cursor.getLong(0))
                assertEquals(expected.payload, cursor.getString(1))
                if (expected.sampleId == null) assertTrue(cursor.isNull(2))
                else assertEquals(expected.sampleId, cursor.getString(2))
                assertEquals(expected.attempts, cursor.getInt(3))
                if (expected.lastError == null) assertTrue(cursor.isNull(4))
                else assertEquals(expected.lastError, cursor.getString(4))
                if (expected.sentAt == null) assertTrue(cursor.isNull(5))
                else assertEquals(expected.sentAt, cursor.getLong(5))
                assertEquals(0L, cursor.getLong(6))
                assertEquals(expected.createdAt, cursor.getLong(7))
                assertEquals(if (expected.sampleId == null) "app" else "daemon", cursor.getString(8))
                assertEquals(0, cursor.getInt(9))
                assertEquals(
                    expected.payload.substringAfter("\"device_time\":\"").substringBefore('"'),
                    JSONObject(cursor.getString(1)).getString("device_time"),
                )
            }
        }
        db.query(
            "SELECT `key`,value FROM settings WHERE `key` IN (" +
                "'cloud_sync_next_attempt_at','cloud_sync_failure_count','cloud_sync_next_drain_at')"
        ).use { cursor ->
            val actual = buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
            assertEquals(settings, actual)
        }
        db.query(
            "SELECT totalPayloadBytes,unknownPayloadRows,backfillComplete " +
                "FROM queue_storage_metadata WHERE id=1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(rows.size, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
        assertUniqueIndex(db, "index_cloud_sync_queue_sampleId")
        assertIndex(db, "index_cloud_sync_queue_sentAt_capturedAt")
        assertIndex(db, "index_cloud_sync_queue_compactionTier_capturedAt")
        db.close()
    }

    @Test
    fun `fresh v18 database installs accounting row and triggers`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "fresh-v18-${System.nanoTime()}.db"
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(AppModule.QUEUE_STORAGE_CALLBACK)
            .allowMainThreadQueries()
            .build()
        val db = room.openHelper.writableDatabase
        val payload = payload("2026-09-03T08:00:00Z", "parked", 0.0)
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        db.execSQL(
            "INSERT INTO cloud_sync_queue(" +
                "createdAt,payloadJson,attempts,payloadBytes,capturedAt,origin,compactionTier) " +
                "VALUES(?,?,0,?,?,?,0)",
            arrayOf<Any?>(1_788_418_800_000L, payload, bytes, 1_788_418_800_000L, "app"),
        )
        db.query(
            "SELECT totalPayloadBytes,unknownPayloadRows,backfillComplete " +
                "FROM queue_storage_metadata WHERE id=1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(bytes.toLong(), cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
        }
        assertUniqueIndex(db, "index_cloud_sync_queue_sampleId")
        assertIndex(db, "index_cloud_sync_queue_sentAt_capturedAt")
        assertIndex(db, "index_cloud_sync_queue_compactionTier_capturedAt")
        room.close()
        context.deleteDatabase(name)
    }

    private fun assertUniqueIndex(db: androidx.sqlite.db.SupportSQLiteDatabase, name: String) {
        db.query("PRAGMA index_list('cloud_sync_queue')").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    found = cursor.getInt(2) == 1
                    break
                }
            }
            assertTrue("missing unique index $name", found)
        }
    }

    private fun assertIndex(db: androidx.sqlite.db.SupportSQLiteDatabase, name: String) {
        db.query("PRAGMA index_list('cloud_sync_queue')").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == name) found = true
            assertTrue("missing index $name", found)
        }
    }

    private fun payload(deviceTime: String, state: String, powerKw: Double): String = JSONObject()
        .put("schema_version", 1)
        .put("vehicle_id", "way")
        .put("device_time", deviceTime)
        .put("source", "BYDMate")
        .put("telemetry", JSONObject().put("state", state).put("power_kw", powerKw))
        .put("diplus", JSONObject())
        .put("location", JSONObject())
        .toString()

    private data class SeedRow(
        val createdAt: Long,
        val payload: String,
        val sampleId: String?,
        val attempts: Int,
        val lastError: String?,
        val sentAt: Long?,
    )

    companion object {
        private const val DB_NAME = "migration-17-18.db"
    }
}
