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
import com.health.openscale.core.data.UnitType
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

    // ---- Metabolic age (invert BMR-vs-age curve) ------------------------------------------------

    @Test
    fun metabolicAge_male_matchesInvertedCurve() {
        // Mifflin intercept (age 0, male): 10*80 + 6.25*175 + 5 = 1898.75
        // Katch-McArdle actual BMR: 370 + 21.6*60 = 1666
        // metAge = (1898.75 - 1666) / 5 = 46.55
        val age = DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.MALE, 60f)
        assertThat(age).isNotNull()
        assertThat(age!!).isWithin(EPS).of(46.55f)
    }

    @Test
    fun metabolicAge_female_usesFemaleConstant() {
        // Mifflin intercept (age 0, female): 10*80 + 6.25*175 - 161 = 1732.75
        // actual BMR: 370 + 21.6*55 = 1558 -> metAge = (1732.75 - 1558) / 5 = 34.95
        val age = DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.FEMALE, 55f)
        assertThat(age).isNotNull()
        assertThat(age!!).isWithin(EPS).of(34.95f)
    }

    @Test
    fun metabolicAge_higherFatFreeMass_yieldsYoungerAge() {
        val leaner = DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.MALE, 65f)!!
        val fatter = DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.MALE, 60f)!!
        assertThat(leaner).isLessThan(fatter)
    }

    @Test
    fun metabolicAge_isClampedToPlausibleRange() {
        // Very high FFM drives the raw result well below 15 -> clamp to 15.
        val young = DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.MALE, 90f)!!
        assertThat(young).isWithin(EPS).of(15f)
        // Very low FFM drives the raw result well above 99 -> clamp to 99.
        val old = DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.MALE, 1f)!!
        assertThat(old).isWithin(EPS).of(99f)
    }

    @Test
    fun metabolicAge_returnsNull_forMissingOrNonPositiveInputs() {
        assertThat(DerivedValuesCalculator.processMetabolicAgeCalculation(null, 175f, GenderType.MALE, 60f)).isNull()
        assertThat(DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 0f, GenderType.MALE, 60f)).isNull()
        assertThat(DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.MALE, null)).isNull()
        assertThat(DerivedValuesCalculator.processMetabolicAgeCalculation(80f, 175f, GenderType.MALE, 0f)).isNull()
    }

    // ---- toPercentOfWeight (body-composition normalisation) -------------------------------------

    @Test
    fun toPercentOfWeight_passesThroughPercent_andConvertsMass() {
        // Already a percentage -> unchanged (weight irrelevant).
        assertThat(DerivedValuesCalculator.toPercentOfWeight(20f, UnitType.PERCENT, 80f)!!).isWithin(EPS).of(20f)
        // 16 kg of a 80 kg body = 20 %.
        assertThat(DerivedValuesCalculator.toPercentOfWeight(16f, UnitType.KG, 80f)!!).isWithin(EPS).of(20f)
    }

    @Test
    fun toPercentOfWeight_returnsNull_forMissingWeightOnMass_orBadInput() {
        assertThat(DerivedValuesCalculator.toPercentOfWeight(16f, UnitType.KG, null)).isNull()
        assertThat(DerivedValuesCalculator.toPercentOfWeight(16f, UnitType.KG, 0f)).isNull()
        assertThat(DerivedValuesCalculator.toPercentOfWeight(null, UnitType.PERCENT, 80f)).isNull()
        assertThat(DerivedValuesCalculator.toPercentOfWeight(0f, UnitType.PERCENT, 80f)).isNull()
        assertThat(DerivedValuesCalculator.toPercentOfWeight(20f, UnitType.CM, 80f)).isNull()
    }

    // ---- Physique rating (Tanita 3x3 body-type matrix) ------------------------------------------

    private data class PhysiqueCase(val fat: Float, val muscle: Float, val expected: Int)

    @Test
    fun physiqueRating_mapsFatAndMuscleBandsToTanitaMatrix_male() {
        // Male age 25: body-fat band 13–18 %, muscle band 37.9–46.7 %.
        // fat  <13 LOW, 13–18 NORMAL, >18 HIGH
        // musc <37.9 LOW, 37.9–46.7 NORMAL, >46.7 HIGH
        val cases = listOf(
            PhysiqueCase(25f, 30f, 1), // high fat,   low muscle    -> hidden obese
            PhysiqueCase(25f, 42f, 2), // high fat,   normal muscle -> obese
            PhysiqueCase(25f, 50f, 3), // high fat,   high muscle   -> solidly built
            PhysiqueCase(15f, 30f, 4), // normal fat, low muscle    -> under-exercised
            PhysiqueCase(15f, 42f, 5), // normal fat, normal muscle -> standard
            PhysiqueCase(15f, 50f, 6), // normal fat, high muscle   -> standard muscular
            PhysiqueCase(10f, 30f, 7), // low fat,    low muscle     -> thin
            PhysiqueCase(10f, 42f, 8), // low fat,    normal muscle  -> thin & muscular
            PhysiqueCase(10f, 50f, 9), // low fat,    high muscle    -> very muscular
        )
        cases.forEach { c ->
            val r = DerivedValuesCalculator.processPhysiqueRatingCalculation(c.fat, c.muscle, 25, GenderType.MALE)
            assertThat(r).isEqualTo(c.expected.toFloat())
        }
    }

    @Test
    fun physiqueRating_usesSexSpecificBands() {
        // Age 25, fat 20 %, muscle 38 %.
        // Male:   fat 20 > 18 HIGH,   muscle 38 in 37.9–46.7 NORMAL -> 2 (obese)
        // Female: fat 20 in 18–23 NORMAL, muscle 38 in 28.4–39.8 NORMAL -> 5 (standard)
        val male = DerivedValuesCalculator.processPhysiqueRatingCalculation(20f, 38f, 25, GenderType.MALE)
        val female = DerivedValuesCalculator.processPhysiqueRatingCalculation(20f, 38f, 25, GenderType.FEMALE)
        assertThat(male).isEqualTo(2f)
        assertThat(female).isEqualTo(5f)
    }

    @Test
    fun physiqueRating_returnsNull_whenAgeOutsideReferenceBands() {
        // Muscle reference (Janssen) starts at age 18; a 10-year-old has no band.
        assertThat(DerivedValuesCalculator.processPhysiqueRatingCalculation(15f, 42f, 10, GenderType.MALE)).isNull()
    }

    @Test
    fun physiqueRating_returnsNull_forMissingOrImplausibleInputs() {
        assertThat(DerivedValuesCalculator.processPhysiqueRatingCalculation(null, 42f, 25, GenderType.MALE)).isNull()
        assertThat(DerivedValuesCalculator.processPhysiqueRatingCalculation(15f, null, 25, GenderType.MALE)).isNull()
        assertThat(DerivedValuesCalculator.processPhysiqueRatingCalculation(0.5f, 42f, 25, GenderType.MALE)).isNull() // fat < 1
        assertThat(DerivedValuesCalculator.processPhysiqueRatingCalculation(15f, 2f, 25, GenderType.MALE)).isNull()   // muscle < 5
    }
}
