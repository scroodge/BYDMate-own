package com.bydmate.app.data.autoservice

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdbOnDeviceClientTest {

    private fun newClient(fake: AdbProtocol): AdbOnDeviceClientImpl {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keyStore = AdbKeyStore(ctx)
        val client = AdbOnDeviceClientImpl(ctx, keyStore)
        client.protocolFactory = { fake }
        return client
    }

    private class FakeProtocol(
        var connectResult: Boolean = true,
        var connectThrows: Throwable? = null,
        var execResponses: Map<String, String?> = emptyMap(),
        var execHandler: ((String) -> String?)? = null
    ) : AdbProtocol {
        var connectCalls = 0
        var disconnectCalls = 0
        var execCalls = mutableListOf<String>()
        private var connected = false

        override fun connect(): Boolean {
            connectCalls++
            connectThrows?.let { throw it }
            connected = connectResult
            return connectResult
        }
        override fun exec(cmd: String): String? {
            execCalls += cmd
            return execHandler?.invoke(cmd) ?: execResponses[cmd]
        }
        override fun isConnected(): Boolean = connected
        override fun disconnect() {
            disconnectCalls++
            connected = false
        }
    }

    @Test
    fun `execAutoserviceReads sends one marker-separated read-only command`() = runTest {
        val fake = FakeProtocol(execHandler = {
            "@vfas0\nResult: Parcel(00000000 0007f330   '....0...')\n" +
                "@vfas1\nResult: Parcel(00000000 42a80000   '.......B')"
        })
        val client = newClient(fake)
        client.connect()
        val result = client.execAutoserviceReads(listOf(
            AutoserviceRead(5, 1014, 1246765072),
            AutoserviceRead(7, 1013, -1807745016),
        ))
        assertEquals(listOf(0x7f330, 0x42a80000), result)
        assertEquals(1, fake.execCalls.size)
        assertEquals(
            "echo @vfas0;service call autoservice 5 i32 1014 i32 1246765072;" +
                "echo @vfas1;service call autoservice 7 i32 1013 i32 -1807745016",
            fake.execCalls.single(),
        )
    }

    @Test
    fun `execAutoserviceReads returns null on transport failure`() = runTest {
        val client = newClient(FakeProtocol(execHandler = { null }))
        client.connect()
        assertNull(client.execAutoserviceReads(listOf(AutoserviceRead(5, 1014, 1))))
    }

    @Test
    fun `AutoserviceRead refuses write transact codes`() {
        try {
            AutoserviceRead(6, 1014, 1145045032)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("read-only"))
        }
    }

    @Test
    fun `parseBatchOutput keys by marker so a failed call does not shift later values`() {
        val out = "@vfas0\nService autoservice does not exist\n" +
            "@vfas1\nResult: Parcel(00000000 00000060   '....`...')\n" +
            "@vfas2\nResult: Parcel(00000000 ffffd8e5   '........')"
        assertEquals(listOf(null, 0x60, -10011), AdbOnDeviceClientImpl.parseBatchOutput(out, 3))
    }

    @Test
    fun `exec write command throws write barrier`() = runTest {
        val client = newClient(FakeProtocol())
        client.connect()
        try {
            // tx=6 is setInt — must be rejected at the boundary.
            client.exec("service call autoservice 6 i32 1014 i32 1145045032")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("write barrier"))
        }
    }

    @Test
    fun `approved seat action writes its fixed fid and verifies read back`() = runTest {
        val action = AutoserviceActuation.PASSENGER_SEAT_HEAT_LEVEL_2
        val write = "service call autoservice 6 i32 1023 i32 1125122076 i32 3"
        val read = "service call autoservice 5 i32 1023 i32 1335885843"
        val client = newClient(FakeProtocol(execResponses = mapOf(
            write to "Result: Parcel(00000001)",
            read to "Result: Parcel(00000000 00000003   '........')",
        )))
        client.connect()

        val result = client.executeAutoserviceActuation(action)

        assertEquals(AutoserviceActuationResult(true, true, 3), result)
    }

    @Test
    fun `rejected direct write never attempts read back`() = runTest {
        val fake = FakeProtocol(execResponses = mapOf(
            "service call autoservice 6 i32 1023 i32 1125122068 i32 1" to
                "Result: Parcel(ffffd8e5)",
        ))
        val client = newClient(fake)
        client.connect()

        val result = client.executeAutoserviceActuation(AutoserviceActuation.DRIVER_SEAT_HEAT_OFF)

        assertEquals(AutoserviceActuationResult(false, false), result)
        assertEquals(1, fake.execCalls.size)
    }

    @Test
    fun `drl accepts only the fixed write and has no fabricated read back`() = runTest {
        val write = "service call autoservice 6 i32 1004 i32 1125122118 i32 1"
        val fake = FakeProtocol(execResponses = mapOf(write to "Result: Parcel(00000001)"))
        val client = newClient(fake)
        client.connect()

        val result = client.executeAutoserviceActuation(AutoserviceActuation.DRL_ON)

        assertEquals(AutoserviceActuationResult(true, null), result)
        assertEquals(listOf(write), fake.execCalls)
    }

    @Test
    fun `exec disconnected returns null`() = runTest {
        val client = newClient(FakeProtocol(connectResult = false))
        // Note: not calling connect() — protocol field stays null.
        val result = client.exec("service call autoservice 5 i32 1014 i32 1145045032")
        assertNull(result)
    }

    @Test
    fun `connect protocol returns true returns success`() = runTest {
        val fake = FakeProtocol(connectResult = true)
        val client = newClient(fake)

        val result = client.connect()

        assertTrue(result.isSuccess)
        assertEquals(1, fake.connectCalls)
    }

    @Test
    fun `connect protocol returns false returns failure`() = runTest {
        val fake = FakeProtocol(connectResult = false)
        val client = newClient(fake)

        val result = client.connect()

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `connect protocol throws returns failure does not crash`() = runTest {
        val fake = FakeProtocol(connectThrows = RuntimeException("kaboom"))
        val client = newClient(fake)

        val result = client.connect()

        assertTrue(result.isFailure)
        assertEquals("kaboom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `shutdown idempotent does not crash when never connected`() = runTest {
        val client = newClient(FakeProtocol())
        // Never connected.
        client.shutdown()
        client.shutdown()
        // No exception → ok.
    }

    @Test
    fun `shutdown after connect disconnects protocol`() = runTest {
        val fake = FakeProtocol(connectResult = true)
        val client = newClient(fake)

        client.connect()
        client.shutdown()

        assertEquals(1, fake.disconnectCalls)
    }

    @Test
    fun `watchdog health is true only when shell prints ok`() = runTest {
        val fake = FakeProtocol(
            connectResult = true,
            execHandler = { cmd ->
                if (cmd.contains("voltflow_cmd_watchdog.pid")) "ok\n" else null
            }
        )
        val client = newClient(fake)

        client.connect()
        val healthy = client.isCommandDaemonWatchdogRunning()

        assertTrue(healthy)
        assertTrue(fake.execCalls.single().contains("start_voltflow_cmd.sh"))
    }

    @Test
    fun `watchdog health is false when lock is stale`() = runTest {
        val fake = FakeProtocol(
            connectResult = true,
            execHandler = { cmd ->
                if (cmd.contains("voltflow_cmd_watchdog.pid")) "" else null
            }
        )
        val client = newClient(fake)

        client.connect()
        val healthy = client.isCommandDaemonWatchdogRunning()

        assertEquals(false, healthy)
    }

    @Test
    fun `explicit disable creates sentinel before terminating daemon and watchdog`() = runTest {
        val fake = FakeProtocol(connectResult = true, execHandler = { "voltflow_cmd_control_ok\n" })
        val client = newClient(fake)

        assertTrue(client.setCommandDaemonEnabled(false))

        val command = fake.execCalls.single()
        assertTrue(command.contains("touch /data/local/tmp/voltflow_cmd_daemon.disabled"))
        assertTrue(command.indexOf("touch /data/local/tmp/voltflow_cmd_daemon.disabled") < command.indexOf("pidof voltflow_cmd_daemon"))
        assertTrue(command.contains("cat /data/local/tmp/voltflow_cmd_watchdog.pid"))
        assertEquals(false, command.contains("pkill -f"))
    }

    @Test
    fun `explicit enable only clears the daemon disabled sentinel`() = runTest {
        val fake = FakeProtocol(connectResult = true, execHandler = { "voltflow_cmd_control_ok\n" })
        val client = newClient(fake)

        assertTrue(client.setCommandDaemonEnabled(true))

        val command = fake.execCalls.single()
        assertTrue(command.contains("rm -f /data/local/tmp/voltflow_cmd_daemon.disabled"))
        assertEquals(false, command.contains("pkill"))
    }
}
