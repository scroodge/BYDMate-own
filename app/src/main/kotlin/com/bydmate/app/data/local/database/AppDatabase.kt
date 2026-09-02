package com.bydmate.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.dao.HourlyRollupDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.dao.PlaceDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripRollupDao
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.local.entity.CloudSyncQueueEntity
import com.bydmate.app.data.local.entity.HourlyRollupEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.OdometerSampleEntity
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.local.entity.TripRollupEntity

@Database(
    entities = [
        TripEntity::class,
        TripPointEntity::class,
        ChargeEntity::class,
        ChargePointEntity::class,
        SettingEntity::class,
        IdleDrainEntity::class,
        BatterySnapshotEntity::class,
        RuleEntity::class,
        RuleLogEntity::class,
        PlaceEntity::class,
        OdometerSampleEntity::class,
        CloudSyncQueueEntity::class,
        HourlyRollupEntity::class,
        TripRollupEntity::class
    ],
    version = 17,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun tripPointDao(): TripPointDao
    abstract fun chargeDao(): ChargeDao
    abstract fun chargePointDao(): ChargePointDao
    abstract fun settingsDao(): SettingsDao
    abstract fun idleDrainDao(): IdleDrainDao
    abstract fun batterySnapshotDao(): BatterySnapshotDao
    abstract fun ruleDao(): RuleDao
    abstract fun ruleLogDao(): RuleLogDao
    abstract fun placeDao(): PlaceDao
    abstract fun odometerSampleDao(): OdometerSampleDao
    abstract fun cloudSyncQueueDao(): CloudSyncQueueDao
    abstract fun hourlyRollupDao(): HourlyRollupDao
    abstract fun tripRollupDao(): TripRollupDao
}
