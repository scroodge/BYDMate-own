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
 * A schema drift between MIGRATION_15_16 and TripRollupEntity would not surface until Room
 * validated it on a real launch — i.e. as a crash on the car. runMigrationsAndValidate compares
 * the migrated database against the exported 16.json, so it fails here instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration15to16Test {

    private val dbName = "migration-15-16-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `migrate creates the trip rollup table matching the entity schema`() {
        helper.createDatabase(dbName, 15).close()

        val migrated = helper.runMigrationsAndValidate(
            dbName, 16, /*validateDroppedTables=*/true,
            AppModule.MIGRATION_15_16
        )

        migrated.execSQL(
            """
            INSERT INTO cloud_trip_rollup (
                tripId, vehicleId, startedAt, lastDeviceTime, endedAt, sampleCount,
                distanceBaselineKm, consumptionBaselineKwh, lastOdometerKm, lastTotalElecConsumptionKwh,
                socStart, socEnd, maxSpeedKmh, speedSum, speedSampleCount,
                regenKwhSum, tractionKwhSum, dirty, updatedAt
            ) VALUES (
                '11111111-1111-1111-1111-111111111111', 'way', 1752746400000, 1752746460000, NULL, 3,
                1000.0, 500.0, 1010.0, 502.0,
                80.0, 70.0, 40.0, 90.0, 3,
                0.25, 1.5, 1, 1752746460000
            )
            """
        )

        migrated.query(
            "SELECT tripId, vehicleId, sampleCount, tractionKwhSum, dirty FROM cloud_trip_rollup"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("11111111-1111-1111-1111-111111111111", c.getString(0))
            assertEquals("way", c.getString(1))
            assertEquals(3, c.getInt(2))
            assertEquals(1.5, c.getDouble(3), 1e-9)
            assertEquals(1, c.getInt(4))
        }
    }

    @Test
    fun `migrate preserves an existing hourly rollup row`() {
        // The hourly table is the other half of the flush envelope; the new table must not
        // disturb it.
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                "INSERT INTO cloud_hourly_rollup (" +
                    "vehicleId, hourStart, sampleCount, powerSum, powerSampleCount, " +
                    "batteryTempSum, batteryTempSampleCount, cabinTempSum, cabinTempSampleCount, " +
                    "outsideTempSum, outsideTempSampleCount, regenKwhSum, tractionKwhSum, dirty, updatedAt" +
                    ") VALUES ('way', 1752746400000, 2, 30.0, 2, 0.0, 0, 0.0, 0, 0.0, 0, 0.25, 1.5, 1, 1752746500000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName, 16, /*validateDroppedTables=*/true,
            AppModule.MIGRATION_15_16
        )

        migrated.query("SELECT vehicleId, sampleCount FROM cloud_hourly_rollup").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("way", c.getString(0))
            assertEquals(2, c.getInt(1))
        }
    }
}
