package com.bydmate.app.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.dao.HourlyRollupDao
import com.bydmate.app.data.local.dao.TripRollupDao
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random

@Singleton
class CloudTelemetrySender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val queueDao: CloudSyncQueueDao,
    private val hourlyDao: HourlyRollupDao,
    private val tripDao: TripRollupDao,
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
    @Volatile private var lastIdleGunState: Int? = null
    @Volatile private var lastIdle12v: Double? = null
    /**
     * Gear as of the last *full* parked sample. Distinct from [lastGear], which decide()
     * advances to the current gear on every call for transition detection and so can never
     * serve as a stable baseline to compare against.
     */
    @Volatile private var lastGearBaseline: Int? = null
    /** Device time of the last *full* parked sample; bounds how long a live_only run may last. */
    @Volatile private var lastFullParkedSampleMs: Long = 0L
    @Volatile private var pendingFlushNow: Boolean = false
    /**
     * Single live_only payload staged by a moving/charging transition, sent by the next
     * [flushPending] outside the batch queue. Charge start/stop otherwise waits out the 60s
     * charging-bulk / parked flush before the live snapshot moves, so the PWA status badge
     * lags a state change by up to a minute. The ping refreshes the snapshot within seconds
     * while the queued full sample stays the durable history record.
     */
    @Volatile private var pendingStatusPingPayload: String? = null
    /**
     * Device time until which the live view is known to be open, from the command poll's
     * `live_fast_seconds` grant. While in the future, status is pushed every
     * [LIVE_FAST_PING_INTERVAL_MS] instead of waiting out the 15-60s batch flush.
     *
     * Expiry-based on purpose: the car forgets on its own, so a closed tab, a crashed
     * browser or a dropped network can never strand the car in fast mode.
     */
    @Volatile private var liveFastUntilMs: Long = 0L
    @Volatile private var lastFastPingMs: Long = 0L
    @Volatile private var lastChargingBelowTail: Boolean = false
    @Volatile private var lastTelemetryState: IternioIntervalPolicy.TelemetryState? = null
    /**
     * Power and device time of the last sample folded into an hourly rollup, i.e. the last one
     * the server will have stored. The energy integration needs the previous reading to form an
     * interval; the server gets it by querying bydmate_telemetry_samples on every sample, which
     * is the lookup this phase removes. Held in memory only — a restart loses one interval, and
     * that interval's gap would exceed the 180s cap and be discarded server-side anyway.
     */
    @Volatile private var lastRollupPowerKw: Double? = null
    @Volatile private var lastRollupDeviceTimeMs: Long? = null
    /** The currently open client-tracked trip, or null. Lazily hydrated from Room (see [ensureTripHydrated]) so a process restart resumes the same trip instead of forking a new one. */
    @Volatile private var currentTripId: String? = null
    /** Vehicle id [currentTripId] was hydrated for; re-hydrate if it ever changes. */
    @Volatile private var tripHydratedForVehicle: String? = null
    /** Energy-integration continuity pair for the open trip, analogous to [lastRollupPowerKw]/[lastRollupDeviceTimeMs] but scoped to the trip so it never carries energy across a trip boundary. */
    @Volatile private var lastTripPowerKw: Double? = null
    @Volatile private var lastTripDeviceTimeMs: Long? = null
    @Volatile private var lastPersistedStatus: PersistedStatus? = null
    private val cadence = CloudTelemetryCadence()
    private val gpsCorridorFilter = GpsCorridorFilter()
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }
    internal var jitterFractionProvider: () -> Double = { Random.nextDouble() }

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
        ensureTripHydrated(config.vehicleId)

        val omitGps = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, "false") == "true"
        val telemetryState = cadence.effectiveState(snapshot, now)
        if (telemetryState != lastTelemetryState) {
            // Fresh drive (or leaving one): don't corridor-thin across the boundary —
            // the first point of a new leg must always be kept as the new anchor.
            gpsCorridorFilter.reset()
        }
        lastTelemetryState = telemetryState
        val decision = decide(snapshot, now, telemetryState)
        if (decision.flushNow) {
            pendingFlushNow = true
        }
        // A transition always pings; on top of that, while the live view is open we ping on
        // a fixed short interval so *every* status field (SOC, charge power, gear, gun) is
        // fresh within seconds, not just the moving/charging edges.
        val liveFastActive = now < liveFastUntilMs
        val fastPingDue = liveFastActive && now - lastFastPingMs >= LIVE_FAST_PING_INTERVAL_MS
        if (decision.statusPing || fastPingDue) {
            // live_only: the server's fast path only upserts the live snapshot, so the ping
            // never duplicates a history row — the queued full sample below is the record.
            pendingStatusPingPayload = CloudTelemetryPayload.build(
                config.vehicleId,
                snapshot,
                omitGps = omitGps,
                telemetryState = telemetryState,
                liveOnly = true,
            )
            lastFastPingMs = now
        }
        if (decision.enqueue) {
            val loc = snapshot.location
            val dropLocationForThinning = !omitGps &&
                telemetryState == IternioIntervalPolicy.TelemetryState.DRIVING &&
                loc != null &&
                !gpsCorridorFilter.shouldKeep(loc.lat, loc.lon, now)
            // A live_only sample is parked-heartbeat-only by construction, so it can never be part
            // of a trip; skip planning to avoid a no-op Room round trip.
            val tripPlan = if (!decision.liveOnly) planTrip(snapshot, telemetryState) else NO_TRIP_PLAN
            val payload = CloudTelemetryPayload.build(
                config.vehicleId,
                snapshot,
                omitGps = omitGps,
                telemetryState = telemetryState,
                dropLocationForThinning = dropLocationForThinning,
                liveOnly = decision.liveOnly,
                // A live_only sample never reaches the hourly rollup server-side, so there is
                // nothing to take over for it and no flag to set.
                clientHourly = !decision.liveOnly,
                tripId = tripPlan.tripId,
                clientTrip = tripPlan.clientTrip,
            )
            queueDao.insert(pendingQueueEntity(payload, now))
            if (!decision.liveOnly) {
                accumulateHourly(config.vehicleId, payload, now)
                applyTripPlan(config.vehicleId, tripPlan, payload, now)
            }
            lastQueuedSampleMs = now
            if (decision.activeSample && activeBatchStartedMs == 0L) {
                activeBatchStartedMs = now
            }
        }

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
        sendPendingStatusPing(config)
        val unsentCount = queueDao.countUnsent()
        if (unsentCount == 0) {
            pendingFlushNow = false
            return Result.success(Unit)
        }

        if (config.wifiOnly && !isWifiConnected()) {
            saveStatus(
                ok = false,
                message = "queued $unsentCount; waiting for Wi-Fi",
                isNetworkAttempt = false,
            )
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            return Result.success(Unit)
        }

        val nextAttemptAt = settingsRepository.getString(
            SettingsRepository.KEY_CLOUD_SYNC_NEXT_ATTEMPT_AT,
            "0",
        ).toLongOrNull() ?: 0L
        if (now < nextAttemptAt) return Result.success(Unit)

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
        val backlogMode = unsentCount > BACKLOG_DRAIN_THRESHOLD
        val batchSize = when {
            backlogMode -> BACKLOG_BATCH_SIZE
            activeBatchMode -> ACTIVE_BATCH_SIZE
            else -> MAX_BATCH_SIZE
        }
        val shouldFlush = unsentCount >= batchSize ||
            pendingFlushNow ||
            (unsentCount > 0 && intervalElapsed)

        if (!shouldFlush) {
            return Result.success(Unit)
        }

        if (backlogMode) {
            val nextDrainAt = settingsRepository.getString(
                SettingsRepository.KEY_CLOUD_SYNC_NEXT_DRAIN_AT,
                "0",
            ).toLongOrNull() ?: 0L
            if (now < nextDrainAt) return Result.success(Unit)
        }

        lastFlushAttemptMs = now
        val flushResult = flushQueue(config, now, batchSize)
        return if (flushResult.success) {
            clearRetryBackoff()
            if (backlogMode) {
                settingsRepository.setString(
                    SettingsRepository.KEY_CLOUD_SYNC_NEXT_DRAIN_AT,
                    (now + BACKLOG_TOKEN_REFILL_MS).toString(),
                )
            }
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
            saveStatus(ok = true, message = message, ack = ack, isNetworkAttempt = true)
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            hourlyDao.pruneCleanBefore(HourlyRollupAccumulator.hourStartOf(now - HOURLY_RETENTION_MS))
            tripDao.pruneCleanBefore(now - TRIP_RETENTION_MS)
            Result.success(Unit)
        } else {
            persistRetryBackoff(now, flushResult.retryAfterMs)
            if (activeBatchMode) activeBatchStartedMs = now
            val remaining = queueDao.countUnsent()
            val ack = flushResult.lastAck?.formatDiagnostics()
            val message = buildString {
                append("queued $remaining; waiting for retry")
                flushResult.retryReason?.let { append(" ($it)") }
                if (!ack.isNullOrBlank()) append("; $ack")
            }
            saveStatus(ok = false, message = message, ack = ack, isNetworkAttempt = true)
            queueDao.pruneToMaxRows(MAX_QUEUE_ROWS)
            Result.failure(IllegalStateException(message))
        }
    }

    /**
     * Fire-and-forget: a full prompt flush here was measured to net-DELAY delivery — it
     * drains the queue and resets activeBatchStartedMs, pushing back the bulk batch that
     * carries the 4 consecutive charging samples auto-start needs. The ping therefore must
     * not touch the queue, activeBatchStartedMs, lastFlushAttemptMs, or the saved sync
     * status; a lost ping costs nothing because the batch still delivers the transition.
     */
    private suspend fun sendPendingStatusPing(config: Config) {
        val payload = pendingStatusPingPayload ?: return
        pendingStatusPingPayload = null
        if (config.wifiOnly && !isWifiConnected()) return
        val result = client.send(config.url, config.apiKey, config.vehicleId, payload)
        // Renewal path for fast mode: once the live view is open these pings are the most
        // frequent thing the car sends, so reading the grant back off them keeps the window
        // alive without depending on the (now 60s) command poll.
        if (result is CloudSendResult.Success) {
            onLiveFastGranted(
                CloudTelemetryAckParser.parse(result.responseBody, sentCount = 1).liveFastSeconds,
            )
        }
        // Logged because this path is otherwise invisible: it writes no queue row and no
        // history row, so without this line a ping that never fired looks exactly like a
        // ping that fired and was ignored downstream.
        Log.i(TAG, "status ping ${if (result is CloudSendResult.Success) "ok" else "failed: $result"}")
    }

    /**
     * Grant (or extend) fast live-status mode. Called from the ~6s command poll; a grant of
     * 0 — the default on older servers and whenever nobody is watching — simply lets the
     * current window lapse rather than cancelling it early.
     */
    fun onLiveFastGranted(seconds: Int) {
        if (seconds <= 0) return
        liveFastUntilMs = nowProvider() + seconds * 1000L
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
                    saveStatus(
                        ok = false,
                        message = "Test HTTP OK; $ackText ($reason)",
                        isNetworkAttempt = true,
                    )
                    return Result.failure(IllegalStateException(reason))
                }
                saveStatus(
                    ok = true,
                    message = "Test OK; $ackText",
                    ack = ackText,
                    isNetworkAttempt = true,
                )
                Result.success(result.responseBody)
            }
            is CloudSendResult.NonRetryableFailure -> {
                saveStatus(ok = false, message = result.message, isNetworkAttempt = true)
                Result.failure(IllegalStateException(result.message))
            }
            is CloudSendResult.RetryableFailure -> {
                saveStatus(ok = false, message = result.message, isNetworkAttempt = true)
                Result.failure(IllegalStateException(result.message))
            }
        }
    }

    private suspend fun flushQueue(
        config: Config,
        now: Long,
        batchSize: Int,
    ): FlushQueueResult {
        val pending = queueDao.getUnsent(batchSize)
        if (pending.isEmpty()) return FlushQueueResult(success = true)

        // The X-Vehicle-Id header must match the vehicle_id inside every sample of the
        // batch, or the server rejects the batch whole — good rows included. A queued row
        // carries the id it was recorded with, so rows enqueued before the user edited
        // their vehicle id in Settings would otherwise go out under the new id. Send one
        // batch per id instead of stamping the current id onto older bodies.
        val (batchVehicleId, items) = pending
            .groupBy { CloudTelemetryPayload.vehicleIdOf(it.payloadJson) ?: config.vehicleId }
            .entries.first()
            .let { it.key to it.value }

        // Cumulative aggregates for every hour still owed to the server, for this batch's
        // vehicle. Re-sent in full each flush: the server replaces its row only when the
        // incoming sample_count is at least what it holds, so a retry is a no-op rather
        // than a double-count, and a block lost to a failed flush heals on the next one.
        val hourlyBlocks = hourlyDao.getDirty(MAX_HOURLY_BLOCKS)
            .filter { it.vehicleId == batchVehicleId }
        // At most one open trip per vehicle (single writer — the daemon never sets
        // client_trip), so this is normally the current trip plus maybe one just-closed
        // trip still settling.
        val tripBlocks = tripDao.getDirty(MAX_TRIP_BLOCKS)
            .filter { it.vehicleId == batchVehicleId }

        val payload = if (items.size == 1 && hourlyBlocks.isEmpty() && tripBlocks.isEmpty()) {
            items.first().payloadJson
        } else {
            CloudTelemetryPayload.buildBatch(
                items.map { it.payloadJson },
                hourlyBlocks.map { HourlyRollupAccumulator.toJson(it) },
                tripBlocks.map { TripRollupAccumulator.toJson(it) },
            )
        }

        return when (val result = client.send(config.url, config.apiKey, batchVehicleId, payload)) {
            is CloudSendResult.Success -> {
                val ack = CloudTelemetryAckParser.parse(result.responseBody, sentCount = items.size)
                // Entry path for fast mode: this is the batch flush (15s driving, 60s
                // parked/charging), so it is what bounds how long a car takes to notice
                // someone opened the live view now that the command poll idles at 60s.
                onLiveFastGranted(ack.liveFastSeconds)
                if (ack.isFullyAcknowledged()) {
                    items.forEach { queueDao.markFinished(it.id, null, now) }
                    // Guarded by sampleCount: if a sample folded into the hour while this
                    // flush was in flight, the row stays dirty and goes again next time.
                    hourlyBlocks.forEach { hourlyDao.markClean(it.vehicleId, it.hourStart, it.sampleCount) }
                    tripBlocks.forEach { tripDao.markClean(it.tripId, it.sampleCount) }
                    FlushQueueResult(success = true, lastAck = ack)
                } else {
                    val reason = ack.parseError ?: ack.error ?: "incomplete ack"
                    items.forEach { queueDao.markAttempt(it.id, reason) }
                    FlushQueueResult(
                        success = false,
                        lastAck = ack,
                        retryReason = reason,
                    )
                }
            }
            is CloudSendResult.NonRetryableFailure -> {
                // Quarantine, not delivery. This is now only reached when the server rejects
                // this *body* (400/413/415/422) — the operator-fixable and transient 4xx codes
                // are retryable, see CloudTelemetryClient.RETRYABLE_CLIENT_CODES. Setting
                // sentAt takes the row out of the FIFO send stream so one undeliverable row
                // cannot block every later sample; lastError != null is what distinguishes a
                // quarantined row from an acknowledged one, and the payload stays readable in
                // cloud_sync_queue until pruned. Follow-up for the never-lose goal: prune
                // acknowledged rows ahead of quarantined ones, since for a rejected body the
                // queue row is the only copy.
                items.forEach { queueDao.markFinished(it.id, result.message, now) }
                FlushQueueResult(success = true, retryReason = result.message)
            }
            is CloudSendResult.RetryableFailure -> {
                items.forEach { queueDao.markAttempt(it.id, result.message) }
                FlushQueueResult(
                    success = false,
                    retryReason = result.message,
                    retryAfterMs = result.retryAfterMs,
                )
            }
        }
    }

    private suspend fun persistRetryBackoff(now: Long, retryAfterMs: Long?) {
        val previousFailures = settingsRepository.getString(
            SettingsRepository.KEY_CLOUD_SYNC_FAILURE_COUNT,
            "0",
        ).toIntOrNull() ?: 0
        val failureCount = (previousFailures + 1).coerceAtMost(MAX_PERSISTED_FAILURE_COUNT)
        val delayMs = CloudTelemetryRetryPolicy.delayMs(
            failureCount = failureCount,
            retryAfterMs = retryAfterMs,
            randomFraction = jitterFractionProvider(),
        )
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_FAILURE_COUNT, failureCount.toString())
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_NEXT_ATTEMPT_AT, (now + delayMs).toString())
    }

    private suspend fun clearRetryBackoff() {
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_FAILURE_COUNT, "0")
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_NEXT_ATTEMPT_AT, "0")
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
        val movingChanged = previousMoving?.let { it != moving } == true
        val chargingChanged = previousCharging?.let { it != charging } == true
        val stateChanged = movingChanged || chargingChanged ||
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

        val gunState = snapshot.diPlusData?.chargeGunState
        val volts12 = snapshot.diPlusData?.voltage12v ?: snapshot.auxVoltageV
        // Parked + nothing material moved => refresh live state only; the server
        // skips the history/hourly/trip writes for these. Any material change
        // (SOC, gun, gear, 12V) falls through to a full sample, so history still
        // gets a row at every real transition.
        var liveOnly = false

        if (!active && enqueue && !stateChanged) {
            val soc = snapshot.soc?.toInt()
            val power = snapshot.powerKw ?: 0.0
            val unchanged = soc != null &&
                soc == lastIdleSoc &&
                charging == (lastIdleCharging ?: false) &&
                abs(power - (lastIdlePowerKw ?: 0.0)) < 0.05 &&
                gunState == lastIdleGunState &&
                gear == lastGearBaseline &&
                volts12Unchanged(volts12)
            // bydmate_phantom_drain_daily sums the gaps between consecutive parked
            // samples but discards any gap >= 6h. An unbounded live_only run over a
            // flat-SOC overnight park would collapse to one such gap and zero out
            // idle_hours, so force a full sample periodically to keep the gaps small.
            val runExpired = lastFullParkedSampleMs != 0L &&
                now - lastFullParkedSampleMs >= LIVE_ONLY_MAX_RUN_MS
            if (unchanged && !runExpired) {
                liveOnly = true
                idleUnchangedCycles += 1
                if (idleUnchangedCycles <= IDLE_UNCHANGED_SKIP_CYCLES) {
                    enqueue = false
                }
            } else {
                idleUnchangedCycles = 0
                lastIdleSoc = soc
                lastIdleCharging = charging
                lastIdlePowerKw = power
                lastIdleGunState = gunState
                lastGearBaseline = gear
                lastIdle12v = volts12
                lastFullParkedSampleMs = now
            }
        } else if (active || stateChanged) {
            idleUnchangedCycles = 0
            lastIdleSoc = snapshot.soc?.toInt()
            lastIdleCharging = charging
            lastIdlePowerKw = snapshot.powerKw ?: 0.0
            lastIdleGunState = gunState
            lastGearBaseline = gear
            lastIdle12v = volts12
            // Leaving parked (or a state change) restarts the live_only run budget.
            lastFullParkedSampleMs = if (active) 0L else now
        }

        val activeTransition = stateChanged && (active || previousCharging == true || previousMoving == true)
        val flushNow = parkedPowerOff || (gearChanged && !active) || (stateChanged && !activeTransition)
        return QueueDecision(
            enqueue = enqueue,
            flushNow = flushNow,
            activeBatchMode = active || previousCharging == true || previousMoving == true,
            activeSample = active,
            liveOnly = liveOnly,
            // Effective moving/charging edges only — a bare gear change during the drive
            // latch (a P blip at a red light) is not a status change and must not ping.
            // Transitions that already flushNow deliver promptly through the queue.
            statusPing = !flushNow && (movingChanged || chargingChanged),
        )
    }

    /**
     * Fold one stored sample into its hour's running aggregate. Reads the built payload rather
     * than the snapshot so the fields counted are exactly the ones the server would have seen.
     */
    private suspend fun accumulateHourly(vehicleId: String, payloadJson: String, now: Long) {
        val deviceTimeMs = HourlyRollupAccumulator.deviceTimeMsOf(payloadJson) ?: return
        val hourStart = HourlyRollupAccumulator.hourStartOf(deviceTimeMs)
        val updated = HourlyRollupAccumulator.fold(
            existing = hourlyDao.find(vehicleId, hourStart),
            vehicleId = vehicleId,
            payloadJson = payloadJson,
            deviceTimeMs = deviceTimeMs,
            previousPowerKw = lastRollupPowerKw,
            previousDeviceTimeMs = lastRollupDeviceTimeMs,
            now = now,
        )
        hourlyDao.upsert(updated)
        lastRollupPowerKw = HourlyRollupAccumulator.powerKwOf(payloadJson)
        lastRollupDeviceTimeMs = deviceTimeMs
    }

    /** Hydrate [currentTripId] from Room once per (vehicle, process lifetime), not on every call. */
    private suspend fun ensureTripHydrated(vehicleId: String) {
        if (tripHydratedForVehicle == vehicleId) return
        currentTripId = tripDao.findOpen(vehicleId)?.tripId
        tripHydratedForVehicle = vehicleId
    }

    /**
     * Decide this sample's trip membership before the payload is built (the flag has to be baked
     * into the sample itself), mirroring the server's own trigger set: opens on the confirmed
     * IDLE->DRIVING transition, closes on gear->P (same `<=5 km/h` guard the server's
     * `v_is_gear_p` uses) or charging-start. The 5-minute gap-detection close stays server-only,
     * per the plan doc, so it has no client-side equivalent here.
     */
    private fun planTrip(
        snapshot: VehicleTelemetrySnapshot,
        telemetryState: IternioIntervalPolicy.TelemetryState,
    ): TripPlan {
        val openTripId = currentTripId
        val gear = snapshot.diPlusData?.gear
        val speedKmh = snapshot.speedKmh ?: snapshot.diPlusData?.speed?.toDouble() ?: 0.0
        val isGearP = gear == 1 && speedKmh <= TRIP_CLOSE_GEAR_P_SPEED_THRESHOLD_KMH
        val isCharging = telemetryState == IternioIntervalPolicy.TelemetryState.CHARGING
        val isDriving = telemetryState == IternioIntervalPolicy.TelemetryState.DRIVING

        return when {
            openTripId == null && isDriving -> TripPlan(
                tripId = UUID.randomUUID().toString(),
                clientTrip = true,
                opening = true,
                closing = false,
            )
            // The closing sample itself doesn't join the trip (matches the server's early-return
            // close triggers, which insert no track point for it either) — only the marker fires.
            openTripId != null && (isGearP || isCharging) -> TripPlan(
                tripId = null,
                clientTrip = false,
                opening = false,
                closing = true,
            )
            openTripId != null -> TripPlan(
                tripId = openTripId,
                clientTrip = true,
                opening = false,
                closing = false,
            )
            else -> NO_TRIP_PLAN
        }
    }

    /** Apply [plan] to Room using the already-built [payloadJson], mirroring [accumulateHourly]. */
    private suspend fun applyTripPlan(vehicleId: String, plan: TripPlan, payloadJson: String, now: Long) {
        when {
            plan.opening -> {
                val tripId = requireNotNull(plan.tripId)
                val deviceTimeMs = HourlyRollupAccumulator.deviceTimeMsOf(payloadJson) ?: now
                val entity = TripRollupAccumulator.open(
                    vehicleId = vehicleId,
                    tripId = tripId,
                    startedAtMs = deviceTimeMs,
                    payloadJson = payloadJson,
                    deviceTimeMs = deviceTimeMs,
                    now = now,
                )
                tripDao.upsert(entity)
                currentTripId = tripId
                lastTripPowerKw = HourlyRollupAccumulator.powerKwOf(payloadJson)
                lastTripDeviceTimeMs = deviceTimeMs
            }
            plan.closing -> {
                val tripId = currentTripId
                if (tripId != null) {
                    tripDao.find(tripId)?.let { existing ->
                        if (existing.endedAt == null) {
                            tripDao.upsert(TripRollupAccumulator.close(existing, now))
                        }
                    }
                }
                currentTripId = null
                lastTripPowerKw = null
                lastTripDeviceTimeMs = null
            }
            plan.tripId != null -> {
                val existing = tripDao.find(plan.tripId) ?: return
                val deviceTimeMs = HourlyRollupAccumulator.deviceTimeMsOf(payloadJson) ?: now
                val updated = TripRollupAccumulator.fold(
                    existing = existing,
                    payloadJson = payloadJson,
                    deviceTimeMs = deviceTimeMs,
                    previousPowerKw = lastTripPowerKw,
                    previousDeviceTimeMs = lastTripDeviceTimeMs,
                    now = now,
                )
                tripDao.upsert(updated)
                lastTripPowerKw = HourlyRollupAccumulator.powerKwOf(payloadJson)
                lastTripDeviceTimeMs = deviceTimeMs
            }
        }
    }

    /**
     * Next-boot finalization marker: if a trip was left open by a process death mid-drive and
     * hasn't seen a sample in a while, close it using its own last known device time — nothing is
     * computed here, close is just a marker, same as the other two close triggers. A restart
     * within the window instead resumes the same trip via [ensureTripHydrated]. Safe to call on
     * every service start; a no-op when there's no stale open trip.
     */
    suspend fun finalizeStaleOpenTrip() {
        val config = readConfig().getOrNull() ?: return
        val now = nowProvider()
        val open = tripDao.findOpen(config.vehicleId) ?: return
        if (now - open.lastDeviceTime < TRIP_STALE_AFTER_MS) return
        tripDao.upsert(TripRollupAccumulator.close(open, now))
    }

    /**
     * 12V sags slowly while parked and D+ quantizes it, so compare against a baseline with a
     * tolerance rather than exactly — otherwise ordinary noise would defeat live_only. A null
     * on either side counts as changed so the sample falls back to a full one.
     */
    private fun volts12Unchanged(volts12: Double?): Boolean {
        val baseline = lastIdle12v ?: return false
        if (volts12 == null) return false
        return abs(volts12 - baseline) < LIVE_ONLY_12V_EPSILON_V
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

    /**
     * `last sync` describes a real HTTP attempt, not an in-memory telemetry tick. Persisting the
     * same waiting/configuration status once is enough for the UI and avoids three settings writes
     * per poll while Wi-Fi is unavailable.
     */
    private suspend fun saveStatus(
        ok: Boolean,
        message: String,
        ack: String? = null,
        isNetworkAttempt: Boolean = false,
    ) {
        val status = PersistedStatus(ok, message, ack)
        if (!isNetworkAttempt && status == lastPersistedStatus) return
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_OK, ok.toString())
        settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ERROR, message)
        if (isNetworkAttempt) {
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_TS, System.currentTimeMillis().toString())
        }
        if (ack != null) {
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ACK, ack)
        }
        lastPersistedStatus = status
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

    private data class PersistedStatus(
        val ok: Boolean,
        val message: String,
        val ack: String?,
    )

    private data class QueueDecision(
        val enqueue: Boolean,
        val flushNow: Boolean,
        val activeBatchMode: Boolean,
        val activeSample: Boolean,
        val liveOnly: Boolean = false,
        /** Stage an immediate queue-independent live_only POST (see [pendingStatusPingPayload]). */
        val statusPing: Boolean = false,
    )

    private data class FlushQueueResult(
        val success: Boolean,
        val lastAck: CloudTelemetryAck? = null,
        val retryReason: String? = null,
        val retryAfterMs: Long? = null,
    )

    private data class TripPlan(
        val tripId: String?,
        val clientTrip: Boolean,
        val opening: Boolean,
        val closing: Boolean,
    )

    private companion object {
        val NO_TRIP_PLAN = TripPlan(tripId = null, clientTrip = false, opening = false, closing = false)
        const val MAX_QUEUE_ROWS = 1000
        /**
         * Hour blocks per flush. Only the current hour is normally dirty; more than a handful
         * means a long offline stretch, and those settle across successive flushes.
         */
        const val MAX_HOURLY_BLOCKS = 12
        /** How long a settled hour is kept for debugging before it is pruned. */
        const val HOURLY_RETENTION_MS = 24 * 60 * 60 * 1000L
        /** At most one open trip per vehicle plus maybe one just-closed trip still settling. */
        const val MAX_TRIP_BLOCKS = 4
        /** How long a settled trip is kept for debugging before it is pruned. */
        const val TRIP_RETENTION_MS = 24 * 60 * 60 * 1000L
        /** Same guard the server's `v_is_gear_p` uses, so a coast-through-P at speed doesn't close. */
        const val TRIP_CLOSE_GEAR_P_SPEED_THRESHOLD_KMH = 5.0
        /**
         * Next-boot finalization threshold. Long enough that an ordinary service restart (Android
         * typically restarts within seconds) never falsely closes a live trip; short enough that an
         * orphaned trip from a real process death doesn't sit open indefinitely.
         */
        const val TRIP_STALE_AFTER_MS = 20 * 60 * 1000L
        const val MAX_BATCH_SIZE = 120
        const val ACTIVE_BATCH_SIZE = 15
        const val BACKLOG_DRAIN_THRESHOLD = ACTIVE_BATCH_SIZE
        const val BACKLOG_BATCH_SIZE = 300
        const val BACKLOG_TOKEN_REFILL_MS = 2_000L
        const val MAX_PERSISTED_FAILURE_COUNT = 30
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
        private const val TAG = "CloudTelemetrySender"

        const val PARKED_CLOUD_HEARTBEAT_MS = 30_000L

        /**
         * Status push interval while the live view is open. Chosen against the PWA's ~1s
         * Realtime debounce so an observed change lands inside the 2-5s the live view
         * promises, without pushing at the 1Hz poll rate.
         */
        const val LIVE_FAST_PING_INTERVAL_MS = 3_000L
        /**
         * Disabled: an unchanged parked heartbeat must still reach the server to refresh
         * VoltFlow live status. It now goes as a live_only sample, which refreshes the live
         * snapshot without the history/hourly/trip writes — so there is no reason to drop it.
         */
        const val IDLE_UNCHANGED_SKIP_CYCLES = 0
        /**
         * Longest a live_only run may last before a full parked sample is forced. Keeps the gap
         * between consecutive stored parked samples well under the 6h window
         * bydmate_phantom_drain_daily uses to accumulate idle time. At the 30s parked heartbeat
         * this still stores 4 rows/hour instead of 120.
         */
        const val LIVE_ONLY_MAX_RUN_MS = 15 * 60 * 1000L
        /** 12V drift below this is noise, not a state change worth a history row. */
        const val LIVE_ONLY_12V_EPSILON_V = 0.3
    }
}
