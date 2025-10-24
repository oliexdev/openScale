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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.service.MeasurementEvaluator.evalBmi
import com.health.openscale.core.service.MeasurementEvaluator.evalBodyFat
import com.health.openscale.core.service.MeasurementEvaluator.evalLBM
import com.health.openscale.core.service.MeasurementEvaluator.evalMuscle
import com.health.openscale.core.service.MeasurementEvaluator.evalVisceralFat
import com.health.openscale.core.service.MeasurementEvaluator.evalWHR
import com.health.openscale.core.service.MeasurementEvaluator.evalWHtR
import com.health.openscale.core.service.MeasurementEvaluator.evalWaistCm
import com.health.openscale.core.service.MeasurementEvaluator.evalWater
import com.health.openscale.core.service.MeasurementEvaluator.evalWeightAgainstTargetRange
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.core.utils.ConverterUtils
import javax.inject.Inject
import javax.inject.Singleton

data class MeasurementEvaluationResult(
    val value: Float,
    val lowLimit: Float,
    val highLimit: Float,
    val state: EvaluationState
)

@Singleton
class MeasurementEvaluationUseCases @Inject constructor() {
    /**
     * Central entry point for evaluation in UI.
     *
     * @param type           The measurement type.
     * @param value             The numeric value to evaluate.
     * @param userEvaluationContext   User context (gender, height, birthDate).
     * @param measuredAtMillis  Timestamp of the measurement (for age-on calculation).
     *
     * @return MeasurementEvaluationResult or null if type is not supported.
     */
    fun evaluate(
        type: MeasurementType,
        value: Float,
        userEvaluationContext: UserEvaluationContext,
        measuredAtMillis: Long
    ): MeasurementEvaluationResult? {
        if (!value.isFinite()) return null

        val ageYears = CalculationUtils.ageOn(
            measuredAtMillis,
            userEvaluationContext.birthDateMillis
        )

        val typeKey = type.key
        val unit = type.unit

        return when (typeKey) {
            MeasurementTypeKey.WEIGHT   -> {
                val weightKg = ConverterUtils.convertFloatValueUnit(value, unit, UnitType.KG)
                evalWeightAgainstTargetRange(
                    weightKg = weightKg,
                    age = ageYears,
                    heightCm = userEvaluationContext.heightCm.toInt(),
                    gender = userEvaluationContext.gender
                )
            }
            MeasurementTypeKey.BODY_FAT -> {
                val fatPercent = ConverterUtils.convertFloatValueUnit(value, unit, UnitType.PERCENT)
                evalBodyFat(fatPercent, ageYears, userEvaluationContext.gender)
            }
            MeasurementTypeKey.WATER    -> {
                val waterPercent = ConverterUtils.convertFloatValueUnit(value, unit, UnitType.PERCENT)
                evalWater(waterPercent, ageYears, userEvaluationContext.gender)
            }
            MeasurementTypeKey.MUSCLE   -> {
                val musclePercent = ConverterUtils.convertFloatValueUnit(value, unit, UnitType.PERCENT)
                evalMuscle(musclePercent, ageYears, userEvaluationContext.gender)
            }
            MeasurementTypeKey.LBM      -> {
                val weightKg = ConverterUtils.convertFloatValueUnit(value, unit, UnitType.KG)
                evalLBM(weightKg, ageYears, userEvaluationContext.gender)
            }
            MeasurementTypeKey.WAIST    -> {
                val waistCm = ConverterUtils.convertFloatValueUnit(value, unit, UnitType.CM)
                evalWaistCm(waistCm, ageYears, userEvaluationContext.gender)
            }
            MeasurementTypeKey.BMI      -> evalBmi(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.WHTR     -> evalWHtR(value, ageYears)
            MeasurementTypeKey.WHR      -> evalWHR(value, ageYears, userEvaluationContext.gender)
            MeasurementTypeKey.VISCERAL_FAT -> evalVisceralFat(value, ageYears)
            else -> null
        }
    }

    /**
     * Returns a broad **plausible** percentage range for selected measurement types.
     *
     * This is **not** a clinical reference band. It’s only used to catch obviously
     * incorrect values (e.g., sensor glitches, unit mix-ups) before attempting
     * a proper evaluation. The ranges are intentionally wide.
     *
     * @param typeKey The measurement type to check.
     * @return A closed percent range [min .. max] if the metric is percentage-based and supported,
     *         or `null` if no generic plausibility range is defined for this type.
     */
    fun plausiblePercentRangeFor(typeKey: MeasurementTypeKey): ClosedFloatingPointRange<Float>? =
        when (typeKey) {
            MeasurementTypeKey.WATER    -> 35f..75f
            MeasurementTypeKey.BODY_FAT -> 3f..70f
            MeasurementTypeKey.MUSCLE   -> 15f..60f
            else -> null
        }
}
