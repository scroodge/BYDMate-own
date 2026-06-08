package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiParsControlClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DiParsControl"
        private const val BASE_URL = "http://127.0.0.1:8988/api/sendCmd"
        private const val PREFIX = "迪加"
        private val BLOCKED_PATTERNS = listOf("发送CAN", "执行SHELL", "执行TSHELL", "下电")
    }

    /** Cloud path: phrase must come from [CommandAllowlist]. */
    suspend fun sendAllowlistedPhrase(phrase: String): Boolean = withContext(Dispatchers.IO) {
        if (BLOCKED_PATTERNS.any { phrase.contains(it) }) {
            Log.w(TAG, "BLOCKED dangerous phrase: '$phrase'")
            return@withContext false
        }
        sendRawPhrase(phrase)
    }

    /** Local automation only — not for remote/cloud commands. */
    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        if (BLOCKED_PATTERNS.any { command.contains(it) }) {
            Log.w(TAG, "BLOCKED dangerous command: '$command'")
            return@withContext false
        }
        sendRawPhrase(command)
    }

    private suspend fun sendRawPhrase(command: String): Boolean = withContext(Dispatchers.IO) {
        val fullCmd = "$PREFIX$command"
        val startTime = System.currentTimeMillis()
        try {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("cmd", fullCmd)
                .build()
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            val elapsed = System.currentTimeMillis() - startTime

            Log.i(TAG, "CMD '$fullCmd' → HTTP ${response.code} (${elapsed}ms) body=$body")

            val json = body?.let { JSONObject(it) }
            json?.optBoolean("success", false) ?: false
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "CMD '$fullCmd' → FAILED (${elapsed}ms): ${e.message}")
            false
        }
    }
}
