/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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
 * Unit tests for [Wla25BodyComposition].
 *
 * The class documents three rounding details as "load-bearing … each worth a tenth of a
 * unit", and two exact values that pin the rounding mode down. Those are asserted here so
 * the behaviour cannot drift silently:
 *
 *  - `round1(26.35)` is **26.4**; half-to-even would give 26.3.
 *  - `round1(1.95)` is **1.9**; 1.95 has a float32 fraction of exactly 0.95, so the half-up
 *    test fails and the value rounds down.
 *
 * `round1` is private, but [Wla25BodyComposition.compute] rounds the incoming weight with it
 * before anything else and reports the result in [Wla25BodyComposition.Result.weightKg], so
 * the two cases can be asserted through the public API without relaxing its visibility.
 *
 * The remaining tests cover the documented validity gate and the clamps, which are stated in
 * the source as invariants rather than derived from vendor output — so they hold regardless
 * of the regression constants.
 */
class Wla25BodyCompositionTest {

    /**
     * Impedances that satisfy the gate: slots 0 and 5 carry the small leading value of each
     * group (~15-25 Ω), the other eight are the large ones (~300 Ω).
     */
    private fun validImpedances() = doubleArrayOf(
        20.0, 300.0, 320.0, 310.0, 305.0,
        18.0, 300.0, 315.0, 308.0, 302.0,
    )

    // --- the validity gate ---------------------------------------------------

    @Test
    fun `accepts a well-formed impedance set`() {
        assertThat(Wla25BodyComposition.impedancesValid(validImpedances())).isTrue()
    }

    @Test
    fun `rejects an array that is not exactly ten values`() {
        assertThat(Wla25BodyComposition.impedancesValid(DoubleArray(9) { 300.0 })).isFalse()
        assertThat(Wla25BodyComposition.impedancesValid(DoubleArray(11) { 300.0 })).isFalse()
        assertThat(Wla25BodyComposition.impedancesValid(DoubleArray(0))).isFalse()
    }

    @Test
    fun `leading slots are checked against one, not against a hundred`() {
        // 20 Ω in slot 0 or 5 is normal and must pass; the same value in any other slot
        // is below that slot's 100 Ω floor and must fail. This asymmetry is what pins the
        // ordering of the ten values, so it is worth guarding explicitly.
        val lowLeading = validImpedances().also { it[0] = 1.0; it[5] = 1.0 }
        assertThat(Wla25BodyComposition.impedancesValid(lowLeading)).isTrue()

        for (slot in intArrayOf(1, 2, 3, 4, 6, 7, 8, 9)) {
            val tooLow = validImpedances().also { it[slot] = 20.0 }
            assertThat(Wla25BodyComposition.impedancesValid(tooLow)).isFalse()
        }
    }

    @Test
    fun `rejects leading slots below one`() {
        assertThat(Wla25BodyComposition.impedancesValid(validImpedances().also { it[0] = 0.5 }))
            .isFalse()
        assertThat(Wla25BodyComposition.impedancesValid(validImpedances().also { it[5] = 0.5 }))
            .isFalse()
    }

    @Test
    fun `compute returns null when the gate fails`() {
        assertThat(Wla25BodyComposition.compute(170, 70.0, DoubleArray(10))).isNull()
    }

    // --- BMI -----------------------------------------------------------------

    @Test
    fun `bmi follows the standard formula`() {
        // 70 kg at 175 cm -> 70 / 1.75^2 = 22.857…
        assertThat(Wla25BodyComposition.bmi(175, 70.0)).isWithin(1e-9).of(22.857142857142858)
        // At 100 cm the divisor is 1.0, so BMI equals the weight — used by the tests below.
        assertThat(Wla25BodyComposition.bmi(100, 26.4)).isWithin(1e-9).of(26.4)
    }

    // --- the documented rounding ---------------------------------------------

    @Test
    fun `round1 is half-up, so 26_35 becomes 26_4`() {
        val result = Wla25BodyComposition.compute(100, 26.35, validImpedances())
        assertThat(result).isNotNull()
        // Half-to-even would give 26.3 here.
        assertThat(result!!.weightKg).isEqualTo(26.4f)
    }

    @Test
    fun `round1 rounds 1_95 down, because float32 makes the half-up test fail`() {
        val result = Wla25BodyComposition.compute(100, 1.95, validImpedances())
        assertThat(result).isNotNull()
        assertThat(result!!.weightKg).isEqualTo(1.9f)
    }

    @Test
    fun `a weight already at one decimal is unchanged`() {
        val result = Wla25BodyComposition.compute(175, 70.4, validImpedances())
        assertThat(result).isNotNull()
        assertThat(result!!.weightKg).isEqualTo(70.4f)
    }

    // --- clamps and internal consistency -------------------------------------

    @Test
    fun `body fat stays inside the documented clamp`() {
        // The clamp is stated in the source as 3.0…60.0 percent.
        for (weight in doubleArrayOf(1.9, 45.0, 70.0, 120.0, 250.0)) {
            val result = Wla25BodyComposition.compute(175, weight, validImpedances())
            assertThat(result).isNotNull()
            assertThat(result!!.fat).isAtLeast(3.0f)
            assertThat(result.fat).isAtMost(60.0f)
        }
    }

    @Test
    fun `visceral fat stays inside its one to twenty range`() {
        for (weight in doubleArrayOf(1.9, 45.0, 70.0, 120.0, 250.0)) {
            val result = Wla25BodyComposition.compute(175, weight, validImpedances())
            assertThat(result).isNotNull()
            assertThat(result!!.visceralFat).isAtLeast(1)
            assertThat(result.visceralFat).isAtMost(20)
        }
    }

    @Test
    fun `fat-free mass is the rounded weight minus the fat it implies`() {
        val result = Wla25BodyComposition.compute(175, 70.0, validImpedances())
        assertThat(result).isNotNull()
        // lbm = weight - roundedFat, so the two must add back up to the rounded weight.
        val impliedFatMass = result!!.weightKg - result.lbmKg
        assertThat(impliedFatMass + result.lbmKg).isWithin(1e-3f).of(result.weightKg)
        assertThat(result.lbmKg).isLessThan(result.weightKg)
    }

    @Test
    fun `muscle mass in kg agrees with muscle percent`() {
        val result = Wla25BodyComposition.compute(175, 70.0, validImpedances())
        assertThat(result).isNotNull()
        val expectedKg = result!!.musclePercent / 100f * result.weightKg
        // muscleKg is round1() of that product, so allow a tenth.
        assertThat(result.muscleKg).isWithin(0.05f).of(expectedKg)
    }

    @Test
    fun `bmr is derived from fat-free mass by the Katch-McArdle constants`() {
        val result = Wla25BodyComposition.compute(175, 70.0, validImpedances())
        assertThat(result).isNotNull()
        val expected = (result!!.lbmKg * 21.6 + 370.0).toInt()
        assertThat(result.bmrKcal).isEqualTo(expected)
    }
}
