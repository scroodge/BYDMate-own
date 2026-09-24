package com.bydmate.app.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * B-20: the watchdog's decision to relaunch VoltFlow Mate, run from the real launcher.
 *
 * The function is cut out of `tools/start_voltflow_cmd.sh` between its BEGIN/END markers and
 * executed with /bin/sh, so this pins the shipped shell code rather than a Kotlin copy of it.
 * (The asset copy is held identical by [LauncherAssetSyncTest].) The head unit's mksh is
 * 32-bit, which a desktop shell cannot reproduce — hence the textual no-division check.
 */
class AppRelaunchDecisionTest {

    private val now = 1_790_255_866L // epoch seconds, from the car on 2026-09-24
    private fun beaconAgo(seconds: Long) = ((now - seconds) * 1000 + 59).toString()

    @Test
    fun `no process means relaunch`() {
        assertEquals("not running", reason(pid = "", beacon = beaconAgo(1)))
    }

    @Test
    fun `live process with a fresh beacon is left alone`() {
        assertEquals("", reason(pid = "13592", beacon = beaconAgo(1)))
        assertEquals("", reason(pid = "13592", beacon = beaconAgo(120)))
    }

    @Test
    fun `live process with a stale beacon is the service-died case`() {
        assertEquals("service stale (beacon age 121s)", reason(pid = "13592", beacon = beaconAgo(121)))
        assertEquals("service stale (beacon age 3600s)", reason(pid = "13592", beacon = beaconAgo(3600)))
    }

    @Test
    fun `live process without a beacon is a graceful stop, not a failure`() {
        assertEquals("", reason(pid = "13592", beacon = ""))
    }

    @Test
    fun `unreadable beacon never triggers a relaunch of a live process`() {
        assertEquals("", reason(pid = "13592", beacon = "garbage"))
        assertEquals("", reason(pid = "13592", beacon = "12"))
    }

    @Test
    fun `beacon millis are cut as text, never divided (mksh is 32-bit)`() {
        val body = function()
        assertTrue("expected the textual millis cut", body.contains("\${2%???}"))
        assertTrue("no arithmetic division on epoch millis", !Regex("""/\s*1000""").containsMatchIn(body))
    }

    private fun reason(pid: String, beacon: String): String {
        val script = function() + "\napp_relaunch_reason \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
        val process = ProcessBuilder("/bin/sh", "-c", script, "sh", pid, beacon, now.toString(), "120")
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("shell timed out", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("shell failed: $out", 0, process.exitValue())
        return out.trim()
    }

    private fun function(): String {
        val lines = launcher().readLines()
        val start = lines.indexOfFirst { it.startsWith("# BEGIN app_relaunch_reason") }
        val end = lines.indexOfFirst { it.startsWith("# END app_relaunch_reason") }
        assertTrue("markers missing in $TOOLS_PATH", start >= 0 && end > start)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun launcher(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, TOOLS_PATH).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not locate $TOOLS_PATH from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val TOOLS_PATH = "tools/start_voltflow_cmd.sh"
    }
}
