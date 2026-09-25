package com.bydmate.app.data.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudBatteryCapacityTest {

    private fun parse(body: String) = CloudBatteryCapacity.fromJson(JSONObject(body))

    @Test fun `reads the car-profile capacity`() {
        assertEquals(45.1, parse("""{"ok":true,"commands":[],"battery_capacity_kwh":45.1}""")!!, 1e-9)
    }

    @Test fun `telemetry ack carries it too`() {
        val ack = CloudTelemetryAckParser.parse(
            """{"ok":true,"inserted":1,"duplicates":0,"live_fast_seconds":0,"battery_capacity_kwh":45.1}""",
            sentCount = 1,
        )
        assertEquals(45.1, ack.batteryCapacityKwh!!, 1e-9)
    }

    @Test fun `older server without the field keeps the setting`() {
        assertNull(parse("""{"ok":true,"commands":[],"poll_after_seconds":300}"""))
    }

    @Test fun `implausible or malformed values are ignored`() {
        assertNull(parse("""{"battery_capacity_kwh":4.2}"""))
        assertNull(parse("""{"battery_capacity_kwh":500}"""))
        assertNull(parse("""{"battery_capacity_kwh":"n/a"}"""))
        assertNull(parse("""{"battery_capacity_kwh":null}"""))
    }
}
