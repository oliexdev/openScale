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
package com.health.openscale.core.service

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import org.junit.Test

/**
 * Unit tests for the pure derived-value formulas in [DerivedValuesCalculator]
 * (BMI, WHR, WHtR, BMR, TDEE, fat caliper). These are health-critical and were
 * extracted into the companion object so they can be tested without a database.
 */
class DerivedValuesCalculatorFormulasTest {

    private companion object {
        const val EPS = 1e-2f
    }

    // ---- BMI ------------------------------------------------------------------------------------

    @Test
    fun bmi_isComputedCorrectly_forTypicalValues() {
        val bmi = DerivedValuesCalculator.processBmiCalculation(weightKg = 70f, heightCm = 175f)
        assertThat(bmi).isNotNull()
        // 70 / (1.75^2) = 22.857...
        assertThat(bmi!!).isWithin(EPS).of(22.857f)
    }

    @Test
    fun bmi_returnsNull_whenHeightIsZero_noDivisionByInfinity() {
        // Regression guard: height=0 must NOT produce Infinity but null.
        assertThat(DerivedValuesCalculator.processBmiCalculation(70f, 0f)).isNull()
    }

    @Test
    fun bmi_returnsNull_whenInputsMissingOrNonPositive() {
        assertThat(DerivedValuesCalculator.processBmiCalculation(null, 175f)).isNull()
        assertThat(DerivedValuesCalculator.processBmiCalculation(70f, null)).isNull()
        assertThat(DerivedValuesCalculator.processBmiCalculation(0f, 175f)).isNull()
        assertThat(DerivedValuesCalculator.processBmiCalculation(-5f, 175f)).isNull()
    }

    // ---- WHR / WHtR -----------------------------------------------------------------------------

    @Test
    fun whr_isWaistOverHips() {
        val whr = DerivedValuesCalculator.processWhrCalculation(waistCm = 80f, hipsCm = 100f)
        assertThat(whr).isNotNull()
        assertThat(whr!!).isWithin(EPS).of(0.8f)
    }

    @Test
    fun whr_returnsNull_whenHipsZero() {
        assertThat(DerivedValuesCalculator.processWhrCalculation(80f, 0f)).isNull()
    }

    @Test
    fun whtr_isWaistOverHeight() {
        val whtr = DerivedValuesCalculator.processWhtrCalculation(waistCm = 90f, bodyHeightCm = 180f)
        assertThat(whtr).isNotNull()
        assertThat(whtr!!).isWithin(EPS).of(0.5f)
    }

    @Test
    fun whtr_returnsNull_whenHeightZero() {
        assertThat(DerivedValuesCalculator.processWhtrCalculation(90f, 0f)).isNull()
    }

    // ---- BMR (Mifflin-St Jeor) ------------------------------------------------------------------

    @Test
    fun bmr_male_matchesMifflinStJeor() {
        // (10*80) + (6.25*175) - (5*30) + 5 = 1748.75
        val bmr = DerivedValuesCalculator.processBmrCalculation(80f, 175f, 30, GenderType.MALE)
        assertThat(bmr).isNotNull()
        assertThat(bmr!!).isWithin(EPS).of(1748.75f)
    }

    @Test
    fun bmr_female_usesFemaleConstant() {
        // (10*80) + (6.25*175) - (5*30) - 161 = 1582.75
        val bmr = DerivedValuesCalculator.processBmrCalculation(80f, 175f, 30, GenderType.FEMALE)
        assertThat(bmr).isNotNull()
        assertThat(bmr!!).isWithin(EPS).of(1582.75f)
    }

    @Test
    fun bmr_returnsNull_forOutOfRangeAge() {
        assertThat(DerivedValuesCalculator.processBmrCalculation(80f, 175f, 0, GenderType.MALE)).isNull()
        assertThat(DerivedValuesCalculator.processBmrCalculation(80f, 175f, 121, GenderType.MALE)).isNull()
    }

    @Test
    fun bmr_returnsNull_forMissingOrNonPositiveBody() {
        assertThat(DerivedValuesCalculator.processBmrCalculation(null, 175f, 30, GenderType.MALE)).isNull()
        assertThat(DerivedValuesCalculator.processBmrCalculation(80f, 0f, 30, GenderType.MALE)).isNull()
    }

    // ---- TDEE -----------------------------------------------------------------------------------

    @Test
    fun tdee_appliesActivityFactor() {
        // 1748.75 * 1.2 (SEDENTARY) = 2098.5
        val tdee = DerivedValuesCalculator.processTDEECalculation(1748.75f, ActivityLevel.SEDENTARY)
        assertThat(tdee).isNotNull()
        assertThat(tdee!!).isWithin(EPS).of(2098.5f)
    }

    @Test
    fun tdee_extremeIsHigherThanSedentary() {
        val sed = DerivedValuesCalculator.processTDEECalculation(1700f, ActivityLevel.SEDENTARY)!!
        val ext = DerivedValuesCalculator.processTDEECalculation(1700f, ActivityLevel.EXTREME)!!
        assertThat(ext).isGreaterThan(sed)
    }

    @Test
    fun tdee_returnsNull_forNullOrNonPositiveInputs() {
        assertThat(DerivedValuesCalculator.processTDEECalculation(null, ActivityLevel.MILD)).isNull()
        assertThat(DerivedValuesCalculator.processTDEECalculation(1700f, null)).isNull()
        assertThat(DerivedValuesCalculator.processTDEECalculation(0f, ActivityLevel.MILD)).isNull()
    }

    // ---- Fat caliper (3-fold, Jackson-Pollock style density) ------------------------------------

    @Test
    fun fatCaliper_returnsPlausiblePercent_forValidInput() {
        val fat = DerivedValuesCalculator.processFatCaliperCalculation(1f, 1f, 1f, 25, GenderType.MALE)
        assertThat(fat).isNotNull()
        assertThat(fat!!).isFinite()
        assertThat(fat).isGreaterThan(0f)
        assertThat(fat).isLessThan(70f)
    }

    @Test
    fun fatCaliper_genderChangesResult() {
        val male = DerivedValuesCalculator.processFatCaliperCalculation(1f, 1f, 1f, 25, GenderType.MALE)!!
        val female = DerivedValuesCalculator.processFatCaliperCalculation(1f, 1f, 1f, 25, GenderType.FEMALE)!!
        assertThat(male).isNotWithin(EPS).of(female)
    }

    @Test
    fun fatCaliper_returnsNull_forInvalidAgeOrZeroCaliper() {
        assertThat(DerivedValuesCalculator.processFatCaliperCalculation(1f, 1f, 1f, 0, GenderType.MALE)).isNull()
        assertThat(DerivedValuesCalculator.processFatCaliperCalculation(0f, 1f, 1f, 25, GenderType.MALE)).isNull()
        assertThat(DerivedValuesCalculator.processFatCaliperCalculation(1f, 1f, null, 25, GenderType.MALE)).isNull()
    }
}
