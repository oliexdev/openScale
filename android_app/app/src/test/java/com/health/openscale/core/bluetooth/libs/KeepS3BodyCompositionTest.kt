/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test

class KeepS3BodyCompositionTest {
    @Test
    fun firstCapture_matchesBundledNativeSdk() {
        val result = calculate(
            weightKg = 85.1f,
            impedance50 = 506,
            impedance100 = 478,
        )

        assertThat(result.bodyFatKg).isEqualTo(25.1f)
        assertThat(result.bodyFatPercent).isEqualTo(29.5f)
        assertThat(result.fatFreeMassKg).isEqualTo(60.0f)
        assertThat(result.waterPercent).isEqualTo(50.2f)
        assertThat(result.boneKg).isEqualTo(3.0f)
        assertThat(result.muscleKg).isEqualTo(57.0f)
        assertThat(result.musclePercent).isEqualTo(66.9f)
        assertThat(result.skeletalMuscleKg).isEqualTo(32.7f)
        assertThat(result.skeletalMusclePercent).isWithin(0.001f).of(38.42538f)
        assertThat(result.subcutaneousFatKg).isEqualTo(21.8f)
        assertThat(result.subcutaneousFatPercent).isEqualTo(25.7f)
        assertThat(result.proteinPercent).isEqualTo(12.7f)
        assertThat(result.visceralFatLevel).isEqualTo(12)
        assertThat(result.basalMetabolicRateKcal).isEqualTo(1791)
        assertThat(result.bodyAge).isEqualTo(28)
        assertThat(result.bmi22ReferenceWeightKg).isEqualTo(62.0f)
    }

    @Test
    fun secondCapture_matchesBundledNativeSdk() {
        val result = calculate(
            weightKg = 85.0f,
            impedance50 = 502,
            impedance100 = 475,
        )

        assertThat(result.bodyFatKg).isEqualTo(24.9f)
        assertThat(result.bodyFatPercent).isEqualTo(29.4f)
        assertThat(result.fatFreeMassKg).isEqualTo(60.1f)
        assertThat(result.waterPercent).isEqualTo(50.3f)
        assertThat(result.boneKg).isEqualTo(3.0f)
        assertThat(result.muscleKg).isEqualTo(57.1f)
        assertThat(result.musclePercent).isEqualTo(67.1f)
        assertThat(result.skeletalMuscleKg).isEqualTo(32.7f)
        assertThat(result.subcutaneousFatKg).isEqualTo(21.6f)
        assertThat(result.subcutaneousFatPercent).isEqualTo(25.5f)
        assertThat(result.proteinPercent).isEqualTo(12.7f)
        assertThat(result.visceralFatLevel).isEqualTo(12)
        assertThat(result.basalMetabolicRateKcal).isEqualTo(1790)
        assertThat(result.bodyAge).isEqualTo(28)
    }

    @Test
    fun femaleAthlete_matchesBundledNativeSdkBranches() {
        val result = calculate(
            gender = GenderType.FEMALE,
            age = 42,
            athlete = true,
            weightKg = 85.1f,
            impedance50 = 506,
            impedance100 = 478,
        )

        assertThat(result.bodyFatKg).isEqualTo(32.6f)
        assertThat(result.bodyFatPercent).isEqualTo(38.4f)
        assertThat(result.fatFreeMassKg).isEqualTo(52.5f)
        assertThat(result.waterPercent).isEqualTo(44.1f)
        assertThat(result.boneKg).isEqualTo(3.1f)
        assertThat(result.muscleKg).isEqualTo(49.4f)
        assertThat(result.musclePercent).isEqualTo(58.0f)
        assertThat(result.skeletalMuscleKg).isEqualTo(28.4f)
        assertThat(result.subcutaneousFatKg).isEqualTo(24.5f)
        assertThat(result.subcutaneousFatPercent).isEqualTo(28.9f)
        assertThat(result.proteinPercent).isEqualTo(10.3f)
        assertThat(result.visceralFatLevel).isEqualTo(7)
        assertThat(result.basalMetabolicRateKcal).isEqualTo(1481)
        assertThat(result.bodyAge).isEqualTo(44)
    }

    @Test
    fun invalidSdkInput_isRejected() {
        assertThat(
            KeepS3BodyComposition.calculate(
                KeepS3BodyComposition.Input(
                    gender = GenderType.MALE,
                    age = 26,
                    heightCm = 168,
                    weightKg = 85.1f,
                    impedance50Ohm = 199,
                    impedance100Ohm = 478,
                ),
            ),
        ).isNull()
    }

