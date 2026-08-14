package com.bydmate.app.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class CloudSendResult {
    data class Success(val responseBody: String?) : CloudSendResult()
    data class RetryableFailure(val message: String) : CloudSendResult()
    data class NonRetryableFailure(val message: String) : CloudSendResult()
}

interface CloudTelemetryClientApi {
    suspend fun send(
        url: String,
        apiKey: String,
        vehicleId: String,
        payloadJson: String,
    ): CloudSendResult
}

@Singleton
class CloudTelemetryClient @Inject constructor(
    baseClient: OkHttpClient,
) : CloudTelemetryClientApi {
    private val httpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun send(
        url: String,
        apiKey: String,
        vehicleId: String,
        payloadJson: String,
    ): CloudSendResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", JSON.toString())
                .header("X-API-Key", apiKey)
                .header("X-Vehicle-Id", vehicleId)
                .header("X-App", "VoltFlow-Mate")
                .post(payloadJson.toRequestBody(JSON))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                classify(response.code, response.messageWithBody(responseBody), responseBody)
            }
        } catch (e: Exception) {
            CloudSendResult.RetryableFailure(e.javaClass.simpleName + ": " + (e.message ?: "network error"))
        }
    }

    private fun okhttp3.Response.messageWithBody(responseBody: String?): String {
        val body = responseBody?.take(300)?.trim()
        return if (body.isNullOrBlank()) {
            "HTTP $code"
        } else {
            "HTTP $code: $body"
        }
    }

    internal companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 4xx codes that describe the *conditions* of the request rather than the content of the
         * payload: a missing or expired API key (401), a key without access to this vehicle (403),
         * a wrong endpoint left in Settings (404), a server-side request timeout (408), a rate
         * limit (429).
         *
         * These must be retryable. The local DB is the source of truth for telemetry, and samples
         * rejected for any of these reasons are still valid and still deliverable once the operator
         * fixes Settings or the limit clears — so the rows stay queued. Classifying them as
         * non-retryable meant a car driving on a stale key destroyed every sample as fast as it
         * produced them: `flushQueue` marks a non-retryable batch finished, so the history was gone
         * with only a status line to show for it.
         *
         * Everything else in 400..499 is the server rejecting *this body* (400/413/415/422), which
         * no amount of retrying changes. Those rows still leave the send stream so a single
         * undeliverable row cannot block every later sample behind it — see the quarantine note in
         * [CloudTelemetrySender.flushQueue].
         */
        val RETRYABLE_CLIENT_CODES = setOf(401, 403, 404, 408, 429)

        /**
         * Status → result. Split out of [send] so the classification is unit-testable without a
         * live socket, and without adding MockWebServer to the test dependencies.
         */
        internal fun classify(code: Int, message: String, responseBody: String?): CloudSendResult =
            when (code) {
                in 200..299 -> CloudSendResult.Success(responseBody)
                in RETRYABLE_CLIENT_CODES -> CloudSendResult.RetryableFailure(message)
                in 400..499 -> CloudSendResult.NonRetryableFailure(message)
                else -> CloudSendResult.RetryableFailure(message)
            }
    }
}
