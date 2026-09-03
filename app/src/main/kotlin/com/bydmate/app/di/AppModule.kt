package com.bydmate.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bydmate.app.data.cloud.CloudTelemetryClient
import com.bydmate.app.data.cloud.CloudTelemetryClientApi
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.CloudSyncQueueDao
import com.bydmate.app.data.local.dao.HourlyRollupDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.dao.PlaceDao
import com.bydmate.app.data.local.dao.QueueStorageMetadataDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripRollupDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.QueueStorageSchema
import com.bydmate.app.domain.calculator.OdometerConsumptionBuffer
import com.bydmate.app.domain.calculator.RangeAvgSource
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.domain.calculator.SocInterpolator
import com.bydmate.app.domain.calculator.SocInterpolatorPrefs
import com.bydmate.app.domain.calculator.SocInterpolatorPrefsImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS idle_drains (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    start_ts INTEGER NOT NULL,
                    end_ts INTEGER,
                    soc_start INTEGER,
                    soc_end INTEGER,
                    kwh_consumed REAL
                )
            """)
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // TripEntity: battery temp + cost
            db.execSQL("ALTER TABLE trips ADD COLUMN bat_temp_avg REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN bat_temp_max REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN bat_temp_min REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN cost REAL")
            // ChargeEntity: battery temp + avg power
            db.execSQL("ALTER TABLE charges ADD COLUMN bat_temp_avg REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN bat_temp_max REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN bat_temp_min REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN avg_power_kw REAL")
            // ChargePointEntity: battery temp per sample
            db.execSQL("ALTER TABLE charge_points ADD COLUMN bat_temp INTEGER")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ChargeEntity: session status, cell voltages, 12V, ext temp, merge count
            db.execSQL("ALTER TABLE charges ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'")
            db.execSQL("ALTER TABLE charges ADD COLUMN cell_voltage_min REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN cell_voltage_max REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN voltage_12v REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN exterior_temp INTEGER")
            db.execSQL("ALTER TABLE charges ADD COLUMN merged_count INTEGER NOT NULL DEFAULT 0")
            // TripEntity: exterior temp
            db.execSQL("ALTER TABLE trips ADD COLUMN exterior_temp INTEGER")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Indices for faster queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_start_ts ON trips(start_ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_charges_start_ts ON charges(start_ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_charges_status ON charges(status)")
            // Battery degradation tracking table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS battery_snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    odometer_km REAL,
                    soc_start INTEGER NOT NULL,
                    soc_end INTEGER NOT NULL,
                    kwh_charged REAL NOT NULL,
                    calculated_capacity_kwh REAL,
                    soh_percent REAL,
                    cell_delta_v REAL,
                    bat_temp_avg REAL,
                    charge_id INTEGER
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_battery_snapshots_timestamp ON battery_snapshots(timestamp)")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE trips ADD COLUMN source TEXT NOT NULL DEFAULT 'live'")
            db.execSQL("ALTER TABLE trips ADD COLUMN byd_id INTEGER")
        }
    }

    // Remove FOREIGN KEY from trip_points — tripId=0 (GPS before trip match) caused
    // SQLITE_CONSTRAINT_FOREIGNKEY (code 787), losing all GPS points
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS trip_points_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    trip_id INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    speed_kmh REAL
                )
            """)
            db.execSQL("INSERT INTO trip_points_new SELECT * FROM trip_points")
            db.execSQL("DROP TABLE trip_points")
            db.execSQL("ALTER TABLE trip_points_new RENAME TO trip_points")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_points_trip_id ON trip_points(trip_id)")
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS automation_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    trigger_logic TEXT NOT NULL DEFAULT 'AND',
                    triggers TEXT NOT NULL,
                    actions TEXT NOT NULL,
                    cooldown_seconds INTEGER NOT NULL DEFAULT 60,
                    require_park INTEGER NOT NULL DEFAULT 0,
                    confirm_before_execute INTEGER NOT NULL DEFAULT 0,
                    last_triggered_at INTEGER,
                    trigger_count INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
            """)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS automation_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    rule_id INTEGER NOT NULL,
                    rule_name TEXT NOT NULL,
                    triggered_at INTEGER NOT NULL,
                    triggers_snapshot TEXT NOT NULL,
                    actions_result TEXT NOT NULL,
                    success INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_log_triggered_at ON automation_log(triggered_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_log_rule_id ON automation_log(rule_id)")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    radius_m INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """)
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE automation_rules ADD COLUMN fire_once_per_trip INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS odometer_samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    mileage_km REAL NOT NULL,
                    total_elec_kwh REAL,
                    soc_percent INTEGER,
                    session_id INTEGER,
                    timestamp INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_odometer_samples_mileage_km ON odometer_samples(mileage_km)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_odometer_samples_session_id ON odometer_samples(session_id)")
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v12: autoservice catch-up trace + baseline source.
            // 4 new fields on existing 'charges' table — see spec section 5.1.
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_start REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_finish REAL")
            db.execSQL("ALTER TABLE charges ADD COLUMN gun_state INTEGER")
            db.execSQL("ALTER TABLE charges ADD COLUMN detection_source TEXT")

            // One-shot cleanup of unfinished sessions left by the removed
            // ChargeTracker. COMPLETED sessions stay in history.
            db.execSQL("DELETE FROM charges WHERE status IN ('SUSPENDED', 'ACTIVE')")
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS cloud_sync_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    payloadJson TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT,
                    sentAt INTEGER
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_sync_queue_sentAt ON cloud_sync_queue(sentAt)")
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE trips ADD COLUMN fuel_liters REAL")
        }
    }

    /** Internal, not private, so Migration14to15Test validates the migration that actually ships. */
    internal val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS cloud_hourly_rollup (
                    vehicleId TEXT NOT NULL,
                    hourStart INTEGER NOT NULL,
                    sampleCount INTEGER NOT NULL,
                    socMin REAL,
                    socMax REAL,
                    socLast REAL,
                    speedMax REAL,
                    powerSum REAL NOT NULL,
                    powerSampleCount INTEGER NOT NULL,
                    batteryTempSum REAL NOT NULL,
                    batteryTempSampleCount INTEGER NOT NULL,
                    cabinTempSum REAL NOT NULL,
                    cabinTempSampleCount INTEGER NOT NULL,
                    outsideTempSum REAL NOT NULL,
                    outsideTempSampleCount INTEGER NOT NULL,
                    regenKwhSum REAL NOT NULL,
                    tractionKwhSum REAL NOT NULL,
                    dirty INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(vehicleId, hourStart)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_hourly_rollup_dirty ON cloud_hourly_rollup(dirty)")
        }
    }

    /** Internal, not private, so Migration15to16Test validates the migration that actually ships. */
    internal val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS cloud_trip_rollup (
                    tripId TEXT NOT NULL,
                    vehicleId TEXT NOT NULL,
                    startedAt INTEGER NOT NULL,
                    lastDeviceTime INTEGER NOT NULL,
                    endedAt INTEGER,
                    sampleCount INTEGER NOT NULL,
                    distanceBaselineKm REAL,
                    consumptionBaselineKwh REAL,
                    lastOdometerKm REAL,
                    lastTotalElecConsumptionKwh REAL,
                    socStart REAL,
                    socEnd REAL,
                    maxSpeedKmh REAL,
                    speedSum REAL NOT NULL,
                    speedSampleCount INTEGER NOT NULL,
                    regenKwhSum REAL NOT NULL,
                    tractionKwhSum REAL NOT NULL,
                    dirty INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(tripId)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_trip_rollup_dirty ON cloud_trip_rollup(dirty)")
        }
    }

    /** Minimal Stage-2 key: makes daemon spool replay idempotent across commit/delete crashes. */
    internal val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cloud_sync_queue ADD COLUMN sampleId TEXT")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_cloud_sync_queue_sampleId " +
                    "ON cloud_sync_queue(sampleId)"
            )
        }
    }

    /** Stage 3 adds measurement only; the effective queue limit remains exactly 1,000 rows. */
    internal val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cloud_sync_queue ADD COLUMN payloadBytes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cloud_sync_queue ADD COLUMN capturedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cloud_sync_queue ADD COLUMN origin TEXT NOT NULL DEFAULT 'app'")
            db.execSQL("ALTER TABLE cloud_sync_queue ADD COLUMN compactionTier INTEGER NOT NULL DEFAULT 0")
            // No payload parsing on Room's database-open path. Legacy creation time is the safe
            // capture-time fallback; exact UTF-8 byte counts are backfilled later in bounded IO.
            db.execSQL("UPDATE cloud_sync_queue SET capturedAt=createdAt")
            db.execSQL("UPDATE cloud_sync_queue SET origin='daemon' WHERE sampleId IS NOT NULL")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cloud_sync_queue_sentAt_capturedAt " +
                    "ON cloud_sync_queue(sentAt,capturedAt)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cloud_sync_queue_compactionTier_capturedAt " +
                    "ON cloud_sync_queue(compactionTier,capturedAt)"
            )
            QueueStorageSchema.installAccounting(db)
        }
    }

    internal val QUEUE_STORAGE_CALLBACK = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            QueueStorageSchema.installAccounting(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            QueueStorageSchema.installAccounting(db)
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "bydmate.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
            .addCallback(QUEUE_STORAGE_CALLBACK)
            .build()
    }

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun provideTripPointDao(db: AppDatabase): TripPointDao = db.tripPointDao()
    @Provides fun provideChargeDao(db: AppDatabase): ChargeDao = db.chargeDao()
    @Provides fun provideChargePointDao(db: AppDatabase): ChargePointDao = db.chargePointDao()
    @Provides fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()
    @Provides fun provideIdleDrainDao(db: AppDatabase): IdleDrainDao = db.idleDrainDao()
    @Provides fun provideBatterySnapshotDao(db: AppDatabase): BatterySnapshotDao = db.batterySnapshotDao()
    @Provides fun provideRuleDao(db: AppDatabase): RuleDao = db.ruleDao()
    @Provides fun provideRuleLogDao(db: AppDatabase): RuleLogDao = db.ruleLogDao()
    @Provides fun providePlaceDao(db: AppDatabase): PlaceDao = db.placeDao()
    @Provides fun provideOdometerSampleDao(db: AppDatabase): OdometerSampleDao = db.odometerSampleDao()
    @Provides fun provideCloudSyncQueueDao(db: AppDatabase): CloudSyncQueueDao = db.cloudSyncQueueDao()
    @Provides fun provideHourlyRollupDao(db: AppDatabase): HourlyRollupDao = db.hourlyRollupDao()
    @Provides fun provideTripRollupDao(db: AppDatabase): TripRollupDao = db.tripRollupDao()
    @Provides fun provideQueueStorageMetadataDao(db: AppDatabase): QueueStorageMetadataDao = db.queueStorageMetadataDao()
    @Provides fun provideCloudTelemetryClientApi(client: CloudTelemetryClient): CloudTelemetryClientApi = client

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideOdometerConsumptionBuffer(
        dao: OdometerSampleDao,
        tripRepository: com.bydmate.app.data.repository.TripRepository,
    ): OdometerConsumptionBuffer = OdometerConsumptionBuffer(
        dao = dao,
        fallbackEmaProvider = { tripRepository.getEmaConsumption() },
    )

    @Provides
    @Singleton
    fun provideSocInterpolatorPrefs(@ApplicationContext ctx: Context): SocInterpolatorPrefs =
        SocInterpolatorPrefsImpl(ctx)

    @Provides
    @Singleton
    fun provideSocInterpolator(
        prefs: SocInterpolatorPrefs,
    ): SocInterpolator = SocInterpolator(persistence = prefs)

    @Provides
    @Singleton
    fun provideRangeCalculator(
        rangeAvgSource: RangeAvgSource,
        settingsRepository: com.bydmate.app.data.repository.SettingsRepository,
        socInterpolator: SocInterpolator,
    ): RangeCalculator = RangeCalculator(
        buffer = rangeAvgSource,
        capacityProvider = { settingsRepository.getBatteryCapacity() },
        socInterpolator = socInterpolator,
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideAdbOnDeviceClient(
        @ApplicationContext context: Context,
        keyStore: com.bydmate.app.data.autoservice.AdbKeyStore
    ): com.bydmate.app.data.autoservice.AdbOnDeviceClient =
        com.bydmate.app.data.autoservice.AdbOnDeviceClientImpl(context, keyStore)

    @Provides
    @Singleton
    fun provideAutoserviceClient(
        adb: com.bydmate.app.data.autoservice.AdbOnDeviceClient
    ): com.bydmate.app.data.autoservice.AutoserviceClient =
        com.bydmate.app.data.autoservice.AutoserviceClientImpl(adb)
}
