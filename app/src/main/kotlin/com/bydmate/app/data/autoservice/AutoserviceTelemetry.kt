package com.bydmate.app.data.autoservice

import android.util.Log
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Test toggle "данные autoservice": read the di+ telemetry fields straight from the BYD
 * `autoservice` Binder (on-device ADB, shell uid) and use them instead of di+'s.
 *
 * FID table: BYDMate-v4.2.0 decompile, live-checked against di+ on car `way` 2026-11-04 —
 * see the "Telemetry-from-autoservice" block in [FidRegistry].
 *
 * Deliberately NOT covered here:
 *  - SOC. autoservice serves the dashboard SOC, di+ the raw BMS SOC (~2 pp apart), and the
 *    payload tags which scale `soc` is on. That choice already has its own switch
 *    ("SOC от машины") and fallback path; mixing it in here would mis-tag the sample.
 *  - di+-only values with no autoservice twin: charging status, auto-park, rain, sentry,
 *    remote lock, stall-sentry label, DRL (autoservice disagreed with di+ on this car).
 */
enum class AutoserviceTelemetryField(val read: AutoserviceRead, val fast: Boolean) {
    // Fast group — changes second to second while driving; read every cycle.
    SPEED(AutoserviceRead(FidRegistry.TX_GET_FLOAT, FidRegistry.DEV_SPEED, FidRegistry.FID_SPEED), fast = true),
    POWER(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_ENGINE, FidRegistry.FID_ENGINE_POWER), fast = true),
    GEAR(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_GEARBOX, FidRegistry.FID_GEAR), fast = true),
    POWER_STATE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_SETTING, FidRegistry.FID_POWER_STATE), fast = true),
    MILEAGE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_STATISTIC, FidRegistry.FID_LIFETIME_MILEAGE), fast = true),

    // Slow group — read in rotating chunks, full refresh every few seconds.
    TOTAL_ELEC_CONSUMPTION(AutoserviceRead(FidRegistry.TX_GET_FLOAT, FidRegistry.DEV_STATISTIC, FidRegistry.FID_LIFETIME_KWH), fast = false),
    VOLTAGE_12V(AutoserviceRead(FidRegistry.TX_GET_FLOAT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_OTA_BATTERY_POWER_VOLTAGE), fast = false),
    MAX_CELL_VOLTAGE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_STATISTIC, FidRegistry.FID_MAX_CELL_VOLTAGE), fast = false),
    MIN_CELL_VOLTAGE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_STATISTIC, FidRegistry.FID_MIN_CELL_VOLTAGE), fast = false),
    MAX_BATTERY_TEMP(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_STATISTIC, FidRegistry.FID_MAX_BATTERY_TEMP), fast = false),
    MIN_BATTERY_TEMP(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_STATISTIC, FidRegistry.FID_MIN_BATTERY_TEMP), fast = false),
    MAX_CHARGE_POWER(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_STATISTIC, FidRegistry.FID_MAX_CHARGE_POWER), fast = false),
    CHARGE_GUN(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_CHARGING, FidRegistry.FID_GUN_CONNECT_STATE), fast = false),
    BMS_STATE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_CHARGING, FidRegistry.FID_CHARGING_BMS_STATE), fast = false),
    AC_STATUS(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_AC, FidRegistry.FID_AC_POWER_STATE), fast = false),
    AC_TEMP(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_AC, FidRegistry.FID_AC_TEMP_MAIN), fast = false),
    FAN_LEVEL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_AC, FidRegistry.FID_AC_WIND_LEVEL), fast = false),
    AC_CIRC(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_AC, FidRegistry.FID_AC_CYCLE_MODE), fast = false),
    INSIDE_TEMP(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_AC, FidRegistry.FID_AC_TEMP_INSIDE), fast = false),
    EXTERIOR_TEMP(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_AC, FidRegistry.FID_AC_TEMP_OUT), fast = false),
    DOOR_FL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_DOOR_FL), fast = false),
    DOOR_FR(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_DOOR_FR), fast = false),
    DOOR_RL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_DOOR_RL), fast = false),
    DOOR_RR(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_DOOR_RR), fast = false),
    WINDOW_FL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_WINDOW_FL), fast = false),
    WINDOW_FR(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_WINDOW_FR), fast = false),
    WINDOW_RL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_WINDOW_RL), fast = false),
    WINDOW_RR(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_WINDOW_RR), fast = false),
    SUNROOF(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_SUNROOF), fast = false),
    SUNSHADE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_SUNSHADE), fast = false),
    TRUNK(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_TRUNK), fast = false),
    HOOD(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_HOOD), fast = false),
    SEATBELT_FL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_INSTRUMENT, FidRegistry.FID_SEATBELT_FL), fast = false),
    LOCK_FL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_LOCK, FidRegistry.FID_LOCK_FL), fast = false),
    TIRE_FL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_TIRE_PRESSURE_FL), fast = false),
    TIRE_FR(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_TIRE_PRESSURE_FR), fast = false),
    TIRE_RL(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_TIRE_PRESSURE_RL), fast = false),
    TIRE_RR(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_BODYWORK, FidRegistry.FID_TIRE_PRESSURE_RR), fast = false),
    DRIVE_MODE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_ENERGY, FidRegistry.FID_DRIVE_MODE), fast = false),
    WORK_MODE(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_ENERGY, FidRegistry.FID_WORK_MODE), fast = false),
    LIGHT_LOW(AutoserviceRead(FidRegistry.TX_GET_INT, FidRegistry.DEV_LIGHT, FidRegistry.FID_LIGHT_LOW), fast = false);

    /** Decoded value: sentinel-filtered int, or the float for transact 7. Null = no data. */
    fun decode(raw: Int): Double? = if (read.tx == FidRegistry.TX_GET_FLOAT) {
        // toString() round-trip keeps 12.2f as 12.2 rather than 12.199999809265137.
        SentinelDecoder.parseFloatFromShellInt(raw)?.toString()?.toDouble()
    } else {
        SentinelDecoder.decodeInt(raw)?.toDouble()
    }
}

