package com.bydmate.app.data.remote

import android.util.Log
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
) {
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "VehicleCmdPoll"
        private const val BASE_POLL_MS = 6000L
        private const val MAX_BACKOFF_MS = 30_000L
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
        if (settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false") != "true") {
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

        return try {
            val httpUrl = commandsUrl.toHttpUrlOrNull() ?: return BASE_POLL_MS
            val request = Request.Builder()
                .url(httpUrl)
                .header("X-API-Key", apiKey)
                .header("X-Vehicle-Id", vehicleId)
                .header("X-App", "VoltFlow-Mate")
                .get()
                .build()

            val response = pollClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Poll HTTP ${response.code} url=$commandsUrl")
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                return backoffMs
            }

            backoffMs = BASE_POLL_MS
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val commands = json.optJSONArray("commands") ?: JSONArray()
            if (commands.length() == 0) return BASE_POLL_MS

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

            postAck(ackUrl, apiKey, vehicleId, acks)
            BASE_POLL_MS
        } catch (e: Exception) {
            Log.e(TAG, "Poll error: ${e.message}")
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            backoffMs
        }
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

    private fun postAck(url: String, apiKey: String, vehicleId: String, acks: JSONArray) {
        try {
            val payload = JSONObject().put("acks", acks).toString()
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .header("X-Vehicle-Id", vehicleId)
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
