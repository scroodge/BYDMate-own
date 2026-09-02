package com.bydmate.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingStateClassifierTest {

    @Test
    fun `production regression explicit gun 1 with positive charging status and zero power is not charging`() {
        assertFalse(
            ChargingStateClassifier.isCharging(
                autoserviceGun = 1,
                diPlusGun = 1,
                chargingStatus = 1,
            )
        )
    }

    @Test
    fun `positive charging status is fallback when no gun is known`() {
        assertTrue(
            ChargingStateClassifier.isCharging(
                autoserviceGun = null,
                diPlusGun = null,
                chargingStatus = 1,
            )
        )
    }

    @Test
    fun `autoservice gun wins when DiPlus gun disagrees`() {
        assertFalse(
            ChargingStateClassifier.isCharging(
                autoserviceGun = 1,
                diPlusGun = 2,
                chargingStatus = 1,
            )
        )
        assertTrue(
            ChargingStateClassifier.isCharging(
                autoserviceGun = 2,
                diPlusGun = 1,
                chargingStatus = 0,
            )
        )
    }

    @Test
    fun `gun states 2 through 5 are charging`() {
        (2..5).forEach { gun ->
            assertTrue(
                "gun state $gun",
                ChargingStateClassifier.isCharging(
                    autoserviceGun = gun,
                    diPlusGun = null,
                    chargingStatus = 0,
                )
            )
        }
    }
}
