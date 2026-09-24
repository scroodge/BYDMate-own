package com.bydmate.app.service

import com.bydmate.app.daemon.CommandDaemon
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B-18: the keep-WiFi toggle lived only in Room while the daemon read `voltflow_cmd.conf`,
 * which was exported once per TrackingService start. These pin the whole app-side chain —
 * a settings change with no service restart, through the written file, to the daemon's own
 * [CommandDaemon.loadConf] — so the two cannot drift apart again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaemonConfigExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val linked = mapOf(
        SettingsRepository.KEY_CLOUD_SYNC_URL to "https://example.test/api/bydmate/telemetry",
        SettingsRepository.KEY_CLOUD_SYNC_API_KEY to "key-1",
        SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID to "car1",
        SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE to "false",
    )

    @Test
    fun `toggling keep-WiFi without a service restart reaches the daemon's parsed config`() = runTest {
        val repo = SettingsRepository(ObservableSettingsDao(linked))
        val dir = tmp.newFolder()
        val confPath = java.io.File(dir, DaemonConfigExport.FILE_NAME).path

        // Same shape as TrackingService: one long-lived collector writing every emission.
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            DaemonConfigExport.observe(repo).collect { text ->
                if (text != null) DaemonConfigExport.write(dir, text)
            }
        }

        assertFalse(CommandDaemon.loadConf(confPath)!!.keepWifiAwake)

        repo.setString(SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE, "true")
        assertTrue(CommandDaemon.loadConf(confPath)!!.keepWifiAwake)

        repo.setString(SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE, "false")
        assertFalse(CommandDaemon.loadConf(confPath)!!.keepWifiAwake)

        job.cancel()
    }

    @Test
    fun `rendered file carries the creds the daemon needs`() {
        val dir = tmp.newFolder()
        val text = DaemonConfigExport.render(
            url = " https://example.test/api/bydmate/telemetry ",
            apiKey = "key-1",
            vehicleId = "car1",
            keepWifiAwake = true,
        )!!
        val conf = CommandDaemon.loadConf(DaemonConfigExport.write(dir, text).path)!!

        assertEquals("https://example.test/api/bydmate/telemetry", conf.telemetryUrl)
        assertEquals("https://example.test/api/bydmate/commands", conf.commandsUrl)
        assertEquals("key-1", conf.apiKey)
        assertEquals("car1", conf.vehicleId)
        assertTrue(conf.keepWifiAwake)
        assertFalse(java.io.File(dir, "${DaemonConfigExport.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `unlinked car renders nothing so an existing file is left alone`() {
        assertNull(DaemonConfigExport.render("https://x.test/api/bydmate/telemetry", "", "car1", true))
        assertNull(DaemonConfigExport.render("https://x.test/api/bydmate/telemetry", "key", " ", true))
        assertNull(DaemonConfigExport.render("", "key", "car1", true))
    }

    /** Room-like: [observe] re-emits on every [set], which the one-shot fakes elsewhere don't. */
    private class ObservableSettingsDao(initial: Map<String, String>) : SettingsDao {
        private val state = MutableStateFlow(initial)
        override suspend fun get(key: String): String? = state.value[key]
        override fun observe(key: String): Flow<String?> = state.map { it[key] }
        override suspend fun set(setting: SettingEntity) {
            state.value = state.value + (setting.key to (setting.value ?: ""))
        }
        override suspend fun setLastKnownSoc(soc: String, timestamp: String) {
            state.value = state.value + mapOf(
                SettingsRepository.KEY_LAST_KNOWN_SOC to soc,
                SettingsRepository.KEY_LAST_SOC_TIMESTAMP to timestamp,
            )
        }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }
}
