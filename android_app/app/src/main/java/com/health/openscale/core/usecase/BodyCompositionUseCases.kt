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

import com.health.openscale.core.data.*
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.core.utils.ConverterUtils
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class BodyCompositionUseCases @Inject constructor(
    private val settings: SettingsFacade,
    private val userUseCases: UserUseCases,
    private val measurementQuery: MeasurementQueryUseCases
) {
    /**
     * Apply selected body-composition formulas for the given measurement.
     *
     * Rules:
     * - If a formula is OFF → leave values untouched (no overwrite, no insert).
     * - If a formula is ON  → overwrite existing value; insert a new value if missing.
     * - If computed result is null → do not insert; keep existing value as-is.
     *
     * No DB I/O here; caller persists via CRUD.
     */
    suspend fun applySelectedFormulasForMeasurement(
        measurement: Measurement,
        values: List<MeasurementValue>
    ): List<MeasurementValue> {
        val bfOpt  = settings.selectedBodyFatFormula.first()
        val bwOpt  = settings.selectedBodyWaterFormula.first()
        val lbmOpt = settings.selectedLbmFormula.first()

        if (bfOpt == BodyFatFormulaOption.OFF &&
            bwOpt == BodyWaterFormulaOption.OFF &&
            lbmOpt == LbmFormulaOption.OFF
        ) return values

        val user  = userUseCases.observeUserById(measurement.userId).first() ?: return values
        val types = measurementQuery.getAllMeasurementTypes().first()
        val byKey = types.associateBy { it.key }

        // Anchor: weight (in kg)
        val weightType = byKey[MeasurementTypeKey.WEIGHT] ?: return values
        val weightVal  = values.find { it.typeId == weightType.id }?.floatValue ?: return values
        val weightKg   = when (weightType.unit) {
            UnitType.KG -> weightVal
            UnitType.LB -> ConverterUtils.toKilogram(weightVal, WeightUnit.LB)
            UnitType.ST -> ConverterUtils.toKilogram(weightVal, WeightUnit.ST)
            else        -> return values
        }

        val heightCm = user.heightCm.toDouble().takeIf { it > 0.0 } ?: return values
        val ageYears = CalculationUtils.ageOn(measurement.timestamp, user.birthDate)
        val isMale   = user.gender == GenderType.MALE

        // BMI (prefer provided, otherwise compute)
        val bmiType = byKey[MeasurementTypeKey.BMI]
        val bmiProvided = bmiType?.let { t -> values.find { it.typeId == t.id }?.floatValue }
        val bmi = bmiProvided?.toDouble() ?: run {
            val hM = heightCm / 100.0
            weightKg / (hM.pow(2.0))
        }

        val out = values.toMutableList()

        // Helper: overwrite existing or insert new value if missing (only if newValue != null)
        fun upsertFloat(type: MeasurementType, newValue: Float?) {
            val idx = out.indexOfFirst { it.typeId == type.id }
            if (idx >= 0) {
                // Update existing (can set to null)
                out[idx] = out[idx].copy(floatValue = newValue)
            } else if (newValue != null) {
                // Insert new row only when we have a concrete value
                out.add(
                    MeasurementValue(
                        id = 0,
                        measurementId = measurement.id, // 0 for new; CRUD layer will set final id
                        typeId = type.id,
                        floatValue = newValue
                    )
                )
            }
        }

        // --- BODY FAT (%)
        byKey[MeasurementTypeKey.BODY_FAT]?.let { type ->
            if (bfOpt != BodyFatFormulaOption.OFF) {
                val bf = when (bfOpt) {
                    BodyFatFormulaOption.DEURENBERG_1991 ->
                        (1.2 * bmi) + (0.23 * ageYears) - if (isMale) 16.2 else 5.4
                    BodyFatFormulaOption.DEURENBERG_1992 ->
                        if (ageYears >= 16)
                            (1.2 * bmi) + (0.23 * ageYears) - (10.8 * (if (isMale) 1 else 0)) - 5.4
                        else
                            (1.294 * bmi) + (0.20 * ageYears) - (11.4 * (if (isMale) 1 else 0)) - 8.0
                    BodyFatFormulaOption.EDDY_1976 ->
                        if (isMale) (1.281 * bmi) - 10.13 else (1.48 * bmi) - 7.0
                    BodyFatFormulaOption.GALLAGHER_2000_NON_ASIAN ->
                        64.5 - (848.0 / bmi) + (0.079 * ageYears)
                    BodyFatFormulaOption.GALLAGHER_2000_ASIAN ->
                        if (isMale) 51.9 - (740.0 / bmi) + (0.029 * ageYears)
                        else         64.8 - (752.0 / bmi) + (0.016 * ageYears)
                    BodyFatFormulaOption.OFF -> null
                }?.coerceAtLeast(0.0)

                val newVal = bf?.toFloat()
                    ?.coerceIn(0f, 75f)
                    ?.let { CalculationUtils.roundTo(it) }

                upsertFloat(type, newVal)
            }
        }

        // --- BODY WATER (stored either as %, or mass depending on type.unit)
        byKey[MeasurementTypeKey.WATER]?.let { type ->
            if (bwOpt != BodyWaterFormulaOption.OFF) {
                val liters = when (bwOpt) {
                    BodyWaterFormulaOption.BEHNKE_1963 ->
                        0.72 * ((if (isMale) 0.204 else 0.18) * (heightCm * heightCm)) / 100.0
                    BodyWaterFormulaOption.DELWAIDE_CRENIER_1973 ->
                        0.72 * (-1.976 + 0.907 * weightKg)
                    BodyWaterFormulaOption.HUME_WEYERS_1971 ->
                        if (isMale) (0.194786 * heightCm) + (0.296785 * weightKg) - 14.012934
                        else         (0.34454  * heightCm) + (0.183809 * weightKg) - 35.270121
                    BodyWaterFormulaOption.LEE_SONG_KIM_2001 ->
                        if (isMale) -28.3497 + (0.243057 * heightCm) + (0.366248 * weightKg)
                        else        -26.6224 + (0.262513 * heightCm) + (0.232948 * weightKg)
                    BodyWaterFormulaOption.OFF -> null
                }?.coerceAtLeast(0.0)

                val newVal: Float? = liters?.let {
                    when (type.unit) {
                        UnitType.PERCENT -> {
                            val pct = (it / weightKg) * 100.0
                            CalculationUtils.roundTo(pct.toFloat().coerceIn(0f, 100f))
                        }
                        UnitType.KG -> CalculationUtils.roundTo(it.toFloat().coerceAtLeast(0f))
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(it.toFloat(), WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(it.toFloat(), WeightUnit.ST)
                        )
                        else -> {
                            // Default to percent if unit is unexpected
                            val pct = (it / weightKg) * 100.0
                            CalculationUtils.roundTo(pct.toFloat().coerceIn(0f, 100f))
                        }
                    }
                }

                upsertFloat(type, newVal)
            }
        }

        // --- LBM (mass)
        byKey[MeasurementTypeKey.LBM]?.let { type ->
            if (lbmOpt != LbmFormulaOption.OFF) {
                val lbmKg = when (lbmOpt) {
                    LbmFormulaOption.WEIGHT_MINUS_BODY_FAT -> {
                        val bfType = byKey[MeasurementTypeKey.BODY_FAT]
                        // Prefer freshly computed BF in 'out', fallback to original list
                        val bfPercent = out.firstOrNull { it.typeId == bfType?.id }?.floatValue
                            ?: values.firstOrNull { it.typeId == bfType?.id }?.floatValue
                        bfPercent?.let { weightKg * (1f - it / 100f) }?.toDouble()
                    }
                    LbmFormulaOption.BOER_1984 ->
                        if (isMale) (0.4071 * weightKg) + (0.267 * heightCm) - 19.2
                        else         (0.252  * weightKg) + (0.473 * heightCm) - 48.3
                    LbmFormulaOption.HUME_1966 ->
                        if (isMale) (0.32810 * weightKg) + (0.33929 * heightCm) - 29.5336
                        else         (0.29569 * weightKg) + (0.41813 * heightCm) - 43.2933
                    LbmFormulaOption.OFF -> null
                }?.coerceAtLeast(0.0)

                val newVal = lbmKg?.let {
                    when (type.unit) {
                        UnitType.KG -> CalculationUtils.roundTo(it.toFloat())
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(it.toFloat(), WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(it.toFloat(), WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(it.toFloat())
                    }
                }

                upsertFloat(type, newVal)
            }
        }

        return out
    }

    /**
     * Corrects the weight value in a list of measurements if the user has amputations.
     *
     * This function checks the user associated with the measurement. If amputations are
     * recorded, it finds the weight measurement, calculates the corrected weight,
     * and returns a new list of measurement values with the updated weight.
     *
     * @param measurement The measurement containing the user ID.
     * @param values The original list of measurement values.
     * @return A new list of measurement values with the corrected weight, or the original
     * list if no correction was needed.
     */
    suspend fun applyAmputationCorrection(
        measurement: Measurement,
        values: List<MeasurementValue>
    ): List<MeasurementValue> {
        val user = userUseCases.observeUserById(measurement.userId).first()
            ?: return values

        if (user.amputations.isEmpty()) {
            return values
        }

        val weightIndex = values.indexOfFirst { it.typeId == MeasurementTypeKey.WEIGHT.id }
        if (weightIndex == -1) {
            return values
        }

        val originalWeightValue = values[weightIndex]
        val measuredWeight = originalWeightValue.floatValue ?: return values

        val totalCorrection = user.amputations.values
            .sumOf { it.correctionValue.toDouble() }
            .toFloat()
            .coerceIn(0f, 100f)

        if (totalCorrection <= 0f) {
            return values
        }

        val remainingPercentage = 100.0f - totalCorrection
        val correctedWeight = if (remainingPercentage > 0f) {
            (measuredWeight * 100.0f) / remainingPercentage
        } else {
            measuredWeight
        }

        val updatedValues = values.toMutableList()
        updatedValues[weightIndex] = originalWeightValue.copy(floatValue = correctedWeight)

        return updatedValues
    }
}
