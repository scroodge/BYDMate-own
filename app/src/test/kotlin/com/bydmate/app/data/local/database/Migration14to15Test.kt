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

/**
 * A schema drift between MIGRATION_14_15 and HourlyRollupEntity would not surface until Room
 * validated it on a real launch — i.e. as a crash on the car. runMigrationsAndValidate compares
 * the migrated database against the exported 15.json, so it fails here instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration14to15Test {

    private val dbName = "migration-14-15-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `migrate creates the hourly rollup table matching the entity schema`() {
        helper.createDatabase(dbName, 14).close()

        val migrated = helper.runMigrationsAndValidate(
            dbName, 15, /*validateDroppedTables=*/true,
            AppModule.MIGRATION_14_15
        )

        migrated.execSQL(
            """
            INSERT INTO cloud_hourly_rollup (
                vehicleId, hourStart, sampleCount, socMin, socMax, socLast, speedMax,
                powerSum, powerSampleCount, batteryTempSum, batteryTempSampleCount,
                cabinTempSum, cabinTempSampleCount, outsideTempSum, outsideTempSampleCount,
                regenKwhSum, tractionKwhSum, dirty, updatedAt
            ) VALUES ('way', 1752746400000, 2, 50.0, 52.0, 51.0, 30.0,
                      30.0, 2, 0.0, 0, 0.0, 0, 0.0, 0, 0.25, 1.5, 1, 1752746500000)
            """
        )

        migrated.query("SELECT vehicleId, hourStart, sampleCount, tractionKwhSum, dirty FROM cloud_hourly_rollup").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("way", c.getString(0))
            assertEquals(1752746400000L, c.getLong(1))
            assertEquals(2, c.getInt(2))
            assertEquals(1.5, c.getDouble(3), 1e-9)
            assertEquals(1, c.getInt(4))
        }
    }

    @Test
    fun `migrate preserves an existing cloud sync queue row`() {
        // The queue is the other half of the flush path; the new table must not disturb it.
        helper.createDatabase(dbName, 14).apply {
            execSQL(
                "INSERT INTO cloud_sync_queue (createdAt, payloadJson, attempts) " +
                    "VALUES (1752746400000, '{\"vehicle_id\":\"way\"}', 0)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 15, /*validateDroppedTables=*/true,
            AppModule.MIGRATION_14_15
        )

        migrated.query("SELECT payloadJson FROM cloud_sync_queue").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("""{"vehicle_id":"way"}""", c.getString(0))
        }
    }
}
