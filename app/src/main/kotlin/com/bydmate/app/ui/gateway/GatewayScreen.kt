package com.bydmate.app.ui.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.service.TrackingService
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.settings.SettingsViewModel
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

@Composable
fun GatewayScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    autoCheckUpdates: Boolean = true,
    onAutoCheckUpdatesChange: (Boolean) -> Unit = {},
    onCheckUpdatesNow: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isRunning by TrackingService.isRunning.collectAsStateWithLifecycle()
    val diPlusConnected by TrackingService.diPlusConnected.collectAsStateWithLifecycle()
    val data by TrackingService.lastData.collectAsStateWithLifecycle()
    val rangeKm by TrackingService.lastRangeKm.collectAsStateWithLifecycle()
    val tripDistanceKm by TrackingService.tripDistanceKm.collectAsStateWithLifecycle()
    val location by TrackingService.lastLocation.collectAsStateWithLifecycle()
    val strings = gatewayStrings(state.appLanguage)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LanguageSwitcher(
            language = state.appLanguage,
            onLanguageChange = viewModel::updateAppLanguage,
        )
        Header(appVersion = state.appVersion, strings = strings)

        StatusCard(
            isRunning = isRunning,
            diPlusConnected = diPlusConnected,
            cloudSyncStatus = state.cloudSyncStatus,
            onStart = { TrackingService.start(context) },
            onStop = { TrackingService.stop(context) },
            strings = strings,
        )

        LiveDataCard(
            soc = data?.soc,
            speed = data?.speed,
            power = data?.power,
            batteryTemp = data?.avgBatTemp ?: data?.maxBatTemp,
            cabinTemp = data?.insideTemp,
            outsideTemp = data?.exteriorTemp,
            auxVoltage = data?.voltage12v,
            odometer = data?.mileage,
            rangeKm = rangeKm,
            tripDistanceKm = tripDistanceKm,
            hasLocation = location != null,
            strings = strings,
        )

        CloudSyncCard(
            enabled = state.cloudSyncEnabled,
            url = state.cloudSyncUrl,
            apiKey = state.cloudSyncApiKey,
            linkCode = state.cloudSyncLinkCode,
            advancedOpen = state.cloudSyncAdvancedOpen,
            linking = state.cloudSyncLinking,
            vehicleId = state.cloudSyncVehicleId,
            wifiOnly = state.cloudSyncWifiOnly,
            omitGps = state.cloudSyncOmitGps,
            status = state.cloudSyncStatus,
            onEnabled = viewModel::toggleCloudSync,
            onUrl = viewModel::updateCloudSyncUrl,
            onLinkCode = viewModel::updateCloudSyncLinkCode,
            onConnect = viewModel::redeemVoltflowLinkCode,
            onToggleAdvanced = viewModel::toggleCloudSyncAdvanced,
            onApiKey = viewModel::updateCloudSyncApiKey,
            onVehicleId = viewModel::updateCloudSyncVehicleId,
            onWifiOnly = viewModel::toggleCloudSyncWifiOnly,
            onOmitGps = viewModel::toggleCloudSyncOmitGps,
            onSave = viewModel::saveCloudSyncSettings,
            onTest = viewModel::sendCloudTestPayload,
            strings = strings,
        )

        UpdatesCard(
            autoCheckUpdates = autoCheckUpdates,
            onAutoCheckChange = onAutoCheckUpdatesChange,
            onCheckNow = onCheckUpdatesNow,
            strings = strings,
        )

        Text(
            strings.gatewayMode,
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun LanguageSwitcher(
    language: String,
    onLanguageChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageButton(
                text = "BE",
                selected = language == SettingsRepository.LANGUAGE_BE,
                onClick = { onLanguageChange(SettingsRepository.LANGUAGE_BE) },
            )
            LanguageButton(
                text = "RU",
                selected = language == SettingsRepository.LANGUAGE_RU,
                onClick = { onLanguageChange(SettingsRepository.LANGUAGE_RU) },
            )
            LanguageButton(
                text = "EN",
                selected = language == SettingsRepository.LANGUAGE_EN,
                onClick = { onLanguageChange(SettingsRepository.LANGUAGE_EN) },
            )
        }
    }
}

@Composable
private fun LanguageButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AccentGreen else CardSurfaceElevated,
            contentColor = if (selected) NavyDark else TextPrimary,
        ),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}


