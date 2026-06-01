package com.bydmate.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.ui.gateway.GatewayScreen
import com.bydmate.app.ui.settings.UpdateDialog
import com.bydmate.app.ui.settings.UpdateState
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
