package com.bydmate.app.daemon

import com.bydmate.app.data.remote.DiParsData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- autoservice-only fallback (di+ down, parked/charging only) ---
    // `gunState` models either di+'s last-known gun state, or (when di+ has never answered at
    // all this run) a fresh direct autoservice gun-state read — the call site picks the source,
    // the gate logic doesn't care which.

    @Test
    fun `fallback is allowed when the last known state was parked`() {
        assertTrue(CommandDaemon.shouldUseAutoserviceFallback(lastKnownGear = 1, gunState = null))
    }

    @Test
    fun `fallback is allowed when charging, regardless of gear`() {
        // gun states 2-5 are AC/DC/AC_DC/VTOL per FidRegistry.FID_GUN_CONNECT_STATE.
        assertTrue(CommandDaemon.shouldUseAutoserviceFallback(lastKnownGear = null, gunState = 2))
        assertTrue(CommandDaemon.shouldUseAutoserviceFallback(lastKnownGear = 3, gunState = 5))
    }

    @Test
    fun `fallback never fires from a driving last-known-state`() {
        // gear=4 (D) per DiParsData's gear enum, gun=1 (NONE) — not parked, not charging.
        assertFalse(CommandDaemon.shouldUseAutoserviceFallback(lastKnownGear = 4, gunState = 1))
    }

    @Test
    fun `fallback never guesses when there is no evidence at all`() {
        // No last-known di+ state and no live autoservice read either — err toward silence.
        assertFalse(CommandDaemon.shouldUseAutoserviceFallback(lastKnownGear = null, gunState = null))
    }

    @Test
    fun `fallback is allowed from a live autoservice charging read when di+ has never answered`() {
        // Models the cold-start case: lastKnownGear is null (di+ never answered), but the call
        // site substituted a fresh direct autoservice gun-state read of 3 (DC) as evidence.
        assertTrue(CommandDaemon.shouldUseAutoserviceFallback(lastKnownGear = null, gunState = 3))
    }

    // --- autoservice/di+ merge: derived predicates ---
    // Both bugs below were live in the code before 2026-07-23 and are the reason the two payload
    // builders were merged into one: is_charging could latch true forever after unplug, and
    // is_parked disagreed between the di+ path and the autoservice-fallback path for a car that
    // was parked *and* charging at once.

    @Test
    fun `isChargingFrom lets a frozen di+ status keep charging true only when autoservice can't see the gun`() {
        // gun unreadable (autoservice down) -> di+'s status is the only evidence left.
        assertTrue(CommandDaemon.isChargingFrom(gun = null, diPlusChargingStatus = 3))
        assertFalse(CommandDaemon.isChargingFrom(gun = null, diPlusChargingStatus = 0))
    }

    @Test
    fun `isChargingFrom lets a live autoservice gun overrule a stale di+ status`() {
        // Regression test: gun=1 (NONE, unplugged) must win over a frozen chargingStatus=3 that
        // never updated after the real unplug — the pre-merge OR formula would have stayed true.
        assertFalse(CommandDaemon.isChargingFrom(gun = 1, diPlusChargingStatus = 3))
        assertTrue(CommandDaemon.isChargingFrom(gun = 2, diPlusChargingStatus = 0))
        assertTrue(CommandDaemon.isChargingFrom(gun = 5, diPlusChargingStatus = null))
        assertFalse(CommandDaemon.isChargingFrom(gun = 6, diPlusChargingStatus = 3))
    }

    @Test
    fun `isParkedFrom prefers gear, falls back to charging state only when gear is unknown`() {
        // The exact daemon scenario this fixes: parked AND charging must read parked=true.
        assertTrue(CommandDaemon.isParkedFrom(gear = 1, isCharging = true))
        assertFalse(CommandDaemon.isParkedFrom(gear = 4, isCharging = false))
        assertFalse(CommandDaemon.isParkedFrom(gear = null, isCharging = true))
        assertTrue(CommandDaemon.isParkedFrom(gear = null, isCharging = false))
    }

    @Test
    fun `chargeTypeFrom maps the shared 2-AC 3to5-DC encoding`() {
        assertEquals("AC", CommandDaemon.chargeTypeFrom(2))
        assertEquals("DC", CommandDaemon.chargeTypeFrom(3))
        assertEquals("DC", CommandDaemon.chargeTypeFrom(5))
        assertNull(CommandDaemon.chargeTypeFrom(1))
        assertNull(CommandDaemon.chargeTypeFrom(null))
    }

    // --- buildTelemetryPayload: direct engine only ---

    private fun diPars(
        soc: Int? = null, speed: Int? = null, mileage: Double? = null, power: Double? = null,
        chargeGunState: Int? = null, gear: Int? = null, chargingStatus: Int? = null,
        doorFL: Int? = null, doorFR: Int? = null, doorRL: Int? = null, doorRR: Int? = null,
        tirePressFL: Int? = null, voltage12v: Double? = null,
    ) = DiParsData(
        soc = soc, speed = speed, mileage = mileage, power = power, chargeGunState = chargeGunState,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null, chargingStatus = chargingStatus,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = voltage12v,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = null, gear = gear,
        powerState = null, insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = doorFL, doorFR = doorFR, doorRL = doorRL, doorRR = doorRR,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null, sunroof = null,
        trunk = null, hood = null, seatbeltFL = null, lockFL = null, tirePressFL = tirePressFL,
        tirePressFR = null, tirePressRL = null, tirePressRR = null, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
        sunshade = null, sentryState = null, remoteLockState = null,
    )

    private fun snap(
        socPercent: Int? = null, powerKw: Int? = null, gun: Int? = null,
        doorFL: Int? = null, tireFL: Int? = null,
    ) = CommandDaemon.AutoserviceSnapshot(
        socPercent = socPercent, powerKw = powerKw, gun = gun, doorFL = doorFL, tireFL = tireFL,
    )

    private fun payload(d: DiParsData?, auto: CommandDaemon.AutoserviceSnapshot?) =
        CommandDaemon.buildTelemetryPayload(
            vehicleId = "way", d = d, auto = auto, liveOnly = false, mateVersion = "test",
        )

    @Test
    fun `direct payload uses the validated autoservice soc`() {
        val json = payload(diPars(soc = 60, chargeGunState = 2), snap(socPercent = 60, gun = 2))
        assertEquals(60, json.getJSONObject("telemetry").getInt("soc"))
        assertEquals(60, json.getJSONObject("autoservice").getInt("soc_percent"))
        assertFalse(json.has("diplus"))
    }

    @Test
    fun `direct payload never serializes a stale di plus soc`() {
        val json = payload(diPars(soc = 55, chargeGunState = 2), snap(socPercent = 62, gun = 2))
        assertEquals(62, json.getJSONObject("telemetry").getInt("soc"))
        assertEquals(62, json.getJSONObject("autoservice").getInt("soc_percent"))
        assertFalse(json.has("diplus"))
    }

    @Test
    fun `unavailable direct soc remains unknown while other direct fields survive`() {
        val json = payload(
            diPars(soc = 55, doorFL = 1, tirePressFL = 240),
            snap(socPercent = null, doorFL = 0, tireFL = 245),
        )
        val autoservice = json.getJSONObject("autoservice")
        assertEquals(JSONObject.NULL, autoservice.get("soc_percent"))
        assertEquals(0, autoservice.getInt("door_fl"))
        assertEquals(245, autoservice.getInt("tire_press_fl_kpa"))
        assertFalse(json.has("diplus"))
    }

    @Test
    fun `direct payload omits di plus block and leaves park state unknown`() {
        val json = payload(d = null, auto = snap(socPercent = 71, gun = 2))
        assertFalse(json.has("diplus"))
        assertEquals(JSONObject.NULL, json.getJSONObject("telemetry").get("is_parked"))
        assertEquals(71, json.getJSONObject("autoservice").getInt("soc_percent"))
    }

    @Test
    fun `both sources absent does not throw`() {
        payload(d = null, auto = null)
    }

    // --- di+ value-staleness (log-only signal) ---
    // The failure mode this catches: fetch() keeps succeeding but di+'s numbers stop changing —
    // 11+ minutes frozen soc/power during a live charge, observed on the car 2026-07-23. Distinct
    // from shouldUseAutoserviceFallback's DIPLUS_STALE_MS, which only catches fetch() failing.

    private val staleMs = 180_000L // mirrors DIPLUS_VALUE_STALE_MS

    @Test
    fun `frozen signature while charging with autoservice tracking is stale`() {
        assertTrue(
            CommandDaemon.isDiPlusValueStale(
                now = t0 + staleMs,
                signatureUnchangedSinceMs = t0,
                autoserviceSocMovedSinceMs = t0 + 60_000L,
                charging = true,
            ),
        )
    }

    @Test
    fun `frozen signature for hours while genuinely parked and flat is not stale`() {
        // The 13-hour overnight window in the field corpus: di+ static the whole time for a
        // legitimate reason (nothing was happening). No movement evidence -> not flagged.
        assertFalse(
            CommandDaemon.isDiPlusValueStale(
                now = t0 + 13 * 3_600_000L,
                signatureUnchangedSinceMs = t0,
                autoserviceSocMovedSinceMs = null,
                charging = false,
            ),
        )
    }

    @Test
    fun `not yet held long enough is not stale even with movement evidence`() {
        assertFalse(
            CommandDaemon.isDiPlusValueStale(
                now = t0 + staleMs - 1,
                signatureUnchangedSinceMs = t0,
                autoserviceSocMovedSinceMs = t0 + 1,
                charging = true,
            ),
        )
    }

    @Test
    fun `exactly at the threshold is stale`() {
        assertTrue(
            CommandDaemon.isDiPlusValueStale(
                now = t0 + staleMs,
                signatureUnchangedSinceMs = t0,
                autoserviceSocMovedSinceMs = null,
                charging = true,
            ),
        )
    }

    @Test
    fun `autoservice soc moving after the signature froze is movement evidence even when not charging`() {
        // e.g. a slow 12V/vampire drain the daemon doesn't classify as "charging" but that still
        // proves the car isn't static.
        assertTrue(
            CommandDaemon.isDiPlusValueStale(
                now = t0 + staleMs,
                signatureUnchangedSinceMs = t0,
                autoserviceSocMovedSinceMs = t0 + 90_000L,
                charging = false,
            ),
        )
    }

    @Test
    fun `autoservice soc movement before the signature froze is stale evidence, not fresh`() {
        // Moved once, then both sources went flat together — that's not ongoing movement.
        assertFalse(
            CommandDaemon.isDiPlusValueStale(
                now = t0 + staleMs,
                signatureUnchangedSinceMs = t0,
                autoserviceSocMovedSinceMs = t0 - 1,
                charging = false,
            ),
        )
    }

    @Test
    fun `diPlusValueSignature changes when any tracked field changes`() {
        val base = diPars(soc = 60, power = -4.0, chargeGunState = 2)
        val sig1 = CommandDaemon.diPlusValueSignature(base)
        val sig2 = CommandDaemon.diPlusValueSignature(diPars(soc = 61, power = -4.0, chargeGunState = 2))
        val sig3 = CommandDaemon.diPlusValueSignature(diPars(soc = 60, power = -4.0, chargeGunState = 2))
        assertTrue(sig1 != sig2)
        assertEquals(sig1, sig3)
    }
}
