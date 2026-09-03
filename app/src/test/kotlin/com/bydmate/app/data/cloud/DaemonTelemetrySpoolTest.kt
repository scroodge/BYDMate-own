package com.bydmate.app.data.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DaemonTelemetrySpoolTest {
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    private val deviceTime = "2026-09-02T12:46:27Z"
    private val payload = """{"vehicle_id":"way","device_time":"$deviceTime","telemetry":{"soc":94.5}}"""

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        directory = context.cacheDir.resolve("daemon-spool-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun `spool survives daemon restart`() {
        val firstProcess = DaemonTelemetrySpool(directory)
        firstProcess.append(payload, deviceTime, SAMPLE_ID)

        val restartedProcess = DaemonTelemetrySpool(directory)
        val recovered = restartedProcess.read(restartedProcess.readyFiles().single())

        assertEquals(SAMPLE_ID, recovered.sampleId)
        assertEquals(deviceTime, recovered.deviceTime)
        assertEquals(payload, recovered.payloadJson)
    }

    @Test
    fun `restart closes a fully synced open record left before rename`() {
        val record = DaemonSpoolRecord(SAMPLE_ID, deviceTime, payload)
        directory.resolve("$SAMPLE_ID.open").writeBytes(DaemonTelemetrySpool.encode(record))

        val restarted = DaemonTelemetrySpool(directory)

        assertEquals(record, restarted.read(restarted.readyFiles().single()))
        assertFalse(directory.resolve("$SAMPLE_ID.open").exists())
    }

    @Test
    fun `daemon only path remains durable when app never runs`() {
        val spool = DaemonTelemetrySpool(directory)
        var durableBeforeIpc = false
        val ingress = DaemonDurableIngress(spool, DaemonQueueIpc {
            durableBeforeIpc = spool.readyFiles().size == 1
            false
        })

        val retained = ingress.capture(payload, deviceTime)

        assertNotNull(retained)
        assertTrue(durableBeforeIpc)
        assertEquals(1, DaemonTelemetrySpool(directory).readyFiles().size)
    }

    @Test
    fun `ipc envelope round trip preserves capture device time exactly`() {
        val record = DaemonSpoolRecord(SAMPLE_ID, deviceTime, payload)

        val decoded = DaemonTelemetrySpool.decode(DaemonTelemetrySpool.encode(record))

        assertEquals(deviceTime, decoded.deviceTime)
        assertEquals(deviceTime, org.json.JSONObject(decoded.payloadJson).getString("device_time"))
    }

    @Test
    fun `transactional import preserves device time end to end`() = runTest {
        val spool = DaemonTelemetrySpool(directory)
        spool.append(payload, deviceTime, SAMPLE_ID)
        val engine = DaemonSpoolImportEngine(database, spool)

        val result = engine.importReady()
        val row = database.cloudSyncQueueDao().getUnsent(1).single()

        assertEquals(1, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(SAMPLE_ID, row.sampleId)
        assertEquals(payload, row.payloadJson)
        assertEquals(java.time.Instant.parse(deviceTime).toEpochMilli(), row.createdAt)
        assertEquals(payload.toByteArray(Charsets.UTF_8).size.toLong(), row.payloadBytes)
        assertEquals(java.time.Instant.parse(deviceTime).toEpochMilli(), row.capturedAt)
        assertEquals("daemon", row.origin)
        assertEquals(0, row.compactionTier)
        assertFalse(directory.resolve("$SAMPLE_ID.ready").exists())
    }

    @Test
    fun `crash after commit replays idempotently without duplicate or loss`() = runTest {
        val spool = DaemonTelemetrySpool(directory)
        spool.append(payload, deviceTime, SAMPLE_ID)
        val crashing = DaemonSpoolImportEngine(database, spool).apply {
            afterCommit = { error("simulated process death") }
        }

        val crash = runCatching { crashing.importReady() }
        assertTrue(crash.isFailure)
        assertEquals(1, database.cloudSyncQueueDao().getUnsent(10).size)
        assertTrue(directory.resolve("$SAMPLE_ID.ready").exists())

        val replay = DaemonSpoolImportEngine(database, DaemonTelemetrySpool(directory)).importReady()
        assertEquals(0, replay.imported)
        assertEquals(1, replay.duplicates)
        assertEquals(1, database.cloudSyncQueueDao().getUnsent(10).size)
        assertFalse(directory.resolve("$SAMPLE_ID.ready").exists())
    }

    companion object {
        private const val SAMPLE_ID = "11111111-1111-1111-1111-111111111111"
    }
}
