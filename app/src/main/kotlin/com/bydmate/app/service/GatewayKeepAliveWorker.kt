package com.bydmate.app.service

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bydmate.app.data.remote.OverdriveEventClient
import com.bydmate.app.data.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Restarts [TrackingService] when gateway was wanted but the service died while
 * the keep-alive provider (Overdrive or D+) is still alive on the head unit.
 */
@HiltWorker
class GatewayKeepAliveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val overdriveEventClient: OverdriveEventClient,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "GatewayKeepAlive"
        const val WORK_NAME = "GatewayKeepAlive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GatewayKeepAliveWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        if (!settingsRepository.isGatewayWanted()) return Result.success()
        if (TrackingService.isRunning.value) return Result.success()

        val provider = settingsRepository.getKeepAliveProvider()
        val providerAlive = when {
            OverdriveCoLifecycleWatchdog.isOverdriveProvider(provider) ->
                OverdriveCoLifecycleWatchdog.isOverdriveProcessAlive(applicationContext) ||
                    overdriveEventClient.ping()
            else -> OverdriveCoLifecycleWatchdog.isDiPlusShellAlive()
        }
        if (!providerAlive) {
            Log.d(TAG, "Keep-alive provider offline ($provider), skip restart")
            return Result.success()
        }

        return try {
            val intent = android.content.Intent(applicationContext, TrackingService::class.java)
            ContextCompat.startForegroundService(applicationContext, intent)
            Log.i(TAG, "Restarted TrackingService (provider=$provider)")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restart TrackingService: ${e.message}")
            Result.retry()
        }
    }
}
