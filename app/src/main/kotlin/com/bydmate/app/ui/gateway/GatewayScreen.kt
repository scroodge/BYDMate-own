package com.bydmate.app.ui.gateway

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.service.TrackingService
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.util.BackgroundRestriction
import com.bydmate.app.util.HeadUnitSettings
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.settings.AdbStatus
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

private const val ADB_GUIDE_URL =
    "https://github.com/scroodge/BYDMate-own/blob/main/docs/guides/dilink5-adb-activation-ru.pdf"

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

    // Re-check the head-unit "Disable background Apps" restriction on every resume,
    // so the warning clears immediately after the user returns from the BYD setting.
    val lifecycleOwner = LocalLifecycleOwner.current
    var backgroundRestricted by remember {
        mutableStateOf(BackgroundRestriction.isRestricted(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundRestricted = BackgroundRestriction.isRestricted(context)
                viewModel.refreshAdbStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.refreshAdbStatus() }

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

        if (backgroundRestricted) {
            BackgroundRestrictionCard(
                onOpenSettings = { BackgroundRestriction.openBackgroundSettings(context) },
                strings = strings,
            )
        }

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
            keepWifiAwake = state.cloudSyncKeepWifiAwake,
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
            onKeepWifiAwake = viewModel::toggleCloudSyncKeepWifiAwake,
            onSave = viewModel::saveCloudSyncSettings,
            onTest = viewModel::sendCloudTestPayload,
            diagnosticLog = state.diagnosticLog,
            onDiagnostics = viewModel::runDiagnostics,
            onClearDiagnostics = viewModel::clearDiagnosticLog,
            strings = strings,
        )

        AdvancedFeaturesCard(
            adbStatus = state.adbStatus,
            onConnectAdb = viewModel::connectAdb,
            onOpenGuide = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(ADB_GUIDE_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onOpenNetworkSettings = { HeadUnitSettings.openParkedNetworkSettings(context) },
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
private fun BackgroundRestrictionCard(
    onOpenSettings: () -> Unit,
    strings: GatewayStrings,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                strings.bgRestrictedTitle,
                color = AccentOrange,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                strings.bgRestrictedBody,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentOrange,
                    contentColor = NavyDark,
                ),
            ) {
                Text(strings.bgRestrictedAction, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AdvancedFeaturesCard(
    adbStatus: AdbStatus,
    onConnectAdb: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    strings: GatewayStrings,
) {
    var howtoOpen by remember { mutableStateOf(false) }
    GatewayCard {
        Text(strings.advancedTitle, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(strings.advancedSubtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        val statusText = when (adbStatus) {
            AdbStatus.CONNECTED -> strings.adbStatusConnected
            AdbStatus.CONNECTING -> strings.adbStatusConnecting
            else -> strings.adbStatusNotSet
        }
        StatusRow(strings.adbStatusLabel, statusText, adbStatus == AdbStatus.CONNECTED)
        if (adbStatus == AdbStatus.CONNECTED) {
            Text(strings.adbConnected, color = AccentGreen, fontSize = 12.sp)
        } else {
            Button(
                onClick = onConnectAdb,
                enabled = adbStatus != AdbStatus.CONNECTING,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
            ) {
                Text(
                    if (adbStatus == AdbStatus.CONNECTING) strings.adbStatusConnecting else strings.adbConnectAction,
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = { howtoOpen = !howtoOpen },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary),
            ) { Text(strings.adbHowtoToggle, fontWeight = FontWeight.Medium) }
            if (howtoOpen) {
                Text(strings.adbHowtoBody, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Button(
                    onClick = onOpenGuide,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = TextPrimary),
                ) { Text(strings.adbGuideAction, fontWeight = FontWeight.Medium) }
            }
        }
        Text(strings.parkedNetworkHint, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        Button(
            onClick = onOpenNetworkSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary),
        ) { Text(strings.parkedNetworkAction, fontWeight = FontWeight.Medium) }
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
    keepWifiAwake: Boolean,
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
    onKeepWifiAwake: (Boolean) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    diagnosticLog: String?,
    onDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
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
        val vehicleNameMissing = vehicleId.trim().isBlank()
        GatewayTextField(strings.linkCode, linkCode, onLinkCode, KeyboardType.Number)
        GatewayHint(strings.linkCodeHint)
        GatewayTextField(
            label = strings.carName,
            value = vehicleId,
            onValueChange = onVehicleId,
            keyboardType = KeyboardType.Text,
            isError = vehicleNameMissing,
        )
        if (vehicleNameMissing) {
            Text(strings.carNameRequired, color = AccentOrange, fontSize = 11.sp)
        } else {
            GatewayHint(strings.carNameHint)
        }
        Button(
            onClick = onConnect,
            enabled = !linking && linkCode.length == 6 && !vehicleNameMissing,
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
            GatewayTextField(
                label = strings.endpointUrl,
                value = url,
                onValueChange = onUrl,
                keyboardType = KeyboardType.Uri,
                placeholder = SettingsRepository.CLOUD_SYNC_ENDPOINT_PLACEHOLDER,
            )
            GatewayHint(strings.endpointHint)
            GatewayTextField("API Key", apiKey, onApiKey, KeyboardType.Password, password = true)
            GatewayHint(strings.apiKeyHint)
        }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.keepWifiAwake, color = TextPrimary, fontSize = 14.sp)
                Text(strings.keepWifiAwakeHint, color = TextSecondary, fontSize = 11.sp)
            }
            Switch(checked = keepWifiAwake, onCheckedChange = onKeepWifiAwake, colors = bydSwitchColors())
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
                enabled = !vehicleNameMissing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary)
            ) {
                Text(strings.sendTest)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Storage diagnostics: works without ADB (plain File API). Lets remote
        // users report whether their DiLink writes the BYD energydata database.
        Button(
            onClick = onDiagnostics,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary)
        ) {
            Text(strings.storageDiag)
        }
        if (diagnosticLog != null) {
            val clipboard = LocalClipboardManager.current
            Spacer(modifier = Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    diagnosticLog,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(diagnosticLog)) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
                ) {
                    Text(strings.copyReport, fontSize = 12.sp)
                }
                Button(
                    onClick = onClearDiagnostics,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextSecondary)
                ) {
                    Text(strings.hideReport, fontSize = 12.sp)
                }
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
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
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
            errorBorderColor = AccentOrange,
            errorLabelColor = AccentOrange,
            errorCursorColor = AccentOrange,
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
    val carNameRequired: String,
    val wifiOnly: String,
    val gpsPrivacy: String,
    val keepWifiAwake: String,
    val keepWifiAwakeHint: String,
    val save: String,
    val sendTest: String,
    val storageDiag: String,
    val copyReport: String,
    val hideReport: String,
    val updates: String,
    val checkUpdates: String,
    val checkUpdatesHint: String,
    val checkUpdatesNow: String,
    val bgRestrictedTitle: String,
    val bgRestrictedBody: String,
    val bgRestrictedAction: String,
    val advancedTitle: String,
    val advancedSubtitle: String,
    val adbStatusLabel: String,
    val adbStatusConnected: String,
    val adbStatusConnecting: String,
    val adbStatusNotSet: String,
    val adbConnected: String,
    val adbConnectAction: String,
    val adbHowtoToggle: String,
    val adbHowtoBody: String,
    val adbGuideAction: String,
    val parkedNetworkHint: String,
    val parkedNetworkAction: String,
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
            carName = "Имя авто (обязательно)",
            carNameHint = "Например: Tang, Seal, Leopard 3 или любое удобное имя машины.",
            carNameRequired = "Без имени авто синхронизация не запустится. Например: Tang, Seal, Leopard 3.",
            wifiOnly = "Только Wi-Fi",
            gpsPrivacy = "Скрывать GPS",
            keepWifiAwake = "Держать Wi-Fi на стоянке",
            keepWifiAwakeHint = "Экспериментально: демон переподключает Wi-Fi каждые ~60 с, чтобы телеметрия не терялась на стоянке. Нужен on-device ADB.",
            save = "Сохранить",
            sendTest = "Отправить тест",
            storageDiag = "Диагностика BYD",
            copyReport = "Копировать",
            hideReport = "Скрыть",
            updates = "Обновления",
            checkUpdates = "Проверять обновления",
            checkUpdatesHint = "При запуске проверять GitHub и предлагать обновиться",
            checkUpdatesNow = "Проверить обновления сейчас",
            bgRestrictedTitle = "Фоновая работа ограничена",
            bgRestrictedBody = "Головное устройство ограничивает фоновую работу VoltFlow Mate — данные перестанут идти, когда машина уснёт. Откройте «Disable background Apps» и отключите ограничение для VoltFlow Mate (OFF).",
            bgRestrictedAction = "Открыть настройки фона",
            advancedTitle = "Расширенные функции",
            advancedSubtitle = "Удалённые команды, телеметрия при выключенной машине, чтение SoH. Нужен разовый on-device ADB — без компьютера.",
            adbStatusLabel = "On-device ADB",
            adbStatusConnected = "Подключён",
            adbStatusConnecting = "Подключение…",
            adbStatusNotSet = "Не настроен",
            adbConnected = "Готово — расширенные функции доступны.",
            adbConnectAction = "Подключить ADB",
            adbHowtoToggle = "Как включить беспроводной ADB",
            adbHowtoBody = "ADB включается на самом планшете, без ПК: инженерное меню → TestTools → «Wireless adb debug switch». Затем нажмите «Подключить ADB» и подтвердите «Allow USB debugging?» прямо на экране.",
            adbGuideAction = "Открыть инструкцию",
            parkedNetworkHint = "Чтобы данные шли при выключенной машине, включите «Keep network on while parked» — Wi-Fi не отключится на стоянке.",
            parkedNetworkAction = "Сеть на стоянке",
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
            carName = "Car name (required)",
            carNameHint = "For example: Tang, Seal, Leopard 3, or any convenient car name.",
            carNameRequired = "Without a car name sync won't start. For example: Tang, Seal, Leopard 3.",
            wifiOnly = "Wi-Fi only",
            gpsPrivacy = "Hide GPS",
            keepWifiAwake = "Keep Wi-Fi awake while parked",
            keepWifiAwakeHint = "Experimental: the daemon reconnects Wi-Fi every ~60s so telemetry doesn't drop while parked. Requires on-device ADB.",
            save = "Save",
            sendTest = "Send test",
            storageDiag = "BYD storage check",
            copyReport = "Copy",
            hideReport = "Hide",
            updates = "Updates",
            checkUpdates = "Check for updates",
            checkUpdatesHint = "On launch, check GitHub and offer to update",
            checkUpdatesNow = "Check for updates now",
            bgRestrictedTitle = "Background activity restricted",
            bgRestrictedBody = "The head unit is restricting VoltFlow Mate in the background — data will stop once the car sleeps. Open “Disable background Apps” and turn the restriction OFF for VoltFlow Mate.",
            bgRestrictedAction = "Open background settings",
            advancedTitle = "Advanced features",
            advancedSubtitle = "Remote commands, telemetry while the car is off, SoH reads. Requires a one-time on-device ADB — no computer needed.",
            adbStatusLabel = "On-device ADB",
            adbStatusConnected = "Connected",
            adbStatusConnecting = "Connecting…",
            adbStatusNotSet = "Not set up",
            adbConnected = "Ready — advanced features available.",
            adbConnectAction = "Connect ADB",
            adbHowtoToggle = "How to enable wireless ADB",
            adbHowtoBody = "ADB is enabled on the tablet itself, no PC: engineering menu → TestTools → “Wireless adb debug switch”. Then tap “Connect ADB” and confirm “Allow USB debugging?” right on screen.",
            adbGuideAction = "Open guide",
            parkedNetworkHint = "For data while the car is off, enable “Keep network on while parked” so Wi-Fi stays up after parking.",
            parkedNetworkAction = "Network while parked",
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
            carName = "Імя аўто (абавязкова)",
            carNameHint = "Напрыклад: Tang, Seal, Leopard 3 або любое зручнае імя машыны.",
            carNameRequired = "Без імя аўто сінхранізацыя не запусціцца. Напрыклад: Tang, Seal, Leopard 3.",
            wifiOnly = "Толькі Wi-Fi",
            gpsPrivacy = "Хаваць GPS",
            keepWifiAwake = "Трымаць Wi-Fi на стаянцы",
            keepWifiAwakeHint = "Эксперыментальна: дэман перападключае Wi-Fi кожныя ~60 с, каб тэлеметрыя не гублялася на стаянцы. Патрэбны on-device ADB.",
            save = "Захаваць",
            sendTest = "Адправіць тэст",
            storageDiag = "Дыягностыка BYD",
            copyReport = "Капіяваць",
            hideReport = "Схаваць",
            updates = "Абнаўленні",
            checkUpdates = "Правяраць абнаўленні",
            checkUpdatesHint = "Пры запуску правяраць GitHub і прапаноўваць абнавіцца",
            checkUpdatesNow = "Праверыць абнаўленні зараз",
            bgRestrictedTitle = "Фонавая праца абмежавана",
            bgRestrictedBody = "Галаўное прыладзе абмяжоўвае фонавую працу VoltFlow Mate — даныя спыняцца, калі машына засне. Адкрыйце «Disable background Apps» і адключыце абмежаванне для VoltFlow Mate (OFF).",
            bgRestrictedAction = "Адкрыць налады фону",
            advancedTitle = "Пашыраныя функцыі",
            advancedSubtitle = "Аддаленыя каманды, тэлеметрыя пры выключанай машыне, чытанне SoH. Патрэбен разавы on-device ADB — без камп'ютара.",
            adbStatusLabel = "On-device ADB",
            adbStatusConnected = "Падключаны",
            adbStatusConnecting = "Падключэнне…",
            adbStatusNotSet = "Не наладжаны",
            adbConnected = "Гатова — пашыраныя функцыі даступны.",
            adbConnectAction = "Падключыць ADB",
            adbHowtoToggle = "Як уключыць бесправадны ADB",
            adbHowtoBody = "ADB уключаецца на самім планшэце, без ПК: інжынернае меню → TestTools → «Wireless adb debug switch». Потым націсніце «Падключыць ADB» і пацвердзіце «Allow USB debugging?» прама на экране.",
            adbGuideAction = "Адкрыць інструкцыю",
            parkedNetworkHint = "Каб даныя ішлі пры выключанай машыне, уключыце «Keep network on while parked» — Wi-Fi не адключыцца на стаянцы.",
            parkedNetworkAction = "Сетка на стаянцы",
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
