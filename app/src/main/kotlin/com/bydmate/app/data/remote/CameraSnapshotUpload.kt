package com.bydmate.app.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * `PUT /api/bydmate/camera-snapshots/{commandId}` — shared by the app ([CameraSnapshotService])
 * and the shell daemon, which has no Hilt graph. The cloud keeps one rolling `latest.jpg` per
 * vehicle and accepts JPEG up to 6 MiB.
 */
object CameraSnapshotUpload {
    const val SOURCE_DIRECT = "direct"
    const val SOURCE_DIPLUS = "diplus"

    /** Derives the upload URL from the configured telemetry URL; null when it is not ours. */
    fun urlFromTelemetry(telemetryUrl: String, commandId: String): String? {
        val base = telemetryUrl.trim().trimEnd('/')
        val apiBase = when {
            base.endsWith("/telemetry") -> base.removeSuffix("/telemetry")
            base.contains("/api/bydmate") -> base.substringBeforeLast('/')
            else -> return null
        }
        return "$apiBase/camera-snapshots/$commandId"
    }

    /** Uploads [jpeg]; the result is the command ack (`done` with `storage_path`, or `failed`). */
    fun put(
        http: OkHttpClient,
        url: String,
        apiKey: String,
        vehicleId: String,
        vehicleUid: String?,
        app: String,
        jpeg: ByteArray,
        source: String,
    ): CameraSnapshotResult {
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .header("X-Vehicle-Id", vehicleId)
            .apply { vehicleUid?.takeIf { it.isNotBlank() }?.let { header("X-Vehicle-Uid", it) } }
            .header("X-App", app)
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
                        "source" to source,
                    ),
                )
            }
        }.getOrElse { CameraSnapshotResult("failed", mapOf("error" to "camera_upload_failed")) }
    }
}
