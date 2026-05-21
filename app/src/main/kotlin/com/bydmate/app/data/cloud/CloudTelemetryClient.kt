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
    object Success : CloudSendResult()
    data class RetryableFailure(val message: String) : CloudSendResult()
    data class NonRetryableFailure(val message: String) : CloudSendResult()
}

@Singleton
class CloudTelemetryClient @Inject constructor(
    baseClient: OkHttpClient,
) {
    private val httpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun send(
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
                when (response.code) {
                    in 200..299 -> CloudSendResult.Success
                    in 400..499 -> CloudSendResult.NonRetryableFailure("HTTP ${response.code}")
                    else -> CloudSendResult.RetryableFailure("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            CloudSendResult.RetryableFailure(e.javaClass.simpleName + ": " + (e.message ?: "network error"))
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
