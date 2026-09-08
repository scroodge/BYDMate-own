package com.bydmate.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class DiPlusOnboardingPolicyTest {
    @Test
    fun `di plus absent blocks onboarding`() {
        assertEquals(
            DiPlusOnboardingState.Missing,
            DiPlusOnboardingPolicy.evaluate(
                installedVersionCode = null,
                firstLaunchConfirmed = false,
            ),
        )
    }

    @Test
    fun `di plus below 158 reports unavailable v2 endpoints`() {
        assertEquals(
            DiPlusOnboardingState.Outdated(versionCode = 157, warningAccepted = false),
            DiPlusOnboardingPolicy.evaluate(
                installedVersionCode = 157,
                firstLaunchConfirmed = true,
            ),
        )
    }

    @Test
    fun `di plus at 158 or above is ready`() {
        assertEquals(
            DiPlusOnboardingState.Ready(versionCode = 158),
            DiPlusOnboardingPolicy.evaluate(
                installedVersionCode = 158,
                firstLaunchConfirmed = true,
            ),
        )
        assertEquals(
            DiPlusOnboardingState.Ready(versionCode = 200),
            DiPlusOnboardingPolicy.evaluate(
                installedVersionCode = 200,
                firstLaunchConfirmed = true,
            ),
        )
    }

    @Test
    fun `installed di plus must be opened once before onboarding continues`() {
        assertEquals(
            DiPlusOnboardingState.NeedsFirstLaunch(versionCode = 158),
            DiPlusOnboardingPolicy.evaluate(
                installedVersionCode = 158,
                firstLaunchConfirmed = false,
            ),
        )
    }
}
