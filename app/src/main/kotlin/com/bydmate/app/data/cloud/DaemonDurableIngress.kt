package com.bydmate.app.data.cloud

import android.util.Base64
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

fun interface DaemonQueueIpc {
    /** True means the app committed this exact sample to canonical Room. */
    fun submit(record: DaemonSpoolRecord): Boolean
}

/** Spool-first by design: IPC is only an optimization after durable ingress exists. */
class DaemonDurableIngress(
    private val spool: DaemonTelemetrySpool,
    private val ipc: DaemonQueueIpc,
) {
    fun capture(payloadJson: String, deviceTime: String): File? {
        val record = DaemonSpoolRecord(UUID.randomUUID().toString(), deviceTime, payloadJson)
        val ready = spool.append(record.payloadJson, record.deviceTime, record.sampleId)
        if (runCatching { ipc.submit(record) }.getOrDefault(false)) {
            check(spool.deleteImported(ready)) { "IPC committed but spool cleanup failed" }
            return null
        }
        return ready
    }
}

/** Synchronous shell Binder client using Android's built-in `content call` bridge. */
class ShellContentQueueIpc : DaemonQueueIpc {
    override fun submit(record: DaemonSpoolRecord): Boolean {
        val encoded = Base64.encodeToString(DaemonTelemetrySpool.encode(record), Base64.NO_WRAP)
        val process = ProcessBuilder(
            "content",
            "call",
            "--uri", "content://${DaemonQueueBrokerProvider.AUTHORITY}",
            "--method", DaemonQueueBrokerProvider.METHOD_ENQUEUE,
            "--extra", "${DaemonQueueBrokerProvider.EXTRA_RECORD}:s:$encoded",
        ).redirectErrorStream(true).start()
        if (!process.waitFor(IPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            return false
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.exitValue() == 0 && output.contains("accepted=true")
    }

    companion object {
        private const val IPC_TIMEOUT_SECONDS = 10L
    }
}

data class ShellCameraSnapshotResult(val status: String, val result: Map<String, Any?>)

/** Shell daemon bridge for a UI-backed local FLV capture in the app process. */
class ShellCameraSnapshotIpc {
    fun capture(commandId: String): ShellCameraSnapshotResult? {
        val process = ProcessBuilder(
            "content",
            "call",
            "--uri", "content://${DaemonQueueBrokerProvider.AUTHORITY}",
            "--method", DaemonQueueBrokerProvider.METHOD_CAMERA_SNAPSHOT,
            "--extra", "${DaemonQueueBrokerProvider.EXTRA_COMMAND_ID}:s:$commandId",
        ).redirectErrorStream(true).start()
        if (!process.waitFor(35, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) return null
        val status = Regex("status=([^,}]+)").find(output)?.groupValues?.get(1) ?: return null
        val result = buildMap<String, Any?> {
            Regex("error=([^,}]+)").find(output)?.groupValues?.get(1)?.let { put("error", it) }
            Regex("storage_path=([^,}]+)").find(output)?.groupValues?.get(1)?.let { put("storage_path", it) }
            Regex("captured_at=([^,}]+)").find(output)?.groupValues?.get(1)?.let { put("captured_at", it) }
            Regex("source=([^,}]+)").find(output)?.groupValues?.get(1)?.let { put("source", it) }
            if (status == "done") put("view", "pano")
        }
        return ShellCameraSnapshotResult(status, result)
    }
}
