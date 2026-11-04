package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-car comparison log for the "данные autoservice" test toggle — one line every
 * [INTERVAL_MS] with every field as `di+/autoservice`, shipped as its own section by
 * «Отправить диагностику» (Telegram) for offline analysis.
 *
 * Line format (one sample):
 *   `04.11 14:03:05 di+=ok as=38/40 raw=39 err=- | SPEED=52/52 POWER=18/19* GEAR=4/4 ...`
 * `-` = no value from that source, `*` marks a disagreement. A dated header line is
 * written whenever the toggle is switched on, so separate drives are easy to tell apart.
 *
 * File is capped at [MAX_BYTES]; on overflow the older half is dropped.
 */
object AutoserviceTelemetryLog {
    const val INTERVAL_MS = 5_000L
    private const val FILE_NAME = "autoservice_telemetry.log"
    private const val MAX_BYTES = 1_500_000L
    private const val TAG = "AsTelemetryLog"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun appendHeader(context: Context, text: String) = appendLine(context, "=== ${stamp()} $text")

    fun appendSample(
        context: Context,
        diPlus: DiParsData?,
        result: AutoserviceOverlayResult?,
        snapshot: AutoserviceTelemetrySnapshot,
        readerError: String?,
    ) = appendLine(context, formatSample(stamp(), diPlus, result, snapshot, readerError))

    fun read(context: Context): String? = runCatching {
        file(context).takeIf { it.exists() }?.readText()
    }.getOrNull()

    /** Pure, for tests. */
    internal fun formatSample(
        stamp: String,
        diPlus: DiParsData?,
        result: AutoserviceOverlayResult?,
        snapshot: AutoserviceTelemetrySnapshot,
        readerError: String?,
    ): String = buildString {
        val total = AutoserviceTelemetryField.entries.size
        append(stamp)
        append(" di+=").append(if (diPlus != null) "ok" else "none")
        append(" as=").append(result?.fromAutoservice?.size ?: 0).append('/').append(total)
        append(" raw=").append(snapshot.values.size)
        append(" err=").append(readerError?.replace(' ', '_')?.take(60) ?: "-")
        append(" |")
        for (field in AutoserviceTelemetryField.entries) {
            val di = diPlus?.let { fieldValue(it, field) }
            val asValue = result?.takeIf { field in it.fromAutoservice }?.let { fieldValue(it.data, field) }
            append(' ').append(field.name).append('=').append(fmt(di)).append('/').append(fmt(asValue))
            if (di != null && asValue != null && fmt(di) != fmt(asValue)) append('*')
        }
    }

    /** The di+-shaped value the overlay writes for [field]. */
    internal fun fieldValue(d: DiParsData, field: AutoserviceTelemetryField): Any? = when (field) {
        AutoserviceTelemetryField.SPEED -> d.speed
        AutoserviceTelemetryField.POWER -> d.power
        AutoserviceTelemetryField.GEAR -> d.gear
        AutoserviceTelemetryField.POWER_STATE -> d.powerState
        AutoserviceTelemetryField.MILEAGE -> d.mileage
        AutoserviceTelemetryField.TOTAL_ELEC_CONSUMPTION -> d.totalElecConsumption
        AutoserviceTelemetryField.VOLTAGE_12V -> d.voltage12v
        AutoserviceTelemetryField.MAX_CELL_VOLTAGE -> d.maxCellVoltage
        AutoserviceTelemetryField.MIN_CELL_VOLTAGE -> d.minCellVoltage
        AutoserviceTelemetryField.MAX_BATTERY_TEMP -> d.maxBatTemp
        AutoserviceTelemetryField.MIN_BATTERY_TEMP -> d.minBatTemp
        AutoserviceTelemetryField.MAX_CHARGE_POWER -> d.maxChargePowerKw
        AutoserviceTelemetryField.CHARGE_GUN -> d.chargeGunState
        AutoserviceTelemetryField.BMS_STATE -> d.bmsState
        AutoserviceTelemetryField.AC_STATUS -> d.acStatus
        AutoserviceTelemetryField.AC_TEMP -> d.acTemp
        AutoserviceTelemetryField.FAN_LEVEL -> d.fanLevel
        AutoserviceTelemetryField.AC_CIRC -> d.acCirc
        AutoserviceTelemetryField.INSIDE_TEMP -> d.insideTemp
        AutoserviceTelemetryField.EXTERIOR_TEMP -> d.exteriorTemp
        AutoserviceTelemetryField.DOOR_FL -> d.doorFL
        AutoserviceTelemetryField.DOOR_FR -> d.doorFR
        AutoserviceTelemetryField.DOOR_RL -> d.doorRL
        AutoserviceTelemetryField.DOOR_RR -> d.doorRR
        AutoserviceTelemetryField.WINDOW_FL -> d.windowFL
        AutoserviceTelemetryField.WINDOW_FR -> d.windowFR
        AutoserviceTelemetryField.WINDOW_RL -> d.windowRL
        AutoserviceTelemetryField.WINDOW_RR -> d.windowRR
        AutoserviceTelemetryField.SUNROOF -> d.sunroof
        AutoserviceTelemetryField.SUNSHADE -> d.sunshade
        AutoserviceTelemetryField.TRUNK -> d.trunk
        AutoserviceTelemetryField.HOOD -> d.hood
        AutoserviceTelemetryField.SEATBELT_FL -> d.seatbeltFL
        AutoserviceTelemetryField.LOCK_FL -> d.lockFL
        AutoserviceTelemetryField.TIRE_FL -> d.tirePressFL
        AutoserviceTelemetryField.TIRE_FR -> d.tirePressFR
        AutoserviceTelemetryField.TIRE_RL -> d.tirePressRL
        AutoserviceTelemetryField.TIRE_RR -> d.tirePressRR
        AutoserviceTelemetryField.DRIVE_MODE -> d.driveMode
        AutoserviceTelemetryField.WORK_MODE -> d.workMode
        AutoserviceTelemetryField.LIGHT_LOW -> d.lightLow
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "-"
        is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
            else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        else -> v.toString()
    }

    private fun stamp(): String = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())

    private fun appendLine(context: Context, line: String) {
        try {
            val f = file(context)
            if (f.exists() && f.length() > MAX_BYTES) {
                val text = f.readText()
                val keepFrom = text.indexOf('\n', text.length / 2).let { if (it < 0) 0 else it + 1 }
                f.writeText(text.substring(keepFrom))
            }
            f.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "append failed: ${e.message}")
        }
    }
}
