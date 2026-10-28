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

import com.health.openscale.core.data.BodyScanCalibration
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
 * Data holder for inputs entered by the user from a clinical body scan sheet (e.g. InBody, Tanita, DEXA).
 */
data class BodyScanFormInput(
    val scanWeightKg: Float,
    val scanFatPercent: Float? = null,
    val scanFatKg: Float? = null,
    val scanMuscleKg: Float? = null,
    val scanWaterLiters: Float? = null,
    val scanBoneKg: Float? = null,
    val scanVisceralFat: Float? = null,
    val scanProteinKg: Float? = null
)

/**
 * Use cases managing body scan calibrations.
 *
 * Responsibilities:
 * - Validate user inputs and baseline smart scale measurements.
 * - Calculate per-compartment additive intercept offsets (Δ in Kg) with weight normalization.
 * - Persist and observe active user calibrations.
 * - Recalculate baseline measurements immediately upon calibration save.
 */
@Singleton
class BodyScanCalibrationUseCases @Inject constructor(
    private val databaseRepository: DatabaseRepository,
    private val settings: SettingsFacade,
    private val query: MeasurementQueryUseCases,
    private val transformation: MeasurementTransformationUseCase
) {
    private val TAG = "BodyScanCalibrationUseCases"

    /** Observes the active calibration record for a user. */
    fun observeCalibrationForUser(userId: Int): Flow<BodyScanCalibration?> =
        databaseRepository.observeBodyScanCalibrationForUser(userId)

    /** Gets the active calibration record for a user. */
    suspend fun getCalibrationForUser(userId: Int): BodyScanCalibration? =
        databaseRepository.getBodyScanCalibrationForUser(userId)

    /** Deletes the calibration record for a user and reverts calibration offsets on all affected measurements. */
    suspend fun deleteCalibrationForUser(userId: Int) {
        LogManager.i(TAG, "Deleting body scan calibration for user $userId")
        val existingCal = databaseRepository.getBodyScanCalibrationForUser(userId)
        if (existingCal != null) {
            val measurements = databaseRepository.getMeasurementsWithValuesForUser(userId).first()
            val forwardMeasurements = measurements.filter { it.measurement.timestamp >= existingCal.scaleMeasurementTimestamp }
            forwardMeasurements.forEach { mwv ->
                LogManager.i(TAG, "Reverting measurement ${mwv.measurement.id} to uncalibrated values")
                val revertedValues = transformation.revertBodyScanCalibration(
                    mwv.measurement,
                    mwv.values.map { it.value },
                    existingCal
                )
                revertedValues.forEach { v ->
                    if (v.id != 0) {
                        databaseRepository.updateMeasurementValue(v)
                    }
                }
                databaseRepository.recalculateDerivedValuesForMeasurement(mwv.measurement.id)
            }
        }
        databaseRepository.deleteBodyScanCalibrationForUser(userId)
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
    fun validateInputs(input: BodyScanFormInput, baseline: MeasurementWithValues?): String? {
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
        if (input.scanWeightKg <= 10f || input.scanWeightKg > 350f) {
            return "Body scan weight must be between 10 kg and 350 kg."
        }
        if (input.scanFatPercent != null && (input.scanFatPercent < 1f || input.scanFatPercent > 70f)) {
            return "Body fat percentage must be between 1% and 70%."
        }
        if (input.scanFatKg != null && (input.scanFatKg <= 0f || input.scanFatKg >= input.scanWeightKg)) {
            return "Body fat mass must be positive and less than total weight."
        }
        if (input.scanMuscleKg != null && (input.scanMuscleKg <= 0f || input.scanMuscleKg >= input.scanWeightKg)) {
            return "Skeletal muscle mass must be positive and less than total weight."
        }
        if (input.scanWaterLiters != null && (input.scanWaterLiters <= 0f || input.scanWaterLiters >= input.scanWeightKg)) {
            return "Total body water must be positive and less than total weight."
        }
        if (input.scanBoneKg != null && (input.scanBoneKg <= 0.1f || input.scanBoneKg > 15f)) {
            return "Bone mass must be between 0.1 kg and 15 kg."
        }
        if (input.scanVisceralFat != null && input.scanVisceralFat < 1f) {
            return "Visceral fat level must be at least 1."
        }
        if (input.scanProteinKg != null && (input.scanProteinKg <= 0f || input.scanProteinKg >= input.scanWeightKg)) {
            return "Protein mass must be positive and less than total weight."
        }
        return null
    }

    /**
     * Calculates the additive intercept offsets (Δ in Kg) for each compartment.
     *
     * Normalization:
     * Due to potential clothing or minor hydration differences between the reference body scan
     * and the home scale weigh-in on calibration day, all reference scan mass inputs
     * are normalized by the ratio (W_scale / W_scan) before computing offsets.
     */
    suspend fun calculateCalibration(
        userId: Int,
        baseline: MeasurementWithValues,
        input: BodyScanFormInput,
        scaleName: String? = null
    ): BodyScanCalibration {
        // If an active calibration was already applied to baseline, reverse it first to recover
        // original uncalibrated values so recalibrating does not double-offset or zero out.
        val existingCal = getCalibrationForUser(userId)
        val effectiveBaseline = if (existingCal != null &&
            baseline.measurement.timestamp >= existingCal.scaleMeasurementTimestamp
        ) {
            val revertedValues = transformation.revertBodyScanCalibration(
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
        require(input.scanWeightKg > 0f) { "Body scan weight must be positive." }

        // Normalization ratio: W0 / W_scan
        val ratio = scaleWeightKg / input.scanWeightKg

        // --- A. FFM Offset (Kg) ---
        val scanFfmKg: Float? = when {
            input.scanFatPercent != null -> input.scanWeightKg * (1f - input.scanFatPercent / 100f)
            input.scanFatKg != null -> input.scanWeightKg - input.scanFatKg
            else -> null
        }
        val offsetFfmKg: Float? = scanFfmKg?.let { rawFfm ->
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
        val offsetMuscleKg: Float? = input.scanMuscleKg?.let { rawMuscle ->
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
        // TBW is typically measured in Liters. Water at 36.5°C body temp is ~0.995 kg/L.
        val offsetWaterKg: Float? = input.scanWaterLiters?.let { rawLiters ->
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
        val offsetBoneKg: Float? = input.scanBoneKg?.let { rawBone ->
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
        val offsetVisceralFat: Float? = input.scanVisceralFat?.let { rawVf ->
            val vfVal = effectiveBaseline.values.find { it.type.key == MeasurementType.VISCERAL_FAT }?.value?.floatValue
            vfVal?.let { rawVf - it }
        }

        // --- F. Protein Offset (Kg) ---
        val offsetProteinKg: Float? = input.scanProteinKg?.let { rawProtein ->
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

        val resolvedFatPercent = input.scanFatPercent
            ?: input.scanFatKg?.let { (it / input.scanWeightKg) * 100f }

        return BodyScanCalibration(
            userId = userId,
            scaleMeasurementTimestamp = baseline.measurement.timestamp,
            scaleName = scaleName,
            scaleBaselineWeightKg = scaleWeightKg,
            scanWeightKg = input.scanWeightKg,
            scanFatPercent = resolvedFatPercent?.let { CalculationUtils.roundTo(it) },
            scanMuscleKg = input.scanMuscleKg?.let { CalculationUtils.roundTo(it) },
            scanWaterLiters = input.scanWaterLiters?.let { CalculationUtils.roundTo(it) },
            scanBoneKg = input.scanBoneKg?.let { CalculationUtils.roundTo(it) },
            scanVisceralFat = input.scanVisceralFat?.let { CalculationUtils.roundTo(it) },
            scanProteinKg = input.scanProteinKg?.let { CalculationUtils.roundTo(it) },
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
        calibration: BodyScanCalibration,
        baselineMeasurement: MeasurementWithValues? = null
    ): Long {
        LogManager.i(TAG, "Saving body scan calibration for user ${calibration.userId}")
        val existingCal = databaseRepository.getBodyScanCalibrationForUser(calibration.userId)

        val id = databaseRepository.insertOrUpdateBodyScanCalibration(calibration)

        if (baselineMeasurement != null) {
            LogManager.i(TAG, "Immediately recalibrating baseline measurement ${baselineMeasurement.measurement.id}")
            val rawValues = if (existingCal != null &&
                baselineMeasurement.measurement.timestamp >= existingCal.scaleMeasurementTimestamp
            ) {
                transformation.revertBodyScanCalibration(
                    baselineMeasurement.measurement,
                    baselineMeasurement.values.map { it.value },
                    existingCal
                )
            } else {
                baselineMeasurement.values.map { it.value }
            }

            val calibratedValues = transformation.applyBodyScanCalibration(
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

