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
package com.health.openscale.core.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
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
 * Integration test for [DerivedValuesCalculator] against a real (in-memory) Room database.
 * Complements the pure-formula unit tests by verifying the DAO wiring: that recalculation
 * reads the raw values + user, computes, and persists the derived rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivedValuesCalculatorIntegrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var calculator: DerivedValuesCalculator
    private var userId = 0
    private var weightTypeId = 0
    private var bmiTypeId = 0
    private var bodyFatTypeId = 0
    private var metabolicAgeTypeId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(context)
        db.measurementTypeDao().insertAll(getDefaultMeasurementTypes())
        val types = db.measurementTypeDao().getAll().first()
        weightTypeId = types.first { it.key == MeasurementTypeKey.WEIGHT }.id
        bmiTypeId = types.first { it.key == MeasurementTypeKey.BMI }.id
        bodyFatTypeId = types.first { it.key == MeasurementTypeKey.BODY_FAT }.id
        metabolicAgeTypeId = types.first { it.key == MeasurementTypeKey.METABOLIC_AGE }.id

        userId = db.userDao().insert(
            User(
                name = "u",
                birthDate = 0L,
                gender = GenderType.MALE,
                heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE,
                useAssistedWeighing = false,
            )
        ).toInt()

        calculator = DerivedValuesCalculator(
            userDao = db.userDao(),
            measurementDao = db.measurementDao(),
            measurementTypeDao = db.measurementTypeDao(),
            measurementValueDao = db.measurementValueDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recalculate_insertsBmi_fromWeightAndUserHeight() = runBlocking {
        val measurementId = db.measurementDao().insert(
            Measurement(userId = userId, timestamp = 1_000L)
        ).toInt()
        db.measurementValueDao().insert(
            MeasurementValue(measurementId = measurementId, typeId = weightTypeId, floatValue = 70f)
        )

        calculator.recalculateDerivedValuesForMeasurement(measurementId)

        val values = db.measurementValueDao().getValuesForMeasurement(measurementId).first()
        val bmi = values.firstOrNull { it.typeId == bmiTypeId }?.floatValue
        assertThat(bmi).isNotNull()
        // 70 / 1.75^2 = 22.857, persisted via roundTo (truncates to 2 decimals) -> 22.85
        assertThat(bmi!!).isWithin(0.05f).of(22.85f)
    }

    @Test
    fun recalculate_removesDerivedValue_whenSourceRemoved() = runBlocking {
        val measurementId = db.measurementDao().insert(
            Measurement(userId = userId, timestamp = 2_000L)
        ).toInt()
        val weightValueId = db.measurementValueDao().insert(
            MeasurementValue(measurementId = measurementId, typeId = weightTypeId, floatValue = 80f)
        )
        calculator.recalculateDerivedValuesForMeasurement(measurementId)
        assertThat(
            db.measurementValueDao().getValuesForMeasurement(measurementId).first()
                .any { it.typeId == bmiTypeId }
        ).isTrue()

        // remove the weight source and recalc -> BMI must be deleted
        db.measurementValueDao().deleteById(weightValueId.toInt())
        calculator.recalculateDerivedValuesForMeasurement(measurementId)

        assertThat(
            db.measurementValueDao().getValuesForMeasurement(measurementId).first()
                .any { it.typeId == bmiTypeId }
        ).isFalse()
    }

    @Test
    fun recalculate_insertsMetabolicAge_fromWeightAndBodyFat() = runBlocking {
        val measurementId = db.measurementDao().insert(
            Measurement(userId = userId, timestamp = 3_000L)
        ).toInt()
        db.measurementValueDao().insert(
            MeasurementValue(measurementId = measurementId, typeId = weightTypeId, floatValue = 80f)
        )
        db.measurementValueDao().insert(
            MeasurementValue(measurementId = measurementId, typeId = bodyFatTypeId, floatValue = 20f)
        )

        calculator.recalculateDerivedValuesForMeasurement(measurementId)

        val values = db.measurementValueDao().getValuesForMeasurement(measurementId).first()
        val metabolicAge = values.firstOrNull { it.typeId == metabolicAgeTypeId }?.floatValue
        assertThat(metabolicAge).isNotNull()
        // FFM = 80*(1-0.20) = 64 -> actualBMR = 370 + 21.6*64 = 1752.4
        // intercept (male) = 10*80 + 6.25*175 + 5 = 1898.75 -> metAge = 146.35/5 = 29.27
        assertThat(metabolicAge!!).isWithin(0.05f).of(29.27f)
    }

    @Test
    fun recalculate_skipsMetabolicAge_whenNoBodyComposition() = runBlocking {
        val measurementId = db.measurementDao().insert(
            Measurement(userId = userId, timestamp = 4_000L)
        ).toInt()
        val bodyFatValueId = db.measurementValueDao().insert(
            MeasurementValue(measurementId = measurementId, typeId = weightTypeId, floatValue = 80f)
        ).let {
            db.measurementValueDao().insert(
                MeasurementValue(measurementId = measurementId, typeId = bodyFatTypeId, floatValue = 20f)
            )
        }
        calculator.recalculateDerivedValuesForMeasurement(measurementId)
        assertThat(
            db.measurementValueDao().getValuesForMeasurement(measurementId).first()
                .any { it.typeId == metabolicAgeTypeId }
        ).isTrue()

        // remove the body-fat source and recalc -> metabolic age must be deleted
        db.measurementValueDao().deleteById(bodyFatValueId.toInt())
        calculator.recalculateDerivedValuesForMeasurement(measurementId)

        assertThat(
            db.measurementValueDao().getValuesForMeasurement(measurementId).first()
                .any { it.typeId == metabolicAgeTypeId }
        ).isFalse()
    }
}
