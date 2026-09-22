package com.bydmate.app

import com.bydmate.app.data.repository.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [shouldSyncEnergyDataOnForeground], the resume-trigger decision extracted from
 * [BYDMateApp]'s app-foreground fix for the reported "trips not syncing" symptom on
 * no-ADB ENERGYDATA-source cars: `syncFromEnergyData`/`TripSummaryCloudSync.syncNewTrips`
 * previously only ran on service/app cold start, so a user checking a just-finished trip
 * while `TrackingService` had been running continuously all day saw nothing until the
 * *next* service restart.
 */
class BYDMateAppTest {

    @Test
    fun `syncs on foreground once setup is complete and data source is ENERGYDATA`() {
        assertTrue(
            shouldSyncEnergyDataOnForeground(
                setupCompleted = true,
                dataSource = SettingsRepository.DataSource.ENERGYDATA,
            ),
        )
    }

    @Test
    fun `stays quiet during first-run setup wizard to avoid duplicate imports`() {
        assertFalse(
            shouldSyncEnergyDataOnForeground(
                setupCompleted = false,
                dataSource = SettingsRepository.DataSource.ENERGYDATA,
            ),
        )
    }

    @Test
    fun `stays quiet on DIPLUS cars, which already get cloud trips from live telemetry`() {
        assertFalse(
            shouldSyncEnergyDataOnForeground(
                setupCompleted = true,
                dataSource = SettingsRepository.DataSource.DIPLUS,
            ),
        )
    }
}
