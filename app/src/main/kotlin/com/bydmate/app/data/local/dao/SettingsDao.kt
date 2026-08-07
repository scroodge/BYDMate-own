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
     * Keep the offline-charge baseline coherent if the process dies between writes.
     * Android 10's SQLite supports this single-statement UPSERT form.
     */
    @Query(
        """
        INSERT INTO settings (`key`, value)
        VALUES ('last_known_soc', :soc), ('last_soc_timestamp', :timestamp)
        ON CONFLICT(`key`) DO UPDATE SET value = excluded.value
        """
    )
    suspend fun setLastKnownSoc(soc: String, timestamp: String)

    @Query("SELECT * FROM settings")
    fun getAll(): Flow<List<SettingEntity>>
}
