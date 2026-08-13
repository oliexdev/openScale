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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val sync = SyncUseCases(baseContext as Application, MeasurementTypeCrudUseCases(repository))
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
        assertTrue("expected seeded test database to exist", dbFile.exists())
        assertEquals(1, repository.getAllUsers().first().size)
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

        assertTrue("restore should fail for zip without openScale.db", result.isFailure)
        assertTrue("failed restore should leave the live database file in place", dbFile.exists())

        assertEquals("failed restore should not mutate live in-memory data", 1, repository.getAllUsers().first().size)

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

            assertEquals(
                "the original record should still exist after a failed restore",
                1,
                reopenedRepo.getAllUsers().first().size
            )
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

        assertTrue("restore should fail for unrelated SQLite databases", result.isFailure)
        assertTrue("failed restore should leave the live database file in place", dbFile.exists())
        assertEquals("failed restore should not mutate live in-memory data", 1, repository.getAllUsers().first().size)

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

            assertEquals(
                "the original record should still exist after rejecting an unrelated database",
                1,
                reopenedRepo.getAllUsers().first().size
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun restoreDatabase_withLegacySingleFile_restoresAndMigrates() = runBlocking {
        val legacyDb = File(sandboxRoot, "legacy-openscale.db")
        createLegacyDatabase(legacyDb)

        val result = useCases.restoreDatabase(Uri.fromFile(legacyDb), baseContext.contentResolver)
        assertTrue("restore should accept legacy openScale single-file databases", result.isSuccess)

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
            assertEquals(1, users.size)
            assertEquals("legacy-user", users.single().name)
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
        assertEquals(2, repository.getAllUsers().first().size)

        val result = useCases.restoreDatabase(Uri.fromFile(backupZip), baseContext.contentResolver)
        assertTrue("restore from app-generated backup should succeed", result.isSuccess)

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
            assertEquals(1, users.size)
            assertEquals("restore-test-user", users.single().name)
        } finally {
            reopened.close()
        }
    }

    private fun buildDatabase(context: Context): AppDatabase = RoomTestSupport.onDisk(context)

    private fun createLegacyDatabase(file: File) = RoomTestSupport.writeLegacyV6Database(file)
}
