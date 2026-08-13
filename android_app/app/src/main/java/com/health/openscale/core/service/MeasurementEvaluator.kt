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

import com.health.openscale.core.data.GenderType
import com.health.openscale.core.model.EvaluationReferenceTables
import com.health.openscale.core.usecase.MeasurementEvaluationResult

/**
 * High-level evaluation API for measurements.
 * Delegates to MeasurementReferenceTable strategies.
 */
object MeasurementEvaluator {

    // --- Body composition ---

    fun evalBodyFat(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> EvaluationReferenceTables.fatMale.evaluate(value, age)
            GenderType.FEMALE -> EvaluationReferenceTables.fatFemale.evaluate(value, age)
        }

    fun evalWater(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> EvaluationReferenceTables.waterMale.evaluate(value, age)
            GenderType.FEMALE -> EvaluationReferenceTables.waterFemale.evaluate(value, age)
        }

    fun evalMuscle(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> EvaluationReferenceTables.muscleMale.evaluate(value, age)
            GenderType.FEMALE -> EvaluationReferenceTables.muscleFemale.evaluate(value, age)
        }

    fun evalLBM(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> EvaluationReferenceTables.lbmMale.evaluate(value, age)
            GenderType.FEMALE -> EvaluationReferenceTables.lbmFemale.evaluate(value, age)
        }

    // --- Indices ---

    fun evalBmi(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> EvaluationReferenceTables.bmiMale.evaluate(value, age)
            GenderType.FEMALE -> EvaluationReferenceTables.bmiFemale.evaluate(value, age)
        }

    fun evalWHtR(value: Float, age: Int): MeasurementEvaluationResult =
        EvaluationReferenceTables.whtr.evaluate(value, age)

    fun evalWHR(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        when (gender) {
            GenderType.MALE   -> EvaluationReferenceTables.whrMale.evaluate(value, age)
            GenderType.FEMALE -> EvaluationReferenceTables.whrFemale.evaluate(value, age)
        }

    fun evalVisceralFat(value: Float, age: Int): MeasurementEvaluationResult =
        EvaluationReferenceTables.visceralFat.evaluate(value, age)

    // --- Circumference / targets ---

    fun evalWaistCm(value: Float, age: Int, gender: GenderType): MeasurementEvaluationResult =
        EvaluationReferenceTables.waistStrategyCm(gender).evaluate(value, age)

    /** Evaluates current weight against BMI-derived target range for given height/gender. */
    fun evalWeightAgainstTargetRange(
        weightKg: Float,
        age: Int,
        heightCm: Int,
        gender: GenderType
    ): MeasurementEvaluationResult =
        EvaluationReferenceTables
            .targetWeightStrategy(heightCm, gender)
            .evaluate(weightKg, age)
}
