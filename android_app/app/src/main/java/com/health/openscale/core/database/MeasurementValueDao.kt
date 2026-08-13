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
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.health.openscale.core.data.MeasurementValue
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementValueDao {
    @Insert
    suspend fun insert(value: MeasurementValue): Long

    @Update
    suspend fun update(value: MeasurementValue)

    @Query("DELETE FROM MeasurementValue WHERE id = :valueId")
    suspend fun deleteById(valueId: Int)

    @Insert
    suspend fun insertAll(values: List<MeasurementValue>)

    @Query("SELECT * FROM MeasurementValue WHERE measurementId = :measurementId")
    fun getValuesForMeasurement(measurementId: Int): Flow<List<MeasurementValue>>

    @Query("SELECT * FROM MeasurementValue WHERE typeId = :typeId")
    fun getValuesForType(typeId: Int): Flow<List<MeasurementValue>>
}