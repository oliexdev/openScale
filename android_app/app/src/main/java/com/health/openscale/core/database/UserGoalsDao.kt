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
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.health.openscale.core.data.UserGoals
import kotlinx.coroutines.flow.Flow

@Dao
interface UserGoalsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(goal: UserGoals): Long

    @Update
    suspend fun update(goal: UserGoals)

    @Query("DELETE FROM user_goals WHERE userId = :userId AND measurementTypeId = :typeId")
    suspend fun delete(userId: Int, typeId: Int)

    @Query("SELECT * FROM user_goals WHERE userId = :userId")
    fun getAllForUser(userId: Int): Flow<List<UserGoals>>
}