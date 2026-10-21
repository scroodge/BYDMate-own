package com.bydmate.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * «Отправить диагностику» pure parts. The redaction cases mirror the server's
 * `diagnostics.test.mjs` (EvAcChargeTimer) — the two rule sets must stay in step.
 */
class DiagnosticsBundleTest {

    @Test
    fun `secrets are redacted`() {
        val out = DiagnosticsBundle.redact(
            listOf(
                "X-API-Key: 9e18aae878a5debaa78ff8f9ea2bbe77",
                """{"api_key":"abc123def","token": "tkn-1"}""",
                "Authorization: Bearer eyJhbGciOi.payload.sig",
                "bot 1234567890:AAH3xYzAbCdEfGhIjKlMnOpQrStUvWxYz01 ok",
                "user someone@example.com logged in",
            ).joinToString("\n"),
        )
        for (secret in listOf("9e18aae8", "abc123def", "tkn-1", "eyJhbGciOi", "AAH3xYz", "someone@")) {
            assertFalse("leaked $secret", out.contains(secret))
        }
        assertTrue(out.contains("X-API-Key: <redacted>"))
        assertTrue(out.contains("<bot-token>"))
        assertTrue(out.contains("<email>"))
    }

    @Test
    fun `every GPS form is removed`() {
        val out = DiagnosticsBundle.redact(
            listOf(
                """{"lat": 53.902284, "lon": 27.561831}""",
                "location latitude=53.9 longitude=-27.56",
                "fix 53.902284, 27.561831 acc 5",
                "intent geo:53.9,27.56?q=home",
            ).joinToString("\n"),
        )
        assertFalse(out.contains("53.9"))
        assertFalse(out.contains("27.56"))
        assertTrue(out.contains(""""lat": <gps>"""))
        assertTrue(out.contains("latitude=<gps>"))
    }

    @Test
    fun `ordinary telemetry numbers survive`() {
        val line = "soc=99.7 power_kw=-17 odometer_km=15251.8 cell_delta_v=0.026 12:38:52"
        assertEquals(line, DiagnosticsBundle.redact(line))
    }

    @Test
    fun `a VIN keeps only its last 6 characters`() {
        assertEquals("vin=VIN…123456", DiagnosticsBundle.redact("vin=LGXCE4CB0P0123456"))
    }

    @Test
    fun `endpoint is derived from the telemetry url`() {
        assertEquals(
            "https://voltflow.life/api/bydmate/diagnostics",
            DiagnosticsBundle.urlFromTelemetry("https://voltflow.life/api/bydmate/telemetry/"),
        )
        assertNull(DiagnosticsBundle.urlFromTelemetry("https://example.com/other"))
    }

    @Test
    fun `comment is collapsed, capped and percent-encoded as ASCII`() {
        val encoded = DiagnosticsBundle.encodeComment("  нет   поездки\n12:38 ")!!
        assertTrue(encoded.all { it.code < 128 })
        assertEquals("нет поездки 12:38", java.net.URLDecoder.decode(encoded, "UTF-8"))
        assertNull(DiagnosticsBundle.encodeComment("   "))
        val long = DiagnosticsBundle.encodeComment("x".repeat(900))!!
        assertEquals(DiagnosticsBundle.MAX_COMMENT_CHARS, long.length)
    }

    @Test
    fun `server answers map to user-facing outcomes`() {
        assertEquals(DiagnosticsBundle.Outcome.SENT, DiagnosticsBundle.outcomeFor(200))
        assertEquals(DiagnosticsBundle.Outcome.NOT_LINKED, DiagnosticsBundle.outcomeFor(401))
        assertEquals(DiagnosticsBundle.Outcome.TOO_BIG, DiagnosticsBundle.outcomeFor(413))
        assertEquals(DiagnosticsBundle.Outcome.TOO_MANY, DiagnosticsBundle.outcomeFor(429))
        assertEquals(DiagnosticsBundle.Outcome.NOT_CONFIGURED, DiagnosticsBundle.outcomeFor(503))
        assertEquals(DiagnosticsBundle.Outcome.FAILED, DiagnosticsBundle.outcomeFor(502))
    }

    @Test
    fun `bundle has titled sections and round-trips through gzip`() {
        val text = DiagnosticsBundle.build(listOf("header" to "mate=0.6.2", "chain log" to null))
        assertTrue(text.contains("===== header =====\nmate=0.6.2"))
        assertTrue(text.contains("===== chain log =====\n(empty)"))
        val gz = DiagnosticsBundle.gzip(text)
        assertEquals(0x1f, gz[0].toInt() and 0xff)
        assertEquals(0x8b, gz[1].toInt() and 0xff)
        val back = GZIPInputStream(ByteArrayInputStream(gz)).readBytes().toString(Charsets.UTF_8)
        assertEquals(text, back)
    }

    @Test
    fun `tail keeps whole lines`() {
        assertEquals("ccc\nddd", DiagnosticsBundle.tail("aaa\nbbb\nccc\nddd", 9))
        assertEquals("short", DiagnosticsBundle.tail("short", 100))
    }
}
