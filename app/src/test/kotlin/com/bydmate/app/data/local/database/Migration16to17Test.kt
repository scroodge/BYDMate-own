package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration16to17Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `migration preserves realistic queue payloads timestamps and delivery state`() {
        val rows = listOf(
            SeedRow(
                1_788_350_000_123L,
                """{"schema_version":1,"vehicle_id":"way","device_time":"2026-09-02T12:46:40.123Z","source":"BYDMate","telemetry":{"soc":94.5,"is_charging":true},"diplus":{"charge_gun_state":2},"location":{}}""",
                attempts = 2,
                lastError = "network timeout",
                sentAt = null,
            ),
            SeedRow(
                1_788_350_010_456L,
                """{"schema_version":1,"vehicle_id":"way","device_time":"2026-09-02T12:46:50.456Z","source":"BYDMate","telemetry":{"soc":94.6,"is_charging":true},"diplus":{"charge_gun_state":2},"location":{}}""",
                attempts = 1,
                lastError = null,
                sentAt = 1_788_350_011_000L,
            ),
            SeedRow(
                1_788_350_020_789L,
                """{"schema_version":1,"vehicle_id":"way","device_time":"2026-09-02T12:47:00.789Z","source":"BYDMate","telemetry":{"soc":94.7,"is_charging":true},"diplus":{"charge_gun_state":2},"location":{}}""",
                attempts = 3,
                lastError = "HTTP 422 rejected payload",
                sentAt = 1_788_350_021_000L,
            ),
        )
        helper.createDatabase("migration-16-17.db", 16).apply {
            rows.forEach { row ->
                execSQL(
                    "INSERT INTO cloud_sync_queue(createdAt,payloadJson,attempts,lastError,sentAt) " +
                        "VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(row.createdAt, row.payload, row.attempts, row.lastError, row.sentAt),
                )
            }
            // Stage-1 retry gates are settings rows; the v17 migration must not disturb them.
            execSQL("INSERT INTO settings(`key`,value) VALUES('cloud_sync_next_attempt_at','1788350099000')")
            execSQL("INSERT INTO settings(`key`,value) VALUES('cloud_sync_failure_count','7')")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-16-17.db", 17, true, AppModule.MIGRATION_16_17,
        )
        db.query(
            "SELECT createdAt,payloadJson,attempts,lastError,sentAt,sampleId " +
                "FROM cloud_sync_queue ORDER BY createdAt"
        ).use { cursor ->
            assertEquals(rows.size, cursor.count)
            rows.forEach { expected ->
                assertEquals(true, cursor.moveToNext())
                assertEquals(expected.createdAt, cursor.getLong(0))
                assertEquals(expected.payload, cursor.getString(1))
                assertEquals(expected.attempts, cursor.getInt(2))
                if (expected.lastError == null) assertEquals(true, cursor.isNull(3))
                else assertEquals(expected.lastError, cursor.getString(3))
                if (expected.sentAt == null) assertEquals(true, cursor.isNull(4))
                else assertEquals(expected.sentAt, cursor.getLong(4))
                assertEquals(true, cursor.isNull(5))
                assertEquals(
                    expected.payload.substringAfter("\"device_time\":\"").substringBefore('"'),
                    org.json.JSONObject(cursor.getString(1)).getString("device_time"),
                )
            }
        }
        db.query("SELECT `key`,value FROM settings WHERE `key` LIKE 'cloud_sync_%' ORDER BY `key`").use {
            assertEquals(2, it.count)
            it.moveToFirst()
            assertEquals("cloud_sync_failure_count", it.getString(0))
            assertEquals("7", it.getString(1))
            it.moveToNext()
            assertEquals("cloud_sync_next_attempt_at", it.getString(0))
            assertEquals("1788350099000", it.getString(1))
        }
        db.execSQL("INSERT INTO cloud_sync_queue(createdAt,payloadJson,sampleId,attempts) VALUES(124,'{}','same',0)")
        val duplicate = runCatching {
            db.execSQL("INSERT INTO cloud_sync_queue(createdAt,payloadJson,sampleId,attempts) VALUES(125,'{}','same',0)")
        }
        assertEquals(true, duplicate.isFailure)
        db.close()
    }

    private data class SeedRow(
        val createdAt: Long,
        val payload: String,
        val attempts: Int,
        val lastError: String?,
        val sentAt: Long?,
    )
}
