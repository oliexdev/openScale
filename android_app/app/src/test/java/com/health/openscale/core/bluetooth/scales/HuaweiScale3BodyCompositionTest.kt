package com.health.openscale.core.bluetooth.scales

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HuaweiScale3BodyCompositionTest {
    @Test
    fun reproducesObservedMaleAdultMeasurements() {
        checkVector(
            heightCm = 178f,
            weightKg = 91.45f,
            ageYears = 46,
            sex = HuaweiScale3BodyComposition.Sex.MALE,
            impedanceOhm = 362.5,
            fatPercent = 25.341705f,
            waterPercent = 54.293285f,
            skeletalMuscleKg = 37.3210f,
            boneKg = 3.51146f,
            bmr = 1943f,
            visceral = 14f,
        )

        checkVector(
            heightCm = 178f,
            weightKg = 91.50f,
            ageYears = 46,
            sex = HuaweiScale3BodyComposition.Sex.MALE,
            impedanceOhm = 370.5,
            fatPercent = 25.73119f,
            waterPercent = 53.47523f,
            skeletalMuscleKg = 37.13919f,
            boneKg = 3.50382f,
            bmr = 1931f,
            visceral = 14f,
        )
    }

    @Test
    fun reproducesObservedFemaleAdultMeasurement() {
        // Independent black-box reference vector:
        // sex=female, age=40, height=165 cm, weight=65 kg, impedance=400 ohm.
        checkVector(
            heightCm = 165f,
            weightKg = 65f,
            ageYears = 40,
            sex = HuaweiScale3BodyComposition.Sex.FEMALE,
            impedanceOhm = 400.0,
            fatPercent = 30.9025f,
            waterPercent = 55.7124f,
            skeletalMuscleKg = 23.6117f,
            boneKg = 2.7600f,
            bmr = 1340f,
            visceral = 5f,
        )
    }

    @Test
    fun rejectsUnsupportedChildProfile() {
        assertNull(
            HuaweiScale3BodyComposition.calculate(
                heightCm = 150f,
                weightKg = 45f,
                ageYears = 17,
                sex = HuaweiScale3BodyComposition.Sex.MALE,
                impedanceOhm = 450.0,
            )
        )
    }

    @Test
    fun rejectsMalformedInputs() {
        assertNull(
            HuaweiScale3BodyComposition.calculate(
                heightCm = 178f,
                weightKg = 91f,
                ageYears = 46,
                sex = HuaweiScale3BodyComposition.Sex.MALE,
                impedanceOhm = 0.0,
            )
        )
        assertNull(
            HuaweiScale3BodyComposition.calculate(
                heightCm = 50f,
                weightKg = 91f,
                ageYears = 46,
                sex = HuaweiScale3BodyComposition.Sex.MALE,
                impedanceOhm = 360.0,
            )
        )
    }

    private fun checkVector(
        heightCm: Float,
        weightKg: Float,
        ageYears: Int,
        sex: HuaweiScale3BodyComposition.Sex,
        impedanceOhm: Double,
        fatPercent: Float,
        waterPercent: Float,
        skeletalMuscleKg: Float,
        boneKg: Float,
        bmr: Float,
        visceral: Float,
    ) {
        val result = HuaweiScale3BodyComposition.calculate(
            heightCm = heightCm,
            weightKg = weightKg,
            ageYears = ageYears,
            sex = sex,
            impedanceOhm = impedanceOhm,
        )
        assertNotNull(result)
        result!!

        assertEquals(fatPercent, result.bodyFatPercent, 0.01f)
        assertEquals(waterPercent, result.waterPercent, 0.01f)
        assertEquals(skeletalMuscleKg, result.skeletalMuscleKg, 0.01f)
        assertEquals(boneKg, result.boneMineralKg, 0.01f)
        assertEquals(bmr, result.bmrKcal, 1f)
        assertEquals(visceral, result.visceralFatLevel, 0f)

        // openScale stores skeletal muscle as percent, not kilograms.
        assertEquals(
            result.skeletalMuscleKg / weightKg * 100f,
            result.skeletalMusclePercent,
            0.001f,
        )
    }
}
