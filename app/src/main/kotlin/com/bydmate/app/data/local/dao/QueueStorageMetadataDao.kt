package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.bydmate.app.data.local.entity.QueueStorageMetadataEntity

@Dao
interface QueueStorageMetadataDao {
    @Query("SELECT * FROM queue_storage_metadata WHERE id = 1")
    suspend fun get(): QueueStorageMetadataEntity?
}
