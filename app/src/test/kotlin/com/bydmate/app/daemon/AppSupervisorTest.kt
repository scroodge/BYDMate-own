package com.bydmate.app.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-20's relaunch decision, now owned by the daemon (B-22 stage 1). The cases are the ones the
 * shell `app_relaunch_reason` was pinned with, so the move changes where the logic runs, not
 * what it decides.
 */
class AppSupervisorTest {

    private val nowMs = 1_790_255_866_000L // from the car on 2026-09-24
    private fun beaconAgo(seconds: Long) = (nowMs - seconds * 1000 + 59).toString()
    private fun reason(alive: Boolean, beacon: String?) =
        AppSupervisor.relaunchReason(alive, beacon, nowMs)

    @Test
    fun `no process means relaunch`() {
        assertEquals("not running", reason(alive = false, beacon = beaconAgo(1)))
    }

    @Test
    fun `live process with a fresh beacon is left alone`() {
        assertNull(reason(alive = true, beacon = beaconAgo(1)))
        assertNull(reason(alive = true, beacon = beaconAgo(120)))
    }

    @Test
    fun `live process with a stale beacon is the service-died case`() {
        assertEquals("service stale (beacon age 121s)", reason(alive = true, beacon = beaconAgo(121)))
        assertEquals("service stale (beacon age 2526s)", reason(alive = true, beacon = beaconAgo(2526)))
    }

    @Test
    fun `live process without a beacon is a graceful stop, not a failure`() {
        assertNull(reason(alive = true, beacon = null))
        assertNull(reason(alive = true, beacon = ""))
    }

    @Test
    fun `unreadable or future beacon never relaunches a live process`() {
        assertNull(reason(alive = true, beacon = "garbage"))
        assertNull(reason(alive = true, beacon = "12"))
        assertNull(reason(alive = true, beacon = (nowMs + 60_000).toString()))
    }

    @Test
    fun `a trailing newline in the beacon file is tolerated`() {
        assertEquals("service stale (beacon age 300s)", reason(alive = true, beacon = beaconAgo(300) + "\n"))
    }

    @Test
    fun `dead process gets the UI, live one the invisible starter`() {
        assertEquals(AppSupervisor.MAIN_ACTIVITY, AppSupervisor.activityFor("not running"))
        assertEquals(
            AppSupervisor.SILENT_START_ACTIVITY,
            AppSupervisor.activityFor("service stale (beacon age 121s)"),
        )
    }

    @Test
    fun `cooldown holds a second attempt for sixty seconds`() {
        assertTrue(AppSupervisor.cooldownElapsed(nowSec = 1_000, lastSec = null))
        assertFalse(AppSupervisor.cooldownElapsed(nowSec = 1_059, lastSec = 1_000))
        assertTrue(AppSupervisor.cooldownElapsed(nowSec = 1_060, lastSec = 1_000))
    }

    @Test
    fun `the watchdog script no longer relaunches the app itself`() {
        // One owner for the decision. A watchdog that also relaunched would bring back the
        // frozen-in-memory copy this move exists to escape.
        val script = locate("tools/start_voltflow_cmd.sh").readText()
        assertFalse(script.contains("app_relaunch_reason"))
        assertFalse(script.contains("com.bydmate.app.MainActivity"))
        assertFalse(script.contains("SilentStartActivity"))
    }

    private fun locate(path: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, path).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not locate $path")
    }
}
