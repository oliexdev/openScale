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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.InBodyCalibration
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InBodyCalibrationUseCasesTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var settings: SettingsFacade
    private lateinit var query: MeasurementQueryUseCases
    private lateinit var transformation: MeasurementTransformationUseCase
    private lateinit var calibrationUseCases: InBodyCalibrationUseCases

    private var userId = 0
    private var weightType: MeasurementType? = null
    private var fatType: MeasurementType? = null
    private var lbmType: MeasurementType? = null
    private var muscleType: MeasurementType? = null
    private var waterType: MeasurementType? = null
    private var boneType: MeasurementType? = null
    private var vfType: MeasurementType? = null
    private var proteinType: MeasurementType? = null
    private var impedanceType: MeasurementType? = null

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())

        settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(app.cacheDir, "cal-test-${System.nanoTime()}.preferences_pb")
        )
        query = MeasurementQueryUseCases(repo)
        val sync = SyncUseCases(app, MeasurementTypeCrudUseCases(repo, ApplicationProvider.getApplicationContext()))
        val userUseCases = UserUseCases(ApplicationProvider.getApplicationContext(), repo, settings, sync)
        transformation = MeasurementTransformationUseCase(settings, userUseCases, query, repo)
        calibrationUseCases = InBodyCalibrationUseCases(repo, settings, query, transformation)

        val types = repo.getAllMeasurementTypes().first()
        weightType = types.find { it.key == MeasurementType.WEIGHT }
        fatType = types.find { it.key == MeasurementType.BODY_FAT }
        lbmType = types.find { it.key == MeasurementType.LBM }
        muscleType = types.find { it.key == MeasurementType.MUSCLE }
        waterType = types.find { it.key == MeasurementType.WATER }
        boneType = types.find { it.key == MeasurementType.BONE }
        vfType = types.find { it.key == MeasurementType.VISCERAL_FAT }
        proteinType = types.find { it.key == MeasurementType.PROTEIN }
        impedanceType = types.find { it.key == MeasurementType.IMPEDANCE }

        userId = repo.insertUser(
            User(
                name = "Tester", birthDate = 0L, gender = GenderType.MALE, heightCm = 180f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false
            )
        ).toInt()
    }

    @After
    fun tearDown() = db.close()

    private fun createBaseline(
        timestamp: Long = 10_000L,
        weightKg: Float = 80.0f,
        fatPercent: Float = 20.0f,
        lbmKg: Float = 64.0f,
        musclePercent: Float = 40.0f,
        waterPercent: Float = 55.0f,
        boneKg: Float = 3.2f,
        visceralFat: Float = 10.0f,
        impedanceOhm: Float = 500.0f
    ): MeasurementWithValues {
        val m = Measurement(id = 1, userId = userId, timestamp = timestamp)
        val values = listOf(
            MeasurementValueWithType(MeasurementValue(1, 1, weightType!!.id, floatValue = weightKg), weightType!!),
            MeasurementValueWithType(MeasurementValue(2, 1, fatType!!.id, floatValue = fatPercent), fatType!!),
            MeasurementValueWithType(MeasurementValue(3, 1, lbmType!!.id, floatValue = lbmKg), lbmType!!),
            MeasurementValueWithType(MeasurementValue(4, 1, muscleType!!.id, floatValue = musclePercent), muscleType!!),
            MeasurementValueWithType(MeasurementValue(5, 1, waterType!!.id, floatValue = waterPercent), waterType!!),
            MeasurementValueWithType(MeasurementValue(6, 1, boneType!!.id, floatValue = boneKg), boneType!!),
            MeasurementValueWithType(MeasurementValue(7, 1, vfType!!.id, floatValue = visceralFat), vfType!!),
            MeasurementValueWithType(MeasurementValue(8, 1, impedanceType!!.id, floatValue = impedanceOhm), impedanceType!!)
        )
        return MeasurementWithValues(m, values)
    }

    @Test
    fun validateInputs_rejectsMissingImpedance() {
        val m = Measurement(id = 1, userId = userId, timestamp = 10_000L)
        val noImpedance = MeasurementWithValues(
            m,
            listOf(MeasurementValueWithType(MeasurementValue(1, 1, weightType!!.id, floatValue = 80f), weightType!!))
        )
        val input = InBodyFormInput(inBodyWeightKg = 80f)
        val error = calibrationUseCases.validateInputs(input, noImpedance)
        assertThat(error).contains("impedance")
    }

    @Test
    fun validateInputs_rejectsMissingBodyComposition() {
        val m = Measurement(id = 1, userId = userId, timestamp = 10_000L)
        val partialMeasurement = MeasurementWithValues(
            m,
            listOf(
                MeasurementValueWithType(MeasurementValue(1, 1, weightType!!.id, floatValue = 80f), weightType!!),
                MeasurementValueWithType(MeasurementValue(2, 1, impedanceType!!.id, floatValue = 500f), impedanceType!!)
            )
        )
        val input = InBodyFormInput(inBodyWeightKg = 80f)
        val error = calibrationUseCases.validateInputs(input, partialMeasurement)
        assertThat(error).contains("body composition")
    }

    @Test
    fun validateInputs_rejectsOutOfRangeWeights() {
        val baseline = createBaseline()
        assertThat(calibrationUseCases.validateInputs(InBodyFormInput(inBodyWeightKg = 5f), baseline)).isNotNull()
        assertThat(calibrationUseCases.validateInputs(InBodyFormInput(inBodyWeightKg = 400f), baseline)).isNotNull()
        assertThat(calibrationUseCases.validateInputs(InBodyFormInput(inBodyWeightKg = 80f), baseline)).isNull()
    }

    @Test
    fun calculateCalibration_normalizesMassByWeightRatio_andComputesKgOffsets() = runBlocking {
        val baseline = createBaseline(
            weightKg = 80.0f,
            fatPercent = 20.0f,
            lbmKg = 64.0f,
            musclePercent = 40.0f, // 32.0 kg
            waterPercent = 55.0f,  // 44.0 kg
            boneKg = 3.2f,
            visceralFat = 10.0f,
            impedanceOhm = 500f
        )

        // InBody scan weight is 80.8 kg (clothing/timing difference)
        // Ratio W0 / W_InBody = 80.0 / 80.8 = 0.990099
        val inBodyInput = InBodyFormInput(
            inBodyWeightKg = 80.8f,
            inBodyFatPercent = 15.0f, // FFM = 80.8 * 0.85 = 68.68 kg -> normalized = 68.68 * (80.0/80.8) = 68.0 kg
            inBodyMuscleKg = 35.0f,   // normalized = 35.0 * (80.0/80.8) = 34.653 kg
            inBodyWaterLiters = 46.0f,// 46.0 * 0.995 = 45.77 kg -> normalized = 45.77 * (80.0/80.8) = 45.316 kg
            inBodyBoneKg = 3.5f,      // normalized = 3.5 * (80.0/80.8) = 3.465 kg
            inBodyVisceralFat = 7.0f  // dimensionless level
        )

        val cal = calibrationUseCases.calculateCalibration(
            userId = userId,
            baseline = baseline,
            input = inBodyInput,
            scaleName = "Test Smart Scale"
        )

        // Delta FFM: 68.0 kg (normalized) - 64.0 kg (scale) = +4.0 kg
        assertThat(cal.offsetFfmKg).isNotNull()
        assertThat(cal.offsetFfmKg!!).isWithin(0.05f).of(4.0f)

        // Delta Muscle: 34.65 kg (normalized) - 32.0 kg (scale) = +2.65 kg
        assertThat(cal.offsetMuscleKg).isNotNull()
        assertThat(cal.offsetMuscleKg!!).isWithin(0.05f).of(2.65f)

        // Delta Water: 45.32 kg (normalized) - 44.0 kg (scale) = +1.32 kg
        assertThat(cal.offsetWaterKg).isNotNull()
        assertThat(cal.offsetWaterKg!!).isWithin(0.05f).of(1.32f)

        // Delta Bone: 3.47 kg (normalized) - 3.2 kg (scale) = +0.27 kg
        assertThat(cal.offsetBoneKg).isNotNull()
        assertThat(cal.offsetBoneKg!!).isWithin(0.05f).of(0.27f)

        // Delta Visceral Fat: 7.0 - 10.0 = -3.0
        assertThat(cal.offsetVisceralFat).isNotNull()
        assertThat(cal.offsetVisceralFat!!).isWithin(0.05f).of(-3.0f)
    }

    @Test
    fun applyInBodyCalibration_preservesMassConservation_andDerivesFatFromCalibratedFFM() = runBlocking {
        // Active calibration with offsetFfmKg = +3.0 kg
        val calibration = InBodyCalibration(
            userId = userId,
            scaleMeasurementTimestamp = 10_000L,
            inBodyWeightKg = 80.0f,
            offsetFfmKg = 3.0f,
            offsetMuscleKg = 1.5f,
            offsetWaterKg = 2.0f,
            offsetBoneKg = 0.3f,
            offsetVisceralFat = -2.0f
        )
        repo.insertOrUpdateInBodyCalibration(calibration)

        // Future weigh-in at timestamp 20_000L (weight = 80.0 kg, uncalibrated FFM = 64.0 kg, uncalibrated fat = 20.0%)
        val measurement = Measurement(id = 2, userId = userId, timestamp = 20_000L)
        val incomingValues = listOf(
            MeasurementValue(typeId = weightType!!.id, floatValue = 80.0f, measurementId = 2),
            MeasurementValue(typeId = impedanceType!!.id, floatValue = 500.0f, measurementId = 2),
            MeasurementValue(typeId = lbmType!!.id, floatValue = 64.0f, measurementId = 2),
            MeasurementValue(typeId = fatType!!.id, floatValue = 20.0f, measurementId = 2),
            MeasurementValue(typeId = muscleType!!.id, floatValue = 40.0f, measurementId = 2),
            MeasurementValue(typeId = waterType!!.id, floatValue = 55.0f, measurementId = 2),
            MeasurementValue(typeId = boneType!!.id, floatValue = 3.2f, measurementId = 2),
            MeasurementValue(typeId = vfType!!.id, floatValue = 10.0f, measurementId = 2)
        )

        val calibrated = transformation.applyInBodyCalibration(measurement, incomingValues)

        // Calibrated FFM should be 64.0 + 3.0 = 67.0 kg
        val calLbm = calibrated.find { it.typeId == lbmType!!.id }?.floatValue
        assertThat(calLbm).isNotNull()
        assertThat(calLbm!!).isWithin(0.05f).of(67.0f)

        // Calibrated Body Fat %: derived directly from calibrated FFM!
        // Fat Mass = 80.0 - 67.0 = 13.0 kg
        // Fat % = (13.0 / 80.0) * 100 = 16.25%
        val calFat = calibrated.find { it.typeId == fatType!!.id }?.floatValue
        assertThat(calFat).isNotNull()
        assertThat(calFat!!).isWithin(0.05f).of(16.25f)

        // Physical Mass Conservation Check: Fat Mass + FFM == Total Weight
        val fatMassKg = 80.0f * (calFat / 100f)
        assertThat(fatMassKg + calLbm).isWithin(0.1f).of(80.0f)

        // Muscle: uncalibrated 40% (32.0 kg) + 1.5 kg = 33.5 kg -> (33.5 / 80.0) * 100 = 41.88%
        val calMuscle = calibrated.find { it.typeId == muscleType!!.id }?.floatValue
        assertThat(calMuscle).isNotNull()
        assertThat(calMuscle!!).isWithin(0.1f).of(41.88f)

        // Visceral fat: 10.0 - 2.0 = 8.0
        val calVf = calibrated.find { it.typeId == vfType!!.id }?.floatValue
        assertThat(calVf).isNotNull()
        assertThat(calVf!!).isWithin(0.05f).of(8.0f)
    }

    @Test
    fun applyInBodyCalibration_ignoresMeasurementsWithoutImpedance() = runBlocking {
        val calibration = InBodyCalibration(
            userId = userId,
            scaleMeasurementTimestamp = 10_000L,
            inBodyWeightKg = 80.0f,
            offsetFfmKg = 3.0f
        )
        repo.insertOrUpdateInBodyCalibration(calibration)

        // Measurement without impedance (e.g. manual entry or non-impedance scale)
        val measurement = Measurement(id = 3, userId = userId, timestamp = 20_000L)
        val incomingValues = listOf(
            MeasurementValue(typeId = weightType!!.id, floatValue = 80.0f, measurementId = 3),
            MeasurementValue(typeId = lbmType!!.id, floatValue = 64.0f, measurementId = 3),
            MeasurementValue(typeId = fatType!!.id, floatValue = 20.0f, measurementId = 3)
        )

        val result = transformation.applyInBodyCalibration(measurement, incomingValues)
        // Values should be untouched
        assertThat(result.find { it.typeId == lbmType!!.id }?.floatValue).isEqualTo(64.0f)
        assertThat(result.find { it.typeId == fatType!!.id }?.floatValue).isEqualTo(20.0f)
    }

    @Test
    fun applyInBodyCalibration_ignoresHistoricalMeasurementsPriorToCalibrationDate() = runBlocking {
        val calibration = InBodyCalibration(
            userId = userId,
            scaleMeasurementTimestamp = 10_000L, // calibration date
            inBodyWeightKg = 80.0f,
            offsetFfmKg = 3.0f
        )
        repo.insertOrUpdateInBodyCalibration(calibration)

        // Historical measurement at timestamp 5_000L (before calibration)
        val measurement = Measurement(id = 4, userId = userId, timestamp = 5_000L)
        val incomingValues = listOf(
            MeasurementValue(typeId = weightType!!.id, floatValue = 80.0f, measurementId = 4),
            MeasurementValue(typeId = impedanceType!!.id, floatValue = 500.0f, measurementId = 4),
            MeasurementValue(typeId = lbmType!!.id, floatValue = 64.0f, measurementId = 4),
            MeasurementValue(typeId = fatType!!.id, floatValue = 20.0f, measurementId = 4)
        )

        val result = transformation.applyInBodyCalibration(measurement, incomingValues)
        // Historical values strictly preserved!
        assertThat(result.find { it.typeId == lbmType!!.id }?.floatValue).isEqualTo(64.0f)
        assertThat(result.find { it.typeId == fatType!!.id }?.floatValue).isEqualTo(20.0f)
    }

    @Test
    fun applyInBodyCalibration_respectsDisabledToggleInSettings() = runBlocking {
        val calibration = InBodyCalibration(
            userId = userId,
            scaleMeasurementTimestamp = 10_000L,
            inBodyWeightKg = 80.0f,
            offsetFfmKg = 3.0f
        )
        repo.insertOrUpdateInBodyCalibration(calibration)

        // Disable calibration toggle in settings
        settings.setInBodyCalibrationEnabled(false)

        val measurement = Measurement(id = 5, userId = userId, timestamp = 20_000L)
        val incomingValues = listOf(
            MeasurementValue(typeId = weightType!!.id, floatValue = 80.0f, measurementId = 5),
            MeasurementValue(typeId = impedanceType!!.id, floatValue = 500.0f, measurementId = 5),
            MeasurementValue(typeId = lbmType!!.id, floatValue = 64.0f, measurementId = 5)
        )

        val result = transformation.applyInBodyCalibration(measurement, incomingValues)
        // Disabled -> values untouched
        assertThat(result.find { it.typeId == lbmType!!.id }?.floatValue).isEqualTo(64.0f)
    }

    @Test
    fun recalibratingOnSameBaseline_reversesExistingOffsets_andDoesNotZeroOutOrDoubleOffset() = runBlocking {
        // 1. Create baseline measurement with scale values (uncalibrated LBM = 64 kg, Fat = 20%)
        val mId = repo.insertMeasurement(Measurement(userId = userId, timestamp = 10_000L)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = weightType!!.id, floatValue = 80.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = impedanceType!!.id, floatValue = 500.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = lbmType!!.id, floatValue = 64.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = fatType!!.id, floatValue = 20.0f))

        val baseline1 = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mId }
        val input = InBodyFormInput(
            inBodyWeightKg = 80.0f,
            inBodyFatKg = 13.0f // FFM = 80 - 13 = 67.0 kg. Offset should be 67 - 64 = +3.0 kg
        )

        // 2. First calibration
        val cal1 = calibrationUseCases.calculateCalibration(userId, baseline1, input)
        assertThat(cal1.offsetFfmKg!!).isWithin(0.01f).of(3.0f)
        calibrationUseCases.saveCalibration(cal1, baseline1)

        // Baseline measurement in DB is now calibrated
        val baselineAfterCal1 = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mId }
        val calibratedLbm = baselineAfterCal1.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue
        assertThat(calibratedLbm).isWithin(0.01f).of(67.0f)

        // 3. User recalibrates using the SAME baseline measurement
        // Previously, this would read 67.0 kg as scale LBM and compute offset = 67 - 67 = 0.0 kg!
        val cal2 = calibrationUseCases.calculateCalibration(userId, baselineAfterCal1, input)
        assertThat(cal2.offsetFfmKg!!).isWithin(0.01f).of(3.0f)

        // Save recalibration
        calibrationUseCases.saveCalibration(cal2, baselineAfterCal1)

        // Verify baseline measurement is NOT double-offset to 70.0 kg!
        val baselineAfterCal2 = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mId }
        val lbmAfterCal2 = baselineAfterCal2.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue
        assertThat(lbmAfterCal2).isWithin(0.01f).of(67.0f)
    }

    @Test
    fun deleteCalibrationForUser_revertsBaselineMeasurementToUncalibratedValues() = runBlocking {
        // 1. Insert baseline measurement
        val mId = repo.insertMeasurement(Measurement(userId = userId, timestamp = 10_000L)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = weightType!!.id, floatValue = 80.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = impedanceType!!.id, floatValue = 500.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = lbmType!!.id, floatValue = 64.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mId, typeId = fatType!!.id, floatValue = 20.0f))

        val baseline = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mId }
        val input = InBodyFormInput(
            inBodyWeightKg = 80.0f,
            inBodyFatKg = 13.0f // FFM = 67.0 kg
        )
        val cal = calibrationUseCases.calculateCalibration(userId, baseline, input)
        calibrationUseCases.saveCalibration(cal, baseline)

        // Verify baseline has calibrated values
        val calibratedBaseline = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mId }
        assertThat(calibratedBaseline.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue)
            .isWithin(0.01f).of(67.0f)

        // 2. Delete calibration
        calibrationUseCases.deleteCalibrationForUser(userId)

        // 3. Verify baseline measurement is reverted to its original uncalibrated values
        val revertedBaseline = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mId }
        assertThat(revertedBaseline.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue)
            .isWithin(0.01f).of(64.0f)
        assertThat(revertedBaseline.values.find { it.type.key == MeasurementType.BODY_FAT }?.value?.floatValue)
            .isWithin(0.01f).of(20.0f)
    }

    @Test
    fun calculateCalibration_preservesFullFloatPrecisionWithoutEarlyRounding() = runBlocking {
        val baseline = MeasurementWithValues(
            measurement = Measurement(id = 1, userId = userId, timestamp = 10_000L),
            values = listOf(
                MeasurementValueWithType(
                    value = MeasurementValue(measurementId = 1, typeId = weightType!!.id, floatValue = 81.35f),
                    type = weightType!!
                ),
                MeasurementValueWithType(
                    value = MeasurementValue(measurementId = 1, typeId = impedanceType!!.id, floatValue = 490.0f),
                    type = impedanceType!!
                ),
                MeasurementValueWithType(
                    value = MeasurementValue(measurementId = 1, typeId = lbmType!!.id, floatValue = 63.27f),
                    type = lbmType!!
                )
            )
        )

        val input = InBodyFormInput(
            inBodyWeightKg = 82.55f,
            inBodyFatKg = 15.35f // FFM = 67.20 kg
        )

        val cal = calibrationUseCases.calculateCalibration(userId, baseline, input)
        // Ratio = 81.35 / 82.55 = ~0.98546335
        // Normalized FFM = 67.20 * ratio = ~66.22314
        // Offset = 66.22314 - 63.27 = ~2.95314f
        // Must preserve full float precision (not rounded to 1 or 2 decimals)
        assertThat(cal.offsetFfmKg).isNotNull()
        assertThat(cal.offsetFfmKg).isWithin(0.0001f).of(2.9531376f)
    }

    @Test
    fun deleteCalibrationForUser_revertsAllForwardMeasurements() = runBlocking {
        // Measurement at t = 5_000 (past)
        val mPastId = repo.insertMeasurement(Measurement(userId = userId, timestamp = 5_000L)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = mPastId, typeId = weightType!!.id, floatValue = 80.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mPastId, typeId = impedanceType!!.id, floatValue = 500.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mPastId, typeId = lbmType!!.id, floatValue = 64.0f))

        // Baseline at t = 10_000
        val mBaseId = repo.insertMeasurement(Measurement(userId = userId, timestamp = 10_000L)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = mBaseId, typeId = weightType!!.id, floatValue = 80.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mBaseId, typeId = impedanceType!!.id, floatValue = 500.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mBaseId, typeId = lbmType!!.id, floatValue = 64.0f))

        // Future measurement at t = 15_000
        val mFutureId = repo.insertMeasurement(Measurement(userId = userId, timestamp = 15_000L)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = mFutureId, typeId = weightType!!.id, floatValue = 80.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mFutureId, typeId = impedanceType!!.id, floatValue = 500.0f))
        repo.insertMeasurementValue(MeasurementValue(measurementId = mFutureId, typeId = lbmType!!.id, floatValue = 64.0f))

        val baseline = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mBaseId }
        val input = InBodyFormInput(inBodyWeightKg = 80.0f, inBodyFatKg = 13.0f) // FFM = 67.0 kg
        val cal = calibrationUseCases.calculateCalibration(userId, baseline, input)
        calibrationUseCases.saveCalibration(cal, baseline)

        // Apply calibration to future measurement as well
        val futureMwv = repo.getMeasurementsWithValuesForUser(userId).first().first { it.measurement.id == mFutureId }
        val calFutureValues = transformation.applyInBodyCalibration(futureMwv.measurement, futureMwv.values.map { it.value }, cal)
        calFutureValues.forEach { repo.updateMeasurementValue(it) }

        // Verify both baseline and future are calibrated to 67.0 kg
        val allAfterCal = repo.getMeasurementsWithValuesForUser(userId).first()
        assertThat(allAfterCal.first { it.measurement.id == mBaseId }.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue)
            .isWithin(0.01f).of(67.0f)
        assertThat(allAfterCal.first { it.measurement.id == mFutureId }.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue)
            .isWithin(0.01f).of(67.0f)

        // Delete calibration
        calibrationUseCases.deleteCalibrationForUser(userId)

        // Verify both baseline and future are reverted to uncalibrated 64.0 kg, past was never touched
        val allReverted = repo.getMeasurementsWithValuesForUser(userId).first()
        assertThat(allReverted.first { it.measurement.id == mPastId }.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue)
            .isWithin(0.01f).of(64.0f)
        assertThat(allReverted.first { it.measurement.id == mBaseId }.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue)
            .isWithin(0.01f).of(64.0f)
        assertThat(allReverted.first { it.measurement.id == mFutureId }.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue)
            .isWithin(0.01f).of(64.0f)
    }

    @Test
    fun dampedHybridScaling_exactAtDayZero_scalesWithMuscleGain_andRespectsClampLimits() = runBlocking {
        // Calibration setup:
        // W0 = 80.0 kg, InBody W = 80.0 kg, inBodyFatPercent = 16.25% (FFM = 67.0 kg)
        // Raw scale FFM at baseline = 64.0 kg -> offsetFfmKg = +3.0 kg
        // Raw scale Muscle at baseline = 32.0 kg, InBody Muscle = 35.0 kg -> offsetMuscleKg = +3.0 kg
        val cal = InBodyCalibration(
            userId = userId,
            scaleMeasurementTimestamp = 10_000L,
            scaleName = "Test Scale",
            scaleBaselineWeightKg = 80.0f,
            inBodyWeightKg = 80.0f,
            inBodyFatPercent = 16.25f,
            inBodyMuscleKg = 35.0f,
            offsetFfmKg = 3.0f,
            offsetMuscleKg = 3.0f
        )

        // Case A: Day 0 weigh-in (raw FFM = 64.0 kg, raw Muscle = 32.0 kg)
        val mDay0 = Measurement(id = 1, userId = userId, timestamp = 10_000L)
        val valuesDay0 = listOf(
            MeasurementValue(measurementId = 1, typeId = weightType!!.id, floatValue = 80.0f),
            MeasurementValue(measurementId = 1, typeId = impedanceType!!.id, floatValue = 500.0f),
            MeasurementValue(measurementId = 1, typeId = lbmType!!.id, floatValue = 64.0f),
            MeasurementValue(measurementId = 1, typeId = muscleType!!.id, floatValue = 40.0f) // 40% of 80 kg = 32 kg
        )
        val calDay0 = transformation.applyInBodyCalibration(mDay0, valuesDay0, cal)
        val lbmDay0 = calDay0.find { it.typeId == lbmType!!.id }?.floatValue
        val muscleDay0 = calDay0.find { it.typeId == muscleType!!.id }?.floatValue
        // S_FFM = 1.0 -> 64.0 + 3.0 = 67.0 kg
        assertThat(lbmDay0).isWithin(0.01f).of(67.0f)
        // S_Muscle = 1.0 -> 32.0 + 3.0 = 35.0 kg -> (35.0 / 80.0) * 100 = 43.75%
        assertThat(muscleDay0).isWithin(0.01f).of(43.75f)

        // Reversion test at Day 0:
        val revDay0 = transformation.revertInBodyCalibration(mDay0, calDay0, cal)
        assertThat(revDay0.find { it.typeId == lbmType!!.id }?.floatValue).isWithin(0.01f).of(64.0f)
        assertThat(revDay0.find { it.typeId == muscleType!!.id }?.floatValue).isWithin(0.01f).of(40.0f)

        // Case B: Muscle gain weigh-in (raw FFM = 70.0 kg, W = 86.0 kg)
        // rawMass0 = 64.0 kg. S_FFM = 0.5 + 0.5 * (70.0 / 64.0) = 1.046875
        // Scaled offset = 3.0 * 1.046875 = 3.140625 kg -> Calibrated FFM = 70.0 + 3.140625 = 73.140625 kg (~73.14)
        val mGain = Measurement(id = 2, userId = userId, timestamp = 20_000L)
        val valuesGain = listOf(
            MeasurementValue(measurementId = 2, typeId = weightType!!.id, floatValue = 86.0f),
            MeasurementValue(measurementId = 2, typeId = impedanceType!!.id, floatValue = 480.0f),
            MeasurementValue(measurementId = 2, typeId = lbmType!!.id, floatValue = 70.0f)
        )
        val calGain = transformation.applyInBodyCalibration(mGain, valuesGain, cal)
        val lbmGain = calGain.find { it.typeId == lbmType!!.id }?.floatValue
        assertThat(lbmGain).isWithin(0.01f).of(73.14f)

        // Reversion test for muscle gain:
        val revGain = transformation.revertInBodyCalibration(mGain, calGain, cal)
        assertThat(revGain.find { it.typeId == lbmType!!.id }?.floatValue).isWithin(0.05f).of(70.0f)

        // Case C: Clamped at upper limit (1.30)
        // e.g. raw FFM = 120.0 kg -> ratio = 120 / 64 = 1.875 -> damped = 0.5 + 0.5*1.875 = 1.4375 -> clamped to 1.30
        // Scaled offset = 3.0 * 1.30 = 3.90 kg -> Calibrated FFM = 120.0 + 3.90 = 123.90 kg
        val mUpper = Measurement(id = 3, userId = userId, timestamp = 30_000L)
        val valuesUpper = listOf(
            MeasurementValue(measurementId = 3, typeId = weightType!!.id, floatValue = 130.0f),
            MeasurementValue(measurementId = 3, typeId = impedanceType!!.id, floatValue = 450.0f),
            MeasurementValue(measurementId = 3, typeId = lbmType!!.id, floatValue = 120.0f)
        )
        val calUpper = transformation.applyInBodyCalibration(mUpper, valuesUpper, cal)
        val lbmUpper = calUpper.find { it.typeId == lbmType!!.id }?.floatValue
        assertThat(lbmUpper).isWithin(0.01f).of(123.90f)

        // Case D: Clamped at lower limit (0.70)
        // e.g. raw FFM = 20.0 kg -> ratio = 20 / 64 = 0.3125 -> damped = 0.5 + 0.5*0.3125 = 0.65625 -> clamped to 0.70
        // Scaled offset = 3.0 * 0.70 = 2.10 kg -> Calibrated FFM = 20.0 + 2.10 = 22.10 kg
        val mLower = Measurement(id = 4, userId = userId, timestamp = 40_000L)
        val valuesLower = listOf(
            MeasurementValue(measurementId = 4, typeId = weightType!!.id, floatValue = 40.0f),
            MeasurementValue(measurementId = 4, typeId = impedanceType!!.id, floatValue = 550.0f),
            MeasurementValue(measurementId = 4, typeId = lbmType!!.id, floatValue = 20.0f)
        )
        val calLower = transformation.applyInBodyCalibration(mLower, valuesLower, cal)
        val lbmLower = calLower.find { it.typeId == lbmType!!.id }?.floatValue
        assertThat(lbmLower).isWithin(0.01f).of(22.10f)
    }

    @Test
    fun fatMassDrivenVisceralFat_neutralizesFalseVFIncreaseFromMuscleGain() = runBlocking {
        // Setup:
        // W0 = 80.0 kg, inBodyFatPercent = 15.0% (BFM_cal_0 = 12.0 kg, FFM_cal_0 = 68.0 kg)
        // Baseline scale raw FFM = 65.0 kg -> offsetFfmKg = +3.0 kg (calibrated FFM at t0 = 68.0 kg)
        // Baseline scale raw VF = 8.0, InBody VF = 7.0 -> offsetVisceralFat = -1.0
        val cal = InBodyCalibration(
            userId = userId,
            scaleMeasurementTimestamp = 10_000L,
            scaleName = "Test Scale",
            scaleBaselineWeightKg = 80.0f,
            inBodyWeightKg = 80.0f,
            inBodyFatPercent = 15.0f,
            offsetFfmKg = 3.0f,
            offsetVisceralFat = -1.0f
        )

        // Day 0 weigh-in:
        val mDay0 = Measurement(id = 1, userId = userId, timestamp = 10_000L)
        val valuesDay0 = listOf(
            MeasurementValue(measurementId = 1, typeId = weightType!!.id, floatValue = 80.0f),
            MeasurementValue(measurementId = 1, typeId = impedanceType!!.id, floatValue = 500.0f),
            MeasurementValue(measurementId = 1, typeId = lbmType!!.id, floatValue = 65.0f),
            MeasurementValue(measurementId = 1, typeId = vfType!!.id, floatValue = 8.0f)
        )
        val calDay0 = transformation.applyInBodyCalibration(mDay0, valuesDay0, cal)
        // Day 0: deltaBfmActual = 0, deltaBfmAssumed = 0 -> VF = 8.0 - 1.0 = 7.0
        assertThat(calDay0.find { it.typeId == vfType!!.id }?.floatValue).isWithin(0.01f).of(7.0f)

        // Weigh-in after gaining 4.0 kg pure muscle (W_n = 84.0 kg):
        // Raw FFM increases from 65.0 to 68.835 kg such that calibrated FFM becomes 72.0 kg (+4 kg lean mass)
        // Vendor scale formula falsely elevated raw VF by +0.6 (from 8.0 to 8.6) due to the 4 kg weight gain:
        val mGain = Measurement(id = 2, userId = userId, timestamp = 20_000L)
        val valuesGain = listOf(
            MeasurementValue(measurementId = 2, typeId = weightType!!.id, floatValue = 84.0f),
            MeasurementValue(measurementId = 2, typeId = impedanceType!!.id, floatValue = 480.0f),
            MeasurementValue(measurementId = 2, typeId = lbmType!!.id, floatValue = 68.835f),
            MeasurementValue(measurementId = 2, typeId = vfType!!.id, floatValue = 8.6f)
        )
        val calGain = transformation.applyInBodyCalibration(mGain, valuesGain, cal)
        // Calibrated FFM is ~72.0 kg.
        // Current calibrated fat mass BFM_cal_n = 84.0 - 72.0 = 12.0 kg.
        // deltaBfmActual = 12.0 - 12.0 = 0.0 kg.
        // deltaBfmAssumed = 0.75 * (84.0 - 80.0) = 3.0 kg.
        // Composition-aware correction: 0.20 * (0.0 - 3.0) = -0.6 rating points.
        // VF_cal = 8.6 + (-1.0) + (-0.6) = 7.0! The false +0.6 increase is completely neutralized!
        assertThat(calGain.find { it.typeId == vfType!!.id }?.floatValue).isWithin(0.05f).of(7.0f)

        // Verify reversion recovers original uncalibrated VF (8.6):
        val revGain = transformation.revertInBodyCalibration(mGain, calGain, cal)
        assertThat(revGain.find { it.typeId == vfType!!.id }?.floatValue).isWithin(0.05f).of(8.6f)
    }
}

