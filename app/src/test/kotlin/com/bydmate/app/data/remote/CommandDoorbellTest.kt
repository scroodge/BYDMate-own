package com.bydmate.app.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDoorbellTest {
    private val cfg = DoorbellConfig(
        url = "wss://abc.supabase.co/realtime/v1/websocket",
        apikey = "anon",
        topic = "cmd-0123456789abcdef0123456789abcdef",
    )

    @Test
    fun configParsesServerFieldAndRejectsJunk() {
        val ok = JSONObject()
            .put("url", cfg.url).put("apikey", cfg.apikey).put("topic", cfg.topic)
        assertEquals(cfg, DoorbellConfig.fromJson(ok))
        assertNull(DoorbellConfig.fromJson(null))
        assertNull(DoorbellConfig.fromJson(JSONObject(ok.toString()).put("url", "https://x")))
        assertNull(DoorbellConfig.fromJson(JSONObject(ok.toString()).put("topic", "realtime:other")))
        assertNull(DoorbellConfig.fromJson(JSONObject(ok.toString()).put("apikey", "")))
    }

    @Test
    fun joinsThePublicBroadcastTopicWithJoinRef() {
        val join = JSONObject(CommandDoorbell.joinMessage(cfg))
        assertEquals("realtime:${cfg.topic}", join.getString("topic"))
        assertEquals("phx_join", join.getString("event"))
        assertEquals(CommandDoorbell.JOIN_REF, join.getString("ref"))
        assertEquals(CommandDoorbell.JOIN_REF, join.getString("join_ref"))
        val config = join.getJSONObject("payload").getJSONObject("config")
        assertFalse(config.getBoolean("private"))
        assertFalse(config.getJSONObject("broadcast").getBoolean("self"))
    }

    @Test
    fun classifiesPhoenixFrames() {
        fun frame(event: String, payload: JSONObject, ref: String? = null) =
            JSONObject().put("topic", "realtime:${cfg.topic}").put("event", event)
                .put("payload", payload).put("ref", ref ?: JSONObject.NULL).toString()

        assertEquals(
            CommandDoorbell.Signal.Joined,
            CommandDoorbell.parse(frame("phx_reply", JSONObject().put("status", "ok"), "1")),
        )
        // A heartbeat reply has another ref and must not be read as a (re)join.
        assertEquals(
            CommandDoorbell.Signal.Ignore,
            CommandDoorbell.parse(frame("phx_reply", JSONObject().put("status", "ok"), "7")),
        )
        assertTrue(
            CommandDoorbell.parse(frame("phx_reply", JSONObject().put("status", "error"), "1"))
                is CommandDoorbell.Signal.Down,
        )
        val ring = JSONObject().put("type", "broadcast").put("event", "command")
            .put("payload", JSONObject().put("id", "c1"))
        assertEquals(CommandDoorbell.Signal.Ring, CommandDoorbell.parse(frame("broadcast", ring)))
        assertEquals(
            CommandDoorbell.Signal.Ignore,
            CommandDoorbell.parse(frame("broadcast", JSONObject().put("event", "other"))),
        )
        assertTrue(CommandDoorbell.parse(frame("phx_error", JSONObject())) is CommandDoorbell.Signal.Down)
        assertTrue(
            CommandDoorbell.parse(frame("system", JSONObject().put("status", "error")))
                is CommandDoorbell.Signal.Down,
        )
        assertEquals(CommandDoorbell.Signal.Ignore, CommandDoorbell.parse("not json"))
    }

    @Test
    fun wakeReturnsEarlyOnRingAndKeepsARingSentWhileBusy() {
        val wake = CommandWake()
        assertFalse(wake.await(10))

        wake.ring() // rung while the loop was polling
        wake.ring() // coalesced
        val started = System.nanoTime()
        assertTrue(wake.await(5_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        assertFalse("coalesced rings must not wake twice", wake.await(10))

        Thread { Thread.sleep(50); wake.ring() }.start()
        assertTrue(wake.await(5_000))
    }
}

class RingRetryTest {
    @org.junit.Test
    fun failedPollAfterARingRetriesFastThenFallsBack() {
        val retry = RingRetry(maxAttempts = 2, retryMs = 1_500L)
        retry.onWake(rung = true)
        org.junit.Assert.assertEquals(1_500L, retry.nextWaitMs(pollOk = false, normalWaitMs = 12_000L))
        retry.onWake(rung = false)
        org.junit.Assert.assertEquals(1_500L, retry.nextWaitMs(pollOk = false, normalWaitMs = 24_000L))
        retry.onWake(rung = false)
        org.junit.Assert.assertEquals(30_000L, retry.nextWaitMs(pollOk = false, normalWaitMs = 30_000L))
    }

    @org.junit.Test
    fun successOrNoRingKeepsTheNormalCadence() {
        val retry = RingRetry()
        org.junit.Assert.assertEquals(12_000L, retry.nextWaitMs(pollOk = false, normalWaitMs = 12_000L))
        retry.onWake(rung = true)
        org.junit.Assert.assertEquals(6_000L, retry.nextWaitMs(pollOk = true, normalWaitMs = 6_000L))
        org.junit.Assert.assertEquals(12_000L, retry.nextWaitMs(pollOk = false, normalWaitMs = 12_000L))
    }
}
