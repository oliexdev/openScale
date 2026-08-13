/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [MiScaleLib] (current implementation).
 *
 * - Three regression fixtures use the exact outputs printed from the current code
 *   to guard against accidental changes.
 * - Behavioral tests verify important branches without brittle hard-coded numbers.
 */
class MiScaleLibTest {

    private val EPS = 1e-3f // general float tolerance

    // --- Simple BMI checks ----------------------------------------------------

    @Test
    fun bmi_isComputedCorrectly_forTypicalMale() {
        // Given
        val lib = MiScaleLib(/* sex=male */ 1, /* age */ 30, /* height cm */ 180f)
        val weight = 80f

        // When
        val bmi = lib.getBMI(weight)

        // Then: BMI = weight / (height_m^2) = 80 / (1.8 * 1.8) = 24.691...
        assertThat(bmi).isWithin(EPS).of(24.691358f)
    }

    @Test
    fun bmi_monotonicity_weightUp_heightSame_increases() {
        val lib = MiScaleLib(0, 28, 165f)
        val bmi1 = lib.getBMI(60f)
        val bmi2 = lib.getBMI(65f)
        assertThat(bmi2).isGreaterThan(bmi1)
    }

    @Test
    fun bmi_monotonicity_heightUp_weightSame_decreases() {
        val libShort = MiScaleLib(1, 35, 170f)
        val libTall  = MiScaleLib(1, 35, 185f)
        val weight = 80f
        assertThat(libTall.getBMI(weight)).isLessThan(libShort.getBMI(weight))
    }

    // --- Regression values for full model (from your dumps) ------------------

    @Test
    fun regression_male_30y_180cm_80kg_imp500() {
        val lib = MiScaleLib(1, 30, 180f)
        val weight = 80f
        val r = Fixture(
            bmi = 24.691359f,
            bodyFat = 23.315107f,
            bone = 3.1254203f,
            lbm = 58.222496f,
            musclePct = 40.977253f,
            waterPct = 52.605835f,
            visceralFat = 13.359997f
        )

        assertThat(lib.getBMI(weight)).isWithin(EPS).of(r.bmi)
        assertThat(lib.getBodyFat(weight, 500f)).isWithin(1e-3f).of(r.bodyFat)
        assertThat(lib.getBoneMass(weight, 500f)).isWithin(1e-3f).of(r.bone)
        assertThat(lib.getLBM(weight, 500f)).isWithin(1e-3f).of(r.lbm)
        assertThat(lib.getMuscle(weight, 500f)).isWithin(1e-3f).of(r.musclePct)
        assertThat(lib.getWater(weight, 500f)).isWithin(1e-3f).of(r.waterPct)
        assertThat(lib.getVisceralFat(weight)).isWithin(1e-3f).of(r.visceralFat)
    }

    @Test
    fun regression_female_28y_165cm_60kg_imp520() {
        val lib = MiScaleLib(0, 28, 165f)
        val weight = 60f
        val r = Fixture(
            bmi = 22.038567f,
            bodyFat = 30.361998f,
            bone = 2.4865808f,
            lbm = 39.29622f,
            musclePct = 40.181103f,
            waterPct = 49.72153f,
            visceralFat = -36.555004f
        )

        assertThat(lib.getBMI(weight)).isWithin(EPS).of(r.bmi)
        assertThat(lib.getBodyFat(weight, 520f)).isWithin(1e-3f).of(r.bodyFat)
        assertThat(lib.getBoneMass(weight, 520f)).isWithin(1e-3f).of(r.bone)
        assertThat(lib.getLBM(weight, 520f)).isWithin(1e-3f).of(r.lbm)
        assertThat(lib.getMuscle(weight, 520f)).isWithin(1e-3f).of(r.musclePct)
        assertThat(lib.getWater(weight, 520f)).isWithin(1e-3f).of(r.waterPct)
        assertThat(lib.getVisceralFat(weight)).isWithin(1e-3f).of(r.visceralFat)
    }

