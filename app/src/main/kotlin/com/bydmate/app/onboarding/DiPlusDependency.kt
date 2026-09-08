package com.bydmate.app.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DiPlusOnboardingState {
    data object Missing : DiPlusOnboardingState
    data class NeedsFirstLaunch(val versionCode: Long) : DiPlusOnboardingState
    data class Outdated(val versionCode: Long, val warningAccepted: Boolean) : DiPlusOnboardingState
    data class Ready(val versionCode: Long) : DiPlusOnboardingState
}

internal object DiPlusOnboardingPolicy {
    const val MIN_VERSION_CODE_FOR_V2_ENDPOINTS = 158L

    fun evaluate(
        installedVersionCode: Long?,
        firstLaunchConfirmed: Boolean,
        outdatedWarningAccepted: Boolean = false,
    ): DiPlusOnboardingState = when {
        installedVersionCode == null -> DiPlusOnboardingState.Missing
        !firstLaunchConfirmed -> DiPlusOnboardingState.NeedsFirstLaunch(installedVersionCode)
        installedVersionCode < MIN_VERSION_CODE_FOR_V2_ENDPOINTS ->
            DiPlusOnboardingState.Outdated(installedVersionCode, outdatedWarningAccepted)
        else -> DiPlusOnboardingState.Ready(installedVersionCode)
    }
}

@Singleton
class DiPlusDependency @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val PACKAGE_NAME = "com.van.diplus"
        const val INSTALL_PAGE_URL = "http://app.shafa.com/apk/diqianliyan.html"

        private const val PREFS_NAME = "diplus_onboarding"
        private const val KEY_CONFIRMED_VERSION = "confirmed_version"
        private const val KEY_WARNING_ACCEPTED_VERSION = "warning_accepted_version"
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun state(): DiPlusOnboardingState {
        val versionCode = installedVersionCode()
        return DiPlusOnboardingPolicy.evaluate(
            installedVersionCode = versionCode,
            firstLaunchConfirmed = versionCode != null &&
                preferences.getLong(KEY_CONFIRMED_VERSION, -1L) == versionCode,
            outdatedWarningAccepted = versionCode != null &&
                preferences.getLong(KEY_WARNING_ACCEPTED_VERSION, -1L) == versionCode,
        )
    }

    fun openDiPlus(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    fun confirmFirstLaunch(versionCode: Long) {
        preferences.edit().putLong(KEY_CONFIRMED_VERSION, versionCode).apply()
    }

    fun confirmOutdatedWarning(versionCode: Long) {
        preferences.edit().putLong(KEY_WARNING_ACCEPTED_VERSION, versionCode).apply()
    }

    fun openInstallPage(): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(INSTALL_PAGE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long? = try {
        val packageInfo = context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
