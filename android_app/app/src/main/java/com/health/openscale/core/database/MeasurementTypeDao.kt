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
import androidx.room.Update
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementTypeDao {
    @Insert
    suspend fun insert(type: MeasurementType): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(types: List<MeasurementType>)

    @Update
    suspend fun update(type: MeasurementType)

    @Delete
    suspend fun delete(type: MeasurementType)

    @Query("SELECT * FROM MeasurementType ORDER BY displayOrder ASC")
    fun getAll(): Flow<List<MeasurementType>>
}