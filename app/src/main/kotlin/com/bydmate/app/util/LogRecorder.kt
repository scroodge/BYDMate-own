package com.bydmate.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures this app's own logcat output to timestamped files the user can export.
 *
 * An app can always read its OWN log lines (its uid/pid) without the privileged
 * READ_LOGS permission, so this works on the head unit with no extra permission
 * and no ADB — the spawned `logcat` runs as our uid and only sees our own lines.
 * Process-wide singleton so a recording survives activity recreation.
 *
 * Persistence: `logcat -f` streams straight to a file on disk as lines arrive, so
 * a capture is never "only in RAM" — whatever was written survives process death
 * and reboot. Each [start] opens a NEW timestamped file and never deletes the
 * previous one, so a pre-crash session can't be wiped by starting a fresh capture
 * (only the oldest are pruned past [MAX_SESSIONS]). [exportToDownloads] writes all
 * kept sessions into one file, so the crash log is included regardless of how many
 * times Start was tapped afterwards.
 */
object LogRecorder {
    private const val LOG_DIR = "logs"
    private const val FILE_PREFIX = "vfm-log-"
    private const val MAX_SESSIONS = 10

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    @Volatile private var process: Process? = null

    private fun logsDir(context: Context): File =
        File(context.filesDir, LOG_DIR).apply { mkdirs() }

    /** Kept session files, oldest first (names sort chronologically). */
    private fun sessionFiles(context: Context): List<File> =
        logsDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** True once any session has captured something worth exporting. */
    fun hasLog(context: Context): Boolean =
        sessionFiles(context).any { it.length() > 0L }

    /**
     * Starts a fresh capture in a new timestamped file. Prunes the oldest sessions
     * beyond [MAX_SESSIONS] but never touches recent ones. Returns false if the
     * platform refuses to spawn logcat (some locked-down ROMs), leaving
     * [isRecording] false so the UI can report it.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (_isRecording.value) return true
        val dir = logsDir(context)
        // Keep room for the new file while retaining the most recent sessions.
        val existing = sessionFiles(context)
        if (existing.size >= MAX_SESSIONS) {
            existing.take(existing.size - (MAX_SESSIONS - 1)).forEach { it.delete() }
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$stamp.log")
        return try {
            // Best-effort clear of our own buffered lines; ignore if the ROM blocks -c.
            runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() }
            process = ProcessBuilder("logcat", "-v", "time", "-f", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            _isRecording.value = true
            true
        } catch (_: Exception) {
            process = null
            _isRecording.value = false
            false
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        _isRecording.value = false
    }

    /** Concatenates every kept session into [out], each preceded by a name header. */
    fun writeAllTo(context: Context, out: OutputStream) {
        sessionFiles(context).forEach { f ->
            out.write("\n===== ${f.name} =====\n".toByteArray())
            f.inputStream().use { it.copyTo(out) }
        }
    }

    /**
     * Copies all kept sessions into the public Downloads collection via MediaStore.
     * This is the head-unit fallback for [android.content.Intent.ACTION_CREATE_DOCUMENT]:
     * the BYD ROM ships a gutted DocumentsUI that registers no file-picker activity,
     * so SAF launches throw ActivityNotFoundException. MediaStore needs no picker and
     * no storage permission for the app's own new entry. Returns the public relative
     * path (e.g. "Download/vfm-log-….txt") on success, or null if there is nothing to
     * export or the write failed.
     */
    fun exportToDownloads(context: Context, displayName: String): String? {
        if (!hasLog(context)) return null
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { writeAllTo(context, it) } ?: return null
            "${Environment.DIRECTORY_DOWNLOADS}/$displayName"
        } catch (_: Exception) {
            null
        }
    }
}
