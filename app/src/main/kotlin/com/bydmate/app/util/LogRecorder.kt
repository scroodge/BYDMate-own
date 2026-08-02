package com.bydmate.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Captures this app's own logcat output to a file the user can export.
 *
 * An app can always read its OWN log lines (its uid/pid) without the
 * privileged READ_LOGS permission, so this works on the head unit with no
 * extra permission and no ADB — the spawned `logcat` runs as our uid and
 * therefore only ever sees our own lines. Process-wide singleton so a
 * recording survives activity recreation (rotation, backgrounding).
 */
object LogRecorder {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    @Volatile private var process: Process? = null

    /** Single reused capture file in app-private storage. */
    fun logFile(context: Context): File = File(context.filesDir, "vfm-diagnostic.log")

    /** True once a session has captured something worth exporting. */
    fun hasLog(context: Context): Boolean = logFile(context).let { it.exists() && it.length() > 0L }

    /**
     * Starts a fresh capture. Clears the previous session file and the logcat
     * ring buffer so the export contains only this session. Returns false if
     * the platform refuses to spawn logcat (some locked-down ROMs), leaving
     * [isRecording] false so the UI can report it.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (_isRecording.value) return true
        val file = logFile(context)
        return try {
            // Best-effort clear of our own buffered lines; ignore if the ROM blocks -c.
            runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() }
            file.delete()
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
}
