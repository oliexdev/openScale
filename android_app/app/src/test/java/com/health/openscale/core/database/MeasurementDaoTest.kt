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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room DAO behaviour, run on the JVM via Robolectric (no emulator needed).
 * Covers the constraints that can silently break: the UNIQUE(userId, timestamp)
 * conflict-ignore, and foreign-key CASCADE deletes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementDaoTest {

    private lateinit var db: AppDatabase
    private var userId: Int = 0

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        userId = db.userDao().insert(
            User(
                name = "Test",
                birthDate = 0L,
                gender = GenderType.MALE,
                heightCm = 180f,
                activityLevel = ActivityLevel.MODERATE,
                useAssistedWeighing = false,
            )
        ).toInt()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insert_duplicateUserIdAndTimestamp_isIgnoredAndReturnsMinusOne() = runBlocking {
        val ts = 1_000L
        val first = db.measurementDao().insert(Measurement(userId = userId, timestamp = ts))
        val second = db.measurementDao().insert(Measurement(userId = userId, timestamp = ts))

        assertThat(first).isGreaterThan(0L)
        assertThat(second).isEqualTo(-1L)
        assertThat(db.measurementDao().getMeasurementsWithValuesForUser(userId).first()).hasSize(1)
    }

    @Test
    fun deleteUser_cascadesToMeasurementsAndValues() = runBlocking {
        val typeId = db.measurementTypeDao().insert(
            MeasurementType(key = MeasurementTypeKey.WEIGHT, inputType = InputFieldType.FLOAT)
        ).toInt()
        val measurementId = db.measurementDao().insert(
            Measurement(userId = userId, timestamp = 2_000L)
        ).toInt()
        db.measurementValueDao().insert(
            MeasurementValue(measurementId = measurementId, typeId = typeId, floatValue = 80f)
        )

        // sanity: data is there
        assertThat(db.measurementDao().getMeasurementsWithValuesForUser(userId).first()).hasSize(1)

        // delete the owning user -> ON DELETE CASCADE must remove measurements and their values
        db.userDao().delete(db.userDao().getById(userId).first()!!)

        assertThat(db.measurementDao().getMeasurementsWithValuesForUser(userId).first()).isEmpty()
        assertThat(db.measurementValueDao().getValuesForMeasurement(measurementId).first()).isEmpty()
    }
}
