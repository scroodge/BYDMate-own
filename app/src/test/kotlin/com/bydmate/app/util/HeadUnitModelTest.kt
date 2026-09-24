package com.bydmate.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitModelTest {

    @Test fun `DiLink 3_0 product hides cabin temp`() {
        // Values from on-car getprop of a 2024 Yuan Up.
        assertTrue(HeadUnitModel.isDiLink3(product = "DiLink3.0", device = "DiLink3.0"))
        assertFalse(HeadUnitModel.hasCabinTemp(product = "DiLink3.0", device = "DiLink3.0"))
    }

    @Test fun `either field is enough`() {
        assertTrue(HeadUnitModel.isDiLink3(product = null, device = "dilink3.0"))
    }

    @Test fun `other head units keep cabin temp`() {
        assertTrue(HeadUnitModel.hasCabinTemp(product = "DiLink5.0", device = "DiLink5.0"))
        assertTrue(HeadUnitModel.hasCabinTemp(product = null, device = null))
    }
}
