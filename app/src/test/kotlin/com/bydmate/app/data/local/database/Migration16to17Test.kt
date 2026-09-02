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
    fun `migration preserves legacy rows and enforces daemon sample id uniqueness`() {
        helper.createDatabase("migration-16-17.db", 16).apply {
            execSQL(
                "INSERT INTO cloud_sync_queue(createdAt,payloadJson,attempts) " +
                    "VALUES(123,'{\"device_time\":\"legacy\"}',0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-16-17.db", 17, true, AppModule.MIGRATION_16_17,
        )
        db.query("SELECT createdAt,payloadJson,sampleId FROM cloud_sync_queue").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(123L, it.getLong(0))
            assertEquals(true, it.isNull(2))
        }
        db.execSQL("INSERT INTO cloud_sync_queue(createdAt,payloadJson,sampleId,attempts) VALUES(124,'{}','same',0)")
        val duplicate = runCatching {
            db.execSQL("INSERT INTO cloud_sync_queue(createdAt,payloadJson,sampleId,attempts) VALUES(125,'{}','same',0)")
        }
        assertEquals(true, duplicate.isFailure)
        db.close()
    }
}
