package com.bydmate.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTelemetryAckParserTest {
    @Test
    fun `full ack is acknowledged`() {
        val ack = CloudTelemetryAckParser.parse(
            """{"ok":true,"inserted_count":12,"duplicate_count":3,"skipped_stale_count":0}""",
            sentCount = 15,
        )
        assertTrue(ack.isFullyAcknowledged())
    }

    @Test
    fun `skipped stale is not acknowledged`() {
        val ack = CloudTelemetryAckParser.parse(
            """{"ok":true,"inserted_count":0,"skipped_stale_count":15,"sample_count":0}""",
            sentCount = 15,
        )
        assertFalse(ack.isFullyAcknowledged())
    }

    @Test
    fun `empty body is not acknowledged`() {
        val ack = CloudTelemetryAckParser.parse(null, sentCount = 1)
        assertFalse(ack.isFullyAcknowledged())
    }

    @Test
    fun `ingest carries the fast live-status grant`() {
        // Second carrier alongside the command poll; this is what lets that poll idle at 60s.
        val ack = CloudTelemetryAckParser.parse(
            """{"ok":true,"inserted_count":1,"sample_count":1,"live_fast_seconds":20}""",
            sentCount = 1,
        )
        assertEquals(20, ack.liveFastSeconds)
        assertTrue("the grant must not disturb normal ack accounting", ack.isFullyAcknowledged())
    }

    @Test
    fun `an older server omitting the grant reads as no grant`() {
        val ack = CloudTelemetryAckParser.parse(
            """{"ok":true,"inserted_count":1,"sample_count":1}""",
            sentCount = 1,
        )
        assertEquals(0, ack.liveFastSeconds)
    }
}