    @Test
    fun regression_male_45y_175cm_95kg_imp430() {
        val lib = MiScaleLib(1, 45, 175f)
        val weight = 95f
        val r = Fixture(
            bmi = 31.020409f,
            bodyFat = 32.41778f,
            bone = 3.2726917f,
            lbm = 60.93042f,
            musclePct = 36.096416f,
            waterPct = 48.2537f,
            visceralFat = 24.462498f
        )

        assertThat(lib.getBMI(weight)).isWithin(EPS).of(r.bmi)
        assertThat(lib.getBodyFat(weight, 430f)).isWithin(1e-3f).of(r.bodyFat)
        assertThat(lib.getBoneMass(weight, 430f)).isWithin(1e-3f).of(r.bone)
        assertThat(lib.getLBM(weight, 430f)).isWithin(1e-3f).of(r.lbm)
        assertThat(lib.getMuscle(weight, 430f)).isWithin(1e-3f).of(r.musclePct)
        assertThat(lib.getWater(weight, 430f)).isWithin(1e-3f).of(r.waterPct)
        assertThat(lib.getVisceralFat(weight)).isWithin(1e-3f).of(r.visceralFat)
    }

    // --- Special paths & edge behavior --------------------------------------

    @Test
    fun muscle_fallback_whenImpedanceZero_usesLbmRatio_andIsClamped() {
        // Female, impedance=0 triggers fallback path (LBM * 0.46) → % of weight → clamp 10..60
        val lib = MiScaleLib(0, 52, 160f)
        val weight = 48f

        // Compute expected via the same path the code uses (behavioral property, not magic number)
        val lbm = lib.getLBM(weight, /* impedance */ 0f)
        val expectedPct = (lbm * 0.46f) / weight * 100f
        val expectedClamped = expectedPct.coerceIn(10f, 60f)

        val actual = lib.getMuscle(weight, /* impedance */ 0f)
        assertThat(actual).isWithin(1e-3f).of(expectedClamped)
        assertThat(actual).isAtLeast(10f)
        assertThat(actual).isAtMost(60f)
    }

    @Test
    fun muscle_percentage_isClampedAt60_whenExtremelyHigh() {
        // Construct params that blow up SMM/weight; expect clamp to 60%
        val lib = MiScaleLib(1, 20, 190f)
        val clamped = lib.getMuscle(/* weight */ 40f, /* very low impedance */ 50f)
        assertThat(clamped).isWithin(EPS).of(60f)
    }

    @Test
    fun water_derivesFromBodyFat_andUsesCoeffBranch() {
        // Check: water = ((100 - BF) * 0.7) * coeff, coeff = 1.02 if <50 else 0.98
        val lib = MiScaleLib(0, 50, 150f)
        val weight = 100f
        val imp = 700f

        val bf = lib.getBodyFat(weight, imp)
        val raw = (100f - bf) * 0.7f
        val coeff = if (raw < 50f) 1.02f else 0.98f
        val expected = raw * coeff

        val water = lib.getWater(weight, imp)
        assertThat(water).isWithin(1e-3f).of(expected)
        if (raw < 50f) {
            assertThat(water).isLessThan(50f)
        } else {
            assertThat(water).isGreaterThan(50f)
        }
    }

    @Test
    fun outputs_areFinite_forTypicalInputs() {
        val lib = MiScaleLib(1, 30, 180f)
        val weight = 80f
        val imp = 500f

        val nums = listOf(
            lib.getBMI(weight),
            lib.getBodyFat(weight, imp),
            lib.getBoneMass(weight, imp),
            lib.getLBM(weight, imp),
            lib.getMuscle(weight, imp),
            lib.getWater(weight, imp),
            lib.getVisceralFat(weight)
        )
        nums.forEach { v ->
            assertThat(v.isNaN()).isFalse()
            assertThat(v.isInfinite()).isFalse()
        }
    }

    private data class Fixture(
        val bmi: Float,
        val bodyFat: Float,
        val bone: Float,
        val lbm: Float,
        val musclePct: Float,
        val waterPct: Float,
        val visceralFat: Float
    )
}