    @Test
    fun bmi22ReferenceWeight_usesHeightSquaredTimesPoint022() {
        val result = calculate(
            heightCm = 190,
            weightKg = 85.1f,
            impedance50 = 506,
            impedance100 = 478,
        )

        // The SDK truncates the fixed-point value before converting tenths of a kg.
        val expectedKg = (190 * 190 * 0.022f).toInt() / 10f
        assertThat(result.bmi22ReferenceWeightKg).isEqualTo(expectedKg)
        assertThat(result.bmi22ReferenceWeightKg).isEqualTo(79.4f)
    }

    @Test
    fun crossProfileBranches_matchBundledNativeSdk() {
        assertComposition(
            calculate(age = 30, heightCm = 190, weightKg = 60f, impedance50 = 506, impedance100 = 478),
            Expected(3.0f, 5.0f, 57.0f, 65.1f, 2.9f, 54.1f, 90.1f, 29.7f,
                2.7f, 4.5f, 19.8f, 1, 1365, 27, 79.4f),
        )
        assertComposition(
            calculate(age = 30, heightCm = 180, athlete = true, weightKg = 70f,
                impedance50 = 506, impedance100 = 478),
            Expected(8.7f, 12.5f, 61.3f, 60.0f, 3.1f, 58.2f, 83.1f, 32.2f,
                6.4f, 9.2f, 18.2f, 6, 1615, 30, 71.2f),
        )
        assertComposition(
            calculate(gender = GenderType.FEMALE, age = 55, heightCm = 160, weightKg = 50f,
                impedance50 = 506, impedance100 = 478),
            Expected(11.4f, 22.8f, 38.6f, 52.9f, 1.9f, 36.7f, 73.4f, 19.2f,
                10.1f, 20.2f, 16.2f, 4, 970, 53, 56.3f),
        )
        assertComposition(
            calculate(gender = GenderType.FEMALE, age = 30, heightCm = 150, weightKg = 45f,
                impedance50 = 506, impedance100 = 478),
            Expected(10.8f, 24.2f, 34.2f, 51.9f, 1.8f, 32.4f, 72.0f, 16.6f,
                10.1f, 22.5f, 15.7f, 2, 1078, 29, 49.5f),
        )
    }

    private fun calculate(
        gender: GenderType = GenderType.MALE,
        age: Int = 26,
        heightCm: Int = 168,
        athlete: Boolean = false,
        weightKg: Float,
        impedance50: Int,
        impedance100: Int,
    ): KeepS3BodyComposition.Result = checkNotNull(
        KeepS3BodyComposition.calculate(
            KeepS3BodyComposition.Input(
                gender = gender,
                age = age,
                heightCm = heightCm,
                weightKg = weightKg,
                impedance50Ohm = impedance50,
                impedance100Ohm = impedance100,
                athlete = athlete,
            ),
        ),
    )

    private fun assertComposition(result: KeepS3BodyComposition.Result, expected: Expected) {
        assertThat(result.bodyFatKg).isEqualTo(expected.fatKg)
        assertThat(result.bodyFatPercent).isEqualTo(expected.fatPercent)
        assertThat(result.fatFreeMassKg).isEqualTo(expected.ffmKg)
        assertThat(result.waterPercent).isEqualTo(expected.waterPercent)
        assertThat(result.boneKg).isEqualTo(expected.boneKg)
        assertThat(result.muscleKg).isEqualTo(expected.muscleKg)
        assertThat(result.musclePercent).isEqualTo(expected.musclePercent)
        assertThat(result.skeletalMuscleKg).isEqualTo(expected.skeletalKg)
        assertThat(result.subcutaneousFatKg).isEqualTo(expected.subcutaneousKg)
        assertThat(result.subcutaneousFatPercent).isEqualTo(expected.subcutaneousPercent)
        assertThat(result.proteinPercent).isEqualTo(expected.proteinPercent)
        assertThat(result.visceralFatLevel).isEqualTo(expected.visceral)
        assertThat(result.basalMetabolicRateKcal).isEqualTo(expected.bmr)
        assertThat(result.bodyAge).isEqualTo(expected.bodyAge)
        assertThat(result.bmi22ReferenceWeightKg).isEqualTo(expected.bmi22ReferenceWeightKg)
    }

    private data class Expected(
        val fatKg: Float,
        val fatPercent: Float,
        val ffmKg: Float,
        val waterPercent: Float,
        val boneKg: Float,
        val muscleKg: Float,
        val musclePercent: Float,
        val skeletalKg: Float,
        val subcutaneousKg: Float,
        val subcutaneousPercent: Float,
        val proteinPercent: Float,
        val visceral: Int,
        val bmr: Int,
        val bodyAge: Int,
        val bmi22ReferenceWeightKg: Float,
    )
}
