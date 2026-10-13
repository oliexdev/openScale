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
 * Unit tests for [MiScaleLib] — Holtek libBodyfat.so parity.
 *
 * Regression values computed from the reverse-engineered Holtek binary
 * (libBodyfat.so ARM64) with the corrected formulas:
 *   - Visceral fat: female else-branch uses -10.5 (not -age); male uses +63 in condition
 *   - Muscle: derived as weight - fatMass - boneMass (not Janssen BIA)
 *   - Body fat: clamp(5, 75) (not cap at 63→75)
 *   - Bone mass: weight-dependent clamping ranges (not fixed cap at 8.0)
 *   - BMR: Holtek impedance-inclusive formula (not Schofield)
 *   - Visceral fat: clamp(1, 50)
 */
class MiScaleLibTest {

    private val EPS = 1e-3f // general float tolerance

    // --- Simple BMI checks ----------------------------------------------------

    @Test
    fun bmi_isComputedCorrectly_forTypicalMale() {
        val lib = MiScaleLib(/* sex=male */ 1, /* age */ 30, /* height cm */ 180f)
        val weight = 80f

        val bmi = lib.getBMI(weight)

        assertThat(bmi).isWithin(EPS).of(24.691358f)
    }

    // --- Regression values for Holtek-corrected model -----------------------

    @Test
    fun regression_male_30y_180cm_80kg_imp500() {
        val lib = MiScaleLib(1, 30, 180f)
        val weight = 80f
        val r = Fixture(
            bmi = 24.691358f,
            bodyFat = 23.3151f,
            bone = 3.125421f,
            lbm = 58.222499f,
            muscleKg = 58.222498f,
            musclePct = 72.778125f,
            waterPct = 52.605841f,
            visceralFat = 10.799771f,
            bmr = 1438.8f
        )

        assertThat(lib.getBMI(weight)).isWithin(EPS).of(r.bmi)
        assertThat(lib.getBodyFat(weight, 500f)).isWithin(1e-3f).of(r.bodyFat)
        assertThat(lib.getBoneMass(weight, 500f)).isWithin(1e-3f).of(r.bone)
        assertThat(lib.getLBM(weight, 500f)).isWithin(1e-3f).of(r.lbm)
        val muscleKg = lib.getMuscle(weight, 500f)
        assertThat(muscleKg).isWithin(1e-3f).of(r.muscleKg)
        assertThat(muscleKg / weight * 100f).isWithin(1e-3f).of(r.musclePct)
        assertThat(lib.getWater(weight, 500f)).isWithin(1e-3f).of(r.waterPct)
        assertThat(lib.getVisceralFat(weight)).isWithin(1e-3f).of(r.visceralFat)
        assertThat(lib.getBMR(weight, 500f)).isWithin(1e-3f).of(r.bmr)
    }

    @Test
    fun regression_female_28y_165cm_60kg_imp520() {
        val lib = MiScaleLib(0, 28, 165f)
        val weight = 60f
        val r = Fixture(
            bmi = 22.038567f,
            bodyFat = 30.361992f,
            bone = 2.486581f,
            lbm = 39.296224f,
            muscleKg = 39.296280f,
            musclePct = 65.493707f,
            waterPct = 49.721538f,
            visceralFat = 1.0f,
            bmr = 1098.557f
        )

        assertThat(lib.getBMI(weight)).isWithin(EPS).of(r.bmi)
        assertThat(lib.getBodyFat(weight, 520f)).isWithin(1e-3f).of(r.bodyFat)
        assertThat(lib.getBoneMass(weight, 520f)).isWithin(1e-3f).of(r.bone)
        assertThat(lib.getLBM(weight, 520f)).isWithin(1e-3f).of(r.lbm)
        val muscleKg = lib.getMuscle(weight, 520f)
        assertThat(muscleKg).isWithin(1e-3f).of(r.muscleKg)
        assertThat(muscleKg / weight * 100f).isWithin(1e-3f).of(r.musclePct)
        assertThat(lib.getWater(weight, 520f)).isWithin(1e-3f).of(r.waterPct)
        assertThat(lib.getVisceralFat(weight)).isWithin(1e-3f).of(r.visceralFat)
        assertThat(lib.getBMR(weight, 520f)).isWithin(1e-3f).of(r.bmr)
    }

    @Test
    fun regression_male_45y_175cm_95kg_imp430() {
        val lib = MiScaleLib(1, 45, 175f)
        val weight = 95f
        val r = Fixture(
            bmi = 31.020408f,
            bodyFat = 32.417763f,
            bone = 3.272692f,
            lbm = 60.930433f,
            muscleKg = 60.930533f,
            musclePct = 64.137299f,
            waterPct = 48.253717f,
            visceralFat = 15.404758f,
            bmr = 1578.72f
        )

        assertThat(lib.getBMI(weight)).isWithin(EPS).of(r.bmi)
        assertThat(lib.getBodyFat(weight, 430f)).isWithin(1e-3f).of(r.bodyFat)
        assertThat(lib.getBoneMass(weight, 430f)).isWithin(1e-3f).of(r.bone)
        assertThat(lib.getLBM(weight, 430f)).isWithin(1e-3f).of(r.lbm)
        val muscleKg = lib.getMuscle(weight, 430f)
        assertThat(muscleKg).isWithin(1e-3f).of(r.muscleKg)
        assertThat(muscleKg / weight * 100f).isWithin(1e-3f).of(r.musclePct)
        assertThat(lib.getWater(weight, 430f)).isWithin(1e-3f).of(r.waterPct)
        assertThat(lib.getVisceralFat(weight)).isWithin(1e-3f).of(r.visceralFat)
        assertThat(lib.getBMR(weight, 430f)).isWithin(1e-3f).of(r.bmr)
    }

