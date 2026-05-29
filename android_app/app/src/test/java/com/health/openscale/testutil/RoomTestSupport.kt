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
package com.health.openscale.testutil

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.database.MIGRATION_10_11
import com.health.openscale.core.database.MIGRATION_11_12
import com.health.openscale.core.database.MIGRATION_12_13
import com.health.openscale.core.database.MIGRATION_13_14
import com.health.openscale.core.database.MIGRATION_14_15
import com.health.openscale.core.database.MIGRATION_6_7
import com.health.openscale.core.database.MIGRATION_7_8
import com.health.openscale.core.database.MIGRATION_8_9
import com.health.openscale.core.database.MIGRATION_9_10
import com.health.openscale.core.service.DerivedValuesCalculator
import java.io.File

/**
 * Shared Room scaffolding for JVM (Robolectric) DB tests: builds databases with the full
 * migration chain, an in-memory variant, the matching [DatabaseRepository], and a hand-rolled
 * legacy v6 database for migration testing. Single source of truth so every DB test stays in
 * sync with the real migration set.
 */
object RoomTestSupport {

    /** The full, ordered migration chain — must mirror DatabaseModule.provideDatabase. */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
    )

    /** On-disk database at the real [AppDatabase.DATABASE_NAME] path, with all migrations applied. */
    fun onDisk(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    /** Fast in-memory database for DAO/use-case tests (main-thread queries allowed). */
    fun inMemory(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /** Builds a [DatabaseRepository] wired to [db]'s DAOs (incl. the derived-values calculator). */
    fun repositoryFor(db: AppDatabase): DatabaseRepository = DatabaseRepository(
        database = db,
        userDao = db.userDao(),
        userGoalsDao = db.userGoalsDao(),
        measurementDao = db.measurementDao(),
        measurementTypeDao = db.measurementTypeDao(),
        measurementValueDao = db.measurementValueDao(),
        derivedValuesCalculator = DerivedValuesCalculator(
            userDao = db.userDao(),
            measurementDao = db.measurementDao(),
            measurementTypeDao = db.measurementTypeDao(),
            measurementValueDao = db.measurementValueDao(),
        ),
    )

    /**
     * Writes a pre-migration (schema version 6) openScale database with one user and one
     * measurement, used to exercise the legacy MIGRATION_6_7 rewrite end-to-end.
     * User: gender=1 (FEMALE), activityLevel=2 (MODERATE), height 168cm. Measurement: weight 72.5.
     */
    fun writeLegacyV6Database(file: File) {
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
