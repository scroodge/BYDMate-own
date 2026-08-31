package com.bydmate.app.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.dao.HourlyRollupDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripRollupDao
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.local.entity.HourlyRollupEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripRollupEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudTelemetrySenderTest {
    @Test
    fun `active samples enqueue every second and flush every fifteen seconds`() = runTest {
        val setup = setup()

        for (second in 1..15) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 98))
        }

        assertEquals(1, setup.client.payloads.size)
        val samples = JSONObject(setup.client.payloads.single()).getJSONArray("samples")
        assertEquals(15, samples.length())
        assertEquals(0, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `charging below tail soc throttles to ten second cadence`() = runTest {
        val setup = setup()

        // 1 Hz polls during a normal charge (soc below the 98% tail) collapse to one
        // queued sample per 10s: seconds 1, 11, 21 enqueue; the rest are skipped.
        for (second in 1..30) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.enqueue(snapshot(charging = true, soc = 60))
        }

        assertEquals(3, setup.queue.items.size)
    }

    @Test
    fun `queued telemetry does not persist cloud status on every poll`() = runTest {
        val setup = setup()

        for (second in 1..30) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.enqueue(snapshot(charging = true, soc = 60))
        }

        assertTrue(setup.settings.setCalls.isEmpty())
    }

    @Test
    fun `manual cloud test records the real network attempt time`() = runTest {
        val setup = setup()

        setup.sender.sendTest(snapshot())

        assertTrue(setup.settings.values[SettingsRepository.KEY_CLOUD_SYNC_LAST_TS] != null)
    }

    @Test
    fun `charging tail at or above ninety eight percent samples every second`() = runTest {
        val setup = setup()

        // Above the tail threshold the pack is balancing toward 100%, so we keep 1 Hz
        // to capture a precise cell delta until the charge stops.
        for (second in 1..10) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.enqueue(snapshot(charging = true, soc = 98))
        }

        assertEquals(10, setup.queue.items.size)
    }

    @Test
    fun `charging below tail soc flushes every sixty seconds not fifteen`() = runTest {
        val setup = setup()

        // soc=60 (bulk) samples at 10s -> 5 queued samples over 45s. Under the old 15s
        // active flush this would already have POSTed a few times; charging-bulk uses a
        // 60s cadence, so nothing flushes before 60s.
        for (second in 1..45) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 60))
        }
        assertEquals(0, setup.client.payloads.size)

        // 60s after the first queued sample, the accumulated bulk batch flushes once.
        setup.now = BASE_TIME_MS + 61_000L
        setup.sender.send(snapshot(charging = true, soc = 60))
        assertEquals(1, setup.client.payloads.size)
        assertEquals(0, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `moving samples enqueue every second`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(speedKmh = 10.0))
        setup.now = BASE_TIME_MS + 1_500L
        setup.sender.send(snapshot(speedKmh = 10.0))
        setup.now = BASE_TIME_MS + 2_000L
        setup.sender.send(snapshot(speedKmh = 10.0))

        assertEquals(2, setup.queue.items.size)
    }

    @Test
    fun `parked heartbeat enqueues every thirty seconds`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 1))
        setup.now = BASE_TIME_MS + 29_000L
        setup.sender.send(snapshot(gear = 1))
        setup.now = BASE_TIME_MS + 31_000L
        setup.sender.send(snapshot(gear = 1, soc = 51))

        assertEquals(2, setup.queue.items.size)
    }

    @Test
    fun `parked unchanged idle still heartbeats every thirty seconds`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 1))
        setup.now = BASE_TIME_MS + 31_000L
        setup.sender.send(snapshot(gear = 1))
        setup.now = BASE_TIME_MS + 61_000L
        setup.sender.send(snapshot(gear = 1))
        setup.now = BASE_TIME_MS + 91_000L
        setup.sender.send(snapshot(gear = 1))

        assertEquals(4, setup.queue.items.size)
    }

    @Test
    fun `brief P during drive keeps one hertz cadence`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 4, speedKmh = 40.0))

        for (second in 2..65) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(gear = 1, speedKmh = 0.0))
        }

        assertEquals(65, setup.queue.items.size)
    }

    @Test
    fun `gear change enqueues immediately while parked`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 4, speedKmh = 0.0))
        setup.now = BASE_TIME_MS + 2_000L
        setup.sender.send(snapshot(gear = 1, speedKmh = 0.0))

        assertEquals(2, setup.queue.items.size)
    }

    @Test
    fun `confirmed P to power off flushes final queued telemetry immediately`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 4, speedKmh = 30.0, powerState = 1))
        setup.now = BASE_TIME_MS + 2_000L
        setup.sender.send(snapshot(gear = 1, speedKmh = 0.0, powerState = 1))
        // The drive latch remains active here, so this must not flush just because gear is P.
        assertEquals(0, setup.client.payloads.size)

        setup.now = BASE_TIME_MS + 3_000L
        setup.sender.send(snapshot(gear = 1, speedKmh = 0.0, powerState = 0))

        assertEquals(1, setup.client.payloads.size)
        assertEquals(0, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `incomplete ack with skipped stale keeps queue`() = runTest {
        val setup = setup(
            results = ArrayDeque(
                listOf(
                    CloudSendResult.Success(
                        """{"ok":true,"inserted_count":0,"duplicate_count":0,"skipped_stale_count":15,"sample_count":0}""",
                    ),
                ),
            ),
        )

        for (second in 1..15) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 98))
        }

        assertEquals(15, setup.queue.items.count { it.sentAt == null })
        assertTrue(setup.queue.items.all { it.attempts >= 1 })
    }

    @Test
    fun `ok false on http 200 keeps queue`() = runTest {
        val setup = setup(
            results = ArrayDeque(
                listOf(
                    CloudSendResult.Success("""{"ok":false,"error":"telemetry missing after persist"}"""),
                ),
            ),
        )

        for (second in 1..15) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 98))
        }

        assertEquals(15, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `backlog drain coalesces active rows into one bounded batch`() = runTest {
        val setup = setup()
        for (second in 1..30) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.enqueue(snapshot(charging = true, soc = 98))
        }
        assertEquals(30, setup.queue.items.count { it.sentAt == null })

        setup.now = BASE_TIME_MS + 31_000L
        val flush = setup.sender.flushPending()
        assertTrue(flush.isSuccess)
        assertEquals(1, setup.client.payloads.size)
        assertEquals(30, JSONObject(setup.client.payloads.single()).getJSONArray("samples").length())
        assertEquals(0, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `retry keeps accumulated active samples for later batch`() = runTest {
        val setup = setup(results = ArrayDeque(listOf(CloudSendResult.RetryableFailure("offline"))))

        for (second in 1..15) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 98))
        }

        assertEquals(1, setup.client.payloads.size)
        assertEquals(15, setup.queue.items.count { it.sentAt == null })
        assertTrue(setup.queue.items.all { it.attempts == 1 })

        setup.client.results.add(CloudSendResult.Success(fullAckJson(15)))
        setup.now = BASE_TIME_MS + 30_000L
        val flush = setup.sender.flushPending()
        assertTrue(flush.isSuccess)

        assertEquals(2, setup.client.payloads.size)
        val retrySamples = JSONObject(setup.client.payloads.last()).getJSONArray("samples")
        assertEquals(15, retrySamples.length())
        val firstAttemptSamples = JSONObject(setup.client.payloads.first()).getJSONArray("samples")
        assertEquals(
            firstAttemptSamples.getJSONObject(0).getString("device_time"),
            retrySamples.getJSONObject(0).getString("device_time"),
        )
        assertEquals(0, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `retry backoff survives sender restart`() = runTest {
        val setup = setup(
            results = ArrayDeque(listOf(CloudSendResult.RetryableFailure("offline"))),
            jitterFractions = ArrayDeque(listOf(1.0)),
        )
        repeat(15) { index -> setup.queue.insert(queueRow("way", index.toLong())) }
        setup.now = BASE_TIME_MS

        setup.sender.flushPending()

        val nextAttemptAt = requireNotNull(
            setup.settings.values[SettingsRepository.KEY_CLOUD_SYNC_NEXT_ATTEMPT_AT],
        ).toLong()
        assertEquals("1", setup.settings.values[SettingsRepository.KEY_CLOUD_SYNC_FAILURE_COUNT])

        val restarted = setup(
            queue = setup.queue,
            settings = setup.settings,
        )
        restarted.now = nextAttemptAt - 1
        restarted.sender.flushPending()
        assertEquals(0, restarted.client.payloads.size)

        restarted.now = nextAttemptAt
        restarted.sender.flushPending()
        assertEquals(1, restarted.client.payloads.size)
    }

    @Test
    fun `retry after delays the next persisted attempt`() = runTest {
        val setup = setup(
            results = ArrayDeque(
                listOf(CloudSendResult.RetryableFailure("rate limited", retryAfterMs = 120_000L)),
            ),
            jitterFractions = ArrayDeque(listOf(0.0)),
        )
        repeat(15) { index -> setup.queue.insert(queueRow("way", index.toLong())) }
        setup.now = BASE_TIME_MS

        setup.sender.flushPending()

        assertEquals(
            BASE_TIME_MS + 120_000L,
            requireNotNull(
                setup.settings.values[SettingsRepository.KEY_CLOUD_SYNC_NEXT_ATTEMPT_AT],
            ).toLong(),
        )
    }

    @Test
    fun `backlog batch never exceeds server cap of three hundred samples`() = runTest {
        val setup = setup()
        repeat(600) { index -> setup.queue.insert(queueRow("way", index.toLong())) }
        setup.now = BASE_TIME_MS

        setup.sender.flushPending()

        assertEquals(1, setup.client.payloads.size)
        assertEquals(
            300,
            JSONObject(setup.client.payloads.single()).getJSONArray("samples").length(),
        )
        assertEquals(300, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `backlog token bucket permits at most one batch every two seconds`() = runTest {
        val setup = setup()
        repeat(900) { index -> setup.queue.insert(queueRow("way", index.toLong())) }
        setup.now = BASE_TIME_MS

        setup.sender.flushPending()
        assertEquals(1, setup.client.payloads.size)

        // A process restart must not refill the bucket early.
        val restarted = setup(queue = setup.queue, settings = setup.settings)
        restarted.now = BASE_TIME_MS + 1_999L
        restarted.sender.flushPending()
        assertEquals(0, restarted.client.payloads.size)

        restarted.now = BASE_TIME_MS + 2_000L
        restarted.sender.flushPending()
        assertEquals(1, restarted.client.payloads.size)
    }

    /**
     * Regression: a queued row carries the vehicle_id it was recorded with. If the user edits
     * their vehicle id in Settings while rows are still queued, those older rows must NOT be
     * sent under the new id — the server compares the X-Vehicle-Id header against the
     * vehicle_id in each sample and rejects the whole batch on a mismatch, silently losing
     * good rows along with the stale ones.
     */
    @Test
    fun `queued rows are sent under the vehicle id in their body, not the current setting`() = runTest {
        val setup = setup()   // current setting is "way"
        setup.queue.insert(queueRow(vehicleId = "old-car", createdAt = 1L))
        setup.queue.insert(queueRow(vehicleId = "old-car", createdAt = 2L))
        setup.setNow(10 * 60_000L)   // past the flush interval

        setup.sender.flushPending()

        assertTrue("expected at least one send", setup.client.payloads.isNotEmpty())
        assertEquals("old-car", setup.client.vehicleIds.first())

        // and the header agrees with every body in that batch
        val first = JSONObject(setup.client.payloads.first())
        val samples = if (first.has("samples")) first.getJSONArray("samples")
            else org.json.JSONArray().put(first)
        for (i in 0 until samples.length()) {
            assertEquals("old-car", samples.getJSONObject(i).getString("vehicle_id"))
        }
    }

    @Test
    fun `driving samples open and extend a trip, gear P closes it without joining`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.enqueue(snapshot(gear = 4, speedKmh = 40.0))
        setup.now = BASE_TIME_MS + 2_000L
        setup.sender.enqueue(snapshot(gear = 4, speedKmh = 45.0))

        assertEquals(1, setup.trips.items.size)
        val open = setup.trips.items.single()
        assertEquals(2, open.sampleCount)
        assertEquals(null, open.endedAt)
        assertTrue(open.dirty)

        setup.now = BASE_TIME_MS + 3_000L
        setup.sender.enqueue(snapshot(gear = 1, speedKmh = 0.0))

        assertEquals(1, setup.trips.items.size)
        val closed = setup.trips.items.single()
        // The gear->P sample that triggered the close did not itself join the trip.
        assertEquals(2, closed.sampleCount)
        assertEquals(open.lastDeviceTime, closed.endedAt)
        assertTrue(closed.dirty)
    }

    @Test
    fun `a resumed drive after gear P opens a new trip, not the closed one`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.enqueue(snapshot(gear = 4, speedKmh = 40.0))
        setup.now = BASE_TIME_MS + 2_000L
        setup.sender.enqueue(snapshot(gear = 1, speedKmh = 0.0))
        val firstTripId = setup.trips.items.single().tripId

        setup.now = BASE_TIME_MS + 3_000L
        setup.sender.enqueue(snapshot(gear = 4, speedKmh = 30.0))

        assertEquals(2, setup.trips.items.size)
        val second = setup.trips.items.first { it.tripId != firstTripId }
        assertEquals(null, second.endedAt)
        assertEquals(1, second.sampleCount)
    }

    @Test
    fun `flush attaches the dirty trip block alongside samples once it flushes`() = runTest {
        val setup = setup()

        for (second in 1..15) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(gear = 4, speedKmh = 40.0))
        }

        assertEquals(1, setup.client.payloads.size)
        val batch = JSONObject(setup.client.payloads.single())
        val trips = batch.getJSONArray("trips")
        assertEquals(1, trips.length())
        assertEquals(15, trips.getJSONObject(0).getInt("sample_count"))
        assertTrue(setup.trips.items.single().dirty.not())
    }

    @Test
    fun `charging start pings the live snapshot immediately without draining the queue`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 1))   // parked baseline; flushes as batch #1
        assertEquals(1, setup.client.payloads.size)

        setup.now = BASE_TIME_MS + 2_000L
        setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))

        // The transition ping went out at once as a single live_only payload…
        assertEquals(2, setup.client.payloads.size)
        val ping = JSONObject(setup.client.payloads.last())
        assertTrue(ping.optBoolean("live_only"))
        assertTrue(!ping.has("samples"))
        // …while the full charging sample stays queued for the 60s bulk batch.
        assertEquals(1, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `charging stop pings the live snapshot immediately`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 1))
        setup.now = BASE_TIME_MS + 2_000L
        setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        setup.now = BASE_TIME_MS + 3_000L
        setup.sender.send(snapshot(charging = false, gear = 1))

        // batch #1 (parked), ping (charge start), ping (unplug)
        assertEquals(3, setup.client.payloads.size)
        val ping = JSONObject(setup.client.payloads.last())
        assertTrue(ping.optBoolean("live_only"))
        assertTrue(!ping.has("samples"))
    }

    @Test
    fun `status ping does not reset the sixty second charging bulk flush`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(gear = 1))   // parked baseline; batch #1

        // Charging at 10s cadence; the start-transition ping is payload #2 and must not
        // push back the bulk batch that carries the samples auto-start needs.
        for (second in 2..61) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        }
        assertEquals(2, setup.client.payloads.size)

        setup.now = BASE_TIME_MS + 62_000L
        setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        assertEquals(3, setup.client.payloads.size)
        val bulk = JSONObject(setup.client.payloads.last())
        assertTrue(bulk.getJSONArray("samples").length() >= 4)
    }

    @Test
    fun `steady states never ping`() = runTest {
        val setup = setup()

        for (second in 1..90 step 10) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        }
        setup.client.payloads.forEach { payload ->
            assertTrue("unexpected single-sample ping", JSONObject(payload).has("samples"))
        }
    }

    @Test
    fun `live fast mode pushes status every three seconds without queueing history`() = runTest {
        val setup = setup()
        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        val baseline = setup.client.payloads.size
        val queuedBefore = setup.queue.items.size

        setup.sender.onLiveFastGranted(20)

        // 12s of steady charging: without fast mode this queues at 10s and flushes at 60s,
        // so nothing would be POSTed at all in this window.
        for (second in 2..13) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        }

        val pings = setup.client.payloads.drop(baseline)
        assertTrue("expected ~4 pings in 12s, got ${pings.size}", pings.size >= 3)
        pings.forEach { payload ->
            val json = JSONObject(payload)
            assertTrue(json.optBoolean("live_only"))
            assertTrue(!json.has("samples"))
        }
        // Fast mode must not inflate stored history — only the 10s charging cadence does.
        assertTrue(setup.queue.items.size - queuedBefore <= 2)
    }

    @Test
    fun `live fast mode lapses on its own so a closed viewer stops the traffic`() = runTest {
        val setup = setup()
        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        setup.sender.onLiveFastGranted(10)

        setup.now = BASE_TIME_MS + 5_000L
        setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        val duringGrant = setup.client.payloads.size

        // Well past the 10s grant: no further pings, even though nothing else changed.
        for (second in 30..48 step 3) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        }
        assertEquals(duringGrant, setup.client.payloads.size)
    }

    @Test
    fun `a zero grant never enables fast mode`() = runTest {
        val setup = setup()
        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        val baseline = setup.client.payloads.size

        // What an older server (no live_fast_seconds key) reports every poll.
        setup.sender.onLiveFastGranted(0)

        for (second in 2..20) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true, soc = 60, gear = 1))
        }
        assertEquals(baseline, setup.client.payloads.size)
    }

    /** A queue row as it looks after being enqueued under [vehicleId]. */
    private fun queueRow(vehicleId: String, createdAt: Long) = CloudSyncQueueEntity(
        createdAt = createdAt,
        payloadJson = JSONObject()
            .put("vehicle_id", vehicleId)
            .put("soc", 55)
            .toString(),
    )

    private fun setup(
        results: ArrayDeque<CloudSendResult> = ArrayDeque(),
        jitterFractions: ArrayDeque<Double> = ArrayDeque(),
        queue: FakeCloudSyncQueueDao = FakeCloudSyncQueueDao(),
        settings: FakeSettingsDao = FakeSettingsDao(
            mapOf(
                SettingsRepository.KEY_CLOUD_SYNC_ENABLED to "true",
                SettingsRepository.KEY_CLOUD_SYNC_URL to SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
                SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID to "way",
                SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC to "60",
                SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY to "false",
                SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS to "false",
            )
        ),
    ): TestSetup {
        val hourly = FakeHourlyRollupDao()
        val trips = FakeTripRollupDao()
        val client = FakeCloudTelemetryClient(results)
        var now = 0L
        val sender = CloudTelemetrySender(
            context = ApplicationProvider.getApplicationContext<Context>(),
            settingsRepository = SettingsRepository(settings),
            queueDao = queue,
            hourlyDao = hourly,
            tripDao = trips,
            client = client,
        ).apply {
            nowProvider = { now }
            jitterFractionProvider = { jitterFractions.removeFirstOrNull() ?: 0.5 }
        }
        return TestSetup(sender, queue, trips, client, settings, getNow = { now }, setNow = { now = it })
    }

    private fun snapshot(
        charging: Boolean = false,
        speedKmh: Double = 0.0,
        soc: Int = 50,
        gear: Int? = null,
        powerState: Int? = null,
    ) = VehicleTelemetrySnapshot(
        capturedAtMs = 1_700_000_000_000L,
        deviceTimeIso = "2023-11-14T22:13:20Z",
        diPlusData = if (gear != null) {
            DiParsData(
                soc = soc,
                speed = speedKmh.toInt(),
                mileage = null,
                power = if (charging) -7.0 else 0.0,
                chargeGunState = if (charging) 2 else 1,
                maxBatTemp = null,
                avgBatTemp = null,
                minBatTemp = null,
                chargingStatus = null,
                batteryCapacityKwh = null,
                totalElecConsumption = null,
                voltage12v = null,
                maxCellVoltage = null,
                minCellVoltage = null,
                exteriorTemp = null,
                gear = gear,
                powerState = powerState,
                insideTemp = null,
                acStatus = null,
                acTemp = null,
                fanLevel = null,
                acCirc = null,
                doorFL = null,
                doorFR = null,
                doorRL = null,
                doorRR = null,
                windowFL = null,
                windowFR = null,
                windowRL = null,
                windowRR = null,
                sunroof = null,
                trunk = null,
                hood = null,
                seatbeltFL = null,
                lockFL = null,
                tirePressFL = null,
                tirePressFR = null,
                tirePressRL = null,
                tirePressRR = null,
                driveMode = null,
                workMode = null,
                autoPark = null,
                rain = null,
                lightLow = null,
                drl = null,
                sunshade = null,
                sentryState = null,
                remoteLockState = null,
            )
        } else {
            null
        },
        soc = soc,
        speedKmh = speedKmh,
        powerKw = if (charging) -7.0 else 0.0,
        batteryTempC = null,
        cabinTempC = null,
        outsideTempC = null,
        batteryVoltageV = null,
        auxVoltageV = null,
        cellVoltageMinV = null,
        cellVoltageMaxV = null,
        cellDeltaV = null,
        odometerKm = null,
        sohPercent = null,
        isCharging = charging,
        chargePowerKw = if (charging) 7.0 else 0.0,
        chargeType = if (charging) "AC" else null,
        kwhCharged = null,
        rangeEstKm = null,
        currentTripDistanceKm = null,
        currentTripConsumptionKwh100km = null,
        isParked = speedKmh <= 0.5,
        tirePressFL = null,
        tirePressFR = null,
        tirePressRL = null,
        tirePressRR = null,
        location = null,
    )

    private data class TestSetup(
        val sender: CloudTelemetrySender,
        val queue: FakeCloudSyncQueueDao,
        val trips: FakeTripRollupDao,
        val client: FakeCloudTelemetryClient,
        val settings: FakeSettingsDao,
        val getNow: () -> Long,
        val setNow: (Long) -> Unit,
    ) {
        var now: Long
            get() = getNow()
            set(value) = setNow(value)
    }

    private class FakeCloudTelemetryClient(
        val results: ArrayDeque<CloudSendResult>,
    ) : CloudTelemetryClientApi {
        val payloads = mutableListOf<String>()
        /** X-Vehicle-Id sent with each request, parallel to [payloads]. */
        val vehicleIds = mutableListOf<String>()

        override suspend fun send(
            url: String,
            apiKey: String,
            vehicleId: String,
            payloadJson: String,
        ): CloudSendResult {
            payloads += payloadJson
            vehicleIds += vehicleId
            return results.removeFirstOrNull() ?: CloudSendResult.Success(fullAckJson(300))
        }
    }

    private class FakeCloudSyncQueueDao : CloudSyncQueueDao {
        val items = mutableListOf<CloudSyncQueueEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: CloudSyncQueueEntity): Long {
            val id = nextId++
            items += entity.copy(id = id)
            return id
        }

        override suspend fun getUnsent(limit: Int): List<CloudSyncQueueEntity> =
            items.filter { it.sentAt == null }
                .sortedBy { it.createdAt }
                .take(limit)

        override suspend fun countUnsent(): Int = items.count { it.sentAt == null }

        override suspend fun markAttempt(id: Long, error: String) {
            update(id) { it.copy(attempts = it.attempts + 1, lastError = error) }
        }

        override suspend fun markFinished(id: Long, error: String?, sentAt: Long) {
            update(id) { it.copy(attempts = it.attempts + 1, lastError = error, sentAt = sentAt) }
        }

        override suspend fun pruneToMaxRows(maxRows: Int) {
            if (items.size <= maxRows) return
            val keep = items.sortedByDescending { it.createdAt }.take(maxRows).map { it.id }.toSet()
            items.removeAll { it.id !in keep }
        }

        private fun update(id: Long, transform: (CloudSyncQueueEntity) -> CloudSyncQueueEntity) {
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) items[index] = transform(items[index])
        }
    }

    private class FakeHourlyRollupDao : HourlyRollupDao {
        val items = mutableListOf<HourlyRollupEntity>()

        override suspend fun upsert(entity: HourlyRollupEntity) {
            val index = items.indexOfFirst { it.vehicleId == entity.vehicleId && it.hourStart == entity.hourStart }
            if (index >= 0) items[index] = entity else items += entity
        }

        override suspend fun find(vehicleId: String, hourStart: Long): HourlyRollupEntity? =
            items.firstOrNull { it.vehicleId == vehicleId && it.hourStart == hourStart }

        override suspend fun getDirty(limit: Int): List<HourlyRollupEntity> =
            items.filter { it.dirty }.sortedBy { it.hourStart }.take(limit)

        override suspend fun markClean(vehicleId: String, hourStart: Long, sampleCount: Int) {
            val index = items.indexOfFirst {
                it.vehicleId == vehicleId && it.hourStart == hourStart && it.sampleCount == sampleCount
            }
            if (index >= 0) items[index] = items[index].copy(dirty = false)
        }

        override suspend fun pruneCleanBefore(hourStart: Long) {
            items.removeAll { !it.dirty && it.hourStart < hourStart }
        }
    }

    private class FakeTripRollupDao : TripRollupDao {
        val items = mutableListOf<TripRollupEntity>()

        override suspend fun upsert(entity: TripRollupEntity) {
            val index = items.indexOfFirst { it.tripId == entity.tripId }
            if (index >= 0) items[index] = entity else items += entity
        }

        override suspend fun find(tripId: String): TripRollupEntity? =
            items.firstOrNull { it.tripId == tripId }

        override suspend fun findOpen(vehicleId: String): TripRollupEntity? =
            items.firstOrNull { it.vehicleId == vehicleId && it.endedAt == null }

        override suspend fun getDirty(limit: Int): List<TripRollupEntity> =
            items.filter { it.dirty }.sortedBy { it.startedAt }.take(limit)

        override suspend fun markClean(tripId: String, sampleCount: Int) {
            val index = items.indexOfFirst { it.tripId == tripId && it.sampleCount == sampleCount }
            if (index >= 0) items[index] = items[index].copy(dirty = false)
        }

        override suspend fun pruneCleanBefore(updatedAt: Long) {
            items.removeAll { !it.dirty && it.endedAt != null && it.updatedAt < updatedAt }
        }
    }

    private class FakeSettingsDao(initial: Map<String, String>) : SettingsDao {
        val values = initial.mapValues { it.value as String? }.toMutableMap()
        val setCalls = mutableListOf<SettingEntity>()

        override suspend fun get(key: String): String? = values[key]

        override fun observe(key: String): Flow<String?> = flowOf(values[key])

        override suspend fun set(setting: SettingEntity) {
            setCalls += setting
            values[setting.key] = setting.value
        }

        override suspend fun setLastKnownSoc(soc: String, timestamp: String) {
            values[SettingsRepository.KEY_LAST_KNOWN_SOC] = soc
            values[SettingsRepository.KEY_LAST_SOC_TIMESTAMP] = timestamp
        }

        override fun getAll(): Flow<List<SettingEntity>> =
            flowOf(values.map { SettingEntity(it.key, it.value) })
    }

    private companion object {
        const val BASE_TIME_MS = 1_700_000_000_000L

        fun fullAckJson(sent: Int) =
            """{"ok":true,"inserted_count":$sent,"duplicate_count":0,"skipped_stale_count":0,"sample_count":$sent}"""
    }
}
