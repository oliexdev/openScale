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
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.core.service.DerivedValuesCalculator
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
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreUseCasesTest {
    private lateinit var baseContext: Context
    private lateinit var sandboxRoot: File
    private lateinit var sandboxContext: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: DatabaseRepository
    private lateinit var useCases: BackupRestoreUseCases
    private lateinit var dbFile: File

    @Before
    fun setUp() = runBlocking {
        baseContext = ApplicationProvider.getApplicationContext()
        sandboxRoot = File(baseContext.cacheDir, "backup-restore-test-${System.nanoTime()}").apply {
            mkdirs()
        }

        sandboxContext = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getDatabasePath(name: String): File {
                return File(sandboxRoot, name).also { file ->
                    file.parentFile?.mkdirs()
                }
            }
        }

        database = buildDatabase(sandboxContext)

        val derivedValuesCalculator = DerivedValuesCalculator(
            userDao = database.userDao(),
            measurementDao = database.measurementDao(),
            measurementTypeDao = database.measurementTypeDao(),
            measurementValueDao = database.measurementValueDao()
        )

        repository = DatabaseRepository(
            database = database,
            userDao = database.userDao(),
            userGoalsDao = database.userGoalsDao(),
            measurementDao = database.measurementDao(),
            measurementTypeDao = database.measurementTypeDao(),
            measurementValueDao = database.measurementValueDao(),
            derivedValuesCalculator = derivedValuesCalculator
        )

        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(sandboxRoot, "settings.preferences_pb") }
        )
        val settings = SettingsFacadeImpl(dataStore)
        val sync = SyncUseCases(baseContext as Application, MeasurementTypeCrudUseCases(repository, ApplicationProvider.getApplicationContext()))
        useCases = BackupRestoreUseCases(sandboxContext, repository, settings, sync)

        repository.insertUser(
            User(
                name = "restore-test-user",
                birthDate = 946684800000L,
                gender = GenderType.FEMALE,
                heightCm = 170f,
                activityLevel = ActivityLevel.MODERATE,
                useAssistedWeighing = false
            )
        )

        dbFile = sandboxContext.getDatabasePath(AppDatabase.DATABASE_NAME)
        assertWithMessage("expected seeded test database to exist").that(dbFile.exists()).isTrue()
        assertThat(repository.getAllUsers().first()).hasSize(1)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        sandboxRoot.deleteRecursively()
    }

    @Test
    fun restoreDatabase_withZipMissingMainDb_keepsExistingData() = runBlocking {
        val invalidZip = File(sandboxRoot, "invalid-backup.zip")
        ZipOutputStream(FileOutputStream(invalidZip)).use { zip ->
            zip.putNextEntry(ZipEntry("not-the-database.txt"))
            zip.write("wrong backup payload".toByteArray())
            zip.closeEntry()
        }

        val result = useCases.restoreDatabase(Uri.fromFile(invalidZip), baseContext.contentResolver)

        assertWithMessage("restore should fail for zip without openScale.db")
            .that(result.isFailure).isTrue()
        assertWithMessage("failed restore should leave the live database file in place")
            .that(dbFile.exists()).isTrue()
        assertWithMessage("failed restore should not mutate live in-memory data")
            .that(repository.getAllUsers().first()).hasSize(1)

        val reopened = buildDatabase(sandboxContext)

        try {
            val reopenedRepo = DatabaseRepository(
                database = reopened,
                userDao = reopened.userDao(),
                userGoalsDao = reopened.userGoalsDao(),
                measurementDao = reopened.measurementDao(),
                measurementTypeDao = reopened.measurementTypeDao(),
                measurementValueDao = reopened.measurementValueDao(),
                derivedValuesCalculator = DerivedValuesCalculator(
                    userDao = reopened.userDao(),
                    measurementDao = reopened.measurementDao(),
                    measurementTypeDao = reopened.measurementTypeDao(),
                    measurementValueDao = reopened.measurementValueDao()
                )
            )

            assertWithMessage("the original record should still exist after a failed restore")
                .that(reopenedRepo.getAllUsers().first()).hasSize(1)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun restoreDatabase_withUnrelatedSqliteFile_keepsExistingData() = runBlocking {
        val unrelatedDb = File(sandboxRoot, "unrelated.db")
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(unrelatedDb, null)
        try {
            sqliteDb.execSQL("CREATE TABLE unrelated_data (id INTEGER PRIMARY KEY, value TEXT)")
            sqliteDb.execSQL("INSERT INTO unrelated_data(value) VALUES ('not openscale')")
        } finally {
            sqliteDb.close()
        }

        val result = useCases.restoreDatabase(Uri.fromFile(unrelatedDb), baseContext.contentResolver)

        assertWithMessage("restore should fail for unrelated SQLite databases")
            .that(result.isFailure).isTrue()
        assertWithMessage("failed restore should leave the live database file in place")
            .that(dbFile.exists()).isTrue()
        assertWithMessage("failed restore should not mutate live in-memory data")
            .that(repository.getAllUsers().first()).hasSize(1)

        val reopened = buildDatabase(sandboxContext)
        try {
            val reopenedRepo = DatabaseRepository(
                database = reopened,
                userDao = reopened.userDao(),
                userGoalsDao = reopened.userGoalsDao(),
                measurementDao = reopened.measurementDao(),
                measurementTypeDao = reopened.measurementTypeDao(),
                measurementValueDao = reopened.measurementValueDao(),
                derivedValuesCalculator = DerivedValuesCalculator(
                    userDao = reopened.userDao(),
                    measurementDao = reopened.measurementDao(),
                    measurementTypeDao = reopened.measurementTypeDao(),
                    measurementValueDao = reopened.measurementValueDao()
                )
            )

            assertWithMessage("the original record should still exist after rejecting an unrelated database")
                .that(reopenedRepo.getAllUsers().first()).hasSize(1)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun restoreDatabase_withLegacySingleFile_restoresAndMigrates() = runBlocking {
        val legacyDb = File(sandboxRoot, "legacy-openscale.db")
        createLegacyDatabase(legacyDb)

        val result = useCases.restoreDatabase(Uri.fromFile(legacyDb), baseContext.contentResolver)
        assertWithMessage("restore should accept legacy openScale single-file databases")
            .that(result.isSuccess).isTrue()

        val reopened = buildDatabase(sandboxContext)
        try {
            val reopenedRepo = DatabaseRepository(
                database = reopened,
                userDao = reopened.userDao(),
                userGoalsDao = reopened.userGoalsDao(),
                measurementDao = reopened.measurementDao(),
                measurementTypeDao = reopened.measurementTypeDao(),
                measurementValueDao = reopened.measurementValueDao(),
                derivedValuesCalculator = DerivedValuesCalculator(
                    userDao = reopened.userDao(),
                    measurementDao = reopened.measurementDao(),
                    measurementTypeDao = reopened.measurementTypeDao(),
                    measurementValueDao = reopened.measurementValueDao()
                )
            )

            val users = reopenedRepo.getAllUsers().first()
            assertThat(users).hasSize(1)
            assertThat(users.single().name).isEqualTo("legacy-user")
        } finally {
            reopened.close()
        }
    }

    @Test
    fun restoreDatabase_withValidBackupZip_restoresPreviousSnapshot() = runBlocking {
        val backupZip = File(sandboxRoot, "valid-backup.zip")
        useCases.backupDatabase(Uri.fromFile(backupZip), baseContext.contentResolver).getOrThrow()

        repository.insertUser(
            User(
                name = "post-backup-user",
                birthDate = 978307200000L,
                gender = GenderType.MALE,
                heightCm = 180f,
                activityLevel = ActivityLevel.MILD,
                useAssistedWeighing = false
            )
        )
        assertThat(repository.getAllUsers().first()).hasSize(2)

        val result = useCases.restoreDatabase(Uri.fromFile(backupZip), baseContext.contentResolver)
        assertWithMessage("restore from app-generated backup should succeed")
            .that(result.isSuccess).isTrue()

        val reopened = buildDatabase(sandboxContext)
        try {
            val reopenedRepo = DatabaseRepository(
                database = reopened,
                userDao = reopened.userDao(),
                userGoalsDao = reopened.userGoalsDao(),
                measurementDao = reopened.measurementDao(),
                measurementTypeDao = reopened.measurementTypeDao(),
                measurementValueDao = reopened.measurementValueDao(),
                derivedValuesCalculator = DerivedValuesCalculator(
                    userDao = reopened.userDao(),
                    measurementDao = reopened.measurementDao(),
                    measurementTypeDao = reopened.measurementTypeDao(),
                    measurementValueDao = reopened.measurementValueDao()
                )
            )

            val users = reopenedRepo.getAllUsers().first()
            assertThat(users).hasSize(1)
            assertThat(users.single().name).isEqualTo("restore-test-user")
        } finally {
            reopened.close()
        }
    }

    private fun buildDatabase(context: Context): AppDatabase = RoomTestSupport.onDisk(context)

    private fun createLegacyDatabase(file: File) = RoomTestSupport.writeLegacyV6Database(file)
}
