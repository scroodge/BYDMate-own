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
    fun `the status loop wakes at the push interval only while watched`() {
        // Regression: the 3s push interval was meaningless while the loop woke every 6s —
        // measured pushes landed 8-9s apart until the wake rate itself changed.
        assertEquals(6_000L, CommandDaemon.statusIntervalMs(t0, 0L))
        assertEquals(3_000L, CommandDaemon.statusIntervalMs(t0, t0 + 60_000L))
        // Unwatched still wakes at 6s, not the 60s push cadence: DiPars must stay fresh for
        // the command loop, and a plug/unplug edge must still be caught promptly.
        assertEquals(6_000L, CommandDaemon.statusIntervalMs(t0, t0 - 1L))
    }

    @Test
    fun `pacing subtracts the work so the period is the interval, not interval plus work`() {
        // The other half of the 8-9s regression: a 3s sleep after ~2s of DiPars + POST work
        // yields a 5s period. Fixed-rate pacing is what actually delivers 3s.
        assertEquals(1_000L, CommandDaemon.pacedSleepMs(3_000L, 2_000L))
        assertEquals(3_000L, CommandDaemon.pacedSleepMs(3_000L, 0L))
        // A slow iteration never sleeps negative — it just runs the next one immediately.
        assertEquals(0L, CommandDaemon.pacedSleepMs(3_000L, 9_000L))
    }

    // --- app-alive gate ---
    //
    // The beacon is written at 1 Hz (TrackingService's poll loop writes it after every
    // enqueue), so its age is a fine-grained "is the app's loop still turning" signal.
    // A single flat 120 s TTL over it is what produced the ~2.5-3 min of stale status the
    // owner saw after every park, measured at 124-236 s across 14 prod transitions.
    //
    // The park transition itself classifies PARKED, not DRIVING — gear=1, and the
    // reduced-payload gear/speed=null case, are both pinned in IternioIntervalPolicyTest —
    // so the DRIVING guard is not involved and deliberately keeps its unconditional skip.

    @Test
    fun `a status-only push resumes seconds after the app dies, a stored one waits`() {
        // The blackout, in one assertion. The head unit force-stops the app, the beacon
        // freezes, and 5 s later the daemon may refresh live state — it writes no history
        // row, so there is nothing to duplicate. A full sample still holds off.
        val age = 5_001L
        assertFalse(CommandDaemon.shouldDeferToApp(beaconAgeMs = age, liveOnly = true))
        assertTrue(CommandDaemon.shouldDeferToApp(beaconAgeMs = age, liveOnly = false))
    }

    @Test
    fun `a healthy one hertz beacon silences both kinds of push`() {
        // The no-dual-writer invariant. While the app is genuinely alive the daemon must
        // stay off the wire entirely: its payload has no GPS and none of the range/trip
        // fields, and its device_time always beats the app's batched samples, so any push
        // here would blank those out of the live snapshot.
        assertTrue(CommandDaemon.shouldDeferToApp(beaconAgeMs = 1_000L, liveOnly = true))
        assertTrue(CommandDaemon.shouldDeferToApp(beaconAgeMs = 1_000L, liveOnly = false))
    }

    @Test
    fun `a long dead app stops holding back stored samples too`() {
        // 20 missed beacons: enough margin for a GC pause, short enough that the parked
        // history row lands promptly instead of two minutes later.
        assertFalse(CommandDaemon.shouldDeferToApp(beaconAgeMs = 20_001L, liveOnly = false))
        assertFalse(CommandDaemon.shouldDeferToApp(beaconAgeMs = 20_001L, liveOnly = true))
    }

    @Test
    fun `no beacon at all is not an excuse to stay silent`() {
        // Absent or unparseable file — an app that has never written one is not sending.
        assertFalse(CommandDaemon.shouldDeferToApp(beaconAgeMs = null, liveOnly = true))
        assertFalse(CommandDaemon.shouldDeferToApp(beaconAgeMs = null, liveOnly = false))
    }

    @Test
    fun `a future-dated beacon does not read as alive`() {
        // Clock skew across the app/daemon boundary must not strand the car silent; the
        // range check is deliberately `in 0..ttl`, not `<= ttl`.
        assertFalse(CommandDaemon.shouldDeferToApp(beaconAgeMs = -1_000L, liveOnly = true))
        assertFalse(CommandDaemon.shouldDeferToApp(beaconAgeMs = -1_000L, liveOnly = false))
    }

    @Test
    fun `the status-only threshold is always the shorter of the two`() {
        // Ordering invariant: swapping these would let a history-writing push go out sooner
        // than a status-only one, which is exactly backwards — the stored row is the one
        // that can be duplicated.
        assertTrue(CommandDaemon.appAliveTtlMs(liveOnly = true) < CommandDaemon.appAliveTtlMs(liveOnly = false))
    }

    @Test
    fun `wifi keepalive never fires when the user has not opted in`() {
        assertFalse(CommandDaemon.shouldRefreshWifiKeepalive(now = t0 + 120_000L, lastAttemptAt = 0L, enabled = false))
    }

    @Test
    fun `wifi keepalive fires once the interval elapses, then waits again`() {
        assertFalse(
            "too soon",
            CommandDaemon.shouldRefreshWifiKeepalive(now = t0 + 30_000L, lastAttemptAt = t0, enabled = true),
        )
        assertTrue(
            CommandDaemon.shouldRefreshWifiKeepalive(now = t0 + 60_000L, lastAttemptAt = t0, enabled = true),
        )
    }
}
