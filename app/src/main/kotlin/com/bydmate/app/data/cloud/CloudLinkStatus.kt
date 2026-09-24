package com.bydmate.app.data.cloud

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Raw link events, as last reported by the telemetry sender (up) and command poller (down). */
data class CloudLinkEvents(
    val enabled: Boolean = false,
    /** Null until the first upload outcome of this process. */
    val upOk: Boolean? = null,
    val lastUpOkMs: Long = 0L,
    val lastEventMs: Long = 0L,
    val queued: Int? = null,
    val waitingForWifi: Boolean = false,
    val configError: String? = null,
    /** Null until the first command poll outcome of this process. */
    val downOk: Boolean? = null,
)

enum class CloudLinkLevel {
    /** Cloud sync switched off. */
    DISABLED,
    /** Nothing heard yet, or nothing heard for a long time. */
    UNKNOWN,
    /** Uploads land and the poll answers. */
    OK,
    /** Uploads land but the command poll fails — the cloud is not answering back. */
    NO_DOWNLINK,
    /** Samples are buffering on the car (no network, Wi-Fi-only wait, short outage). */
    QUEUED,
    /** Settings are broken, or no upload has landed for a long time. */
    ERROR,
}

data class CloudLinkView(val level: CloudLinkLevel, val queued: Int?)

object CloudLinkClassifier {
    /** Failing this long without a single delivered batch is an error, not a blip. */
    const val ERROR_AFTER_MS = 10 * 60_000L
    /** No event at all this long means the loop is quiet — don't claim a live link. */
    const val SILENT_AFTER_MS = 15 * 60_000L

    fun classify(e: CloudLinkEvents, nowMs: Long): CloudLinkView {
        if (!e.enabled) return CloudLinkView(CloudLinkLevel.DISABLED, null)
        if (e.configError != null) return CloudLinkView(CloudLinkLevel.ERROR, e.queued)
        if (e.upOk == null && e.downOk == null) return CloudLinkView(CloudLinkLevel.UNKNOWN, e.queued)
        if (e.upOk == false) {
            if (e.waitingForWifi) return CloudLinkView(CloudLinkLevel.QUEUED, e.queued)
            val failingSince = if (e.lastUpOkMs == 0L) null else nowMs - e.lastUpOkMs
            val level = if (failingSince == null || failingSince > ERROR_AFTER_MS) {
                CloudLinkLevel.ERROR
            } else {
                CloudLinkLevel.QUEUED
            }
            return CloudLinkView(level, e.queued)
        }
        if (nowMs - e.lastEventMs > SILENT_AFTER_MS) return CloudLinkView(CloudLinkLevel.UNKNOWN, e.queued)
        if (e.downOk == false) return CloudLinkView(CloudLinkLevel.NO_DOWNLINK, e.queued)
        return CloudLinkView(CloudLinkLevel.OK, e.queued)
    }
}

/**
 * Process-wide cloud link state for the floating widget. Fed by CloudTelemetrySender
 * (uploads) and VehicleCommandPoller (the downlink); logs every level change so an
 * on-car test can see exactly when the indicator flipped.
 */
object CloudLinkStatus {
    private const val TAG = "CloudLink"

    private val _events = MutableStateFlow(CloudLinkEvents())
    val events: StateFlow<CloudLinkEvents> = _events
    private var lastLoggedLevel: CloudLinkLevel? = null

    fun setEnabled(enabled: Boolean) = mutate { it.copy(enabled = enabled) }

    fun onUpload(ok: Boolean, queued: Int?, waitingForWifi: Boolean = false, nowMs: Long = System.currentTimeMillis()) =
        mutate {
            it.copy(
                upOk = ok,
                lastUpOkMs = if (ok) nowMs else it.lastUpOkMs,
                lastEventMs = nowMs,
                queued = queued,
                waitingForWifi = waitingForWifi,
                configError = null,
            )
        }

    fun onConfigError(message: String) = mutate { it.copy(configError = message) }

    fun onPoll(ok: Boolean, nowMs: Long = System.currentTimeMillis()) =
        mutate { it.copy(downOk = ok, lastEventMs = nowMs) }

    @Synchronized
    private fun mutate(change: (CloudLinkEvents) -> CloudLinkEvents) {
        val next = change(_events.value)
        _events.value = next
        val view = CloudLinkClassifier.classify(next, System.currentTimeMillis())
        if (view.level != lastLoggedLevel) {
            Log.i(TAG, "link ${lastLoggedLevel ?: "—"} -> ${view.level} " +
                "(up=${next.upOk}, down=${next.downOk}, queued=${next.queued}, " +
                "wifiWait=${next.waitingForWifi}, configError=${next.configError})")
            lastLoggedLevel = view.level
        }
    }
}
