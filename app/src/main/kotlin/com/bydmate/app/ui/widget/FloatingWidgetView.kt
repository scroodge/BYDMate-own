package com.bydmate.app.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery6Bar
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.data.cloud.CloudLinkClassifier
import com.bydmate.app.data.cloud.CloudLinkEvents
import com.bydmate.app.data.cloud.CloudLinkLevel
import com.bydmate.app.domain.calculator.Trend
import com.bydmate.app.ui.components.socColor as componentsSocColor
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * VoltFlow layout — 260 × 108 dp (WidgetController's drag/trash math assumes it), 3 rows.
 *
 * Row 1 (climate + link): cabin temp (🚗), outside temp (🌡), battery temp (🔋), 12V (⚡),
 *   cloud link indicator. Cabin temp is dropped on DiLink 3.0 (2024 cars have no cabin
 *   sensor) — see HeadUnitModel.
 * Row 2 (main): SOC% as di+ shows it (0.1 % on di+ 2.0, status color) · AI range km (28sp)
 *   · AI consumption + trend.
 *   AI values are the car-side port of the web's AI Range formula (AiRangeEstimator),
 *   smoothed for display by AiRangeMonitor; the trend is AI consumption short vs long EMA.
 * Row 3 (trip): trip duration (⏱), trip distance (route), regenerated energy (♻, TripRegenMeter).
 *
 * Icons are muted gray, values are white in service rows. Km is always white
 * regardless of SOC status (only the border + SOC % + trend text colorize).
 */
@Composable
fun FloatingWidgetView(
    soc: Int?,
    /** di+ 2.0 0.1 %-resolution SOC; null on di+ 1.x, where [soc] is shown instead. */
    socPrecise: Double?,
    aiRangeKm: Double?,
    aiConsumption: Double?,
    aiTrend: Trend,
    sessionStartedAt: Long?,
    tripDistanceKm: Double?,
    regenKwh: Double?,
    insideTemp: Int?,
    outsideTemp: Int?,
    batTemp: Int?,
    voltage12v: Double?,
    cloud: CloudLinkEvents,
    showCabinTemp: Boolean,
    alpha: Float,
    scaleFactor: Float = 1.0f,
) {
    val status = widgetStatus(soc, voltage12v)
    val borderColor = when (status) {
        Status.OK -> AccentGreen
        Status.WARN -> SocYellow
        Status.CRIT -> SocRed
        Status.NO_DATA -> TextMuted.copy(alpha = 0.4f)
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * scaleFactor,
        fontScale = baseDensity.fontScale,
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        Column(
            modifier = Modifier
                .alpha(alpha.coerceIn(0.3f, 1.0f))
                .size(width = 260.dp, height = 108.dp)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp))
                .background(CardSurface, RoundedCornerShape(14.dp))
                .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            RowClimate(
                insideTemp = insideTemp,
                showCabinTemp = showCabinTemp,
                outsideTemp = outsideTemp,
                batTemp = batTemp,
                voltage12v = voltage12v,
                cloud = cloud,
            )
            WidgetDivider()
            // No distance gate here (unlike the old odometer trend): AiRangeSmoother keeps
            // the trend at NONE until two minutes of actual driving have fed it.
            RowEnergy(
                soc = soc,
                socPrecise = socPrecise,
                rangeKm = aiRangeKm,
                consumption = aiConsumption,
                trend = aiTrend,
            )
            WidgetDivider()
            RowTrip(sessionStartedAt = sessionStartedAt, tripDistanceKm = tripDistanceKm, regenKwh = regenKwh)
        }
    }
}

@Composable
private fun RowEnergy(
    soc: Int?,
    socPrecise: Double?,
    rangeKm: Double?,
    consumption: Double?,
    trend: Trend,
) {
    val trendColor = when (trend) {
        Trend.DOWN -> AccentGreen
        Trend.UP -> SocYellow
        Trend.FLAT -> TextPrimary
        Trend.NONE -> TextMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatSoc(soc, socPrecise),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = socColor(soc),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "AI",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = rangeKm?.let { "%.0f".format(it) } ?: "—",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "км",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trend != Trend.NONE) {
                Icon(
                    imageVector = when (trend) {
                        Trend.DOWN -> Icons.Outlined.TrendingDown
                        Trend.UP -> Icons.Outlined.TrendingUp
                        else -> Icons.Outlined.TrendingFlat
                    },
                    contentDescription = null,
                    tint = trendColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = consumption?.let { "%.1f".format(it) } ?: "—",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (trend == Trend.NONE) TextMuted else trendColor,
            )
        }
    }
}

@Composable
private fun RowClimate(
    insideTemp: Int?,
    showCabinTemp: Boolean,
    outsideTemp: Int?,
    batTemp: Int?,
    voltage12v: Double?,
    cloud: CloudLinkEvents,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCabinTemp) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                IconText(icon = Icons.Outlined.DirectionsCar, text = formatTemp(insideTemp))
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconText(icon = Icons.Outlined.Thermostat, text = formatTemp(outsideTemp))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconText(icon = Icons.Outlined.Battery6Bar, text = formatTemp(batTemp))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconText(icon = Icons.Outlined.Bolt, text = voltage12v?.let { "%.1f".format(it) } ?: "—")
        }
        CloudLinkIndicator(cloud)
    }
}

/**
 * Passive — no tap target (the left third already opens the navigator). Re-classifies
 * every 30 s so the time-based levels (silence, prolonged outage) age without a new event.
 */
