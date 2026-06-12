package com.bydmate.app.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Detects whether the head unit has restricted this app from running in the
 * background — on BYD DiLink this is **Settings → General → Disable background
 * Apps** (the `com.byd.appstartmanagement` screen). When enabled for our app,
 * the OS force-stops it aggressively and the telemetry gateway / survival daemon
 * supervisor cannot stay alive across parking events.
 *
 * The standard Android signal for "user restricted this app's background work"
 * is [ActivityManager.isBackgroundRestricted] (API 28+). BYD's App Start
 * Management toggles this flag, so we use it as the check. It never produces a
 * false positive (it is only true when the restriction is explicitly set), so a
 * warning shown from it is always actionable.
 */
object BackgroundRestriction {

    private const val TAG = "BackgroundRestriction"

    /** True when the app is background-restricted by the user / head unit. */
    fun isRestricted(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.isBackgroundRestricted ?: false
    } catch (e: Exception) {
        Log.w(TAG, "isRestricted check failed: ${e.message}")
        false
    }

    /**
     * Opens the DiLink **App Start Management** screen (where "Disable background
     * Apps" lives). Falls back to this app's standard App Info page if the BYD
     * activity is unavailable. Returns true when the BYD screen opened directly.
     */
    fun openBackgroundSettings(context: Context): Boolean {
        val openedByd = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(
                    "com.byd.appstartmanagement",
                    "com.byd.appstartmanagement.frame.AppStartManagement",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess
        if (!openedByd) {
            runCatching {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
        return openedByd
    }
}
