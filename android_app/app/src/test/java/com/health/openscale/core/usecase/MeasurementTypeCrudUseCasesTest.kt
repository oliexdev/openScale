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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.getDefaultMeasurementTypes
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the unit-conversion logic in [MeasurementTypeCrudUseCases.updateTypeAndConvertValues]
 * against in-memory Room (Robolectric). Covers generic length conversion, percent<->absolute
 * composition conversion (using the per-measurement WEIGHT), and the skip/no-op paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementTypeCrudUseCasesTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var useCase: MeasurementTypeCrudUseCases
    private var userId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(ApplicationProvider.getApplicationContext())
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(getDefaultMeasurementTypes())
        useCase = MeasurementTypeCrudUseCases(repo)
        userId = repo.insertUser(
            User(
                name = "u", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun type(key: MeasurementTypeKey): MeasurementType =
        repo.getAllMeasurementTypes().first().first { it.key == key }

    private suspend fun newMeasurement(timestamp: Long): Int =
        repo.insertMeasurement(Measurement(userId = userId, timestamp = timestamp)).toInt()

    private suspend fun valueOf(typeId: Int): Float? =
        repo.getValuesForType(typeId).first().firstOrNull()?.floatValue

    @Test
    fun sameUnit_updatesDefinitionWithoutConverting() = runBlocking {
        val waist = type(MeasurementTypeKey.WAIST)
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = waist.id, floatValue = 90f))

        val report = useCase.updateTypeAndConvertValues(waist, waist.copy(name = "Bauch")).getOrThrow()

        assertThat(report.attempted).isFalse()
        assertThat(valueOf(waist.id)).isWithin(1e-3f).of(90f)
        assertThat(type(MeasurementTypeKey.WAIST).name).isEqualTo("Bauch")
    }

    @Test
    fun genericConversion_centimeterToInch() = runBlocking {
        val waist = type(MeasurementTypeKey.WAIST) // unit CM by default
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = waist.id, floatValue = 100f))

        val report = useCase.updateTypeAndConvertValues(waist, waist.copy(unit = UnitType.INCH)).getOrThrow()

        assertThat(report.attempted).isTrue()
        assertThat(report.updatedCount).isEqualTo(1)
        assertThat(valueOf(waist.id)).isWithin(1e-2f).of(39.3701f)
    }

    @Test
    fun compositionConversion_percentToKg_usesPerMeasurementWeight() = runBlocking {
        val weight = type(MeasurementTypeKey.WEIGHT)   // KG
        val bodyFat = type(MeasurementTypeKey.BODY_FAT) // PERCENT
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = weight.id, floatValue = 80f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = bodyFat.id, floatValue = 20f))

        val report = useCase.updateTypeAndConvertValues(bodyFat, bodyFat.copy(unit = UnitType.KG)).getOrThrow()

        assertThat(report.updatedCount).isEqualTo(1)
        assertThat(valueOf(bodyFat.id)).isWithin(1e-2f).of(16f) // 20% of 80kg
    }

    @Test
    fun compositionConversion_percentToKg_skipsRowsWithoutWeight() = runBlocking {
        val bodyFat = type(MeasurementTypeKey.BODY_FAT)
        val mId = newMeasurement(1_000L)
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = bodyFat.id, floatValue = 20f))

        val report = useCase.updateTypeAndConvertValues(bodyFat, bodyFat.copy(unit = UnitType.KG)).getOrThrow()

        assertThat(report.attempted).isTrue()
        assertThat(report.updatedCount).isEqualTo(0)
        assertThat(valueOf(bodyFat.id)).isWithin(1e-3f).of(20f) // unchanged
    }
}
