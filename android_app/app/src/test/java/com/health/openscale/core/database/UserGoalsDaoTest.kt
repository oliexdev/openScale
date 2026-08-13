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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [UserGoalsDao] behaviour on the JVM (Robolectric): conflict-ignore on the composite primary key,
 * delete by (userId, typeId), and foreign-key CASCADE when the owning user is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserGoalsDaoTest {

    private lateinit var db: AppDatabase
    private var userId = 0
    private var typeId = 0

    @Before
    fun setUp() = runBlocking {
        db = com.health.openscale.testutil.RoomTestSupport.inMemory(ApplicationProvider.getApplicationContext())
        userId = db.userDao().insert(
            User(
                name = "u", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
        typeId = db.measurementTypeDao().insert(MeasurementType(key = MeasurementTypeKey.WEIGHT)).toInt()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insert_duplicateCompositeKey_isIgnoredAndKeepsOriginal() = runBlocking {
        val first = db.userGoalsDao().insert(UserGoals(userId, typeId, goalValue = 70f))
        val second = db.userGoalsDao().insert(UserGoals(userId, typeId, goalValue = 99f))

        assertThat(first).isAtLeast(0L)
        assertThat(second).isEqualTo(-1L) // OnConflict.IGNORE
        val goals = db.userGoalsDao().getAllForUser(userId).first()
        assertThat(goals).hasSize(1)
        assertThat(goals.single().goalValue).isWithin(1e-3f).of(70f)
    }

    @Test
    fun delete_removesGoalByCompositeKey() = runBlocking {
        db.userGoalsDao().insert(UserGoals(userId, typeId, goalValue = 70f))
        db.userGoalsDao().delete(userId, typeId)
        assertThat(db.userGoalsDao().getAllForUser(userId).first()).isEmpty()
    }

    @Test
    fun deleteUser_cascadesToGoals() = runBlocking {
        db.userGoalsDao().insert(UserGoals(userId, typeId, goalValue = 70f))
        db.userDao().delete(db.userDao().getById(userId).first()!!)
        assertThat(db.userGoalsDao().getAllForUser(userId).first()).isEmpty()
    }
}