/** Latest decoded values with their read time. Immutable view for the poll loop. */
data class AutoserviceTelemetrySnapshot(
    val values: Map<AutoserviceTelemetryField, Double>,
    val readAtMs: Map<AutoserviceTelemetryField, Long>,
) {
    fun fresh(field: AutoserviceTelemetryField, nowMs: Long): Double? {
        val at = readAtMs[field] ?: return null
        val maxAge = if (field.fast) FAST_MAX_AGE_MS else SLOW_MAX_AGE_MS
        if (nowMs - at > maxAge) return null
        return values[field]
    }

    val isEmpty: Boolean get() = readAtMs.isEmpty()

    companion object {
        val EMPTY = AutoserviceTelemetrySnapshot(emptyMap(), emptyMap())
        const val FAST_MAX_AGE_MS = 5_000L
        const val SLOW_MAX_AGE_MS = 30_000L
    }
}

/** Result of [AutoserviceTelemetryOverlay.apply]: the merged sample plus what came from where. */
data class AutoserviceOverlayResult(
    val data: DiParsData,
    /** Fields taken from autoservice this tick. */
    val fromAutoservice: Set<AutoserviceTelemetryField>,
    /** Fields where di+ and autoservice both had a value and disagreed: "NAME di+=x as=y". */
    val mismatches: List<String>,
)

/**
 * Pure merge of an autoservice snapshot into a di+ sample, converted to di+'s units so
 * every consumer downstream (trips, cloud payload, widget) sees the same shape it always
 * did. A stale or sentinel autoservice value leaves the di+ value in place.
 */
object AutoserviceTelemetryOverlay {