    // --- Holtek-specific behavioral tests ------------------------------------

    @Test
    fun muscle_derivedFromWeightFatBone_andIsClampedTo10_120kg() {
        // Holtek FF(): muscle = weight - fatMass - boneMass, clamped to [10, 120] kg
        val lib = MiScaleLib(1, 30, 180f)
        val weight = 80f
        val imp = 500f

        val fatMass = (lib.getBodyFat(weight, imp) * 0.01f) * weight
        val boneMass = lib.getBoneMass(weight, imp)
        val expectedMuscleKg = (weight - fatMass - boneMass).coerceIn(10f, 120f)

        val actual = lib.getMuscle(weight, imp)
        assertThat(actual).isWithin(1e-3f).of(expectedMuscleKg)
        assertThat(actual).isAtLeast(10f)
        assertThat(actual).isAtMost(120f)
    }

    @Test
    fun visceralFat_isClampedTo1_50() {
        // Holtek GG(): clamp(1, 50) — the female else-branch previously produced negative values
        val lib = MiScaleLib(0, 28, 165f)
        val weight = 60f

        val vfal = lib.getVisceralFat(weight)
        assertThat(vfal).isAtLeast(1.0f)
        assertThat(vfal).isAtMost(50.0f)
    }

    @Test
    fun visceralFat_maleCondition_includesPlus63() {
        // Holtek GG(): male condition is height < weight * 1.6 + 63 (not just weight * 1.6)
        // Male 30y, 175cm, 80kg: 80*1.6+63 = 191 > 175 → "if" branch (was "else" without +63)
        val lib = MiScaleLib(1, 30, 175f)
        val weight = 80f

        val subcalc = ((175f * 0.4f) - (175f * (175f * 0.0826f))) * -1f
        val expected = ((weight * 305f) / (subcalc + 48f)) - 2.9f + (30f * 0.15f)
        val clamped = expected.coerceIn(1f, 50f)

        assertThat(lib.getVisceralFat(weight)).isWithin(1e-3f).of(clamped)
    }

    @Test
    fun bodyFat_isClampedTo5_75() {
        // Holtek CC(): clamp(5, 75) — previously only capped at 63→75 with no minimum
        val lib = MiScaleLib(0, 50, 150f)
        val weight = 100f
        val imp = 700f

        val bf = lib.getBodyFat(weight, imp)
        assertThat(bf).isAtLeast(5.0f)
        assertThat(bf).isAtMost(75.0f)
    }

    @Test
    fun boneMass_usesWeightDependentRanges() {
        // Holtek DD(): female, weight < 45 → clamp(0.8, 2.8)
        val libFemale = MiScaleLib(0, 30, 160f)
        val boneFemale = libFemale.getBoneMass(40f, 500f)
        assertThat(boneFemale).isAtLeast(0.8f)
        assertThat(boneFemale).isAtMost(2.8f)

        // Holtek DD(): male, weight >= 60 → clamp(1.9, 3.9)
        val libMale = MiScaleLib(1, 30, 180f)
        val boneMale = libMale.getBoneMass(80f, 500f)
        assertThat(boneMale).isAtLeast(1.9f)
        assertThat(boneMale).isAtMost(3.9f)
    }

    @Test
    fun bmr_includesImpedance_andDiffersFromSchofield() {
        // Holtek BB(): BMR includes impedance in the formula
        val lib = MiScaleLib(1, 30, 180f)
        val weight = 80f

        // With different impedances, BMR should differ (Schofield would be constant)
        val bmrLowImp = lib.getBMR(weight, 200f)
        val bmrHighImp = lib.getBMR(weight, 1000f)

        assertThat(bmrLowImp).isNotWithin(1f).of(bmrHighImp)
        assertThat(bmrLowImp).isAtLeast(500f)
        assertThat(bmrHighImp).isAtMost(10000f)
    }

    @Test
    fun water_derivesFromBodyFat_andUsesCoeffBranch() {
        val lib = MiScaleLib(0, 50, 150f)
        val weight = 100f
        val imp = 700f

        val bf = lib.getBodyFat(weight, imp)
        val raw = (100f - bf) * 0.7f
        val coeff = if (raw < 50f) 1.02f else 0.98f
        val expected = (raw * coeff).coerceIn(35.0f, 75.0f)

        val water = lib.getWater(weight, imp)
        assertThat(water).isWithin(1e-3f).of(expected)
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
            lib.getVisceralFat(weight),
            lib.getBMR(weight, imp)
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
        val muscleKg: Float,
        val musclePct: Float,
        val waterPct: Float,
        val visceralFat: Float,
        val bmr: Float
    )
}
