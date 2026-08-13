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
 * Unit tests for [TrisaBodyAnalyzeLib].
 *
 * - Regression fixtures use outputs computed from the current formulas
 *   to guard against accidental changes.
 * - Behavioral tests verify key monotonicity/branch properties.
 */
class TrisaBodyAnalyzeLibTest {

    private val EPS = 1e-3f // general float tolerance

    // --- Simple BMI checks ----------------------------------------------------

    @Test
    fun bmi_isComputedCorrectly_forTypicalMale() {
        val lib = TrisaBodyAnalyzeLib(1, 30, 180f)
        val weight = 80f

        val bmi = lib.getBMI(weight)

        assertThat(bmi).isWithin(EPS).of(24.691358f)
    }

    @Test
    fun bmi_monotonicity_weightUp_heightSame_increases() {
        val lib = TrisaBodyAnalyzeLib(0, 28, 165f)
        val bmi1 = lib.getBMI(60f)
        val bmi2 = lib.getBMI(65f)
        assertThat(bmi2).isGreaterThan(bmi1)
    }

    @Test
    fun bmi_monotonicity_heightUp_weightSame_decreases() {
        val shorty = TrisaBodyAnalyzeLib(1, 35, 170f)
        val tall   = TrisaBodyAnalyzeLib(1, 35, 185f)
        val weight = 80f
        assertThat(tall.getBMI(weight)).isLessThan(shorty.getBMI(weight))
    }

    // --- Behavioral properties -----------------------------------------------

    @Test
    fun impedance_effects_haveExpectedDirections() {
        val male = TrisaBodyAnalyzeLib(1, 30, 180f)
        val female = TrisaBodyAnalyzeLib(0, 30, 165f)

        val w = 70f
        val impLow = 300f
        val impHigh = 700f

        assertThat(male.getWater(w, impHigh)).isLessThan(male.getWater(w, impLow))
        assertThat(male.getMuscle(w, impHigh)).isLessThan(male.getMuscle(w, impLow))
        assertThat(male.getBone(w, impHigh)).isLessThan(male.getBone(w, impLow))
        assertThat(male.getFat(w, impHigh)).isGreaterThan(male.getFat(w, impLow))

        assertThat(female.getWater(w, impHigh)).isLessThan(female.getWater(w, impLow))
        assertThat(female.getMuscle(w, impHigh)).isLessThan(female.getMuscle(w, impLow))
        assertThat(female.getBone(w, impHigh)).isLessThan(female.getBone(w, impLow))
        assertThat(female.getFat(w, impHigh)).isGreaterThan(female.getFat(w, impLow))
    }

    @Test
    fun sex_flag_changes_branch_outputs() {
        val male = TrisaBodyAnalyzeLib(1, 30, 175f)
        val female = TrisaBodyAnalyzeLib(0, 30, 175f)
        val w = 70f
        val imp = 500f

        assertThat(male.getWater(w, imp)).isNotEqualTo(female.getWater(w, imp))
        assertThat(male.getFat(w, imp)).isNotEqualTo(female.getFat(w, imp))
        assertThat(male.getMuscle(w, imp)).isNotEqualTo(female.getMuscle(w, imp))
        assertThat(male.getBone(w, imp)).isNotEqualTo(female.getBone(w, imp))
    }

    @Test
    fun outputs_areFinite_forTypicalInputs() {
        val lib = TrisaBodyAnalyzeLib(1, 30, 180f)
        val w = 80f
        val imp = 500f

        val nums = listOf(
            lib.getBMI(w),
            lib.getWater(w, imp),
            lib.getFat(w, imp),
            lib.getMuscle(w, imp),
            lib.getBone(w, imp)
        )

        nums.forEach { v ->
            assertThat(v.isNaN()).isFalse()
            assertThat(v.isInfinite()).isFalse()
        }
    }

    // --- Regression fixtures -------------------------------------------------

