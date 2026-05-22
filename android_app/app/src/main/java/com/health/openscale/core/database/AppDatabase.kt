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
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserIcon
import com.health.openscale.core.data.UserGoals
import com.health.openscale.getDefaultMeasurementTypes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, AppDatabase.Companion.DATABASE_NAME)
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
            .build()

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
    @Provides
    fun provideUserGoalsDao(db: AppDatabase): UserGoalsDao = db.userGoalsDao()
    @Provides
    fun provideMeasurementDao(db: AppDatabase): MeasurementDao = db.measurementDao()
    @Provides
    fun provideMeasurementValueDao(db: AppDatabase): MeasurementValueDao = db.measurementValueDao()
    @Provides
    fun provideMeasurementTypeDao(db: AppDatabase): MeasurementTypeDao = db.measurementTypeDao()
}

/**
 * Main Room database for the application.
 * It holds references to all DAOs and manages the database instance.
 */
@Database(
    entities = [
        User::class,
        UserGoals::class,
        Measurement::class,
        MeasurementValue::class,
        MeasurementType::class,
    ],
    version = 14,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun userGoalsDao(): UserGoalsDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun measurementValueDao(): MeasurementValueDao
    abstract fun measurementTypeDao(): MeasurementTypeDao

    companion object {
        const val DATABASE_NAME = "openScale.db"
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

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF")

        db.execSQL("""
            ALTER TABLE `User`
            ADD COLUMN `icon` TEXT NOT NULL DEFAULT '${UserIcon.IC_DEFAULT.name}'
        """.trimIndent())

        db.execSQL("PRAGMA foreign_keys=ON")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF")

        db.execSQL("""
            ALTER TABLE `User`
            ADD COLUMN `useAssistedWeighing` INTEGER NOT NULL DEFAULT 0
        """.trimIndent())

        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS `index_Measurement_userId_timestamp` 
            ON `Measurement` (`userId`, `timestamp`)
        """.trimIndent())

        db.execSQL("PRAGMA foreign_keys=ON")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `user_goals` (
                `userId` INTEGER NOT NULL,
                `measurementTypeId` INTEGER NOT NULL,
                `goalValue` REAL NOT NULL,
                PRIMARY KEY(`userId`, `measurementTypeId`),
                FOREIGN KEY(`userId`) REFERENCES `User`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`measurementTypeId`) REFERENCES `MeasurementType`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_goals_userId` ON `user_goals` (`userId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_goals_measurementTypeId` ON `user_goals` (`measurementTypeId`)")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `User` ADD COLUMN `amputations` TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `user_goals` ADD COLUMN `goalTargetDate` INTEGER")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_MeasurementType_key`")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_MeasurementType_key` ON `MeasurementType`(`key`)")
    }
}

// In AppDatabase.kt

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Step 1: Add HEART_RATE if it's missing, using INSERT OR IGNORE.
        // We find the definition from the default list to get its properties (color, icon, etc.).
        val heartRateType = getDefaultMeasurementTypes().find { it.key == MeasurementTypeKey.HEART_RATE }

        if (heartRateType != null) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO MeasurementType 
                    (`key`, `name`, `color`, `icon`, `unit`, `inputType`, `displayOrder`,
                     `isDerived`, `isEnabled`, `isPinned`, `isOnRightYAxis`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    heartRateType.key.name,
                    null,
                    heartRateType.color,
                    heartRateType.icon.name,
                    heartRateType.unit.name,
                    heartRateType.inputType.name,
                    -1, // Use a temporary displayOrder to avoid conflicts
                    if (heartRateType.isDerived) 1 else 0,
                    if (heartRateType.isEnabled) 1 else 0,
                    if (heartRateType.isPinned) 1 else 0,
                    if (heartRateType.isOnRightYAxis) 1 else 0
                )
            )
        }

        // Step 2: Re-order ALL existing types to match the getDefaultMeasurementTypes() list.
        // This ensures the order is identical for new installs and migrated users.
        val defaultTypesInOrder = getDefaultMeasurementTypes()
        db.beginTransaction()
        try {
            defaultTypesInOrder.forEachIndexed { index, measurementType ->
                val displayOrder = index + 1 // Room/SQL indices are often 1-based
                db.execSQL(
                    "UPDATE MeasurementType SET displayOrder = ? WHERE `key` = ?",
                    arrayOf<Any?>(displayOrder, measurementType.key.name)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
