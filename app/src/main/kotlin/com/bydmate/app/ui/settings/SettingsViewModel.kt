package com.bydmate.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.cloud.CloudTelemetryAckParser
import com.bydmate.app.data.cloud.CloudTelemetrySender
import com.bydmate.app.data.cloud.VoltflowLinkClient
import com.bydmate.app.data.cloud.VoltflowLinkResult
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.remote.DiParsClient
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
 * On-device ADB (127.0.0.1:5555) connection state for the advanced-features
 * wizard. UNKNOWN = not checked yet / never attempted; CONNECTING = handshake in
 * flight (the «Allow USB debugging?» dialog is up on screen); CONNECTED = paired;
 * FAILED = handshake refused (wireless ADB not enabled in the engineering menu,
 * or the user dismissed the dialog).
 */
enum class AdbStatus { UNKNOWN, CONNECTING, CONNECTED, FAILED }

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
    val adbStatus: AdbStatus = AdbStatus.UNKNOWN,
    val cloudSyncEnabled: Boolean = true,
    val cloudSyncUrl: String = SettingsRepository.DEFAULT_CLOUD_SYNC_URL,
    val cloudSyncApiKey: String = "",
    val cloudSyncVehicleId: String = "",
    val cloudSyncWifiOnly: Boolean = false,
    val cloudSyncOmitGps: Boolean = false,
    val cloudSyncKeepWifiAwake: Boolean = false,
    val cloudSyncStatus: String? = null,
    val cloudSyncStatusIsError: Boolean = false,
    val lastCloudSyncTs: Long = 0L,
    val lastCloudSyncOk: Boolean = false,
    val cloudSyncLinkCode: String = "",
    val cloudSyncAdvancedOpen: Boolean = false,
    val cloudSyncLinking: Boolean = false,
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
    private val adbOnDeviceClient: AdbOnDeviceClient,
    private val batteryStateRepository: BatteryStateRepository,
    private val cloudTelemetrySender: CloudTelemetrySender? = null,
    private val voltflowLinkClient: VoltflowLinkClient,
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
        observeLastCloudSync()
    }

    /**
     * B-2: keep the "last synced" indicator live while the screen is open. The cloud
     * sender writes KEY_CLOUD_SYNC_LAST_TS after every attempt; observing it (Room
     * Flow) refreshes the timestamp without reopening the screen. Kept separate from
     * cloudSyncStatus so a background sync never clobbers the manual-test status.
     */
    private fun observeLastCloudSync() {
        viewModelScope.launch {
            settingsRepository.observeString(SettingsRepository.KEY_CLOUD_SYNC_LAST_TS).collect {
                val ts = it?.toLongOrNull() ?: 0L
                val ok = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_OK, "false") == "true"
                _uiState.update { s -> s.copy(lastCloudSyncTs = ts, lastCloudSyncOk = ok) }
            }
        }
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

            val cloudSyncEnabled = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, SettingsRepository.DEFAULT_CLOUD_SYNC_ENABLED) == "true"
            val cloudSyncUrl = settingsRepository.getString(
                SettingsRepository.KEY_CLOUD_SYNC_URL,
                SettingsRepository.DEFAULT_CLOUD_SYNC_URL
            ).ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
            val cloudSyncApiKey = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, "")
            val cloudSyncVehicleId = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, "")
            val cloudSyncWifiOnly = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, "false") == "true"
            val cloudSyncOmitGps = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, "false") == "true"
            val cloudSyncKeepWifiAwake = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE, "false") == "true"
            val cloudSyncStatusPair = formatCloudSyncStatus()
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
                    cloudSyncWifiOnly = cloudSyncWifiOnly,
                    cloudSyncOmitGps = cloudSyncOmitGps,
                    cloudSyncKeepWifiAwake = cloudSyncKeepWifiAwake,
                    cloudSyncStatus = cloudSyncStatusPair?.first,
                    cloudSyncStatusIsError = cloudSyncStatusPair?.second ?: false,
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
     * Re-reads the live on-device ADB connection state. Cheap; safe to call when
     * the advanced-features card appears or resumes. Never downgrades a CONNECTING
     * status (the handshake may still be in flight with the dialog on screen).
     */
    fun refreshAdbStatus() {
        viewModelScope.launch {
            val connected = runCatching { adbOnDeviceClient.isConnected() }.getOrDefault(false)
            _uiState.update { s ->
                when {
                    connected -> s.copy(adbStatus = AdbStatus.CONNECTED)
                    s.adbStatus == AdbStatus.CONNECTING -> s
                    s.adbStatus == AdbStatus.CONNECTED -> s.copy(adbStatus = AdbStatus.UNKNOWN)
                    else -> s
                }
            }
        }
    }

    /**
     * Triggers the on-device ADB handshake — this is what raises the system
     * «Allow USB debugging?» dialog on the head unit (no computer needed). On
     * success the app gains shell-uid abilities (SoH/autoservice reads, D+
     * relaunch, survival-daemon supervision). Failure means wireless ADB is not
     * enabled in the engineering menu, or the dialog was dismissed.
     */
    fun connectAdb() {
        if (_uiState.value.adbStatus == AdbStatus.CONNECTING) return
        _uiState.update { it.copy(adbStatus = AdbStatus.CONNECTING) }
        viewModelScope.launch {
            val ok = runCatching { adbOnDeviceClient.connect().isSuccess }.getOrDefault(false)
            _uiState.update { it.copy(adbStatus = if (ok) AdbStatus.CONNECTED else AdbStatus.FAILED) }
        }
    }

    /** Hide the storage diagnostics report. */
    fun clearDiagnosticLog() {
        _uiState.update { it.copy(diagnosticLog = null) }
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

    fun updateCloudSyncLinkCode(value: String) {
        _uiState.update { it.copy(cloudSyncLinkCode = value.filter { ch -> ch.isDigit() }.take(6)) }
    }

    fun toggleCloudSyncAdvanced() {
        _uiState.update { it.copy(cloudSyncAdvancedOpen = !it.cloudSyncAdvancedOpen) }
    }

    fun redeemVoltflowLinkCode() {
        val state = _uiState.value
        if (state.cloudSyncLinkCode.length != 6) {
            _uiState.update {
                it.copy(
                    cloudSyncStatus = cloudText(
                        "Введите 6-значный код из VoltFlow",
                        "Увядзіце 6-значны код з VoltFlow",
                        "Enter the 6-digit code from VoltFlow",
                    ),
                    cloudSyncStatusIsError = true,
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cloudSyncLinking = true,
                    cloudSyncStatus = cloudText(
                        "Подключение к VoltFlow…",
                        "Падключэнне да VoltFlow…",
                        "Connecting to VoltFlow…",
                    ),
                    cloudSyncStatusIsError = false,
                )
            }

            val telemetryUrl = state.cloudSyncUrl.trim().ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
            when (val result = voltflowLinkClient.redeem(telemetryUrl, state.cloudSyncLinkCode)) {
                is VoltflowLinkResult.Success -> {
                    settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, result.apiKey)
                    settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_URL, result.endpointUrl)
                    _uiState.update {
                        it.copy(
                            cloudSyncApiKey = result.apiKey,
                            cloudSyncUrl = result.endpointUrl,
                            cloudSyncLinkCode = "",
                            cloudSyncLinking = false,
                            cloudSyncStatus = cloudText(
                                "VoltFlow подключен. Сохраните настройки или отправьте тест.",
                                "VoltFlow падключаны. Захавайце налады або адправіць тэст.",
                                "VoltFlow linked. Save settings or send a test.",
                            ),
                            cloudSyncStatusIsError = false,
                        )
                    }
                    if (state.cloudSyncVehicleId.trim().isNotBlank()) {
                        sendCloudTestPayload()
                    }
                }
                is VoltflowLinkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            cloudSyncLinking = false,
                            cloudSyncStatus = cloudText(
                                "Ошибка подключения: ${result.message}",
                                "Памылка падключэння: ${result.message}",
                                "Link failed: ${result.message}",
                            ),
                            cloudSyncStatusIsError = true,
                        )
                    }
                }
            }
        }
    }

    fun updateCloudSyncVehicleId(value: String) {
        _uiState.update { it.copy(cloudSyncVehicleId = value) }
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

    fun toggleCloudSyncKeepWifiAwake(enabled: Boolean) {
        _uiState.update { it.copy(cloudSyncKeepWifiAwake = enabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE, enabled.toString())
        }
    }

    fun saveCloudSyncSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            val url = state.cloudSyncUrl.trim().ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
            val vehicleId = state.cloudSyncVehicleId.trim()
            val enabled = state.cloudSyncEnabled &&
                url.startsWith("https://", ignoreCase = true) &&
                vehicleId.isNotBlank()
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_URL, url)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, state.cloudSyncApiKey)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, vehicleId)
            settingsRepository.setString(
                SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC,
                SettingsRepository.DEFAULT_CLOUD_SYNC_INTERVAL_SEC,
            )
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, state.cloudSyncWifiOnly.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, state.cloudSyncOmitGps.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE, state.cloudSyncKeepWifiAwake.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, enabled.toString())
            val statusIsError = url.isBlank() ||
                !url.startsWith("https://", ignoreCase = true) ||
                vehicleId.isBlank()
            val status = if (url.isBlank()) {
                cloudText("Endpoint URL пустой", "Endpoint URL пусты", "Endpoint URL is empty")
            } else if (!url.startsWith("https://", ignoreCase = true)) {
                cloudText("Endpoint должен начинаться с https://", "Endpoint мусіць пачынацца з https://", "Endpoint must start with https://")
            } else if (vehicleId.isBlank()) {
                cloudText("Укажите имя авто", "Увядзіце імя аўто", "Enter the car name")
            } else {
                cloudText("Сохранено", "Захавана", "Saved")
            }
            _uiState.update {
                it.copy(
                    cloudSyncEnabled = enabled,
                    cloudSyncUrl = url,
                    cloudSyncStatus = status,
                    cloudSyncStatusIsError = statusIsError,
                )
            }
        }
    }

    fun sendCloudTestPayload() {
        val state = _uiState.value
        viewModelScope.launch {
            val url = state.cloudSyncUrl.trim().ifBlank { SettingsRepository.DEFAULT_CLOUD_SYNC_URL }
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_URL, url)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_API_KEY, state.cloudSyncApiKey)
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_VEHICLE_ID, state.cloudSyncVehicleId.trim())
            settingsRepository.setString(
                SettingsRepository.KEY_CLOUD_SYNC_INTERVAL_SEC,
                SettingsRepository.DEFAULT_CLOUD_SYNC_INTERVAL_SEC,
            )
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_WIFI_ONLY, state.cloudSyncWifiOnly.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_OMIT_GPS, state.cloudSyncOmitGps.toString())
            settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE, state.cloudSyncKeepWifiAwake.toString())
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
            _uiState.update { it.copy(cloudSyncStatus = cloudText("Отправка теста...", "Адпраўка тэсту...", "Sending test..."), cloudSyncStatusIsError = false) }
            val result = cloudTelemetrySender?.sendTest(snapshot)
                ?: Result.failure(IllegalStateException(cloudText("Cloud sender недоступен", "Cloud sender недаступны", "Cloud sender is unavailable")))
            if (result.isFailure && !state.cloudSyncEnabled) {
                settingsRepository.setString(SettingsRepository.KEY_CLOUD_SYNC_ENABLED, "false")
            }
            _uiState.update {
                it.copy(
                    cloudSyncEnabled = result.isSuccess && canEnableLiveSync,
                    cloudSyncStatusIsError = result.isFailure,
                    cloudSyncUrl = url,
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
        val ack = CloudTelemetryAckParser.parse(responseBody, sentCount = 1)
        val delivery = ack.formatDiagnostics()
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
            "delivery $delivery; ok=$ok, db diplus ${if (hasDiPlus) "OK" else "missing"}, db cells ${if (hasCellMin && hasCellMax && hasCellDelta) "OK" else "missing"}"
        }.getOrElse {
            "delivery $delivery; raw ${body.take(160)}"
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

    private suspend fun formatCloudSyncStatus(): Pair<String, Boolean>? {
        val ts = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_TS, "0").toLongOrNull() ?: 0L
        if (ts <= 0L) return null
        val ok = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_OK, "false") == "true"
        val message = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ERROR, "")
        val ack = settingsRepository.getString(SettingsRepository.KEY_CLOUD_SYNC_LAST_ACK, "")
        val language = settingsRepository.getString(
            SettingsRepository.KEY_APP_LANGUAGE,
            SettingsRepository.DEFAULT_APP_LANGUAGE,
        )
        val prefix = when (language) {
            SettingsRepository.LANGUAGE_RU -> if (ok) "Синхронизация OK" else "Ошибка синхронизации"
            SettingsRepository.LANGUAGE_EN -> if (ok) "Sync OK" else "Sync failed"
            else -> if (ok) "Сінхранізацыя OK" else "Памылка сінхранізацыі"
        }
        val text = buildString {
            append("$prefix: ${formatTs(ts)}")
            if (ack.isNotBlank()) append("; $ack")
            if (!ok && message.isNotBlank()) append(" ($message)")
        }
        return text to !ok
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
}
