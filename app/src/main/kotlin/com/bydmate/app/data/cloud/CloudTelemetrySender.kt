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
    @Volatile private var lastQueuedSampleMs: Long = 0L
    @Volatile private var lastFlushAttemptMs: Long = 0L
    @Volatile private var lastMoving: Boolean? = null
    @Volatile private var lastCharging: Boolean? = null

    suspend fun send(snapshot: VehicleTelemetrySnapshot): Result<Unit> {
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false") != "true") {
            return Result.success(Unit)
        }
        val config = readConfig().getOrElse { error ->
            saveStatus(ok = false, message = error.message ?: "Ошибка настроек")
            return Result.failure(error)
        }

        val now = System.currentTimeMillis()
        queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)

        val payload = CloudTelemetryPayload.build(config.vehicleId, snapshot)
        val decision = decide(snapshot, now)
        if (decision.enqueue) {
            queueDao.insert(pendingQueueEntity(payload, now))
            lastQueuedSampleMs = now
        }

        val unsentCount = queueDao.countUnsent()
        if (config.wifiOnly && !isWifiConnected()) {
            saveStatus(ok = false, message = "queued $unsentCount; waiting for Wi-Fi")
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            return Result.success(Unit)
        }

        val flushIntervalMs = config.flushIntervalSec * 1000L
        val shouldFlush = unsentCount >= MAX_BATCH_SIZE ||
            decision.flushNow ||
            (unsentCount > 0 && now - lastFlushAttemptMs >= flushIntervalMs)

        if (!shouldFlush) {
            saveStatus(ok = true, message = "queued $unsentCount")
            return Result.success(Unit)
        }

        lastFlushAttemptMs = now
        return if (flushQueue(config, now)) {
            val remaining = queueDao.countUnsent()
            saveStatus(ok = true, message = "OK; queued $remaining")
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            Result.success(Unit)
        } else {
            val remaining = queueDao.countUnsent()
            val message = "queued $remaining; waiting for retry"
            saveStatus(ok = false, message = message)
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            Result.failure(IllegalStateException(message))
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
        while (true) {
            val items = queueDao.getUnsent(MAX_BATCH_SIZE)
            if (items.isEmpty()) return true

            val payload = if (items.size == 1) {
                items.first().payloadJson
            } else {
                CloudTelemetryPayload.buildBatch(items.map { it.payloadJson })
            }

            when (val result = client.send(config.url, config.apiKey, config.vehicleId, payload)) {
                CloudSendResult.Success -> {
                    items.forEach { queueDao.markFinished(it.id, null, now) }
                }
                is CloudSendResult.NonRetryableFailure -> {
                    items.forEach { queueDao.markFinished(it.id, result.message, now) }
                }
                is CloudSendResult.RetryableFailure -> {
                    items.forEach { queueDao.markAttempt(it.id, result.message) }
                    return false
                }
            }
        }
    }

    private fun decide(snapshot: VehicleTelemetrySnapshot, now: Long): QueueDecision {
        val moving = (snapshot.speedKmh ?: 0.0) > MOVING_SPEED_THRESHOLD_KMH
        val charging = snapshot.isCharging == true || kotlin.math.abs(snapshot.chargePowerKw ?: 0.0) > CHARGING_POWER_THRESHOLD_KW
        val stateChanged = lastMoving?.let { it != moving } == true ||
            lastCharging?.let { it != charging } == true
        lastMoving = moving
        lastCharging = charging

        val minSampleIntervalMs = when {
            moving -> MOVING_SAMPLE_INTERVAL_MS
            charging -> CHARGING_SAMPLE_INTERVAL_MS
            else -> STOPPED_HEARTBEAT_INTERVAL_MS
        }
        val enqueue = stateChanged || lastQueuedSampleMs == 0L || now - lastQueuedSampleMs >= minSampleIntervalMs
        return QueueDecision(enqueue = enqueue, flushNow = stateChanged)
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
                flushIntervalSec = settingsRepository.getString(
                    SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC,
                    SettingsRepository.DEFAULT_CLOUD_SYNC_INTERVAL_SEC,
                ).toLongOrNull()?.coerceIn(5L, 300L) ?: 60L,
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

    private fun pendingQueueEntity(payload: String, now: Long) = CloudSyncQueueEntity(
        createdAt = now,
        payloadJson = payload,
    )

    private data class Config(
        val url: String,
        val apiKey: String,
        val vehicleId: String,
        val wifiOnly: Boolean,
        val flushIntervalSec: Long,
    )

    private data class QueueDecision(
        val enqueue: Boolean,
        val flushNow: Boolean,
    )

    private companion object {
        const val MAX_QUEUE_ROWS = 1000
        const val MAX_BATCH_SIZE = 60
        const val MOVING_SPEED_THRESHOLD_KMH = 0.5
        const val CHARGING_POWER_THRESHOLD_KW = 0.1
        const val MOVING_SAMPLE_INTERVAL_MS = 1_000L
        const val CHARGING_SAMPLE_INTERVAL_MS = 30_000L
        const val STOPPED_HEARTBEAT_INTERVAL_MS = 5L * 60_000L
    }
}
