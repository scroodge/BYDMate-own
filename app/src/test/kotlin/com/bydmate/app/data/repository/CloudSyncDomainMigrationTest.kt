package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Domain migration: move an already-paired car off the retired volt-flow-beige.vercel.app
 * host onto voltflow.life on upgrade, without a re-link, while never touching a user's own
 * custom endpoint. See SettingsRepository.migrateCloudSyncDomainIfNeeded.
 */
class CloudSyncDomainMigrationTest {

    @Test
    fun `legacy vercel host is rewritten to the new default`() = runTest {
        val dao = FakeSettingsDao(
            mutableMapOf(
                SettingsRepository.KEY_CLOUD_SYNC_URL to
                    "https://volt-flow-beige.vercel.app/api/bydmate/telemetry",
            )
        )
        val repo = SettingsRepository(dao)

        assertTrue(repo.migrateCloudSyncDomainIfNeeded())

        assertEquals(
            SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
            dao.get(SettingsRepository.KEY_CLOUD_SYNC_URL),
        )
    }

    @Test
    fun `blank url is filled with the new default`() = runTest {
        val dao = FakeSettingsDao(mutableMapOf(SettingsRepository.KEY_CLOUD_SYNC_URL to ""))
        val repo = SettingsRepository(dao)

        assertTrue(repo.migrateCloudSyncDomainIfNeeded())

        assertEquals(
            SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
            dao.get(SettingsRepository.KEY_CLOUD_SYNC_URL),
        )
    }

    @Test
    fun `a user's custom endpoint is left untouched`() = runTest {
        val custom = "https://my-nas.example.com/ingest"
        val dao = FakeSettingsDao(
            mutableMapOf(SettingsRepository.KEY_CLOUD_SYNC_URL to custom)
        )
        val repo = SettingsRepository(dao)

        assertFalse(repo.migrateCloudSyncDomainIfNeeded())

        assertEquals(custom, dao.get(SettingsRepository.KEY_CLOUD_SYNC_URL))
    }

    @Test
    fun `migration runs once — a second call is a no-op even if the url reverts`() = runTest {
        val dao = FakeSettingsDao(
            mutableMapOf(
                SettingsRepository.KEY_CLOUD_SYNC_URL to
                    "https://volt-flow-beige.vercel.app/api/bydmate/telemetry",
            )
        )
        val repo = SettingsRepository(dao)

        assertTrue(repo.migrateCloudSyncDomainIfNeeded())
        // simulate the user manually re-entering the old host afterwards
        dao.set(
            SettingEntity(
                SettingsRepository.KEY_CLOUD_SYNC_URL,
                "https://volt-flow-beige.vercel.app/api/bydmate/telemetry",
            )
        )

        assertFalse("flag set — must not rewrite again", repo.migrateCloudSyncDomainIfNeeded())
        assertEquals(
            "https://volt-flow-beige.vercel.app/api/bydmate/telemetry",
            dao.get(SettingsRepository.KEY_CLOUD_SYNC_URL),
        )
    }

    private class FakeSettingsDao(
        private val store: MutableMap<String, String> = mutableMapOf(),
    ) : SettingsDao {
        override suspend fun get(key: String): String? = store[key]
        override fun observe(key: String): Flow<String?> = flowOf(store[key])
        override suspend fun set(setting: SettingEntity) {
            store[setting.key] = setting.value ?: ""
        }
        override fun getAll(): Flow<List<SettingEntity>> =
            flowOf(store.map { SettingEntity(it.key, it.value) })
    }
}
