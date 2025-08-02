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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Measurement and MeasurementValue entities.
 */
@Dao
interface MeasurementDao {

    /**
     * Inserts a measurement. If the measurement already exists based on its primary key, it's replaced.
     * @param measurement The measurement to insert.
     * @return The row ID of the newly inserted measurement.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: Measurement): Long

    /**
     * Inserts a list of measurement values.
     * The `measurementId` in each [MeasurementValue] object MUST be correctly set beforehand.
     * Existing values with the same primary key will be replaced.
     *
     * @param values The list of measurement values to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurementValues(values: List<MeasurementValue>)

    /**
     * Inserts a single measurement and its associated values within a transaction.
     * This method ensures that the correct `measurementId` is set for each value
     * after the main measurement has been inserted and its ID is available.
     *
     * @param measurement The measurement to insert.
     * @param values The list of associated measurement values.
     */
    @Transaction
    suspend fun insertSingleMeasurementWithItsValues(measurement: Measurement, values: List<MeasurementValue>) {
        val measurementId = insert(measurement) // Insert the main measurement to get its ID

        // Update each MeasurementValue with the correct measurementId
        val updatedValues = values.map { value ->
            // Important: Create a new instance if MeasurementValue is a data class to ensure immutability.
            value.copy(measurementId = measurementId.toInt())
        }

        if (updatedValues.isNotEmpty()) {
            insertMeasurementValues(updatedValues) // Insert the updated measurement values
        }
    }

    /**
     * Updates an existing measurement.
     * @param measurement The measurement to update.
     */
    @Update
    suspend fun update(measurement: Measurement)

    /**
     * Deletes a measurement.
     * @param measurement The measurement to delete.
     */
    @Delete
    suspend fun delete(measurement: Measurement)

    /**
     * Deletes all measurements for a specific user.
     * @param userId The ID of the user whose measurements are to be deleted.
     * @return The number of measurements deleted.
     */
    @Query("DELETE FROM Measurement WHERE userId = :userId")
    suspend fun deleteMeasurementsByUserId(userId: Int): Int

    /**
     * Retrieves all measurements with their associated values for a specific user, ordered by timestamp descending.
     * @param userId The ID of the user.
     * @return A Flow emitting a list of [MeasurementWithValues].
     */
    @Transaction
    @Query("SELECT * FROM Measurement WHERE userId = :userId ORDER BY timestamp DESC")
    fun getMeasurementsWithValuesForUser(userId: Int): Flow<List<MeasurementWithValues>>

    /**
     * Retrieves a specific measurement with its associated values by its ID.
     * @param measurementId The ID of the measurement.
     * @return A Flow emitting a [MeasurementWithValues] object or null if not found.
     */
    @Transaction
    @Query("SELECT * FROM Measurement WHERE id = :measurementId")
    fun getMeasurementWithValuesById(measurementId: Int): Flow<MeasurementWithValues?>

    /**
     * Retrieves all measurement values with their associated type information for a specific measurement.
     * @param measurementId The ID of the measurement.
     * @return A Flow emitting a list of [MeasurementValueWithType].
     */
    @Transaction
    @Query("SELECT * FROM MeasurementValue WHERE measurementId = :measurementId")
    fun getValuesWithTypeForMeasurement(measurementId: Int): Flow<List<MeasurementValueWithType>>

    /**
     * Retrieves a specific measurement by its ID, without its associated values.
     * @param id The ID of the measurement.
     * @return The [Measurement] object or null if not found.
     */
    @Query("SELECT * FROM Measurement WHERE id = :id")
    suspend fun getMeasurementById(id: Int): Measurement?
}
