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
package com.health.openscale.core.service

import androidx.annotation.VisibleForTesting
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasureUnit
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.database.MeasurementDao
import com.health.openscale.core.database.MeasurementTypeDao
import com.health.openscale.core.database.MeasurementValueDao
import com.health.openscale.core.database.UserDao
import com.health.openscale.core.model.EvaluationReferenceTables
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes and persists derived measurement values (BMI, WHR, WHtR, BMR, TDEE, fat caliper)
 * for a measurement. Extracted from [com.health.openscale.core.database.DatabaseRepository]
 * to keep the repository focused on persistence. Operates directly on the DAOs.
 */
@Singleton
class DerivedValuesCalculator @Inject constructor(
    private val userDao: UserDao,
    private val measurementDao: MeasurementDao,
    private val measurementTypeDao: MeasurementTypeDao,
    private val measurementValueDao: MeasurementValueDao
) {

    private val DERIVED_VALUES_TAG = "DerivedValues" // Specific tag for this complex logic

    suspend fun recalculateDerivedValuesForMeasurement(measurementId: Int) {
        val startTime = System.nanoTime()
        LogManager.i(DERIVED_VALUES_TAG, "Starting recalculation of derived values for measurementId: $measurementId")

        val measurement = measurementDao.getMeasurementById(measurementId) ?: run {
            //LogManager.w(DERIVED_VALUES_TAG, "Measurement with ID $measurementId not found. Cannot recalculate derived values.")
            return
        }
        val userId = measurement.userId

        // Fetch all current values for this specific measurement and all global MeasurementType definitions
        val currentMeasurementValues = measurementValueDao.getValuesForMeasurement(measurementId).first()
        val allGlobalTypes = measurementTypeDao.getAll().first() // These are MeasurementType objects, containing unit info
        val user = userDao.getById(userId).first() ?: run {
           // LogManager.w(DERIVED_VALUES_TAG, "User with ID $userId not found for measurement $measurementId. Cannot recalculate derived values.")
            return
        }

       // LogManager.d(DERIVED_VALUES_TAG, "Fetched ${currentMeasurementValues.size} current values, " +
       //         "${allGlobalTypes.size} global types, and user '${user.name}' for measurement $measurementId.")

        // Helper to find a raw value and its unit from the persisted MeasurementValues and MeasurementTypes
        val findValueAndUnit = { key: MeasurementTypeKey ->
            val measurementTypeObject = allGlobalTypes.find { it.key == key }
            if (measurementTypeObject == null) {
                LogManager.w(DERIVED_VALUES_TAG, "MeasurementType for key '$key' not found in global types list.")
                Pair(null, null) // Return nulls if the type definition is missing
            } else {
                val valueObject = currentMeasurementValues.find { it.typeId == measurementTypeObject.id }
                val value = valueObject?.floatValue
                val unit = measurementTypeObject.unit // The unit is defined in the MeasurementType object
               // LogManager.v(DERIVED_VALUES_TAG, "findValueAndUnit for $key (typeId: ${measurementTypeObject.id}, unit: $unit): ${value ?: "not found"}")
                Pair(value, unit)
            }
        }

        // Helper to save or update a derived measurement value
        val saveOrUpdateDerivedValue: suspend (value: Float?, typeKey: MeasurementTypeKey) -> Unit =
            save@{ derivedValue, derivedValueTypeKey ->
                val derivedTypeObject = allGlobalTypes.find { it.key == derivedValueTypeKey }

                if (derivedTypeObject == null) {
                    LogManager.w(DERIVED_VALUES_TAG, "Cannot save/update derived value: Type for key '$derivedValueTypeKey' not found.")
                    return@save
                }

                val existingDerivedValueObject = currentMeasurementValues.find { it.typeId == derivedTypeObject.id }

                if (derivedValue == null) {
                    // If derived value is null, delete any existing persisted value for it
                    if (existingDerivedValueObject != null) {
                        measurementValueDao.deleteById(existingDerivedValueObject.id)
                        //LogManager.d(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} is null. Deleted existing value (ID: ${existingDerivedValueObject.id}).")
                    } else {
                        //LogManager.v(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} is null. No existing value to delete.")
                    }
                } else {
                    // If derived value is not null, insert or update it
                    val roundedValue = CalculationUtils.roundTo(derivedValue) // Apply rounding
                    if (existingDerivedValueObject != null) {
                        if (existingDerivedValueObject.floatValue != roundedValue) {
                            measurementValueDao.update(existingDerivedValueObject.copy(floatValue = roundedValue))
                         //   LogManager.d(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} updated from ${existingDerivedValueObject.floatValue} to $roundedValue.")
                        } else {
                            //LogManager.v(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} is $roundedValue (unchanged). No update needed.")
                        }
                    } else {
                        measurementValueDao.insert(
                            MeasurementValue(
                                measurementId = measurementId,
                                typeId = derivedTypeObject.id,
                                floatValue = roundedValue
                            )
                        )
                        //LogManager.d(DERIVED_VALUES_TAG, "New derived value for key ${derivedTypeObject.key} inserted: $roundedValue.")
                    }
                }
            }

        // Fetch raw values and their original units
        val (weightValue, weightUnitType) = findValueAndUnit(MeasurementTypeKey.WEIGHT)
        val (bodyFatValue, bodyFatUnitType) = findValueAndUnit(MeasurementTypeKey.BODY_FAT) // Unit usually % (UnitType.PERCENT)
        val (muscleValue, muscleUnitType) = findValueAndUnit(MeasurementTypeKey.MUSCLE) // Unit usually % (UnitType.PERCENT), may be mass
        val (lbmValue, lbmUnitType) = findValueAndUnit(MeasurementTypeKey.LBM) // Unit usually kg (UnitType.KG)
        val (waistValue, waistUnitType) = findValueAndUnit(MeasurementTypeKey.WAIST)
        val (hipsValue, hipsUnitType) = findValueAndUnit(MeasurementTypeKey.HIPS)
        val (caliper1Value, caliper1UnitType) = findValueAndUnit(MeasurementTypeKey.CALIPER_1)
        val (caliper2Value, caliper2UnitType) = findValueAndUnit(MeasurementTypeKey.CALIPER_2)
        val (caliper3Value, caliper3UnitType) = findValueAndUnit(MeasurementTypeKey.CALIPER_3)

        // --- CONVERT VALUES TO REQUIRED UNITS FOR CALCULATIONS ---

        // Convert weight to Kilograms (KG)
        val weightKg: Float? = if (weightValue != null && weightUnitType != null) {
            when (weightUnitType) {
                UnitType.KG -> weightValue
                UnitType.LB -> ConverterUtils.toKilogram(weightValue, WeightUnit.LB)
                UnitType.ST -> ConverterUtils.toKilogram(weightValue, WeightUnit.ST)
                else -> {
                    LogManager.w(DERIVED_VALUES_TAG, "Unsupported unit $weightUnitType for weight conversion. Assuming KG if value present for ${MeasurementTypeKey.WEIGHT}.")
                    weightValue // Fallback or handle error appropriately
                }
            }
        } else null

        // Convert waist circumference to Centimeters (CM)
        val waistCm: Float? = if (waistValue != null && waistUnitType != null) {
            when (waistUnitType) {
                UnitType.CM -> waistValue
                UnitType.INCH -> ConverterUtils.toCentimeter(waistValue, MeasureUnit.INCH)
                else -> {
                    LogManager.w(DERIVED_VALUES_TAG, "Unsupported unit $waistUnitType for waist conversion. Assuming CM if value present for ${MeasurementTypeKey.WAIST}.")
                    waistValue
                }
            }
        } else null

        // Convert hips circumference to Centimeters (CM)
        val hipsCm: Float? = if (hipsValue != null && hipsUnitType != null) {
            when (hipsUnitType) {
                UnitType.CM -> hipsValue
                UnitType.INCH -> ConverterUtils.toCentimeter(hipsValue, MeasureUnit.INCH)
                else -> {
                    LogManager.w(DERIVED_VALUES_TAG, "Unsupported unit $hipsUnitType for hips conversion. Assuming CM if value present for ${MeasurementTypeKey.HIPS}.")
                    hipsValue
                }
            }
        } else null

        // Convert caliper measurements to Centimeters (CM)
        val caliper1Cm: Float? = if (caliper1Value != null && caliper1UnitType != null) {
            when (caliper1UnitType) {
                UnitType.CM -> caliper1Value
                UnitType.INCH -> ConverterUtils.toCentimeter(caliper1Value, MeasureUnit.INCH)
                else -> caliper1Value // Fallback
            }
        } else null
        val caliper2Cm: Float? = if (caliper2Value != null && caliper2UnitType != null) {
            when (caliper2UnitType) {
                UnitType.CM -> caliper2Value
                UnitType.INCH -> ConverterUtils.toCentimeter(caliper2Value, MeasureUnit.INCH)
                else -> caliper2Value
            }
        } else null
        val caliper3Cm: Float? = if (caliper3Value != null && caliper3UnitType != null) {
            when (caliper3UnitType) {
                UnitType.CM -> caliper3Value
                UnitType.INCH -> ConverterUtils.toCentimeter(caliper3Value, MeasureUnit.INCH)
                else -> caliper3Value
            }
        } else null

        // User's height is assumed to be stored in CM in the User object
        val userHeightCm = user.heightCm

        val ageAtMeasurementYears = CalculationUtils.ageOn(
            dateMillis = measurement.timestamp,
            birthDateMillis = user.birthDate
        )

        // Body-fat and muscle may each be stored as a percentage or as an absolute
        // mass. Normalise both to a percentage of body weight so the science-based
        // reference tables (body-fat, Janssen muscle) can band them consistently.
        val bodyFatPercent: Float? = toPercentOfWeight(bodyFatValue, bodyFatUnitType, weightKg)
        val musclePercent: Float? = toPercentOfWeight(muscleValue, muscleUnitType, weightKg)

        // Fat-free (lean) mass in kg, used for the body-composition-aware
        // (Katch-McArdle) BMR behind metabolic age. Prefer a measured LBM value;
        // otherwise derive it from weight and the normalised body-fat percentage.
        val fatFreeMassKg: Float? = when {
            lbmValue != null && lbmValue > 0f && lbmUnitType != null && lbmUnitType.isWeightUnit() ->
                ConverterUtils.toKilogram(lbmValue, lbmUnitType.toWeightUnit())
            weightKg != null && weightKg > 0f ->
                bodyFatPercent?.takeIf { it in 1f..75f }?.let { weightKg * (1f - it / 100f) }
            else -> null
        }

        // --- PERFORM DERIVED VALUE CALCULATIONS ---
        // Pass the converted values (e.g., weightKg, waistCm) to the processing functions

        processBmiCalculation(weightKg, userHeightCm).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.BMI) }
        processWhrCalculation(waistCm, hipsCm).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.WHR) }
        processWhtrCalculation(waistCm, userHeightCm).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.WHTR) }
        // BMR: when the source device already supplied one (e.g. S400 BIA-based BMR
        // via FFM), keep it. Mifflin-St Jeor is an anthropometric fallback used
        // only when no BMR landed on the row. TDEE always derives from whichever
        // BMR is actually persisted.
        val bmrTypeId = allGlobalTypes.find { it.key == MeasurementTypeKey.BMR }?.id
        val existingBmr = bmrTypeId?.let { id ->
            currentMeasurementValues.find { it.typeId == id }?.floatValue
        }
        val bmr = existingBmr ?: processBmrCalculation(
            weightKg = weightKg,
            heightCm = user.heightCm,
            ageYears = ageAtMeasurementYears,
            gender = user.gender
        )?.also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.BMR) }
        processTDEECalculation(bmr, user.activityLevel).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.TDEE) }

        processMetabolicAgeCalculation(
            weightKg = weightKg,
            heightCm = user.heightCm,
            gender = user.gender,
            fatFreeMassKg = fatFreeMassKg
        ).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.METABOLIC_AGE) }

        processPhysiqueRatingCalculation(
            bodyFatPercent = bodyFatPercent,
            musclePercent = musclePercent,
            age = ageAtMeasurementYears,
            gender = user.gender
        ).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.PHYSIQUE_RATING) }

        processFatCaliperCalculation(
            caliper1Cm = caliper1Cm,
            caliper2Cm = caliper2Cm,
            caliper3Cm = caliper3Cm,
            ageYears = ageAtMeasurementYears,
            gender = user.gender
        ).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.CALIPER) }

        val endTime = System.nanoTime()
        val durationMillis = (endTime - startTime) / 1_000_000
        LogManager.i(DERIVED_VALUES_TAG, "Finished recalculation of derived values for measurementId: $measurementId. Took $durationMillis ms.")    }

    // --- Calculation helper functions (pure; in companion object, @VisibleForTesting for unit tests) ---
    companion object {
    private const val CALC_PROCESS_TAG = "DerivedValuesProcess"

    @VisibleForTesting
    internal fun processBmiCalculation(weightKg: Float?, heightCm: Float?): Float? {
        //LogManager.v(CALC_PROCESS_TAG, "Processing BMI: weight=$weightKg kg, height=$heightCm cm")
        return if (weightKg != null && weightKg > 0f && heightCm != null && heightCm > 0f) {
            val heightM = heightCm / 100f
            weightKg / (heightM * heightM)
        } else {
            //LogManager.d(CALC_PROCESS_TAG, "BMI calculation skipped: Missing or invalid weight/height.")
            null
        }
    }

    @VisibleForTesting
    internal fun processWhrCalculation(waistCm: Float?, hipsCm: Float?): Float? {
       // LogManager.v(CALC_PROCESS_TAG, "Processing WHR: waist=$waistCm cm, hips=$hipsCm cm")
        return if (waistCm != null && waistCm > 0f && hipsCm != null && hipsCm > 0f) {
            waistCm / hipsCm
        } else {
            //LogManager.d(CALC_PROCESS_TAG, "WHR calculation skipped: Missing or invalid waist/hips measurements.")
            null
        }
    }

    @VisibleForTesting
    internal fun processWhtrCalculation(waistCm: Float?, bodyHeightCm: Float?): Float? {
       // LogManager.v(CALC_PROCESS_TAG, "Processing WHTR: waist=$waistCm cm, bodyHeight=$bodyHeightCm cm")
        return if (waistCm != null && waistCm > 0f && bodyHeightCm != null && bodyHeightCm > 0f) {
            waistCm / bodyHeightCm
        } else {
            //LogManager.d(CALC_PROCESS_TAG, "WHTR calculation skipped: Missing or invalid waist/body height measurements.")
            null
        }
    }

    @VisibleForTesting
    internal fun processBmrCalculation(
        weightKg: Float?,
        heightCm: Float?,
        ageYears: Int,
        gender: GenderType
    ): Float? {
       // LogManager.v(CALC_PROCESS_TAG, "Processing BMR: weight=$weightKg kg, height=$heightCm cm, age=$ageYears, gender=$gender")

        if (weightKg == null || weightKg <= 0f ||
            heightCm == null || heightCm <= 0f ||
            ageYears !in 1..120
        ) {
            LogManager.d(CALC_PROCESS_TAG, "BMR calculation skipped: Missing/invalid weight, height or age ($ageYears).")
            return null
        }

        return when (gender) {
            GenderType.MALE   -> (10.0f * weightKg) + (6.25f * heightCm) - (5.0f * ageYears) + 5.0f
            GenderType.FEMALE -> (10.0f * weightKg) + (6.25f * heightCm) - (5.0f * ageYears) - 161.0f
        }
    }

    /**
     * Science-based metabolic age (years): the age at which the population-normative
     * BMR (Mifflin-St Jeor, evaluated at the user's own weight/height/gender) equals
     * the user's actual body-composition BMR (Katch-McArdle, from fat-free mass).
     *
     * actualBMR   = 370 + 21.6 * FFM_kg                       (Katch-McArdle)
     * refBMR(age) = 10*w + 6.25*h - 5*age + s ; s = +5 (male), -161 (female)  (Mifflin-St Jeor)
     * Solving refBMR(metAge) = actualBMR (linear in age):
     *   metAge = (10*w + 6.25*h + s - actualBMR) / 5
     *
     * A higher actual BMR (more lean mass) yields a younger metabolic age.
     * Chronological age is not an input. Requires a fat-free-mass signal.
     */
    @VisibleForTesting
    internal fun processMetabolicAgeCalculation(
        weightKg: Float?,
        heightCm: Float?,
        gender: GenderType,
        fatFreeMassKg: Float?
    ): Float? {
        if (weightKg == null || weightKg <= 0f ||
            heightCm == null || heightCm <= 0f ||
            fatFreeMassKg == null || fatFreeMassKg <= 0f
        ) {
            LogManager.d(CALC_PROCESS_TAG, "Metabolic age skipped: missing/invalid weight, height or fat-free mass.")
            return null
        }

        val actualBmr = 370.0f + 21.6f * fatFreeMassKg // Katch-McArdle
        val genderConst = when (gender) {
            GenderType.MALE -> 5.0f
            GenderType.FEMALE -> -161.0f
        }
        val metAge = ((10.0f * weightKg) + (6.25f * heightCm) + genderConst - actualBmr) / 5.0f
        return metAge.coerceIn(15.0f, 99.0f)
    }

    /**
     * Normalises a body-composition value to a percentage of body weight.
     * Values already stored as a percentage pass through; values stored as a
     * mass (kg/lb/st) are divided by the weight. Returns null when the value is
     * missing/non-positive, its unit is unsupported, or a mass value has no
     * weight to divide by.
     */
    @VisibleForTesting
    internal fun toPercentOfWeight(value: Float?, unitType: UnitType?, weightKg: Float?): Float? {
        if (value == null || value <= 0f || unitType == null) return null
        return when (unitType) {
            UnitType.PERCENT -> value
            UnitType.KG, UnitType.LB, UnitType.ST ->
                if (weightKg != null && weightKg > 0f)
                    ConverterUtils.toKilogram(value, unitType.toWeightUnit()) / weightKg * 100f
                else null
            else -> null
        }
    }

    /**
     * Science-based Tanita-style physique rating (1–9). Crosses the user's
     * body-fat level with their muscle-mass level, each banded LOW/NORMAL/HIGH
     * against age- and sex-specific population reference ranges (the body-fat
     * ranges and the Janssen 2000 skeletal-muscle ranges in
     * [EvaluationReferenceTables]).
     *
     *   fatIndex:    HIGH=0, NORMAL=1, LOW=2   (more fat -> lower row)
     *   muscleIndex: LOW=0,  NORMAL=1, HIGH=2
     *   rating = fatIndex*3 + muscleIndex + 1
     *
     * yielding the Tanita 3x3 body-type matrix:
     *   1 hidden-obese     2 obese             3 solidly-built
     *   4 under-exercised  5 standard          6 standard-muscular
     *   7 thin             8 thin & muscular   9 very-muscular
     *
     * Returns null when body-fat or muscle is missing/implausible, or when the
     * user's age falls outside either reference table (no defensible band).
     */
    @VisibleForTesting
    internal fun processPhysiqueRatingCalculation(
        bodyFatPercent: Float?,
        musclePercent: Float?,
        age: Int,
        gender: GenderType
    ): Float? {
        if (bodyFatPercent == null || bodyFatPercent !in 1f..75f ||
            musclePercent == null || musclePercent !in 5f..80f
        ) {
            LogManager.d(CALC_PROCESS_TAG, "Physique rating skipped: missing/implausible body-fat or muscle.")
            return null
        }

        val fatStrategy = if (gender == GenderType.MALE)
            EvaluationReferenceTables.fatMale else EvaluationReferenceTables.fatFemale
        val muscleStrategy = if (gender == GenderType.MALE)
            EvaluationReferenceTables.muscleMale else EvaluationReferenceTables.muscleFemale

        val fatState = fatStrategy.evaluate(bodyFatPercent, age).state
        val muscleState = muscleStrategy.evaluate(musclePercent, age).state
        if (fatState == EvaluationState.UNDEFINED || muscleState == EvaluationState.UNDEFINED) {
            LogManager.d(CALC_PROCESS_TAG, "Physique rating skipped: age $age outside reference bands.")
            return null
        }

        val fatIndex = when (fatState) {
            EvaluationState.HIGH -> 0
            EvaluationState.NORMAL -> 1
            EvaluationState.LOW -> 2
            EvaluationState.UNDEFINED -> return null
        }
        val muscleIndex = when (muscleState) {
            EvaluationState.LOW -> 0
            EvaluationState.NORMAL -> 1
            EvaluationState.HIGH -> 2
            EvaluationState.UNDEFINED -> return null
        }
        return (fatIndex * 3 + muscleIndex + 1).toFloat()
    }

    @VisibleForTesting
    internal fun processTDEECalculation(bmr: Float?, activityLevel: ActivityLevel?): Float? {
       // LogManager.v(CALC_PROCESS_TAG, "Processing TDEE: BMR=$bmr, ActivityLevel=$activityLevel")
        if (bmr == null || bmr <= 0f || activityLevel == null) {
            LogManager.d(CALC_PROCESS_TAG, "TDEE calculation skipped: Missing or invalid BMR or activity level.")
            return null
        }

        val activityFactor = when (activityLevel) {
            ActivityLevel.SEDENTARY -> 1.2f
            ActivityLevel.MILD -> 1.375f
            ActivityLevel.MODERATE -> 1.55f
            ActivityLevel.HEAVY -> 1.725f
            ActivityLevel.EXTREME -> 1.9f
        }
        return bmr * activityFactor
    }


    @VisibleForTesting
    internal fun processFatCaliperCalculation(
        caliper1Cm: Float?,
        caliper2Cm: Float?,
        caliper3Cm: Float?,
        ageYears: Int,
        gender: GenderType
    ): Float? {
       // LogManager.v(CALC_PROCESS_TAG, "Processing Fat Caliper: c1=$caliper1Cm cm, c2=$caliper2Cm cm, c3=$caliper3Cm cm, age=$ageYears, gender=$gender")

        if (caliper1Cm == null || caliper1Cm <= 0f ||
            caliper2Cm == null || caliper2Cm <= 0f ||
            caliper3Cm == null || caliper3Cm <= 0f
        ) {
            //LogManager.d(CALC_PROCESS_TAG, "Fat Caliper calculation skipped: One or more caliper values are missing or zero.")
            return null
        }

        if (ageYears <= 0) {
            LogManager.w(CALC_PROCESS_TAG, "Fat Caliper calculation skipped: Invalid age ($ageYears).")
            return null
        }

        // Sum of skinfolds in millimeters
        val sumSkinfoldsMm = (caliper1Cm + caliper2Cm + caliper3Cm) * 10.0f
        // LogManager.v(CALC_PROCESS_TAG, "Sum of skinfolds (S): $sumSkinfoldsMm mm")

        // Choose constants based on gender
        val k0: Float
        val k1: Float
        val k2: Float
        val ka: Float

        when (gender) {
            GenderType.MALE -> {
                k0 = 1.10938f
                k1 = 0.0008267f
                k2 = 0.0000016f
                ka = 0.0002574f
            }
            GenderType.FEMALE -> {
                k0 = 1.0994921f
                k1 = 0.0009929f
                k2 = 0.0000023f
                ka = 0.0001392f
            }
        }

        val bodyDensity =
            k0 - (k1 * sumSkinfoldsMm) + (k2 * sumSkinfoldsMm * sumSkinfoldsMm) - (ka * ageYears)
        //LogManager.v(CALC_PROCESS_TAG, "Calculated Body Density (BD): $bodyDensity")

        if (bodyDensity <= 0f) {
            LogManager.w(CALC_PROCESS_TAG, "Invalid Body Density calculated: $bodyDensity.")
            return null
        }

        val fatPercentage = (4.95f / bodyDensity - 4.5f) * 100.0f
        //LogManager.v(CALC_PROCESS_TAG, "Calculated Fat Percentage from BD: $fatPercentage %")

        return fatPercentage.takeIf { it in 1.0f..70.0f } ?: run {
            //LogManager.w(CALC_PROCESS_TAG, "Calculated Fat Percentage ($fatPercentage%) is outside the expected physiological range (1–70%).")
            fatPercentage
        }
    }
    }
}
