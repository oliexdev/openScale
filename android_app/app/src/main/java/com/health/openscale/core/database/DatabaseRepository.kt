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

import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.service.DerivedValuesCalculator
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository class for accessing and managing data in the application's database.
 * It abstracts the data sources (DAOs) and provides a clean API for data operations.
 */
@Singleton
class DatabaseRepository @Inject constructor(
    private val database: AppDatabase,
    private val userDao: UserDao,
    private val userGoalsDao: UserGoalsDao,
    private val measurementDao: MeasurementDao,
    private val measurementTypeDao: MeasurementTypeDao,
    private val measurementValueDao: MeasurementValueDao,
    private val derivedValuesCalculator: DerivedValuesCalculator
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
        database.close()
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

    // --- User Goals Operations ---
    suspend fun insertUserGoal(goal: UserGoals): Long {
        LogManager.d(TAG, "Inserting user goal for userId: ${goal.userId}, typeId: ${goal.measurementTypeId}")
        return userGoalsDao.insert(goal)
    }

    suspend fun updateUserGoal(goal: UserGoals) {
        LogManager.d(TAG, "Updating user goal for userId: ${goal.userId}, typeId: ${goal.measurementTypeId}")
        userGoalsDao.update(goal)
    }

    suspend fun deleteUserGoal(userId: Int, measurementTypeId: Int) {
        LogManager.d(TAG, "Deleting user goal for userId: $userId, typeId: $measurementTypeId")
        userGoalsDao.delete(userId, measurementTypeId)
    }

    fun getAllGoalsForUser(userId: Int): Flow<List<UserGoals>> {
        return userGoalsDao.getAllForUser(userId)
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
        if (id != -1L) {
            LogManager.d(TAG,"New measurement inserted with id: $id. Recalculating derived values.")
            recalculateDerivedValuesForMeasurement(id.toInt())
        } else {
            LogManager.i(TAG, "Measurement insertion ignored for user id: ${measurement.userId}, timestamp: ${measurement.timestamp} (likely a duplicate).")
        }

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
     * Inserts a list of measurements, each with its associated values,
     * and returns the IDs of the newly inserted main Measurement records and the ignored one with timestamps.
     */
    suspend fun insertMeasurementsWithValues(measurementsData: List<Pair<Measurement, List<MeasurementValue>>>) : Pair<List<Long>, List<Long>>  {
        val insertedIds = mutableListOf<Long>()
        val ignoredIds = mutableListOf<Long>()

        LogManager.i(TAG, "Attempting to insert ${measurementsData.size} measurements with their values.")
        withContext(Dispatchers.IO) {
            measurementsData.forEachIndexed { index, (measurement, values) ->
                try {
                    val newMeasurementId = measurementDao.insertSingleMeasurementWithItsValues(measurement, values)
                    if (newMeasurementId != -1L) {
                        insertedIds.add(newMeasurementId)
                        LogManager.d(TAG,"Inserting measurement ${index + 1}/${measurementsData.size}, userId: ${measurement.userId}, with ${values.size} values.")
                    }
                    else {
                        ignoredIds.add(measurement.timestamp)
                        LogManager.d(TAG,"Ignored measurement ${index + 1}/${measurementsData.size}, userId: ${measurement.userId}, with ${values.size} values (duplicated timestamp).")
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "Failed to insert measurement (userId: ${measurement.userId}, timestamp: ${measurement.timestamp}) and its values. Error: ${e.message}", e)
                }
            }
        }
        LogManager.i(TAG, "Finished inserting measurements. ${insertedIds.size} measurements successfully inserted and ${ignoredIds.size} ignored due to duplicate timestamps.")
        return Pair(insertedIds, ignoredIds)
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

    fun getValuesForType(typeId: Int): Flow<List<MeasurementValue>> =
        measurementValueDao.getValuesForType(typeId)

    // --- Measurement Type Operations ---

    suspend fun insertAllMeasurementTypes(types: List<MeasurementType>) {
        val existingTypes = measurementTypeDao.getAll().first()
        val existingKeys = existingTypes.map { it.key }.toSet()

        val typesToInsert = types.filter { type ->
            // Allow insertion if the key is CUSTOM or if the key does not already exist in the database.
            type.key == MeasurementTypeKey.CUSTOM || type.key !in existingKeys
        }

        if (typesToInsert.isNotEmpty()) {
            LogManager.i(TAG, "Found ${typesToInsert.size} new measurement types to insert.")
            measurementTypeDao.insertAll(typesToInsert)
        } else {
            LogManager.d(TAG, "No new measurement types to insert. All provided non-custom types already exist.")
        }
    }

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


    // --- Derived Values Calculation (delegated to DerivedValuesCalculator) ---

    /**
     * Recalculates all derived measurement values (BMI, WHR, BMR, TDEE, ...) for a given measurement.
     * Delegates to [com.health.openscale.core.service.DerivedValuesCalculator]; kept here so that
     * existing external callers and the repository's own write methods (which auto-recalculate)
     * remain unchanged.
     *
     * @param measurementId The ID of the measurement for which to recalculate derived values.
     */
    suspend fun recalculateDerivedValuesForMeasurement(measurementId: Int) =
        derivedValuesCalculator.recalculateDerivedValuesForMeasurement(measurementId)
}
