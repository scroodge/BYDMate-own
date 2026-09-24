package com.bydmate.app.data.remote

import android.util.Log
import com.bydmate.app.data.cloud.CloudLinkStatus
import com.bydmate.app.data.cloud.CloudTelemetrySender
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class VehicleCommandPoller @Inject constructor(
    private val controlClient: DiParsControlClient,
    private val settingsRepository: SettingsRepository,
    private val cloudTelemetrySender: CloudTelemetrySender,
) {
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "VehicleCmdPoll"
        private const val BASE_POLL_MS = CommandPollingCadence.BASE_POLL_MS
        private const val MAX_BACKOFF_MS = 30_000L

        /**
         * Next poll delay from the server's `poll_after_seconds`, or [BASE_POLL_MS] when the
         * field is absent (older server) or nonsensical. A suspended server uses the shared
         * 5-minute floor so this client can discover when commands are enabled again.
         *
         * Polling every 6s around the clock was the single largest source of cloud function
         * invocations, and while commands are suspended every one of those returns an empty
         * list. Letting the server set the cadence means it can be tuned — or restored to 6s
         * the moment commands come back — without shipping an APK.
         */
        internal fun pollIntervalMs(
            serverSeconds: Int,
            commandsEnabled: Boolean = true,
        ): Long = CommandPollingCadence.intervalMs(serverSeconds, commandsEnabled)

        /**
         * Car-profile capacity from the poll's `battery_capacity_kwh` (cloud
         * vehicle-battery-capacity.ts), or null when absent (older server, unresolved car)
         * or outside the 10–200 kWh band the web's range-estimate.ts also enforces. The app
         * has no settings screen and di+ reports no usable capacity, so this is the only
         * way the on-car AI Range can match the web instead of the 72.9 kWh default.
         */
        internal fun cloudBatteryCapacityKwh(json: JSONObject): Double? {
            if (!json.has("battery_capacity_kwh")) return null
            val kwh = json.optDouble("battery_capacity_kwh", Double.NaN)
            return kwh.takeIf { it.isFinite() && it >= 10.0 && it <= 200.0 }
        }
    }

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null
    private var backoffMs = BASE_POLL_MS

    @Volatile var latestData: DiParsData? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pollingJob = scope?.launch {
            Log.i(TAG, "Polling started")
            while (true) {
                val waited = pollOnce()
                delay(waited)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope?.cancel()
        scope = null
        backoffMs = BASE_POLL_MS
        Log.i(TAG, "Polling stopped")
    }

    val isRunning: Boolean get() = pollingJob?.isActive == true

    private suspend fun pollOnce(): Long {
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, SettingsRepository.DEFAULT_CLOUD_SYNC_ENABLED) != "true") {
            return BASE_POLL_MS
        }

        val telemetryUrl = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_URL, "").trim()
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, "").trim()
        val vehicleId = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "").trim()
        if (telemetryUrl.isBlank() || apiKey.isBlank() || vehicleId.isBlank()) {
            return BASE_POLL_MS
        }

        val commandsUrl = commandsUrlFromTelemetry(telemetryUrl) ?: return BASE_POLL_MS
        val ackUrl = "$commandsUrl/ack"
        val vehicleUid = settingsRepository.getOrCreateVehicleUid()

        return try {
            val httpUrl = commandsUrl.toHttpUrlOrNull() ?: return BASE_POLL_MS
            val request = Request.Builder()
                .url(httpUrl)
                .header("X-API-Key", apiKey)
                .header("X-Vehicle-Id", vehicleId)
                .header("X-Vehicle-Uid", vehicleUid)
                .header("X-App", "VoltFlow-Mate")
                .get()
                .build()

            val response = pollClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Poll HTTP ${response.code} url=$commandsUrl")
                CloudLinkStatus.onPoll(ok = false)
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                return backoffMs
            }

            backoffMs = BASE_POLL_MS
            CloudLinkStatus.onPoll(ok = true)
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            // Someone has the live view open: push status fast until this grant lapses.
            // Read before the empty-queue return — an idle command queue is the normal case
            // and must not skip the grant. Absent on older servers, which reads as 0 = off.
            cloudTelemetrySender.onLiveFastGranted(json.optInt("live_fast_seconds", 0))
            cloudTelemetrySender.onOfflineBufferCapGranted(
                if (json.has("offline_buffer_cap_bytes")) {
                    json.optLong("offline_buffer_cap_bytes").coerceAtLeast(0L)
                } else {
                    null
                },
            )
            cloudBatteryCapacityKwh(json)?.let { applyCloudBatteryCapacity(it) }
            val nextPollMs = pollIntervalMs(
                serverSeconds = json.optInt("poll_after_seconds", 0),
                commandsEnabled = json.optBoolean("commands_enabled", true),
            )
            val commands = json.optJSONArray("commands") ?: JSONArray()
            if (commands.length() == 0) return nextPollMs

            Log.i(TAG, "Received ${commands.length()} command(s)")
            val acks = JSONArray()

            for (i in 0 until commands.length()) {
                val cmd = commands.getJSONObject(i)
                val id = cmd.getString("id")
                val type = cmd.getString("type")
                val paramsJson = cmd.optJSONObject("params") ?: JSONObject()
                val params = jsonObjectToMap(paramsJson)
                val ack = executeCommand(id, type, params)
                acks.put(ack)
            }

            postAck(ackUrl, apiKey, vehicleId, vehicleUid, acks)
            nextPollMs
        } catch (e: Exception) {
            Log.e(TAG, "Poll error: ${e.message}")
            CloudLinkStatus.onPoll(ok = false)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            backoffMs
        }
    }

    /** Writes only on change — this runs on every poll. */
    private suspend fun applyCloudBatteryCapacity(kwh: Double) {
        val current = settingsRepository.getString(
            SettingsRepository.KEY_BATTERY_CAPACITY,
            SettingsRepository.DEFAULT_BATTERY_CAPACITY,
        ).toDoubleOrNull()
        if (current != null && kotlin.math.abs(current - kwh) < 0.001) return
        settingsRepository.setString(SettingsRepository.KEY_BATTERY_CAPACITY, kwh.toString())
        Log.i(TAG, "battery capacity from cloud car profile: $current -> $kwh kWh")
    }

    private suspend fun executeCommand(id: String, type: String, params: Map<String, Any?>): JSONObject {
        val data = latestData
        movementBlock(data)?.let { reason ->
            return ack(id, "rejected", mapOf("error" to reason))
        }
        CommandAllowlist.auxVoltageBlockReason(data)?.let { reason ->
            return ack(id, "rejected", mapOf("error" to reason))
        }

        return when (val built = CommandAllowlist.buildPhrase(type, params)) {
            is CommandAllowlist.BuildResult.Rejected ->
                ack(id, "rejected", mapOf("error" to built.reason))
            is CommandAllowlist.BuildResult.Ok -> {
                val sent = controlClient.sendAllowlistedPhrase(built.phrase)
                if (sent) {
                    ack(id, "done", mapOf("phrase" to built.phrase, "verified" to false))
                } else {
                    ack(id, "failed", mapOf("error" to "sendCmd_failed", "phrase" to built.phrase))
                }
            }
        }
    }

    private fun movementBlock(data: DiParsData?): String? {
        val reason = CommandAllowlist.movementBlockReason(data) ?: return null
        return reason
    }

    private fun ack(id: String, status: String, result: Map<String, Any?>): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("status", status)
            put("result", JSONObject(result))
        }
    }

    private fun postAck(url: String, apiKey: String, vehicleId: String, vehicleUid: String, acks: JSONArray) {
        try {
            val payload = JSONObject().put("acks", acks).toString()
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .header("X-Vehicle-Id", vehicleId)
                .header("X-Vehicle-Uid", vehicleUid)
                .header("X-App", "VoltFlow-Mate")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            pollClient.newCall(request).execute().use { response ->
                Log.i(TAG, "Ack HTTP ${response.code} (${acks.length()} items)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ack failed: ${e.message}")
        }
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.get(key)
        }
        return map
    }

    private fun commandsUrlFromTelemetry(telemetryUrl: String): String? {
        val trimmed = telemetryUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/telemetry") ->
                trimmed.removeSuffix("/telemetry") + "/commands"
            trimmed.contains("/api/bydmate") ->
                trimmed.substringBeforeLast('/') + "/commands"
            else -> null
        }
    }
}
