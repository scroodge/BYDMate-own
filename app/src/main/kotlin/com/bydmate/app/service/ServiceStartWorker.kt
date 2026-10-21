package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bydmate.app.di.BootEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WorkManager worker that starts TrackingService.
 *
 * Used by BootReceiver instead of direct startForegroundService().
 * WorkManager guarantees execution even after process death —
 * same approach as BydConnect (ServiceStartWorker).
 */
class ServiceStartWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ServiceStartWorker"
        const val WORK_NAME = "ServiceStart"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting TrackingService via WorkManager")
        appendChainLog("Worker doWork started")
        val settings = EntryPointAccessors.fromApplication(applicationContext, BootEntryPoint::class.java)
            .settingsRepository()
        if (!settings.isGatewayWanted()) {
            Log.i(TAG, "Gateway not wanted, skip start")
            appendChainLog("Worker skipped (gateway_wanted=false)")
            return Result.success()
        }
        return try {
            val intent = Intent(applicationContext, TrackingService::class.java).apply {
                putExtra("onBoot", true)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            appendChainLog("startForegroundService OK")
            Log.i(TAG, "startForegroundService OK")
            Result.success()
        } catch (e: Exception) {
            appendChainLog("startForegroundService FAILED: ${e.message}")
            Log.e(TAG, "Failed to start TrackingService: ${e.message}", e)
            Result.retry()
        }
    }

    private fun appendChainLog(entry: String) = ChainLog.append(applicationContext, entry)
}
