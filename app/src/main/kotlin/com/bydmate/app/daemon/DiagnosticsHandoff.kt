package com.bydmate.app.daemon

import java.io.File
import java.io.RandomAccessFile

/**
 * Hands the daemon's own log to the app for «Отправить диагностику».
 *
 * `/data/local/tmp` belongs to the shell uid, so the app does not read the log itself. It drops
 * [REQUEST] into its external files dir (the same tree the daemon already writes its telemetry
 * spool to); the daemon's status loop, which wakes every few seconds, writes [OUTPUT] next to it
 * and removes the request. The app waits a bounded time and sends without this section if the
 * daemon is down — which is itself a finding.
 *
 * File work only, no daemon state, so `DiagnosticsHandoffTest` drives it with temp dirs.
 */
internal object DiagnosticsHandoff {
    const val DIRECTORY =
        "/storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/diagnostics"
    const val REQUEST = "daemon_log.request"
    const val OUTPUT = "daemon_log.txt"
    const val DAEMON_LOG = "/data/local/tmp/voltflow_cmd_daemon.log"
    const val RELAUNCH_TS = AppSupervisor.RELAUNCH_TS_FILE

    /** Current log tail; the rotated `.1` gets a smaller share — it is older context. */
    const val MAX_CURRENT_BYTES = 1_500_000L
    const val MAX_PREVIOUS_BYTES = 500_000L

    /** Serves a pending request. Returns true when one was answered. Never throws. */
    fun serve(
        dir: File = File(DIRECTORY),
        log: File = File(DAEMON_LOG),
        relaunchTs: File = File(RELAUNCH_TS),
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val request = File(dir, REQUEST)
        if (!request.exists()) return false
        return try {
            val text = buildString {
                append("daemon handoff at ").append(nowMs).append('\n')
                append("relaunch_ts=").append(readSmall(relaunchTs) ?: "-").append('\n')
                section(File(log.path + ".1"), MAX_PREVIOUS_BYTES)
                section(log, MAX_CURRENT_BYTES)
            }
            val tmp = File(dir, "$OUTPUT.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, OUTPUT))) {
                File(dir, OUTPUT).writeText(text)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            request.delete()
        }
    }

    private fun StringBuilder.section(file: File, maxBytes: Long) {
        append("=== ").append(file.path)
        if (!file.isFile) {
            append(" (absent) ===\n")
            return
        }
        append(" (").append(file.length()).append(" bytes) ===\n")
        append(tail(file, maxBytes)).append('\n')
    }

    private fun readSmall(file: File): String? =
        runCatching { file.takeIf { it.isFile && it.length() < 256 }?.readText()?.trim() }.getOrNull()

    /** The last [maxBytes] of [file], starting at a line boundary when it had to cut. */
    internal fun tail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - maxBytes)
            val bytes = ByteArray(maxBytes.toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            val firstBreak = text.indexOf('\n')
            return if (firstBreak in 0 until text.length - 1) text.substring(firstBreak + 1) else text
        }
    }
}
