package com.bydmate.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.cloud.CloudTelemetrySender
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.remote.VehicleTelemetrySnapshot
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.service.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import com.bydmate.app.service.BootReceiver
import com.bydmate.app.service.TrackingService
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Represents the runtime status of the autoservice data channel.
 *
 * - [NotEnabled] — the toggle is OFF; render no status block.
 * - [Disconnected] — toggle ON, but [BatteryStateRepository.refresh] returned
 *   `autoserviceAvailable = false` (ADB not connected or service unreachable).
 * - [Connected] — toggle ON and at least one real value was returned.
 *   Individual fields are nullable because partial sentinel responses occur.
 * - [AllSentinel] — see KDoc on the object itself.
 */
sealed class AutoserviceStatus {
    object NotEnabled : AutoserviceStatus()
    object Disconnected : AutoserviceStatus()
    data class Connected(
        val socNow: Float?,
        val lifetimeKm: Float?,
        val lifetimeKwh: Float?,
        val sohPercent: Float?
    ) : AutoserviceStatus()

    /**
     * Toggle ON, autoservice connected, but the battery-trio (SoC, lifetime km,
     * lifetime kWh) all came back as sentinel/null. Typically happens when the
     * car is in deep standby — DiLink BMS bus quiet, only 12V + cached SoH
     * still readable. UI should show "сейчас машина не отвечает" rather than
     * empty cards.
     */
    object AllSentinel : AutoserviceStatus()
}

/**
 * UI state for the Settings screen.
 * Contains current setting values and export operation status.
 */
