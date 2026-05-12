package com.bydmate.app.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudTelemetrySender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val queueDao: CloudSyncQueueDao,
    private val client: CloudTelemetryClient,
) {
    suspend fun send(snapshot: VehicleTelemetrySnapshot): Result<Unit> {
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false") != "true") {
            return Result.success(Unit)
        }
        val config = readConfig().getOrElse { error ->
            saveStatus(ok = false, message = error.message ?: "Ошибка настроек")
            return Result.failure(error)
        }
        if (config.wifiOnly && !isWifiConnected()) {
            return Result.success(Unit)
        }

        val now = System.currentTimeMillis()
        queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)

        val payload = CloudTelemetryPayload.build(config.vehicleId, snapshot)
        if (!flushQueue(config, now)) {
            queueDao.insert(retryQueueEntity(payload, now, "waiting for queued payload retry"))
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            saveStatus(ok = false, message = "waiting for queued payload retry")
            return Result.failure(IllegalStateException("waiting for queued payload retry"))
        }

        return when (val result = client.send(config.url, config.apiKey, config.vehicleId, payload)) {
            CloudSendResult.Success -> {
                saveStatus(ok = true, message = "OK")
                Result.success(Unit)
            }
            is CloudSendResult.NonRetryableFailure -> {
                queueDao.insert(finishedQueueEntity(payload, now, result.message))
                saveStatus(ok = false, message = result.message)
                Result.failure(IllegalStateException(result.message))
            }
            is CloudSendResult.RetryableFailure -> {
                queueDao.insert(retryQueueEntity(payload, now, result.message))
                saveStatus(ok = false, message = result.message)
                Result.failure(IllegalStateException(result.message))
            }
        }.also {
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
        }
    }

    suspend fun sendTest(snapshot: VehicleTelemetrySnapshot): Result<Unit> {
        val config = readConfig().getOrElse { error ->
            saveStatus(ok = false, message = error.message ?: "Ошибка настроек")
            return Result.failure(error)
        }
        val payload = CloudTelemetryPayload.build(config.vehicleId, snapshot)
        return when (val result = client.send(config.url, config.apiKey, config.vehicleId, payload)) {
            CloudSendResult.Success -> {
                saveStatus(ok = true, message = "Test OK")
                Result.success(Unit)
            }
            is CloudSendResult.NonRetryableFailure -> {
                saveStatus(ok = false, message = result.message)
                Result.failure(IllegalStateException(result.message))
            }
            is CloudSendResult.RetryableFailure -> {
                saveStatus(ok = false, message = result.message)
                Result.failure(IllegalStateException(result.message))
            }
        }
    }

    private suspend fun flushQueue(config: Config, now: Long): Boolean {
        for (item in queueDao.getUnsent(50)) {
            when (val result = client.send(config.url, config.apiKey, config.vehicleId, item.payloadJson)) {
                CloudSendResult.Success -> queueDao.markFinished(item.id, null, now)
                is CloudSendResult.NonRetryableFailure -> queueDao.markFinished(item.id, result.message, now)
                is CloudSendResult.RetryableFailure -> {
                    queueDao.markAttempt(item.id, result.message)
                    return false
                }
            }
        }
        return true
    }

    private suspend fun readConfig(): Result<Config> {
        val url = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_URL, "").trim()
        if (url.isBlank()) return Result.failure(IllegalArgumentException("Endpoint URL пустой"))
        if (!url.startsWith("https://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Endpoint должен начинаться с https://"))
        }
        val vehicleId = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "").trim()
        if (vehicleId.isBlank()) return Result.failure(IllegalArgumentException("Vehicle ID пустой"))
        return Result.success(
            Config(
                url = url,
                apiKey = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, ""),
                vehicleId = vehicleId,
                wifiOnly = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, "false") == "true",
            )
        )
    }

    private suspend fun saveStatus(ok: Boolean, message: String) {
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_OK, ok.toString())
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ERROR, message)
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_TS, System.currentTimeMillis().toString())
    }

    private fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun retryQueueEntity(payload: String, now: Long, error: String) = CloudSyncQueueEntity(
        createdAt = now,
        payloadJson = payload,
        attempts = 1,
        lastError = error,
    )

    private fun finishedQueueEntity(payload: String, now: Long, error: String) = CloudSyncQueueEntity(
        createdAt = now,
        payloadJson = payload,
        attempts = 1,
        lastError = error,
        sentAt = now,
    )

    private data class Config(
        val url: String,
        val apiKey: String,
        val vehicleId: String,
        val wifiOnly: Boolean,
    )

    private companion object {
        const val MAX_QUEUE_ROWS = 1000
    }
}
