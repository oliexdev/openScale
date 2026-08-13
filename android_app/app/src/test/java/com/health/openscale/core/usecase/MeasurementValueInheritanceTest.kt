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
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
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
 * Tests [MeasurementTransformationUseCase.applyValueInheritance] against in-memory Room
 * (Robolectric): a measurement coming from a scale inherits the manually kept *numeric* values
 * (waist, hips, custom types, …) of its chronological predecessor, without ever overwriting what
 * the scale reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementValueInheritanceTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var transformation: MeasurementTransformationUseCase
    private var userId = 0
    private var weightId = 0
    private var waistId = 0
    private var hipsId = 0
    private var heartRateId = 0
    private var commentId = 0
    private var bmiId = 0
    private var impedanceId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(getDefaultMeasurementTypes())

        val settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(app.cacheDir, "inheritance-${System.nanoTime()}.preferences_pb"),
        )
        val query = MeasurementQueryUseCases(repo)
        val sync = SyncUseCases(app, MeasurementTypeCrudUseCases(repo))
        transformation = MeasurementTransformationUseCase(
            settings, UserUseCases(repo, settings, sync), query
        )

        val types = repo.getAllMeasurementTypes().first()
        weightId = types.first { it.key == MeasurementTypeKey.WEIGHT }.id
        waistId = types.first { it.key == MeasurementTypeKey.WAIST }.id
        hipsId = types.first { it.key == MeasurementTypeKey.HIPS }.id
        heartRateId = types.first { it.key == MeasurementTypeKey.HEART_RATE }.id
        commentId = types.first { it.key == MeasurementTypeKey.COMMENT }.id
        bmiId = types.first { it.key == MeasurementTypeKey.BMI }.id
        impedanceId = types.first { it.key == MeasurementTypeKey.IMPEDANCE }.id

        userId = repo.insertUser(
            User(
                name = "u", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
    }

    @After
    fun tearDown() = db.close()

    /** Writes a historic measurement directly through the repository. */
    private suspend fun insertHistory(timestamp: Long, vararg values: MeasurementValue): Int {
        val id = repo.insertMeasurement(Measurement(userId = userId, timestamp = timestamp)).toInt()
        values.forEach { repo.insertMeasurementValue(it.copy(measurementId = id)) }
        return id
    }

    private fun float(typeId: Int, value: Float) =
        MeasurementValue(measurementId = 0, typeId = typeId, floatValue = value)

    private fun incoming(timestamp: Long) = Measurement(userId = userId, timestamp = timestamp)

    private fun List<MeasurementValue>.floatOf(typeId: Int) =
        firstOrNull { it.typeId == typeId }?.floatValue

    @Test
    fun inherits_fillsNumericTypesTheScaleDoesNotReport() = runBlocking {
        insertHistory(
            1_000L,
            float(weightId, 70f),
            float(waistId, 90f),
            MeasurementValue(measurementId = 0, typeId = heartRateId, intValue = 60),
        )

        val result = transformation.applyValueInheritance(
            incoming(2_000L), listOf(float(weightId, 71f))
        )

        assertThat(result.floatOf(waistId)).isEqualTo(90f)
        assertThat(result.first { it.typeId == heartRateId }.intValue).isEqualTo(60)
    }

    @Test
    fun inherits_noComment() = runBlocking {
        insertHistory(
            1_000L,
            float(weightId, 70f),
            MeasurementValue(measurementId = 0, typeId = commentId, textValue = "after breakfast"),
        )

        val result = transformation.applyValueInheritance(
            incoming(2_000L), listOf(float(weightId, 71f))
        )

        assertThat(result.none { it.typeId == commentId }).isTrue()
    }

    @Test
    fun inherits_neverOverwritesValuesFromTheScale() = runBlocking {
        insertHistory(1_000L, float(weightId, 70f), float(waistId, 90f))

        val result = transformation.applyValueInheritance(
            incoming(2_000L), listOf(float(weightId, 71f), float(waistId, 85f))
        )

        assertThat(result.filter { it.typeId == waistId }).hasSize(1)
        assertThat(result.floatOf(waistId)).isEqualTo(85f)
    }

    @Test
    fun inherits_ignoresEmptyPredecessorValues() = runBlocking {
        insertHistory(
            1_000L,
            float(weightId, 70f),
            float(waistId, 0f),
            MeasurementValue(measurementId = 0, typeId = heartRateId, intValue = 0),
        )

        val result = transformation.applyValueInheritance(
            incoming(2_000L), listOf(float(weightId, 71f))
        )

        assertThat(result.none { it.typeId == waistId }).isTrue()
        assertThat(result.none { it.typeId == heartRateId }).isTrue()
    }

    @Test
    fun inherits_usesThePredecessorNotALaterMeasurement() = runBlocking {
        insertHistory(1_000L, float(weightId, 70f), float(waistId, 90f))
        insertHistory(3_000L, float(weightId, 72f), float(waistId, 100f))

        // A historic entry read from the scale's memory, landing between the two.
        val result = transformation.applyValueInheritance(
            incoming(2_000L), listOf(float(weightId, 71f))
        )

        assertThat(result.floatOf(waistId)).isEqualTo(90f)
    }

    @Test
    fun inherits_returnsValuesUnchangedWhenNothingPrecedes() = runBlocking {
        insertHistory(3_000L, float(weightId, 72f), float(waistId, 100f))

        val values = listOf(float(weightId, 71f))
        val result = transformation.applyValueInheritance(incoming(2_000L), values)

        assertThat(result).isEqualTo(values)
    }

    @Test
    fun inherits_skipsDerivedAndInternalTypes() = runBlocking {
        insertHistory(
            1_000L,
            float(weightId, 70f),
            float(impedanceId, 500f),
            float(hipsId, 95f),
        )
        // The insert triggers the derived recalculation, so BMI exists on the predecessor.
        assertThat(repo.getMeasurementsWithValuesForUser(userId).first()
            .single().values.any { it.type.key == MeasurementTypeKey.BMI }).isTrue()

        val result = transformation.applyValueInheritance(
            incoming(2_000L), listOf(float(weightId, 71f))
        )

        assertThat(result.none { it.typeId == bmiId }).isTrue()
        assertThat(result.none { it.typeId == impedanceId }).isTrue()
        // The scale's own weight stands; the old one is not pulled in alongside it.
        assertThat(result.filter { it.typeId == weightId }).hasSize(1)
        assertThat(result.floatOf(weightId)).isEqualTo(71f)
        assertThat(result.floatOf(hipsId)).isEqualTo(95f)
    }

    @Test
    fun inherits_coversNumericCustomTypesButNoOthers() = runBlocking {
        val customFloatId = repo.insertMeasurementType(
            MeasurementType(key = MeasurementTypeKey.CUSTOM, name = "Blood pressure",
                unit = UnitType.NONE, inputType = InputFieldType.FLOAT)
        ).toInt()
        val customIntId = repo.insertMeasurementType(
            MeasurementType(key = MeasurementTypeKey.CUSTOM, name = "Steps",
                unit = UnitType.NONE, inputType = InputFieldType.INT)
        ).toInt()
        val customTextId = repo.insertMeasurementType(
            MeasurementType(key = MeasurementTypeKey.CUSTOM, name = "Mood",
                unit = UnitType.NONE, inputType = InputFieldType.TEXT)
        ).toInt()
        val customDateId = repo.insertMeasurementType(
            MeasurementType(key = MeasurementTypeKey.CUSTOM, name = "Last blood test",
                unit = UnitType.NONE, inputType = InputFieldType.DATE)
        ).toInt()
        val disabledCustomId = repo.insertMeasurementType(
            MeasurementType(key = MeasurementTypeKey.CUSTOM, name = "Retired",
                unit = UnitType.NONE, inputType = InputFieldType.FLOAT, isEnabled = false)
        ).toInt()

        insertHistory(
            1_000L,
            float(weightId, 70f),
            float(customFloatId, 120f),
            MeasurementValue(measurementId = 0, typeId = customIntId, intValue = 8_000),
            MeasurementValue(measurementId = 0, typeId = customTextId, textValue = "good"),
            MeasurementValue(measurementId = 0, typeId = customDateId, dateValue = 987_000L),
            float(disabledCustomId, 42f),
        )

        val result = transformation.applyValueInheritance(
            incoming(2_000L), listOf(float(weightId, 71f))
        )

        assertThat(result.floatOf(customFloatId)).isEqualTo(120f)
        assertThat(result.first { it.typeId == customIntId }.intValue).isEqualTo(8_000)
        assertThat(result.none { it.typeId == customTextId }).isTrue()
        assertThat(result.none { it.typeId == customDateId }).isTrue()
        assertThat(result.none { it.typeId == disabledCustomId }).isTrue()
    }

    /**
     * The form pre-fill path goes through the same method with an empty value list — so the weight,
     * which the sync path always supplies itself, is inherited here as a starting point.
     */
    @Test
    fun inherits_withoutIncomingValues_prefillsIncludingWeight() = runBlocking {
        insertHistory(
            1_000L,
            float(weightId, 70f),
            float(waistId, 90f),
            MeasurementValue(measurementId = 0, typeId = commentId, textValue = "after breakfast"),
        )

        val prefill = transformation.applyValueInheritance(incoming(2_000L), emptyList())

        assertThat(prefill.floatOf(weightId)).isEqualTo(70f)
        assertThat(prefill.floatOf(waistId)).isEqualTo(90f)
        assertThat(prefill.none { it.typeId == commentId }).isTrue()
        assertThat(prefill.none { it.typeId == bmiId }).isTrue()
    }

    @Test
    fun inherits_ignoresHistoryOfAnotherUser() = runBlocking {
        val otherUserId = repo.insertUser(
            User(
                name = "other", birthDate = 0L, gender = GenderType.FEMALE, heightCm = 165f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
        val otherMeasurementId =
            repo.insertMeasurement(Measurement(userId = otherUserId, timestamp = 1_000L)).toInt()
        repo.insertMeasurementValue(float(waistId, 75f).copy(measurementId = otherMeasurementId))

        val values = listOf(float(weightId, 71f))
        val result = transformation.applyValueInheritance(incoming(2_000L), values)

        assertThat(result).isEqualTo(values)
    }
}
