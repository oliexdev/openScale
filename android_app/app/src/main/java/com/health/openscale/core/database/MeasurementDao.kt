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
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Measurement and MeasurementValue entities.
 */
@Dao
interface MeasurementDao {

    /**
     * Inserts a measurement. If the measurement already exists based on its primary key and timestamp, it's ignored.
     * @param measurement The measurement to insert.
     * @return The row ID of the newly inserted measurement if the insertion was successful.
     *         Returns `-1L` if the insertion was ignored due to a conflict with an existing
     *         unique constraint (e.g., `userId` and `timestamp` combination already present).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(measurement: Measurement): Long

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
