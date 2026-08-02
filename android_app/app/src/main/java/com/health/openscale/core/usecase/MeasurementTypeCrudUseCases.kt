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
import com.health.openscale.core.database.DatabaseRepository
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

    /** All measurement types (predefined + custom). Used by the sync layer to build the
     *  generic, self-describing value set for external sync apps. */
    suspend fun getAll(): List<MeasurementType> = repository.getAllMeasurementTypes().first()

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
     * Composition values that support both PERCENT and absolute weight units (KG/LB/ST) use
     * the WEIGHT value from the *same measurement*. If the required weight value is missing
     * for a row, that row is skipped.
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
            var converted: Float?

            // Percent <-> absolute conversions for composition-like metrics
            if (ConverterUtils.isPercentageOrMassComposition(typeKey)) {
                val needsBodyWeight = oldUnit == UnitType.PERCENT || newUnit == UnitType.PERCENT
                val weightInKg = if (needsBodyWeight) {
                    val resolvedWeightType = weightType ?: continue
                    if (!resolvedWeightType.unit.isWeightUnit()) continue

                    val weightOnThisMeasurement = repository
                        .getValuesForMeasurement(mv.measurementId)
                        .first()
                        .find { it.typeId == resolvedWeightType.id }
                        ?.floatValue
                        ?: continue

                    ConverterUtils.convertFloatValueUnit(
                        weightOnThisMeasurement,
                        resolvedWeightType.unit,
                        UnitType.KG,
                    )
                } else {
                    null
                }

                converted = ConverterUtils.convertPercentageOrMassCompositionUnit(
                    value = current,
                    fromUnit = oldUnit,
                    toUnit = newUnit,
                    bodyWeightKg = weightInKg,
                ) ?: continue
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