    /** Null only when there is nothing at all: no di+ sample and no fresh autoservice value. */
    fun apply(diPlus: DiParsData?, snapshot: AutoserviceTelemetrySnapshot, nowMs: Long): AutoserviceOverlayResult? {
        val base = diPlus ?: EMPTY_DIPARS
        val used = mutableSetOf<AutoserviceTelemetryField>()
        val mismatches = mutableListOf<String>()

        fun <T : Any> pick(field: AutoserviceTelemetryField, diValue: T?, convert: (Double) -> T?): T? {
            val raw = snapshot.fresh(field, nowMs) ?: return diValue
            val converted = convert(raw) ?: return diValue
            used += field
            if (diValue != null && !sameValue(diValue, converted)) {
                mismatches += "${field.name} di+=$diValue as=$converted"
            }
            return converted
        }

        val asInt: (Double) -> Int? = { DiParsClient.sanitizeSentinelInt(it.roundToInt()) }
        val asTemp: (Double) -> Int? = { DiParsClient.sanitizeTempC(it.roundToInt()) }
        val asBatteryTemp: (Double) -> Int? = {
            DiParsClient.sanitizeTempC(it.roundToInt() - FidRegistry.BATTERY_TEMP_OFFSET_C)
        }

        val maxBatTemp = pick(AutoserviceTelemetryField.MAX_BATTERY_TEMP, base.maxBatTemp, asBatteryTemp)
        val minBatTemp = pick(AutoserviceTelemetryField.MIN_BATTERY_TEMP, base.minBatTemp, asBatteryTemp)
        // autoservice has no average; keep di+'s, else the midpoint (di+ 7 = (8+6)/2 live).
        val avgBatTemp = base.avgBatTemp
            ?: if (maxBatTemp != null && minBatTemp != null) (maxBatTemp + minBatTemp) / 2 else null

        val merged = base.copy(
            speed = pick(AutoserviceTelemetryField.SPEED, base.speed) { it.roundToInt().takeIf { v -> v in 0..400 } },
            mileage = pick(AutoserviceTelemetryField.MILEAGE, base.mileage) { it.takeIf { v -> v > 0 }?.div(10.0) },
            power = pick(AutoserviceTelemetryField.POWER, base.power) { DiParsClient.sanitizePowerKw(it) },
            chargeGunState = pick(AutoserviceTelemetryField.CHARGE_GUN, base.chargeGunState, asInt),
            maxBatTemp = maxBatTemp,
            avgBatTemp = avgBatTemp,
            minBatTemp = minBatTemp,
            totalElecConsumption = pick(AutoserviceTelemetryField.TOTAL_ELEC_CONSUMPTION, base.totalElecConsumption) { it.takeIf { v -> v >= 0 } },
            voltage12v = pick(AutoserviceTelemetryField.VOLTAGE_12V, base.voltage12v) { it.takeIf { v -> v in 5.0..20.0 } },
            maxCellVoltage = pick(AutoserviceTelemetryField.MAX_CELL_VOLTAGE, base.maxCellVoltage) { cellVolts(it) },
            minCellVoltage = pick(AutoserviceTelemetryField.MIN_CELL_VOLTAGE, base.minCellVoltage) { cellVolts(it) },
            exteriorTemp = pick(AutoserviceTelemetryField.EXTERIOR_TEMP, base.exteriorTemp, asTemp),
            gear = pick(AutoserviceTelemetryField.GEAR, base.gear, asInt),
            powerState = pick(AutoserviceTelemetryField.POWER_STATE, base.powerState, asInt),
            insideTemp = pick(AutoserviceTelemetryField.INSIDE_TEMP, base.insideTemp, asTemp),
            acStatus = pick(AutoserviceTelemetryField.AC_STATUS, base.acStatus, asInt),
            acTemp = pick(AutoserviceTelemetryField.AC_TEMP, base.acTemp, asTemp),
            fanLevel = pick(AutoserviceTelemetryField.FAN_LEVEL, base.fanLevel, asInt),
            acCirc = pick(AutoserviceTelemetryField.AC_CIRC, base.acCirc, asInt),
            doorFL = pick(AutoserviceTelemetryField.DOOR_FL, base.doorFL, asInt),
            doorFR = pick(AutoserviceTelemetryField.DOOR_FR, base.doorFR, asInt),
            doorRL = pick(AutoserviceTelemetryField.DOOR_RL, base.doorRL, asInt),
            doorRR = pick(AutoserviceTelemetryField.DOOR_RR, base.doorRR, asInt),
            windowFL = pick(AutoserviceTelemetryField.WINDOW_FL, base.windowFL, ::percent),
            windowFR = pick(AutoserviceTelemetryField.WINDOW_FR, base.windowFR, ::percent),
            windowRL = pick(AutoserviceTelemetryField.WINDOW_RL, base.windowRL, ::percent),
            windowRR = pick(AutoserviceTelemetryField.WINDOW_RR, base.windowRR, ::percent),
            sunroof = pick(AutoserviceTelemetryField.SUNROOF, base.sunroof, ::percent),
            sunshade = pick(AutoserviceTelemetryField.SUNSHADE, base.sunshade, ::percent),
            trunk = pick(AutoserviceTelemetryField.TRUNK, base.trunk, asInt),
            hood = pick(AutoserviceTelemetryField.HOOD, base.hood, asInt),
            seatbeltFL = pick(AutoserviceTelemetryField.SEATBELT_FL, base.seatbeltFL, asInt),
            lockFL = pick(AutoserviceTelemetryField.LOCK_FL, base.lockFL, asInt),
            tirePressFL = pick(AutoserviceTelemetryField.TIRE_FL, base.tirePressFL, ::tireKpa),
            tirePressFR = pick(AutoserviceTelemetryField.TIRE_FR, base.tirePressFR, ::tireKpa),
            tirePressRL = pick(AutoserviceTelemetryField.TIRE_RL, base.tirePressRL, ::tireKpa),
            tirePressRR = pick(AutoserviceTelemetryField.TIRE_RR, base.tirePressRR, ::tireKpa),
            driveMode = pick(AutoserviceTelemetryField.DRIVE_MODE, base.driveMode, asInt),
            workMode = pick(AutoserviceTelemetryField.WORK_MODE, base.workMode, asInt),
            lightLow = pick(AutoserviceTelemetryField.LIGHT_LOW, base.lightLow, asInt),
            bmsState = pick(AutoserviceTelemetryField.BMS_STATE, base.bmsState, asInt),
            maxChargePowerKw = pick(AutoserviceTelemetryField.MAX_CHARGE_POWER, base.maxChargePowerKw) { it.takeIf { v -> v >= 0 }?.div(10.0) },
        )
        if (diPlus == null && used.isEmpty()) return null
        return AutoserviceOverlayResult(merged, used, mismatches)
    }

