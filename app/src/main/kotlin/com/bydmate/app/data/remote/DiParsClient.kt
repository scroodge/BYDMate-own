package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class DiParsData(
    /**
     * Traction-battery SOC, whole %. Rounded from [socPrecise] so every existing
     * consumer (trip tracking, charging detection, UI, widget) keeps integer semantics.
     */
    val soc: Int?,
    val speed: Int?,
    val mileage: Double?,
    val power: Double?,
    val chargeGunState: Int?,
    val maxBatTemp: Int?,
    val avgBatTemp: Int?,
    val minBatTemp: Int?,
    val chargingStatus: Int?,
    val batteryCapacityKwh: Double?,
    val totalElecConsumption: Double?,
    val voltage12v: Double?,
    val maxCellVoltage: Double?,
    val minCellVoltage: Double?,
    val exteriorTemp: Int?,
    // Automation params (v2.2.0)
    val gear: Int?,               // 1=P, 2=R, 3=N, 4=D
    val powerState: Int?,         // 0=OFF, 1=ON, 2=DRIVE
    val insideTemp: Int?,
    val acStatus: Int?,           // 0=OFF, 1=ON
    val acTemp: Int?,
    val fanLevel: Int?,
    val acCirc: Int?,             // 0=external, 1=internal
    val doorFL: Int?,             // 0=closed, 1=open
    val doorFR: Int?,
    val doorRL: Int?,
    val doorRR: Int?,
    val windowFL: Int?,           // 0-100%
    val windowFR: Int?,
    val windowRL: Int?,
    val windowRR: Int?,
    val sunroof: Int?,            // 0-100%
    val trunk: Int?,              // 0=closed, 1=open
    val hood: Int?,               // 0=closed, 1=open
    val seatbeltFL: Int?,         // 0=unbuckled, 1=buckled, 2=invalid
    val lockFL: Int?,             // 1=unlocked, 2=locked
    val tirePressFL: Int?,        // kPa
    val tirePressFR: Int?,
    val tirePressRL: Int?,
    val tirePressRR: Int?,
    val driveMode: Int?,          // 1=ECO, 2=SPORT
    val workMode: Int?,           // 0=stop, 1=EV, 2=forced EV, 3=HEV
    val autoPark: Int?,           // 0=disabled, 1=standby, 2=active
    val rain: Int?,
    val lightLow: Int?,           // 0=OFF, 1=ON
    val drl: Int?,                // 0=invalid, 1=ON, 2=OFF
    val sunshade: Int?,           // 0-100%
    val sentryState: Int?,        // D+ sentry on/off
    val remoteLockState: Int?,    // remote lock enum
    /** D+ `熄火录像配置开关` text value, e.g. 开启熄火哨兵 */
    val stallSentryMode: String? = null,
    /** D+ `电源状态` text, e.g. 关 / 行车 */
    val powerStateLabel: String? = null,
    /**
     * Unrounded SOC. di+ 2.0 reports 0.1 % resolution (`SOC:76.1`); di+ 1.x reports whole
     * percents through the same field, so this equals [soc] there. Sent to the cloud under
     * the existing `soc` JSON key — `diplus_soc` is `numeric`, so the decimal is preserved.
     */
    val socPrecise: Double? = null,
)