@Composable
private fun UpdatesCard(
    autoCheckUpdates: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    strings: GatewayStrings,
) {
    GatewayCard {
        Text(strings.updates, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(strings.checkUpdates, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(strings.checkUpdatesHint, color = TextSecondary, fontSize = 12.sp)
            }
            Switch(
                checked = autoCheckUpdates,
                onCheckedChange = onAutoCheckChange,
                colors = bydSwitchColors(),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onCheckNow,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = TextPrimary),
        ) {
            Text(strings.checkUpdatesNow, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Header(appVersion: String, strings: GatewayStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.voltflow_cloud_release),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "VoltFlow Mate",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "${strings.bridge} • v$appVersion",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StatusCard(
    isRunning: Boolean,
    diPlusConnected: Boolean,
    cloudSyncStatus: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    strings: GatewayStrings,
) {
    GatewayCard {
        Text(strings.gatewayStatus, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        StatusRow(strings.service, if (isRunning) strings.running else strings.stopped, isRunning)
        StatusRow("DiPlus", if (diPlusConnected) strings.connected else strings.waiting, diPlusConnected)
        cloudSyncStatus?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, color = if (it.isErrorStatus()) AccentOrange else TextSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text(strings.start, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary)
            ) {
                Text(strings.stop)
            }
        }
    }
}

@Composable
private fun LiveDataCard(
    soc: Int?,
    speed: Int?,
    power: Double?,
    batteryTemp: Int?,
    cabinTemp: Int?,
    outsideTemp: Int?,
    auxVoltage: Double?,
    odometer: Double?,
    rangeKm: Double?,
    tripDistanceKm: Double?,
    hasLocation: Boolean,
    strings: GatewayStrings,
) {
    GatewayCard {
        Text(strings.latestData, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("SOC", fmt(soc?.toDouble(), 0, "%"), Modifier.weight(1f))
            Metric(strings.speed, fmt(speed?.toDouble(), 0, " km/h"), Modifier.weight(1f))
            Metric(strings.power, fmt(power, 1, " kW"), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric(strings.range, fmt(rangeKm, 0, " km"), Modifier.weight(1f))
            Metric(strings.trip, fmt(tripDistanceKm, 1, " km"), Modifier.weight(1f))
            Metric("12V", fmt(auxVoltage, 1, " V"), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric(strings.battery, fmtTemp(batteryTemp), Modifier.weight(1f))
            Metric(strings.cabin, fmtTemp(cabinTemp), Modifier.weight(1f))
            Metric(strings.outside, fmtTemp(outsideTemp), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        StatusRow(strings.odometer, fmt(odometer, 1, " km"), odometer != null)
        StatusRow("GPS", if (hasLocation) strings.available else strings.noPermissionData, hasLocation)
    }
}

@Composable
private fun CloudSyncCard(
    enabled: Boolean,
    url: String,
    apiKey: String,
    linkCode: String,
    advancedOpen: Boolean,
    linking: Boolean,
    vehicleId: String,
    wifiOnly: Boolean,
    omitGps: Boolean,
    status: String?,
    onEnabled: (Boolean) -> Unit,
    onUrl: (String) -> Unit,
    onLinkCode: (String) -> Unit,
    onConnect: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onApiKey: (String) -> Unit,
    onVehicleId: (String) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onOmitGps: (Boolean) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    strings: GatewayStrings,
) {
    GatewayCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.voltFlowSync, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(strings.postTelemetry, color = TextSecondary, fontSize = 12.sp)
            }
            Switch(checked = enabled, onCheckedChange = onEnabled, colors = bydSwitchColors())
        }
        Spacer(modifier = Modifier.height(10.dp))
        GatewayTextField(
            label = strings.endpointUrl,
            value = url,
            onValueChange = onUrl,
            keyboardType = KeyboardType.Uri,
            placeholder = SettingsRepository.CLOUD_SYNC_ENDPOINT_PLACEHOLDER,
        )
        GatewayHint(strings.endpointHint)
        GatewayTextField(strings.linkCode, linkCode, onLinkCode, KeyboardType.Number)
        GatewayHint(strings.linkCodeHint)
        Button(
            onClick = onConnect,
            enabled = !linking && linkCode.length == 6,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
        ) {
            Text(
                if (linking) strings.connecting else strings.connect,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = onToggleAdvanced,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary),
        ) {
            Text(strings.advanced, fontWeight = FontWeight.Medium)
        }
        if (advancedOpen) {
            Spacer(modifier = Modifier.height(8.dp))
            GatewayTextField("API Key", apiKey, onApiKey, KeyboardType.Password, password = true)
            GatewayHint(strings.apiKeyHint)
        }
        GatewayTextField(strings.carName, vehicleId, onVehicleId, KeyboardType.Text)
        GatewayHint(strings.carNameHint)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.wifiOnly, color = TextPrimary, fontSize = 14.sp)
            Switch(checked = wifiOnly, onCheckedChange = onWifiOnly, colors = bydSwitchColors())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.gpsPrivacy, color = TextPrimary, fontSize = 14.sp)
            Switch(checked = omitGps, onCheckedChange = onOmitGps, colors = bydSwitchColors())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text(strings.save, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary)
            ) {
                Text(strings.sendTest)
            }
        }
        status?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                it,
                color = if (it.isErrorStatus()) AccentOrange else AccentGreen,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun GatewayHint(text: String) {
    Text(text, color = TextSecondary, fontSize = 11.sp)
}

@Composable
private fun GatewayTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    password: Boolean = false,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = AccentGreen,
            unfocusedLabelColor = TextSecondary,
            focusedBorderColor = AccentGreen,
            unfocusedBorderColor = TextMuted,
            cursorColor = AccentGreen,
        )
    )
}

