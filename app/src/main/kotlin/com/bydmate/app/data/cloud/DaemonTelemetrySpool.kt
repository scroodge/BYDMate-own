package com.bydmate.app.data.cloud

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32

/** A single immutable daemon sample, durable before either IPC or best-effort HTTP. */
data class DaemonSpoolRecord(
    val sampleId: String,
    val deviceTime: String,
    val payloadJson: String,
)

/**
 * Shell/app shared ingress journal. One record per file keeps recovery deterministic: `.open`
 * is never imported, while a complete fsynced record is atomically renamed to `.ready`.
 */
class DaemonTelemetrySpool(private val directory: File) {
    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create spool directory: $directory" }
        recoverCompleteOpenFiles()
    }

    /** Completes the tiny fsync→rename crash window; partial `.open` files remain for diagnosis. */
    private fun recoverCompleteOpenFiles() {
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".open") }
            ?.forEach { open ->
                val valid = runCatching {
                    DataInputStream(BufferedInputStream(FileInputStream(open))).use(::readRecord)
                }.isSuccess
                if (valid) {
                    val ready = File(directory, open.name.removeSuffix(".open") + READY_SUFFIX)
                    if (!ready.exists()) open.renameTo(ready)
                }
            }
    }

    fun append(
        payloadJson: String,
        deviceTime: String,
        sampleId: String = UUID.randomUUID().toString(),
    ): File {
        require(payloadJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES)
        require(SAMPLE_ID.matches(sampleId)) { "Invalid sample id" }
        require(deviceTime.isNotBlank() && deviceTime.length <= MAX_DEVICE_TIME_CHARS)
        val record = DaemonSpoolRecord(sampleId, deviceTime, payloadJson)
        val open = File(directory, "$sampleId.open")
        val ready = File(directory, "$sampleId.ready")
        FileOutputStream(open).use { stream ->
            DataOutputStream(BufferedOutputStream(stream)).use { out ->
                writeRecord(out, record)
                out.flush()
                stream.fd.sync()
            }
        }
        check(open.renameTo(ready)) { "Could not close daemon spool record" }
        return ready
    }

    fun readyFiles(): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(READY_SUFFIX) }
        ?.sortedBy(File::getName)
        .orEmpty()

    fun read(file: File): DaemonSpoolRecord {
        require(file.parentFile?.canonicalFile == directory.canonicalFile)
        require(file.name.endsWith(READY_SUFFIX))
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use(::readRecord)
    }

    fun deleteImported(file: File): Boolean = file.delete() || !file.exists()

    companion object {
        const val EXTERNAL_DIRECTORY =
            "/storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/telemetry/daemon-spool"
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_DEVICE_TIME_CHARS = 64
        private const val MAGIC = 0x56465332 // VFS2
        private const val VERSION = 1
        private const val READY_SUFFIX = ".ready"
        private val SAMPLE_ID = Regex("[0-9a-fA-F-]{36}")

        fun encode(record: DaemonSpoolRecord): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { writeRecord(it, record) }
            bytes.toByteArray()
        }

        fun decode(bytes: ByteArray): DaemonSpoolRecord {
            require(bytes.size <= MAX_PAYLOAD_BYTES + 256) { "Spool envelope too large" }
            return DataInputStream(ByteArrayInputStream(bytes)).use(::readRecord)
        }

        private fun writeRecord(out: DataOutputStream, record: DaemonSpoolRecord) {
            val id = record.sampleId.toByteArray(StandardCharsets.UTF_8)
            val time = record.deviceTime.toByteArray(StandardCharsets.UTF_8)
            val payload = record.payloadJson.toByteArray(StandardCharsets.UTF_8)
            val crc = CRC32().apply {
                update(id); update(time); update(payload)
            }.value
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(id.size); out.write(id)
            out.writeInt(time.size); out.write(time)
            out.writeInt(payload.size); out.write(payload)
            out.writeLong(crc)
        }

        private fun readRecord(input: DataInputStream): DaemonSpoolRecord {
            require(input.readInt() == MAGIC) { "Invalid spool magic" }
            require(input.readInt() == VERSION) { "Unsupported spool version" }
            val id = input.readBounded(36).toString(StandardCharsets.UTF_8)
            val time = input.readBounded(MAX_DEVICE_TIME_CHARS).toString(StandardCharsets.UTF_8)
            val payload = input.readBounded(MAX_PAYLOAD_BYTES).toString(StandardCharsets.UTF_8)
            val expected = input.readLong()
            require(input.read() == -1) { "Trailing spool bytes" }
            val actual = CRC32().apply {
                update(id.toByteArray(StandardCharsets.UTF_8))
                update(time.toByteArray(StandardCharsets.UTF_8))
                update(payload.toByteArray(StandardCharsets.UTF_8))
            }.value
            require(actual == expected) { "Spool checksum mismatch" }
            require(SAMPLE_ID.matches(id)) { "Invalid sample id" }
            return DaemonSpoolRecord(id, time, payload)
        }

        private fun DataInputStream.readBounded(max: Int): ByteArray {
            val length = readInt()
            require(length in 1..max) { "Invalid spool field length" }
            return ByteArray(length).also(::readFully)
        }
    }
}
