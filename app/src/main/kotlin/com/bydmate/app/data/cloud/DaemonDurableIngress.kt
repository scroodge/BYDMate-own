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
