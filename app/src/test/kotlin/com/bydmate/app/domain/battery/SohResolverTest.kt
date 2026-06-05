package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SohResolverTest {

    private class StubBatterySnapshotDao(
        private val lastValue: BatterySnapshotEntity?,
    ) : com.bydmate.app.data.local.dao.BatterySnapshotDao {
        override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override fun getRecent(limit: Int): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0
        override suspend fun getLast(): BatterySnapshotEntity? = lastValue
        override suspend fun getCount(): Int = 0
    }

    private fun fakeSettings(initial: Map<String, String> = emptyMap()): SettingsRepository {
        val map = initial.toMutableMap()
        val dao = object : com.bydmate.app.data.local.dao.SettingsDao {
            override suspend fun get(key: String): String? = map[key]
            override fun observe(key: String): Flow<String?> = flowOf(map[key])
            override suspend fun set(entity: com.bydmate.app.data.local.entity.SettingEntity) {
                map[entity.key] = entity.value ?: ""
            }
            override fun getAll(): Flow<List<com.bydmate.app.data.local.entity.SettingEntity>> = flowOf(emptyList())
        }
        return SettingsRepository(dao)
    }

    @Test
    fun `live BMS SoH wins and persists to settings cache`() = runTest {
        val settings = fakeSettings()
        val resolver = SohResolver(
            BatteryHealthRepository(StubBatterySnapshotDao(null)),
            settings,
        )

        val result = resolver.resolveSohPercent(BatteryReading(97.5f, 80f, null, null, null, 0L))

        assertEquals(97.5, result!!, 0.01)
        assertEquals(97.5, settings.getLastKnownSohPercent()!!, 0.01)
    }

    @Test
    fun `falls back to last battery health snapshot`() = runTest {
        val snap = BatterySnapshotEntity(
            timestamp = 0L,
            socStart = 30,
            socEnd = 80,
            kwhCharged = 36.0,
            calculatedCapacityKwh = 72.0,
            sohPercent = 98.7,
        )
        val resolver = SohResolver(
            BatteryHealthRepository(StubBatterySnapshotDao(snap)),
            fakeSettings(),
        )

        val result = resolver.resolveSohPercent(BatteryReading(null, 80f, null, null, null, 0L))

        assertEquals(98.7, result!!, 0.01)
    }

    @Test
    fun `falls back to settings cache when live and snapshot absent`() = runTest {
        val settings = fakeSettings(
            mapOf(SettingsRepository.KEY_LAST_KNOWN_SOH to "99.2"),
        )
        val resolver = SohResolver(
            BatteryHealthRepository(StubBatterySnapshotDao(null)),
            settings,
        )

        val result = resolver.resolveSohPercent(null)

        assertEquals(99.2, result!!, 0.01)
    }

    @Test
    fun `returns null when no SoH source available`() = runTest {
        val resolver = SohResolver(
            BatteryHealthRepository(StubBatterySnapshotDao(null)),
            fakeSettings(),
        )

        assertNull(resolver.resolveSohPercent(null))
    }
}
