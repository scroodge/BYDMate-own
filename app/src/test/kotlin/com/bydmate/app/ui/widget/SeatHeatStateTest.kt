package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatHeatStateTest {

    @Test
    fun `read value 1 is off`() = assertEquals(false, seatHeatOn(1))

    @Test
    fun `either heat level counts as on so a tap turns it off`() {
        assertEquals(true, seatHeatOn(2))
        assertEquals(true, seatHeatOn(3))
    }

    @Test
    fun `missing or unexpected value is unknown`() {
        assertEquals(null, seatHeatOn(null))
        assertEquals(null, seatHeatOn(0))
        assertEquals(null, seatHeatOn(4))
    }
}
