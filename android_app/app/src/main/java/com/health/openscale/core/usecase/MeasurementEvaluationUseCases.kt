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

        val ageYears = CalculationUtils.ageOn(
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
