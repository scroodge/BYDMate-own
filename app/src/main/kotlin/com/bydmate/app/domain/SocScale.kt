package com.bydmate.app.domain

import kotlin.math.roundToInt

/**
 * The car reports SOC on **two different scales**, and they are not interchangeable.
 *
 * - **Raw BMS SOC** — what di+ 2.0 serves as `电量百分比` (0.1 % resolution; di+'s own
 *   `/api/historyStatus` calls it `socPermille` / `socPrecise`). This is the BMS's own
 *   state variable.
 * - **Display SOC** — what `autoservice getFloat(1014, FID_SOC)` returns: whole percent,
 *   and the value the instrument cluster shows. It is the raw value rescaled onto the
 *   usable window and clamped, so it reads 100 while the pack is still balancing at
 *   raw 99.4, and reads 0 with ~2 % raw still in the pack.
 *
 * Measured on 2026-08-26 against paired cloud samples (`diplus_soc` vs
 * `autoservice_soc_percent`), the two are related by an affine map, **per vehicle**:
 *
 * | car | display = slope × raw + intercept | usable window (raw) | paired samples |
 * |---|---|---|---|
 * | `way` | `1.0280 × raw − 2.246` | 2.18 – 99.46 % | 414 |
 * | `yuan up` | `1.0178 × raw − 0.519` | 0.51 – 98.77 % | 958 |
 *
 * Two more cars (`BYE Yuan Up`, `BYD`) showed **zero** divergence across 2703 samples —
 * both still run di+ 1.x, which reported the display value itself. The split therefore
 * tracks the di+ version, not the model: a car diverges the moment it moves to di+ 2.0.
 *
 * The slopes differ per car, so there is no fleet-wide constant. [IDENTITY] is the
 * default precisely because it preserves today's behaviour: correct for di+ 1.x cars,
 * and no worse than the previous silent mixing for di+ 2.0 cars. Populate a real
 * calibration per vehicle once `soc_source` has been in the field long enough to fit
 * one — see `docs/DIPLUS_DATA.md`, "The two SOC scales".
 */
data class SocScaleCalibration(
    /** display = slope × raw + intercept. */
    val slope: Double,
    val intercept: Double,
) {
    init {
        require(slope > 0.0) { "slope must be positive, was $slope" }
    }

    /** Display-scale percent → raw BMS percent. */
    fun displayToRaw(displayPercent: Double): Double = (displayPercent - intercept) / slope

    /** Raw BMS percent → display-scale percent. */
    fun rawToDisplay(rawPercent: Double): Double = slope * rawPercent + intercept

    /** True when this calibration is a no-op, i.e. the two scales coincide (di+ 1.x). */
    val isIdentity: Boolean get() = slope == 1.0 && intercept == 0.0

    companion object {
        /**
         * The two scales coincide. Correct for cars on di+ 1.x; a placeholder on di+ 2.0
         * cars until a per-vehicle calibration is fitted.
         */
        val IDENTITY = SocScaleCalibration(slope = 1.0, intercept = 0.0)
    }
}

/** Which of the two scales a given SOC reading came from. */
enum class SocSource(val wireName: String) {
    /** di+ `电量百分比`. Raw BMS scale on di+ 2.0, display scale on di+ 1.x. */
    DIPLUS("diplus"),

    /** autoservice `getFloat(1014, FID_SOC)`. Always the display scale. */
    AUTOSERVICE("autoservice"),
}

/**
 * Converts an autoservice (display-scale) reading onto the raw scale so it can be
 * compared with, or substituted for, a di+ reading. Returns null for sentinels and
 * out-of-range values.
 */
fun SocScaleCalibration.autoserviceToRawPercent(displayPercent: Float?): Int? =
    displayPercent
        ?.takeIf { it.isFinite() && it in 0f..100f }
        ?.let { displayToRaw(it.toDouble()) }
        ?.takeIf { it.isFinite() }
        ?.roundToInt()
        ?.coerceIn(0, 100)
