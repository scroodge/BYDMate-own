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
import android.util.Log
import com.bydmate.app.data.remote.camera.BmmCameraGrabber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
 * Captures one 2×2 surround-view JPEG and uploads it. First straight from BYD's camera stack
 * ([BmmCameraGrabber], 2560×1920, no di+). If that fails — another model, a linker namespace
 * that blocks the vendor JNI library in the app process — it falls back to one 1920×1080
 * frame from di+'s local `pano` stream through di+'s own mpegts.js player at localhost.
 * The ack's `source` says which path produced the photo.
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
        val uploadUrl = CameraSnapshotUpload.urlFromTelemetry(telemetryUrl, commandId)
        if (uploadUrl == null || apiKey.isBlank() || vehicleId.isBlank()) {
            return CameraSnapshotResult("failed", mapOf("error" to "camera_snapshot_not_configured"))
        }

        val direct = withContext(Dispatchers.IO) {
            BmmCameraGrabber.captureSurroundJpeg { Log.i(TAG, it) }
        }
        val jpeg = direct ?: runCatching { capturePanoJpeg() }.getOrElse {
            Log.w(TAG, "camera snapshot: di+ player capture failed too")
            return CameraSnapshotResult("failed", mapOf("error" to "camera_capture_failed"))
        }
        val source = if (direct != null) CameraSnapshotUpload.SOURCE_DIRECT else CameraSnapshotUpload.SOURCE_DIPLUS
        val result = withContext(Dispatchers.IO) {
            CameraSnapshotUpload.put(
                http = http,
                url = uploadUrl,
                apiKey = apiKey,
                vehicleId = vehicleId,
                vehicleUid = settingsRepository.getOrCreateVehicleUid(),
                app = "VoltFlow-Mate",
                jpeg = jpeg,
                source = source,
            )
        }
        Log.i(TAG, "camera snapshot: source=$source ${jpeg.size} B → ${result.status}")
        return result
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

    private companion object {
        const val TAG = "CameraSnapshot"
        const val LOCAL_PLAYER_URL = "http://127.0.0.1:8988/live-player.html"
        const val JS_BRIDGE = "VoltFlowSnapshot"
        const val CAPTURE_TIMEOUT_MS = 30_000L
        const val STREAM_BITRATE_BPS = 4_000_000
        // Six MiB JPEG plus Base64 expansion; kept in sync with the cloud upload limit.
        const val MAX_JPEG_BASE64_CHARS = 8_388_608
        val CAPTURE_SCRIPT = """
            (function () {
              const fail = () => window.$JS_BRIDGE.failed();
              const video = document.getElementById('live-video');
              const view = document.getElementById('view');
              const fps = document.getElementById('fps');
              const bitrate = document.getElementById('bitrate');
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
                // The player's default 737280 bps was sized for 1280x720, and di+ ignores
                // fps (it streams ~24 fps), so a 1080p key frame got ~7 KB and the photo
                // came out as blocks. 4 Mbit/s is di+'s ceiling (8 Mbit/s gave the same
                // frame sizes on `way`, 2026-11-04).
                if (bitrate) bitrate.value = '$STREAM_BITRATE_BPS';
                if (width) width.value = '1920';
                if (height) height.value = '1080';
                if (audio) audio.checked = false;
                start.click();
                // Take the very first decoded frame: a fresh stream opens on its key frame,
                // ~95 KB at 4 Mbit/s against ~22 KB for every later one. The old fixed 800 ms
                // wait landed ~20 frames in.
                setTimeout(take, 100);
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
                  setTimeout(take, 50);
                } else {
                  fail();
                }
              };
              startPano();
            })();
        """.trimIndent()
    }
}
