package com.bydmate.app.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class VoltflowLinkResult {
    data class Success(
        val apiKey: String,
        val endpointUrl: String,
    ) : VoltflowLinkResult()

    data class Failure(val message: String) : VoltflowLinkResult()
}

@Singleton
class VoltflowLinkClient @Inject constructor(
    baseClient: OkHttpClient,
) {
    private val httpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun redeem(telemetryEndpointUrl: String, code: String): VoltflowLinkResult =
        withContext(Dispatchers.IO) {
            val redeemUrl = redeemUrlFromTelemetryEndpoint(telemetryEndpointUrl.trim())
            if (redeemUrl == null) {
                return@withContext VoltflowLinkResult.Failure("Invalid VoltFlow endpoint URL")
            }

            val digits = code.filter { it.isDigit() }
            if (digits.length != 6) {
                return@withContext VoltflowLinkResult.Failure("Enter the 6-digit code from VoltFlow")
            }

            try {
                val body = JSONObject().put("code", digits).toString()
                val request = Request.Builder()
                    .url(redeemUrl)
                    .header("Content-Type", JSON.toString())
                    .post(body.toRequestBody(JSON))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(responseBody) }.getOrNull()
                    val ok = json?.optBoolean("ok", false) == true
                    if (!response.isSuccessful || !ok) {
                        val error = json?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: "Link failed (${response.code})"
                        return@withContext VoltflowLinkResult.Failure(error)
                    }

                    val apiKey = json.optString("api_key").trim()
                    val endpointUrl = json.optString("endpoint_url").trim()
                    if (apiKey.isBlank() || endpointUrl.isBlank()) {
                        return@withContext VoltflowLinkResult.Failure("Incomplete response from VoltFlow")
                    }

                    VoltflowLinkResult.Success(apiKey = apiKey, endpointUrl = endpointUrl)
                }
            } catch (e: Exception) {
                VoltflowLinkResult.Failure(e.message ?: "Network error")
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun redeemUrlFromTelemetryEndpoint(telemetryUrl: String): String? {
            val trimmed = telemetryUrl.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return null
            return when {
                trimmed.endsWith("/api/bydmate/telemetry", ignoreCase = true) -> {
                    trimmed.removeSuffix("/api/bydmate/telemetry") +
                        "/api/bydmate/link-code/redeem"
                }
                trimmed.endsWith("/telemetry", ignoreCase = true) -> {
                    trimmed.removeSuffix("/telemetry") + "/link-code/redeem"
                }
                else -> null
            }
        }
    }
}
