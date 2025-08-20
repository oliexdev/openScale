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
package com.health.openscale.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.utils.LogManager
import com.health.openscale.getDefaultMeasurementTypes

/**
 * Main Room database for the application.
 * It holds references to all DAOs and manages the database instance.
 */
@Database(
    entities = [
        User::class,
        Measurement::class,
        MeasurementValue::class,
        MeasurementType::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun measurementValueDao(): MeasurementValueDao
    abstract fun measurementTypeDao(): MeasurementTypeDao

    /**
     * Closes the database connection and resets the singleton instance.
     * This is typically not needed in normal app operation as Room handles lifecycle.
     * Could be useful in specific scenarios like testing or explicit resource cleanup.
     */
    fun closeConnection() {
        if (isOpen) {
            try {
                super.close() // Call RoomDatabase's close method
                INSTANCE = null
                LogManager.i(TAG, "Database connection closed and INSTANCE reset.")
            } catch (e: Exception) {
                LogManager.e(TAG, "Error closing database connection.", e)
            }
        } else {
            LogManager.w(TAG, "Attempted to close database connection, but it was already closed or not initialized.")
        }
    }

    companion object {
        private const val TAG = "AppDatabase"
        const val DATABASE_NAME = "openScale.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Gets the singleton instance of the [AppDatabase].
         * Uses double-checked locking to ensure thread safety.
         *
         * @param context The application context.
         * @return The singleton [AppDatabase] instance.
         */
        fun getInstance(context: Context): AppDatabase {
            // Double-checked locking pattern
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also {
                    LogManager.i(TAG, "Database instance created or retrieved.")
                    INSTANCE = it
                }
            }
        }

        /**
         * Builds the Room database instance.
         *
         * @param appContext The application context.
         * @return A new [AppDatabase] instance.
         */
        private fun buildDatabase(appContext: Context): AppDatabase {
            LogManager.d(TAG, "Building new database instance: $DATABASE_NAME")
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_6_7)
                .build()
        }
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF")

        // --- Create tables ---
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `User`(
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `name` TEXT NOT NULL,
              `birthDate` INTEGER NOT NULL,
              `gender` TEXT NOT NULL,
              `heightCm` REAL NOT NULL,
              `activityLevel` TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `Measurement`(
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `userId` INTEGER NOT NULL,
              `timestamp` INTEGER NOT NULL,
              FOREIGN KEY(`userId`) REFERENCES `User`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_Measurement_userId` ON `Measurement` (`userId`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `MeasurementType`(
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `key` TEXT NOT NULL,
              `name` TEXT,
              `color` INTEGER NOT NULL,
              `icon` TEXT NOT NULL,
              `unit` TEXT NOT NULL,
              `inputType` TEXT NOT NULL,
              `displayOrder` INTEGER NOT NULL,
              `isDerived` INTEGER NOT NULL,
              `isEnabled` INTEGER NOT NULL,
              `isPinned` INTEGER NOT NULL,
              `isOnRightYAxis` INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS `index_MeasurementType_key`
            ON `MeasurementType`(`key`)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `MeasurementValue`(
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `measurementId` INTEGER NOT NULL,
              `typeId` INTEGER NOT NULL,
              `floatValue` REAL,
              `intValue` INTEGER,
              `textValue` TEXT,
              `dateValue` INTEGER,
              FOREIGN KEY(`measurementId`) REFERENCES `Measurement`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
              FOREIGN KEY(`typeId`) REFERENCES `MeasurementType`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MeasurementValue_measurementId` ON `MeasurementValue` (`measurementId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MeasurementValue_typeId` ON `MeasurementValue` (`typeId`)")

        // --- Create measurement type idempotent (INSERT OR IGNORE, used UNIQUE-Index) ---
        fun ensureType(db: SupportSQLiteDatabase, type: MeasurementType, displayOrder: Int) {
            db.execSQL(
                """
                    INSERT OR IGNORE INTO MeasurementType
                        (`key`,`name`,`color`,`icon`,`unit`,`inputType`,`displayOrder`,
                         `isDerived`,`isEnabled`,`isPinned`,`isOnRightYAxis`)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(),
                arrayOf<Any?>(
                    type.key.name,
                    null,
                    type.color,
                    type.icon.name,
                    type.unit.name,
                    type.inputType.name,
                    displayOrder,
                    if (type.isDerived) 1 else 0,
                    if (type.isEnabled) 1 else 0,
                    if (type.isPinned) 1 else 0,
                    if (type.isOnRightYAxis) 1 else 0
                )
            )
        }

        var order = 1
        for (type in getDefaultMeasurementTypes()) {
            ensureType(db, type, order++)
        }

        // --- Migrate users ---
        db.execSQL("""
            INSERT INTO `User` (id, name, birthDate, gender, heightCm, activityLevel)
            SELECT 
              u.id,
              u.username,
              u.birthday,
              CASE u.gender WHEN 0 THEN 'MALE' ELSE 'FEMALE' END,
              u.bodyHeight,
              CASE u.activityLevel
                   WHEN 0 THEN 'SEDENTARY'
                   WHEN 1 THEN 'MILD'
                   WHEN 2 THEN 'MODERATE'
                   WHEN 3 THEN 'HEAVY'
                   WHEN 4 THEN 'EXTREME'
                   ELSE 'SEDENTARY' END
            FROM `scaleUsers` u
        """.trimIndent())

        // --- Migrate measurements (only enabled = 1) ---
        db.execSQL("""
            INSERT INTO `Measurement` (id, userId, timestamp)
            SELECT m.id, m.userId, COALESCE(m.datetime, 0)
            FROM `scaleMeasurements` m
            WHERE m.enabled = 1
        """.trimIndent())

        // --- Migrate values ---
        fun insertFloat(column: String, key: String) {
            db.execSQL("""
                INSERT INTO MeasurementValue (measurementId, typeId, floatValue)
                SELECT m.id,
                       (SELECT id FROM MeasurementType WHERE `key` = ?),
                       m.`$column`
                FROM scaleMeasurements m
                WHERE m.enabled = 1
                  AND m.`$column` IS NOT NULL
                  AND m.`$column` != 0
            """.trimIndent(), arrayOf(key))
        }

        fun insertText(column: String, key: String) {
            db.execSQL("""
        INSERT INTO MeasurementValue (measurementId, typeId, textValue)
        SELECT m.id,
               (SELECT id FROM MeasurementType WHERE `key` = ?),
               m.`$column`
        FROM scaleMeasurements m
        WHERE m.enabled = 1
          AND m.`$column` IS NOT NULL
          AND m.`$column` != ''
    """.trimIndent(), arrayOf(key))
        }

        insertFloat("weight",      "WEIGHT")
        insertFloat("fat",         "BODY_FAT")
        insertFloat("water",       "WATER")
        insertFloat("muscle",      "MUSCLE")
        insertFloat("visceralFat", "VISCERAL_FAT")
        insertFloat("lbm",         "LBM")
        insertFloat("waist",       "WAIST")
        insertFloat("hip",         "HIPS")
        insertFloat("bone",        "BONE")
        insertFloat("chest",       "CHEST")
        insertFloat("thigh",       "THIGH")
        insertFloat("biceps",      "BICEPS")
        insertFloat("neck",        "NECK")
        insertFloat("caliper1",    "CALIPER_1")
        insertFloat("caliper2",    "CALIPER_2")
        insertFloat("caliper3",    "CALIPER_3")
        insertFloat("calories",    "CALORIES")
        insertText ("comment",     "COMMENT")

        // --- Cleanup  ---
        db.execSQL("DROP INDEX IF EXISTS `index_scaleMeasurements_userId_datetime`")
        db.execSQL("DROP TABLE IF EXISTS `scaleMeasurements`")
        db.execSQL("DROP TABLE IF EXISTS `scaleUsers`")

        db.execSQL("PRAGMA foreign_keys=ON")
    }
}
