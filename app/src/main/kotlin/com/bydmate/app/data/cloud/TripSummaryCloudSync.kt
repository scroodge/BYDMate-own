package com.bydmate.app.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes locally imported energydata trip records (per-trip aggregates from the
 * car's own EC_database.db, no ADB needed) to VoltFlow's
 * POST /api/bydmate/trip-summaries, so cars without ADB still get trip and
 * consumption history in the cloud.
 *
 * Reuses the Cloud Sync settings (URL/API key/vehicle id) and only runs when
 * the data source is ENERGYDATA — ADB cars on the DiPlus source get cloud trips
 * from live telemetry instead, so this sender stays quiet there to avoid
 * double-reporting. The server upserts on (user, vehicle, started_at), so
 * re-sending after a lost ack is harmless; the local watermark
 * (max start_ts already acknowledged) only exists to keep payloads small.
 */
@Singleton
class TripSummaryCloudSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao,
    private val client: CloudTelemetryClientApi,
) {
    data class SyncResult(val sent: Int, val skipped: Int = 0, val error: String? = null)

    private val syncMutex = Mutex()
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }
    internal var wifiChecker: () -> Boolean = { isWifiConnected() }

    /** Never throws; failures are logged + stored for diagnostics and retried on the next sync. */
    suspend fun syncNewTrips(): SyncResult {
        if (!syncMutex.tryLock()) return SyncResult(sent = 0)
        return try {
            doSync()
        } catch (e: Exception) {
            Log.e(TAG, "syncNewTrips unexpected error", e)
            saveStatus("error: ${e.message ?: e.javaClass.simpleName}")
            SyncResult(sent = 0, error = e.message ?: e.toString())
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun doSync(): SyncResult {
        if (settingsRepository.getString(
                SettingsRepository.KEY_CLOUD_SYNC_ENABLED,
                SettingsRepository.DEFAULT_CLOUD_SYNC_ENABLED,
            ) != "true"
        ) {
            return SyncResult(sent = 0)
        }
        if (settingsRepository.getDataSource() != SettingsRepository.DataSource.ENERGYDATA) {
            return SyncResult(sent = 0)
        }

        val telemetryUrl = settingsRepository.getString(
            SettingsRepository.KEY_CLOUD_SYNC_URL,
            SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
        ).trim().ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
        if (!telemetryUrl.startsWith("https://", ignoreCase = true)) return SyncResult(sent = 0)
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, "").trim()
        val vehicleId = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "").trim()
        // Same hard gate as telemetry: nothing leaves the car until VoltFlow is
        // linked (API key) and the car is named.
        if (apiKey.isBlank() || vehicleId.isBlank()) return SyncResult(sent = 0)

        val watermarkMs = settingsRepository
            .getString(SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS, "0")
            .toLongOrNull() ?: 0L
        val trips = tripDao.getEnergydataTripsSince(watermarkMs)
        if (trips.isEmpty()) return SyncResult(sent = 0)

        val wifiOnly = settingsRepository
            .getString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, "false") == "true"
        if (wifiOnly && !wifiChecker()) {
            Log.d(TAG, "doSync: ${trips.size} trips pending; waiting for Wi-Fi")
            return SyncResult(sent = 0)
        }

        val url = tripSummariesUrl(telemetryUrl)
        var sent = 0
        var skipped = 0
        var inserted = 0
        var updated = 0
        // Trips arrive ascending by start_ts; the watermark only advances past a
        // trip once its batch is acknowledged (or it was skipped as unsendable),
        // so a failed POST retries the same records on the next sync.
        var pendingBatch = mutableListOf<JSONObject>()
        var batchMaxStartTs = watermarkMs

        suspend fun flushBatch(): String? {
            if (pendingBatch.isEmpty()) return null
            val payload = JSONArray(pendingBatch as List<JSONObject>).toString()
            val error = when (val result = client.send(url, apiKey, vehicleId, payload)) {
                is CloudSendResult.Success -> {
                    val ack = parseAck(result.responseBody)
                    if (ack == null) {
                        "unexpected response: ${result.responseBody?.take(120)}"
                    } else {
                        inserted += ack.inserted
                        updated += ack.updated
                        null
                    }
                }
                is CloudSendResult.NonRetryableFailure -> result.message
                is CloudSendResult.RetryableFailure -> result.message
            }
            if (error == null) {
                sent += pendingBatch.size
                pendingBatch = mutableListOf()
                settingsRepository.setString(
                    SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS,
                    batchMaxStartTs.toString(),
                )
            }
            return error
        }

        for (trip in trips) {
            val entry = toPayloadEntry(trip)
            if (entry == null) {
                skipped++
            } else {
                pendingBatch.add(entry)
            }
            batchMaxStartTs = maxOf(batchMaxStartTs, trip.startTs)
            if (pendingBatch.size >= MAX_BATCH_SIZE) {
                val error = flushBatch()
                if (error != null) return failed(sent, skipped, error)
            }
        }
        val error = flushBatch()
        if (error != null) return failed(sent, skipped, error)

        // Everything scanned was either acknowledged or unsendable — advance the
        // watermark past trailing skipped records too so they are not rescanned.
        settingsRepository.setString(
            SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS,
            batchMaxStartTs.toString(),
        )
        saveStatus("OK: sent $sent (ins $inserted, upd $updated), skipped $skipped")
        Log.i(TAG, "doSync: sent=$sent inserted=$inserted updated=$updated skipped=$skipped")
        return SyncResult(sent = sent, skipped = skipped)
    }

    private suspend fun failed(sent: Int, skipped: Int, error: String): SyncResult {
        Log.w(TAG, "doSync failed after sent=$sent: $error")
        saveStatus("error: $error (sent $sent)")
        return SyncResult(sent = sent, skipped = skipped, error = error)
    }

    /**
     * Maps a local energydata trip to the API element, or null if the record
     * can't be sent. The server validates the WHOLE batch (zod), so one
     * out-of-range element would reject all 300 — filter client-side with the
     * same limits. Zero-km records are idle drains, not trips; skip them so the
     * cloud trip list stays consistent with the telemetry-derived one.
     */
    private fun toPayloadEntry(trip: TripEntity): JSONObject? {
        val endTs = trip.endTs ?: return null
        val distanceKm = trip.distanceKm ?: return null
        val energyKwh = trip.kwhConsumed ?: return null
        val startSec = trip.startTs / 1000L
        val endSec = endTs / 1000L
        if (startSec <= 0 || endSec < startSec) return null
        if (distanceKm <= 0.0 || distanceKm > MAX_DISTANCE_KM) return null
        if (energyKwh < 0.0 || energyKwh > MAX_ENERGY_KWH) return null

        return JSONObject().apply {
            put("start_timestamp", startSec)
            put("end_timestamp", endSec)
            put("distance_km", distanceKm)
            put("energy_kwh", energyKwh)
            val durationSec = endSec - startSec
            if (durationSec in 0..MAX_DURATION_SEC) put("duration_seconds", durationSec)
            val fuel = trip.fuelLiters
            if (fuel != null && fuel > 0.0) put("fuel_kwh", fuel)
        }
    }

    private fun parseAck(responseBody: String?): Ack? {
        if (responseBody.isNullOrBlank()) return null
        return try {
            val json = JSONObject(responseBody)
            if (!json.optBoolean("ok", false)) return null
            Ack(inserted = json.optInt("inserted", 0), updated = json.optInt("updated", 0))
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun saveStatus(message: String) {
        settingsRepository.setString(SettingsRepository.KEY_TRIP_SUMMARY_SYNC_LAST_RESULT, message)
        settingsRepository.setString(SettingsRepository.KEY_TRIP_SUMMARY_SYNC_LAST_TS, nowProvider().toString())
    }

    private fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private data class Ack(val inserted: Int, val updated: Int)

    companion object {
        private const val TAG = "TripSummaryCloudSync"

        /** Server-side zod limits (EvAcChargeTimer src/lib/bydmate/trip-summary-payload.ts). */
        const val MAX_BATCH_SIZE = 300
        const val MAX_DISTANCE_KM = 2000.0
        const val MAX_ENERGY_KWH = 500.0
        const val MAX_DURATION_SEC = 24 * 60 * 60

        /** The trip-summaries endpoint lives next to the telemetry one. */
        fun tripSummariesUrl(telemetryUrl: String): String =
            telemetryUrl.trimEnd('/').removeSuffix("/telemetry") + "/trip-summaries"
    }
}
