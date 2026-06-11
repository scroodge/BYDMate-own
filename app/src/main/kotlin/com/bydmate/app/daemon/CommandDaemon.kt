package com.bydmate.app.daemon

import com.bydmate.app.data.remote.CommandAllowlist
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsControlClient
import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Headless command-poll daemon — the survival-proof twin of [com.bydmate.app.data.remote.VehicleCommandPoller].
 *
 * Why this exists: the in-app poller runs inside the `com.bydmate.app` process, which BYD's
 * power-off routine (`collectPowerOffEvent` → force-stop) kills when the head unit parks/sleeps.
 * This class is launched as a shell-uid `app_process` daemon (see `tools/start_voltflow_cmd.sh`),
 * which survives the force-stop exactly like DI+ (`aps_diplus`) does.
 *
 * It needs NO Android Context: both actuation and telemetry go over plain localhost HTTP to
 * DiPlus on 127.0.0.1:8988, and [DiParsClient] / [DiParsControlClient] / [CommandAllowlist]
 * are all constructible from just an [OkHttpClient]. Config (cloud URL / api key / vehicle id)
 * comes from a properties-style file instead of SettingsRepository.
 *
 * Launch:  CLASSPATH=<base.apk> app_process /system/bin --nice-name=voltflow_cmd_daemon \
 *              com.bydmate.app.daemon.CommandDaemon [confPath]
 *
 * Safety: reuses [CommandAllowlist] (movement / aux-voltage guards + phrase allowlist) and
 * [DiParsControlClient]'s blocked-pattern filter verbatim — no command logic is reinvented here.
 */
object CommandDaemon {

    private const val DEFAULT_CONF = "/data/local/tmp/voltflow_cmd.conf"
    private const val BASE_POLL_MS = 2500L
    private const val MAX_BACKOFF_MS = 30_000L

    /** How often to refresh telemetry used for movement/voltage guards. */
    private const val TELEMETRY_TTL_MS = 5_000L

    /** How often the daemon pushes a telemetry sample to the cloud (keeps data flowing when the app is dead). */
    private const val TELEMETRY_PUSH_MS = 60_000L

    private val ts get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    private fun log(msg: String) {
        println("[$ts] $msg")
        System.out.flush()
    }

    private data class Conf(
        val commandsUrl: String,
        val ackUrl: String,
        val telemetryUrl: String,
        val apiKey: String,
        val vehicleId: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val confPath = args.getOrNull(0) ?: DEFAULT_CONF
        log("CommandDaemon starting (conf=$confPath)")

        val ok = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        val diPars = DiParsClient(ok)
        val control = DiParsControlClient(ok)

        var backoffMs = BASE_POLL_MS
        var latestData: DiParsData? = null
        var latestDataAt = 0L
        var lastTelemetryPushAt = 0L

        runBlocking {
            while (true) {
                val conf = loadConf(confPath)
                if (conf == null) {
                    Thread.sleep(BASE_POLL_MS)
                    continue
                }

                val waited = try {
                    // Refresh telemetry for guards if stale (cheap localhost call).
                    val now = System.currentTimeMillis()
                    if (now - latestDataAt > TELEMETRY_TTL_MS) {
                        diPars.fetch()?.let { latestData = it; latestDataAt = now }
                    }

                    // Push telemetry to the cloud so data keeps flowing while the app process is dead.
                    if (now - lastTelemetryPushAt >= TELEMETRY_PUSH_MS) {
                        latestData?.let { pushTelemetry(ok, conf, it) }
                        lastTelemetryPushAt = now
                    }

                    val result = pollOnce(ok, conf, control, latestData)
                    backoffMs = if (result) BASE_POLL_MS else min(backoffMs * 2, MAX_BACKOFF_MS)
                    if (result) BASE_POLL_MS else backoffMs
                } catch (e: Exception) {
                    log("poll error: ${e.message}")
                    backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                    backoffMs
                }
                Thread.sleep(waited)
            }
        }
    }

    /** @return true on a clean poll (HTTP ok), false to trigger backoff. */
    private suspend fun pollOnce(
        ok: OkHttpClient,
        conf: Conf,
        control: DiParsControlClient,
        data: DiParsData?,
    ): Boolean {
        val httpUrl = conf.commandsUrl.toHttpUrlOrNull() ?: return false
        val request = Request.Builder()
            .url(httpUrl)
            .header("X-API-Key", conf.apiKey)
            .header("X-Vehicle-Id", conf.vehicleId)
            .header("X-App", "VoltFlow-Mate-Daemon")
            .get()
            .build()

        ok.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log("poll HTTP ${response.code}")
                return false
            }
            val body = response.body?.string().orEmpty()
            val commands = JSONObject(body).optJSONArray("commands") ?: JSONArray()
            if (commands.length() == 0) return true