@Singleton
open class DiParsClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DiParsClient"
        private const val BASE_URL = "http://127.0.0.1:8988/api/getDiPars"
        private const val GET_VAL_URL = "http://127.0.0.1:8988/api/getVal"
        // The two getVal fields (stall-sentry config + power-state label) are
        // descriptive telemetry strings that change rarely; refresh them at this
        // cadence instead of every tick to save two localhost round-trips per second.
        private const val VAL_REFRESH_INTERVAL_MS = 30_000L
        private const val TEMPLATE =
            "SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}" +
            "|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}" +
            "|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}" +
            "|ChargingStatus:{充电状态}" +
            "|BatCapacity:{电池容量}|TotalElecCon:{总电耗}" +
            "|Voltage12V:{蓄电池电压}|MaxCellV:{最高电池电压}" +
            "|MinCellV:{最低电池电压}|ExtTemp:{车外温度}" +
            // Automation params (v2.2.0)
            "|Gear:{档位}|PowerState:{电源状态}|InsideTemp:{车内温度}" +
            "|ACStatus:{空调状态}|ACTemp:{主驾驶空调温度}|FanLevel:{风量档位}" +
            "|ACCirc:{空调循环方式}" +
            "|DoorFL:{主驾车门}|DoorFR:{副驾车门}|DoorRL:{左后车门}|DoorRR:{右后车门}" +
            "|WindowFL:{主驾车窗打开百分比}|WindowFR:{副驾车窗打开百分比}" +
            "|WindowRL:{左后车窗打开百分比}|WindowRR:{右后车窗打开百分比}" +
            "|Sunroof:{天窗打开百分比}|Trunk:{后备箱门}|Hood:{引擎盖}" +
            "|SeatbeltFL:{主驾驶安全带状态}|LockFL:{主驾车门锁}" +
            "|TirePressFL:{左前轮气压}|TirePressFR:{右前轮气压}" +
            "|TirePressRL:{左后轮气压}|TirePressRR:{右后轮气压}" +
            "|DriveMode:{整车运行模式}|WorkMode:{整车工作模式}" +
            "|AutoPark:{自动驻车}|Rain:{雨量}" +
            "|LightLow:{近光灯}|DRL:{日行灯}" +
            "|Sunshade:{遮阳帘打开百分比}|Sentry:{哨兵状态}|RemoteLock:{远程锁车状态}"

        // di+ emits magic "no data" sentinels when a signal can't be read; forwarding
        // them poisons cloud analytics. Values observed live on the head unit:
        //   Power=3095   — engine-power PID unreadable (car OFF while AC charging)
        //   InsideTemp=-2000
        //   Rain=-2147482648 (~ Int.MIN_VALUE)
        private const val MAX_PLAUSIBLE_POWER_KW = 350.0   // any BYD stays well within ±350 kW
        private const val SENTINEL_INT_ABS = 1_000_000     // catches Int.MIN_VALUE-family magic numbers
        private const val MIN_PLAUSIBLE_TEMP_C = -90
        private const val MAX_PLAUSIBLE_TEMP_C = 90

        /** Engine-power filter. di+ returns ~3095 kW when the PID is unreadable. */
        internal fun sanitizePowerKw(raw: Double?): Double? =
            raw?.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_PLAUSIBLE_POWER_KW }

        /** Drops Int.MIN_VALUE-family magic numbers (e.g. Rain=-2147482648). */
        internal fun sanitizeSentinelInt(raw: Int?): Int? =
            raw?.takeIf { kotlin.math.abs(it) < SENTINEL_INT_ABS }

        /** Cabin/ambient/battery temps: di+ uses -2000 (and similar) as "no data". */
        internal fun sanitizeTempC(raw: Int?): Int? =
            raw?.takeIf { it in MIN_PLAUSIBLE_TEMP_C..MAX_PLAUSIBLE_TEMP_C }

        /**
         * Single numeric gate for every di+ field, covering both di+ generations.
         *
         * di+ renders each numeric parameter with `NumberFormat.getInstance()` on a
         * `double` (verified in 1.3.8b16 `s$c.b` and 2.0.0b1 `u$c.c`), so a value may be
         * fractional and — since NumberFormat is locale-sensitive — may use a comma as
         * the decimal separator on a non-en/zh head unit. di+ 2.0 added an availability
         * gate: when a parameter is unavailable the lookup returns null and the
         * placeholder is left **unsubstituted**, so the wire carries a literal
         * `{哨兵状态}` rather than a number. Confirmed on car `way` 2026-08-14 against
         * di+ 2.0.0b1: `SOC:76.1|...|Sentry:{哨兵状态}`.
         *
         * Returns null for anything that is not a number, so callers keep getting a
         * clean absent value instead of a garbage one.
         */
        internal fun parseNum(raw: String?): Double? {
            val v = raw?.trim().orEmpty()
            if (v.isEmpty()) return null
            // Unsubstituted placeholder: di+ 2.0 signalling "parameter unavailable".
            if ((v.startsWith("{") && v.endsWith("}")) ||
                (v.startsWith("[") && v.endsWith("]"))
            ) return null
            // Locale decimal comma -> point. di+ emits at most one separator.
            return v.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        /** Integer-valued di+ field, tolerant of the fractional/placeholder cases above. */
        internal fun parseIntNum(raw: String?): Int? =
            parseNum(raw)
                ?.takeIf { kotlin.math.abs(it) <= Int.MAX_VALUE.toDouble() }
                ?.let { kotlin.math.round(it).toInt() }
    }

    // Cached getVal results (see VAL_REFRESH_INTERVAL_MS). @Volatile because fetch()
    // may run concurrently from the poll loop and the charging detector; a rare
    // double-refresh is harmless since the values are idempotent.
    @Volatile private var cachedStallSentryMode: String? = null
    @Volatile private var cachedPowerStateLabel: String? = null
    @Volatile private var lastValFetchMs: Long = 0L

    open suspend fun fetch(): DiParsData? = withContext(Dispatchers.IO) {
        try {
            val httpUrl = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("text", TEMPLATE)
                .build()
            val request = Request.Builder().url(httpUrl).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (body == null) {
                Log.w(TAG, "Response body is null")
                return@withContext null
            }

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                Log.w(TAG, "success=false: $body")
                return@withContext null
            }

            val base = parse(json.optString("val", ""))
            val now = System.currentTimeMillis()
            if (now - lastValFetchMs >= VAL_REFRESH_INTERVAL_MS) {
                lastValFetchMs = now
                cachedStallSentryMode = fetchVal("熄火录像配置开关") ?: cachedStallSentryMode
                cachedPowerStateLabel = fetchVal("电源状态") ?: cachedPowerStateLabel
            }
            base.copy(
                stallSentryMode = cachedStallSentryMode ?: base.stallSentryMode,
                powerStateLabel = cachedPowerStateLabel ?: base.powerStateLabel,
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchVal(name: String): String? {
        return try {
            val httpUrl = GET_VAL_URL.toHttpUrl().newBuilder()
                .addQueryParameter("name", name)
                .addQueryParameter("status", "true")
                .build()
            val request = Request.Builder().url(httpUrl).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return null
            json.optString("val").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "fetchVal($name) failed: ${e.message}")
            null
        }
    }

    private fun parse(raw: String): DiParsData {
        val map = mutableMapOf<String, String>()
        raw.split("|").forEach { part ->
            val colonIdx = part.indexOf(':')
            if (colonIdx > 0) {
                val key = part.substring(0, colonIdx)
                val value = part.substring(colonIdx + 1)
                map[key] = value
            }
        }

        // Cell voltages: DiPlus already divides raw millivolts by 1000 → value is in volts
        // Treat <= 0 as unavailable (DiPlus returns 0.0 when BMS hasn't reported)
        val maxCellRaw = parseNum(map["MaxCellV"])
        val minCellRaw = parseNum(map["MinCellV"])
        val maxCell = maxCellRaw?.takeIf { it > 0.5 }
        val minCell = minCellRaw?.takeIf { it > 0.5 }

        // 12V: may come as millivolts (>100) or volts (<100); 0 = unavailable
        val v12Raw = parseNum(map["Voltage12V"])
        val v12 = when {
            v12Raw == null || v12Raw <= 0.0 -> null
            v12Raw > 100.0 -> v12Raw / 1000.0  // millivolts → volts
            else -> v12Raw                       // already in volts
        }

        Log.d(TAG, "Raw DiPlus: MaxCellV=${map["MaxCellV"]}, MinCellV=${map["MinCellV"]}, " +
            "Voltage12V=${map["Voltage12V"]}, ExtTemp=${map["ExtTemp"]}, " +
            "BatCapacity=${map["BatCapacity"]}, AvgBatTemp=${map["AvgBatTemp"]}")
        Log.d(TAG, "Parsed: maxCell=$maxCell, minCell=$minCell, v12=$v12")

        return DiParsData(
            soc = parseIntNum(map["SOC"]),
            socPrecise = parseNum(map["SOC"]),
            speed = parseIntNum(map["Speed"]),
            mileage = parseNum(map["Mileage"])?.let { it / 10.0 },
            power = sanitizePowerKw(parseNum(map["Power"])),
            chargeGunState = parseIntNum(map["ChargeGun"]),
            maxBatTemp = sanitizeTempC(parseIntNum(map["MaxBatTemp"])),
            avgBatTemp = sanitizeTempC(parseIntNum(map["AvgBatTemp"])),
            minBatTemp = sanitizeTempC(parseIntNum(map["MinBatTemp"])),
            chargingStatus = parseIntNum(map["ChargingStatus"]),
            batteryCapacityKwh = parseNum(map["BatCapacity"]),
            totalElecConsumption = parseNum(map["TotalElecCon"]),
            voltage12v = v12,
            maxCellVoltage = maxCell,
            minCellVoltage = minCell,
            exteriorTemp = sanitizeTempC(parseIntNum(map["ExtTemp"])),
            gear = parseIntNum(map["Gear"]),
            powerState = parseIntNum(map["PowerState"]),
            insideTemp = sanitizeTempC(parseIntNum(map["InsideTemp"])),
            acStatus = parseIntNum(map["ACStatus"]),
            acTemp = parseIntNum(map["ACTemp"]),
            fanLevel = parseIntNum(map["FanLevel"]),
            acCirc = parseIntNum(map["ACCirc"]),
            doorFL = parseIntNum(map["DoorFL"]),
            doorFR = parseIntNum(map["DoorFR"]),
            doorRL = parseIntNum(map["DoorRL"]),
            doorRR = parseIntNum(map["DoorRR"]),
            windowFL = parseIntNum(map["WindowFL"]),
            windowFR = parseIntNum(map["WindowFR"]),
            windowRL = parseIntNum(map["WindowRL"]),
            windowRR = parseIntNum(map["WindowRR"]),
            sunroof = parseIntNum(map["Sunroof"]),
            trunk = parseIntNum(map["Trunk"]),
            hood = parseIntNum(map["Hood"]),
            seatbeltFL = parseIntNum(map["SeatbeltFL"]),
            lockFL = parseIntNum(map["LockFL"]),
            tirePressFL = parseIntNum(map["TirePressFL"]),
            tirePressFR = parseIntNum(map["TirePressFR"]),
            tirePressRL = parseIntNum(map["TirePressRL"]),
            tirePressRR = parseIntNum(map["TirePressRR"]),
            driveMode = parseIntNum(map["DriveMode"]),
            workMode = parseIntNum(map["WorkMode"]),
            autoPark = parseIntNum(map["AutoPark"]),
            rain = sanitizeSentinelInt(parseIntNum(map["Rain"])),
            lightLow = parseIntNum(map["LightLow"]),
            drl = parseIntNum(map["DRL"]),
            sunshade = parseIntNum(map["Sunshade"]),
            sentryState = parseIntNum(map["Sentry"]),
            remoteLockState = parseIntNum(map["RemoteLock"]),
            stallSentryMode = null,
            powerStateLabel = null,
        )
    }
}
