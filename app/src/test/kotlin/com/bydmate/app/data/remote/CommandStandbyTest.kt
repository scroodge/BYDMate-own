package com.bydmate.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandStandbyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `fresh daemon beacon puts the app on standby`() {
        assertTrue(CommandStandby.appShouldStandby("${now - 5_000}", now))
        assertTrue(CommandStandby.appShouldStandby("${now - CommandStandby.BEACON_TTL_MS}\n", now))
    }

    @Test
    fun `stale, missing, garbage or future beacon lets the app execute`() {
        assertFalse(CommandStandby.appShouldStandby("${now - CommandStandby.BEACON_TTL_MS - 1}", now))
        assertFalse(CommandStandby.appShouldStandby(null, now))
        assertFalse(CommandStandby.appShouldStandby("", now))
        assertFalse(CommandStandby.appShouldStandby("abc", now))
        // A clock jump must not freeze the app in standby forever.
        assertFalse(CommandStandby.appShouldStandby("${now + 60_000}", now))
    }
}
