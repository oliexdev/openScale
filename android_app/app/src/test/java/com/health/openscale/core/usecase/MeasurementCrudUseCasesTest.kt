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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.getDefaultMeasurementTypes
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests [MeasurementCrudUseCases.saveMeasurement] — insert and the update value-diff
 * (delete removed / update existing / insert new) — against in-memory Room (Robolectric).
 * Assertions target specific measurement types since recalculation adds derived values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementCrudUseCasesTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var crud: MeasurementCrudUseCases
    private var userId = 0
    private var weightId = 0
    private var waistId = 0
    private var neckId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(getDefaultMeasurementTypes())
        val settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(app.cacheDir, "crud-${System.nanoTime()}.preferences_pb"),
        )
        crud = RoomTestSupport.measurementCrudFor(app, repo, settings)

        val types = repo.getAllMeasurementTypes().first()
        weightId = types.first { it.key == MeasurementTypeKey.WEIGHT }.id
        waistId = types.first { it.key == MeasurementTypeKey.WAIST }.id
        neckId = types.first { it.key == MeasurementTypeKey.NECK }.id

        userId = repo.insertUser(
            User(
                name = "u", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun saveMeasurement_insert_persistsValues() = runBlocking {
        val id = crud.saveMeasurement(
            Measurement(userId = userId, timestamp = 1_000L),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 70f)),
        ).getOrThrow()

        assertThat(id).isGreaterThan(0)
        val values = repo.getValuesForMeasurement(id).first()
        assertThat(values.any { it.typeId == weightId && it.floatValue == 70f }).isTrue()
    }

    @Test
    fun saveMeasurement_update_appliesValueDiff() = runBlocking {
        // initial: WEIGHT + WAIST
        val id = crud.saveMeasurement(
            Measurement(userId = userId, timestamp = 2_000L),
            listOf(
                MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 70f),
                MeasurementValue(measurementId = 0, typeId = waistId, floatValue = 90f),
            ),
        ).getOrThrow()
        val weightValueId = repo.getValuesForMeasurement(id).first().first { it.typeId == weightId }.id

        // update: keep+change WEIGHT, drop WAIST, add NECK
        crud.saveMeasurement(
            Measurement(id = id, userId = userId, timestamp = 2_000L),
            listOf(
                MeasurementValue(id = weightValueId, measurementId = id, typeId = weightId, floatValue = 72f),
                MeasurementValue(measurementId = 0, typeId = neckId, floatValue = 38f),
            ),
        ).getOrThrow()

        val after = repo.getValuesForMeasurement(id).first()
        assertThat(after.none { it.typeId == waistId }).isTrue()                       // deleted
        assertThat(after.first { it.typeId == weightId }.floatValue).isWithin(1e-3f).of(72f) // updated
        assertThat(after.any { it.typeId == neckId && it.floatValue == 38f }).isTrue()  // inserted
    }
}
