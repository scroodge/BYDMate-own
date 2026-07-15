package com.bydmate.app.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripSummaryCloudSyncTest {

    @Test
    fun `sends new energydata trips and advances watermark`() = runTest {
        val setup = setup(trips = listOf(trip(startTs = T0, endTs = T0 + 1_800_000L)))

        val result = setup.sync.syncNewTrips()

        assertNull(result.error)
        assertEquals(1, result.sent)
        assertEquals(1, setup.client.payloads.size)
        val batch = JSONArray(setup.client.payloads.single())
        assertEquals(1, batch.length())
        val entry = batch.getJSONObject(0)
        assertEquals(T0 / 1000L, entry.getLong("start_timestamp"))
        assertEquals((T0 + 1_800_000L) / 1000L, entry.getLong("end_timestamp"))
        assertEquals(12.4, entry.getDouble("distance_km"), 0.001)
        assertEquals(1.9, entry.getDouble("energy_kwh"), 0.001)
        assertEquals(1800L, entry.getLong("duration_seconds"))
        assertEquals(T0.toString(), setup.settings.map[SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS])
        assertEquals(
            "https://voltflow.life/api/bydmate/trip-summaries",
            setup.client.urls.single(),
        )
    }

    @Test
    fun `already synced trips are not resent`() = runTest {
        val setup = setup(trips = listOf(trip(startTs = T0, endTs = T0 + 600_000L)))

        setup.sync.syncNewTrips()
        setup.sync.syncNewTrips()

        assertEquals(1, setup.client.payloads.size)
    }

    @Test
    fun `zero km idle records are skipped but watermark still advances`() = runTest {
        val setup = setup(
            trips = listOf(
                trip(startTs = T0, endTs = T0 + 600_000L, distanceKm = 0.0),
                trip(startTs = T0 + 3_600_000L, endTs = T0 + 4_200_000L),
            ),
        )

        val result = setup.sync.syncNewTrips()

        assertEquals(1, result.sent)
        assertEquals(1, result.skipped)
        assertEquals(1, JSONArray(setup.client.payloads.single()).length())
        assertEquals(
            (T0 + 3_600_000L).toString(),
            setup.settings.map[SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS],
        )
    }

    @Test
    fun `all skipped records still advance watermark without a post`() = runTest {
        val setup = setup(trips = listOf(trip(startTs = T0, endTs = null)))

        val result = setup.sync.syncNewTrips()

        assertEquals(0, result.sent)
        assertEquals(1, result.skipped)
        assertEquals(0, setup.client.payloads.size)
        assertEquals(T0.toString(), setup.settings.map[SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS])
    }

    @Test
    fun `diplus data source sends nothing`() = runTest {
        val setup = setup(
            trips = listOf(trip(startTs = T0, endTs = T0 + 600_000L)),
            settings = mapOf(SettingsRepository.KEY_DATA_SOURCE to "DIPLUS"),
        )

        val result = setup.sync.syncNewTrips()

        assertEquals(0, result.sent)
        assertEquals(0, setup.client.payloads.size)
    }

    @Test
    fun `disabled cloud sync sends nothing`() = runTest {
        val setup = setup(
            trips = listOf(trip(startTs = T0, endTs = T0 + 600_000L)),
            settings = mapOf(SettingsRepository.KEY_CLOUD_SYNC_ENABLED to "false"),
        )

        assertEquals(0, setup.sync.syncNewTrips().sent)
        assertEquals(0, setup.client.payloads.size)
    }

    @Test
    fun `missing api key sends nothing`() = runTest {
        val setup = setup(
            trips = listOf(trip(startTs = T0, endTs = T0 + 600_000L)),
            settings = mapOf(SettingsRepository.KEY_CLOUD_SYNC_API_KEY to ""),
        )

        assertEquals(0, setup.sync.syncNewTrips().sent)
        assertEquals(0, setup.client.payloads.size)
    }

    @Test
    fun `retryable failure keeps watermark and retries on next sync`() = runTest {
        val setup = setup(
            trips = listOf(trip(startTs = T0, endTs = T0 + 600_000L)),
            results = ArrayDeque(listOf(CloudSendResult.RetryableFailure("offline"))),
        )

        val first = setup.sync.syncNewTrips()
        assertNotNull(first.error)
        assertNull(setup.settings.map[SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS])

        val second = setup.sync.syncNewTrips()
        assertNull(second.error)
        assertEquals(1, second.sent)
        assertEquals(2, setup.client.payloads.size)
    }

    @Test
    fun `ok false response does not advance watermark`() = runTest {
        val setup = setup(
            trips = listOf(trip(startTs = T0, endTs = T0 + 600_000L)),
            results = ArrayDeque(listOf(CloudSendResult.Success("""{"ok":false,"error":"Unauthorized"}"""))),
        )

        val result = setup.sync.syncNewTrips()

        assertNotNull(result.error)
        assertNull(setup.settings.map[SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS])
    }

    @Test
    fun `large backlog is chunked into batches of three hundred`() = runTest {
        val trips = (0 until 450).map { i ->
            trip(startTs = T0 + i * 3_600_000L, endTs = T0 + i * 3_600_000L + 600_000L)
        }
        val setup = setup(trips = trips)

        val result = setup.sync.syncNewTrips()

        assertEquals(450, result.sent)
        assertEquals(2, setup.client.payloads.size)
        assertEquals(300, JSONArray(setup.client.payloads[0]).length())
        assertEquals(150, JSONArray(setup.client.payloads[1]).length())
    }

    @Test
    fun `out of range records are filtered so the batch is not rejected`() = runTest {
        val setup = setup(
            trips = listOf(
                trip(startTs = T0, endTs = T0 + 600_000L, distanceKm = 5000.0), // > zod max
                trip(startTs = T0 + 3_600_000L, endTs = T0 + 4_200_000L, kwhConsumed = 900.0), // > zod max
                trip(startTs = T0 + 7_200_000L, endTs = T0 + 7_800_000L),
            ),
        )

        val result = setup.sync.syncNewTrips()

        assertEquals(1, result.sent)
        assertEquals(2, result.skipped)
        assertEquals(1, JSONArray(setup.client.payloads.single()).length())
    }

    @Test
    fun `wifi only defers sending when not on wifi`() = runTest {
        val setup = setup(
            trips = listOf(trip(startTs = T0, endTs = T0 + 600_000L)),
            settings = mapOf(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY to "true"),
            wifiConnected = false,
        )

        assertEquals(0, setup.sync.syncNewTrips().sent)
        assertEquals(0, setup.client.payloads.size)
        assertNull(setup.settings.map[SettingsRepository.KEY_TRIP_SUMMARY_SYNC_TS])
    }

    @Test
    fun `custom endpoint url derives trip summaries path`() {
        assertEquals(
            "https://example.com/api/bydmate/trip-summaries",
            TripSummaryCloudSync.tripSummariesUrl("https://example.com/api/bydmate/telemetry"),
        )
        assertEquals(
            "https://example.com/api/bydmate/trip-summaries",
            TripSummaryCloudSync.tripSummariesUrl("https://example.com/api/bydmate/telemetry/"),
        )
        assertEquals(
            "https://example.com/api/bydmate/trip-summaries",
            TripSummaryCloudSync.tripSummariesUrl("https://example.com/api/bydmate"),
        )
    }

    @Test
    fun `non energydata local trips are never selected`() = runTest {
        // getEnergydataTripsSince filters by source in SQL; the fake mirrors that.
        val setup = setup(
            trips = listOf(
                trip(startTs = T0, endTs = T0 + 600_000L, source = "live"),
                trip(startTs = T0 + 3_600_000L, endTs = T0 + 4_200_000L, source = "diplus"),
            ),
        )

        assertEquals(0, setup.sync.syncNewTrips().sent)
        assertEquals(0, setup.client.payloads.size)
    }

    // --- Fixtures ---

    private fun setup(
        trips: List<TripEntity> = emptyList(),
        settings: Map<String, String> = emptyMap(),
        results: ArrayDeque<CloudSendResult> = ArrayDeque(),
        wifiConnected: Boolean = true,
    ): TestSetup {
        val settingsDao = FakeSettingsDao(
            mapOf(
                SettingsRepository.KEY_CLOUD_SYNC_ENABLED to "true",
                SettingsRepository.KEY_CLOUD_SYNC_API_KEY to "test-key",
                SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID to "way",
            ) + settings,
        )
        val client = FakeClient(results)
        val sync = TripSummaryCloudSync(
            context = ApplicationProvider.getApplicationContext<Context>(),
            settingsRepository = SettingsRepository(settingsDao),
            tripDao = FakeTripDao(trips),
            client = client,
        ).apply {
            nowProvider = { T0 }
            wifiChecker = { wifiConnected }
        }
        return TestSetup(sync, client, settingsDao)
    }

    private fun trip(
        startTs: Long,
        endTs: Long?,
        distanceKm: Double = 12.4,
        kwhConsumed: Double = 1.9,
        source: String = "energydata",
    ) = TripEntity(
        id = startTs,
        startTs = startTs,
        endTs = endTs,
        distanceKm = distanceKm,
        kwhConsumed = kwhConsumed,
        source = source,
        bydId = startTs,
    )

    private data class TestSetup(
        val sync: TripSummaryCloudSync,
        val client: FakeClient,
        val settings: FakeSettingsDao,
    )

    private class FakeClient(val results: ArrayDeque<CloudSendResult>) : CloudTelemetryClientApi {
        val payloads = mutableListOf<String>()
        val urls = mutableListOf<String>()

        override suspend fun send(
            url: String,
            apiKey: String,
            vehicleId: String,
            payloadJson: String,
        ): CloudSendResult {
            urls += url
            payloads += payloadJson
            return results.removeFirstOrNull()
                ?: CloudSendResult.Success(
                    """{"ok":true,"vehicle_id":"$vehicleId","inserted":${JSONArray(payloadJson).length()},"updated":0}""",
                )
        }
    }

    private class FakeSettingsDao(initial: Map<String, String>) : SettingsDao {
        val map = initial.toMutableMap()

        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(setting: SettingEntity) {
            map[setting.key] = setting.value ?: ""
        }
        override fun getAll(): Flow<List<SettingEntity>> =
            flowOf(map.map { SettingEntity(it.key, it.value) })
    }

    private class FakeTripDao(private val trips: List<TripEntity>) : TripDao {
        override suspend fun getEnergydataTripsSince(sinceTsMs: Long): List<TripEntity> =
            trips.filter { it.source == "energydata" && it.startTs > sinceTsMs }.sortedBy { it.startTs }

        override suspend fun insert(trip: TripEntity): Long = 0L
        override suspend fun update(trip: TripEntity) {}
        override fun getAll(): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): TripEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getTodaySummary(dayStart: Long, dayEnd: Long): TripSummary = TripSummary(0.0, 0.0)
        override fun getLastTrip(): Flow<TripEntity?> = flowOf(null)
        override fun getRecent(limit: Int): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getCount(): Int = 0
        override suspend fun getByBydId(bydId: Long): TripEntity? = null
        override suspend fun getTripsWithoutSoc(): List<TripEntity> = emptyList()
        override suspend fun getTripsWithoutCost(): List<TripEntity> = emptyList()
        override suspend fun getPeriodSummary(from: Long, to: Long): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getLiveTrips(): List<TripEntity> = emptyList()
        override suspend fun getByStartTsRange(minTs: Long, maxTs: Long): TripEntity? = null
        override suspend fun getAllSnapshot(): List<TripEntity> = emptyList()
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteZeroKmTrips(): Int = 0
        override suspend fun getTripsForCapacityEstimate(minSocDelta: Int, limit: Int): List<TripEntity> = emptyList()
        override suspend fun getRecentSummary(maxTrips: Int): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getRecentForEma(limit: Int): List<TripEntity> = emptyList()
        override suspend fun getForEmaSince(fromTs: Long): List<TripEntity> = emptyList()
        override suspend fun getRecentForEmaFiltered(minKm: Double, limit: Int): List<TripEntity> = emptyList()
    }

    private companion object {
        /** 2026-07-06T00:00:00Z in ms. */
        const val T0 = 1_782_950_400_000L
    }
}
