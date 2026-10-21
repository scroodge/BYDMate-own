package com.bydmate.app.service

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The service start/stop chain log (SharedPreferences, survives logcat rollover). Shown in
 * Settings and included in «Отправить диагностику» bundles.
 *
 * It used to be three copies (BootReceiver, ServiceStartWorker, TrackingService) keeping 20
 * `HH:mm:ss` lines — a day of quickboots pushed out the event being investigated, and a line
 * without a date could not be placed (car `cl`, 2026-10-21). One writer, [MAX_LINES], dated.
 */
object ChainLog {
    const val MAX_LINES = 200
    private const val TAG = "ChainLog"

    fun append(context: Context, entry: String) {
        try {
            val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
            val prefs = context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(BootReceiver.KEY_CHAIN_LOG, "") ?: ""
            prefs.edit().putString(BootReceiver.KEY_CHAIN_LOG, appended(existing, "$stamp $entry")).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append chain log: ${e.message}")
        }
    }

    /** [existing] plus [line], keeping the newest [maxLines]. Pure, for tests. */
    internal fun appended(existing: String, line: String, maxLines: Int = MAX_LINES): String {
        val lines = existing.lines().filter { it.isNotBlank() }.takeLast(maxLines - 1)
        return (lines + line).joinToString("\n")
    }

    fun read(context: Context): String? =
        context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BootReceiver.KEY_CHAIN_LOG, null)
            ?.takeIf { it.isNotBlank() }
}
