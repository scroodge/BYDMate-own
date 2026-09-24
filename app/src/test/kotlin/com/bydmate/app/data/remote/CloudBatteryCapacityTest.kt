package com.bydmate.app.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudBatteryCapacityTest {

    private fun parse(body: String) = VehicleCommandPoller.cloudBatteryCapacityKwh(JSONObject(body))

    @Test fun `reads the car-profile capacity`() {
        assertEquals(45.1, parse("""{"ok":true,"commands":[],"battery_capacity_kwh":45.1}""")!!, 1e-9)
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
