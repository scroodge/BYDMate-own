package com.bydmate.app.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.local.entity.SettingEntity
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
    fun `backlog drain sends multiple active batches in one flush`() = runTest {
        val setup = setup()
        for (second in 1..30) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.enqueue(snapshot(charging = true, soc = 98))
        }
        assertEquals(30, setup.queue.items.count { it.sentAt == null })

        setup.now = BASE_TIME_MS + 31_000L
        val flush = setup.sender.flushPending()
        assertTrue(flush.isSuccess)
        assertEquals(2, setup.client.payloads.size)
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
        assertEquals(0, setup.queue.items.count { it.sentAt == null })
    }

    private fun setup(
        results: ArrayDeque<CloudSendResult> = ArrayDeque(),
    ): TestSetup {
        val settingsDao = FakeSettingsDao(
            mapOf(
                SettingsRepository.KEY_CLOUD_SYNC_ENABLED to "true",
                SettingsRepository.KEY_CLOUD_SYNC_URL to SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
                SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID to "way",
                SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC to "60",
                SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY to "false",
                SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS to "false",
            )
        )
        val queue = FakeCloudSyncQueueDao()
        val client = FakeCloudTelemetryClient(results)
        var now = 0L
        val sender = CloudTelemetrySender(
            context = ApplicationProvider.getApplicationContext<Context>(),
            settingsRepository = SettingsRepository(settingsDao),
            queueDao = queue,
            client = client,
        ).apply {
            nowProvider = { now }
        }
        return TestSetup(sender, queue, client, getNow = { now }, setNow = { now = it })
    }

    private fun snapshot(
        charging: Boolean = false,
        speedKmh: Double = 0.0,
        soc: Int = 50,
        gear: Int? = null,
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
                powerState = null,
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
        val client: FakeCloudTelemetryClient,
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

        override suspend fun send(
            url: String,
            apiKey: String,
            vehicleId: String,
            payloadJson: String,
        ): CloudSendResult {
            payloads += payloadJson
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

    private class FakeSettingsDao(initial: Map<String, String>) : SettingsDao {
        private val values = initial.mapValues { it.value as String? }.toMutableMap()

        override suspend fun get(key: String): String? = values[key]

        override fun observe(key: String): Flow<String?> = flowOf(values[key])

        override suspend fun set(setting: SettingEntity) {
            values[setting.key] = setting.value
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
