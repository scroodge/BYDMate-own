package com.bydmate.app.ui.navigation

import androidx.compose.runtime.Composable
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.ui.gateway.GatewayScreen

@Composable
fun AppNavigation(
    @Suppress("UNUSED_PARAMETER") settingsRepository: SettingsRepository,
    @Suppress("UNUSED_PARAMETER") updateChecker: UpdateChecker
) {
    GatewayScreen()
}
