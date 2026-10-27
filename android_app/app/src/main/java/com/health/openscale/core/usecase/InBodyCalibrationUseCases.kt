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

import com.health.openscale.core.data.InBodyCalibration
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data holder for inputs entered by the user from an InBody test result sheet.
 */
data class InBodyFormInput(
    val inBodyWeightKg: Float,
    val inBodyFatPercent: Float? = null,
    val inBodyFatKg: Float? = null,
    val inBodyMuscleKg: Float? = null,
    val inBodyWaterLiters: Float? = null,
    val inBodyBoneKg: Float? = null,
    val inBodyVisceralFat: Float? = null,
    val inBodyProteinKg: Float? = null
)

/**
 * Use cases managing InBody calibrations.
 *
 * Responsibilities:
 * - Validate user inputs and baseline smart scale measurements.
 * - Calculate per-compartment additive intercept offsets (Δ in Kg) with weight normalization.
 * - Persist and observe active user calibrations.
 * - Recalculate baseline measurements immediately upon calibration save.
 */
@Singleton
class InBodyCalibrationUseCases @Inject constructor(
    private val databaseRepository: DatabaseRepository,
    private val settings: SettingsFacade,
    private val query: MeasurementQueryUseCases,
    private val transformation: MeasurementTransformationUseCase
) {
    private val TAG = "InBodyCalibrationUseCases"

    /** Observes the active calibration record for a user. */
    fun observeCalibrationForUser(userId: Int): Flow<InBodyCalibration?> =
        databaseRepository.observeInBodyCalibrationForUser(userId)

    /** Gets the active calibration record for a user. */
    suspend fun getCalibrationForUser(userId: Int): InBodyCalibration? =
        databaseRepository.getInBodyCalibrationForUser(userId)

    /** Deletes the calibration record for a user and reverts baseline offsets. */
    suspend fun deleteCalibrationForUser(userId: Int) {
        LogManager.i(TAG, "Deleting InBody calibration for user $userId")
        val existingCal = databaseRepository.getInBodyCalibrationForUser(userId)
        if (existingCal != null) {
            val measurements = databaseRepository.getMeasurementsWithValuesForUser(userId).first()
            val baseline = measurements.find { it.measurement.timestamp == existingCal.scaleMeasurementTimestamp }
            if (baseline != null) {
                LogManager.i(TAG, "Reverting baseline measurement ${baseline.measurement.id} to uncalibrated values")
                val revertedValues = transformation.revertInBodyCalibration(
                    baseline.measurement,
                    baseline.values.map { it.value },
                    existingCal
                )
                revertedValues.forEach { v ->
                    if (v.id != 0) {
                        databaseRepository.updateMeasurementValue(v)
                    }
                }
                databaseRepository.recalculateDerivedValuesForMeasurement(baseline.measurement.id)
            }
        }
        databaseRepository.deleteInBodyCalibrationForUser(userId)
    }

    /**
     * Observes measurements for [userId] that are eligible as calibration baselines
     * (must have raw bioimpedance > 0).
     */
    fun getEligibleBaselineMeasurements(userId: Int): Flow<List<MeasurementWithValues>> =
        query.getMeasurementsForUser(userId).map { list ->
            list.filter { mwv ->
                val impedance = mwv.values.find { it.type.key == MeasurementType.IMPEDANCE }?.value?.floatValue
                impedance != null && impedance > 0f
            }
        }

    /**
     * Validates that the form inputs and baseline measurement meet requirements.
     *
     * @return Error description if invalid, or null if inputs are valid.
     */
    fun validateInputs(input: InBodyFormInput, baseline: MeasurementWithValues?): String? {
        if (baseline == null) {
            return "Please select a baseline scale measurement."
        }
        val impedance = baseline.values.find { it.type.key == MeasurementType.IMPEDANCE }?.value?.floatValue
        if (impedance == null || impedance <= 0f) {
            return "The selected scale measurement does not contain bioelectrical impedance data."
        }
        val hasBodyFat = baseline.values.any { it.type.key == MeasurementType.BODY_FAT || it.type.key == MeasurementType.LBM }
        if (!hasBodyFat) {
            return "The selected scale measurement is missing body composition data (fat/muscle). Please select a complete weigh-in taken with bare feet."
        }
        if (input.inBodyWeightKg <= 10f || input.inBodyWeightKg > 350f) {
            return "InBody weight must be between 10 kg and 350 kg."
        }
        if (input.inBodyFatPercent != null && (input.inBodyFatPercent < 1f || input.inBodyFatPercent > 70f)) {
            return "Body fat percentage must be between 1% and 70%."
        }
        if (input.inBodyFatKg != null && (input.inBodyFatKg <= 0f || input.inBodyFatKg >= input.inBodyWeightKg)) {
            return "Body fat mass must be positive and less than total weight."
        }
        if (input.inBodyMuscleKg != null && (input.inBodyMuscleKg <= 0f || input.inBodyMuscleKg >= input.inBodyWeightKg)) {
            return "Skeletal muscle mass must be positive and less than total weight."
        }
        if (input.inBodyWaterLiters != null && (input.inBodyWaterLiters <= 0f || input.inBodyWaterLiters >= input.inBodyWeightKg)) {
            return "Total body water must be positive and less than total weight."
        }
        if (input.inBodyBoneKg != null && (input.inBodyBoneKg <= 0.1f || input.inBodyBoneKg > 15f)) {
            return "Bone mass must be between 0.1 kg and 15 kg."
        }
        if (input.inBodyVisceralFat != null && input.inBodyVisceralFat < 1f) {
            return "Visceral fat level must be at least 1."
        }
        if (input.inBodyProteinKg != null && (input.inBodyProteinKg <= 0f || input.inBodyProteinKg >= input.inBodyWeightKg)) {
            return "Protein mass must be positive and less than total weight."
        }
        return null
    }

    /**
     * Calculates the additive intercept offsets (Δ in Kg) for each compartment.
     *
     * Normalization:
     * Due to potential clothing or minor hydration differences between the clinical test
     * and the home scale weigh-in on calibration day, all InBody reference mass inputs
     * are normalized by the ratio (W_scale / W_InBody) before computing offsets.
     */
    suspend fun calculateCalibration(
        userId: Int,
        baseline: MeasurementWithValues,
        input: InBodyFormInput,
        scaleName: String? = null
    ): InBodyCalibration {
        // If an active calibration was already applied to baseline, reverse it first to recover
        // original uncalibrated values so recalibrating does not double-offset or zero out.
        val existingCal = getCalibrationForUser(userId)
        val isCalEnabled = settings.isInBodyCalibrationEnabled.first()
        val effectiveBaseline = if (existingCal != null &&
            isCalEnabled &&
            baseline.measurement.timestamp >= existingCal.scaleMeasurementTimestamp
        ) {
            val revertedValues = transformation.revertInBodyCalibration(
                baseline.measurement,
                baseline.values.map { it.value },
                existingCal
            )
            baseline.copy(
                values = baseline.values.map { vt ->
                    val newVal = revertedValues.find { it.typeId == vt.type.id } ?: vt.value
                    vt.copy(value = newVal)
                }
            )
        } else {
            baseline
        }

        val types = query.getAllMeasurementTypes().first()
        val byKey = types.associateBy { it.key }

        // 1. Baseline home scale weight (W0) in kg
        val weightItem = effectiveBaseline.values.find { it.type.key == MeasurementType.WEIGHT }
            ?: throw IllegalArgumentException("Baseline measurement lacks weight.")
        val scaleWeightVal = weightItem.value.floatValue
            ?: throw IllegalArgumentException("Baseline weight value is missing.")
        val scaleWeightKg = when (weightItem.type.unit) {
            UnitType.KG -> scaleWeightVal
            UnitType.LB -> ConverterUtils.toKilogram(scaleWeightVal, WeightUnit.LB)
            UnitType.ST -> ConverterUtils.toKilogram(scaleWeightVal, WeightUnit.ST)
            else -> scaleWeightVal
        }
        require(scaleWeightKg > 0f) { "Scale weight must be positive." }
        require(input.inBodyWeightKg > 0f) { "InBody weight must be positive." }

        // Normalization ratio: W0 / W_InBody
        val ratio = scaleWeightKg / input.inBodyWeightKg

        // --- A. FFM Offset (Kg) ---
        val inBodyFfmKg: Float? = when {
            input.inBodyFatPercent != null -> input.inBodyWeightKg * (1f - input.inBodyFatPercent / 100f)
            input.inBodyFatKg != null -> input.inBodyWeightKg - input.inBodyFatKg
            else -> null
        }
        val offsetFfmKg: Float? = inBodyFfmKg?.let { rawFfm ->
            val normalizedFfm = rawFfm * ratio
            val scaleFfmKg: Float? = run {
                val lbmItem = effectiveBaseline.values.find { it.type.key == MeasurementType.LBM }
                val lbmVal = lbmItem?.value?.floatValue
                if (lbmVal != null) {
                    when (lbmItem.type.unit) {
                        UnitType.KG -> lbmVal
                        UnitType.LB -> ConverterUtils.toKilogram(lbmVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(lbmVal, WeightUnit.ST)
                        else -> lbmVal
                    }
                } else {
                    val bfVal = effectiveBaseline.values.find { it.type.key == MeasurementType.BODY_FAT }?.value?.floatValue
                    bfVal?.let { scaleWeightKg * (1f - it / 100f) }
                }
            }
            scaleFfmKg?.let { normalizedFfm - it }
        }

        // --- B. Muscle Offset (Kg) ---
        val offsetMuscleKg: Float? = input.inBodyMuscleKg?.let { rawMuscle ->
            val normalizedMuscle = rawMuscle * ratio
            val muscleItem = effectiveBaseline.values.find { it.type.key == MeasurementType.MUSCLE }
            val muscleVal = muscleItem?.value?.floatValue
            val scaleMuscleKg: Float? = if (muscleVal != null) {
                when (muscleItem.type.unit) {
                    UnitType.PERCENT -> (muscleVal / 100f) * scaleWeightKg
                    UnitType.KG -> muscleVal
                    UnitType.LB -> ConverterUtils.toKilogram(muscleVal, WeightUnit.LB)
                    UnitType.ST -> ConverterUtils.toKilogram(muscleVal, WeightUnit.ST)
                    else -> (muscleVal / 100f) * scaleWeightKg
                }
            } else null
            scaleMuscleKg?.let { normalizedMuscle - it }
        }

        // --- C. Water Offset (Kg) ---
        // InBody measures TBW in Liters. Water at 36.5°C body temp is ~0.995 kg/L.
        val offsetWaterKg: Float? = input.inBodyWaterLiters?.let { rawLiters ->
            val rawWaterKg = rawLiters * 0.995f
            val normalizedWater = rawWaterKg * ratio
            val waterItem = effectiveBaseline.values.find { it.type.key == MeasurementType.WATER }
            val waterVal = waterItem?.value?.floatValue
            val scaleWaterKg: Float? = if (waterVal != null) {
                when (waterItem.type.unit) {
                    UnitType.PERCENT -> (waterVal / 100f) * scaleWeightKg
                    UnitType.KG -> waterVal
                    UnitType.LB -> ConverterUtils.toKilogram(waterVal, WeightUnit.LB)
                    UnitType.ST -> ConverterUtils.toKilogram(waterVal, WeightUnit.ST)
                    else -> (waterVal / 100f) * scaleWeightKg
                }
            } else null
            scaleWaterKg?.let { normalizedWater - it }
        }

        // --- D. Bone Mass Offset (Kg) ---
        val offsetBoneKg: Float? = input.inBodyBoneKg?.let { rawBone ->
            val normalizedBone = rawBone * ratio
            val boneItem = effectiveBaseline.values.find { it.type.key == MeasurementType.BONE }
            val boneVal = boneItem?.value?.floatValue
            val scaleBoneKg: Float? = if (boneVal != null) {
                when (boneItem.type.unit) {
                    UnitType.KG -> boneVal
                    UnitType.LB -> ConverterUtils.toKilogram(boneVal, WeightUnit.LB)
                    UnitType.ST -> ConverterUtils.toKilogram(boneVal, WeightUnit.ST)
                    else -> boneVal
                }
            } else null
            scaleBoneKg?.let { normalizedBone - it }
        }

        // --- E. Visceral Fat Offset (dimensionless level) ---
        val offsetVisceralFat: Float? = input.inBodyVisceralFat?.let { rawVf ->
            val vfVal = effectiveBaseline.values.find { it.type.key == MeasurementType.VISCERAL_FAT }?.value?.floatValue
            vfVal?.let { rawVf - it }
        }

        // --- F. Protein Offset (Kg) ---
        val offsetProteinKg: Float? = input.inBodyProteinKg?.let { rawProtein ->
            val normalizedProtein = rawProtein * ratio
            val proteinItem = effectiveBaseline.values.find { it.type.key == MeasurementType.PROTEIN }
            val proteinVal = proteinItem?.value?.floatValue
            val scaleProteinKg: Float? = if (proteinVal != null) {
                when (proteinItem.type.unit) {
                    UnitType.PERCENT -> (proteinVal / 100f) * scaleWeightKg
                    UnitType.KG -> proteinVal
                    UnitType.LB -> ConverterUtils.toKilogram(proteinVal, WeightUnit.LB)
                    UnitType.ST -> ConverterUtils.toKilogram(proteinVal, WeightUnit.ST)
                    else -> (proteinVal / 100f) * scaleWeightKg
                }
            } else null
            scaleProteinKg?.let { normalizedProtein - it }
        }

        val resolvedFatPercent = input.inBodyFatPercent
            ?: input.inBodyFatKg?.let { (it / input.inBodyWeightKg) * 100f }

        return InBodyCalibration(
            userId = userId,
            scaleMeasurementTimestamp = baseline.measurement.timestamp,
            scaleName = scaleName,
            inBodyWeightKg = input.inBodyWeightKg,
            inBodyFatPercent = resolvedFatPercent?.let { CalculationUtils.roundTo(it) },
            inBodyMuscleKg = input.inBodyMuscleKg?.let { CalculationUtils.roundTo(it) },
            inBodyWaterLiters = input.inBodyWaterLiters?.let { CalculationUtils.roundTo(it) },
            inBodyBoneKg = input.inBodyBoneKg?.let { CalculationUtils.roundTo(it) },
            inBodyVisceralFat = input.inBodyVisceralFat?.let { CalculationUtils.roundTo(it) },
            inBodyProteinKg = input.inBodyProteinKg?.let { CalculationUtils.roundTo(it) },
            offsetFfmKg = offsetFfmKg,
            offsetMuscleKg = offsetMuscleKg,
            offsetWaterKg = offsetWaterKg,
            offsetBoneKg = offsetBoneKg,
            offsetVisceralFat = offsetVisceralFat,
            offsetProteinKg = offsetProteinKg
        )
    }

    /**
     * Saves the calibration profile to the database and immediately recalibrates
     * the baseline measurement so that changes are visible right away.
     */
    suspend fun saveCalibration(
        calibration: InBodyCalibration,
        baselineMeasurement: MeasurementWithValues? = null
    ): Long {
        LogManager.i(TAG, "Saving InBody calibration for user ${calibration.userId}")
        val existingCal = databaseRepository.getInBodyCalibrationForUser(calibration.userId)
        val isCalEnabled = settings.isInBodyCalibrationEnabled.first()

        val id = databaseRepository.insertOrUpdateInBodyCalibration(calibration)

        if (baselineMeasurement != null) {
            LogManager.i(TAG, "Immediately recalibrating baseline measurement ${baselineMeasurement.measurement.id}")
            val rawValues = if (existingCal != null &&
                isCalEnabled &&
                baselineMeasurement.measurement.timestamp >= existingCal.scaleMeasurementTimestamp
            ) {
                transformation.revertInBodyCalibration(
                    baselineMeasurement.measurement,
                    baselineMeasurement.values.map { it.value },
                    existingCal
                )
            } else {
                baselineMeasurement.values.map { it.value }
            }

            val calibratedValues = transformation.applyInBodyCalibration(
                baselineMeasurement.measurement,
                rawValues,
                calibration
            )
            calibratedValues.forEach { v ->
                if (v.id != 0) {
                    databaseRepository.updateMeasurementValue(v)
                } else {
                    databaseRepository.insertMeasurementValue(v.copy(measurementId = baselineMeasurement.measurement.id))
                }
            }
            databaseRepository.recalculateDerivedValuesForMeasurement(baselineMeasurement.measurement.id)
        }

        return id
    }
}

