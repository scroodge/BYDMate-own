package com.bydmate.app.ui.gateway

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.Dp
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
import com.bydmate.app.util.LogRecorder
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.settings.AdbStatus
import com.bydmate.app.ui.settings.DaemonStatus
import com.bydmate.app.ui.settings.SettingsViewModel
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
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
    val chargingPowerKw by TrackingService.chargingPowerKw.collectAsStateWithLifecycle()
    val chargingTimeToFullMin by TrackingService.chargingTimeToFullMin.collectAsStateWithLifecycle()
    val location by TrackingService.lastLocation.collectAsStateWithLifecycle()
    val lastDiPlusUpdateMs by TrackingService.lastDiPlusUpdateMs.collectAsStateWithLifecycle()
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
                viewModel.refreshDaemonStatus()
                viewModel.refreshFloatingWidget()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshAdbStatus()
        viewModel.refreshDaemonStatus()
        viewModel.refreshFloatingWidget()
    }

    val statusCard: @Composable (Modifier, Boolean) -> Unit = { mod, stretch ->
        StatusCard(
            isRunning = isRunning,
            diPlusConnected = diPlusConnected,
            cloudSyncStatus = state.cloudSyncStatus,
            cloudSyncStatusIsError = state.cloudSyncStatusIsError,
            lastSyncTs = state.lastCloudSyncTs,
            lastSyncOk = state.lastCloudSyncOk,
            onStart = { TrackingService.start(context) },
            onStop = { TrackingService.stop(context) },
            strings = strings,
            modifier = mod,
            stretch = stretch,
        )
    }
    val liveDataCard: @Composable (Modifier, Boolean) -> Unit = { mod, stretch ->
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
            chargingPowerKw = chargingPowerKw,
            chargingTimeToFullMin = chargingTimeToFullMin,
            hasLocation = location != null,
            lastUpdateMs = lastDiPlusUpdateMs,
            strings = strings,
            modifier = mod,
            stretch = stretch,
        )
    }
    val widgetCard: @Composable () -> Unit = {
        FloatingWidgetCard(
            enabled = state.floatingWidgetEnabled,
            needsPermission = state.floatingWidgetNeedsPermission,
            scale = state.floatingWidgetScale,
            onEnabledChange = viewModel::setFloatingWidgetEnabled,
            onScaleChange = viewModel::setFloatingWidgetScale,
            strings = strings,
        )
    }
    val syncLinked = state.cloudSyncApiKey.isNotBlank() && state.cloudSyncVehicleId.trim().isNotBlank()
    var section by rememberSaveable { mutableStateOf(GatewaySection.HOME) }
    val cloudLinkCard: @Composable () -> Unit = {
        CloudLinkCard(
            linkCode = state.cloudSyncLinkCode,
            linking = state.cloudSyncLinking,
            vehicleId = state.cloudSyncVehicleId,
            status = state.cloudSyncStatus,
            statusIsError = state.cloudSyncStatusIsError,
            onLinkCode = viewModel::updateCloudSyncLinkCode,
            onConnect = viewModel::redeemVoltflowLinkCode,
            onVehicleId = viewModel::updateCloudSyncVehicleId,
            onSave = viewModel::saveCloudSyncSettings,
            strings = strings,
        )
    }
    val cloudSwitchesCard: @Composable () -> Unit = {
        CloudSwitchesCard(
            enabled = state.cloudSyncEnabled,
            wifiOnly = state.cloudSyncWifiOnly,
            omitGps = state.cloudSyncOmitGps,
            keepWifiAwake = state.cloudSyncKeepWifiAwake,
            socFromCar = state.cloudSocFromCar,
            onEnabled = viewModel::toggleCloudSync,
            onWifiOnly = viewModel::toggleCloudSyncWifiOnly,
            onOmitGps = viewModel::toggleCloudSyncOmitGps,
            onKeepWifiAwake = viewModel::toggleCloudSyncKeepWifiAwake,
            onSocFromCar = viewModel::toggleCloudSocFromCar,
            strings = strings,
        )
    }
    val cloudDiagnosticsCard: @Composable () -> Unit = {
        CloudDiagnosticsCard(
            url = state.cloudSyncUrl,
            apiKey = state.cloudSyncApiKey,
            vehicleId = state.cloudSyncVehicleId,
            status = state.cloudSyncStatus,
            statusIsError = state.cloudSyncStatusIsError,
            onUrl = viewModel::updateCloudSyncUrl,
            onApiKey = viewModel::updateCloudSyncApiKey,
            onTest = viewModel::sendCloudTestPayload,
            diagnosticLog = state.diagnosticLog,
            onDiagnostics = viewModel::runDiagnostics,
            onClearDiagnostics = viewModel::clearDiagnosticLog,
            strings = strings,
        )
    }
    // An unlinked car has nothing to show on Home but this: the one thing it needs to do next.
    val linkPromptCard: @Composable () -> Unit = {
        if (!syncLinked) {
            GatewayCard {
                Text(strings.notLinked, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Button(
                    onClick = { section = GatewaySection.LINK },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                ) { Text(strings.linkCar, fontWeight = FontWeight.Bold) }
            }
        }
    }
    val advancedCard: @Composable () -> Unit = {
        AdvancedFeaturesCard(
            adbStatus = state.adbStatus,
            onConnectAdb = viewModel::connectAdb,
            daemonStatus = state.daemonStatus,
            onInstallDaemon = viewModel::installDaemon,
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
    }
    val logCaptureCard: @Composable () -> Unit = { LogCaptureCard(strings = strings) }
    val updatesCard: @Composable () -> Unit = {
        UpdatesCard(
            autoCheckUpdates = autoCheckUpdates,
            onAutoCheckChange = onAutoCheckUpdatesChange,
            onCheckNow = onCheckUpdatesNow,
            strings = strings,
        )
    }
    val backgroundCard: @Composable () -> Unit = {
        if (backgroundRestricted) {
            BackgroundRestrictionCard(
                onOpenSettings = { BackgroundRestriction.openBackgroundSettings(context) },
                strings = strings,
            )
        }
    }
    val footer: @Composable () -> Unit = {
        Text(
            strings.gatewayMode,
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }

    // Sections keep the screen calm: Home is status and data only, every switch is in Settings,
    // everything troubleshooting-shaped is in Diagnostics. Home is always what opens first.
    val sectionBody: @Composable (Boolean) -> Unit = { wide ->
        when (section) {
            GatewaySection.HOME ->
                if (wide) {
                    // Home is a dashboard, not a list: both columns fill the tablet's height.
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            backgroundCard()
                            statusCard(Modifier.weight(1f), true)
                            linkPromptCard()
                            footer()
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            liveDataCard(Modifier.fillMaxHeight(), true)
                        }
                    }
                } else {
                    backgroundCard()
                    statusCard(Modifier, false)
                    linkPromptCard()
                    liveDataCard(Modifier, false)
                    footer()
                }
            GatewaySection.LINK ->
                if (wide) WideColumn(Modifier.widthIn(max = 680.dp)) { cloudLinkCard() } else cloudLinkCard()
            // On the tablet each of these is two columns so the whole section fits the screen
            // height; on a narrow screen they stack in the one scrolling column.
            GatewaySection.SETTINGS ->
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WideColumn(Modifier.weight(1f)) { cloudSwitchesCard() }
                        WideColumn(Modifier.weight(1f)) { widgetCard() }
                    }
                } else {
                    cloudSwitchesCard()
                    widgetCard()
                }
            GatewaySection.UPDATES ->
                if (wide) WideColumn(Modifier.widthIn(max = 680.dp)) { updatesCard() } else updatesCard()
            GatewaySection.DIAGNOSTICS ->
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WideColumn(Modifier.weight(1f)) {
                            cloudDiagnosticsCard()
                            logCaptureCard()
                        }
                        WideColumn(Modifier.weight(1f)) { advancedCard() }
                    }
                } else {
                    cloudDiagnosticsCard()
                    advancedCard()
                    logCaptureCard()
                }
        }
    }

    // The head unit is a wide, short landscape screen (~960x540 dp): a menu on the left and the
    // chosen section on the right. Narrow windows keep one scrolling column with the menu on top.
    VoltFlowBackground {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            CompositionLocalProvider(LocalGatewayCompact provides true) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) { Header(appVersion = state.appVersion, strings = strings) }
                        LanguageSwitcher(
                            language = state.appLanguage,
                            onLanguageChange = viewModel::updateAppLanguage,
                            modifier = Modifier,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionNav(
                            selected = section,
                            onSelect = { section = it },
                            strings = strings,
                            vertical = true,
                            modifier = Modifier.width(190.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) { sectionBody(true) }
                    }
                }
            }
        } else {
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
                SectionNav(
                    selected = section,
                    onSelect = { section = it },
                    strings = strings,
                    vertical = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                sectionBody(false)
                Gap(8.dp)
            }
        }
    }
    }
}

