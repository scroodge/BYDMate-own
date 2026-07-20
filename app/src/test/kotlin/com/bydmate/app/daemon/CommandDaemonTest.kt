package com.bydmate.app.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon's push gating, which is what decides how fresh the owner's status is while the
 * car is off. The loop itself cannot be driven from a test — it blocks on DiPars, HTTP and
 * Thread.sleep forever — so the decisions are extracted and pinned here instead.
 *
 * Every case below is a bug that actually reached the car during 2026-07-20 development.
 */
class CommandDaemonTest {

    private val t0 = 1_700_000_000_000L

    private fun plan(
        now: Long,
        lastPush: Long = t0,
        lastInterval: Long = t0,
        fastUntil: Long = 0L,
        gunChanged: Boolean = false,
    ) = CommandDaemon.planPush(now, lastPush, lastInterval, fastUntil, gunChanged)

    @Test
    fun `idle car pushes only on the sixty second history cadence`() {
        assertFalse(plan(now = t0 + 30_000L).push)
        assertTrue(plan(now = t0 + 60_000L).push)
        assertTrue(plan(now = t0 + 60_000L).dueByInterval)
    }

    @Test
    fun `a watched car pushes status every three seconds`() {
        val fastUntil = t0 + 60_000L
        assertFalse("2s is too soon", plan(now = t0 + 2_000L, fastUntil = fastUntil).push)
        assertTrue(plan(now = t0 + 3_000L, fastUntil = fastUntil).push)
    }

    @Test
    fun `fast pushes are status-only so a watched charge stores no extra history`() {
        // Regression: the parked "unchanged" test is false while charging, so before this the
        // 3s push fell through to a full sample and wrote a history row every 3 seconds.
        val fast = plan(now = t0 + 3_000L, fastUntil = t0 + 60_000L)
        assertTrue(fast.push)
        assertFalse(fast.dueByInterval)
        assertTrue(
            "charging + not due by interval must still be live_only",
            fast.liveOnly(unchanged = false, runExpired = false),
        )
    }

    @Test
    fun `the history cadence survives a long viewing session`() {
        // Regression: fast pushes used to reset the same timer that gates history, so a car
        // watched for an hour stored nothing. Only interval pushes and edges advance it.
        assertFalse(plan(now = t0 + 3_000L, fastUntil = t0 + 600_000L).advancesIntervalTimer)
        assertTrue(plan(now = t0 + 60_000L, fastUntil = t0 + 600_000L).advancesIntervalTimer)

        // With the fast timer churning every 3s, the interval timer still fires at 60s.
        val due = CommandDaemon.planPush(
            now = t0 + 60_000L,
            lastTelemetryPushAt = t0 + 57_000L, // a fast push 3s ago
            lastIntervalPushAt = t0,
            liveFastUntilMs = t0 + 600_000L,
            gunChanged = false,
        )
        assertTrue(due.dueByInterval)
        assertFalse(
            "a real cadence push must store history",
            due.liveOnly(unchanged = false, runExpired = false),
        )
    }

    @Test
    fun `plugging in pushes immediately and is stored as a real event`() {
        // The owner's case: car off, charger plugged in. Waiting out the 60s cadence is what
        // made this take minutes.
        val edge = plan(now = t0 + 1_000L, gunChanged = true)
        assertTrue(edge.push)
        assertFalse(
            "a plug/unplug must not be live_only",
            edge.liveOnly(unchanged = true, runExpired = false),
        )
        assertTrue("an edge re-baselines the idle comparison", edge.advancesIntervalTimer)
    }

    @Test
    fun `an idle parked car keeps using the live_only fast path`() {
        val interval = plan(now = t0 + 60_000L)
        assertTrue(interval.liveOnly(unchanged = true, runExpired = false))
        // ...but the 15-minute forced-full rule still wins, or phantom-drain analytics break.
        assertFalse(interval.liveOnly(unchanged = true, runExpired = true))
    }

    @Test
    fun `an expired grant stops the fast cadence on its own`() {
        // Expiry is the only off switch, so a crashed tab cannot strand the car pushing.
        val expired = t0 + 10_000L
        assertFalse(plan(now = t0 + 11_000L, lastPush = t0 + 10_500L, fastUntil = expired).push)
    }

    @Test
    fun `the loop wakes at the push interval only while watched`() {
        // Regression: the 3s push interval was meaningless while the loop slept 6s — measured
        // pushes landed 8-9s apart until this clamp.
        assertEquals(6_000L, CommandDaemon.loopSleepMs(6_000L, t0, 0L))
        assertEquals(3_000L, CommandDaemon.loopSleepMs(6_000L, t0, t0 + 60_000L))
        // Never *lengthens* a backoff the poll asked for.
        assertEquals(1_000L, CommandDaemon.loopSleepMs(1_000L, t0, t0 + 60_000L))
    }
}
