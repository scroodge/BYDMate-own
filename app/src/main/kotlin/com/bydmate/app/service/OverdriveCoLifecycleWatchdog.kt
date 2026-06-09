package com.bydmate.app.service

import android.app.ActivityManager
import android.content.Context
import com.bydmate.app.data.repository.SettingsRepository

object OverdriveCoLifecycleWatchdog {

    fun isOverdriveProvider(provider: String): Boolean =
        provider == SettingsRepository.KEEP_ALIVE_PROVIDER_OVERDRIVE

    fun isDiPlusProvider(provider: String): Boolean =
        provider == SettingsRepository.KEEP_ALIVE_PROVIDER_DIPLUS

    fun shouldRelaunchDiPlus(provider: String): Boolean = isDiPlusProvider(provider)

    /**
     * True when com.overdrive.app or its shell daemons are in the process list.
     */
    fun isOverdriveProcessAlive(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = am.runningAppProcesses ?: return false
        return processes.any { proc ->
            val name = proc.processName
            name.contains("overdrive", ignoreCase = true) ||
                name.contains("AccSentry", ignoreCase = true) ||
                name.contains("SentryDaemon", ignoreCase = true)
        }
    }

    fun isDiPlusShellAlive(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A 2>/dev/null"))
            proc.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().any { line ->
                    line.contains("aps_diplus") ||
                        line.contains("com.van.diplus")
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