            log("received ${commands.length()} command(s)")
            val acks = JSONArray()
            for (i in 0 until commands.length()) {
                val cmd = commands.getJSONObject(i)
                val id = cmd.getString("id")
                val type = cmd.getString("type")
                val params = jsonObjectToMap(cmd.optJSONObject("params") ?: JSONObject())
                acks.put(executeCommand(control, data, id, type, params))
            }
            postAck(ok, conf, acks)
        }
        return true
    }

    private suspend fun executeCommand(
        control: DiParsControlClient,
        data: DiParsData?,
        id: String,
        type: String,
        params: Map<String, Any?>,
    ): JSONObject {
        CommandAllowlist.movementBlockReason(data)?.let { return ack(id, "rejected", mapOf("error" to it)) }
        CommandAllowlist.auxVoltageBlockReason(data)?.let { return ack(id, "rejected", mapOf("error" to it)) }

        return when (val built = CommandAllowlist.buildPhrase(type, params)) {
            is CommandAllowlist.BuildResult.Rejected ->
                ack(id, "rejected", mapOf("error" to built.reason))
            is CommandAllowlist.BuildResult.Ok -> {
                val sent = control.sendAllowlistedPhrase(built.phrase)
                if (sent) {
                    log("executed '$type' → '${built.phrase}'")
                    ack(id, "done", mapOf("phrase" to built.phrase, "verified" to false))
                } else {
                    log("FAILED '$type' → '${built.phrase}'")
                    ack(id, "failed", mapOf("error" to "sendCmd_failed", "phrase" to built.phrase))
                }
            }
        }
    }

    private fun postAck(ok: OkHttpClient, conf: Conf, acks: JSONArray) {
        try {
            val payload = JSONObject().put("acks", acks).toString()
            val request = Request.Builder()
                .url(conf.ackUrl)
                .header("Content-Type", "application/json")
                .header("X-API-Key", conf.apiKey)
                .header("X-Vehicle-Id", conf.vehicleId)
                .header("X-App", "VoltFlow-Mate-Daemon")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            ok.newCall(request).execute().use { log("ack HTTP ${it.code} (${acks.length()} items)") }
        } catch (e: Exception) {
            log("ack failed: ${e.message}")
        }
    }

    private fun ack(id: String, status: String, result: Map<String, Any?>): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("status", status)
            put("result", JSONObject(result))
        }

    /** Push one telemetry sample to the cloud ingest endpoint (contract: docs/cloud-telemetry-contract-ru.md). */
    private fun pushTelemetry(ok: OkHttpClient, conf: Conf, data: DiParsData) {
        try {
            val payload = buildTelemetryPayload(conf.vehicleId, data).toString()
            val request = Request.Builder()
                .url(conf.telemetryUrl)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-API-Key", conf.apiKey)
                .header("X-Vehicle-Id", conf.vehicleId)
                .header("X-App", "VoltFlow-Mate-Daemon")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            ok.newCall(request).execute().use {
                log("telemetry HTTP ${it.code} (soc=${data.soc} pwr_state=${data.powerState})")
            }
        } catch (e: Exception) {
            log("telemetry push failed: ${e.message}")
        }
    }

    private fun buildTelemetryPayload(vehicleId: String, d: DiParsData): JSONObject {
        val cellDelta = if (d.maxCellVoltage != null && d.minCellVoltage != null) {
            d.maxCellVoltage!! - d.minCellVoltage!!
        } else {
            null
        }
        val gun = d.chargeGunState
        val isCharging = (d.chargingStatus != null && d.chargingStatus!! > 0) ||
            (gun != null && gun in 2..5)

        val diplus = JSONObject().apply {
            putN("soc", d.soc); putN("speed_kmh", d.speed); putN("mileage_km", d.mileage)
            putN("power_kw", d.power); putN("charge_gun_state", d.chargeGunState)
            putN("max_battery_temp_c", d.maxBatTemp); putN("avg_battery_temp_c", d.avgBatTemp)
            putN("min_battery_temp_c", d.minBatTemp); putN("charging_status", d.chargingStatus)
            putN("battery_capacity_kwh", d.batteryCapacityKwh)
            putN("total_elec_consumption_kwh", d.totalElecConsumption)
            putN("voltage_12v", d.voltage12v); putN("max_cell_voltage_v", d.maxCellVoltage)
            putN("min_cell_voltage_v", d.minCellVoltage); putN("cell_delta_v", cellDelta)
            putN("exterior_temp_c", d.exteriorTemp); putN("gear", d.gear); putN("power_state", d.powerState)
            putN("inside_temp_c", d.insideTemp); putN("ac_status", d.acStatus); putN("ac_temp_c", d.acTemp)
            putN("fan_level", d.fanLevel); putN("ac_circ", d.acCirc)
            putN("door_fl", d.doorFL); putN("door_fr", d.doorFR); putN("door_rl", d.doorRL); putN("door_rr", d.doorRR)
            putN("window_fl_percent", d.windowFL); putN("window_fr_percent", d.windowFR)
            putN("window_rl_percent", d.windowRL); putN("window_rr_percent", d.windowRR)
            putN("sunroof_percent", d.sunroof); putN("trunk", d.trunk); putN("hood", d.hood)
            putN("seatbelt_fl", d.seatbeltFL); putN("lock_fl", d.lockFL)
            putN("tire_press_fl_kpa", d.tirePressFL); putN("tire_press_fr_kpa", d.tirePressFR)
            putN("tire_press_rl_kpa", d.tirePressRL); putN("tire_press_rr_kpa", d.tirePressRR)
            putN("drive_mode", d.driveMode); putN("work_mode", d.workMode); putN("auto_park", d.autoPark)
            putN("rain", d.rain); putN("light_low", d.lightLow); putN("drl", d.drl)
            putN("sunshade_percent", d.sunshade)
            putN("sentry_state", d.sentryState); putN("remote_lock_state", d.remoteLockState)
            putN("stall_sentry_mode", d.stallSentryMode)
        }

        val telemetry = JSONObject().apply {
            putN("soc", d.soc); putN("speed_kmh", d.speed?.toDouble()); putN("power_kw", d.power)
            putN("battery_temp_c", d.avgBatTemp?.toDouble()); putN("cabin_temp_c", d.insideTemp?.toDouble())
            putN("outside_temp_c", d.exteriorTemp?.toDouble()); putN("aux_voltage_v", d.voltage12v)
            putN("cell_voltage_min_v", d.minCellVoltage); putN("cell_voltage_max_v", d.maxCellVoltage)
            putN("cell_delta_v", cellDelta); putN("odometer_km", d.mileage)
            put("is_charging", isCharging)
            putN("charge_power_kw", if (isCharging) d.power?.let { kotlin.math.abs(it) } else null)
        }

        return JSONObject().apply {
            put("schema_version", 1)
            put("vehicle_id", vehicleId)
            put("device_time", isoNow())
            put("source", "BYDMate")
            put("telemetry", telemetry)
            put("diplus", diplus)
            // location is required by the ingest schema; the daemon has no GPS → empty (fields are nullable).
            put("location", JSONObject())
        }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun JSONObject.putN(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.get(key)
        }
        return map
    }

    /**
     * Config file is simple `key=value` lines:
     *   url=https://<host>/api/bydmate/telemetry   (or .../commands — either is accepted)
     *   api_key=...
     *   vehicle_id=...
     * Returns null (caller retries) if the file is missing/incomplete.
     */
    private fun loadConf(path: String): Conf? {
        val file = File(path)
        if (!file.isFile) return null
        val props = HashMap<String, String>()
        runCatching {
            file.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEachLine
                val idx = t.indexOf('=')
                if (idx > 0) props[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
            }
        }.onFailure { return null }

        val rawUrl = props["url"]?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = props["api_key"]?.takeIf { it.isNotBlank() } ?: return null
        val vehicleId = props["vehicle_id"]?.takeIf { it.isNotBlank() } ?: return null
        val commandsUrl = commandsUrlFromTelemetry(rawUrl) ?: return null
        val telemetryUrl = telemetryUrlFromAny(rawUrl) ?: return null
        return Conf(
            commandsUrl = commandsUrl,
            ackUrl = "$commandsUrl/ack",
            telemetryUrl = telemetryUrl,
            apiKey = apiKey,
            vehicleId = vehicleId,
        )
    }

    /** Mirror of VehicleCommandPoller.commandsUrlFromTelemetry — keep in sync. */
    private fun commandsUrlFromTelemetry(telemetryUrl: String): String? {
        val trimmed = telemetryUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/commands") -> trimmed
            trimmed.endsWith("/telemetry") -> trimmed.removeSuffix("/telemetry") + "/commands"
            trimmed.contains("/api/bydmate") -> trimmed.substringBeforeLast('/') + "/commands"
            else -> null
        }
    }

    /** Normalize the configured base URL to the `…/api/bydmate/telemetry` ingest endpoint. */
    private fun telemetryUrlFromAny(url: String): String? {
        val trimmed = url.trimEnd('/')
        return when {
            trimmed.endsWith("/telemetry") -> trimmed
            trimmed.endsWith("/commands") -> trimmed.removeSuffix("/commands") + "/telemetry"
            trimmed.contains("/api/bydmate") -> trimmed.substringBeforeLast('/') + "/telemetry"
            else -> null
        }
    }
}
