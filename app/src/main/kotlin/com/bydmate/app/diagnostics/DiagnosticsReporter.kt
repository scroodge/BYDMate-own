package com.bydmate.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.daemon.DiagnosticsHandoff
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.ChainLog
import com.bydmate.app.service.TrackingService
import com.bydmate.app.util.LogRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Отправить диагностику»: gathers what a field report needs — liveness, the cloud queue and its
 * backoff, the chain log, the daemon's log and our logcat — redacts it, and posts one gzip to
 * VoltFlow, which relays it to the developer. Only on the user's tap; nothing is retried.
 *
 * The questions it has to answer are the ones the car `cl` report (2026-10-21) could not: was
 * TrackingService alive, was the queue in backoff, did AppSupervisor relaunch the app.
 */
@Singleton
class DiagnosticsReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val queueDao: CloudSyncQueueDao,
    baseClient: OkHttpClient,
) {
    private val http = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun send(comment: String?): DiagnosticsBundle.Outcome = withContext(Dispatchers.IO) {
        val telemetryUrl = settings.getString(SettingsRepository.KEY_CLOUD_SYNC_URL, "").trim()
        val apiKey = settings.getString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, "").trim()
        val vehicleId = settings.getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "").trim()
        val url = DiagnosticsBundle.urlFromTelemetry(telemetryUrl)
        if (url == null || apiKey.isBlank() || vehicleId.isBlank()) {
            return@withContext DiagnosticsBundle.Outcome.NOT_LINKED
        }

        val text = DiagnosticsBundle.redact(DiagnosticsBundle.build(collect(vehicleId)))
        val body = DiagnosticsBundle.gzip(text)
        if (body.size > DiagnosticsBundle.MAX_GZIP_BYTES) return@withContext DiagnosticsBundle.Outcome.TOO_BIG

        try {
            val request = Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .header("X-Vehicle-Id", vehicleId)
                .header("X-Vehicle-Uid", settings.getOrCreateVehicleUid())
                .header("X-Mate-Version", BuildConfig.VERSION_NAME)
                .header("X-App", "VoltFlow-Mate")
                .apply { DiagnosticsBundle.encodeComment(comment)?.let { header("X-Diagnostics-Comment", it) } }
                .post(body.toRequestBody(GZIP))
                .build()
            http.newCall(request).execute().use { response ->
                Log.i(TAG, "diagnostics upload HTTP ${response.code} (${body.size / 1024} KB)")
                DiagnosticsBundle.outcomeFor(response.code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "diagnostics upload failed: ${e.javaClass.simpleName}")
            DiagnosticsBundle.Outcome.FAILED
        }
    }

    private suspend fun collect(vehicleId: String): List<Pair<String, String?>> {
        val daemonLog = requestDaemonLog()
        return listOf(
            "header" to header(vehicleId),
            "liveness" to liveness(),
            "cloud queue" to queue(),
            "chain log" to ChainLog.read(context),
            "daemon log" to (daemonLog ?: "(daemon did not answer within ${DAEMON_WAIT_MS / 1000}s — not running?)"),
            "logcat (this app)" to logcat(),
            "recorded log sessions" to recordedLog(),
        )
    }

    private fun header(vehicleId: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        return listOf(
            "mate=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "vehicle_id=$vehicleId",
            "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}",
            "time=${fmt.format(Date())} zone=${TimeZone.getDefault().id}",
            "uptime_s=${SystemClock.elapsedRealtime() / 1000} awake_s=${SystemClock.uptimeMillis() / 1000}",
        ).joinToString("\n")
    }

    private fun liveness(): String {
        val beacon = context.getExternalFilesDir(null)?.let { File(it, "voltflow_mate_heartbeat") }
        val beaconMs = beacon?.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val beaconAge = beaconMs?.let { "${(System.currentTimeMillis() - it) / 1000}s" } ?: "absent"
        return listOf(
            "tracking_service_running=${TrackingService.isRunning.value}",
            "app_alive_beacon_age=$beaconAge",
        ).joinToString("\n")
    }

    private suspend fun queue(): String {
        val keys = listOf(
            SettingsRepository.KEY_CLOUD_SYNC_ENABLED,
            SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY,
            SettingsRepository.KEY_CLOUD_SYNC_LAST_OK,
            SettingsRepository.KEY_CLOUD_SYNC_LAST_TS,
            SettingsRepository.KEY_CLOUD_SYNC_LAST_ERROR,
            SettingsRepository.KEY_CLOUD_SYNC_LAST_ACK,
            SettingsRepository.KEY_CLOUD_SYNC_FAILURE_COUNT,
            SettingsRepository.KEY_CLOUD_SYNC_NEXT_ATTEMPT_AT,
            SettingsRepository.KEY_CLOUD_SYNC_NEXT_DRAIN_AT,
            SettingsRepository.KEY_CLOUD_SYNC_OFFLINE_BUFFER_CAP_BYTES,
            SettingsRepository.KEY_CLOUD_SYNC_BUFFER_DIAGNOSTIC,
        )
        return buildString {
            append("unsent=").append(queueDao.countUnsent())
            append(" unsent_bytes=").append(queueDao.unsentPayloadBytes()).append('\n')
            append("oldest_unsent_captured_at=").append(queueDao.oldestUnsentCapturedAt() ?: "-").append('\n')
            append("newest_unsent_captured_at=").append(queueDao.newestUnsentCapturedAt() ?: "-").append('\n')
            append("now=").append(System.currentTimeMillis()).append('\n')
            for (key in keys) append(key).append('=').append(settings.getString(key, "")).append('\n')
        }
    }

    /** Asks the daemon (shell uid) for its log via [DiagnosticsHandoff]; null if it does not answer. */
    private suspend fun requestDaemonLog(): String? {
        val dir = context.getExternalFilesDir("diagnostics") ?: return null
        val output = File(dir, DiagnosticsHandoff.OUTPUT)
        output.delete()
        runCatching { File(dir, DiagnosticsHandoff.REQUEST).writeText(System.currentTimeMillis().toString()) }
            .onFailure { return null }
        val deadline = SystemClock.elapsedRealtime() + DAEMON_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (output.isFile && output.length() > 0) {
                delay(300) // let a rename-less fallback write finish
                return runCatching { output.readText() }.getOrNull().also { output.delete() }
            }
            delay(500)
        }
        File(dir, DiagnosticsHandoff.REQUEST).delete()
        return null
    }

    private fun logcat(): String? = runCatching {
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "6000").redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor(5, TimeUnit.SECONDS)
        DiagnosticsBundle.tail(text, MAX_SECTION_CHARS)
    }.getOrNull()

    private fun recordedLog(): String? = runCatching {
        if (!LogRecorder.hasLog(context)) return@runCatching null
        val out = ByteArrayOutputStream()
        LogRecorder.writeAllTo(context, out)
        DiagnosticsBundle.tail(out.toString(Charsets.UTF_8.name()), MAX_SECTION_CHARS)
    }.getOrNull()

    private companion object {
        const val TAG = "Diagnostics"
        const val DAEMON_WAIT_MS = 20_000L
        const val MAX_SECTION_CHARS = 1_500_000
        val GZIP = "application/gzip".toMediaType()
    }
}