    @Test
    fun regression_male_30y_180cm_80kg_imp500() {
        val lib = TrisaBodyAnalyzeLib(1, 30, 180f)
        val w = 80f
        val imp = 500f
        val r = Fixture(
            bmi = 24.691359f,
            water = 57.031845f,
            fat = 23.186619f,
            muscle = 40.767307f,
            bone = 4.254889f
        )
        checkFixture(lib, w, imp, r)
    }

    @Test
    fun regression_female_28y_165cm_60kg_imp520() {
        val lib = TrisaBodyAnalyzeLib(0, 28, 165f)
        val w = 60f
        val imp = 520f
        val r = Fixture(
            bmi = 22.038567f,
            water = 51.246567f,
            fat = 27.63467f,
            muscle = 32.776436f,
            bone = 4.575968f
        )
        checkFixture(lib, w, imp, r)
    }

    @Test
    fun regression_male_45y_175cm_95kg_imp430() {
        val lib = TrisaBodyAnalyzeLib(1, 45, 175f)
        val w = 95f
        val imp = 430f
        val r = Fixture(
            bmi = 31.020409f,
            water = 51.385693f,
            fat = 34.484245f,
            muscle = 30.524948f,
            bone = 3.1716952f
        )
        checkFixture(lib, w, imp, r)
    }

    @Test
    fun regression_female_55y_160cm_50kg_imp600() {
        val lib = TrisaBodyAnalyzeLib(0, 55, 160f)
        val w = 50f
        val imp = 600f
        val r = Fixture(
            bmi = 19.53125f,
            water = 55.407524f,
            fat = 26.659752f,
            muscle = 27.356312f,
            bone = 3.8092093f
        )
        checkFixture(lib, w, imp, r)
    }

    @Test
    fun regression_male_20y_190cm_65kg_imp480() {
        val lib = TrisaBodyAnalyzeLib(1, 20, 190f)
        val w = 65f
        val imp = 480f
        val r = Fixture(
            bmi = 18.00554f,
            water = 64.203964f,
            fat = 10.668964f,
            muscle = 49.972504f,
            bone = 5.2273664f
        )
        checkFixture(lib, w, imp, r)
    }

    @Test
    fun regression_female_22y_155cm_55kg_imp510() {
        val lib = TrisaBodyAnalyzeLib(0, 22, 155f)
        val w = 55f
        val imp = 510f
        val r = Fixture(
            bmi = 22.89282f,
            water = 49.936302f,
            fat = 28.405312f,
            muscle = 33.747982f,
            bone = 4.713689f
        )
        checkFixture(lib, w, imp, r)
    }

    @Test
    fun regression_male_35y_175cm_85kg_imp200() {
        val lib = TrisaBodyAnalyzeLib(1, 35, 175f)
        val w = 85f
        val imp = 200f
        val r = Fixture(
            bmi = 27.755102f,
            water = 56.290474f,
            fat = 25.228241f,
            muscle = 38.142612f,
            bone = 3.9760387f
        )
        checkFixture(lib, w, imp, r)
    }

    @Test
    fun regression_female_40y_170cm_70kg_imp800() {
        val lib = TrisaBodyAnalyzeLib(0, 40, 170f)
        val w = 70f
        val imp = 800f
        val r = Fixture(
            bmi = 24.221453f,
            water = 47.909973f,
            fat = 35.216103f,
            muscle = 27.238312f,
            bone = 3.7960525f
        )
        checkFixture(lib, w, imp, r)
    }

    // --- Helper --------------------------------------------------------------

    private fun checkFixture(lib: TrisaBodyAnalyzeLib, w: Float, imp: Float, r: Fixture) {
        assertThat(lib.getBMI(w)).isWithin(EPS).of(r.bmi)
        assertThat(lib.getWater(w, imp)).isWithin(EPS).of(r.water)
        assertThat(lib.getFat(w, imp)).isWithin(EPS).of(r.fat)
        assertThat(lib.getMuscle(w, imp)).isWithin(EPS).of(r.muscle)
        assertThat(lib.getBone(w, imp)).isWithin(EPS).of(r.bone)
    }

    private data class Fixture(
        val bmi: Float,
        val water: Float,
        val fat: Float,
        val muscle: Float,
        val bone: Float
    )
}