@Composable
private fun CloudLinkIndicator(cloud: CloudLinkEvents) {
    val view by produceState(CloudLinkClassifier.classify(cloud, System.currentTimeMillis()), cloud) {
        while (true) {
            value = CloudLinkClassifier.classify(cloud, System.currentTimeMillis())
            delay(30_000L)
        }
    }
    val (icon, tint) = when (view.level) {
        CloudLinkLevel.DISABLED -> return
        CloudLinkLevel.UNKNOWN -> Icons.Outlined.Cloud to TextMuted
        CloudLinkLevel.OK -> Icons.Outlined.CloudDone to AccentGreen
        CloudLinkLevel.NO_DOWNLINK -> Icons.Outlined.Cloud to SocYellow
        CloudLinkLevel.QUEUED -> Icons.Outlined.CloudUpload to SocYellow
        CloudLinkLevel.ERROR -> Icons.Outlined.CloudOff to SocRed
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val queued = view.queued
        if (view.level in setOf(CloudLinkLevel.QUEUED, CloudLinkLevel.ERROR) && queued != null && queued > 0) {
            Text(
                text = formatQueued(queued),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = tint,
            )
            Spacer(Modifier.width(3.dp))
        }
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun RowTrip(
    sessionStartedAt: Long?,
    tripDistanceKm: Double?,
    regenKwh: Double?,
) {
    val durationText by produceState(initialValue = formatDurationShort(sessionStartedAt), sessionStartedAt) {
        while (true) {
            value = formatDurationShort(sessionStartedAt)
            delay(15_000L)
        }
    }
    // Three equal slots so long labels ("1ч 25м", "287 км", "1.4 кВт·ч") never collide.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconText(icon = Icons.Outlined.Schedule, text = durationText)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconText(icon = Icons.Outlined.Route, text = formatTripKm(tripDistanceKm))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconText(icon = Icons.Outlined.Recycling, text = formatRegenKwh(regenKwh), unit = "кВт·ч")
        }
    }
}

/** Icon muted gray, value white 13sp, optional unit muted 10sp so it fits a 1/3 slot. */
@Composable
private fun IconText(icon: ImageVector, text: String, unit: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
        )
        if (unit != null) {
            Spacer(Modifier.width(2.dp))
            Text(text = unit, fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
private fun WidgetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TextMuted.copy(alpha = 0.22f)),
    )
}

internal fun formatDurationShort(sessionStartedAt: Long?): String {
    if (sessionStartedAt == null) return "—"
    val elapsed = System.currentTimeMillis() - sessionStartedAt
    val totalMin = (elapsed / 60_000L).toInt().coerceAtLeast(0)
    val hours = totalMin / 60
    val minutes = totalMin % 60
    // Compact form in hours mode ("1ч 25м") keeps label narrow enough to fit in
    // the top row's 1/3-width slot alongside trip distance and cabin temp.
    return if (hours > 0) "${hours}ч ${minutes}м" else "$minutes мин"
}

/** di+ 2.0 sends SOC at 0.1 % — show it the way di+'s own screen does (66.6 %, not 67 %). */
internal fun formatSoc(soc: Int?, socPrecise: Double?): String = when {
    socPrecise != null && socPrecise.isFinite() -> String.format(java.util.Locale.US, "%.1f", socPrecise) + "%"
    soc != null -> "$soc%"
    else -> "—"
}

internal fun formatRegenKwh(kwh: Double?): String =
    kwh?.takeIf { it.isFinite() }?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"

internal fun formatTemp(temp: Int?): String = temp?.let { if (it > 0) "+$it°" else "$it°" } ?: "—"

/** Queue depth in 4 chars max: 124, 1.2k, 12k. */
internal fun formatQueued(count: Int): String = when {
    count < 1000 -> count.toString()
    count < 10_000 -> "%.1fk".format(count / 1000.0)
    else -> "${count / 1000}k"
}

internal fun formatTripKm(km: Double?): String {
    if (km == null || km.isNaN() || km.isInfinite() || km < 0.0) return "—"
    return "%.1f км".format(km)
}

// ---- Status model (covered by WidgetStatusTest) ----

internal enum class Status { OK, WARN, CRIT, NO_DATA }

internal fun widgetStatus(soc: Int?, v12: Double?): Status {
    if (soc == null && v12 == null) return Status.NO_DATA
    val socStatus = when {
        soc == null -> Status.NO_DATA
        soc < 15 -> Status.CRIT
        soc < 30 -> Status.WARN
        else -> Status.OK
    }
    val vStatus = when {
        v12 == null -> Status.OK
        v12 < 12.0 -> Status.CRIT
        v12 < 12.5 -> Status.WARN
        else -> Status.OK
    }
    return listOf(socStatus, vStatus)
        .filter { it != Status.NO_DATA }
        .maxByOrNull { severity(it) }
        ?: Status.NO_DATA
}

private fun severity(s: Status): Int = when (s) {
    Status.NO_DATA -> -1
    Status.OK -> 0
    Status.WARN -> 1
    Status.CRIT -> 2
}

// Mirrors DashboardScreen's SOC ring coloring (>50 green, >=20 yellow, <20 red).
// Was a private widget-local function with different thresholds (>=30 green,
// >=15 yellow) — split coloring between the two surfaces was confusing because
// the same SOC value rendered green on the widget but yellow on the dashboard.
private fun socColor(soc: Int?): Color =
    if (soc == null) TextMuted else componentsSocColor(soc)
