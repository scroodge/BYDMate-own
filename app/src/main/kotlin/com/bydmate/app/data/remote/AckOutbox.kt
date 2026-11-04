package com.bydmate.app.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Command acks that have not reached the cloud yet.
 *
 * The server moves a command pending → sent when it hands it out, so a lost ack leaves the
 * row `sent`: the command ran on the car, but the web never learns it did. That is what
 * happened to a `camera_snapshot` on 2026-11-04 — the car's link flapped for a minute after
 * power-on and the single ack POST timed out. Acks are kept here and re-sent until the server
 * answers; the ack route updates by id, so a duplicate delivery is harmless.
 *
 * Plain JVM: the app's [VehicleCommandPoller] and the shell-uid daemon both use it.
 */
class AckOutbox(
    private val maxItems: Int = 50,
    private val maxAgeMs: Long = 10 * 60_000L,
) {
    private class Entry(val ack: JSONObject, val queuedAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int @Synchronized get() = entries.size

    val isEmpty: Boolean get() = size == 0

    /**
     * Queues [newAcks] (null: just retry what is queued), then sends everything queued in one
     * batch. [send] returns the HTTP status, or null when the request itself failed. 2xx and
     * 4xx both settle the batch — a 4xx will not get better by retrying it.
     *
     * @return the status [send] returned, or null when nothing was sent or the request failed.
     */
    fun deliver(newAcks: JSONArray?, nowMs: Long, send: (JSONArray) -> Int?): Int? {
        val batch = synchronized(this) {
            if (newAcks != null) {
                for (i in 0 until newAcks.length()) {
                    val ack = newAcks.optJSONObject(i) ?: continue
                    val id = ack.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    entries.remove(id)
                    entries[id] = Entry(ack, nowMs)
                }
            }
            entries.values.removeAll { nowMs - it.queuedAtMs > maxAgeMs }
            while (entries.size > maxItems) entries.remove(entries.keys.first())
            if (entries.isEmpty()) return null
            JSONArray().also { arr -> entries.values.forEach { arr.put(it.ack) } }
        }
        val code = try {
            send(batch)
        } catch (e: Exception) {
            null
        }
        if (code != null && code in 200..499) {
            synchronized(this) {
                for (i in 0 until batch.length()) {
                    val ack = batch.getJSONObject(i)
                    val id = ack.getString("id")
                    // Replaced by a newer ack for the same id while sending: keep that one.
                    if (entries[id]?.ack === ack) entries.remove(id)
                }
            }
        }
        return code
    }

    companion object {
        /** Poll again this soon while acks are waiting, instead of the 6 s / 90 s cadence. */
        const val RETRY_MS = 3_000L
    }
}
