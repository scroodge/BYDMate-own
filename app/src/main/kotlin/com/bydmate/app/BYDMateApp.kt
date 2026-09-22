package com.bydmate.app

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bydmate.app.data.local.DataThinningWorker
import com.bydmate.app.data.cloud.DaemonSpoolImporter
import com.bydmate.app.data.cloud.TripSummaryCloudSync
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.QueueStorageAccounting
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration as OsmdroidConfig
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Whether an app-foreground resume should trigger [HistoryImporter.syncFromEnergyData] +
 * [TripSummaryCloudSync.syncNewTrips]. Extracted as a pure function so the resume-trigger
 * decision is unit-testable without Robolectric/instrumentation on [BYDMateApp] itself --
 * same reasoning as [com.bydmate.app.daemon.CommandDaemon]'s `planPush`/`loopSleepMs`.
 * `DIPLUS`-source cars already get cloud trips from live telemetry and must stay quiet here
 * to avoid double-reporting (see [TripSummaryCloudSync]'s class doc).
 */
internal fun shouldSyncEnergyDataOnForeground(
    setupCompleted: Boolean,
    dataSource: SettingsRepository.DataSource,
): Boolean = setupCompleted && dataSource == SettingsRepository.DataSource.ENERGYDATA

@HiltAndroidApp
class BYDMateApp : Application(), Configuration.Provider {

    @Inject lateinit var historyImporter: HistoryImporter
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var chargeDao: ChargeDao
    @Inject lateinit var daemonSpoolImporter: DaemonSpoolImporter
    @Inject lateinit var queueStorageAccounting: QueueStorageAccounting
    @Inject lateinit var tripSummaryCloudSync: TripSummaryCloudSync

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        initOsmdroid()
        appScope.launch {
            // Canonicalize shell-daemon ingress before this process starts normal upload work.
            val spool = daemonSpoolImporter.importReady()
            if (spool.imported + spool.duplicates + spool.invalid > 0) {
                android.util.Log.i("BYDMateApp", "daemon spool import: $spool")
            }
            val storage = queueStorageAccounting.backfillAndMeasure()
            android.util.Log.i("BYDMateApp", "queue storage accounting: $storage")
            // One-shot migration: remove phantom autoservice rows created by the
            // lifetime_kwh driving-counter bug in v2.4.15/v2.4.16.
            if (!settingsRepository.isMigrationV2_4_17Done()) {
                val removed = chargeDao.deletePhantomAutoserviceRows()
                settingsRepository.setMigrationV2_4_17Done()
                android.util.Log.i("BYDMateApp", "v2.4.17 migration: removed $removed phantom autoservice rows")
            }
            // One-shot: move an already-paired car off the retired volt-flow-beige.vercel.app
            // host onto voltflow.life, without requiring a re-link. Custom endpoints untouched.
            if (settingsRepository.migrateCloudSyncDomainIfNeeded()) {
                android.util.Log.i("BYDMateApp", "domain migration: cloud_sync_url moved to voltflow.life")
            }
            // One-time cleanup of existing duplicates from v2.0.0
            historyImporter.cleanupDuplicates()
            // Only sync if setup is completed (prevents duplicates during first wizard run)
            if (settingsRepository.isSetupCompleted()) {
                historyImporter.sync()
            }
        }
        scheduleDataThinning()
        registerActivityLifecycleCallbacks(
            WidgetLifecycleCallbacks(
                app = this,
                appScope = appScope,
                settingsRepository = settingsRepository,
                historyImporter = historyImporter,
                tripSummaryCloudSync = tripSummaryCloudSync,
            ),
        )
    }

    private fun initOsmdroid() {
        OsmdroidConfig.getInstance().apply {
            userAgentValue = packageName
            val basePath = File(filesDir, "osmdroid")
            basePath.mkdirs()
            osmdroidBasePath = basePath
            val tilePath = File(basePath, "tiles")
            tilePath.mkdirs()
            osmdroidTileCache = tilePath
            tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
            load(this@BYDMateApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }
    }

    private class WidgetLifecycleCallbacks(
        private val app: Context,
        private val appScope: CoroutineScope,
        private val settingsRepository: SettingsRepository,
        private val historyImporter: HistoryImporter,
        private val tripSummaryCloudSync: TripSummaryCloudSync,
    ) : ActivityLifecycleCallbacks {
        private var resumedCount = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            resumedCount++
            if (resumedCount == 1) {
                // User opened BYDMate → widget hides; also clear the
                // "hidden until app launch" long-press flag so it reappears
                // next time the app goes to background.
                WidgetPreferences(app).setHiddenUntilAppLaunch(false)
                WidgetController.setAppForegrounded(true)
                syncOnForeground()
            }
        }

        /**
         * v0.5.6: the ENERGYDATA sync doc comment on `syncFromEnergyData` has long
         * claimed it runs "on service start and app foreground", but only the service
         * -start call ever existed (`HistoryImporter.runSync()` from `TrackingService`
         * / `BYDMateApp.onCreate()`). With no periodic trigger either, a user who opens
         * the app to check a just-finished trip while `TrackingService` has been running
         * continuously all day sees nothing until the *next* service restart -- reported
         * live as "trips not syncing" for cars on the no-ADB ENERGYDATA source (VoltFlow
         * BACKLOG: Kevlar_5, 2026-09-22). `syncFromEnergyData()` is cheap to call here:
         * it bails immediately via `EnergyDataReader.hasSourceChanged()` when the car's
         * on-device trip log file hasn't changed, so this adds no meaningful cost on the
         * far more common "nothing new" resume. Uses the lighter sync (not `runSync()`)
         * to skip the heavier dedup/consumption-recalc maintenance pass that already runs
         * at service start.
         */
        private fun syncOnForeground() {
            appScope.launch {
                val setupCompleted = settingsRepository.isSetupCompleted()
                val dataSource = settingsRepository.getDataSource()
                if (!shouldSyncEnergyDataOnForeground(setupCompleted, dataSource)) return@launch
                historyImporter.syncFromEnergyData()
                tripSummaryCloudSync.syncNewTrips()
            }
        }

        override fun onActivityPaused(activity: Activity) {
            resumedCount--
            if (resumedCount <= 0) {
                resumedCount = 0
                WidgetController.setAppForegrounded(false)
                val prefs = WidgetPreferences(app)
                if (prefs.isEnabled() && Settings.canDrawOverlays(app)) {
                    WidgetController.attach(app)
                }
            }
        }

        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun scheduleDataThinning() {
        val request = PeriodicWorkRequestBuilder<DataThinningWorker>(
            1, TimeUnit.DAYS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DataThinningWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
