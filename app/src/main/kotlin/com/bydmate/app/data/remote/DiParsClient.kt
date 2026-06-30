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
)

@Singleton
open class DiParsClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DiParsClient"
        private const val BASE_URL = "http://127.0.0.1:8988/api/getDiPars"
        private const val GET_VAL_URL = "http://127.0.0.1:8988/api/getVal"
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
    }

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
            val stallSentry = fetchVal("熄火录像配置开关")
            val powerLabel = fetchVal("电源状态")
            base.copy(
                stallSentryMode = stallSentry ?: base.stallSentryMode,
                powerStateLabel = powerLabel ?: base.powerStateLabel,
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
        val maxCellRaw = map["MaxCellV"]?.toDoubleOrNull()
        val minCellRaw = map["MinCellV"]?.toDoubleOrNull()
        val maxCell = maxCellRaw?.takeIf { it > 0.5 }
        val minCell = minCellRaw?.takeIf { it > 0.5 }

        // 12V: may come as millivolts (>100) or volts (<100); 0 = unavailable
        val v12Raw = map["Voltage12V"]?.toDoubleOrNull()
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
            soc = map["SOC"]?.toIntOrNull(),
            speed = map["Speed"]?.toIntOrNull(),
            mileage = map["Mileage"]?.toDoubleOrNull()?.let { it / 10.0 },
            power = sanitizePowerKw(map["Power"]?.toDoubleOrNull()),
            chargeGunState = map["ChargeGun"]?.toIntOrNull(),
            maxBatTemp = sanitizeTempC(map["MaxBatTemp"]?.toIntOrNull()),
            avgBatTemp = sanitizeTempC(map["AvgBatTemp"]?.toIntOrNull()),
            minBatTemp = sanitizeTempC(map["MinBatTemp"]?.toIntOrNull()),
            chargingStatus = map["ChargingStatus"]?.toIntOrNull(),
            batteryCapacityKwh = map["BatCapacity"]?.toDoubleOrNull(),
            totalElecConsumption = map["TotalElecCon"]?.toDoubleOrNull(),
            voltage12v = v12,
            maxCellVoltage = maxCell,
            minCellVoltage = minCell,
            exteriorTemp = sanitizeTempC(map["ExtTemp"]?.toIntOrNull()),
            gear = map["Gear"]?.toIntOrNull(),
            powerState = map["PowerState"]?.toIntOrNull(),
            insideTemp = sanitizeTempC(map["InsideTemp"]?.toIntOrNull()),
            acStatus = map["ACStatus"]?.toIntOrNull(),
            acTemp = map["ACTemp"]?.toIntOrNull(),
            fanLevel = map["FanLevel"]?.toIntOrNull(),
            acCirc = map["ACCirc"]?.toIntOrNull(),
            doorFL = map["DoorFL"]?.toIntOrNull(),
            doorFR = map["DoorFR"]?.toIntOrNull(),
            doorRL = map["DoorRL"]?.toIntOrNull(),
            doorRR = map["DoorRR"]?.toIntOrNull(),
            windowFL = map["WindowFL"]?.toIntOrNull(),
            windowFR = map["WindowFR"]?.toIntOrNull(),
            windowRL = map["WindowRL"]?.toIntOrNull(),
            windowRR = map["WindowRR"]?.toIntOrNull(),
            sunroof = map["Sunroof"]?.toIntOrNull(),
            trunk = map["Trunk"]?.toIntOrNull(),
            hood = map["Hood"]?.toIntOrNull(),
            seatbeltFL = map["SeatbeltFL"]?.toIntOrNull(),
            lockFL = map["LockFL"]?.toIntOrNull(),
            tirePressFL = map["TirePressFL"]?.toIntOrNull(),
            tirePressFR = map["TirePressFR"]?.toIntOrNull(),
            tirePressRL = map["TirePressRL"]?.toIntOrNull(),
            tirePressRR = map["TirePressRR"]?.toIntOrNull(),
            driveMode = map["DriveMode"]?.toIntOrNull(),
            workMode = map["WorkMode"]?.toIntOrNull(),
            autoPark = map["AutoPark"]?.toIntOrNull(),
            rain = sanitizeSentinelInt(map["Rain"]?.toIntOrNull()),
            lightLow = map["LightLow"]?.toIntOrNull(),
            drl = map["DRL"]?.toIntOrNull(),
            sunshade = map["Sunshade"]?.toIntOrNull(),
            sentryState = map["Sentry"]?.toIntOrNull(),
            remoteLockState = map["RemoteLock"]?.toIntOrNull(),
            stallSentryMode = null,
            powerStateLabel = null,
        )
    }
}
