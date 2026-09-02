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
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import android.content.Context
import com.health.openscale.core.database.DatabaseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val repository: DatabaseRepository,
    @param:ApplicationContext private val appContext: Context
) {
    private val TAG = "MeasurementTypeCrudUseCase"

    /**
     * Adds a new measurement type. A user-created type (the editor constructs rows with a
     * blank identity) gets its frozen `user.*` identity assigned here.
     */
    suspend fun add(type: MeasurementType): Result<Long> = runCatching {
        repository.insertMeasurementType(withIdentity(type, existing = null))
    }

    /**
     * Updates a measurement type without touching existing values.
     *
     * The editor rebuilds the entity from scratch on save, so the fields it does not show
     * — the identity and isInternal — are restored from the stored row here; without this,
     * every edit would silently clear them.
     */
    suspend fun update(type: MeasurementType): Result<Unit> = runCatching {
        val existing = repository.getMeasurementTypeById(type.id)
        repository.updateMeasurementType(
            withIdentity(type, existing).copy(isInternal = existing?.isInternal ?: type.isInternal)
        )
    }

    /**
     * Returns the measurement type backing a handler-declared [key], creating it on
     * first use. Called while a measurement is being saved, so it never fails the
     * measurement: on any problem it returns null and the caller drops just that value.
     *
     * The lookup goes through the identity, never the display name, so the type is still
     * found after the user renamed it, switched the app language or changed its unit.
     */
    suspend fun resolveOrCreate(key: MeasurementType.Key<*>): MeasurementType? = runCatching {
        repository.getMeasurementTypeByIdentity(key.identity)
            ?.let { return@runCatching it }

        // Create only in the ble.* namespace. Predefined rows come from seeding and the
        // migrations; creating one here would mint a half-defined builtin (localized name
        // stored, end-of-list displayOrder — or, for an identity the registry does not
        // know, an unrenamable, undeletable ghost). The Key constructor cannot be sealed
        // against the module, so this is the enforcement, not the factories.
        if (!key.identity.startsWith(MeasurementType.DEVICE_PREFIX)) {
            LogManager.e(
                TAG,
                "Refusing to create '${key.identity}': only ble.* keys materialize on first use."
            )
            return@runCatching null
        }

        val displayOrder = repository.getAllMeasurementTypes().first().size + 1
        val newType = MeasurementType(
            identity = key.identity,
            name = appContext.getString(key.nameResId),
            color = key.defaultColor,
            icon = key.defaultIcon,
            unit = key.defaultUnit,
            inputType = key.inputType,
            displayOrder = displayOrder,
            isDerived = key.isDerived,
            isEnabled = key.defaultEnabled,
            isPinned = key.defaultPinned,
            isOnRightYAxis = key.defaultOnRightYAxis,
            isInternal = key.isInternal
        )
        LogManager.i(TAG, "Creating measurement type for ${key.identity}")
        newType.copy(id = repository.insertMeasurementType(newType).toInt())
    }.recoverCatching { error ->
        // Lost the race on the unique index against a concurrent insert: take the winner.
        repository.getMeasurementTypeByIdentity(key.identity) ?: throw error
    }.getOrElse { error ->
        LogManager.e(TAG, "Could not resolve key ${key.identity}", error)
        null
    }

    /**
     * The identity a row must carry, as a ranked list of rules.
     *
     * Identities are frozen: a builtin or ble.* one is never changed — the ble.* identity
     * is the handler's only link to its own values, and moving it would create a second
     * type at the next weigh-in. A user.* identity is frozen too, but the editor may
     * replace it outright: that is the CSV column field, and the user changing it
     * deliberately is very different from a rename silently invalidating earlier exports.
     * Only a row with no identity at all gets one derived from its display name.
     */
    private suspend fun withIdentity(
        incoming: MeasurementType,
        existing: MeasurementType?
    ): MeasurementType {
        val current = existing?.identity?.takeIf { it.isNotBlank() }
        val identity = when {
            current != null && !existing.isUserOwned() -> current
            incoming.identity.startsWith(MeasurementType.USER_PREFIX) &&
                (current == null || existing.isUserOwned()) -> incoming.identity
            current != null -> current
            // An explicit identity is honoured only when it is a user.* one or belongs to
            // the registry (re-seeding a predefined row). Anything else — a smuggled ble.*
            // or an unknown builtin.* — is ignored and the type gets a fresh user identity,
            // so no caller can claim a foreign namespace through the editor path.
            incoming.identity.isNotBlank() &&
                (incoming.identity.startsWith(MeasurementType.USER_PREFIX) ||
                    MeasurementType.keyOf(incoming.identity) != null) -> incoming.identity
            else -> {
                val taken = repository.getAllMeasurementTypes().first()
                    .filter { it.id != incoming.id }
                    .filter { it.identity.isNotBlank() }
                    .map { MeasurementType.identityColumnKey(it.identity) }
                    .toSet()
                MeasurementType.userIdentityFor(incoming.name, taken)
            }
        }
        return incoming.copy(identity = identity)
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

        // Update the type definition first — through the same identity rules as update(),
        // so an edited CSV column survives a simultaneous unit change.
        val identity = withIdentity(updatedType, originalType).identity
        val finalType = originalType.copy(
            identity = identity,
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
        val weightType = repository.getAllMeasurementTypes().first().find { it.identity == MeasurementType.WEIGHT.identity }

        var updatedCount = 0
        for (mv in allValuesForType) {
            val current = mv.floatValue ?: continue
            var converted: Float?

            // Percent <-> absolute conversions for composition-like metrics
            if (typeKey == MeasurementType.BODY_FAT ||
                typeKey == MeasurementType.WATER ||
                typeKey == MeasurementType.MUSCLE
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
