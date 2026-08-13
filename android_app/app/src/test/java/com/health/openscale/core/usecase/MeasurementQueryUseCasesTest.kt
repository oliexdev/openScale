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
package com.health.openscale.core.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.testutil.Fixtures
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementQueryUseCasesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var query: MeasurementQueryUseCases

    @Before
    fun setUp() {
        db = RoomTestSupport.inMemory(context)
        repo = RoomTestSupport.repositoryFor(db)
        query = MeasurementQueryUseCases(repo)
    }

    @After
    fun tearDown() = db.close()

    // ---- findClosestMeasurement (pure) ------------------------------------------------------

    @Test
    fun findClosestMeasurement_emptyList_returnsNull() {
        assertThat(query.findClosestMeasurement(Fixtures.ts(2025, 1, 10), emptyList())).isNull()
    }

    @Test
    fun findClosestMeasurement_prefersSameDay_evenIfAnotherDayIsCloserInTime() {
        val selected = Fixtures.ts(2025, 1, 10, hour = 1)
        val sameDay = Fixtures.mwv(1, Fixtures.ts(2025, 1, 10, hour = 12), emptyList()) // same day, ~11h away
        val prevDayCloser = Fixtures.mwv(2, Fixtures.ts(2025, 1, 9, hour = 23), emptyList()) // other day, ~2h away

        val (index, match) = query.findClosestMeasurement(selected, listOf(sameDay, prevDayCloser))!!
        assertThat(index).isEqualTo(0)
        assertThat(match.measurement.id).isEqualTo(1)
    }

    @Test
    fun findClosestMeasurement_noSameDay_returnsAbsoluteClosest() {
        val selected = Fixtures.ts(2025, 1, 10, hour = 12)
        val before = Fixtures.mwv(1, Fixtures.ts(2025, 1, 8, hour = 12), emptyList())
        val after = Fixtures.mwv(2, Fixtures.ts(2025, 1, 11, hour = 12), emptyList()) // 1 day away — closest

        val (_, match) = query.findClosestMeasurement(selected, listOf(before, after))!!
        assertThat(match.measurement.id).isEqualTo(2)
    }

    // ---- getMeasurementsForUser (DB) --------------------------------------------------------

    @Test
    fun getMeasurementsForUser_returnsInsertedMeasurements() = runBlocking {
        val uid = repo.insertUser(
            User(
                name = "u", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
        repo.insertMeasurement(Measurement(userId = uid, timestamp = 1_000L))
        repo.insertMeasurement(Measurement(userId = uid, timestamp = 2_000L))

        assertThat(query.getMeasurementsForUser(uid).first()).hasSize(2)
    }
}
