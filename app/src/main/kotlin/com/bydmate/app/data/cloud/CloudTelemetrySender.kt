package com.bydmate.app.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class CloudTelemetrySender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val queueDao: CloudSyncQueueDao,
    private val client: CloudTelemetryClientApi,
) {
    @Volatile private var lastQueuedSampleMs: Long = 0L
    @Volatile private var lastFlushAttemptMs: Long = 0L
    @Volatile private var lastMoving: Boolean? = null
    @Volatile private var lastCharging: Boolean? = null
    @Volatile private var lastGear: Int? = null
    @Volatile private var activeBatchStartedMs: Long = 0L
    @Volatile private var idleUnchangedCycles: Int = 0
    @Volatile private var lastIdleSoc: Int? = null
    @Volatile private var lastIdleCharging: Boolean? = null
    @Volatile private var lastIdlePowerKw: Double? = null
    @Volatile private var pendingFlushNow: Boolean = false
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    /** Fast path: queue a sample without blocking on HTTP flush. */
    suspend fun enqueue(snapshot: VehicleTelemetrySnapshot): Result<Unit> {
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false") != "true") {
            return Result.success(Unit)
        }
        val config = readConfig().getOrElse { error ->
            saveStatus(ok = false, message = error.message ?: "Ошибка настроек")
            return Result.failure(error)
        }

        val now = nowProvider()
        queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)

        val omitGps = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, "false") == "true"
        val decision = decide(snapshot, now)
        if (decision.flushNow) {
            pendingFlushNow = true
        }
        if (decision.enqueue) {
            val payload = CloudTelemetryPayload.build(config.vehicleId, snapshot, omitGps = omitGps)
            queueDao.insert(pendingQueueEntity(payload, now))
            lastQueuedSampleMs = now
            if (decision.activeSample && activeBatchStartedMs == 0L) {
                activeBatchStartedMs = now
            }
        }

        val unsentCount = queueDao.countUnsent()
        saveStatus(ok = true, message = "queued $unsentCount")
        return Result.success(Unit)
    }

    /** Flush queued samples when interval/batch thresholds are met. Safe to skip if already flushing. */
    suspend fun flushPending(): Result<Unit> {
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false") != "true") {
            return Result.success(Unit)
        }
        val config = readConfig().getOrElse { error ->
            saveStatus(ok = false, message = error.message ?: "Ошибка настроек")
            return Result.failure(error)
        }

        val now = nowProvider()
        val unsentCount = queueDao.countUnsent()
        if (unsentCount == 0) {
            pendingFlushNow = false
            return Result.success(Unit)
        }

        if (config.wifiOnly && !isWifiConnected()) {
            saveStatus(ok = false, message = "queued $unsentCount; waiting for Wi-Fi")
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            return Result.success(Unit)
        }

        val activeBatchMode = activeBatchStartedMs != 0L
        val flushIntervalMs = if (activeBatchMode) {
            ACTIVE_FLUSH_INTERVAL_MS
        } else {
            config.flushIntervalSec * 1000L
        }
        val intervalElapsed = if (activeBatchMode) {
            activeBatchStartedMs != 0L && now - activeBatchStartedMs >= flushIntervalMs
        } else {
            now - lastFlushAttemptMs >= flushIntervalMs
        }
        val batchSize = if (activeBatchMode) ACTIVE_BATCH_SIZE else MAX_BATCH_SIZE
        val shouldFlush = unsentCount >= batchSize ||
            pendingFlushNow ||
            (unsentCount > 0 && intervalElapsed)

        if (!shouldFlush) {
            saveStatus(ok = true, message = "queued $unsentCount")
            return Result.success(Unit)
        }

        lastFlushAttemptMs = now
        return if (flushQueue(config, now, batchSize, drainAll = !activeBatchMode)) {
            pendingFlushNow = false
            if (activeBatchMode) activeBatchStartedMs = 0L
            val remaining = queueDao.countUnsent()
            saveStatus(ok = true, message = "OK; queued $remaining")
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            Result.success(Unit)
        } else {
            if (activeBatchMode) activeBatchStartedMs = now
            val remaining = queueDao.countUnsent()
            val message = "queued $remaining; waiting for retry"
            saveStatus(ok = false, message = message)
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            Result.failure(IllegalStateException(message))
        }
    }

    suspend fun send(snapshot: VehicleTelemetrySnapshot): Result<Unit> {
        val enqueueResult = enqueue(snapshot)
        if (enqueueResult.isFailure) return enqueueResult
        return flushPending()
    }

    suspend fun sendTest(snapshot: VehicleTelemetrySnapshot): Result<String?> {
        val config = readConfig().getOrElse { error ->
            saveStatus(ok = false, message = error.message ?: "Ошибка настроек")
            return Result.failure(error)
        }
        val omitGps = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, "false") == "true"
        val payload = CloudTelemetryPayload.build(config.vehicleId, snapshot, omitGps = omitGps)
        return when (val result = client.send(config.url, config.apiKey, config.vehicleId, payload)) {
            is CloudSendResult.Success -> {
                saveStatus(ok = true, message = "Test OK")
                Result.success(result.responseBody)
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

    private suspend fun flushQueue(config: Config, now: Long, batchSize: Int, drainAll: Boolean): Boolean {
        while (true) {
            val items = queueDao.getUnsent(batchSize)
            if (items.isEmpty()) return true

            val payload = if (items.size == 1) {
                items.first().payloadJson
            } else {
                CloudTelemetryPayload.buildBatch(items.map { it.payloadJson })
            }

            when (val result = client.send(config.url, config.apiKey, config.vehicleId, payload)) {
                is CloudSendResult.Success -> {
                    items.forEach { queueDao.markFinished(it.id, null, now) }
                    if (!drainAll) return true
                }
                is CloudSendResult.NonRetryableFailure -> {
                    items.forEach { queueDao.markFinished(it.id, result.message, now) }
                    if (!drainAll) return true
                }
                is CloudSendResult.RetryableFailure -> {
                    items.forEach { queueDao.markAttempt(it.id, result.message) }
                    return false
                }
            }
        }
    }

    private fun decide(snapshot: VehicleTelemetrySnapshot, now: Long): QueueDecision {
        val telemetryState = classifyCloudTelemetryState(snapshot)
        val moving = telemetryState == IternioIntervalPolicy.TelemetryState.DRIVING
        val charging = telemetryState == IternioIntervalPolicy.TelemetryState.CHARGING
        val active = moving || charging
        val gear = snapshot.diPlusData?.gear
        val previousMoving = lastMoving
        val previousCharging = lastCharging
        val previousGear = lastGear
        val gearChanged = previousGear != null && gear != null && previousGear != gear
        val stateChanged = previousMoving?.let { it != moving } == true ||
            previousCharging?.let { it != charging } == true ||
            gearChanged ||
            (previousGear == null && gear != null && lastQueuedSampleMs > 0L)
        lastMoving = moving
        lastCharging = charging
        lastGear = gear

        val minSampleIntervalMs = when (telemetryState) {
            IternioIntervalPolicy.TelemetryState.DRIVING -> MOVING_SAMPLE_INTERVAL_MS
            IternioIntervalPolicy.TelemetryState.CHARGING -> CHARGING_SAMPLE_INTERVAL_MS
            IternioIntervalPolicy.TelemetryState.PARKED -> PARKED_CLOUD_HEARTBEAT_MS
        }

        var enqueue = stateChanged || lastQueuedSampleMs == 0L || now - lastQueuedSampleMs >= minSampleIntervalMs

        if (!active && enqueue && !stateChanged) {
            val soc = snapshot.soc?.toInt()
            val power = snapshot.powerKw ?: 0.0
            val unchanged = soc != null &&
                soc == lastIdleSoc &&
                charging == (lastIdleCharging ?: false) &&
                abs(power - (lastIdlePowerKw ?: 0.0)) < 0.05
            if (unchanged) {
                idleUnchangedCycles += 1
                if (idleUnchangedCycles <= IDLE_UNCHANGED_SKIP_CYCLES) {
                    enqueue = false
                }
            } else {
                idleUnchangedCycles = 0
                lastIdleSoc = soc
                lastIdleCharging = charging
                lastIdlePowerKw = power
            }
        } else if (active || stateChanged) {
            idleUnchangedCycles = 0
            lastIdleSoc = snapshot.soc?.toInt()
            lastIdleCharging = charging
            lastIdlePowerKw = snapshot.powerKw ?: 0.0
        }

        val activeTransition = stateChanged && (active || previousCharging == true || previousMoving == true)
        return QueueDecision(
            enqueue = enqueue,
            flushNow = (gearChanged && !active) || (stateChanged && !activeTransition),
            activeBatchMode = active || previousCharging == true || previousMoving == true,
            activeSample = active,
        )
    }

    private fun classifyCloudTelemetryState(
        snapshot: VehicleTelemetrySnapshot,
    ): IternioIntervalPolicy.TelemetryState {
        snapshot.diPlusData?.let { return IternioIntervalPolicy.classifyFromDiPars(it) }
        val charging = snapshot.isCharging == true ||
            abs(snapshot.chargePowerKw ?: snapshot.powerKw ?: 0.0) > CHARGING_POWER_THRESHOLD_KW
        val moving = (snapshot.speedKmh ?: 0.0) > MOVING_SPEED_THRESHOLD_KMH
        return IternioIntervalPolicy.classify(charging = charging, parked = !moving && !charging)
    }

    private suspend fun readConfig(): Result<Config> {
        val url = settingsRepository.getString(
            SettingsRepository.KEY_CLOUD_SYNC_URL,
            SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
        ).trim().ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
        if (url.isBlank()) return Result.failure(IllegalArgumentException("Endpoint URL пустой"))
        if (!url.startsWith("https://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Endpoint должен начинаться с https://"))
        }
        val vehicleId = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "").trim()
        if (vehicleId.isBlank()) return Result.failure(IllegalArgumentException("Укажите имя авто"))
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
        val activeBatchMode: Boolean,
        val activeSample: Boolean,
    )

    private companion object {
        const val MAX_QUEUE_ROWS = 1000
        const val MAX_BATCH_SIZE = 120
        const val ACTIVE_BATCH_SIZE = 15
        const val MOVING_SPEED_THRESHOLD_KMH = 0.5
        const val CHARGING_POWER_THRESHOLD_KW = 0.1
        const val MOVING_SAMPLE_INTERVAL_MS = 1_000L
        const val CHARGING_SAMPLE_INTERVAL_MS = 1_000L
        const val ACTIVE_FLUSH_INTERVAL_MS = 15_000L
        /** Parked online heartbeat for VoltFlow live status (aligned with Iternio PARKED cadence). */
        const val PARKED_CLOUD_HEARTBEAT_MS = 30_000L
        /** Disabled while parked heartbeat is 30s — unchanged SOC should still refresh VoltFlow status. */
        const val IDLE_UNCHANGED_SKIP_CYCLES = 0
    }
}
