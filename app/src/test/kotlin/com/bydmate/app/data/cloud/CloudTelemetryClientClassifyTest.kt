package com.bydmate.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: the local DB is the source of truth for telemetry, so a queued row may only leave
 * the send stream when the server has either acknowledged it or rejected its content. Every 4xx
 * used to be non-retryable, which meant `flushQueue` marked the batch finished — a car driving on
 * an expired API key (401) or through a rate limit (429) destroyed its own history as fast as it
 * produced it, with nothing but a status line to show for it.
 *
 * Classification is a pure function so this can be asserted without a socket or MockWebServer.
 */
class CloudTelemetryClientClassifyTest {
    @Test
    fun `expired or wrong key stays queued`() {
        assertRetryable(401)
        assertRetryable(403)
    }

    @Test
    fun `rate limit stays queued`() {
        assertRetryable(429)
    }

    @Test
    fun `wrong endpoint and request timeout stay queued`() {
        // Both are fixed outside the car — in Settings, or by the server recovering — and the
        // samples remain valid meanwhile.
        assertRetryable(404)
        assertRetryable(408)
    }

    @Test
    fun `server rejecting the body is not retried`() {
        // Retrying an unparseable, oversized or unprocessable body would block every later
        // sample behind a row that can never succeed.
        assertNonRetryable(400)
        assertNonRetryable(413)
        assertNonRetryable(415)
        assertNonRetryable(422)
    }

    @Test
    fun `server errors stay queued`() {
        assertRetryable(500)
        assertRetryable(502)
        assertRetryable(503)
    }

    @Test
    fun `success carries the response body through for ack parsing`() {
        val result = CloudTelemetryClient.classify(200, "HTTP 200", """{"ok":true}""")
        assertTrue("expected Success for 200, got $result", result is CloudSendResult.Success)
        assertEquals("""{"ok":true}""", (result as CloudSendResult.Success).responseBody)
    }

    private fun assertRetryable(code: Int) {
        val result = CloudTelemetryClient.classify(code, "HTTP $code", null)
        assertTrue(
            "HTTP $code must keep queued rows: expected RetryableFailure, got $result",
            result is CloudSendResult.RetryableFailure,
        )
    }

    private fun assertNonRetryable(code: Int) {
        val result = CloudTelemetryClient.classify(code, "HTTP $code", null)
        assertTrue(
            "HTTP $code must not be retried forever: expected NonRetryableFailure, got $result",
            result is CloudSendResult.NonRetryableFailure,
        )
    }
}
