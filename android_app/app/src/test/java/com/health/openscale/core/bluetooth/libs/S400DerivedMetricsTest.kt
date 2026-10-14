package com.health.openscale.core.bluetooth.libs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S400DerivedMetricsTest {
    @Test
    fun recordedCalculationResponses() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/s400/derived-v235.tsv"))
        val checked = resource.bufferedReader().useLines { verifyS400DerivedMetricsFixture(it) }
        assertEquals(276, checked)
    }

    @Test
    fun missingComponentsRemainMissing() {
        val r = S400DerivedMetrics.compute(185f, 87f, true, S400DerivedMetrics.Components(23.7f))
        assertNull(r.boneKg)
        assertNull(r.slmKg)
        assertNull(r.slmPct)
        assertNull(r.waterKg)
        assertNull(r.waterPct)
        assertNull(r.skeletalMuscleKg)
        assertNull(r.skeletalMusclePct)
        assertEquals(66.4f, r.ffmKg, 0.0001f)
        assertEquals(1804f, r.bmrKcal, 0f)
    }

    @Test
    fun displayedWaterPercentageIsPrimary() {
        val r = S400DerivedMetrics.compute(160f, 40f, false,
            S400DerivedMetrics.Components(bodyFatPct = 10f, waterPct = 83.3f))
        assertEquals(83.3f, r.waterPct!!, 0.0001f)
        assertEquals(33.3f, r.waterKg!!, 0.0001f)
    }

    @Test
    fun fractionalSkeletalClampIsPreserved() {
        val r = S400DerivedMetrics.compute(153f, 91f, false,
            S400DerivedMetrics.Components(bodyFatPct = 51.2f, boneKg = 2.5f,
                waterPct = 30.3f, skeletalMuscleKg = 18.855f))
        assertEquals(18.855f, r.skeletalMuscleKg!!, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidHeight() {
        S400DerivedMetrics.compute(Float.NaN, 87f, true, S400DerivedMetrics.Components(23.7f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidWeight() {
        S400DerivedMetrics.compute(185f, 0f, true, S400DerivedMetrics.Components(23.7f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidFatPercentage() {
        S400DerivedMetrics.compute(185f, 87f, true, S400DerivedMetrics.Components(101f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedFixture() {
        verifyS400DerivedMetricsFixture(sequenceOf("incomplete\t185"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsEmptyFixture() {
        verifyS400DerivedMetricsFixture(emptySequence())
    }
}
