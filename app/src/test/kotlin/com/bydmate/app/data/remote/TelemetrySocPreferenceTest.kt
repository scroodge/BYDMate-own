package com.bydmate.app.data.remote

import com.bydmate.app.domain.CloudSocPreference
import com.bydmate.app.domain.SocScaleCalibration
import com.bydmate.app.domain.SocSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetrySocPreferenceTest {

    @Test
    fun `default preference keeps Di+ first`() {
        val r = resolveTelemetrySoc(diPlusSoc = 70, autoserviceSocPercent = 68f)
        assertEquals(ResolvedSoc(70, SocSource.DIPLUS), r)
    }

    @Test
    fun `autoservice-first takes the car's SOC even when Di+ has one`() {
        val r = resolveTelemetrySoc(70, 68f, preference = CloudSocPreference.AUTOSERVICE_FIRST)
        assertEquals(ResolvedSoc(68, SocSource.AUTOSERVICE), r)
    }

    @Test
    fun `autoservice-first is not converted onto the raw scale`() {
        // A real per-car calibration would turn display 68 into raw ~68.1 -> 68; use a steep
        // one so a conversion is unmistakable. The car's own number must come back untouched.
        val steep = SocScaleCalibration(slope = 2.0, intercept = 0.0)
        val r = resolveTelemetrySoc(70, 68f, steep, CloudSocPreference.AUTOSERVICE_FIRST)
        assertEquals(ResolvedSoc(68, SocSource.AUTOSERVICE), r)
    }

    @Test
    fun `autoservice-first falls back to Di+ when the car gives no SOC`() {
        assertEquals(
            ResolvedSoc(70, SocSource.DIPLUS),
            resolveTelemetrySoc(70, null, preference = CloudSocPreference.AUTOSERVICE_FIRST),
        )
        // Sentinel / out-of-range autoservice reads count as "no SOC".
        assertEquals(
            ResolvedSoc(70, SocSource.DIPLUS),
            resolveTelemetrySoc(70, -1f, preference = CloudSocPreference.AUTOSERVICE_FIRST),
        )
    }

    @Test
    fun `autoservice-first with nothing available resolves to no SOC`() {
        val r = resolveTelemetrySoc(null, null, preference = CloudSocPreference.AUTOSERVICE_FIRST)
        assertNull(r.percent)
        assertNull(r.source)
    }

    @Test
    fun `Di+-first still falls back to autoservice`() {
        assertEquals(ResolvedSoc(68, SocSource.AUTOSERVICE), resolveTelemetrySoc(null, 68f))
    }

    @Test
    fun `wire parsing defaults on anything unknown`() {
        assertEquals(CloudSocPreference.AUTOSERVICE_FIRST, CloudSocPreference.fromWire(" autoservice "))
        assertEquals(CloudSocPreference.DIPLUS_FIRST, CloudSocPreference.fromWire("diplus"))
        assertEquals(CloudSocPreference.DIPLUS_FIRST, CloudSocPreference.fromWire(null))
        assertEquals(CloudSocPreference.DIPLUS_FIRST, CloudSocPreference.fromWire("garbage"))
    }
}