    private fun cellVolts(raw: Double): Double? = (raw / 1000.0).takeIf { it in 1.5..5.0 }
    private fun percent(raw: Double): Int? = raw.roundToInt().takeIf { it in 0..100 }
    private fun tireKpa(raw: Double): Int? = raw.roundToInt().takeIf { it in 1..600 }

    private fun sameValue(a: Any, b: Any): Boolean = when {
        a is Number && b is Number -> kotlin.math.abs(a.toDouble() - b.toDouble()) < 0.0005
        else -> a == b
    }

    /** A di+ sample with every field absent — the base when di+ returned nothing. */
    internal val EMPTY_DIPARS = DiParsData(
        soc = null, speed = null, mileage = null, power = null, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null, chargingStatus = null,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = null,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = null, gear = null,
        powerState = null, insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null, sunroof = null,
        trunk = null, hood = null, seatbeltFL = null, lockFL = null, tirePressFL = null,
        tirePressFR = null, tirePressRL = null, tirePressRR = null, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
        sunshade = null, sentryState = null, remoteLockState = null,
    )
}

/**
 * Background reader for the test toggle. Runs off the 1 Hz poll loop on purpose: one
 * `service call` costs ~28 ms over on-device ADB, so the full ~38-field set (~1 s) must
 * never sit upstream of the cloud push and the liveness beacon (CLAUDE.md, v0.5.2).
 *
 * Each cycle reads the fast group plus one rotating slow chunk, as separate ADB execs so
 * the synchronized protocol lock is never held for more than ~6 calls at a time.
 */
@Singleton
class AutoserviceTelemetryReader @Inject constructor(
    private val adb: AdbOnDeviceClient,
) {
    private val values = ConcurrentHashMap<AutoserviceTelemetryField, Double>()
    private val readAt = ConcurrentHashMap<AutoserviceTelemetryField, Long>()

    @Volatile var lastCycleMs: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    fun snapshot(): AutoserviceTelemetrySnapshot =
        AutoserviceTelemetrySnapshot(HashMap(values), HashMap(readAt))

    fun clear() {
        values.clear()
        readAt.clear()
    }

    suspend fun run(isEnabled: () -> Boolean) {
        var slowChunkIndex = 0
        var lastConnectAttemptMs = 0L
        while (true) {
            if (!isEnabled()) {
                if (readAt.isNotEmpty()) clear()
                delay(IDLE_DELAY_MS)
                continue
            }
            val cycleStart = System.currentTimeMillis()
            try {
                if (!adb.isConnected() && cycleStart - lastConnectAttemptMs >= RECONNECT_INTERVAL_MS) {
                    lastConnectAttemptMs = cycleStart
                    adb.connect().onFailure { lastError = "adb connect: ${it.message}" }
                }
                if (adb.isConnected()) {
                    readGroup(FAST_FIELDS)
                    readGroup(SLOW_CHUNKS[slowChunkIndex])
                    slowChunkIndex = (slowChunkIndex + 1) % SLOW_CHUNKS.size
                    lastCycleMs = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "cycle failed: ${e.message}")
            }
            val elapsed = System.currentTimeMillis() - cycleStart
            delay((CYCLE_MS - elapsed).coerceAtLeast(MIN_GAP_MS))
        }
    }

    private suspend fun readGroup(fields: List<AutoserviceTelemetryField>) {
        val raws = adb.execAutoserviceReads(fields.map { it.read })
        if (raws == null) {
            lastError = "batch read returned null"
            return
        }
        val now = System.currentTimeMillis()
        fields.forEachIndexed { i, field ->
            val decoded = raws.getOrNull(i)?.let { field.decode(it) }
            if (decoded != null) {
                values[field] = decoded
                readAt[field] = now
            } else {
                // Sentinel / missing: drop the old value so a stale reading can't linger.
                values.remove(field)
                readAt.remove(field)
            }
        }
    }

    companion object {
        private const val TAG = "AsTelemetry"
        private const val CYCLE_MS = 1_000L
        private const val MIN_GAP_MS = 200L
        private const val IDLE_DELAY_MS = 2_000L
        private const val RECONNECT_INTERVAL_MS = 30_000L
        private const val SLOW_CHUNK_SIZE = 6

        val FAST_FIELDS: List<AutoserviceTelemetryField> =
            AutoserviceTelemetryField.entries.filter { it.fast }
        val SLOW_CHUNKS: List<List<AutoserviceTelemetryField>> =
            AutoserviceTelemetryField.entries.filter { !it.fast }.chunked(SLOW_CHUNK_SIZE)
    }
}
