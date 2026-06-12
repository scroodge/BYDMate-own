package com.bydmate.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.ui.gateway.GatewayScreen
import com.bydmate.app.ui.settings.UpdateDialog
import com.bydmate.app.ui.settings.UpdateState
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    @Suppress("UNUSED_PARAMETER") settingsRepository: SettingsRepository,
    updateChecker: UpdateChecker,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var autoUpdateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var updateDialogState by remember { mutableStateOf<UpdateState?>(null) }
    var autoCheckEnabled by remember {
        mutableStateOf(UpdateChecker.isAutoCheckEnabled(context))
    }

    fun runManualUpdateCheck() {
        scope.launch {
            updateDialogState = UpdateState.Checking
            try {
                val info = updateChecker.checkForUpdate(context, forceCheck = true)
                if (info != null) {
                    autoUpdateInfo = info
                    updateDialogState = UpdateState.Available(
                        version = info.version,
                        notes = info.releaseNotes,
                    )
                } else {
                    autoUpdateInfo = null
                    updateDialogState = UpdateState.UpToDate
                }
            } catch (e: Exception) {
                autoUpdateInfo = null
                updateDialogState = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    val currentAppVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
    var showPostInstallReminder by remember {
        mutableStateOf(UpdateChecker.getLastSeenVersion(context) != currentAppVersion)
    }
    if (showPostInstallReminder) {
        PostInstallReminderDialog(
            version = currentAppVersion,
            onDismiss = {
                UpdateChecker.setLastSeenVersion(context, currentAppVersion)
                showPostInstallReminder = false
            }
        )
    }

    LaunchedEffect(Unit) {
        if (!UpdateChecker.isAutoCheckEnabled(context)) return@LaunchedEffect
        try {
            val info = updateChecker.checkForUpdate(context, forceCheck = false)
            if (info != null) {
                autoUpdateInfo = info
                updateDialogState = UpdateState.Available(
                    version = info.version,
                    notes = info.releaseNotes,
                )
            }
        } catch (_: Exception) {
            // offline, rate-limit, etc.
        }
    }

    updateDialogState?.let { dialogState ->
        val currentVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")

        UpdateDialog(
            currentVersion = currentVersion,
            state = dialogState,
            onCheck = {
                when (dialogState) {
                    is UpdateState.Available -> {
                        val info = autoUpdateInfo ?: return@UpdateDialog
                        updateDialogState = UpdateState.Downloading(info.version, "Скачивание: 0%")
                        scope.launch {
                            try {
                                updateChecker.downloadAndInstall(context, info) { progress ->
                                    updateDialogState = UpdateState.Downloading(info.version, progress)
                                }
                            } catch (e: Exception) {
                                updateDialogState = UpdateState.Error(e.message ?: "Download failed")
                            }
                        }
                    }
                    else -> runManualUpdateCheck()
                }
            },
            onDismiss = {
                updateDialogState = null
                autoUpdateInfo = null
            },
        )
    }

    GatewayScreen(
        autoCheckUpdates = autoCheckEnabled,
        onAutoCheckUpdatesChange = { enabled ->
            UpdateChecker.setAutoCheckEnabled(context, enabled)
            autoCheckEnabled = enabled
        },
        onCheckUpdatesNow = { runManualUpdateCheck() },
    )
}

@Composable
private fun PostInstallReminderDialog(version: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .clickable { /* absorb clicks so backdrop dismiss doesn't fire */ }
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.nav_autostart_dialog_title, version),
                        color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.nav_autostart_dialog_body),
                        color = TextSecondary, fontSize = 14.sp
                    )
                    Button(
                        onClick = {
                            val opened = runCatching {
                                val intent = Intent(Intent.ACTION_MAIN).apply {
                                    setClassName(
                                        "com.byd.appstartmanagement",
                                        "com.byd.appstartmanagement.frame.AppStartManagement"
                                    )
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.isSuccess
                            if (!opened) {
                                runCatching {
                                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallback)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.nav_autostart_open_settings_error),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text(
                            stringResource(R.string.nav_autostart_dialog_button),
                            color = Color.Black, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
