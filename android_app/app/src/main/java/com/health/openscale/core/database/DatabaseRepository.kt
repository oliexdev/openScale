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
package com.health.openscale.core.database

import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.CalculationUtil
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Repository class for accessing and managing data in the application's database.
 * It abstracts the data sources (DAOs) and provides a clean API for data operations.
 */
class DatabaseRepository(
    private val database: AppDatabase,
    private val userDao: UserDao,
    private val measurementDao: MeasurementDao,
    private val measurementTypeDao: MeasurementTypeDao,
    private val measurementValueDao: MeasurementValueDao
) {

    private val TAG = "DatabaseRepository"

    /**
     * Gets the name of the database.
     * @return The database name.
     */
    fun getDatabaseName(): String {
        return AppDatabase.DATABASE_NAME
    }

    /**
     * Closes the database connection.
     */
    fun closeDatabase() {
        LogManager.i(TAG, "Attempting to close database connection.")
        database.closeConnection()
    }

    // --- User Operations ---
    fun getAllUsers(): Flow<List<User>> = userDao.getAllUsers()
    fun getUserById(id: Int): Flow<User?> = userDao.getById(id)

    suspend fun insertUser(user: User): Long {
        LogManager.d(TAG, "Inserting user: ${user.name}")
        return userDao.insert(user)
    }

    suspend fun updateUser(user: User) {
        LogManager.d(TAG, "Updating user with id: ${user.id}")
        userDao.update(user)
    }

    suspend fun deleteUser(user: User) {
        LogManager.d(TAG, "Deleting user with id: ${user.id}")
        userDao.delete(user)
    }

    // --- Measurement Operations ---

    fun getMeasurementsWithValuesForUser(userId: Int): Flow<List<MeasurementWithValues>> =
        measurementDao.getMeasurementsWithValuesForUser(userId)

    fun getMeasurementWithValuesById(measurementId: Int): Flow<MeasurementWithValues?> =
        measurementDao.getMeasurementWithValuesById(measurementId)

    /**
     * Inserts a new measurement and recalculates derived values.
     */
    suspend fun insertMeasurement(measurement: Measurement): Long {
        LogManager.d(TAG, "Inserting measurement for user id: ${measurement.userId}")
        val id = measurementDao.insert(measurement)
        LogManager.d(TAG, "New measurement inserted with id: $id. Recalculating derived values.")
        recalculateDerivedValuesForMeasurement(id.toInt())
        return id
    }

    /**
     * Updates an existing measurement and recalculates derived values.
     */
    suspend fun updateMeasurement(measurement: Measurement) {
        LogManager.d(TAG, "Updating measurement with id: ${measurement.id}. Recalculating derived values.")
        measurementDao.update(measurement)
        recalculateDerivedValuesForMeasurement(measurement.id)
    }

    suspend fun deleteMeasurement(measurement: Measurement) {
        LogManager.d(TAG, "Deleting measurement with id: ${measurement.id}")
        measurementDao.delete(measurement)
    }


    // --- Measurement Value Operations ---

    /**
     * Inserts a new measurement value and recalculates derived values for the associated measurement.
     */
    suspend fun insertMeasurementValue(value: MeasurementValue) {
        LogManager.d(TAG, "Inserting measurement value for measurement id: ${value.measurementId}, typeId: ${value.typeId}")
        measurementValueDao.insert(value)
        LogManager.d(TAG, "Recalculating derived values for measurement id: ${value.measurementId}")
        recalculateDerivedValuesForMeasurement(value.measurementId)
    }

    /**
     * Updates an existing measurement value and recalculates derived values for the associated measurement.
     */
    suspend fun updateMeasurementValue(value: MeasurementValue) {
        LogManager.d(TAG, "Updating measurement value with id: ${value.id}. Recalculating derived values for measurement id: ${value.measurementId}")
        measurementValueDao.update(value)
        recalculateDerivedValuesForMeasurement(value.measurementId)
    }

    /**
     * Inserts a list of measurements, each with its associated values.
     */
    suspend fun insertMeasurementsWithValues(measurementsData: List<Pair<Measurement, List<MeasurementValue>>>) {
        LogManager.i(TAG, "Attempting to insert ${measurementsData.size} measurements with their values.")
        withContext(Dispatchers.IO) {
            measurementsData.forEachIndexed { index, (measurement, values) ->
                try {
                    LogManager.d(TAG, "Inserting measurement ${index + 1}/${measurementsData.size}, userId: ${measurement.userId}, with ${values.size} values.")
                    measurementDao.insertSingleMeasurementWithItsValues(measurement, values)
                } catch (e: Exception) {
                    LogManager.e(TAG, "Failed to insert measurement (userId: ${measurement.userId}, timestamp: ${measurement.timestamp}) and its values. Error: ${e.message}", e)
                }
            }
        }
        LogManager.i(TAG, "Finished inserting measurements with values.")
    }

    suspend fun deleteMeasurementValueById(valueId: Int) {
        LogManager.d(TAG, "Deleting measurement value with id: $valueId")
        measurementValueDao.deleteById(valueId)
    }

    /**
     * Deletes all measurements for a given user.
     * @return The number of deleted measurements.
     */
    suspend fun deleteAllMeasurementsForUser(userId: Int): Int {
        LogManager.i(TAG, "Deleting all measurements for user id: $userId")
        return withContext(Dispatchers.IO) {
            measurementDao.deleteMeasurementsByUserId(userId).also { count ->
                LogManager.i(TAG, "$count measurements deleted for user id: $userId")
            }
        }
    }

    fun getValuesForMeasurement(measurementId: Int): Flow<List<MeasurementValue>> =
        measurementValueDao.getValuesForMeasurement(measurementId)

    // --- Measurement Type Operations ---

    fun getAllMeasurementTypes(): Flow<List<MeasurementType>> = measurementTypeDao.getAll()

    suspend fun insertMeasurementType(type: MeasurementType): Long {
        LogManager.d(TAG, "Inserting measurement type: ${type.key}") // Logging the key
        return measurementTypeDao.insert(type)
    }

    suspend fun deleteMeasurementType(type: MeasurementType) {
        LogManager.d(TAG, "Deleting measurement type with id: ${type.id}, key: ${type.key}")
        measurementTypeDao.delete(type)
    }

    suspend fun updateMeasurementType(type: MeasurementType) {
        LogManager.d(TAG, "Updating measurement type with id: ${type.id}, key: ${type.key}")
        measurementTypeDao.update(type)
    }


    // --- Derived Values Calculation ---
    private val DERIVED_VALUES_TAG = "DerivedValues" // Specific tag for this complex logic

    /**
     * Recalculates all derived measurement values (like BMI, LBM, etc.) for a given measurement.
     * This method fetches the necessary base values and user data, then processes each calculation.
     *
     * @param measurementId The ID of the measurement for which to recalculate derived values.
     */
    private suspend fun recalculateDerivedValuesForMeasurement(measurementId: Int) {
        LogManager.i(DERIVED_VALUES_TAG, "Starting recalculation of derived values for measurementId: $measurementId")

        val measurement = measurementDao.getMeasurementById(measurementId) ?: run {
            LogManager.w(DERIVED_VALUES_TAG, "Measurement with ID $measurementId not found. Cannot recalculate derived values.")
            return
        }
        val userId = measurement.userId

        val currentMeasurementValues = measurementValueDao.getValuesForMeasurement(measurementId).first()
        val allGlobalTypes = measurementTypeDao.getAll().first()
        val user = userDao.getById(userId).first() ?: run {
            LogManager.w(DERIVED_VALUES_TAG, "User with ID $userId not found for measurement $measurementId. Cannot recalculate derived values.")
            return
        }

        LogManager.d(DERIVED_VALUES_TAG, "Fetched ${currentMeasurementValues.size} current values, " +
                "${allGlobalTypes.size} global types, and user '${user.name}' for measurement $measurementId.")

        val findValue = { key: MeasurementTypeKey ->
            val type = allGlobalTypes.find { it.key == key }
            if (type == null) {
                LogManager.w(DERIVED_VALUES_TAG, "MeasurementType for key '$key' not found in global types list.")
            }
            val value = currentMeasurementValues.find { it.typeId == type?.id }?.floatValue
            LogManager.v(DERIVED_VALUES_TAG, "findValue for $key (typeId: ${type?.id}): ${value ?: "not found"}")
            value
        }

        val saveOrUpdateDerivedValue: suspend (value: Float?, typeKey: MeasurementTypeKey) -> Unit =
            save@{ derivedValue, derivedValueTypeKey ->
                val derivedTypeObject = allGlobalTypes.find { it.key == derivedValueTypeKey }

                if (derivedTypeObject == null) {
                    LogManager.w(DERIVED_VALUES_TAG, "Cannot save/update derived value: Type for key '$derivedValueTypeKey' not found.")
                    return@save
                }

                val existingDerivedValueObject = currentMeasurementValues.find { it.typeId == derivedTypeObject.id }

                if (derivedValue == null) {
                    if (existingDerivedValueObject != null) {
                        measurementValueDao.deleteById(existingDerivedValueObject.id)
                        LogManager.d(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} is null. Deleted existing value (ID: ${existingDerivedValueObject.id}).")
                    } else {
                        LogManager.v(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} is null. No existing value to delete.")
                    }
                } else {
                    val roundedValue = roundTo(derivedValue)
                    if (existingDerivedValueObject != null) {
                        if (existingDerivedValueObject.floatValue != roundedValue) {
                            measurementValueDao.update(existingDerivedValueObject.copy(floatValue = roundedValue))
                            LogManager.d(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} updated from ${existingDerivedValueObject.floatValue} to $roundedValue.")
                        } else {
                            LogManager.v(DERIVED_VALUES_TAG, "Derived value for key ${derivedTypeObject.key} is $roundedValue (unchanged). No update needed.")
                        }
                    } else {
                        measurementValueDao.insert(
                            MeasurementValue(
                                measurementId = measurementId,
                                typeId = derivedTypeObject.id,
                                floatValue = roundedValue
                            )
                        )
                        LogManager.d(DERIVED_VALUES_TAG, "New derived value for key ${derivedTypeObject.key} inserted: $roundedValue.")
                    }
                }
            }

        val weightKg = findValue(MeasurementTypeKey.WEIGHT)
        val bodyFatPercentage = findValue(MeasurementTypeKey.BODY_FAT)
        val waistCm = findValue(MeasurementTypeKey.WAIST)
        val hipsCm = findValue(MeasurementTypeKey.HIPS)
        val caliper1Cm = findValue(MeasurementTypeKey.CALIPER_1)
        val caliper2Cm = findValue(MeasurementTypeKey.CALIPER_2)
        val caliper3Cm = findValue(MeasurementTypeKey.CALIPER_3)

        processBmiCalculation(weightKg, user.heightCm).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.BMI) }
        processLbmCalculation(weightKg, bodyFatPercentage).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.LBM) }
        processWhrCalculation(waistCm, hipsCm).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.WHR) }
        processWhtrCalculation(waistCm, user.heightCm).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.WHTR) }
        processBmrCalculation(weightKg, user).also { bmr ->
            saveOrUpdateDerivedValue(bmr, MeasurementTypeKey.BMR)
            processTDEECalculation(bmr, user.activityLevel).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.TDEE) }
        }
        processFatCaliperCalculation(caliper1Cm, caliper2Cm, caliper3Cm, user)
            .also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.CALIPER) }

        LogManager.i(DERIVED_VALUES_TAG, "Finished recalculation of derived values for measurementId: $measurementId")
    }

    // --- Private Calculation Helper Functions ---
    private val CALC_PROCESS_TAG = "DerivedValuesProcess"

    private fun processBmiCalculation(weightKg: Float?, heightCm: Float?): Float? {
        LogManager.v(CALC_PROCESS_TAG, "Processing BMI: weight=$weightKg kg, height=$heightCm cm")
        return if (weightKg != null && weightKg > 0f && heightCm != null && heightCm > 0f) {
            val heightM = heightCm / 100f
            weightKg / (heightM * heightM)
        } else {
            LogManager.d(CALC_PROCESS_TAG, "BMI calculation skipped: Missing or invalid weight/height.")
            null
        }
    }

    private fun processLbmCalculation(weightKg: Float?, bodyFatPercentage: Float?): Float? {
        LogManager.v(CALC_PROCESS_TAG, "Processing LBM: weight=$weightKg kg, bodyFat=$bodyFatPercentage %")
        return if (weightKg != null && weightKg > 0f && bodyFatPercentage != null && bodyFatPercentage in 0f..100f) {
            val fatMass = weightKg * (bodyFatPercentage / 100f)
            weightKg - fatMass
        } else {
            if (bodyFatPercentage != null && bodyFatPercentage !in 0f..100f) {
                LogManager.w(CALC_PROCESS_TAG, "Invalid body fat percentage for LBM calculation: $bodyFatPercentage%. Must be between 0 and 100.")
            } else if (weightKg == null || weightKg <= 0f) {
                LogManager.d(CALC_PROCESS_TAG, "LBM calculation skipped: Missing or invalid weight.")
            } else {
                LogManager.d(CALC_PROCESS_TAG, "LBM calculation skipped: Missing body fat percentage.")
            }
            null
        }
    }

    private fun processWhrCalculation(waistCm: Float?, hipsCm: Float?): Float? {
        LogManager.v(CALC_PROCESS_TAG, "Processing WHR: waist=$waistCm cm, hips=$hipsCm cm")
        return if (waistCm != null && waistCm > 0f && hipsCm != null && hipsCm > 0f) {
            waistCm / hipsCm
        } else {
            LogManager.d(CALC_PROCESS_TAG, "WHR calculation skipped: Missing or invalid waist/hips measurements.")
            null
        }
    }

    private fun processWhtrCalculation(waistCm: Float?, bodyHeightCm: Float?): Float? {
        LogManager.v(CALC_PROCESS_TAG, "Processing WHTR: waist=$waistCm cm, bodyHeight=$bodyHeightCm cm")
        return if (waistCm != null && waistCm > 0f && bodyHeightCm != null && bodyHeightCm > 0f) {
            waistCm / bodyHeightCm
        } else {
            LogManager.d(CALC_PROCESS_TAG, "WHTR calculation skipped: Missing or invalid waist/body height measurements.")
            null
        }
    }

    private fun processBmrCalculation(weightKg: Float?, user: User): Float? {
        LogManager.v(CALC_PROCESS_TAG, "Processing BMR for user ${user.id}: weight=$weightKg kg")
        val heightCm = user.heightCm
        val birthDateTimestamp = user.birthDate
        val gender = user.gender

        if (weightKg == null || weightKg <= 0f ||
            heightCm == null || heightCm <= 0f ||
            birthDateTimestamp <= 0L || gender == null
        ) {
            LogManager.d(CALC_PROCESS_TAG, "BMR calculation skipped: Missing or invalid weight, height, birthdate, or gender.")
            return null
        }

        val ageYears = CalculationUtil.dateToAge(birthDateTimestamp)
        LogManager.v(CALC_PROCESS_TAG, "Calculated age for BMR: $ageYears years")

        return if (ageYears in 1..120) {
            when (gender) {
                GenderType.MALE -> (10.0f * weightKg) + (6.25f * heightCm) - (5.0f * ageYears) + 5.0f
                GenderType.FEMALE -> (10.0f * weightKg) + (6.25f * heightCm) - (5.0f * ageYears) - 161.0f
                else -> {
                    LogManager.w(CALC_PROCESS_TAG, "BMR calculation not supported for gender: '$gender'. User ID: ${user.id}")
                    null
                }
            }
        } else {
            LogManager.w(CALC_PROCESS_TAG, "Invalid age for BMR calculation: $ageYears years. User ID: ${user.id}")
            null
        }
    }

    private fun processTDEECalculation(bmr: Float?, activityLevel: ActivityLevel?): Float? {
        LogManager.v(CALC_PROCESS_TAG, "Processing TDEE: BMR=$bmr, ActivityLevel=$activityLevel")
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

    private fun processFatCaliperCalculation(
        caliper1Cm: Float?,
        caliper2Cm: Float?,
        caliper3Cm: Float?,
        user: User
    ): Float? {
        LogManager.v(CALC_PROCESS_TAG, "Processing Fat Caliper: c1=$caliper1Cm cm, c2=$caliper2Cm cm, c3=$caliper3Cm cm for user ${user.id}")

        if (caliper1Cm == null || caliper1Cm <= 0f ||
            caliper2Cm == null || caliper2Cm <= 0f ||
            caliper3Cm == null || caliper3Cm <= 0f
        ) {
            LogManager.d(CALC_PROCESS_TAG, "Fat Caliper calculation skipped: One or more caliper values are missing or zero.")
            return null
        }

        val gender = user.gender
        val ageYears = CalculationUtil.dateToAge(user.birthDate)

        if (gender == null || ageYears <= 0) {
            LogManager.w(CALC_PROCESS_TAG, "Fat Caliper calculation skipped: Invalid gender ($gender) or age ($ageYears years). User ID: ${user.id}")
            return null
        }
        LogManager.v(CALC_PROCESS_TAG, "Calculated age for Fat Caliper: $ageYears years")

        val sumSkinfoldsMm = (caliper1Cm + caliper2Cm + caliper3Cm) * 10.0f
        LogManager.v(CALC_PROCESS_TAG, "Sum of skinfolds (S): $sumSkinfoldsMm mm")

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
            else -> {
                LogManager.w(CALC_PROCESS_TAG, "Fat Caliper calculation not supported for gender: '$gender'. User ID: ${user.id}")
                return null
            }
        }

        val bodyDensity = k0 - (k1 * sumSkinfoldsMm) + (k2 * sumSkinfoldsMm * sumSkinfoldsMm) - (ka * ageYears)
        LogManager.v(CALC_PROCESS_TAG, "Calculated Body Density (BD): $bodyDensity")

        if (bodyDensity <= 0f) {
            LogManager.w(CALC_PROCESS_TAG, "Invalid Body Density calculated: $bodyDensity. Caliper values might be outside the formula's valid range. User ID: ${user.id}")
            return null
        }

        val fatPercentage = (4.95f / bodyDensity - 4.5f) * 100.0f
        LogManager.v(CALC_PROCESS_TAG, "Calculated Fat Percentage from BD: $fatPercentage %")

        return if (fatPercentage in 1.0f..70.0f) {
            fatPercentage
        } else {
            LogManager.w(CALC_PROCESS_TAG, "Calculated Fat Percentage ($fatPercentage%) is outside the expected physiological range (1-70%). User ID: ${user.id}")
            fatPercentage
        }
    }

    /**
     * Rounds a float value to two decimal places.
     */
    private fun roundTo(value: Float): Float {
        return (value * 100).toInt() / 100.0f
    }
}

