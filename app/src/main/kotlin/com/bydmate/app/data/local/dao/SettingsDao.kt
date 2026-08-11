package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bydmate.app.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun set(setting: SettingEntity)

    /**
     * Keep the offline-charge baseline coherent if the process dies between writes: both rows
     * land in a single statement, so a kill can never leave the SOC without its capture time.
     *
     * `INSERT OR REPLACE`, deliberately not `ON CONFLICT ... DO UPDATE`. The UPSERT form needs
     * SQLite 3.24+, and while the DiLink head unit reports Android 10 it ships an older SQLite
     * than stock: it fails to compile the UPSERT with `near "ON": syntax error`, which threw on
     * every 1 Hz poll tick and silently took the whole cloud telemetry stream down with it.
     * REPLACE is equivalent here because `settings` is a bare (key, value) table — no sibling
     * columns to preserve on row replacement, and nothing references it.
     */
    @Query(
        """
        INSERT OR REPLACE INTO settings (`key`, value)
        VALUES ('last_known_soc', :soc), ('last_soc_timestamp', :timestamp)
        """
    )
    suspend fun setLastKnownSoc(soc: String, timestamp: String)

    @Query("SELECT * FROM settings")
    fun getAll(): Flow<List<SettingEntity>>
}
