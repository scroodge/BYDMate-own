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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.settings.SettingsViewModel
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
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isRunning by TrackingService.isRunning.collectAsStateWithLifecycle()
    val diPlusConnected by TrackingService.diPlusConnected.collectAsStateWithLifecycle()
    val data by TrackingService.lastData.collectAsStateWithLifecycle()
    val rangeKm by TrackingService.lastRangeKm.collectAsStateWithLifecycle()
    val tripDistanceKm by TrackingService.tripDistanceKm.collectAsStateWithLifecycle()
    val location by TrackingService.lastLocation.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Header(appVersion = state.appVersion)

        StatusCard(
            isRunning = isRunning,
            diPlusConnected = diPlusConnected,
            onStart = { TrackingService.start(context) },
            onStop = { TrackingService.stop(context) }
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
        )

        CloudSyncCard(
            enabled = state.cloudSyncEnabled,
            url = state.cloudSyncUrl,
            apiKey = state.cloudSyncApiKey,
            vehicleId = state.cloudSyncVehicleId,
            intervalSec = state.cloudSyncIntervalSec,
            wifiOnly = state.cloudSyncWifiOnly,
            status = state.cloudSyncStatus,
            onEnabled = viewModel::toggleCloudSync,
            onUrl = viewModel::updateCloudSyncUrl,
            onApiKey = viewModel::updateCloudSyncApiKey,
            onVehicleId = viewModel::updateCloudSyncVehicleId,
            onInterval = viewModel::updateCloudSyncIntervalSec,
            onWifiOnly = viewModel::toggleCloudSyncWifiOnly,
            onSave = viewModel::saveCloudSyncSettings,
            onTest = viewModel::sendCloudTestPayload,
        )

        Text(
            "Gateway mode: приложение читает live-данные DiPlus/BYD и отправляет их в VoltFlow. Поездки, AI, ABRP, автоматизация и локальная аналитика скрыты из интерфейса.",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun Header(appVersion: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "CloudEV Gateway",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "VoltFlow telemetry bridge • v$appVersion",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StatusCard(
    isRunning: Boolean,
    diPlusConnected: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    GatewayCard {
        Text("Gateway status", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        StatusRow("Service", if (isRunning) "Running" else "Stopped", isRunning)
        StatusRow("DiPlus", if (diPlusConnected) "Connected" else "Waiting", diPlusConnected)
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text("Start", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary)
            ) {
                Text("Stop")
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
) {
    GatewayCard {
        Text("Latest vehicle data", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("SOC", fmt(soc?.toDouble(), 0, "%"), Modifier.weight(1f))
            Metric("Speed", fmt(speed?.toDouble(), 0, " km/h"), Modifier.weight(1f))
            Metric("Power", fmt(power, 1, " kW"), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Range", fmt(rangeKm, 0, " km"), Modifier.weight(1f))
            Metric("Trip", fmt(tripDistanceKm, 1, " km"), Modifier.weight(1f))
            Metric("12V", fmt(auxVoltage, 1, " V"), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Battery", fmtTemp(batteryTemp), Modifier.weight(1f))
            Metric("Cabin", fmtTemp(cabinTemp), Modifier.weight(1f))
            Metric("Outside", fmtTemp(outsideTemp), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        StatusRow("Odometer", fmt(odometer, 1, " km"), odometer != null)
        StatusRow("GPS", if (hasLocation) "Available" else "No permission/data", hasLocation)
    }
}

@Composable
private fun CloudSyncCard(
    enabled: Boolean,
    url: String,
    apiKey: String,
    vehicleId: String,
    intervalSec: String,
    wifiOnly: Boolean,
    status: String?,
    onEnabled: (Boolean) -> Unit,
    onUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onVehicleId: (String) -> Unit,
    onInterval: (String) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    GatewayCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("VoltFlow sync", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("POST telemetry to your HTTPS endpoint", color = TextSecondary, fontSize = 12.sp)
            }
            Switch(checked = enabled, onCheckedChange = onEnabled, colors = bydSwitchColors())
        }
        Spacer(modifier = Modifier.height(10.dp))
        GatewayTextField("Endpoint URL", url, onUrl, KeyboardType.Uri)
        GatewayTextField("API Key", apiKey, onApiKey, KeyboardType.Password, password = true)
        GatewayTextField("Vehicle ID", vehicleId, onVehicleId, KeyboardType.Text)
        GatewayTextField("Flush interval seconds", intervalSec, onInterval, KeyboardType.Number)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Wi-Fi only", color = TextPrimary, fontSize = 14.sp)
            Switch(checked = wifiOnly, onCheckedChange = onWifiOnly, colors = bydSwitchColors())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceElevated, contentColor = TextPrimary)
            ) {
                Text("Send test")
            }
        }
        status?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                it,
                color = if (it.contains("failed", ignoreCase = true) || it.contains("ошиб", ignoreCase = true)) AccentOrange else AccentGreen,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun GatewayTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
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

private fun fmt(value: Double?, digits: Int, suffix: String): String =
    if (value != null && value.isFinite()) "%.${digits}f%s".format(value, suffix) else "--"

private fun fmtTemp(value: Int?): String =
    if (value != null && value in -50..90) "$value °C" else "--"
