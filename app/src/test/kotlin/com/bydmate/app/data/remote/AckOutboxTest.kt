package com.bydmate.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AckOutboxTest {
    private fun ack(id: String, status: String = "done") =
        JSONObject().put("id", id).put("status", status).put("result", JSONObject())

    private fun acks(vararg ids: String) = JSONArray().also { arr -> ids.forEach { arr.put(ack(it)) } }

    private fun ids(batch: JSONArray) = (0 until batch.length()).map { batch.getJSONObject(it).getString("id") }

    @Test
    fun deliveredAcksLeaveTheOutbox() {
        val outbox = AckOutbox()
        val sent = mutableListOf<List<String>>()
        assertEquals(200, outbox.deliver(acks("a"), 0L) { sent += ids(it); 200 })
        assertTrue(outbox.isEmpty)
        assertEquals(listOf(listOf("a")), sent)
    }

    @Test
    fun failedAckIsResentWithTheNextBatch() {
        val outbox = AckOutbox()
        assertNull(outbox.deliver(acks("a"), 0L) { throw java.io.IOException("timeout") })
        assertEquals(1, outbox.size)
        assertNull(outbox.deliver(acks("b"), 1L) { null })
        assertEquals(2, outbox.size)
        val sent = mutableListOf<List<String>>()
        outbox.deliver(null, 2L) { sent += ids(it); 200 }
        assertEquals(listOf(listOf("a", "b")), sent)
        assertTrue(outbox.isEmpty)
    }

    @Test
    fun serverErrorKeepsAcksButClientErrorSettlesThem() {
        val outbox = AckOutbox()
        outbox.deliver(acks("a"), 0L) { 503 }
        assertEquals(1, outbox.size)
        outbox.deliver(null, 1L) { 400 }
        assertTrue(outbox.isEmpty)
    }

    @Test
    fun emptyOutboxSendsNothing() {
        var called = false
        assertNull(AckOutbox().deliver(null, 0L) { called = true; 200 })
        assertFalse(called)
    }

    @Test
    fun newerAckForSameIdReplacesTheOldOne() {
        val outbox = AckOutbox()
        outbox.deliver(JSONArray().put(ack("a", "failed")), 0L) { null }
        var statuses = emptyList<String>()
        outbox.deliver(JSONArray().put(ack("a", "done")), 1L) { batch ->
            statuses = (0 until batch.length()).map { batch.getJSONObject(it).getString("status") }
            200
        }
        assertEquals(listOf("done"), statuses)
    }

    @Test
    fun oldAndExcessAcksAreDropped() {
        val aged = AckOutbox(maxAgeMs = 1_000L)
        aged.deliver(acks("a"), 0L) { null }
        var called = false
        aged.deliver(null, 5_000L) { called = true; 200 }
        assertFalse(called)
        assertTrue(aged.isEmpty)

        val capped = AckOutbox(maxItems = 2)
        var sent = emptyList<String>()
        capped.deliver(acks("a", "b", "c"), 0L) { sent = ids(it); null }
        assertEquals(listOf("b", "c"), sent)
    }
}
