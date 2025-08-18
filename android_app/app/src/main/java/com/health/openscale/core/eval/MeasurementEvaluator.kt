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
package com.health.openscale.core.eval

import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.utils.CalculationUtil
import com.health.openscale.ui.screen.UserEvaluationContext

data class MeasurementEvaluationResult(
    val value: Float,
    val lowLimit: Float,
    val highLimit: Float,
    val state: EvaluationState
)

/**
 * High-level evaluation API for measurements.
 * Delegates to MeasurementReferenceTable strategies.
 */
object MeasurementEvaluator {

    /**
     * Central entry point for evaluation in UI.
     *
     * @param typeKey           The measurement type key.
     * @param value             The numeric value to evaluate.
     * @param userEvaluationContext   User context (gender, height, birthDate).
     * @param measuredAtMillis  Timestamp of the measurement (for age-on calculation).
     *
     * @return MeasurementEvaluationResult or null if type is not supported.
     */
    fun evaluate(
        typeKey: MeasurementTypeKey,
        value: Float,
        userEvaluationContext: UserEvaluationContext,
        measuredAtMillis: Long
    ): MeasurementEvaluationResult? {
        if (!value.isFinite()) return null

        val ageYears = CalculationUtil.ageOn(
            measuredAtMillis,
            userEvaluationContext.birthDateMillis
        )

        return when (typeKey) {
            MeasurementTypeKey.BODY_FAT -> evalBodyFat(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.WATER    -> evalWater(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.MUSCLE   -> evalMuscle(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.LBM      -> evalLBM(value, ageYears, userEvaluationContext.gender)

            MeasurementTypeKey.BMI      -> evalBmi(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.WHTR     -> evalWHtR(value, ageYears)
            MeasurementTypeKey.WHR      -> evalWHR(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.VISCERAL_FAT -> evalVisceralFat(value, ageYears)

            MeasurementTypeKey.WAIST    -> evalWaistCm(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.WEIGHT   -> evalWeightAgainstTargetRange(
                weightKg = value,
                age = ageYears,
                heightCm = userEvaluationContext.heightCm.toInt(),
                gender = userEvaluationContext.gender
            )

            else -> null
        }
    }
    // --- Body composition ---

    fun evalBodyFat(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> MeasurementReferenceTable.fatMale.evaluate(value, age)
            GenderType.FEMALE -> MeasurementReferenceTable.fatFemale.evaluate(value, age)
        }

    fun evalWater(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> MeasurementReferenceTable.waterMale.evaluate(value, age)
            GenderType.FEMALE -> MeasurementReferenceTable.waterFemale.evaluate(value, age)
        }

    fun evalMuscle(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> MeasurementReferenceTable.muscleMale.evaluate(value, age)
            GenderType.FEMALE -> MeasurementReferenceTable.muscleFemale.evaluate(value, age)
        }

    fun evalLBM(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> MeasurementReferenceTable.lbmMale.evaluate(value, age)
            GenderType.FEMALE -> MeasurementReferenceTable.lbmFemale.evaluate(value, age)
        }

    // --- Indices ---

    fun evalBmi(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> MeasurementReferenceTable.bmiMale.evaluate(value, age)
            GenderType.FEMALE -> MeasurementReferenceTable.bmiFemale.evaluate(value, age)
        }

    fun evalWHtR(value: Float, age: Int): MeasurementEvaluationResult =
        MeasurementReferenceTable.whtr.evaluate(value, age)

    fun evalWHR(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> MeasurementReferenceTable.whrMale.evaluate(value, age)
            GenderType.FEMALE -> MeasurementReferenceTable.whrFemale.evaluate(value, age)
        }

    fun evalVisceralFat(value: Float, age: Int): MeasurementEvaluationResult =
        MeasurementReferenceTable.visceralFat.evaluate(value, age)

    // --- Circumference / targets ---

    fun evalWaistCm(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        MeasurementReferenceTable.waistStrategyCm(gender).evaluate(value, age)

    /** Evaluates current weight against BMI-derived target range for given height/gender. */
    fun evalWeightAgainstTargetRange(
        weightKg: Float,
        age: Int,
        heightCm: Int,
        gender: GenderType
    ): MeasurementEvaluationResult =
        MeasurementReferenceTable
            .targetWeightStrategy(heightCm, gender)
            .evaluate(weightKg, age)
}