/**
 * The VoltFlow backdrop: the deep navy of the logo with a soft green glow behind the header and a
 * cool blue one in the opposite corner. Drawn by the screen itself so it fills the whole tablet
 * edge to edge regardless of what the window behind it is.
 */
@Composable
private fun VoltFlowBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDeep, NavyDark)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(AccentGreen.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.06f, size.height * 0.0f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(AccentBlue.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.96f, size.height * 1.0f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
            },
        content = content,
    )
}

private enum class GatewaySection { HOME, LINK, SETTINGS, DIAGNOSTICS, UPDATES }

@Composable
private fun SectionNav(
    selected: GatewaySection,
    onSelect: (GatewaySection) -> Unit,
    strings: GatewayStrings,
    vertical: Boolean,
    modifier: Modifier,
) {
    val items = listOf(
        GatewaySection.HOME to strings.navHome,
        GatewaySection.LINK to strings.navLink,
        GatewaySection.SETTINGS to strings.navSettings,
        GatewaySection.DIAGNOSTICS to strings.diagnostics,
        GatewaySection.UPDATES to strings.updates,
    )
    val button: @Composable (GatewaySection, String, Modifier) -> Unit = { target, label, mod ->
        val active = target == selected
        Button(
            onClick = { onSelect(target) },
            modifier = mod,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) AccentGreen else CardSurfaceElevated,
                contentColor = if (active) NavyDark else TextPrimary,
            ),
        ) {
            Text(label, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
        }
    }
    if (vertical) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (target, label) -> button(target, label, Modifier.fillMaxWidth().height(52.dp)) }
        }
    } else {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { (target, label) -> button(target, label, Modifier) }
        }
    }
}