data class SettingsUiState(
    val batteryCapacity: String = SettingsRepository.DEFAULT_BATTERY_CAPACITY,
    val homeTariff: String = SettingsRepository.DEFAULT_HOME_TARIFF,
    val dcTariff: String = SettingsRepository.DEFAULT_DC_TARIFF,
    val units: String = SettingsRepository.DEFAULT_UNITS,
    val currency: String = SettingsRepository.DEFAULT_CURRENCY,
    val currencySymbol: String = "BYN",
    val exportStatus: String? = null,
    val importStatus: String? = null,
    val appVersion: String = "0.0.0",
    val updateStatus: String? = null,
    val updateDialogState: UpdateState = UpdateState.Idle,
    val showUpdateDialog: Boolean = false,
    val diagnosticLog: String? = null,
    val logSaveStatus: String? = null,
    val isRecordingLogs: Boolean = false,
    val tripCostTariff: String = "home",
    val consumptionGood: String = SettingsRepository.DEFAULT_CONSUMPTION_GOOD,
    val consumptionBad: String = SettingsRepository.DEFAULT_CONSUMPTION_BAD,
    val lastBootInfo: String? = null,
    val chainLog: String? = null,
    val openRouterApiKey: String = "",
    val openRouterModel: String = "",
    val openRouterModelName: String = "",
    val showModelPicker: Boolean = false,
    val availableModels: List<OpenRouterModel> = emptyList(),
    val modelsLoading: Boolean = false,
    val aiSaveStatus: String? = null,
    val tariffSaveStatus: String? = null,
    val recalcStatus: String? = null,
    val showRecalcConfirm: Boolean = false,
    // Hidden Smart Home settings (unlocked by tapping version 7 times)
    val devModeUnlocked: Boolean = false,
    val aliceEndpoint: String = "",
    val aliceApiKey: String = "",
    val aliceEnabled: Boolean = false,
    val aliceSaveStatus: String? = null,
    val autoCheckUpdates: Boolean = true,
    val dataSource: String = "ENERGYDATA",
    val dataSourceStatus: String? = null,
    val autoserviceEnabled: Boolean = false,
    val autoserviceStatus: AutoserviceStatus = AutoserviceStatus.NotEnabled,
    val cloudSyncEnabled: Boolean = false,
    val cloudSyncUrl: String = SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
    val cloudSyncApiKey: String = "",
    val cloudSyncVehicleId: String = "",
    val cloudSyncIntervalSec: String = SettingsRepository.DEFAULT_CLOUD_SYNC_INTERVAL_SEC,
    val cloudSyncWifiOnly: Boolean = false,
    val cloudSyncOmitGps: Boolean = false,
    val cloudSyncStatus: String? = null,
    val appLanguage: String = SettingsRepository.DEFAULT_APP_LANGUAGE,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val chargeRepository: ChargeRepository,
    private val updateChecker: UpdateChecker,
    private val historyImporter: HistoryImporter,
    private val energyDataReader: EnergyDataReader,
    private val diParsClient: DiParsClient,
    private val idleDrainDao: IdleDrainDao,
    private val insightsManager: InsightsManager,
    private val adbOnDeviceClient: AdbOnDeviceClient,
    private val batteryStateRepository: BatteryStateRepository,
    private val cloudTelemetrySender: CloudTelemetrySender? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(
        appVersion = getVersion(),
        autoCheckUpdates = UpdateChecker.isAutoCheckEnabled(appContext)
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun getVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    init {
        loadSettings()
    }

    /** Load all settings from the repository on init. */
    private fun loadSettings() {
        viewModelScope.launch {
            val capacity = settingsRepository.getString(
                SettingsRepository.KEY_BATTERY_CAPACITY,
                SettingsRepository.DEFAULT_BATTERY_CAPACITY
            )
            val homeTariff = settingsRepository.getString(
                SettingsRepository.KEY_HOME_TARIFF,
                SettingsRepository.DEFAULT_HOME_TARIFF
            )
            val dcTariff = settingsRepository.getString(
                SettingsRepository.KEY_DC_TARIFF,
                SettingsRepository.DEFAULT_DC_TARIFF
            )
            val units = settingsRepository.getString(
                SettingsRepository.KEY_UNITS,
                SettingsRepository.DEFAULT_UNITS
            )
            val currency = settingsRepository.getCurrency()
            val tripCostTariff = settingsRepository.getTripCostTariffKey()
            val consumptionGood = settingsRepository.getString(
                SettingsRepository.KEY_CONSUMPTION_GOOD,
                SettingsRepository.DEFAULT_CONSUMPTION_GOOD
            )
            val consumptionBad = settingsRepository.getString(
                SettingsRepository.KEY_CONSUMPTION_BAD,
                SettingsRepository.DEFAULT_CONSUMPTION_BAD
            )

            // Read boot log from SharedPreferences
            val bootInfo = readBootInfo()
            val chainLog = readChainLog()

            // AI settings
            val apiKey = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_API_KEY, "")
            val modelId = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_MODEL, "")

            // Smart Home settings
            val aliceEndpoint = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENDPOINT, "")
            val aliceApiKey = settingsRepository.getString(SettingsRepository.KEY_ALICE_API_KEY, "")
            val aliceEnabled = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENABLED, "false") == "true"

            val dataSource = settingsRepository.getDataSource().name

            val autoserviceEnabled = settingsRepository.isAutoserviceEnabled()

            val cloudSyncEnabled = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false") == "true"
            val cloudSyncUrl = settingsRepository.getString(
                SettingsRepository.KEY_CLOUD_SYNC_URL,
                SettingsRepository.DEFAULT_CLOUD_SYNC_URL
            ).ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
            val cloudSyncApiKey = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, "")
            val cloudSyncVehicleId = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "")
            val cloudSyncIntervalSec = settingsRepository.getString(
                SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC,
                SettingsRepository.DEFAULT_CLOUD_SYNC_INTERVAL_SEC
            )
            val cloudSyncWifiOnly = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, "false") == "true"
            val cloudSyncOmitGps = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, "false") == "true"
            val cloudSyncStatus = formatCloudSyncStatus()
            val appLanguage = settingsRepository.getString(
                SettingsRepository.KEY_APP_LANGUAGE,
                SettingsRepository.DEFAULT_APP_LANGUAGE,
            ).takeIf {
                it == SettingsRepository.LANGUAGE_BE ||
                    it == SettingsRepository.LANGUAGE_RU ||
                    it == SettingsRepository.LANGUAGE_EN
            }
                ?: SettingsRepository.DEFAULT_APP_LANGUAGE

            _uiState.update {
                it.copy(
                    batteryCapacity = capacity,
                    homeTariff = homeTariff,
                    dcTariff = dcTariff,
                    units = units,
                    currency = currency.code,
                    currencySymbol = currency.symbol,
                    tripCostTariff = tripCostTariff,
                    consumptionGood = consumptionGood,
                    consumptionBad = consumptionBad,
                    lastBootInfo = bootInfo,
                    chainLog = chainLog,
                    openRouterApiKey = apiKey,
                    openRouterModel = modelId,
                    openRouterModelName = modelId.substringAfterLast("/").substringBefore(":"),
                    aliceEndpoint = aliceEndpoint,
                    aliceApiKey = aliceApiKey,
                    aliceEnabled = aliceEnabled,
                    dataSource = dataSource,
                    autoserviceEnabled = autoserviceEnabled,
                    cloudSyncEnabled = cloudSyncEnabled,
                    cloudSyncUrl = cloudSyncUrl,
                    cloudSyncApiKey = cloudSyncApiKey,
                    cloudSyncVehicleId = cloudSyncVehicleId,
                    cloudSyncIntervalSec = cloudSyncIntervalSec,
                    cloudSyncWifiOnly = cloudSyncWifiOnly,
                    cloudSyncOmitGps = cloudSyncOmitGps,
                    cloudSyncStatus = cloudSyncStatus,
                    appLanguage = appLanguage,
                )
            }

            loadAutoserviceState()
        }
    }

    internal suspend fun loadAutoserviceState() {
        val status = if (!settingsRepository.isAutoserviceEnabled()) {
            AutoserviceStatus.NotEnabled
        } else {
            val state = batteryStateRepository.refresh()
            when {
                !state.autoserviceAvailable -> AutoserviceStatus.Disconnected
                state.socNow == null && state.lifetimeKm == null && state.lifetimeKwh == null ->
                    AutoserviceStatus.AllSentinel
                else -> AutoserviceStatus.Connected(
                    socNow = state.socNow,
                    lifetimeKm = state.lifetimeKm,
                    lifetimeKwh = state.lifetimeKwh,
                    sohPercent = state.sohPercent
                )
            }
        }
        _uiState.update { it.copy(autoserviceStatus = status) }
    }

    /**
     * UI entry point for the autoservice toggle. Persists the new value, then
     * triggers ADB handshake on enable. Single coroutine — no UI race between
     * persist-reload and connect-reload.
     */
    fun enableAutoservice(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoserviceEnabled(enabled)
            _uiState.update { it.copy(autoserviceEnabled = enabled) }
            if (enabled) {
                adbOnDeviceClient.connect()
            }
            loadAutoserviceState()
        }
    }

    fun setDataSource(value: String) {
        if (value == _uiState.value.dataSource) return
        val target = runCatching { SettingsRepository.DataSource.valueOf(value) }.getOrNull() ?: return
        _uiState.update { it.copy(dataSource = value, dataSourceStatus = "Переключение...") }
        viewModelScope.launch {
            settingsRepository.setDataSource(target)
            val r = historyImporter.runSync()
            _uiState.update { it.copy(dataSourceStatus = r.details ?: r.error ?: "Готово") }
        }
    }

    /** Save battery capacity setting. */
    fun saveBatteryCapacity(value: String) {
        _uiState.update { it.copy(batteryCapacity = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_BATTERY_CAPACITY, value)
        }
    }

    /** Update tariff in UI only (no DB save until explicit "Save" press). */
    fun updateHomeTariff(value: String) {
        _uiState.update { it.copy(homeTariff = value) }
    }

    fun updateDcTariff(value: String) {
        _uiState.update { it.copy(dcTariff = value) }
    }

    /** Save tariffs to DB and calculate costs for new trips. */
    fun saveTariffs() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_HOME_TARIFF, state.homeTariff)
            settingsRepository.setString(SettingsRepository.KEY_DC_TARIFF, state.dcTariff)
            val tariff = settingsRepository.getTripCostTariff()
            historyImporter.calculateMissingCosts(tariff)
            _uiState.update { it.copy(tariffSaveStatus = "Сохранено") }
            delay(2000)
            _uiState.update { it.copy(tariffSaveStatus = null) }
        }
    }

    /** Recalculate cost for ALL trips using current tariff. */
    fun recalculateAllCosts() {
        viewModelScope.launch {
            val tariff = settingsRepository.getTripCostTariff()
            val allTrips = tripRepository.getAllTrips().firstOrNull() ?: emptyList()
            var count = 0
            for (trip in allTrips) {
                val kwh = trip.kwhConsumed ?: continue
                tripRepository.updateTrip(trip.copy(cost = kwh * tariff))
                count++
            }
            _uiState.update { it.copy(recalcStatus = "Пересчитано: $count поездок") }
            delay(3000)
            _uiState.update { it.copy(recalcStatus = null) }
        }
    }

    /** Save distance units preference (km or miles). */
    fun saveUnits(value: String) {
        _uiState.update { it.copy(units = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_UNITS, value)
        }
    }

    /** Save trip cost tariff preference. */
    fun saveTripCostTariff(value: String) {
        _uiState.update { it.copy(tripCostTariff = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_TRIP_COST_TARIFF, value)
        }
    }

    fun showRecalcConfirm() { _uiState.update { it.copy(showRecalcConfirm = true) } }
    fun hideRecalcConfirm() { _uiState.update { it.copy(showRecalcConfirm = false) } }
    fun confirmRecalc() {
        _uiState.update { it.copy(showRecalcConfirm = false) }
        recalculateAllCosts()
    }

    /** Save currency preference. */
    fun saveCurrency(code: String) {
        val currency = SettingsRepository.CURRENCIES.find { it.code == code }
            ?: SettingsRepository.CURRENCIES.first()
        _uiState.update { it.copy(currency = currency.code, currencySymbol = currency.symbol) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CURRENCY, code)
        }
    }

    /**
     * Export all trips and charges to CSV files in the Downloads directory.
     * Creates two files: bydmate_trips_<timestamp>.csv and bydmate_charges_<timestamp>.csv.
     */
    fun exportCsv() {
        viewModelScope.launch {
            _uiState.update { it.copy(exportStatus = "Экспорт...") }

            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

                // Export trips
                val trips = tripRepository.getAllTrips().firstOrNull() ?: emptyList()
                val tripsFile = File(downloadsDir, "bydmate_trips_$timestamp.csv")
                FileWriter(tripsFile).use { writer ->
                    writer.append("id,start_ts,end_ts,distance_km,kwh_consumed,kwh_per_100km,soc_start,soc_end,temp_avg_c,avg_speed_kmh,bat_temp_avg,bat_temp_max,bat_temp_min,cost,exterior_temp\n")
                    for (trip in trips) {
                        writer.append("${trip.id},${trip.startTs},${trip.endTs ?: ""},")
                        writer.append("${trip.distanceKm ?: ""},${trip.kwhConsumed ?: ""},")
                        writer.append("${trip.kwhPer100km ?: ""},${trip.socStart ?: ""},")
                        writer.append("${trip.socEnd ?: ""},${trip.tempAvgC ?: ""},")
                        writer.append("${trip.avgSpeedKmh ?: ""},${trip.batTempAvg ?: ""},")
                        writer.append("${trip.batTempMax ?: ""},${trip.batTempMin ?: ""},")
                        writer.append("${trip.cost ?: ""},${trip.exteriorTemp ?: ""}\n")
                    }
                }

                // Export charges
                val charges = chargeRepository.getAllCharges().firstOrNull() ?: emptyList()
                val chargesFile = File(downloadsDir, "bydmate_charges_$timestamp.csv")
                FileWriter(chargesFile).use { writer ->
                    writer.append("id,start_ts,end_ts,soc_start,soc_end,kwh_charged,kwh_charged_soc,max_power_kw,type,cost,lat,lon,bat_temp_avg,bat_temp_max,bat_temp_min,avg_power_kw,status,cell_voltage_min,cell_voltage_max,voltage_12v,exterior_temp,merged_count\n")
                    for (charge in charges) {
                        writer.append("${charge.id},${charge.startTs},${charge.endTs ?: ""},")
                        writer.append("${charge.socStart ?: ""},${charge.socEnd ?: ""},")
                        writer.append("${charge.kwhCharged ?: ""},${charge.kwhChargedSoc ?: ""},")
                        writer.append("${charge.maxPowerKw ?: ""},${charge.type ?: ""},")
                        writer.append("${charge.cost ?: ""},${charge.lat ?: ""},")
                        writer.append("${charge.lon ?: ""},${charge.batTempAvg ?: ""},")
                        writer.append("${charge.batTempMax ?: ""},${charge.batTempMin ?: ""},")
                        writer.append("${charge.avgPowerKw ?: ""},${charge.status},")
                        writer.append("${charge.cellVoltageMin ?: ""},${charge.cellVoltageMax ?: ""},")
                        writer.append("${charge.voltage12v ?: ""},${charge.exteriorTemp ?: ""},")
                        writer.append("${charge.mergedCount}\n")
                    }
                }

                val tripCount = trips.size
                val chargeCount = charges.size
                _uiState.update {
                    it.copy(
                        exportStatus = "Экспортировано: $tripCount поездок, $chargeCount зарядок\n→ ${downloadsDir.absolutePath}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(exportStatus = "Ошибка: ${e.message}")
                }
            }
        }
    }

    /** Clear the export status message. */
    fun clearExportStatus() {
        _uiState.update { it.copy(exportStatus = null) }
    }

    /** Import trip history from BYD energydata database. */
    fun importBydHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(importStatus = "Импорт...") }
            val result = historyImporter.forceImport()
            if (result.isError) {
                _uiState.update {
                    it.copy(importStatus = "Ошибка: ${result.error}")
                }
            } else {
                val status = result.details
                    ?: "Импортировано ${result.count} поездок из BYD"
                _uiState.update { it.copy(importStatus = status) }
            }
        }
    }

    /** Run full diagnostics: BYD storage, our DB, DiPlus API, permissions. */
    fun runDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(diagnosticLog = "Диагностика...") }

            val sb = StringBuilder()
            val sdf = SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US)

            // 1. Permissions
            sb.appendLine("=== Разрешения ===")
            val perms = listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            for (perm in perms) {
                val granted = ContextCompat.checkSelfPermission(appContext, perm) ==
                    PackageManager.PERMISSION_GRANTED
                val name = perm.substringAfterLast(".")
                sb.appendLine("$name: ${if (granted) "✓" else "✗"}")
            }

            // 2. BYD energydata
            try {
                val bydReport = energyDataReader.diagnose()
                sb.appendLine()
                sb.append(bydReport)
            } catch (e: Exception) {
                sb.appendLine("\nОШИБКА BYD: ${e.message}")
            }

            // 3. Our database
            sb.appendLine("\n=== Наша база данных ===")
            try {
                val trips = tripRepository.getAllTrips().first()
                val charges = chargeRepository.getAllCharges().first()
                val drainCount = idleDrainDao.getCount()
                val drainKwh = idleDrainDao.getTotalKwh()
                sb.appendLine("Поездок: ${trips.size}")
                sb.appendLine("Зарядок: ${charges.size}")
                sb.appendLine("Стоянок (idle drain): $drainCount (%.2f кВт·ч)".format(drainKwh))

                if (trips.isNotEmpty()) {
                    sb.appendLine("\nПоследние 5 поездок:")
                    trips.take(5).forEach { t ->
                        val startFmt = sdf.format(Date(t.startTs))
                        val endFmt = t.endTs?.let { sdf.format(Date(it)) } ?: "null"
                        sb.appendLine("#${t.id}: $startFmt – $endFmt")
                        sb.appendLine("  km=${t.distanceKm ?: "-"}, kwh=${t.kwhConsumed ?: "-"}, " +
                            "soc=${t.socStart ?: "-"}→${t.socEnd ?: "-"}, " +
                            "speed=${t.avgSpeedKmh?.let { "%.0f".format(it) } ?: "-"}")
                        sb.appendLine("  raw: start=${t.startTs}, end=${t.endTs ?: "null"}")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("ОШИБКА: ${e.message}")
            }

            // 4. DiPlus API
            sb.appendLine("\n=== DiPlus API ===")
            try {
                val testTemplate = "SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}"
                val httpUrl = okhttp3.HttpUrl.Builder()
                    .scheme("http").host("127.0.0.1").port(8988)
                    .addPathSegments("api/getDiPars")
                    .addQueryParameter("text", testTemplate)
                    .build()
                val testClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val resp = testClient.newCall(
                    okhttp3.Request.Builder().url(httpUrl).build()
                ).execute()
                val body = resp.body?.string() ?: "(пустое тело)"
                sb.appendLine("HTTP ${resp.code}: $body")
            } catch (e: java.net.ConnectException) {
                sb.appendLine("Соединение отклонено — DiPlus не запущен")
            } catch (e: java.net.SocketTimeoutException) {
                sb.appendLine("Таймаут соединения")
            } catch (e: Exception) {
                sb.appendLine("${e.javaClass.simpleName}: ${e.message}")
            }

            _uiState.update { it.copy(diagnosticLog = sb.toString()) }
        }
    }

    private fun readBootInfo(): String? {
        return try {
            val prefs = appContext.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val ts = prefs.getLong(BootReceiver.KEY_LAST_BOOT_TS, 0L)
            if (ts == 0L) return null
            val method = prefs.getString(BootReceiver.KEY_LAST_BOOT_METHOD, "?") ?: "?"
            val sdf = SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US)
            "${sdf.format(Date(ts))} ($method)"
        } catch (_: Exception) { null }
    }

    private fun readChainLog(): String? {
        return try {
            val prefs = appContext.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val log = prefs.getString(BootReceiver.KEY_CHAIN_LOG, null)
            if (log.isNullOrBlank()) null else log
        } catch (_: Exception) { null }
    }

    fun saveOpenRouterApiKey(value: String) {
        _uiState.update { it.copy(openRouterApiKey = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_OPENROUTER_API_KEY, value)
        }
    }

    fun selectModel(model: OpenRouterModel) {
        _uiState.update { it.copy(
            openRouterModel = model.id,
            openRouterModelName = model.name,
            showModelPicker = false
        ) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_OPENROUTER_MODEL, model.id)
        }
    }

    fun showModelPicker() {
        val apiKey = _uiState.value.openRouterApiKey
        if (apiKey.isBlank()) return
        _uiState.update { it.copy(showModelPicker = true, modelsLoading = true) }
        viewModelScope.launch {
            val models = insightsManager.getModels(apiKey)
            _uiState.update { it.copy(availableModels = models, modelsLoading = false) }
        }
    }

    fun hideModelPicker() {
        _uiState.update { it.copy(showModelPicker = false) }
    }

    fun saveAiSettings() {
        val apiKey = _uiState.value.openRouterApiKey
        val model = _uiState.value.openRouterModel
        if (apiKey.isBlank() || model.isBlank()) {
            _uiState.update { it.copy(aiSaveStatus = "Укажите API-ключ и модель") }
            return
        }
        _uiState.update { it.copy(aiSaveStatus = "Загрузка инсайта...") }
        viewModelScope.launch {
            val insight = insightsManager.refresh()
            if (insight != null) {
                _uiState.update { it.copy(aiSaveStatus = "Готово! Переключитесь на Главную") }
            } else {
                _uiState.update { it.copy(aiSaveStatus = "Ошибка получения инсайта") }
            }
        }
    }

    // --- Smart Home (hidden) ---

    private var versionTapCount = 0
    private var lastVersionTapTime = 0L

    fun onVersionTap() {
        val now = System.currentTimeMillis()
        if (now - lastVersionTapTime > 2000) versionTapCount = 0
        lastVersionTapTime = now
        versionTapCount++
        if (versionTapCount >= 7) {
            _uiState.update { it.copy(devModeUnlocked = true) }
            versionTapCount = 0
        }
    }

    fun updateAliceEndpoint(value: String) {
        _uiState.update { it.copy(aliceEndpoint = value) }
    }

    fun updateAliceApiKey(value: String) {
        _uiState.update { it.copy(aliceApiKey = value) }
    }

    fun saveAliceSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_ALICE_ENDPOINT, state.aliceEndpoint)
            settingsRepository.setString(SettingsRepository.KEY_ALICE_API_KEY, state.aliceApiKey)
            val enabled = state.aliceEndpoint.isNotBlank() && state.aliceApiKey.isNotBlank()
            settingsRepository.setString(SettingsRepository.KEY_ALICE_ENABLED, enabled.toString())
            _uiState.update { it.copy(aliceEnabled = enabled, aliceSaveStatus = "Сохранено") }
            delay(2000)
            _uiState.update { it.copy(aliceSaveStatus = null) }
        }
    }

    fun toggleAlice(enabled: Boolean) {
        _uiState.update { it.copy(aliceEnabled = enabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_ALICE_ENABLED, enabled.toString())
        }
    }

    fun toggleCloudSync(enabled: Boolean) {
        _uiState.update { it.copy(cloudSyncEnabled = enabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, enabled.toString())
        }
    }

    fun updateCloudSyncUrl(value: String) {
        _uiState.update { it.copy(cloudSyncUrl = value) }
    }

    fun updateCloudSyncApiKey(value: String) {
        _uiState.update { it.copy(cloudSyncApiKey = value) }
    }

    fun updateCloudSyncVehicleId(value: String) {
        _uiState.update { it.copy(cloudSyncVehicleId = value) }
    }

    fun updateCloudSyncIntervalSec(value: String) {
        _uiState.update { it.copy(cloudSyncIntervalSec = value.filter { ch -> ch.isDigit() }.take(4)) }
    }

    fun updateAppLanguage(value: String) {
        val language = when (value) {
            SettingsRepository.LANGUAGE_RU -> SettingsRepository.LANGUAGE_RU
            SettingsRepository.LANGUAGE_EN -> SettingsRepository.LANGUAGE_EN
            else -> SettingsRepository.LANGUAGE_BE
        }
        _uiState.update { it.copy(appLanguage = language) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_APP_LANGUAGE, language)
        }
    }

    fun toggleCloudSyncWifiOnly(enabled: Boolean) {
        _uiState.update { it.copy(cloudSyncWifiOnly = enabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, enabled.toString())
        }
    }

    fun toggleCloudSyncOmitGps(enabled: Boolean) {
        _uiState.update { it.copy(cloudSyncOmitGps = enabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, enabled.toString())
        }
    }

    fun saveCloudSyncSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            val interval = state.cloudSyncIntervalSec.toIntOrNull()?.coerceIn(5, 300) ?: 60
            val url = state.cloudSyncUrl.trim().ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
            val enabled = state.cloudSyncEnabled && url.startsWith("https://", ignoreCase = true)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_URL, url)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, state.cloudSyncApiKey)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, state.cloudSyncVehicleId.trim())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC, interval.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, state.cloudSyncWifiOnly.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, state.cloudSyncOmitGps.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, enabled.toString())
            val status = if (url.isBlank()) {
                cloudText("Endpoint URL пустой", "Endpoint URL пусты", "Endpoint URL is empty")
            } else if (!url.startsWith("https://", ignoreCase = true)) {
                cloudText("Endpoint должен начинаться с https://", "Endpoint мусіць пачынацца з https://", "Endpoint must start with https://")
            } else {
                cloudText("Сохранено", "Захавана", "Saved")
            }
            _uiState.update {
                it.copy(
                    cloudSyncEnabled = enabled,
                    cloudSyncUrl = url,
                    cloudSyncIntervalSec = interval.toString(),
                    cloudSyncStatus = status,
                )
            }
        }
    }

    fun sendCloudTestPayload() {
        val state = _uiState.value
        viewModelScope.launch {
            val interval = state.cloudSyncIntervalSec.toIntOrNull()?.coerceIn(5, 300) ?: 60
            val url = state.cloudSyncUrl.trim().ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_URL, url)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, state.cloudSyncApiKey)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, state.cloudSyncVehicleId.trim())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC, interval.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, state.cloudSyncWifiOnly.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, state.cloudSyncOmitGps.toString())
            val canEnableLiveSync = url.startsWith("https://", ignoreCase = true) &&
                state.cloudSyncVehicleId.trim().isNotBlank()
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, canEnableLiveSync.toString())
            val snapshot = VehicleTelemetrySnapshot.from(
                data = TrackingService.lastData.value,
                battery = null,
                charging = null,
                enginePowerKw = null,
                capturedAtMs = System.currentTimeMillis(),
                rangeEstKm = TrackingService.lastRangeKm.value,
                currentTripDistanceKm = TrackingService.tripDistanceKm.value,
                currentTripConsumptionKwh100km = null,
                location = if (hasFineLocationPermission()) TrackingService.lastLocation.value else null,
            )
            _uiState.update { it.copy(cloudSyncStatus = cloudText("Отправка теста...", "Адпраўка тэсту...", "Sending test...")) }
            val result = cloudTelemetrySender?.sendTest(snapshot)
                ?: Result.failure(IllegalStateException(cloudText("Cloud sender недоступен", "Cloud sender недаступны", "Cloud sender is unavailable")))
            if (result.isFailure && !state.cloudSyncEnabled) {
                settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false")
            }
            _uiState.update {
                it.copy(
                    cloudSyncEnabled = result.isSuccess && canEnableLiveSync,
                    cloudSyncUrl = url,
                    cloudSyncIntervalSec = interval.toString(),
                    cloudSyncStatus = result.fold(
                        onSuccess = {
                            val diagnostics = cloudTestDiagnostics(snapshot)
                            val backend = backendAckDiagnostics(it)
                            cloudText(
                                "Тест OK: ${formatTs(System.currentTimeMillis())}; live sync включен; sent: $diagnostics; backend: $backend",
                                "Тэст OK: ${formatTs(System.currentTimeMillis())}; live sync уключаны; sent: $diagnostics; backend: $backend",
                                "Test OK: ${formatTs(System.currentTimeMillis())}; live sync enabled; sent: $diagnostics; backend: $backend",
                            )
                        },
                        onFailure = { e ->
                            cloudText(
                                "Ошибка синхронизации: ${e.message ?: "ошибка"}",
                                "Памылка сінхранізацыі: ${e.message ?: "памылка"}",
                                "Sync failed: ${e.message ?: "error"}",
                            )
                        },
                    )
                )
            }
        }
    }

    private fun cloudTestDiagnostics(snapshot: VehicleTelemetrySnapshot): String {
        val data = snapshot.diPlusData
        val hasDiPlus = data != null
        val hasCells = snapshot.cellVoltageMinV != null &&
            snapshot.cellVoltageMaxV != null &&
            snapshot.cellDeltaV != null
        val hasTpms = listOf(
            data?.tirePressFL,
            data?.tirePressFR,
            data?.tirePressRL,
            data?.tirePressRR,
        ).any { it != null }
        val hasDoorsWindows = listOf(
            data?.doorFL,
            data?.doorFR,
            data?.doorRL,
            data?.doorRR,
            data?.windowFL,
            data?.windowFR,
            data?.windowRL,
            data?.windowRR,
            data?.trunk,
            data?.hood,
        ).any { it != null }
        val hasClimate = listOf(
            data?.insideTemp,
            data?.acStatus,
            data?.acTemp,
            data?.fanLevel,
            data?.acCirc,
            data?.exteriorTemp,
        ).any { it != null }
        val hasLocation = snapshot.location != null
        val hasCore = snapshot.soc != null || snapshot.speedKmh != null || snapshot.odometerKm != null
        val cellText = if (hasCells) {
            "cells ${"%.3f".format(snapshot.cellDeltaV ?: 0.0)}V"
        } else {
            "cells missing"
        }
        return listOf(
            "core ${if (hasCore) "OK" else "missing"}",
            "diplus ${if (hasDiPlus) "OK" else "missing"}",
            cellText,
            "tpms ${if (hasTpms) "OK" else "missing"}",
            "doors/windows ${if (hasDoorsWindows) "OK" else "missing"}",
            "climate ${if (hasClimate) "OK" else "missing"}",
            "gps ${if (hasLocation) "OK" else "missing"}",
        ).joinToString(", ")
    }

    private fun backendAckDiagnostics(responseBody: String?): String {
        val body = responseBody?.trim().orEmpty()
        if (body.isBlank()) return "empty ack"
        return runCatching {
            val json = JSONObject(body)
            val ok = json.optBoolean("ok", json.optBoolean("success", true))
            val persisted = json.optJSONObject("persisted")
                ?: json.optJSONObject("saved")
                ?: json.optJSONObject("sample")
                ?: json.optJSONObject("data")
                ?: json
            val hasDiPlus = findNonEmptyObject(persisted, "diplus")
            val hasCellDelta = findNonNull(persisted, "diplus_cell_delta_v") ||
                findNonNull(persisted, "cell_delta_v")
            val hasCellMin = findNonNull(persisted, "diplus_min_cell_voltage_v") ||
                findNonNull(persisted, "cell_voltage_min_v") ||
                findNonNull(persisted, "min_cell_voltage_v")
            val hasCellMax = findNonNull(persisted, "diplus_max_cell_voltage_v") ||
                findNonNull(persisted, "cell_voltage_max_v") ||
                findNonNull(persisted, "max_cell_voltage_v")
            "ok=$ok, db diplus ${if (hasDiPlus) "OK" else "missing"}, db cells ${if (hasCellMin && hasCellMax && hasCellDelta) "OK" else "missing"}"
        }.getOrElse {
            "raw ${body.take(160)}"
        }
    }

    private fun findNonNull(value: Any?, key: String): Boolean {
        return when (value) {
            is JSONObject -> {
                if (value.has(key) && !value.isNull(key)) {
                    true
                } else {
                    value.keys().asSequence().any { findNonNull(value.opt(it), key) }
                }
            }
            is JSONArray -> (0 until value.length()).any { findNonNull(value.opt(it), key) }
            else -> false
        }
    }

    private fun findNonEmptyObject(value: Any?, key: String): Boolean {
        return when (value) {
            is JSONObject -> {
                val direct = value.opt(key)
                if (direct is JSONObject && direct.length() > 0) {
                    true
                } else {
                    value.keys().asSequence().any { findNonEmptyObject(value.opt(it), key) }
                }
            }
            is JSONArray -> (0 until value.length()).any { findNonEmptyObject(value.opt(it), key) }
            else -> false
        }
    }

    private suspend fun formatCloudSyncStatus(): String? {
        val ts = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_TS, "0").toLongOrNull() ?: 0L
        if (ts <= 0L) return null
        val ok = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_OK, "false") == "true"
        val message = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ERROR, "")
        val language = settingsRepository.getString(
            SettingsRepository.KEY_APP_LANGUAGE,
            SettingsRepository.DEFAULT_APP_LANGUAGE,
        )
        val prefix = when (language) {
            SettingsRepository.LANGUAGE_RU -> if (ok) "Синхронизация OK" else "Ошибка синхронизации"
            SettingsRepository.LANGUAGE_EN -> if (ok) "Sync OK" else "Sync failed"
            else -> if (ok) "Сінхранізацыя OK" else "Памылка сінхранізацыі"
        }
        return "$prefix: ${formatTs(ts)}" +
            if (!ok && message.isNotBlank()) " ($message)" else ""
    }

    private fun cloudText(ru: String, be: String, en: String): String =
        when (_uiState.value.appLanguage) {
            SettingsRepository.LANGUAGE_RU -> ru
            SettingsRepository.LANGUAGE_EN -> en
            else -> be
        }

    private fun formatTs(ts: Long): String =
        SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US).format(Date(ts))

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun saveConsumptionGood(value: String) {
        _uiState.update { it.copy(consumptionGood = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CONSUMPTION_GOOD, value)
        }
    }

    fun saveConsumptionBad(value: String) {
        _uiState.update { it.copy(consumptionBad = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CONSUMPTION_BAD, value)
        }
    }

    private var logProcess: Process? = null
    private var logFile: File? = null
    private var logAutoStopJob: Job? = null

    companion object {
        private const val LOG_MAX_DURATION_MS = 2 * 60 * 60 * 1000L // 2 hours auto-stop
        private const val LOG_MAX_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB max
    }

    fun startLogRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "bydmate_logs_$timestamp.txt"

                val saveDir = listOf(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    File("/storage/emulated/0/Download"),
                    appContext.getExternalFilesDir(null)
                ).firstOrNull { dir ->
                    dir != null && (dir.exists() || dir.mkdirs()) && dir.canWrite()
                }

                if (saveDir == null) {
                    _uiState.update { it.copy(logSaveStatus = "Ошибка: нет доступа к файловой системе") }
                    return@launch
                }

                logFile = File(saveDir, fileName)

                // Clear logcat buffer and start continuous recording
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

                logProcess = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-v", "time",
                    "-s", "BootReceiver:*", "SilentStartActivity:*",
                    "DiParsClient:*", "TrackingService:*", "TripTracker:*",
                    "DiPlusDbReader:*",
                    "HistoryImporter:*", "EnergyDataReader:*"
                ))

                // Background thread to pipe logcat to file with size limit
                Thread {
                    try {
                        logProcess?.inputStream?.bufferedReader()?.use { reader ->
                            logFile?.bufferedWriter()?.use { writer ->
                                var line = reader.readLine()
                                while (line != null) {
                                    // Stop if file exceeds size limit
                                    if ((logFile?.length() ?: 0) > LOG_MAX_SIZE_BYTES) {
                                        writer.write("--- LOG STOPPED: file size limit reached (50 MB) ---")
                                        writer.newLine()
                                        break
                                    }
                                    writer.write(line)
                                    writer.newLine()
                                    writer.flush()
                                    line = reader.readLine()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }.start()

                // Auto-stop after 2 hours
                logAutoStopJob = viewModelScope.launch {
                    delay(LOG_MAX_DURATION_MS)
                    stopLogRecording()
                }

                _uiState.update {
                    it.copy(isRecordingLogs = true, logSaveStatus = "Запись (авто-стоп через 2ч)... → ${logFile?.absolutePath}")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(logSaveStatus = "Ошибка: ${e.message}") }
            }
        }
    }

    fun stopLogRecording() {
        logAutoStopJob?.cancel()
        logAutoStopJob = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logProcess?.destroy()
                logProcess = null

                val file = logFile
                val sizeKb = (file?.length() ?: 0) / 1024

                _uiState.update {
                    it.copy(
                        isRecordingLogs = false,
                        logSaveStatus = "Сохранено: ${file?.absolutePath} (${sizeKb} КБ)"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isRecordingLogs = false, logSaveStatus = "Ошибка: ${e.message}")
                }
            }
        }
    }

    fun showUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = true, updateDialogState = UpdateState.Idle) }
    }

    fun hideUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        UpdateChecker.setAutoCheckEnabled(appContext, enabled)
        _uiState.update { it.copy(autoCheckUpdates = enabled) }
    }

    /** Check for app updates on GitHub. */
    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(updateDialogState = UpdateState.Checking, updateStatus = "Проверка...") }
            try {
                val update = updateChecker.checkForUpdate(appContext, forceCheck = true)
                if (update != null) {
                    _uiState.update {
                        it.copy(
                            updateDialogState = UpdateState.Available(
                                version = update.version,
                                notes = update.releaseNotes ?: ""
                            ),
                            updateStatus = "Доступна v${update.version}"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            updateDialogState = UpdateState.UpToDate,
                            updateStatus = "Установлена последняя версия"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        updateDialogState = UpdateState.Error(e.message ?: "Unknown error"),
                        updateStatus = "Ошибка: ${e.message}"
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        viewModelScope.launch {
            try {
                val update = updateChecker.checkForUpdate(appContext, forceCheck = true)
                if (update != null) {
                    _uiState.update {
                        it.copy(updateDialogState = UpdateState.Downloading(update.version, "Скачивание: 0%"))
                    }
                    updateChecker.downloadAndInstall(appContext, update) { progress ->
                        _uiState.update {
                            it.copy(updateDialogState = UpdateState.Downloading(update.version, progress))
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(updateDialogState = UpdateState.Error(e.message ?: "Download failed")) }
            }
        }
    }
}
