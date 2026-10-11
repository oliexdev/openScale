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
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
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
import java.util.UUID

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
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        val settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(app.cacheDir, "crud-${System.nanoTime()}.preferences_pb"),
        )
        crud = RoomTestSupport.measurementCrudFor(app, repo, settings)

        val types = repo.getAllMeasurementTypes().first()
        weightId = types.first { it.key == MeasurementType.WEIGHT }.id
        waistId = types.first { it.key == MeasurementType.WAIST }.id
        neckId = types.first { it.key == MeasurementType.NECK }.id

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

    @Test
    fun saveMeasurement_insert_duplicateTimestamp_returnsSentinelWithoutOrphanValues() = runBlocking {
        val firstId = crud.saveMeasurement(
            Measurement(userId = userId, timestamp = 5_000L),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 70f)),
        ).getOrThrow()
        assertThat(firstId).isGreaterThan(0)
        val valuesBefore = repo.getValuesForMeasurement(firstId).first()

        // A second new measurement at the same (userId, timestamp) is a duplicate → -1 sentinel.
        val result = crud.saveMeasurement(
            Measurement(userId = userId, timestamp = 5_000L),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 99f)),
        )

        assertThat(result.getOrThrow()).isEqualTo(-1)
        // Original untouched, and no orphan values (e.g. measurementId = -1) were written.
        assertThat(repo.getValuesForMeasurement(firstId).first()).hasSize(valuesBefore.size)
        assertThat(repo.getValuesForMeasurement(-1).first()).isEmpty()
    }

    @Test
    fun saveMeasurement_editWeight_recalculatesDerivedWithoutChurningIds() = runBlocking {
        val bmiId = repo.getAllMeasurementTypes().first().first { it.key == MeasurementType.BMI }.id

        val id = crud.saveMeasurement(
            Measurement(userId = userId, timestamp = 3_000L),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 70f)),
        ).getOrThrow()

        val bmiBefore = repo.getValuesForMeasurement(id).first().first { it.typeId == bmiId }
        assertThat(bmiBefore.floatValue!!).isWithin(0.1f).of(22.86f) // 70 / 1.75^2

        val weightValueId = repo.getValuesForMeasurement(id).first().first { it.typeId == weightId }.id
        crud.saveMeasurement(
            Measurement(id = id, userId = userId, timestamp = 3_000L),
            listOf(MeasurementValue(id = weightValueId, measurementId = id, typeId = weightId, floatValue = 80f)),
        ).getOrThrow()

        val bmiAfter = repo.getValuesForMeasurement(id).first().first { it.typeId == bmiId }
        assertThat(bmiAfter.floatValue!!).isWithin(0.1f).of(26.12f) // 80 / 1.75^2
        // Derived row updated in place, not deleted+recreated.
        assertThat(bmiAfter.id).isEqualTo(bmiBefore.id)
    }

    @Test
    fun saveMeasurement_editTimestampOntoExisting_returnsSentinelAndLeavesDataUnchanged() = runBlocking {
        val m1 = crud.saveMeasurement(
            Measurement(userId = userId, timestamp = 1_000L),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 60f)),
        ).getOrThrow()
        val m2 = crud.saveMeasurement(
            Measurement(userId = userId, timestamp = 2_000L),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 80f)),
        ).getOrThrow()
        val m2WeightValueId = repo.getValuesForMeasurement(m2).first().first { it.typeId == weightId }.id

        // Move m2 onto m1's timestamp → violates the unique (userId, timestamp) index → -1 sentinel.
        val result = crud.saveMeasurement(
            Measurement(id = m2, userId = userId, timestamp = 1_000L),
            listOf(MeasurementValue(id = m2WeightValueId, measurementId = m2, typeId = weightId, floatValue = 81f)),
        )

        assertThat(result.getOrThrow()).isEqualTo(-1)
        // m2 keeps its timestamp; m1 untouched.
        assertThat(db.measurementDao().getMeasurementById(m2)!!.timestamp).isEqualTo(2_000L)
        assertThat(repo.getValuesForMeasurement(m1).first().first { it.typeId == weightId }.floatValue)
            .isWithin(1e-3f).of(60f)
    }

    // --- Photo files (IMAGE values store only the file name in textValue) ---

    private suspend fun insertPhotoType(): MeasurementType {
        val id = repo.insertMeasurementType(
            MeasurementType(identity = "user.photo_front", name = "Photo front", inputType = InputFieldType.IMAGE)
        ).toInt()
        return repo.getAllMeasurementTypes().first().first { it.id == id }
    }

    private fun createPhoto(): String {
        val name = "${UUID.randomUUID()}.jpg"
        MeasurementCrudUseCases.imageDir(app).mkdirs()
        MeasurementCrudUseCases.imageFile(app, name)!!.writeBytes(byteArrayOf(1, 2, 3))
        return name
    }

    private fun photoExists(name: String) = MeasurementCrudUseCases.imageFile(app, name)!!.exists()

    private suspend fun saveWithPhoto(photoTypeId: Int, photo: String, timestamp: Long = 5_000L): Int =
        crud.saveMeasurement(
            Measurement(userId = userId, timestamp = timestamp),
            listOf(MeasurementValue(measurementId = 0, typeId = photoTypeId, textValue = photo)),
        ).getOrThrow()

    @Test
    fun saveMeasurement_replacedPhoto_deletesOldFile() = runBlocking {
        val photoType = insertPhotoType()
        val oldPhoto = createPhoto()
        val id = saveWithPhoto(photoType.id, oldPhoto)
        val valueId = repo.getValuesForMeasurement(id).first().first { it.typeId == photoType.id }.id

        val newPhoto = createPhoto()
        crud.saveMeasurement(
            Measurement(id = id, userId = userId, timestamp = 5_000L),
            listOf(MeasurementValue(id = valueId, measurementId = id, typeId = photoType.id, textValue = newPhoto)),
        ).getOrThrow()

        assertThat(photoExists(oldPhoto)).isFalse()
        assertThat(photoExists(newPhoto)).isTrue()
    }

    @Test
    fun saveMeasurement_removedPhoto_deletesFile() = runBlocking {
        val photoType = insertPhotoType()
        val photo = createPhoto()
        val id = saveWithPhoto(photoType.id, photo)

        crud.saveMeasurement(
            Measurement(id = id, userId = userId, timestamp = 5_000L),
            listOf(MeasurementValue(measurementId = id, typeId = weightId, floatValue = 70f)),
        ).getOrThrow()

        assertThat(photoExists(photo)).isFalse()
    }

    @Test
    fun deleteMeasurement_deletesPhotoFile() = runBlocking {
        val photoType = insertPhotoType()
        val photo = createPhoto()
        val id = saveWithPhoto(photoType.id, photo)

        crud.deleteMeasurement(db.measurementDao().getMeasurementById(id)!!).getOrThrow()

        assertThat(photoExists(photo)).isFalse()
    }

    @Test
    fun deleteUserAndPurge_deletePhotoFiles() = runBlocking {
        val photoType = insertPhotoType()
        val settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(app.cacheDir, "crud-user-${System.nanoTime()}.preferences_pb"),
        )
        val userUseCases = UserUseCases(
            app, repo, settings, SyncUseCases(app, MeasurementTypeCrudUseCases(repo, app))
        )
        val purged = createPhoto()
        saveWithPhoto(photoType.id, purged, timestamp = 6_000L)
        userUseCases.purgeMeasurementsForUser(userId).getOrThrow()
        assertThat(photoExists(purged)).isFalse()

        val deleted = createPhoto()
        saveWithPhoto(photoType.id, deleted, timestamp = 7_000L)
        userUseCases.deleteUser(repo.getAllUsers().first().first { it.id == userId }).getOrThrow()
        assertThat(photoExists(deleted)).isFalse()
    }

    @Test
    fun deleteType_deletesPhotoFiles() = runBlocking {
        val photoType = insertPhotoType()
        val photo = createPhoto()
        saveWithPhoto(photoType.id, photo)

        MeasurementTypeCrudUseCases(repo, app).delete(photoType).getOrThrow()

        assertThat(photoExists(photo)).isFalse()
    }

    @Test
    fun imageFile_rejectsNamesOutsideTheImageDirectory() {
        assertThat(MeasurementCrudUseCases.imageFile(app, "../openScale.db")).isNull()
        assertThat(MeasurementCrudUseCases.imageFile(app, "notes.txt")).isNull()
        assertThat(MeasurementCrudUseCases.imageFile(app, null)).isNull()
    }
}
