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
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.GenderType
import org.junit.Test

/**
 * Tests the reference-table evaluation that drives the in/out-of-range coloring shown to users.
 * Focuses on band boundaries, age-band selection, gender branching, and out-of-band -> UNDEFINED.
 */
class MeasurementEvaluatorTest {

    // ---- BMI band boundaries (adult male band 19..64 -> 18.5..25) -------------------------------

    @Test
    fun bmi_belowLowerBound_isLow() {
        assertThat(MeasurementEvaluator.evalBmi(18.4f, 30, GenderType.MALE).state)
            .isEqualTo(EvaluationState.LOW)
    }

    @Test
    fun bmi_atLowerBound_isNormal() {
        assertThat(MeasurementEvaluator.evalBmi(18.5f, 30, GenderType.MALE).state)
            .isEqualTo(EvaluationState.NORMAL)
    }

    @Test
    fun bmi_atUpperBound_isNormal() {
        assertThat(MeasurementEvaluator.evalBmi(25.0f, 30, GenderType.MALE).state)
            .isEqualTo(EvaluationState.NORMAL)
    }

    @Test
    fun bmi_aboveUpperBound_isHigh() {
        assertThat(MeasurementEvaluator.evalBmi(25.1f, 30, GenderType.MALE).state)
            .isEqualTo(EvaluationState.HIGH)
    }

    @Test
    fun bmi_resultCarriesBandLimits() {
        val r = MeasurementEvaluator.evalBmi(22f, 30, GenderType.MALE)
        assertThat(r.lowLimit).isWithin(1e-3f).of(18.5f)
        assertThat(r.highLimit).isWithin(1e-3f).of(25f)
    }

    @Test
    fun bmi_childBandDiffersFromAdult() {
        // Age 5 male band is 14.4..16.8 -> a value normal for an adult is HIGH for a 5-year-old.
        assertThat(MeasurementEvaluator.evalBmi(17f, 5, GenderType.MALE).state)
            .isEqualTo(EvaluationState.HIGH)
    }

    @Test
    fun bmi_ageBelowYoungestBand_isUndefined() {
        // youngest defined band starts at age 5
        val r = MeasurementEvaluator.evalBmi(20f, 4, GenderType.MALE)
        assertThat(r.state).isEqualTo(EvaluationState.UNDEFINED)
        assertThat(r.lowLimit).isWithin(1e-3f).of(-1f)
    }

    // ---- gender branching -----------------------------------------------------------------------

    @Test
    fun bodyFat_sameValueDiffersByGender() {
        // age 25: male band 13..18 -> 17 is NORMAL; female band 18..23 -> 17 is LOW
        assertThat(MeasurementEvaluator.evalBodyFat(17f, 25, GenderType.MALE).state)
            .isEqualTo(EvaluationState.NORMAL)
        assertThat(MeasurementEvaluator.evalBodyFat(17f, 25, GenderType.FEMALE).state)
            .isEqualTo(EvaluationState.LOW)
    }

    @Test
    fun whr_sameValueDiffersByGender() {
        // male band 0.8..0.9 -> 0.85 NORMAL; female band 0.7..0.8 -> 0.85 HIGH
        assertThat(MeasurementEvaluator.evalWHR(0.85f, 40, GenderType.MALE).state)
            .isEqualTo(EvaluationState.NORMAL)
        assertThat(MeasurementEvaluator.evalWHR(0.85f, 40, GenderType.FEMALE).state)
            .isEqualTo(EvaluationState.HIGH)
    }

    // ---- target weight (formula strategy from height) -------------------------------------------

    @Test
    fun targetWeight_evaluatesAgainstBmiDerivedRange() {
        // height 180cm male: h^2=3.24, range = [3.24*20, 3.24*25] = [64.8, 81.0]
        assertThat(MeasurementEvaluator.evalWeightAgainstTargetRange(70f, 30, 180, GenderType.MALE).state)
            .isEqualTo(EvaluationState.NORMAL)
        assertThat(MeasurementEvaluator.evalWeightAgainstTargetRange(60f, 30, 180, GenderType.MALE).state)
            .isEqualTo(EvaluationState.LOW)
        assertThat(MeasurementEvaluator.evalWeightAgainstTargetRange(85f, 30, 180, GenderType.MALE).state)
            .isEqualTo(EvaluationState.HIGH)
    }
}
