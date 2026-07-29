package com.bydmate.app.ui.components

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.bydmate.app.ui.theme.*

// -- Helper functions --

fun socColor(soc: Int): Color = when {
    soc > 50 -> SocGreen
    soc >= 20 -> SocYellow
    else -> SocRed
}

data class ConsumptionThresholds(val good: Double, val bad: Double) {
    companion object {
        val Default = ConsumptionThresholds(good = 20.0, bad = 30.0)
    }
}

// Provided by MainActivity / WidgetController from SettingsRepository so user-edited
// thresholds in Settings actually recolor every screen and the floating widget.
val LocalConsumptionThresholds = compositionLocalOf { ConsumptionThresholds.Default }

// Единый стиль Switch по всему приложению:
// включён — зелёный track + тёмный thumb, выключен — серый track + тёмный thumb.
@Composable
fun bydSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = NavyMid,
    checkedTrackColor = AccentGreen,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = NavyMid,
    uncheckedTrackColor = TextMuted,
    uncheckedBorderColor = Color.Transparent,
)
