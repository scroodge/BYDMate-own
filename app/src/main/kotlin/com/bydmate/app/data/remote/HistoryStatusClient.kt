package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Live traction-pack reading from di+ 2.0 `/api/historyStatus`.
 *
 * [currentA] is signed exactly as di+ reports it: **negative while charging** (measured on car
 * `way`, AC, 2026-10-01: −17.8…−18.0 A at 316 V ≈ 5.65 kW) and a small positive value when idle
 * (+0.3 A). Do not take `abs()` of it to get charge power — see [HistoryStatusClient.chargePowerKw].
 */
data class PackReading(
    val voltageV: Double,
    val currentA: Double,
    val readAtMs: Long,
)

/**
 * Reads the pack voltage and current that di+ 2.0 already exposes on localhost, so charge power
 * can be derived as V × I instead of using di+'s integer engine-power parameter (which read `-5`
 * for a real 5.65 kW charge, an ~11 % under-report).
 *
 * di+ 1.x has no such endpoint; the call then fails or returns no `vehicle` object and callers
 * keep their previous behavior. The reply also runs several Room `COUNT(*)` queries, and polling
 * it measured ≈ +1.5 pp of one core per 1 Hz, so reads are rate-limited by [MIN_REFETCH_MS] and
 * callers should only ask while a gun is connected.
 */
@Singleton
open class HistoryStatusClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "HistoryStatusClient"
        private const val URL = "http://127.0.0.1:8988/api/historyStatus"

        /** Minimum gap between localhost reads; a cached value is returned in between. */
        internal const val MIN_REFETCH_MS = 5_000L

        /** A cached reading older than this is dropped so callers fall back, not coast on it. */
        internal const val MAX_AGE_MS = 15_000L

        private const val MIN_PLAUSIBLE_PACK_V = 100.0
        private const val MAX_PLAUSIBLE_PACK_V = 1000.0
        private const val MAX_PLAUSIBLE_PACK_A = 1000.0

        /**
         * Extracts `vehicle.batteryPackVoltage` / `vehicle.batteryPackCurrent`. Returns null when
         * the body is not JSON, has no `vehicle` object, or either value is missing, non-finite
         * (di+ yields NaN when its validity bit is clear) or physically implausible.
         */
        internal fun parse(body: String, nowMs: Long): PackReading? = try {
            val vehicle = JSONObject(body).optJSONObject("vehicle")
            if (vehicle == null) {
                null
            } else {
                sanitize(
                    voltageV = vehicle.optDouble("batteryPackVoltage", Double.NaN),
                    currentA = vehicle.optDouble("batteryPackCurrent", Double.NaN),
                    nowMs = nowMs,
                )
            }
        } catch (e: Exception) {
            null
        }

        internal fun sanitize(voltageV: Double, currentA: Double, nowMs: Long): PackReading? {
            if (!voltageV.isFinite() || !currentA.isFinite()) return null
            if (voltageV !in MIN_PLAUSIBLE_PACK_V..MAX_PLAUSIBLE_PACK_V) return null
            if (abs(currentA) > MAX_PLAUSIBLE_PACK_A) return null
            return PackReading(voltageV, currentA, nowMs)
        }

        /**
         * Power flowing **into** the pack, in kW: `max(0, −V × I / 1000)`, to 0.001 kW. Same formula
         * di+ uses for its own `batteryPower`. Only the charging direction counts: an idle
         * `+0.3 A` at 317 V would otherwise read 0.095 kW, right at the auto-start threshold
         * (`charge_power_kw > 0.1`). Null when there is no reading, so a missing measurement is
         * never mistaken for zero power.
         */
        fun chargePowerKw(pack: PackReading?): Double? {
            if (pack == null) return null
            val kw = max(0.0, -pack.voltageV * pack.currentA / 1000.0)
            return round(kw * 1000.0) / 1000.0
        }

        /** Decides whether a held reading may still be used at [nowMs]. */
        internal fun isUsable(pack: PackReading?, nowMs: Long): Boolean =
            pack != null && nowMs - pack.readAtMs in 0..MAX_AGE_MS

        /** Decides whether a new localhost read is due at [nowMs]. */
        internal fun shouldRefetch(lastAttemptMs: Long, nowMs: Long): Boolean =
            nowMs - lastAttemptMs !in 0 until MIN_REFETCH_MS
    }

    // @Volatile: the app's poll loop and the daemon thread never share an instance, but the
    // charging detector may read from another coroutine; a rare double fetch is harmless.
    @Volatile private var lastPack: PackReading? = null
    @Volatile private var lastAttemptMs: Long = 0L

    /**
     * Returns a fresh-enough pack reading, hitting localhost at most once per [MIN_REFETCH_MS].
     * Null means "no usable measurement" (di+ 1.x, di+ down, invalid values, or a stale cache).
     */
    open suspend fun readCached(nowMs: Long = System.currentTimeMillis()): PackReading? {
        if (shouldRefetch(lastAttemptMs, nowMs)) {
            lastAttemptMs = nowMs
            fetch(nowMs)?.let { lastPack = it }
        }
        return lastPack?.takeIf { isUsable(it, nowMs) }
    }

    private suspend fun fetch(nowMs: Long): PackReading? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                parse(body, nowMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: ${e.message}")
            null
        }
    }
}
