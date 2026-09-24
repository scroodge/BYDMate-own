package com.bydmate.app.util

import android.os.Build

/**
 * Head-unit generation checks. The unit identifies itself through the build product /
 * device name — on-car `getprop` of a 2024 Yuan Up: `ro.build.product = DiLink3.0`,
 * `ro.product.device = DiLink3.0`.
 */
object HeadUnitModel {

    fun isDiLink3(product: String? = Build.PRODUCT, device: String? = Build.DEVICE): Boolean =
        listOf(product, device).any { it.equals("DiLink3.0", ignoreCase = true) }

    /**
     * 2024 cars on DiLink 3.0 have no cabin temperature sensor on the di+ bus — the
     * value is absent or meaningless there, so the UI hides the cabin temperature
     * instead of showing a dash or a bogus number.
     */
    fun hasCabinTemp(product: String? = Build.PRODUCT, device: String? = Build.DEVICE): Boolean =
        !isDiLink3(product, device)
}