/** Below this width the screen falls back to the single scrolling column. */
private val WIDE_LAYOUT_MIN_WIDTH = 720.dp

/** True while cards are laid out in the dense multi-column head-unit layout. */
private val LocalGatewayCompact = compositionLocalOf { false }

/**
 * Vertical gap inside a card. Cards already space children with `spacedBy`, so in the
 * dense layout the extra Spacer is dropped instead of doubling every gap.
 */
@Composable
private fun ColumnScope.Gap(height: Dp) {
    if (!LocalGatewayCompact.current) Spacer(modifier = Modifier.height(height))
}

@Composable
private fun WideColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun LanguageSwitcher(
    language: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Row(
        modifier = modifier,
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
private fun FloatingWidgetCard(
    enabled: Boolean,
    needsPermission: Boolean,
    scale: Float,
    onEnabledChange: (Boolean) -> Unit,
    onScaleChange: (Float) -> Unit,
    strings: GatewayStrings,
) {
    val context = LocalContext.current
    // Live preview: while this card is on screen and the widget is enabled, pop the
    // overlay over Settings so dragging the slider shows the resize immediately —
    // see WidgetController.setPreviewMode. Stops on nav-away (Compose disposes this
    // card when `section` switches away from SETTINGS).
    DisposableEffect(enabled) {
        if (enabled) com.bydmate.app.ui.widget.WidgetController.setPreviewMode(context, true)
        onDispose { com.bydmate.app.ui.widget.WidgetController.setPreviewMode(context, false) }
    }
    GatewayCard {
        Text(strings.widgetTitle, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Gap(10.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(strings.widgetToggle, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(strings.widgetHint, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = bydSwitchColors(),
            )
        }
        if (needsPermission) {
            Gap(8.dp)
            Text(strings.widgetNeedsPermission, color = AccentOrange, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (enabled) {
            Gap(14.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(strings.widgetScaleLabel, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "${(scale * 100).toInt()}%",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = scale,
                onValueChange = onScaleChange,
                valueRange = com.bydmate.app.ui.widget.WidgetPreferences.SCALE_MIN..
                    com.bydmate.app.ui.widget.WidgetPreferences.SCALE_MAX,
                colors = SliderDefaults.colors(
                    thumbColor = AccentGreen,
                    activeTrackColor = AccentGreen,
                ),
            )
        }
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
        Gap(10.dp)
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
        Gap(10.dp)
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
private fun LogCaptureCard(strings: GatewayStrings) {
    val context = LocalContext.current
    val isRecording by LogRecorder.isRecording.collectAsStateWithLifecycle()
    // hasLog is a file check, not reactive; it is re-read on every recomposition,
    // and isRecording flipping (start/stop) always triggers one, so the Save
    // button enables as soon as a stopped session leaves a file behind.
    val hasLog = isRecording || LogRecorder.hasLog(context)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                LogRecorder.writeAllTo(context, out)
            } ?: error("no output stream")
        }.isSuccess
        Toast.makeText(
            context,
            if (ok) strings.logSaved else strings.logSaveFailed,
            Toast.LENGTH_LONG,
        ).show()
    }

    GatewayCard {
        Text(strings.logCaptureTitle, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(strings.logCaptureSubtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        if (isRecording) {
            Text(strings.logRecording, color = AccentOrange, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        val compact = LocalGatewayCompact.current
        val buttonModifier = if (compact) Modifier.weight(1f) else Modifier.fillMaxWidth()
        val buttons: @Composable () -> Unit = {
        Button(
            onClick = {
                if (isRecording) {
                    LogRecorder.stop()
                } else if (!LogRecorder.start(context)) {
                    Toast.makeText(context, strings.logStartFailed, Toast.LENGTH_LONG).show()
                }
            },
            modifier = buttonModifier,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) AccentOrange else AccentGreen,
                contentColor = NavyDark,
            ),
        ) {
            Text(if (isRecording) strings.logStop else strings.logStart, fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = {
                val name = "vfm-log-${System.currentTimeMillis()}.txt"
                // Prefer the system file picker (SAF) where it exists. On the BYD head
                // unit DocumentsUI is gutted and CREATE_DOCUMENT resolves to nothing, so
                // launch() throws ActivityNotFoundException — fall back to Downloads.
                val launched = runCatching { saveLauncher.launch(name) }.isSuccess
                if (!launched) {
                    val path = LogRecorder.exportToDownloads(context, name)
                    Toast.makeText(
                        context,
                        if (path != null) "${strings.logSaved} $path" else strings.logSaveFailed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            enabled = hasLog,
            modifier = buttonModifier,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CardSurfaceElevated,
                contentColor = TextPrimary,
                disabledContainerColor = CardSurfaceElevated,
                disabledContentColor = TextMuted,
            ),
        ) {
            Text(strings.logSave, fontWeight = FontWeight.Medium)
        }
        }
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { buttons() }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
        }
    }
}

@Composable
private fun Header(appVersion: String, strings: GatewayStrings) {
    val compact = LocalGatewayCompact.current
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.voltflow_cloud_release),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 32.dp else 40.dp),
            )
            Text(
                "VoltFlow Mate",
                color = TextPrimary,
                fontSize = if (compact) 22.sp else 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "${strings.bridge} • v$appVersion",
            color = TextSecondary,
            fontSize = if (compact) 12.sp else 14.sp,
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
    daemonStatus: DaemonStatus,
    onInstallDaemon: () -> Unit,
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
        Gap(6.dp)
        val daemonStatusText = when (daemonStatus) {
            DaemonStatus.RUNNING -> strings.daemonStatusRunning
            DaemonStatus.RUNNING_NO_WATCHDOG -> strings.daemonStatusPartial
            DaemonStatus.CHECKING, DaemonStatus.INSTALLING -> strings.daemonStatusChecking
            else -> strings.daemonStatusNotRunning
        }
        StatusRow(strings.daemonStatusLabel, daemonStatusText, daemonStatus == DaemonStatus.RUNNING)
        Button(
            onClick = onInstallDaemon,
            enabled = daemonStatus != DaemonStatus.INSTALLING && daemonStatus != DaemonStatus.CHECKING,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
        ) {
            Text(
                if (daemonStatus == DaemonStatus.INSTALLING) strings.daemonInstalling else strings.daemonInstallAction,
                fontWeight = FontWeight.Bold,
            )
        }
        if (daemonStatus == DaemonStatus.INSTALL_FAILED) {
            Text(strings.daemonInstallFailedHint, color = AccentOrange, fontSize = 12.sp)
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
    cloudSyncStatusIsError: Boolean,
    lastSyncTs: Long,
    lastSyncOk: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    strings: GatewayStrings,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
) {
    GatewayCard(modifier, stretch) {
        Text(strings.gatewayStatus, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Gap(10.dp)
        StatusRow(strings.service, if (isRunning) strings.running else strings.stopped, isRunning)
        StatusRow("DiPlus", if (diPlusConnected) strings.connected else strings.waiting, diPlusConnected)
        cloudSyncStatus?.let {
            Gap(6.dp)
            Text(it, color = if (cloudSyncStatusIsError) AccentOrange else TextSecondary, fontSize = 12.sp)
        }
        if (lastSyncTs > 0L) {
            // B-2: live "last synced N ago" indicator. Ticks once a second while the
            // screen is open; language-neutral (glyph + s/m/h + ✓/✗) to avoid the
            // inline-i18n block. Orange when the last attempt failed.
            var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(lastSyncTs) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1000)
                }
            }
            val ageSec = ((nowMs - lastSyncTs) / 1000).coerceAtLeast(0)
            val ageText = when {
                ageSec < 60 -> "${ageSec}s"
                ageSec < 3600 -> "${ageSec / 60}m"
                else -> "${ageSec / 3600}h"
            }
            Gap(4.dp)
            Text(
                "⟳ $ageText ${if (lastSyncOk) "✓" else "✗"}",
                color = if (lastSyncOk) TextSecondary else AccentOrange,
                fontSize = 12.sp,
            )
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f).heightIn(min = if (stretch) 60.dp else 40.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text(strings.start, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f).heightIn(min = if (stretch) 60.dp else 40.dp),
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
    chargingPowerKw: Double?,
    chargingTimeToFullMin: Double?,
    hasLocation: Boolean,
    lastUpdateMs: Long,
    strings: GatewayStrings,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
) {
    GatewayCard(modifier, stretch) {
        Text(strings.latestData, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (lastUpdateMs > 0L) {
            // B-1: D+ data freshness. Ticks up while D+ is quiet; past the stale
            // threshold the values below are likely frozen, so the badge turns orange.
            // Reflects fetch success (not value change), so a parked static car is fine.
            var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(lastUpdateMs) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1000)
                }
            }
            val ageSec = ((nowMs - lastUpdateMs) / 1000).coerceAtLeast(0)
            val stale = ageSec >= 10
            val ageText = when {
                ageSec < 60 -> "${ageSec}s"
                ageSec < 3600 -> "${ageSec / 60}m"
                else -> "${ageSec / 3600}h"
            }
            Gap(4.dp)
            Text(
                "⟳ $ageText" + if (stale) " ⚠" else "",
                color = if (stale) AccentOrange else TextSecondary,
                fontSize = 12.sp,
            )
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("SOC", fmt(soc?.toDouble(), 0, "%"), Modifier.weight(1f))
            Metric(strings.speed, fmt(speed?.toDouble(), 0, " km/h"), Modifier.weight(1f))
            Metric(strings.power, fmt(power, 1, " kW"), Modifier.weight(1f))
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric(strings.range, fmt(rangeKm, 0, " km"), Modifier.weight(1f))
            Metric(strings.trip, fmt(tripDistanceKm, 1, " km"), Modifier.weight(1f))
            Metric("12V", fmt(auxVoltage, 1, " V"), Modifier.weight(1f))
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric(strings.battery, fmtTemp(batteryTemp), Modifier.weight(1f))
            // DiLink 3.0 (2024 cars) has no cabin temperature sensor — hide rather than show "—".
            if (com.bydmate.app.util.HeadUnitModel.hasCabinTemp()) {
                Metric(strings.cabin, fmtTemp(cabinTemp), Modifier.weight(1f))
            }
            Metric(strings.outside, fmtTemp(outsideTemp), Modifier.weight(1f))
        }
        // Only while actively charging — chargingPowerKw is null the rest of the time
        // (see TrackingService.updateChargingPower), so this row would otherwise show
        // a permanent pair of dashes.
        if (chargingPowerKw != null) {
            Gap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Metric(strings.chargingPower, fmt(chargingPowerKw, 1, " kW"), Modifier.weight(1f))
                Metric(strings.timeToFull, fmtEtaMinutes(chargingTimeToFullMin), Modifier.weight(1f))
            }
        }
        Gap(8.dp)
        StatusRow(strings.odometer, fmt(odometer, 1, " km"), odometer != null)
        StatusRow("GPS", if (hasLocation) strings.available else strings.noPermissionData, hasLocation)
    }
}

/** Linking only: the code, the car's name, Connect and Save. Every switch lives in Settings. */
@Composable
private fun CloudLinkCard(
    linkCode: String,
    linking: Boolean,
    vehicleId: String,
    status: String?,
    statusIsError: Boolean,
    onLinkCode: (String) -> Unit,
    onConnect: () -> Unit,
    onVehicleId: (String) -> Unit,
    onSave: () -> Unit,
    strings: GatewayStrings,
) {
    GatewayCard {
        Text(strings.voltFlowSync, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Gap(10.dp)
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
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary),
        ) {
            Text(strings.save, fontWeight = FontWeight.Medium)
        }
        status?.let {
            Gap(8.dp)
            Text(it, color = if (statusIsError) AccentOrange else AccentGreen, fontSize = 12.sp)
        }
    }
}

/** Every cloud-sync switch in one card; each one persists the moment it is flipped. */
@Composable
private fun CloudSwitchesCard(
    enabled: Boolean,
    wifiOnly: Boolean,
    omitGps: Boolean,
    keepWifiAwake: Boolean,
    socFromCar: Boolean,
    onEnabled: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onOmitGps: (Boolean) -> Unit,
    onKeepWifiAwake: (Boolean) -> Unit,
    onSocFromCar: (Boolean) -> Unit,
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
        Gap(10.dp)
        SwitchRow(strings.wifiOnly, null, wifiOnly, onWifiOnly)
        SwitchRow(strings.gpsPrivacy, null, omitGps, onOmitGps)
        SwitchRow(strings.keepWifiAwake, strings.keepWifiAwakeHint, keepWifiAwake, onKeepWifiAwake)
        SwitchRow(strings.socFromCar, strings.socFromCarHint, socFromCar, onSocFromCar)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            if (hint != null) Text(hint, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = bydSwitchColors())
    }
}

/** Everything a user only needs when something is wrong; shown only after "Диагностика" is pressed. */
@Composable
private fun CloudDiagnosticsCard(
    url: String,
    apiKey: String,
    vehicleId: String,
    status: String?,
    statusIsError: Boolean,
    onUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onTest: () -> Unit,
    diagnosticLog: String?,
    onDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
    strings: GatewayStrings,
) {
    GatewayCard {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onTest,
                enabled = vehicleId.trim().isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary),
            ) {
                Text(strings.sendTest)
            }
            // Storage diagnostics: works without ADB (plain File API). Lets remote
            // users report whether their DiLink writes the BYD energydata database.
            Button(
                onClick = onDiagnostics,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary),
            ) {
                Text(strings.storageDiag)
            }
        }
        if (diagnosticLog != null) {
            val clipboard = LocalClipboardManager.current
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
        // The sync card may be collapsed, so a test result has to be visible right here.
        status?.let {
            Text(it, color = if (statusIsError) AccentOrange else AccentGreen, fontSize = 12.sp)
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
private fun GatewayCard(
    modifier: Modifier = Modifier,
    /** Fill the height the caller gave the card, spreading the rows out instead of piling them at the top. */
    stretch: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalGatewayCompact.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth().then(modifier),
    ) {
        Column(
            modifier = Modifier
                .padding(if (compact) 12.dp else 14.dp)
                .then(if (stretch) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = if (stretch) Arrangement.SpaceEvenly else Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    val size = if (LocalGatewayCompact.current) 15.sp else 13.sp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = size)
        Text(value, color = if (ok) AccentGreen else AccentOrange, fontSize = size, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    // Big numbers on the tablet layout: this is the part read from the driver's seat.
    val dense = LocalGatewayCompact.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = TextSecondary, fontSize = if (dense) 13.sp else 11.sp)
        Text(value, color = TextPrimary, fontSize = if (dense) 26.sp else 17.sp, fontWeight = FontWeight.Bold)
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
    val chargingPower: String,
    val timeToFull: String,
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
    val socFromCar: String,
    val socFromCarHint: String,
    val diagnostics: String,
    val navHome: String,
    val navLink: String,
    val navSettings: String,
    val notLinked: String,
    val linkCar: String,
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
    val daemonStatusLabel: String,
    val daemonStatusRunning: String,
    val daemonStatusPartial: String,
    val daemonStatusNotRunning: String,
    val daemonStatusChecking: String,
    val daemonInstallAction: String,
    val daemonInstalling: String,
    val daemonInstallFailedHint: String,
    val parkedNetworkHint: String,
    val parkedNetworkAction: String,
    val logCaptureTitle: String,
    val logCaptureSubtitle: String,
    val logRecording: String,
    val logStart: String,
    val logStop: String,
    val logSave: String,
    val logSaved: String,
    val logSaveFailed: String,
    val logStartFailed: String,
    val widgetTitle: String,
    val widgetToggle: String,
    val widgetHint: String,
    val widgetNeedsPermission: String,
    val widgetScaleLabel: String,
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
            chargingPower = "Мощность зарядки",
            timeToFull = "До полной зарядки",
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
            socFromCar = "SOC от машины, а не от Di+",
            socFromCarHint = "Выкл — SOC от Di+ (точнее, шаг 0,1 %). Вкл — как на приборной панели, целые %. Второй источник — запасной.",
            diagnostics = "Диагностика",
            navHome = "Главная",
            navLink = "Привязка",
            navSettings = "Настройки",
            notLinked = "Авто не привязано к VoltFlow",
            linkCar = "Привязать авто",
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
            daemonStatusLabel = "Демон восстановления",
            daemonStatusRunning = "Работает",
            daemonStatusPartial = "Работает без watchdog",
            daemonStatusNotRunning = "Не запущен",
            daemonStatusChecking = "Проверка…",
            daemonInstallAction = "Установить / запустить демон",
            daemonInstalling = "Установка…",
            daemonInstallFailedHint = "Не удалось запустить демон. Проверьте on-device ADB выше.",
            parkedNetworkHint = "Чтобы данные шли при выключенной машине, включите «Keep network on while parked» — Wi-Fi не отключится на стоянке.",
            parkedNetworkAction = "Сеть на стоянке",
            logCaptureTitle = "Журнал диагностики",
            logCaptureSubtitle = "Запишите лог приложения для отправки разработчику. Работает без ADB и компьютера.",
            logRecording = "● Идёт запись…",
            logStart = "Начать запись лога",
            logStop = "Остановить запись",
            logSave = "Сохранить лог в файл…",
            logSaved = "Лог сохранён.",
            logSaveFailed = "Не удалось сохранить лог.",
            logStartFailed = "Не удалось запустить запись лога на этом устройстве.",
            widgetTitle = "Плавающий виджет",
            widgetToggle = "Показывать поверх других приложений",
            widgetHint = "AI запас хода, AI расход с трендом, температуры, 12V и связь с облаком. Появляется, когда VoltFlow Mate свёрнут.",
            widgetNeedsPermission = "Нет разрешения на показ поверх окон. Подключите ADB в «Расширенных функциях» и включите виджет ещё раз.",
            widgetScaleLabel = "Размер виджета",
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
            chargingPower = "Charging power",
            timeToFull = "Time to full",
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
            socFromCar = "Use the car's SOC instead of Di+",
            socFromCarHint = "Off: Di+ SOC (finer, 0.1 % steps). On: the instrument-cluster SOC, whole %. The other source is the fallback.",
            diagnostics = "Diagnostics",
            navHome = "Home",
            navLink = "Link",
            navSettings = "Settings",
            notLinked = "This car is not linked to VoltFlow",
            linkCar = "Link this car",
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
            daemonStatusLabel = "Survival daemon",
            daemonStatusRunning = "Running",
            daemonStatusPartial = "Running, no watchdog",
            daemonStatusNotRunning = "Not running",
            daemonStatusChecking = "Checking…",
            daemonInstallAction = "Install / run daemon",
            daemonInstalling = "Installing…",
            daemonInstallFailedHint = "Could not start the daemon. Check on-device ADB above.",
            parkedNetworkHint = "For data while the car is off, enable “Keep network on while parked” so Wi-Fi stays up after parking.",
            parkedNetworkAction = "Network while parked",
            logCaptureTitle = "Diagnostic log",
            logCaptureSubtitle = "Record the app log to share with the developer. Works with no ADB and no computer.",
            logRecording = "● Recording…",
            logStart = "Start log recording",
            logStop = "Stop recording",
            logSave = "Save log to file…",
            logSaved = "Log saved.",
            logSaveFailed = "Could not save the log.",
            logStartFailed = "Could not start log recording on this device.",
            widgetTitle = "Floating widget",
            widgetToggle = "Show over other apps",
            widgetHint = "AI range, AI consumption with trend, temperatures, 12V and cloud link. Appears while VoltFlow Mate is in the background.",
            widgetNeedsPermission = "No permission to draw over other apps. Connect ADB under Advanced features, then turn the widget on again.",
            widgetScaleLabel = "Widget size",
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
            chargingPower = "Магутнасць зарадкі",
            timeToFull = "Да поўнай зарадкі",
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
            socFromCar = "SOC ад машыны, а не ад Di+",
            socFromCarHint = "Выкл — SOC ад Di+ (дакладней, крок 0,1 %). Укл — як на прыборнай панэлі, цэлыя %. Другая крыніца — запасная.",
            diagnostics = "Дыягностыка",
            navHome = "Галоўная",
            navLink = "Прывязка",
            navSettings = "Налады",
            notLinked = "Аўто не прывязана да VoltFlow",
            linkCar = "Прывязаць аўто",
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
            daemonStatusLabel = "Дэман выжывання",
            daemonStatusRunning = "Працуе",
            daemonStatusPartial = "Працуе без watchdog",
            daemonStatusNotRunning = "Не запушчаны",
            daemonStatusChecking = "Праверка…",
            daemonInstallAction = "Усталяваць / запусціць дэман",
            daemonInstalling = "Усталёўка…",
            daemonInstallFailedHint = "Не ўдалося запусціць дэман. Праверце on-device ADB вышэй.",
            parkedNetworkHint = "Каб даныя ішлі пры выключанай машыне, уключыце «Keep network on while parked» — Wi-Fi не адключыцца на стаянцы.",
            parkedNetworkAction = "Сетка на стаянцы",
            logCaptureTitle = "Журнал дыягностыкі",
            logCaptureSubtitle = "Запішыце лог праграмы, каб адправіць распрацоўніку. Працуе без ADB і камп'ютара.",
            logRecording = "● Ідзе запіс…",
            logStart = "Пачаць запіс лога",
            logStop = "Спыніць запіс",
            logSave = "Захаваць лог у файл…",
            logSaved = "Лог захаваны.",
            logSaveFailed = "Не ўдалося захаваць лог.",
            logStartFailed = "Не ўдалося запусціць запіс лога на гэтай прыладзе.",
            widgetTitle = "Плавальны віджэт",
            widgetToggle = "Паказваць паверх іншых праграм",
            widgetHint = "AI запас ходу, AI расход з трэндам, тэмпературы, 12V і сувязь з воблакам. З'яўляецца, калі VoltFlow Mate згорнуты.",
            widgetNeedsPermission = "Няма дазволу паказваць паверх вокнаў. Падключыце ADB у «Пашыраных функцыях» і ўключыце віджэт яшчэ раз.",
            widgetScaleLabel = "Памер віджэта",
        )
    }

private fun fmt(value: Double?, digits: Int, suffix: String): String =
    if (value != null && value.isFinite()) "%.${digits}f%s".format(value, suffix) else "--"

private fun fmtTemp(value: Int?): String =
    if (value != null && value in -50..90) "$value °C" else "--"

private fun fmtEtaMinutes(value: Double?): String {
    if (value == null || !value.isFinite() || value < 0.0) return "--"
    val totalMin = (value + 0.5).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
