package com.bydmate.app.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Best-effort deep-links into head-unit system settings screens that the user
 * must toggle by hand (we cannot flip them programmatically without system
 * permission). Used by the advanced-features wizard so the whole setup is
 * doable on the tablet, no computer required.
 */
object HeadUnitSettings {

    private const val TAG = "HeadUnitSettings"

    /**
     * Opens network/Wi-Fi settings so the user can enable "Keep network on while
     * parked" (BYD DiLink keeps Wi-Fi alive past the ~9-minute park timeout).
     * Tries the BYD connectivity screen first, then standard wireless settings,
     * then the top-level Settings app. Returns true if anything opened.
     */
    fun openParkedNetworkSettings(context: Context): Boolean {
        val candidates = listOf(
            // BYD connectivity / network settings (varies by DiLink build).
            Intent(Intent.ACTION_MAIN).apply {
                setClassName("com.byd.setting", "com.byd.setting.network.NetworkActivity")
            },
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            val opened = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.isSuccess
            if (opened) return true
        }
        Log.w(TAG, "openParkedNetworkSettings: no settings activity resolved")
        return false
    }
}
