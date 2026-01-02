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

import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates CRUD operations for [MeasurementType] and provides a
 * unit-safe update that also converts existing measurement values when
 * the unit changes.
 *
 * This moves DB orchestration and value conversion logic out of ViewModels
 * to keep UI layers slim and easier to test.
 */
@Singleton
class MeasurementTypeCrudUseCases @Inject constructor(
    private val repository: DatabaseRepository
) {
    private val TAG = "MeasurementTypeCrudUseCase"

    /** Adds a new measurement type. */
    suspend fun add(type: MeasurementType): Result<Long> = runCatching {
        repository.insertMeasurementType(type)
    }

    /** Updates a measurement type without touching existing values. */
    suspend fun update(type: MeasurementType): Result<Unit> = runCatching {
        repository.updateMeasurementType(type)
    }

    /** Finds and returns a specific MeasurementType by its key. */
    suspend fun getByKey(key: MeasurementTypeKey): MeasurementType? {
        return repository.getAllMeasurementTypes().first().find { it.key == key }
    }

    /** Deletes a measurement type. Caller must ensure cascading semantics are OK. */
    suspend fun delete(type: MeasurementType): Result<Unit> = runCatching {
        repository.deleteMeasurementType(type)
    }

    /**
     * Report returned by [updateTypeAndConvertValues] so the UI can show a concise summary.
     * @property attempted true if a conversion step was relevant and attempted.
     * @property updatedCount number of values that were updated after conversion.
     */
    data class UnitConversionReport(
        val attempted: Boolean,
        val updatedCount: Int
    )

    /**
     * Updates a type (e.g., name, flags, **unit**) and, if its unit changed, converts
     * all existing values of that type to the new unit.
     *
     * Special handling: BODY_FAT, WATER, MUSCLE may switch between PERCENT and absolute
     * weight units (KG/LB/ST). Conversion uses the WEIGHT value from the *same measurement*.
     * If the required weight value is missing for a row, that row is skipped.
     *
     * Note: repository.updateMeasurementValue(...) is assumed to trigger derived-value
     * recalculation. If not, add explicit recalculation here after updates.
     */
    suspend fun updateTypeAndConvertValues(
        originalType: MeasurementType,
        updatedType: MeasurementType
    ): Result<UnitConversionReport> = runCatching {
        val typeKey = originalType.key
        val oldUnit = originalType.unit
        val newUnit = updatedType.unit

        // Update the type definition first.
        val finalType = originalType.copy(
            name = updatedType.name,
            color = updatedType.color,
            icon = updatedType.icon,
            unit = newUnit,
            inputType = updatedType.inputType,
            isEnabled = updatedType.isEnabled,
            isDerived = originalType.isDerived,
            isPinned = updatedType.isPinned,
            isOnRightYAxis = updatedType.isOnRightYAxis,
            displayOrder = originalType.displayOrder
        )
        repository.updateMeasurementType(finalType)

        if (oldUnit == newUnit) {
            return@runCatching UnitConversionReport(attempted = false, updatedCount = 0)
        }

        // Only FLOAT-like types have unit conversions.
        if (finalType.inputType != InputFieldType.FLOAT) {
            LogManager.i(TAG, "Unit changed but inputType is not FLOAT; skipping conversion.")
            return@runCatching UnitConversionReport(attempted = true, updatedCount = 0)
        }

        val allValuesForType = repository.getValuesForType(finalType.id).first()
        if (allValuesForType.isEmpty()) {
            return@runCatching UnitConversionReport(attempted = true, updatedCount = 0)
        }

        // Resolve the global WEIGHT type (needed for percent<->absolute conversions)
        val weightType = repository.getAllMeasurementTypes().first().find { it.key == MeasurementTypeKey.WEIGHT }

        var updatedCount = 0
        for (mv in allValuesForType) {
            val current = mv.floatValue ?: continue
            var converted: Float? = null

            // Percent <-> absolute conversions for composition-like metrics
            if (typeKey == MeasurementTypeKey.BODY_FAT ||
                typeKey == MeasurementTypeKey.WATER ||
                typeKey == MeasurementTypeKey.MUSCLE
            ) {
                if (weightType == null) {
                    // No weight type found; cannot compute percent-based conversions.
                    continue
                }

                val weightOnThisMeasurement = repository
                    .getValuesForMeasurement(mv.measurementId)
                    .first()
                    .find { it.typeId == weightType.id }?.floatValue

                if (weightOnThisMeasurement == null) {
                    // Missing WEIGHT value for this measurement row; skip.
                    continue
                }

                // Normalize the total weight to KG for math, then convert to target at the end
                val weightInKg = when (weightType.unit) {
                    UnitType.KG -> weightOnThisMeasurement
                    UnitType.LB -> ConverterUtils.toKilogram(weightOnThisMeasurement, WeightUnit.LB)
                    UnitType.ST -> ConverterUtils.toKilogram(weightOnThisMeasurement, WeightUnit.ST)
                    else -> null
                } ?: continue

                when {
                    // PERCENT -> absolute (kg/lb/st)
                    oldUnit == UnitType.PERCENT && newUnit.isWeightUnit() -> {
                        val absoluteInKg = (current / 100f) * weightInKg
                        converted = ConverterUtils.convertFloatValueUnit(absoluteInKg, UnitType.KG, newUnit)
                    }
                    // absolute (kg/lb/st) -> PERCENT
                    oldUnit.isWeightUnit() && newUnit == UnitType.PERCENT -> {
                        val currentInKg = ConverterUtils.convertFloatValueUnit(current, oldUnit, UnitType.KG)
                        if (weightInKg != 0f) {
                            converted = currentInKg / weightInKg * 100f
                        } else {
                            converted = 0f
                        }
                    }
                    // absolute <-> absolute
                    oldUnit.isWeightUnit() && newUnit.isWeightUnit() -> {
                        converted = ConverterUtils.convertFloatValueUnit(current, oldUnit, newUnit)
                    }
                    else -> {
                        // Unsupported path, keep original
                        converted = current
                    }
                }
            } else {
                // Generic unit conversion
                converted = ConverterUtils.convertFloatValueUnit(current, oldUnit, newUnit)
            }

            repository.updateMeasurementValue(mv.copy(floatValue = converted))
            updatedCount++
        }

        UnitConversionReport(attempted = true, updatedCount = updatedCount)
    }
}
