package com.health.openscale.core.usecase

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacadeImpl
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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
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
        baseContext = InstrumentationRegistry.getInstrumentation().targetContext
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

        repository = DatabaseRepository(
            database = database,
            userDao = database.userDao(),
            userGoalsDao = database.userGoalsDao(),
            measurementDao = database.measurementDao(),
            measurementTypeDao = database.measurementTypeDao(),
            measurementValueDao = database.measurementValueDao()
        )

        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(sandboxRoot, "settings.preferences_pb") }
        )
        val settings = SettingsFacadeImpl(dataStore)
        useCases = BackupRestoreUseCases(sandboxContext, repository, settings)

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
                measurementValueDao = reopened.measurementValueDao()
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
                measurementValueDao = reopened.measurementValueDao()
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
                measurementValueDao = reopened.measurementValueDao()
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
                measurementValueDao = reopened.measurementValueDao()
            )

            val users = reopenedRepo.getAllUsers().first()
            assertEquals(1, users.size)
            assertEquals("restore-test-user", users.single().name)
        } finally {
            reopened.close()
        }
    }

    private fun buildDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(
                com.health.openscale.core.database.MIGRATION_6_7,
                com.health.openscale.core.database.MIGRATION_7_8,
                com.health.openscale.core.database.MIGRATION_8_9,
                com.health.openscale.core.database.MIGRATION_9_10,
                com.health.openscale.core.database.MIGRATION_10_11,
                com.health.openscale.core.database.MIGRATION_11_12,
                com.health.openscale.core.database.MIGRATION_12_13,
                com.health.openscale.core.database.MIGRATION_13_14
            )
            .build()

    private fun createLegacyDatabase(file: File) {
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            database.execSQL(
                """
                    CREATE TABLE scaleUsers (
                        id INTEGER PRIMARY KEY,
                        username TEXT NOT NULL,
                        birthday INTEGER NOT NULL,
                        gender INTEGER NOT NULL,
                        bodyHeight REAL NOT NULL,
                        activityLevel INTEGER NOT NULL
                    )
                """.trimIndent()
            )
            database.execSQL(
                """
                    CREATE TABLE scaleMeasurements (
                        id INTEGER PRIMARY KEY,
                        userId INTEGER NOT NULL,
                        datetime INTEGER,
                        enabled INTEGER NOT NULL,
                        weight REAL,
                        fat REAL,
                        water REAL,
                        muscle REAL,
                        visceralFat REAL,
                        lbm REAL,
                        waist REAL,
                        hip REAL,
                        bone REAL,
                        chest REAL,
                        thigh REAL,
                        biceps REAL,
                        neck REAL,
                        caliper1 REAL,
                        caliper2 REAL,
                        caliper3 REAL,
                        calories REAL,
                        comment TEXT
                    )
                """.trimIndent()
            )
            database.execSQL(
                """
                    INSERT INTO scaleUsers (id, username, birthday, gender, bodyHeight, activityLevel)
                    VALUES (1, 'legacy-user', 946684800000, 1, 168.0, 2)
                """.trimIndent()
            )
            database.execSQL(
                """
                    INSERT INTO scaleMeasurements (id, userId, datetime, enabled, weight, comment)
                    VALUES (1, 1, 1712325600000, 1, 72.5, 'legacy measurement')
                """.trimIndent()
            )
            database.execSQL("PRAGMA user_version = 6")
        } finally {
            database.close()
        }
    }
}