@Composable
private fun GatewayCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = if (ok) AccentGreen else AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

private data class GatewayStrings(
    val bridge: String,
    val gatewayMode: String,
    val gatewayStatus: String,
    val service: String,
    val running: String,
    val stopped: String,
    val connected: String,
    val waiting: String,
    val start: String,
    val stop: String,
    val latestData: String,
    val speed: String,
    val power: String,
    val range: String,
    val trip: String,
    val battery: String,
    val cabin: String,
    val outside: String,
    val odometer: String,
    val available: String,
    val noPermissionData: String,
    val voltFlowSync: String,
    val postTelemetry: String,
    val endpointUrl: String,
    val endpointHint: String,
    val apiKeyHint: String,
    val linkCode: String,
    val linkCodeHint: String,
    val connect: String,
    val connecting: String,
    val advanced: String,
    val carName: String,
    val carNameHint: String,
    val wifiOnly: String,
    val gpsPrivacy: String,
    val save: String,
    val sendTest: String,
    val updates: String,
    val checkUpdates: String,
    val checkUpdatesHint: String,
    val checkUpdatesNow: String,
)

private fun gatewayStrings(language: String): GatewayStrings =
    when (language) {
        SettingsRepository.LANGUAGE_RU -> GatewayStrings(
            bridge = "Мост телеметрии VoltFlow",
            gatewayMode = "Режим шлюза: приложение читает live-данные DiPlus/BYD и отправляет их в VoltFlow. Поездки, AI, автоматизация и локальная аналитика скрыты из интерфейса.",
            gatewayStatus = "Статус шлюза",
            service = "Сервис",
            running = "Запущен",
            stopped = "Остановлен",
            connected = "Подключен",
            waiting = "Ожидание",
            start = "Запустить",
            stop = "Остановить",
            latestData = "Последние данные авто",
            speed = "Скорость",
            power = "Мощность",
            range = "Запас",
            trip = "Поездка",
            battery = "Батарея",
            cabin = "Салон",
            outside = "Снаружи",
            odometer = "Одометр",
            available = "Доступен",
            noPermissionData = "Нет разрешения/данных",
            voltFlowSync = "Синхронизация VoltFlow",
            postTelemetry = "POST телеметрии на ваш HTTPS endpoint",
            endpointUrl = "Endpoint URL",
            endpointHint = "Endpoint уже указан по умолчанию. Его можно заменить своим HTTPS URL.",
            apiKeyHint = "API Key берется в VoltFlow: Настройки -> CloudSync.",
            linkCode = "Код из VoltFlow",
            linkCodeHint = "6 цифр из VoltFlow: Настройки → VoltFlow Mate → Подключить BYDMate.",
            connect = "Подключить",
            connecting = "Подключение…",
            advanced = "Дополнительно",
            carName = "Ваше имя авто",
            carNameHint = "Например: Tang, Seal, Leopard 3 или любое удобное имя машины.",
            wifiOnly = "Только Wi-Fi",
            gpsPrivacy = "Скрывать GPS",
            save = "Сохранить",
            sendTest = "Отправить тест",
            updates = "Обновления",
            checkUpdates = "Проверять обновления",
            checkUpdatesHint = "При запуске проверять GitHub и предлагать обновиться",
            checkUpdatesNow = "Проверить обновления сейчас",
        )
        SettingsRepository.LANGUAGE_EN -> GatewayStrings(
            bridge = "VoltFlow telemetry bridge",
            gatewayMode = "Gateway mode: the app reads live DiPlus/BYD data and sends it to VoltFlow. Trips, AI, automation, and local analytics are hidden from this interface.",
            gatewayStatus = "Gateway status",
            service = "Service",
            running = "Running",
            stopped = "Stopped",
            connected = "Connected",
            waiting = "Waiting",
            start = "Start",
            stop = "Stop",
            latestData = "Latest vehicle data",
            speed = "Speed",
            power = "Power",
            range = "Range",
            trip = "Trip",
            battery = "Battery",
            cabin = "Cabin",
            outside = "Outside",
            odometer = "Odometer",
            available = "Available",
            noPermissionData = "No permission/data",
            voltFlowSync = "VoltFlow sync",
            postTelemetry = "POST telemetry to your HTTPS endpoint",
            endpointUrl = "Endpoint URL",
            endpointHint = "Endpoint is filled in by default. You can replace it with your own HTTPS URL.",
            apiKeyHint = "API Key is in VoltFlow: Settings -> CloudSync.",
            linkCode = "Code from VoltFlow",
            linkCodeHint = "6 digits from VoltFlow: Settings → VoltFlow Mate → Link BYDMate.",
            connect = "Connect",
            connecting = "Connecting…",
            advanced = "Advanced",
            carName = "Your car name",
            carNameHint = "For example: Tang, Seal, Leopard 3, or any convenient car name.",
            wifiOnly = "Wi-Fi only",
            gpsPrivacy = "Hide GPS",
            save = "Save",
            sendTest = "Send test",
            updates = "Updates",
            checkUpdates = "Check for updates",
            checkUpdatesHint = "On launch, check GitHub and offer to update",
            checkUpdatesNow = "Check for updates now",
        )
        else -> GatewayStrings(
            bridge = "Мост тэлеметрыі VoltFlow",
            gatewayMode = "Рэжым шлюза: праграма чытае live-даныя DiPlus/BYD і адпраўляе іх у VoltFlow. Паездкі, AI, аўтаматызацыя і лакальная аналітыка схаваныя з інтэрфейсу.",
            gatewayStatus = "Статус шлюза",
            service = "Сэрвіс",
            running = "Запушчаны",
            stopped = "Спынены",
            connected = "Падключаны",
            waiting = "Чаканне",
            start = "Запусціць",
            stop = "Спыніць",
            latestData = "Апошнія даныя аўто",
            speed = "Хуткасць",
            power = "Магутнасць",
            range = "Запас",
            trip = "Паездка",
            battery = "Батарэя",
            cabin = "Салон",
            outside = "Звонку",
            odometer = "Адаметр",
            available = "Даступны",
            noPermissionData = "Няма дазволу/даных",
            voltFlowSync = "Сінхранізацыя VoltFlow",
            postTelemetry = "POST тэлеметрыі на ваш HTTPS endpoint",
            endpointUrl = "Endpoint URL",
            endpointHint = "Endpoint ужо пазначаны па змаўчанні. Яго можна замяніць сваім HTTPS URL.",
            apiKeyHint = "API Key бярэцца ў VoltFlow: Налады -> CloudSync.",
            linkCode = "Код з VoltFlow",
            linkCodeHint = "6 лічбаў з VoltFlow: Налады → VoltFlow Mate → Злучыць BYDMate.",
            connect = "Злучыць",
            connecting = "Падключэнне…",
            advanced = "Дадаткова",
            carName = "Ваша імя аўто",
            carNameHint = "Напрыклад: Tang, Seal, Leopard 3 або любое зручнае імя машыны.",
            wifiOnly = "Толькі Wi-Fi",
            gpsPrivacy = "Хаваць GPS",
            save = "Захаваць",
            sendTest = "Адправіць тэст",
            updates = "Абнаўленні",
            checkUpdates = "Правяраць абнаўленні",
            checkUpdatesHint = "Пры запуску правяраць GitHub і прапаноўваць абнавіцца",
            checkUpdatesNow = "Праверыць абнаўленні зараз",
        )
    }

private fun String.isErrorStatus(): Boolean =
    contains("failed", ignoreCase = true) ||
        contains("ошиб", ignoreCase = true) ||
        contains("памыл", ignoreCase = true)

private fun fmt(value: Double?, digits: Int, suffix: String): String =
    if (value != null && value.isFinite()) "%.${digits}f%s".format(value, suffix) else "--"

private fun fmtTemp(value: Int?): String =
    if (value != null && value in -50..90) "$value °C" else "--"
