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
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.InputFieldType
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * CSV export/import round-trip on the JVM (Robolectric), exercising the real ContentResolver
 * file IO path. Covers the data-portability contract and duplicate-timestamp handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportExportUseCasesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var useCases: ImportExportUseCases
    private var userId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(context)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(getDefaultMeasurementTypes())
        val sync = SyncUseCases(context as Application, MeasurementTypeCrudUseCases(repo))
        useCases = ImportExportUseCases(repo, sync)

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

        val weightId = repo.getAllMeasurementTypes().first()
            .first { it.key == MeasurementTypeKey.WEIGHT }.id
        listOf(1_000L to 70f, 2_000L to 71f).forEach { (ts, w) ->
            val mId = db.measurementDao().insert(Measurement(userId = userId, timestamp = ts)).toInt()
            db.measurementValueDao().insert(
                MeasurementValue(measurementId = mId, typeId = weightId, floatValue = w)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun csvRoundTrip_exportThenImport_restoresMeasurements() = runBlocking {
        val file = File(context.cacheDir, "export-${System.nanoTime()}.csv")
        val cr = context.contentResolver

        val exported = useCases.exportUserToCsv(userId, Uri.fromFile(file), cr).getOrThrow()
        assertThat(exported).isEqualTo(2)

        repo.deleteAllMeasurementsForUser(userId)
        assertThat(repo.getMeasurementsWithValuesForUser(userId).first()).isEmpty()

        val report = useCases.importUserFromCsv(userId, Uri.fromFile(file), cr).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(2)
        assertThat(repo.getMeasurementsWithValuesForUser(userId).first()).hasSize(2)
    }

    @Test
    fun csvImport_duplicateTimestamps_areIgnored() = runBlocking {
        val file = File(context.cacheDir, "dup-${System.nanoTime()}.csv")
        val cr = context.contentResolver
        useCases.exportUserToCsv(userId, Uri.fromFile(file), cr).getOrThrow()

        // The two measurements still exist, so re-importing the same file ignores them as duplicates.
        val report = useCases.importUserFromCsv(userId, Uri.fromFile(file), cr).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(0)
        assertThat(report.ignoredMeasurementsCount).isEqualTo(2)
    }

    // ---- edge cases -----------------------------------------------------------------------------

    private fun csvUri(content: String): Uri {
        val file = File(context.cacheDir, "csv-${System.nanoTime()}.csv")
        file.writeText(content)
        return Uri.fromFile(file)
    }

    @Test
    fun import_separateDateAndTimeColumns_isParsed() = runBlocking {
        val report = useCases.importUserFromCsv(
            userId, csvUri("DATE,TIME,WEIGHT\n2025-04-07,08:30,72.5\n"), context.contentResolver
        ).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(1)
        assertThat(report.linesSkippedMissingDate).isEqualTo(0)
        assertThat(report.linesSkippedDateParseError).isEqualTo(0)
    }

    @Test
    fun import_combinedDateTimeColumn_isParsed() = runBlocking {
        val report = useCases.importUserFromCsv(
            userId, csvUri("dateTime,WEIGHT\n2025-04-07 08:30,72.5\n"), context.contentResolver
        ).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(1)
    }

    @Test
    fun import_missingTime_defaultsToNoon() = runBlocking {
        val report = useCases.importUserFromCsv(
            userId, csvUri("DATE,WEIGHT\n2025-04-08,72.5\n"), context.contentResolver
        ).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(1)

        val imported = repo.getMeasurementsWithValuesForUser(userId).first()
            .map { it.measurement.timestamp }
            .map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
            .first { it.toLocalDate() == LocalDate.of(2025, 4, 8) }
        assertThat(imported.hour).isEqualTo(12)
        assertThat(imported.minute).isEqualTo(0)
    }

    @Test
    fun import_blankDate_isSkippedAsMissingDate() = runBlocking {
        val report = useCases.importUserFromCsv(
            userId, csvUri("DATE,WEIGHT\n,72.5\n"), context.contentResolver
        ).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(0)
        assertThat(report.linesSkippedMissingDate).isEqualTo(1)
    }

    @Test
    fun import_unparseableDate_isSkippedAsParseError() = runBlocking {
        val report = useCases.importUserFromCsv(
            userId, csvUri("DATE,WEIGHT\nnot-a-date,72.5\n"), context.contentResolver
        ).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(0)
        assertThat(report.linesSkippedDateParseError).isEqualTo(1)
    }

    @Test
    fun import_zeroValue_isFilteredSoNoMeasurementCreated() = runBlocking {
        val report = useCases.importUserFromCsv(
            userId, csvUri("DATE,WEIGHT\n2025-04-09,0\n"), context.contentResolver
        ).getOrThrow()
        // the only value is 0 and gets filtered -> the row has no values -> no measurement
        assertThat(report.importedMeasurementsCount).isEqualTo(0)
    }

    @Test
    fun import_internalImpedanceColumn_isImportedDespiteDisabledType() = runBlocking {
        val impedanceType = repo.getAllMeasurementTypes().first()
            .first { it.key == MeasurementTypeKey.IMPEDANCE }
        // Impedance is a raw input: internal and disabled by default, yet still importable.
        assertThat(impedanceType.isInternal).isTrue()
        assertThat(impedanceType.isEnabled).isFalse()

        val report = useCases.importUserFromCsv(
            userId, csvUri("DATE,IMPEDANCE\n2025-04-11,480\n"), context.contentResolver
        ).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(1)

        val value = repo.getMeasurementsWithValuesForUser(userId).first()
            .flatMap { it.values }
            .firstOrNull { it.value.typeId == impedanceType.id }
        assertThat(value).isNotNull()
        assertThat(value!!.value.floatValue).isWithin(1e-3f).of(480f)
    }

    @Test
    fun csvRoundTrip_internalImpedance_isExportedAndReimported() = runBlocking {
        val types = repo.getAllMeasurementTypes().first()
        val weightId = types.first { it.key == MeasurementTypeKey.WEIGHT }.id
        val impedanceId = types.first { it.key == MeasurementTypeKey.IMPEDANCE }.id

        // Add a measurement carrying a raw impedance value.
        val mId = db.measurementDao().insert(Measurement(userId = userId, timestamp = 3_000L)).toInt()
        db.measurementValueDao().insert(
            MeasurementValue(measurementId = mId, typeId = weightId, floatValue = 80f)
        )
        db.measurementValueDao().insert(
            MeasurementValue(measurementId = mId, typeId = impedanceId, floatValue = 500f)
        )

        val file = File(context.cacheDir, "imp-${System.nanoTime()}.csv")
        val cr = context.contentResolver

        useCases.exportUserToCsv(userId, Uri.fromFile(file), cr).getOrThrow()

        // The internal impedance column and value must be present in the CSV.
        val csv = file.readText()
        assertThat(csv).contains(MeasurementTypeKey.IMPEDANCE.name)
        assertThat(csv).contains("500.0")

        repo.deleteAllMeasurementsForUser(userId)

        useCases.importUserFromCsv(userId, Uri.fromFile(file), cr).getOrThrow()

        // The raw impedance value survives the round-trip despite the type being disabled.
        val reimported = repo.getMeasurementsWithValuesForUser(userId).first()
            .flatMap { it.values }
            .firstOrNull { it.value.typeId == impedanceId }
        assertThat(reimported).isNotNull()
        assertThat(reimported!!.value.floatValue).isWithin(1e-3f).of(500f)
    }

    @Test
    fun import_customTypeMappedByName() = runBlocking {
        val customTypeId = db.measurementTypeDao().insert(
            MeasurementType(
                key = MeasurementTypeKey.CUSTOM,
                name = "MyMetric",
                inputType = InputFieldType.FLOAT,
                unit = UnitType.NONE,
                isEnabled = true,
            )
        ).toInt()

        val report = useCases.importUserFromCsv(
            userId, csvUri("DATE,MyMetric\n2025-04-10,3.5\n"), context.contentResolver
        ).getOrThrow()
        assertThat(report.importedMeasurementsCount).isEqualTo(1)

        val value = repo.getMeasurementsWithValuesForUser(userId).first()
            .flatMap { it.values }
            .firstOrNull { it.value.typeId == customTypeId }
        assertThat(value).isNotNull()
        assertThat(value!!.value.floatValue).isWithin(1e-3f).of(3.5f)
    }
}
