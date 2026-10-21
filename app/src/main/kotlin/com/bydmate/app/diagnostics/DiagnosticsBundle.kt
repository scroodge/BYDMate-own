package com.bydmate.app.diagnostics

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Pure parts of «Отправить диагностику»: bundle layout, redaction, endpoint and response
 * mapping. [DiagnosticsReporter] gathers the inputs and does the I/O.
 *
 * The bundle goes to VoltFlow (`POST /api/bydmate/diagnostics`), which relays it to the
 * developer's Telegram chat; the bot token never ships in the APK. Contract:
 * EvAcChargeTimer `supabase/VOLTFLOW_MATE_API.md` → Diagnostics upload.
 */
object DiagnosticsBundle {
    /** Server cap is 4 MB gzip; stay well under it. */
    const val MAX_GZIP_BYTES = 3_500_000
    const val MAX_COMMENT_CHARS = 500

    fun build(sections: List<Pair<String, String?>>): String = buildString {
        for ((title, body) in sections) {
            append("\n===== ").append(title).append(" =====\n")
            append(body?.takeIf { it.isNotBlank() } ?: "(empty)").append('\n')
        }
    }

    private const val REDACTED = "<redacted>"
    private val RULES: List<Pair<Regex, String>> = listOf(
        // Secrets first, so a later rule cannot leave half a key behind.
        Regex("""(?i)(x-api-key\s*[:=]\s*)\S+""") to "$1$REDACTED",
        Regex("""(?i)(\bapi[_-]?key["']?\s*[:=]\s*["']?)[^\s"',}]+""") to "$1$REDACTED",
        Regex("""(?i)(\bbearer\s+)[A-Za-z0-9._~+/=-]+""") to "$1$REDACTED",
        Regex("""\b\d{6,12}:[A-Za-z0-9_-]{30,}\b""") to "<bot-token>",
        Regex("""(?i)(\b(?:link[_-]?code|token|password|secret)["']?\s*[:=]\s*["']?)[^\s"',}]+""") to "$1$REDACTED",
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        // GPS is not needed for diagnosis (owner, 2026-10-21): drop every coordinate form.
        Regex("""(?i)(\b(?:lat|lon|lng|latitude|longitude)["']?\s*[:=]\s*["']?)-?\d+(?:\.\d+)?""") to "$1<gps>",
        Regex("""(?i)\bgeo:\S*""") to "geo:<gps>",
        Regex("""-?\d{1,3}\.\d{4,}\s*[,;]\s*-?\d{1,3}\.\d{4,}""") to "<gps>",
        // VIN: 17 chars, no I/O/Q. Keep the last 6 so one car's reports can still be matched.
        Regex("""\b[A-HJ-NPR-Z0-9]{11}([A-HJ-NPR-Z0-9]{6})\b""") to "VIN…$1",
    )

    /**
     * Same rules as the server's `redactDiagnosticsText` (EvAcChargeTimer
     * `src/lib/voltflowmate/diagnostics.ts`) — change both together. The server re-applies its
     * copy, so this one keeps secrets off the wire, not just out of the chat.
     */
    fun redact(text: String): String =
        RULES.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** `…/api/bydmate/telemetry` → `…/api/bydmate/diagnostics`, as the command poller derives its URL. */
    fun urlFromTelemetry(telemetryUrl: String): String? {
        val trimmed = telemetryUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/telemetry") -> trimmed.removeSuffix("/telemetry") + "/diagnostics"
            trimmed.contains("/api/bydmate") -> trimmed.substringBeforeLast('/') + "/diagnostics"
            else -> null
        }
    }

    /** Percent-encoded comment for the `X-Diagnostics-Comment` header (headers are ASCII). */
    fun encodeComment(comment: String?): String? {
        val trimmed = comment?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_COMMENT_CHARS)
        if (trimmed.isNullOrEmpty()) return null
        return java.net.URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
    }

    enum class Outcome { SENT, NOT_LINKED, TOO_MANY, TOO_BIG, NOT_CONFIGURED, FAILED }

    fun outcomeFor(httpCode: Int): Outcome = when (httpCode) {
        in 200..299 -> Outcome.SENT
        401 -> Outcome.NOT_LINKED
        413 -> Outcome.TOO_BIG
        429 -> Outcome.TOO_MANY
        503 -> Outcome.NOT_CONFIGURED
        else -> Outcome.FAILED
    }

    /** The last [maxChars] of [text], cut at a line boundary. */
    fun tail(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.substring(text.length - maxChars)
        val firstBreak = cut.indexOf('\n')
        return if (firstBreak in 0 until cut.length - 1) cut.substring(firstBreak + 1) else cut
    }
}
