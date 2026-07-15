package com.bydmate.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoltflowLinkClientTest {

    @Test
    fun redeemUrlFromDefaultTelemetryEndpoint() {
        val url = "https://voltflow.life/api/bydmate/telemetry"
        assertEquals(
            "https://voltflow.life/api/bydmate/link-code/redeem",
            VoltflowLinkClient.redeemUrlFromTelemetryEndpoint(url),
        )
    }

    @Test
    fun redeemUrlRejectsHttp() {
        assertNull(
            VoltflowLinkClient.redeemUrlFromTelemetryEndpoint("http://example.com/api/bydmate/telemetry"),
        )
    }
}
