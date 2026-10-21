package com.bydmate.app.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The daemon side of «Отправить диагностику»: answer only when asked, never leave the request. */
class DiagnosticsHandoffTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `no request, no work`() {
        val dir = tmp.newFolder("diagnostics")
        assertFalse(DiagnosticsHandoff.serve(dir, File(dir, "missing.log"), File(dir, "ts")))
        assertFalse(File(dir, DiagnosticsHandoff.OUTPUT).exists())
    }

    @Test
    fun `a request gets both log generations and the relaunch stamp, then is removed`() {
        val dir = tmp.newFolder("diagnostics")
        val log = tmp.newFile("voltflow_cmd_daemon.log").apply { writeText("[12:37] relaunch: service stale\n") }
        File(log.path + ".1").writeText("[11:00] previous run\n")
        val ts = tmp.newFile("relaunch_ts").apply { writeText("1761039468\n") }
        File(dir, DiagnosticsHandoff.REQUEST).writeText("1")

        assertTrue(DiagnosticsHandoff.serve(dir, log, ts, nowMs = 42L))

        val out = File(dir, DiagnosticsHandoff.OUTPUT).readText()
        assertTrue(out.contains("relaunch_ts=1761039468"))
        assertTrue(out.contains("previous run"))
        assertTrue(out.contains("relaunch: service stale"))
        assertTrue(out.indexOf("previous run") < out.indexOf("relaunch: service stale"))
        assertFalse(File(dir, DiagnosticsHandoff.REQUEST).exists())
    }

    @Test
    fun `a missing log is reported as absent, not as a failure`() {
        val dir = tmp.newFolder("diagnostics")
        File(dir, DiagnosticsHandoff.REQUEST).writeText("1")
        assertTrue(DiagnosticsHandoff.serve(dir, File(dir, "none.log"), File(dir, "none.ts")))
        assertTrue(File(dir, DiagnosticsHandoff.OUTPUT).readText().contains("(absent)"))
    }

    @Test
    fun `tail cuts at a line boundary`() {
        val file = tmp.newFile("big.log").apply { writeText("aaaa\nbbbb\ncccc\n") }
        assertEquals("cccc\n", DiagnosticsHandoff.tail(file, 7))
    }
}
