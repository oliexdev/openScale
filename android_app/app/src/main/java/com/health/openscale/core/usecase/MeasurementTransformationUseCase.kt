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

import com.health.openscale.core.data.BodyFatFormulaOption
import com.health.openscale.core.data.BodyWaterFormulaOption
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.BodyScanCalibration
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.LbmFormulaOption
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlin.math.log10
import kotlin.math.pow

/**
 * A use case responsible for transforming raw incoming measurements based on
 * business logic like "Assisted Weighing" or "Smart User Assignment".
 * It prepares the data for the final save operation.
 */
@Singleton
class MeasurementTransformationUseCase @Inject constructor(
    private val settings: SettingsFacade,
    private val userUseCases: UserUseCases,
    private val query: MeasurementQueryUseCases,
    private val databaseRepository: DatabaseRepository
) {

    /**
     * The row id of the WEIGHT type. Resolved from the database — the pre-16 code compared
     * MeasurementValue.typeId against the enum's ordinal, which only worked because WEIGHT
     * happened to be seeded as row 1.
     */
    private suspend fun weightTypeId(): Int? =
        query.getAllMeasurementTypes().first().find { it.key == MeasurementType.WEIGHT }?.id

    /**
     * Transforms a measurement by applying "Assisted Weighing" logic.
     * It calculates the weight difference and returns the new list of values.
     *
     * @return A new list containing only the calculated difference value.
     */
    suspend fun applyAssistedWeighing(
        measurement: Measurement,
        totalWeightValues: List<MeasurementValue>,
        referenceUser: User
    ): List<MeasurementValue> {
        val weightTypeId = weightTypeId() ?: return totalWeightValues
        val totalWeight = totalWeightValues.find { it.typeId == weightTypeId }?.floatValue ?: 0f
        val lastReferenceWeight = query.getMeasurementsForUser(referenceUser.id).first()
            .firstNotNullOfOrNull { m -> m.values.find { v -> v.type.key == MeasurementType.WEIGHT }?.value?.floatValue } ?: 0f

        val diffWeight = totalWeight - lastReferenceWeight
        val diffWeightMeasurementValue = MeasurementValue(
            typeId = weightTypeId,
            floatValue = diffWeight,
            measurementId = measurement.id // placeholder
        )

        LogManager.i("MeasurementTransformation", "Assisted Weighing: Total=$totalWeight, Ref=$lastReferenceWeight, Diff=$diffWeight.")
        return listOf(diffWeightMeasurementValue)
    }

    /**
     * Transforms a standard measurement by applying "Smart User Assignment" logic if enabled.
     * It determines the correct user or decides to ignore the measurement.
     *
     * @return The (potentially modified) Measurement object, or null if the measurement should be ignored.
     */
    suspend fun applySmartUserAssignment(
        measurement: Measurement,
        values: List<MeasurementValue>
    ): Measurement? {
        val isSmartAssignment = settings.isSmartAssignmentEnabled.first()
        if (!isSmartAssignment) {
            return measurement
        }

        val newWeight = weightTypeId()?.let { id -> values.find { it.typeId == id } }?.floatValue
        if (newWeight == null) {
            return measurement // No weight to assign, save as is
        }

        // 1. Find candidates
        val allUsers = userUseCases.observeAllUsers().first()
        val userDiffs = allUsers.mapNotNull { user ->
            val lastWeight = query.getMeasurementsForUser(user.id).first()
                .firstNotNullOfOrNull { m -> m.values.find { v -> v.type.key == MeasurementType.WEIGHT }?.value?.floatValue }

            if (lastWeight != null && lastWeight > 0) {
                val diffPercent = (abs(newWeight - lastWeight) / lastWeight) * 100
                LogManager.d("SmartAssignment", "User ${user.name}: last weight=$lastWeight, new=$newWeight, diff=${diffPercent.roundToInt()}%")
                Triple(user, lastWeight, diffPercent)
            } else {
                null
            }
        }

        if (userDiffs.isEmpty()) {
            LogManager.i("SmartAssignment", "No users with previous weight found. Assigning to current user.")
            return measurement
        }

        // 2. Find best candidate
        val bestMatch = userDiffs.minByOrNull { it.third }!!

        // 3. Plausibility check
        val tolerancePercent = settings.smartAssignmentTolerancePercent.first()
        val ignoreOutsideTolerance = settings.smartAssignmentIgnoreOutsideTolerance.first()

        val winningUser: User? = if (bestMatch.third <= tolerancePercent) {
            bestMatch.first
        } else {
            if (ignoreOutsideTolerance) null else allUsers.find { it.id == measurement.userId }
        }

        // 4. Return result
        return if (winningUser != null) {
            LogManager.i("SmartAssignment", "Assigning measurement to ${winningUser.name}.")
            measurement.copy(userId = winningUser.id)
        } else {
            LogManager.i("SmartAssignment", "Ignoring measurement as no user is within tolerance.")
            null // Signal to ignore
        }
    }

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
        val types = query.getAllMeasurementTypes().first()
        val byKey = types.associateBy { it.key }

        // Anchor: weight (in kg)
        val weightType = byKey[MeasurementType.WEIGHT] ?: return values
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
        val bmiType = byKey[MeasurementType.BMI]
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
        byKey[MeasurementType.BODY_FAT]?.let { type ->
            if (bfOpt != BodyFatFormulaOption.OFF) {
                val bf = when (bfOpt) {
                    BodyFatFormulaOption.US_NAVY -> {
                        val waistIn = values.find { it.typeId == byKey[MeasurementType.WAIST]?.id }?.let {
                            it.floatValue?.let { value ->
                                ConverterUtils.convertFloatValueUnit(value, byKey[MeasurementType.WAIST]!!.unit, UnitType.INCH)
                            }
                        }
                        val neckIn = values.find { it.typeId == byKey[MeasurementType.NECK]?.id }?.let {
                            it.floatValue?.let { value ->
                                ConverterUtils.convertFloatValueUnit(value, byKey[MeasurementType.NECK]!!.unit, UnitType.INCH)
                            }
                        }
                        val heightIn = (heightCm / 2.54).toFloat()

                        if (waistIn != null && waistIn > 0f && neckIn != null && neckIn > 0f && heightIn > 0f) {
                            if (isMale) {
                                val diff = waistIn - neckIn
                                if (diff > 0f) {
                                    86.010f * log10(diff) - 70.041f * log10(heightIn) + 36.76f
                                } else null
                            } else {
                                val hipsIn = values.find { it.typeId == byKey[MeasurementType.HIPS]?.id }?.let {
                                    it.floatValue?.let { value ->
                                        ConverterUtils.convertFloatValueUnit(value, byKey[MeasurementType.HIPS]!!.unit, UnitType.INCH)
                                    }
                                }
                                if (hipsIn != null && hipsIn > 0f) {
                                    val diff = waistIn + hipsIn - neckIn
                                    if (diff > 0f) {
                                        163.205f * log10(diff) - 97.684f * log10(heightIn) - 78.387f
                                    } else null
                                } else null
                            }
                        } else null
                    }
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
                }?.toDouble()

                val newVal = bf?.toFloat()
                    ?.coerceIn(1.0f, 70.0f)
                    ?.let { CalculationUtils.roundTo(it) }

                upsertFloat(type, newVal)
            }
        }

        // --- BODY WATER (stored either as %, or mass depending on type.unit)
        byKey[MeasurementType.WATER]?.let { type ->
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
        byKey[MeasurementType.LBM]?.let { type ->
            if (lbmOpt != LbmFormulaOption.OFF) {
                val lbmKg = when (lbmOpt) {
                    LbmFormulaOption.WEIGHT_MINUS_BODY_FAT -> {
                        val bfType = byKey[MeasurementType.BODY_FAT]
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
     * Fills the gaps in [measurement] from the chronologically preceding measurement of the same
     * user — the one and only inheritance rule in the app, used both when a scale delivers a
     * measurement and when the "new measurement" form is pre-filled.
     *
     * A scale only reports what it can weigh, so everything the user keeps by hand — waist, hips,
     * caliper values, custom types — would silently be absent on every sync. Those are taken from
     * the last measurement **before** [measurement]'s timestamp, so a historic entry read from the
     * scale's memory inherits the state of its own point in time and never that of a later one.
     *
     * Inherited are only **numeric** values (FLOAT/INT) that still carry information:
     * - Values already in [values] win — only missing types are filled.
     * - An empty predecessor (`null` or `0`) is dropped; `0` says nothing worth repeating.
     * - TEXT, DATE, TIME and USER are dropped: a comment or a date is written about *that* one
     *   weigh-in and would be wrong on the next.
     * - Derived types (BMI, BMR, …) are dropped — they are recomputed from the raw values.
     * - Internal (impedance) and disabled types are dropped: raw device input, stale the moment it
     *   is copied.
     *
     * The weight needs no special case: every scale reports one, so on the sync path it is already
     * in [values] and is left alone. Only the form pre-fill passes an empty [values] and therefore
     * gets the previous weight as its starting point.
     *
     * No DB writes here; the caller persists via CRUD.
     *
     * @return [values] plus the inherited ones, or [values] unchanged if there is nothing to inherit.
     */
    suspend fun applyValueInheritance(
        measurement: Measurement,
        values: List<MeasurementValue>
    ): List<MeasurementValue> {
        // History is ordered newest → oldest, so the first strictly older entry is the predecessor.
        val previous = query.getMeasurementsForUser(measurement.userId).first()
            .firstOrNull {
                it.measurement.id != measurement.id &&
                    it.measurement.timestamp < measurement.timestamp
            } ?: return values

        val presentTypeIds = values.map { it.typeId }.toSet()

        val carried = previous.values.mapNotNull { previousValue ->
            val type = previousValue.type
            if (type.id in presentTypeIds) return@mapNotNull null
            if (type.isDerived || type.isInternal || !type.isEnabled) return@mapNotNull null

            when (type.inputType) {
                InputFieldType.FLOAT -> previousValue.value.floatValue
                    ?.takeIf { it != 0f }
                    ?.let { MeasurementValue(measurementId = 0, typeId = type.id, floatValue = it) }
                InputFieldType.INT -> previousValue.value.intValue
                    ?.takeIf { it != 0 }
                    ?.let { MeasurementValue(measurementId = 0, typeId = type.id, intValue = it) }
                InputFieldType.TEXT, InputFieldType.DATE,
                InputFieldType.TIME, InputFieldType.USER, InputFieldType.IMAGE -> null
            }
        }

        if (carried.isEmpty()) return values

        LogManager.i(
            "MeasurementTransformation",
            "Carry-over: inherited ${carried.size} value(s) from measurement ${previous.measurement.id}."
        )
        return values + carried
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

        val weightTypeId = weightTypeId() ?: return values
        val weightIndex = values.indexOfFirst { it.typeId == weightTypeId }
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

    /**
     * Applies professional scale calibration offsets (Δ) to incoming measurement values.
     *
     * Offsets represent additive intercepts for each body composition compartment,
     * computed from a same-day professional reference scan (e.g. InBody, Tanita, DEXA).
     *
     * Rules:
     * - Only applied if calibration is enabled globally in settings.
     * - Only applied if an active calibration profile exists for the user.
     * - Forward-only: measurement timestamp must be >= calibration.scaleMeasurementTimestamp.
     * - Impedance required: only measurements with raw impedance > 0 are calibrated.
     * - All mass offsets are added in kilograms, preserving regression slopes.
     * - Body Fat is derived from calibrated FFM (Mass Conservation).
     *
     * @return [values] with calibrated values replacing or augmenting uncalibrated ones.
     */
    suspend fun applyBodyScanCalibration(
        measurement: Measurement,
        values: List<MeasurementValue>,
        calibration: BodyScanCalibration? = null
    ): List<MeasurementValue> {
        val isEnabled = settings.isBodyScanCalibrationEnabled.first()
        if (!isEnabled) {
            return values
        }

        val activeCalibration = calibration
            ?: databaseRepository.getBodyScanCalibrationForUser(measurement.userId)
            ?: return values

        if (measurement.timestamp < activeCalibration.scaleMeasurementTimestamp) {
            return values
        }

        val types = query.getAllMeasurementTypes().first()
        val byKey = types.associateBy { it.key }

        // Bioimpedance check: scale must report impedance > 0
        val impedanceType = byKey[MeasurementType.IMPEDANCE]
        val impedanceVal = impedanceType?.let { t -> values.find { it.typeId == t.id }?.floatValue }
        if (impedanceVal == null || impedanceVal <= 0f) {
            return values
        }

        // Anchor: weight in kg
        val weightType = byKey[MeasurementType.WEIGHT] ?: return values
        val weightVal = values.find { it.typeId == weightType.id }?.floatValue ?: return values
        val weightKg = when (weightType.unit) {
            UnitType.KG -> weightVal
            UnitType.LB -> ConverterUtils.toKilogram(weightVal, WeightUnit.LB)
            UnitType.ST -> ConverterUtils.toKilogram(weightVal, WeightUnit.ST)
            else -> return values
        }
        if (weightKg <= 0f) {
            return values
        }

        val out = values.toMutableList()

        fun upsertFloat(type: MeasurementType, newValue: Float?) {
            val idx = out.indexOfFirst { it.typeId == type.id }
            if (idx >= 0) {
                out[idx] = out[idx].copy(floatValue = newValue)
            } else if (newValue != null) {
                out.add(
                    MeasurementValue(
                        id = 0,
                        measurementId = measurement.id,
                        typeId = type.id,
                        floatValue = newValue
                    )
                )
            }
        }

        // Reference masses at t0 for damped hybrid allometric scaling (gamma = 0.5)
        val hasValidBaseline = activeCalibration.scaleBaselineWeightKg > 0f && activeCalibration.scanWeightKg > 0f
        val normRatio = if (hasValidBaseline) activeCalibration.scaleBaselineWeightKg / activeCalibration.scanWeightKg else null
        val refFfmKg0 = if (hasValidBaseline) {
            activeCalibration.scanFatPercent?.let { activeCalibration.scaleBaselineWeightKg * (1f - it / 100f) }
        } else null
        val refMuscleKg0 = if (normRatio != null) activeCalibration.scanMuscleKg?.let { it * normRatio } else null
        val refWaterKg0 = if (normRatio != null) activeCalibration.scanWaterLiters?.let { (it * 0.995f) * normRatio } else null
        val refProteinKg0 = if (normRatio != null) activeCalibration.scanProteinKg?.let { it * normRatio } else null

        var calibratedFfmKgForVf: Float? = null

        // --- A. FFM (LBM) & BODY FAT (%) (Mass conservation) ---
        activeCalibration.offsetFfmKg?.let { deltaFfmKg ->
            val lbmType = byKey[MeasurementType.LBM]
            val bfType = byKey[MeasurementType.BODY_FAT]

            val uncalibratedFfmKg: Float? = run {
                val lbmVal = lbmType?.let { t -> out.find { it.typeId == t.id }?.floatValue }
                if (lbmVal != null) {
                    when (lbmType.unit) {
                        UnitType.KG -> lbmVal
                        UnitType.LB -> ConverterUtils.toKilogram(lbmVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(lbmVal, WeightUnit.ST)
                        else -> lbmVal
                    }
                } else {
                    val bfVal = bfType?.let { t -> out.find { it.typeId == t.id }?.floatValue }
                    bfVal?.let { weightKg * (1f - it / 100f) }
                }
            }

            if (uncalibratedFfmKg != null) {
                val scaledDeltaFfm = computeDampedScaledOffset(uncalibratedFfmKg, deltaFfmKg, refFfmKg0)
                val calibratedFfmKg = (uncalibratedFfmKg + scaledDeltaFfm)
                    .coerceIn(weightKg * 0.10f, weightKg * 0.98f)
                calibratedFfmKgForVf = calibratedFfmKg

                lbmType?.let { type ->
                    val calibratedLbmVal = when (type.unit) {
                        UnitType.KG -> CalculationUtils.roundTo(calibratedFfmKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedFfmKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedFfmKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(calibratedFfmKg)
                    }
                    upsertFloat(type, calibratedLbmVal)
                }

                bfType?.let { type ->
                    val calibratedBfPercent = (((weightKg - calibratedFfmKg) / weightKg) * 100f)
                        .coerceIn(1.0f, 75.0f)
                    upsertFloat(type, CalculationUtils.roundTo(calibratedBfPercent))
                }
            }
        }

        // --- B. MUSCLE (%) ---
        activeCalibration.offsetMuscleKg?.let { deltaMuscleKg ->
            byKey[MeasurementType.MUSCLE]?.let { type ->
                val uncalibratedVal = out.find { it.typeId == type.id }?.floatValue
                if (uncalibratedVal != null) {
                    val uncalibratedMuscleKg = when (type.unit) {
                        UnitType.PERCENT -> (uncalibratedVal / 100f) * weightKg
                        UnitType.KG -> uncalibratedVal
                        UnitType.LB -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.ST)
                        else -> (uncalibratedVal / 100f) * weightKg
                    }
                    val scaledDeltaMuscle = computeDampedScaledOffset(uncalibratedMuscleKg, deltaMuscleKg, refMuscleKg0)
                    val calibratedMuscleKg = (uncalibratedMuscleKg + scaledDeltaMuscle).coerceIn(0f, weightKg)
                    val calibratedVal = when (type.unit) {
                        UnitType.PERCENT -> CalculationUtils.roundTo(((calibratedMuscleKg / weightKg) * 100f).coerceIn(1f, 90f))
                        UnitType.KG -> CalculationUtils.roundTo(calibratedMuscleKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedMuscleKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedMuscleKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(((calibratedMuscleKg / weightKg) * 100f).coerceIn(1f, 90f))
                    }
                    upsertFloat(type, calibratedVal)
                }
            }
        }

        // --- C. TOTAL BODY WATER (%) ---
        activeCalibration.offsetWaterKg?.let { deltaWaterKg ->
            byKey[MeasurementType.WATER]?.let { type ->
                val uncalibratedVal = out.find { it.typeId == type.id }?.floatValue
                if (uncalibratedVal != null) {
                    val uncalibratedWaterKg = when (type.unit) {
                        UnitType.PERCENT -> (uncalibratedVal / 100f) * weightKg
                        UnitType.KG -> uncalibratedVal
                        UnitType.LB -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.ST)
                        else -> (uncalibratedVal / 100f) * weightKg
                    }
                    val scaledDeltaWater = computeDampedScaledOffset(uncalibratedWaterKg, deltaWaterKg, refWaterKg0)
                    val calibratedWaterKg = (uncalibratedWaterKg + scaledDeltaWater).coerceIn(0f, weightKg)
                    val calibratedVal = when (type.unit) {
                        UnitType.PERCENT -> CalculationUtils.roundTo(((calibratedWaterKg / weightKg) * 100f).coerceIn(1f, 95f))
                        UnitType.KG -> CalculationUtils.roundTo(calibratedWaterKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedWaterKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedWaterKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(((calibratedWaterKg / weightKg) * 100f).coerceIn(1f, 95f))
                    }
                    upsertFloat(type, calibratedVal)
                }
            }
        }

        // --- D. BONE MASS (kg) ---
        activeCalibration.offsetBoneKg?.let { deltaBoneKg ->
            byKey[MeasurementType.BONE]?.let { type ->
                val uncalibratedVal = out.find { it.typeId == type.id }?.floatValue
                if (uncalibratedVal != null) {
                    val uncalibratedBoneKg = when (type.unit) {
                        UnitType.KG -> uncalibratedVal
                        UnitType.LB -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.ST)
                        else -> uncalibratedVal
                    }
                    val calibratedBoneKg = (uncalibratedBoneKg + deltaBoneKg).coerceIn(0.5f, 15f)
                    val calibratedVal = when (type.unit) {
                        UnitType.KG -> CalculationUtils.roundTo(calibratedBoneKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedBoneKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedBoneKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(calibratedBoneKg)
                    }
                    upsertFloat(type, calibratedVal)
                }
            }
        }

        // --- E. VISCERAL FAT (dimensionless level) ---
        activeCalibration.offsetVisceralFat?.let { deltaVf ->
            byKey[MeasurementType.VISCERAL_FAT]?.let { type ->
                val uncalibratedVal = out.find { it.typeId == type.id }?.floatValue
                if (uncalibratedVal != null) {
                    val calibratedVf = if (
                        calibratedFfmKgForVf != null &&
                        activeCalibration.scanFatPercent != null &&
                        activeCalibration.scaleBaselineWeightKg > 0f
                    ) {
                        val bfmCal0 = activeCalibration.scaleBaselineWeightKg * (activeCalibration.scanFatPercent / 100f)
                        val bfmCalN = weightKg - calibratedFfmKgForVf
                        val deltaBfmActual = bfmCalN - bfmCal0
                        val deltaBfmAssumed = 0.75f * (weightKg - activeCalibration.scaleBaselineWeightKg)
                        (uncalibratedVal + deltaVf + 0.20f * (deltaBfmActual - deltaBfmAssumed)).coerceAtLeast(1.0f)
                    } else {
                        (uncalibratedVal + deltaVf).coerceAtLeast(1.0f)
                    }
                    upsertFloat(type, CalculationUtils.roundTo(calibratedVf))
                }
            }
        }

        // --- F. PROTEIN (%) ---
        activeCalibration.offsetProteinKg?.let { deltaProteinKg ->
            byKey[MeasurementType.PROTEIN]?.let { type ->
                val uncalibratedVal = out.find { it.typeId == type.id }?.floatValue
                if (uncalibratedVal != null) {
                    val uncalibratedProteinKg = when (type.unit) {
                        UnitType.PERCENT -> (uncalibratedVal / 100f) * weightKg
                        UnitType.KG -> uncalibratedVal
                        UnitType.LB -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(uncalibratedVal, WeightUnit.ST)
                        else -> (uncalibratedVal / 100f) * weightKg
                    }
                    val scaledDeltaProtein = computeDampedScaledOffset(uncalibratedProteinKg, deltaProteinKg, refProteinKg0)
                    val calibratedProteinKg = (uncalibratedProteinKg + scaledDeltaProtein).coerceIn(0f, weightKg)
                    val calibratedVal = when (type.unit) {
                        UnitType.PERCENT -> CalculationUtils.roundTo(((calibratedProteinKg / weightKg) * 100f).coerceIn(1f, 50f))
                        UnitType.KG -> CalculationUtils.roundTo(calibratedProteinKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedProteinKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(calibratedProteinKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(((calibratedProteinKg / weightKg) * 100f).coerceIn(1f, 50f))
                    }
                    upsertFloat(type, calibratedVal)
                }
            }
        }

        LogManager.i(
            "MeasurementTransformation",
            "Professional scale calibration applied for user ${measurement.userId} on measurement ${measurement.id}."
        )
        return out
    }

    /**
     * Reverts professional scale calibration offsets on measurement values, returning them to their
     * uncalibrated state.
     *
     * @param measurement Target measurement.
     * @param values Values currently containing calibrated values.
     * @param calibration Optional explicit calibration profile to revert. If null, the active
     *                    calibration for [measurement.userId] is fetched from the database.
     */
    suspend fun revertBodyScanCalibration(
        measurement: Measurement,
        values: List<MeasurementValue>,
        calibration: BodyScanCalibration? = null
    ): List<MeasurementValue> {
        val activeCalibration = calibration
            ?: databaseRepository.getBodyScanCalibrationForUser(measurement.userId)
            ?: return values

        val types = query.getAllMeasurementTypes().first()
        val byKey = types.associateBy { it.key }

        // Bioimpedance check: scale must report impedance > 0
        val impedanceType = byKey[MeasurementType.IMPEDANCE]
        val impedanceVal = impedanceType?.let { t -> values.find { it.typeId == t.id }?.floatValue }
        if (impedanceVal == null || impedanceVal <= 0f) {
            return values
        }

        // Anchor: weight in kg
        val weightType = byKey[MeasurementType.WEIGHT] ?: return values
        val weightVal = values.find { it.typeId == weightType.id }?.floatValue ?: return values
        val weightKg = when (weightType.unit) {
            UnitType.KG -> weightVal
            UnitType.LB -> ConverterUtils.toKilogram(weightVal, WeightUnit.LB)
            UnitType.ST -> ConverterUtils.toKilogram(weightVal, WeightUnit.ST)
            else -> return values
        }
        if (weightKg <= 0f) {
            return values
        }

        val out = values.toMutableList()

        fun upsertFloat(type: MeasurementType, newValue: Float?) {
            val idx = out.indexOfFirst { it.typeId == type.id }
            if (idx >= 0) {
                out[idx] = out[idx].copy(floatValue = newValue)
            } else if (newValue != null) {
                out.add(
                    MeasurementValue(
                        id = 0,
                        measurementId = measurement.id,
                        typeId = type.id,
                        floatValue = newValue
                    )
                )
            }
        }

        // Reference masses at t0 for reverting damped hybrid allometric scaling (gamma = 0.5)
        val hasValidBaseline = activeCalibration.scaleBaselineWeightKg > 0f && activeCalibration.scanWeightKg > 0f
        val normRatio = if (hasValidBaseline) activeCalibration.scaleBaselineWeightKg / activeCalibration.scanWeightKg else null
        val refFfmKg0 = if (hasValidBaseline) {
            activeCalibration.scanFatPercent?.let { activeCalibration.scaleBaselineWeightKg * (1f - it / 100f) }
        } else null
        val refMuscleKg0 = if (normRatio != null) activeCalibration.scanMuscleKg?.let { it * normRatio } else null
        val refWaterKg0 = if (normRatio != null) activeCalibration.scanWaterLiters?.let { (it * 0.995f) * normRatio } else null
        val refProteinKg0 = if (normRatio != null) activeCalibration.scanProteinKg?.let { it * normRatio } else null

        var currentFfmKgForVf: Float? = null

        // --- A. FFM (LBM) & BODY FAT (%) (Revert mass conservation) ---
        activeCalibration.offsetFfmKg?.let { deltaFfmKg ->
            val lbmType = byKey[MeasurementType.LBM]
            val bfType = byKey[MeasurementType.BODY_FAT]

            val currentFfmKg: Float? = run {
                val lbmVal = lbmType?.let { t -> out.find { it.typeId == t.id }?.floatValue }
                if (lbmVal != null) {
                    when (lbmType.unit) {
                        UnitType.KG -> lbmVal
                        UnitType.LB -> ConverterUtils.toKilogram(lbmVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(lbmVal, WeightUnit.ST)
                        else -> lbmVal
                    }
                } else {
                    val bfVal = bfType?.let { t -> out.find { it.typeId == t.id }?.floatValue }
                    bfVal?.let { weightKg * (1f - it / 100f) }
                }
            }

            if (currentFfmKg != null) {
                currentFfmKgForVf = currentFfmKg
                val uncalibratedFfmKg = revertDampedScaledMass(currentFfmKg, deltaFfmKg, refFfmKg0)
                    .coerceIn(weightKg * 0.10f, weightKg * 0.98f)

                lbmType?.let { type ->
                    val uncalibratedLbmVal = when (type.unit) {
                        UnitType.KG -> CalculationUtils.roundTo(uncalibratedFfmKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedFfmKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedFfmKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(uncalibratedFfmKg)
                    }
                    upsertFloat(type, uncalibratedLbmVal)
                }

                bfType?.let { type ->
                    val uncalibratedBfPercent = (((weightKg - uncalibratedFfmKg) / weightKg) * 100f)
                        .coerceIn(1.0f, 75.0f)
                    upsertFloat(type, CalculationUtils.roundTo(uncalibratedBfPercent))
                }
            }
        }

        // --- B. MUSCLE (%) ---
        activeCalibration.offsetMuscleKg?.let { deltaMuscleKg ->
            byKey[MeasurementType.MUSCLE]?.let { type ->
                val currentVal = out.find { it.typeId == type.id }?.floatValue
                if (currentVal != null) {
                    val currentMuscleKg = when (type.unit) {
                        UnitType.PERCENT -> (currentVal / 100f) * weightKg
                        UnitType.KG -> currentVal
                        UnitType.LB -> ConverterUtils.toKilogram(currentVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(currentVal, WeightUnit.ST)
                        else -> (currentVal / 100f) * weightKg
                    }
                    val uncalibratedMuscleKg = revertDampedScaledMass(currentMuscleKg, deltaMuscleKg, refMuscleKg0).coerceIn(0f, weightKg)
                    val uncalibratedVal = when (type.unit) {
                        UnitType.PERCENT -> CalculationUtils.roundTo(((uncalibratedMuscleKg / weightKg) * 100f).coerceIn(1f, 90f))
                        UnitType.KG -> CalculationUtils.roundTo(uncalibratedMuscleKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedMuscleKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedMuscleKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(((uncalibratedMuscleKg / weightKg) * 100f).coerceIn(1f, 90f))
                    }
                    upsertFloat(type, uncalibratedVal)
                }
            }
        }

        // --- C. TOTAL BODY WATER (%) ---
        activeCalibration.offsetWaterKg?.let { deltaWaterKg ->
            byKey[MeasurementType.WATER]?.let { type ->
                val currentVal = out.find { it.typeId == type.id }?.floatValue
                if (currentVal != null) {
                    val currentWaterKg = when (type.unit) {
                        UnitType.PERCENT -> (currentVal / 100f) * weightKg
                        UnitType.KG -> currentVal
                        UnitType.LB -> ConverterUtils.toKilogram(currentVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(currentVal, WeightUnit.ST)
                        else -> (currentVal / 100f) * weightKg
                    }
                    val uncalibratedWaterKg = revertDampedScaledMass(currentWaterKg, deltaWaterKg, refWaterKg0).coerceIn(0f, weightKg)
                    val uncalibratedVal = when (type.unit) {
                        UnitType.PERCENT -> CalculationUtils.roundTo(((uncalibratedWaterKg / weightKg) * 100f).coerceIn(1f, 95f))
                        UnitType.KG -> CalculationUtils.roundTo(uncalibratedWaterKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedWaterKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedWaterKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(((uncalibratedWaterKg / weightKg) * 100f).coerceIn(1f, 95f))
                    }
                    upsertFloat(type, uncalibratedVal)
                }
            }
        }

        // --- D. BONE MASS (kg) ---
        activeCalibration.offsetBoneKg?.let { deltaBoneKg ->
            byKey[MeasurementType.BONE]?.let { type ->
                val currentVal = out.find { it.typeId == type.id }?.floatValue
                if (currentVal != null) {
                    val currentBoneKg = when (type.unit) {
                        UnitType.KG -> currentVal
                        UnitType.LB -> ConverterUtils.toKilogram(currentVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(currentVal, WeightUnit.ST)
                        else -> currentVal
                    }
                    val uncalibratedBoneKg = (currentBoneKg - deltaBoneKg).coerceIn(0.5f, 15f)
                    val uncalibratedVal = when (type.unit) {
                        UnitType.KG -> CalculationUtils.roundTo(uncalibratedBoneKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedBoneKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedBoneKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(uncalibratedBoneKg)
                    }
                    upsertFloat(type, uncalibratedVal)
                }
            }
        }

        // --- E. VISCERAL FAT (dimensionless level) ---
        activeCalibration.offsetVisceralFat?.let { deltaVf ->
            byKey[MeasurementType.VISCERAL_FAT]?.let { type ->
                val currentVal = out.find { it.typeId == type.id }?.floatValue
                if (currentVal != null) {
                    val uncalibratedVf = if (
                        currentFfmKgForVf != null &&
                        activeCalibration.scanFatPercent != null &&
                        activeCalibration.scaleBaselineWeightKg > 0f
                    ) {
                        val bfmCal0 = activeCalibration.scaleBaselineWeightKg * (activeCalibration.scanFatPercent / 100f)
                        val bfmCalN = weightKg - currentFfmKgForVf
                        val deltaBfmActual = bfmCalN - bfmCal0
                        val deltaBfmAssumed = 0.75f * (weightKg - activeCalibration.scaleBaselineWeightKg)
                        (currentVal - deltaVf - 0.20f * (deltaBfmActual - deltaBfmAssumed)).coerceAtLeast(1.0f)
                    } else {
                        (currentVal - deltaVf).coerceAtLeast(1.0f)
                    }
                    upsertFloat(type, CalculationUtils.roundTo(uncalibratedVf))
                }
            }
        }

        // --- F. PROTEIN (%) ---
        activeCalibration.offsetProteinKg?.let { deltaProteinKg ->
            byKey[MeasurementType.PROTEIN]?.let { type ->
                val currentVal = out.find { it.typeId == type.id }?.floatValue
                if (currentVal != null) {
                    val currentProteinKg = when (type.unit) {
                        UnitType.PERCENT -> (currentVal / 100f) * weightKg
                        UnitType.KG -> currentVal
                        UnitType.LB -> ConverterUtils.toKilogram(currentVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(currentVal, WeightUnit.ST)
                        else -> (currentVal / 100f) * weightKg
                    }
                    val uncalibratedProteinKg = revertDampedScaledMass(currentProteinKg, deltaProteinKg, refProteinKg0).coerceIn(0f, weightKg)
                    val uncalibratedVal = when (type.unit) {
                        UnitType.PERCENT -> CalculationUtils.roundTo(((uncalibratedProteinKg / weightKg) * 100f).coerceIn(1f, 50f))
                        UnitType.KG -> CalculationUtils.roundTo(uncalibratedProteinKg)
                        UnitType.LB -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedProteinKg, WeightUnit.LB)
                        )
                        UnitType.ST -> CalculationUtils.roundTo(
                            ConverterUtils.fromKilogram(uncalibratedProteinKg, WeightUnit.ST)
                        )
                        else -> CalculationUtils.roundTo(((uncalibratedProteinKg / weightKg) * 100f).coerceIn(1f, 50f))
                    }
                    upsertFloat(type, uncalibratedVal)
                }
            }
        }

        LogManager.i(
            "MeasurementTransformation",
            "Professional scale calibration reverted for user ${measurement.userId} on measurement ${measurement.id}."
        )
        return out
    }

    /**
     * Damped hybrid allometric scaling factor (γ = 0.5) for lean mass compartments:
     * S_M = (0.5 + 0.5 * (M_raw_n / M_raw_0)).coerceIn(0.70, 1.30)
     * Scaled offset = Δ_M * S_M
     */
    private fun computeDampedScaledOffset(
        rawMassN: Float,
        deltaM: Float,
        refMass0: Float?
    ): Float {
        if (refMass0 == null) return deltaM
        val rawMass0 = refMass0 - deltaM
        if (rawMass0 <= 1.0f) return deltaM
        val sM = (0.5f + 0.5f * (rawMassN / rawMass0)).coerceIn(0.70f, 1.30f)
        return deltaM * sM
    }

    /**
     * Reverts damped hybrid allometric scaling to recover uncalibrated raw mass:
     * M_cal_n = M_raw_n + Δ_M * (0.5 + 0.5 * M_raw_n / M_raw_0)
     * M_raw_n = (M_cal_n - 0.5 * Δ_M) / (1.0 + 0.5 * Δ_M / M_raw_0)
     */
    private fun revertDampedScaledMass(
        calMassN: Float,
        deltaM: Float,
        refMass0: Float?
    ): Float {
        if (refMass0 == null) return calMassN - deltaM
        val rawMass0 = refMass0 - deltaM
        if (rawMass0 <= 1.0f) return calMassN - deltaM

        val denominator = 1.0f + (0.5f * deltaM / rawMass0)
        if (denominator <= 0.01f) return calMassN - deltaM
        val estimatedRaw = (calMassN - 0.5f * deltaM) / denominator
        val sM = (0.5f + 0.5f * (estimatedRaw / rawMass0)).coerceIn(0.70f, 1.30f)
        return when {
            sM <= 0.70f -> calMassN - (deltaM * 0.70f)
            sM >= 1.30f -> calMassN - (deltaM * 1.30f)
            else -> estimatedRaw
        }
    }
}
