package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loopback client for Overdrive [BydEventDaemon] (TCP 127.0.0.1:19878).
 */
@Singleton
class OverdriveEventClient @Inject constructor() {

    companion object {
        private const val TAG = "OverdriveEventClient"
        private const val HOST = "127.0.0.1"
        private const val PORT = 19878
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 3_000
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        sendCommand(JSONObject().put("cmd", "ping"))?.optString("message") == "pong"
    }

    suspend fun getBattery(): JSONObject? = withContext(Dispatchers.IO) {
        sendCommand(JSONObject().put("cmd", "getBattery"))
    }

    private fun sendCommand(command: JSONObject): JSONObject? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println(command.toString())
                val line = reader.readLine() ?: return null
                JSONObject(line)
            }
        } catch (e: Exception) {
            Log.d(TAG, "sendCommand failed: ${e.message}")
            null
        }
    }
}
