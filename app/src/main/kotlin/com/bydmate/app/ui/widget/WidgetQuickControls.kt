package com.bydmate.app.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary

/** Commands exposed by the floating widget's quick-controls drawer. */
enum class WidgetQuickControl {
    DRIVER_SEAT_HEAT,
    DRL,
    PASSENGER_SEAT_HEAT,
}

/**
 * A separate overlay directly below the read-only telemetry widget. It must remain separate:
 * the widget itself owns a raw touch listener for drag/long-press, which would consume Compose
 * button gestures if the controls lived in the same view.
 */
@Composable
internal fun WidgetQuickControlsView(
    alpha: Float,
    scaleFactor: Float,
    commandPending: Boolean,
    states: Map<WidgetQuickControl, Boolean?>,
    onToggle: (WidgetQuickControl) -> Unit,
) {
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * scaleFactor,
        fontScale = baseDensity.fontScale,
    )
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        ) {
            Column(
                modifier = Modifier
                    .alpha(alpha.coerceIn(0.3f, 1.0f))
                    .size(width = 260.dp, height = 120.dp)
                    .background(CardSurface, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                androidx.compose.material3.Text(
                    text = "БЫСТРЫЕ НАСТРОЙКИ",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QuickControlColumn(
                        modifier = Modifier.weight(1f),
                        title = "ВОДИТЕЛЬ",
                        subtitle = "Подогрев 2",
                        control = WidgetQuickControl.DRIVER_SEAT_HEAT,
                        enabled = states[WidgetQuickControl.DRIVER_SEAT_HEAT],
                        commandPending = commandPending,
                        onToggle = onToggle,
                    )
                    QuickControlColumn(
                        modifier = Modifier.weight(1f),
                        title = "ДХО",
                        subtitle = "DRL",
                        control = WidgetQuickControl.DRL,
                        enabled = states[WidgetQuickControl.DRL],
                        commandPending = commandPending,
                        onToggle = onToggle,
                    )
                    QuickControlColumn(
                        modifier = Modifier.weight(1f),
                        title = "ПАССАЖИР",
                        subtitle = "Подогрев 2",
                        control = WidgetQuickControl.PASSENGER_SEAT_HEAT,
                        enabled = states[WidgetQuickControl.PASSENGER_SEAT_HEAT],
                        commandPending = commandPending,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickControlColumn(
    modifier: Modifier,
    title: String,
    subtitle: String,
    control: WidgetQuickControl,
    enabled: Boolean?,
    commandPending: Boolean,
    onToggle: (WidgetQuickControl) -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(
            text = title,
            color = TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        androidx.compose.material3.Text(
            text = subtitle,
            color = TextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // One toggle: green = on, grey = off. Unknown (DRL before the first tap, or a failed
            // seat read) shows grey "—" and a tap turns it on.
            QuickControlButton(
                label = when (enabled) { true -> "ВКЛ"; false -> "ВЫКЛ"; null -> "—" },
                color = if (enabled == true) AccentGreen else TextMuted.copy(alpha = 0.45f),
                enabled = !commandPending,
            ) { onToggle(control) }
        }
    }
}

@Composable
private fun QuickControlButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Text(
        text = label,
        color = if (enabled) TextPrimary else TextMuted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
