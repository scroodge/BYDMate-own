package com.bydmate.app.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Where to listen for command rings — the `doorbell` object of a `/api/bydmate/commands`
 * response (cloud migration `20261103180000_vehicle_command_doorbell`). Absent on servers
 * that predate it, which simply means: poll only.
 */
data class DoorbellConfig(val url: String, val apikey: String, val topic: String) {
    companion object {
        private val TOPIC = Regex("^cmd-[0-9a-f]{16,64}$")

        fun fromJson(json: JSONObject?): DoorbellConfig? {
            if (json == null) return null
            val url = json.optString("url").trim()
            val apikey = json.optString("apikey").trim()
            val topic = json.optString("topic").trim()
            if (!(url.startsWith("wss://") || url.startsWith("ws://"))) return null
            if (apikey.isEmpty() || !TOPIC.matches(topic)) return null
            return DoorbellConfig(url, apikey, topic)
        }
    }
}

/**
 * Lets a poll loop sleep until either its interval elapses or a ring arrives. Rings while
 * the loop is busy polling are kept (at most one), so a command inserted mid-poll is not lost.
 */
class CommandWake {
    private val permits = Semaphore(0)

    @Synchronized
    fun ring() {
        if (permits.availablePermits() == 0) permits.release()
    }

    /** @return true when woken by a ring, false when [timeoutMs] simply elapsed. */
    fun await(timeoutMs: Long): Boolean {
        val rung = permits.tryAcquire(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        permits.drainPermits()
        return rung
    }
}

/**
 * One Supabase Realtime (Phoenix v1 JSON) WebSocket, subscribed to this car's doorbell
 * topic. A `command` broadcast calls [onRing]; the caller then polls `/commands` as usual,
 * so validation, the allowlist and acks are unchanged and a forged ring is one extra poll.
 *
 * Plain JVM (no Android APIs): the shell-uid [com.bydmate.app.daemon.CommandDaemon] runs it
 * too. All state lives on one executor thread; socket callbacks hop onto it and are dropped
 * when they belong to an older connection. Reconnects back off 1 s → 60 s; each successful
 * join rings once, to pick up anything inserted while the socket was down.
 */
class CommandDoorbell(
    baseClient: OkHttpClient,
    private val log: (String) -> Unit,
    private val onRing: () -> Unit,
) {
    private val client = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Keeps the hotspot NAT mapping alive and fails a silently dead socket in ~20 s.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "voltflow-doorbell").apply { isDaemon = true }
    }

    private var config: DoorbellConfig? = null
    private var socket: WebSocket? = null
    private var generation = 0
    private var ref = 1
    private var backoffMs = MIN_BACKOFF_MS
    private var heartbeat: ScheduledFuture<*>? = null
    private var pendingReconnect: ScheduledFuture<*>? = null

    @Volatile
    var isJoined: Boolean = false
        private set

    /** Apply the latest server answer; `null` closes the socket. Cheap when unchanged. */
    fun update(newConfig: DoorbellConfig?) {
        executor.execute {
            if (newConfig == config) return@execute
            config = newConfig
            backoffMs = MIN_BACKOFF_MS
            pendingReconnect?.cancel(false)
            pendingReconnect = null
            closeSocket(if (newConfig == null) "disabled" else "config changed")
            if (newConfig != null) connect()
        }
    }

    fun stop() = update(null)

    private fun connect() {
        val cfg = config ?: return
        val gen = generation
        ref = 1
        val request = try {
            Request.Builder()
                .url("${cfg.url}?apikey=${cfg.apikey}&vsn=1.0.0")
                .build()
        } catch (e: IllegalArgumentException) {
            log("doorbell: bad url (${e.message})")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) =
                onSocket(gen) { join(webSocket, cfg) }

            override fun onMessage(webSocket: WebSocket, text: String) =
                onSocket(gen) { handle(text) }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onSocket(gen) { scheduleReconnect("closed $code $reason") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onSocket(gen) { scheduleReconnect("failure: ${t.javaClass.simpleName} ${t.message}") }
        })
    }

    private fun onSocket(gen: Int, block: () -> Unit) {
        executor.execute { if (gen == generation) block() }
    }

    private fun join(webSocket: WebSocket, cfg: DoorbellConfig) {
        webSocket.send(joinMessage(cfg))
        heartbeat?.cancel(false)
        heartbeat = executor.scheduleWithFixedDelay(
            { socket?.send(heartbeatMessage((++ref).toString())) },
            HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS,
        )
    }

    private fun handle(text: String) {
        when (val signal = parse(text)) {
            Signal.Joined -> {
                isJoined = true
                backoffMs = MIN_BACKOFF_MS
                log("doorbell joined ${topicTag()}")
                onRing() // catch anything inserted while we were disconnected
            }
            Signal.Ring -> {
                log("doorbell ring")
                onRing()
            }
            is Signal.Down -> scheduleReconnect(signal.reason)
            Signal.Ignore -> Unit
        }
    }

    private fun scheduleReconnect(reason: String) {
        closeSocket(reason)
        if (config == null || pendingReconnect != null) return
        val wait = backoffMs
        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        log("doorbell down ($reason), retry in ${wait / 1000}s")
        pendingReconnect = executor.schedule({
            pendingReconnect = null
            connect()
        }, wait, TimeUnit.MILLISECONDS)
    }

    private fun closeSocket(reason: String) {
        generation++ // stale callbacks from the old socket are ignored from here on
        heartbeat?.cancel(false)
        heartbeat = null
        socket?.let {
            it.close(1000, reason.take(100))
            it.cancel()
        }
        socket = null
        isJoined = false
    }

    /** First 8 chars of the topic: enough to tell cars apart in a log, not enough to ring one. */
    private fun topicTag(): String = config?.topic?.take(8)?.let { "$it…" } ?: "-"

    sealed class Signal {
        object Joined : Signal()
        object Ring : Signal()
        data class Down(val reason: String) : Signal()
        object Ignore : Signal()
    }

    companion object {
        const val RING_EVENT = "command"
        internal const val JOIN_REF = "1"
        private const val HEARTBEAT_MS = 25_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L

        internal fun joinMessage(cfg: DoorbellConfig): String {
            val payload = JSONObject()
                .put(
                    "config",
                    JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("postgres_changes", JSONArray())
                        .put("private", false),
                )
                .put("access_token", cfg.apikey)
            return JSONObject()
                .put("topic", "realtime:${cfg.topic}")
                .put("event", "phx_join")
                .put("payload", payload)
                .put("ref", JOIN_REF)
                .put("join_ref", JOIN_REF)
                .toString()
        }

        internal fun heartbeatMessage(ref: String): String =
            JSONObject()
                .put("topic", "phoenix")
                .put("event", "heartbeat")
                .put("payload", JSONObject())
                .put("ref", ref)
                .toString()

        /** Pure Phoenix-frame classifier, so the protocol handling is unit-testable. */
        internal fun parse(text: String): Signal {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return Signal.Ignore
            val payload = msg.optJSONObject("payload") ?: JSONObject()
            return when (msg.optString("event")) {
                "phx_reply" -> when {
                    msg.optString("ref") != JOIN_REF -> Signal.Ignore // heartbeat replies
                    payload.optString("status") == "ok" -> Signal.Joined
                    else -> Signal.Down("join refused: ${payload.optJSONObject("response")}")
                }
                "broadcast" ->
                    if (payload.optString("event") == RING_EVENT) Signal.Ring else Signal.Ignore
                "phx_error", "phx_close" -> Signal.Down(msg.optString("event"))
                "system" ->
                    if (payload.optString("status") == "error") {
                        Signal.Down("system: ${payload.optString("message")}")
                    } else {
                        Signal.Ignore
                    }
                else -> Signal.Ignore
            }
        }
    }
}
