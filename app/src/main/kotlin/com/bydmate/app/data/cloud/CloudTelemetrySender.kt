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
    /** Last explicit DiPars ignition state; null means that DiPars did not report one yet. */
    @Volatile private var lastPowerOn: Boolean? = null
    @Volatile private var activeBatchStartedMs: Long = 0L
    @Volatile private var idleUnchangedCycles: Int = 0
    @Volatile private var lastIdleSoc: Int? = null
    @Volatile private var lastIdleCharging: Boolean? = null
    @Volatile private var lastIdlePowerKw: Double? = null
    @Volatile private var pendingFlushNow: Boolean = false
    @Volatile private var lastChargingBelowTail: Boolean = false
    private val cadence = CloudTelemetryCadence()
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    /** Fast path: queue a sample without blocking on HTTP flush. */
    suspend fun enqueue(snapshot: VehicleTelemetrySnapshot): Result<Unit> {
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, SettingsRepository.DEFAULT_CLOUD_SYNC_ENABLED) != "true") {
            return Result.success(Unit)
        }
        val config = readConfig().getOrElse { error ->
            saveStatus(ok = false, message = error.message ?: "Ошибка настроек")
            return Result.failure(error)
        }

        val now = nowProvider()
        queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)

        val omitGps = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, "false") == "true"
        val telemetryState = cadence.effectiveState(snapshot, now)
        val decision = decide(snapshot, now, telemetryState)
        if (decision.flushNow) {
            pendingFlushNow = true
        }
        if (decision.enqueue) {
            val payload = CloudTelemetryPayload.build(
                config.vehicleId,
                snapshot,
                omitGps = omitGps,
                telemetryState = telemetryState,
            )
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
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, SettingsRepository.DEFAULT_CLOUD_SYNC_ENABLED) != "true") {
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
            // Charging-bulk (<98% SOC) changes slowly: flush every 60s so the long charge
            // sends ~6-sample batches instead of ~4 tiny POSTs/min — ~4x fewer
            // charging-phase backend invocations + verify reads. Driving and the >=98%
            // balance tail stay at 15s (driving needs trip resolution; the tail samples
            // at 1Hz and trips the 15-sample batch flush anyway). Live status still lands
            // within the server's <=90s freshness target.
            if (lastCharging == true && lastChargingBelowTail) {
                CHARGING_BULK_FLUSH_INTERVAL_MS
            } else {
                ACTIVE_FLUSH_INTERVAL_MS
            }
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
        val drainAll = !activeBatchMode || unsentCount > BACKLOG_DRAIN_THRESHOLD
        val flushResult = flushQueue(config, now, batchSize, drainAll = drainAll)
        return if (flushResult.success) {
            pendingFlushNow = false
            if (activeBatchMode && queueDao.countUnsent() <= BACKLOG_DRAIN_THRESHOLD) {
                activeBatchStartedMs = 0L
            }
            val remaining = queueDao.countUnsent()
            val ack = flushResult.lastAck?.formatDiagnostics()
            val message = buildString {
                append("OK")
                if (!ack.isNullOrBlank()) append("; $ack")
                append("; queued $remaining")
            }
            saveStatus(ok = true, message = message, ack = ack)
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            Result.success(Unit)
        } else {
            if (activeBatchMode) activeBatchStartedMs = now
            val remaining = queueDao.countUnsent()
            val ack = flushResult.lastAck?.formatDiagnostics()
            val message = buildString {
                append("queued $remaining; waiting for retry")
                flushResult.retryReason?.let { append(" ($it)") }
                if (!ack.isNullOrBlank()) append("; $ack")
            }
            saveStatus(ok = false, message = message, ack = ack)
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
        val now = nowProvider()
        val telemetryState = cadence.effectiveState(snapshot, now)
        val payload = CloudTelemetryPayload.build(
            config.vehicleId,
            snapshot,
            omitGps = omitGps,
            telemetryState = telemetryState,
        )
        return when (val result = client.send(config.url, config.apiKey, config.vehicleId, payload)) {
            is CloudSendResult.Success -> {
                val ack = CloudTelemetryAckParser.parse(result.responseBody, sentCount = 1)
                val ackText = ack.formatDiagnostics()
                settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ACK, ackText)
                if (!ack.isFullyAcknowledged()) {
                    val reason = ack.parseError ?: ack.error ?: "incomplete ack"
                    saveStatus(ok = false, message = "Test HTTP OK; $ackText ($reason)")
                    return Result.failure(IllegalStateException(reason))
                }
                saveStatus(ok = true, message = "Test OK; $ackText", ack = ackText)
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

    private suspend fun flushQueue(
        config: Config,
        now: Long,
        batchSize: Int,
        drainAll: Boolean,
    ): FlushQueueResult {
        var lastAck: CloudTelemetryAck? = null
        while (true) {
            val pending = queueDao.getUnsent(batchSize)
            if (pending.isEmpty()) return FlushQueueResult(success = true, lastAck = lastAck)

            // The X-Vehicle-Id header must match the vehicle_id inside every sample of the
            // batch, or the server rejects the batch whole — good rows included. A queued row
            // carries the id it was recorded with, so rows enqueued before the user edited
            // their vehicle id in Settings would otherwise go out under the new id. Send one
            // batch per id instead of stamping the current id onto older bodies.
            val (batchVehicleId, items) = pending
                .groupBy { CloudTelemetryPayload.vehicleIdOf(it.payloadJson) ?: config.vehicleId }
                .entries.first()
                .let { it.key to it.value }

            val payload = if (items.size == 1) {
                items.first().payloadJson
            } else {
                CloudTelemetryPayload.buildBatch(items.map { it.payloadJson })
            }

            when (val result = client.send(config.url, config.apiKey, batchVehicleId, payload)) {
                is CloudSendResult.Success -> {
                    val ack = CloudTelemetryAckParser.parse(result.responseBody, sentCount = items.size)
                    lastAck = ack
                    if (ack.isFullyAcknowledged()) {
                        items.forEach { queueDao.markFinished(it.id, null, now) }
                        if (!drainAll) return FlushQueueResult(success = true, lastAck = ack)
                    } else {
                        val reason = ack.parseError ?: ack.error ?: "incomplete ack"
                        items.forEach { queueDao.markAttempt(it.id, reason) }
                        return FlushQueueResult(
                            success = false,
                            lastAck = ack,
                            retryReason = reason,
                        )
                    }
                }
                is CloudSendResult.NonRetryableFailure -> {
                    items.forEach { queueDao.markFinished(it.id, result.message, now) }
                    if (!drainAll) {
                        return FlushQueueResult(success = true, lastAck = lastAck, retryReason = result.message)
                    }
                }
                is CloudSendResult.RetryableFailure -> {
                    items.forEach { queueDao.markAttempt(it.id, result.message) }
                    return FlushQueueResult(
                        success = false,
                        lastAck = lastAck,
                        retryReason = result.message,
                    )
                }
            }
        }
    }

    private fun decide(
        snapshot: VehicleTelemetrySnapshot,
        now: Long,
        telemetryState: IternioIntervalPolicy.TelemetryState,
    ): QueueDecision {
        val moving = telemetryState == IternioIntervalPolicy.TelemetryState.DRIVING
        val charging = telemetryState == IternioIntervalPolicy.TelemetryState.CHARGING
        val active = moving || charging
        val gear = snapshot.diPlusData?.gear
        val speedKmh = snapshot.speedKmh ?: snapshot.diPlusData?.speed?.toDouble() ?: 0.0
        val powerOn = snapshot.diPlusData?.powerState?.let { it >= 1 }
        val previousMoving = lastMoving
        val previousCharging = lastCharging
        val previousGear = lastGear
        val previousPowerOn = lastPowerOn
        val gearChanged = previousGear != null && gear != null && previousGear != gear
        // A deliberate D → P → power-off is different from a brief P blip in traffic.
        // The drive latch must still keep the latter at 1 Hz, but when the next DiPars
        // read confirms ignition-off while the car remains stationary in P, flush the
        // just-queued final sample before BYD force-stops the app process.
        val parkedPowerOff = previousPowerOn == true && powerOn == false &&
            previousGear == 1 && gear == 1 &&
            speedKmh <= CloudTelemetryCadence.MOVING_SPEED_THRESHOLD_KMH
        val stateChanged = previousMoving?.let { it != moving } == true ||
            previousCharging?.let { it != charging } == true ||
            gearChanged ||
            (previousGear == null && gear != null && lastQueuedSampleMs > 0L)
        lastMoving = moving
        lastCharging = charging
        lastGear = gear
        if (powerOn != null) lastPowerOn = powerOn
        // Tracks charging-bulk vs the >=98% balance tail so flushPending can pick the
        // flush cadence (bulk flushes less often — see CHARGING_BULK_FLUSH_INTERVAL_MS).
        lastChargingBelowTail =
            charging && (snapshot.soc ?: 0) < CHARGING_TAIL_SOC_THRESHOLD_PERCENT

        val minSampleIntervalMs = when (telemetryState) {
            IternioIntervalPolicy.TelemetryState.DRIVING -> MOVING_SAMPLE_INTERVAL_MS
            IternioIntervalPolicy.TelemetryState.CHARGING ->
                if ((snapshot.soc ?: 0) >= CHARGING_TAIL_SOC_THRESHOLD_PERCENT) {
                    CHARGING_TAIL_SAMPLE_INTERVAL_MS
                } else {
                    CHARGING_SAMPLE_INTERVAL_MS
                }
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
            flushNow = parkedPowerOff || (gearChanged && !active) || (stateChanged && !activeTransition),
            activeBatchMode = active || previousCharging == true || previousMoving == true,
            activeSample = active,
        )
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

    private suspend fun saveStatus(ok: Boolean, message: String, ack: String? = null) {
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_OK, ok.toString())
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ERROR, message)
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_TS, System.currentTimeMillis().toString())
        if (ack != null) {
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ACK, ack)
        }
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

    private data class FlushQueueResult(
        val success: Boolean,
        val lastAck: CloudTelemetryAck? = null,
        val retryReason: String? = null,
    )

    private companion object {
        const val MAX_QUEUE_ROWS = 1000
        const val MAX_BATCH_SIZE = 120
        const val ACTIVE_BATCH_SIZE = 15
        const val BACKLOG_DRAIN_THRESHOLD = ACTIVE_BATCH_SIZE
        const val MOVING_SAMPLE_INTERVAL_MS = 1_000L
        // Charging changes slowly, so sample at 10s for the bulk of the charge to cut
        // stored telemetry volume ~10x. Above CHARGING_TAIL_SOC_THRESHOLD_PERCENT the
        // pack is balancing toward 100% and cell delta moves fast, so fall back to 1s
        // until the charge stops (state leaves CHARGING) to capture a precise tail.
        const val CHARGING_SAMPLE_INTERVAL_MS = 10_000L
        const val CHARGING_TAIL_SAMPLE_INTERVAL_MS = 1_000L
        const val CHARGING_TAIL_SOC_THRESHOLD_PERCENT = 98
        const val ACTIVE_FLUSH_INTERVAL_MS = 15_000L
        // Charging-bulk (<CHARGING_TAIL_SOC_THRESHOLD_PERCENT) flush cadence. Bulk SOC
        // moves slowly, so flushing every 60s instead of 15s cuts charging-phase POSTs
        // ~4x while staying within the server's <=90s live-status freshness target.
        const val CHARGING_BULK_FLUSH_INTERVAL_MS = 60_000L
        /** Parked online heartbeat for VoltFlow live status (aligned with Iternio PARKED cadence). */
        const val PARKED_CLOUD_HEARTBEAT_MS = 30_000L
        /** Disabled while parked heartbeat is 30s — unchanged SOC should still refresh VoltFlow status. */
        const val IDLE_UNCHANGED_SKIP_CYCLES = 0
    }
}
