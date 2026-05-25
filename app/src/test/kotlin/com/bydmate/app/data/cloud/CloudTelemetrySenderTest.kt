package com.bydmate.app.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.local.entity.SettingEntity
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
    fun `charging samples enqueue about every second without flushing before one minute`() = runTest {
        val setup = setup()

        for (second in 1..59) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true))
        }

        assertEquals(59, setup.queue.items.count { it.sentAt == null })
        assertEquals(0, setup.client.payloads.size)
    }

    @Test
    fun `charging samples flush as one batch each minute`() = runTest {
        val setup = setup()

        for (second in 1..60) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true))
        }

        assertEquals(1, setup.client.payloads.size)
        val samples = JSONObject(setup.client.payloads.single()).getJSONArray("samples")
        assertEquals(60, samples.length())
        assertEquals(0, setup.queue.items.count { it.sentAt == null })
    }

    @Test
    fun `moving samples still enqueue about every 60 seconds`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot(speedKmh = 10.0))
        setup.now = BASE_TIME_MS + 60_000L
        setup.sender.send(snapshot(speedKmh = 10.0))
        setup.now = BASE_TIME_MS + 61_000L
        setup.sender.send(snapshot(speedKmh = 10.0))

        assertEquals(2, setup.queue.items.size)
    }

    @Test
    fun `stopped heartbeat remains five minutes`() = runTest {
        val setup = setup()

        setup.now = BASE_TIME_MS + 1_000L
        setup.sender.send(snapshot())
        setup.now = BASE_TIME_MS + 299_000L
        setup.sender.send(snapshot())
        setup.now = BASE_TIME_MS + 301_000L
        setup.sender.send(snapshot())

        assertEquals(2, setup.queue.items.size)
    }

    @Test
    fun `retry keeps accumulated charging samples for later batch`() = runTest {
        val setup = setup(results = ArrayDeque(listOf(CloudSendResult.RetryableFailure("offline"))))

        for (second in 1..60) {
            setup.now = BASE_TIME_MS + second * 1_000L
            setup.sender.send(snapshot(charging = true))
        }

        assertEquals(1, setup.client.payloads.size)
        assertEquals(60, setup.queue.items.count { it.sentAt == null })
        assertTrue(setup.queue.items.all { it.attempts == 1 })

        setup.client.results.add(CloudSendResult.Success("{}"))
        setup.now = BASE_TIME_MS + 120_000L
        setup.sender.send(snapshot(charging = true))

        assertEquals(2, setup.client.payloads.size)
        val retrySamples = JSONObject(setup.client.payloads.last()).getJSONArray("samples")
        assertEquals(61, retrySamples.length())
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
    ) = VehicleTelemetrySnapshot(
        capturedAtMs = 1_700_000_000_000L,
        deviceTimeIso = "2023-11-14T22:13:20Z",
        diPlusData = null,
        soc = 50,
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
            return results.removeFirstOrNull() ?: CloudSendResult.Success("{}")
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
    }
}
