package com.bydmate.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class CameraSnapshotResult(
    val status: String,
    val result: Map<String, Any?>,
)

/**
 * Captures one 1920×1080 JPEG from di+'s local 2×2 `pano` stream. di+ already hosts a
 * mpegts.js player at localhost; using that player avoids exposing the FLV stream to the
 * phone or adding a second native H.264/FLV implementation to Mate.
 */
@Singleton
class CameraSnapshotService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun captureAndUpload(commandId: String): CameraSnapshotResult {
        val telemetryUrl = settingsRepository
            .getString(SettingsRepository.KEY_CLOUD_SYNC_URL, "")
            .trim()
        val apiKey = settingsRepository
            .getString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, "")
            .trim()
        val vehicleId = settingsRepository
            .getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "")
            .trim()
        val uploadUrl = uploadUrlFromTelemetry(telemetryUrl, commandId)
        if (uploadUrl == null || apiKey.isBlank() || vehicleId.isBlank()) {
            return CameraSnapshotResult("failed", mapOf("error" to "camera_snapshot_not_configured"))
        }

        val jpeg = runCatching { capturePanoJpeg() }.getOrElse {
            return CameraSnapshotResult("failed", mapOf("error" to "camera_capture_failed"))
        }
        val request = Request.Builder()
            .url(uploadUrl)
            .header("X-API-Key", apiKey)
            .header("X-Vehicle-Id", vehicleId)
            .header("X-Vehicle-Uid", settingsRepository.getOrCreateVehicleUid())
            .header("X-App", "VoltFlow-Mate")
            .put(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use CameraSnapshotResult("failed", mapOf("error" to "camera_upload_http_${response.code}"))
                }
                val body = JSONObject(response.body?.string().orEmpty())
                if (!body.optBoolean("ok")) {
                    return@use CameraSnapshotResult("failed", mapOf("error" to "camera_upload_failed"))
                }
                CameraSnapshotResult(
                    "done",
                    mapOf(
                        "storage_path" to body.getString("storage_path"),
                        "captured_at" to body.getString("captured_at"),
                        "view" to "pano",
                    ),
                )
            }
        }.getOrElse { CameraSnapshotResult("failed", mapOf("error" to "camera_upload_failed")) }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun capturePanoJpeg(): ByteArray = withTimeout(CAPTURE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            var webView: WebView? = null

            fun finish(bytes: ByteArray?) {
                if (!finished.compareAndSet(false, true)) return
                main.post {
                    webView?.destroy()
                    if (bytes != null && !continuation.isCompleted) continuation.resume(bytes)
                    else if (!continuation.isCompleted) continuation.cancel()
                }
            }

            main.post {
                val web = WebView(context.applicationContext)
                webView = web
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.mediaPlaybackRequiresUserGesture = false
                web.addJavascriptInterface(object {
                    @JavascriptInterface fun complete(base64: String) {
                        if (base64.length > MAX_JPEG_BASE64_CHARS) return finish(null)
                        finish(runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull())
                    }
                    @JavascriptInterface fun failed() = finish(null)
                }, JS_BRIDGE)
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                }
                web.loadUrl(LOCAL_PLAYER_URL)
            }
            continuation.invokeOnCancellation { main.post { webView?.destroy() } }
        }
    }

    private fun uploadUrlFromTelemetry(telemetryUrl: String, commandId: String): String? {
        val base = telemetryUrl.trimEnd('/')
        val apiBase = when {
            base.endsWith("/telemetry") -> base.removeSuffix("/telemetry")
            base.contains("/api/bydmate") -> base.substringBeforeLast('/')
            else -> return null
        }
        return "$apiBase/camera-snapshots/$commandId"
    }

    private companion object {
        const val LOCAL_PLAYER_URL = "http://127.0.0.1:8988/live-player.html"
        const val JS_BRIDGE = "VoltFlowSnapshot"
        const val CAPTURE_TIMEOUT_MS = 30_000L
        // Six MiB JPEG plus Base64 expansion; kept in sync with the cloud upload limit.
        const val MAX_JPEG_BASE64_CHARS = 8_388_608
        val CAPTURE_SCRIPT = """
            (function () {
              const fail = () => window.$JS_BRIDGE.failed();
              const video = document.getElementById('live-video');
              const view = document.getElementById('view');
              const fps = document.getElementById('fps');
              const width = document.getElementById('width');
              const height = document.getElementById('height');
              const audio = document.getElementById('audio');
              const start = document.getElementById('start-btn');
              if (!video || !view || !start) return fail();
              const deadline = Date.now() + 15000;
              const startPano = () => {
                if (!Array.from(view.options).some((option) => option.value === 'pano')) {
                  if (Date.now() < deadline) return setTimeout(startPano, 200);
                  return fail();
                }
                view.value = 'pano';
                if (fps) fps.value = '2';
                if (width) width.value = '1920';
                if (height) height.value = '1080';
                if (audio) audio.checked = false;
                start.click();
                setTimeout(take, 800);
              };
              const take = () => {
                if (video.videoWidth > 0 && video.videoHeight > 0 && video.readyState >= 2) {
                  try {
                    const canvas = document.createElement('canvas');
                    canvas.width = video.videoWidth;
                    canvas.height = video.videoHeight;
                    canvas.getContext('2d').drawImage(video, 0, 0);
                    window.$JS_BRIDGE.complete(canvas.toDataURL('image/jpeg', 0.90).split(',')[1]);
                  } catch (_) { fail(); }
                } else if (Date.now() < deadline) {
                  setTimeout(take, 250);
                } else {
                  fail();
                }
              };
              startPano();
            })();
        """.trimIndent()
    }
}
