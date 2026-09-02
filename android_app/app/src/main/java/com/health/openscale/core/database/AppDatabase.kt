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
import com.health.openscale.core.data.UserIcon
import com.health.openscale.core.data.UserGoals
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
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
    version = 16,
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

/**
 * The pre-16 `key` column value of a predefined key (`WEIGHT`, `BODY_FAT`).
 *
 * The migrations below run at chain positions where the schema still stores enum names, so
 * they must keep writing the old format; 15→16 converts afterwards. Derived from the
 * identity instead of the removed enum — behaviour is byte-identical.
 */
private val MeasurementType.Key<*>.legacyKeyName: String
    get() = MeasurementType.identityColumnKey(identity)

// -----------------------------------------------------------------------------
// Legacy v2.x migrations (schema versions 1..6).
//
// These were shipped by the old Java app (see
// v2.5.4:.../core/database/AppDatabase.java) and were dropped during the Kotlin
// rewrite, which registered only 6..15. A database exported from an older v2.x
// build carries a `user_version` below 6 (e.g. v2.3.1 == 5), so restoring it and
// launching v3.x left Room without a migration path and it crashed on launch
// (issue #1410). Restoring these brings a v1..v5 database up to version 6, where
// MIGRATION_6_7 (the legacy schema converter) takes over. The SQL is a verbatim
// port of the original, production-tested migrations; Room already wraps each
// migration in a transaction, so the old explicit begin/end calls are omitted.
// -----------------------------------------------------------------------------

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Drop old index on datetime only
        db.execSQL("DROP INDEX index_scaleMeasurements_datetime")

        // Rename old table
        db.execSQL("ALTER TABLE scaleMeasurements RENAME TO scaleMeasurementsOld")

        // Create new table with foreign key
        db.execSQL(
            "CREATE TABLE scaleMeasurements" +
                " (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                " userId INTEGER NOT NULL, enabled INTEGER NOT NULL," +
                " datetime INTEGER, weight REAL NOT NULL, fat REAL NOT NULL," +
                " water REAL NOT NULL, muscle REAL NOT NULL, lbw REAL NOT NULL," +
                " waist REAL NOT NULL, hip REAL NOT NULL, bone REAL NOT NULL," +
                " comment TEXT, FOREIGN KEY(userId) REFERENCES scaleUsers(id)" +
                " ON UPDATE NO ACTION ON DELETE CASCADE)"
        )

        // Create new index on datetime + userId
        db.execSQL(
            "CREATE UNIQUE INDEX index_scaleMeasurements_userId_datetime" +
                " ON scaleMeasurements (userId, datetime)"
        )

        // Copy data from the old table, ignoring those with invalid userId (if any)
        db.execSQL(
            "INSERT INTO scaleMeasurements" +
                " SELECT * FROM scaleMeasurementsOld" +
                " WHERE userId IN (SELECT id from scaleUsers)"
        )

        // Delete old table
        db.execSQL("DROP TABLE scaleMeasurementsOld")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Drop old index
        db.execSQL("DROP INDEX index_scaleMeasurements_userId_datetime")

        // Rename old tables
        db.execSQL("ALTER TABLE scaleMeasurements RENAME TO scaleMeasurementsOld")
        db.execSQL("ALTER TABLE scaleUsers RENAME TO scaleUsersOld")

        // Create new table with foreign key
        db.execSQL(
            "CREATE TABLE scaleMeasurements" +
                " (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                " userId INTEGER NOT NULL, enabled INTEGER NOT NULL," +
                " datetime INTEGER, weight REAL NOT NULL, fat REAL NOT NULL," +
                " water REAL NOT NULL, muscle REAL NOT NULL, visceralFat REAL NOT NULL," +
                " lbm REAL NOT NULL, waist REAL NOT NULL, hip REAL NOT NULL," +
                " bone REAL NOT NULL, chest REAL NOT NULL, thigh REAL NOT NULL," +
                " biceps REAL NOT NULL, neck REAL NOT NULL, caliper1 REAL NOT NULL," +
                " caliper2 REAL NOT NULL, caliper3 REAL NOT NULL, comment TEXT," +
                " FOREIGN KEY(userId) REFERENCES scaleUsers(id)" +
                " ON UPDATE NO ACTION ON DELETE CASCADE)"
        )

        db.execSQL(
            "CREATE TABLE scaleUsers " +
                "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "username TEXT NOT NULL, birthday INTEGER NOT NULL, bodyHeight REAL NOT NULL, " +
                "scaleUnit INTEGER NOT NULL, gender INTEGER NOT NULL, initialWeight REAL NOT NULL, " +
                "goalWeight REAL NOT NULL, goalDate INTEGER, measureUnit INTEGER NOT NULL, activityLevel INTEGER NOT NULL)"
        )

        // Create new index on datetime + userId
        db.execSQL(
            "CREATE UNIQUE INDEX index_scaleMeasurements_userId_datetime" +
                " ON scaleMeasurements (userId, datetime)"
        )

        // Copy data from the old tables
        db.execSQL(
            "INSERT INTO scaleMeasurements" +
                " SELECT id, userId, enabled, datetime, weight, fat, water, muscle," +
                " 0 AS visceralFat, lbw AS lbm, waist, hip, bone, 0 AS chest," +
                " 0 as thigh, 0 as biceps, 0 as neck, 0 as caliper1," +
                " 0 as caliper2, 0 as caliper3, comment FROM scaleMeasurementsOld"
        )

        db.execSQL(
            "INSERT INTO scaleUsers" +
                " SELECT id, username, birthday, bodyHeight, scaleUnit, gender, initialWeight, goalWeight," +
                " goalDate, 0 AS measureUnit, 0 AS activityLevel FROM scaleUsersOld"
        )

        // Delete old tables
        db.execSQL("DROP TABLE scaleMeasurementsOld")
        db.execSQL("DROP TABLE scaleUsersOld")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Drop old index
        db.execSQL("DROP INDEX index_scaleMeasurements_userId_datetime")

        // Rename old table
        db.execSQL("ALTER TABLE scaleMeasurements RENAME TO scaleMeasurementsOld")

        // Create new table with foreign key
        db.execSQL(
            "CREATE TABLE scaleMeasurements" +
                " (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                " userId INTEGER NOT NULL, enabled INTEGER NOT NULL," +
                " datetime INTEGER, weight REAL NOT NULL, fat REAL NOT NULL," +
                " water REAL NOT NULL, muscle REAL NOT NULL, visceralFat REAL NOT NULL," +
                " lbm REAL NOT NULL, waist REAL NOT NULL, hip REAL NOT NULL," +
                " bone REAL NOT NULL, chest REAL NOT NULL, thigh REAL NOT NULL," +
                " biceps REAL NOT NULL, neck REAL NOT NULL, caliper1 REAL NOT NULL," +
                " caliper2 REAL NOT NULL, caliper3 REAL NOT NULL, calories REAL NOT NULL, comment TEXT," +
                " FOREIGN KEY(userId) REFERENCES scaleUsers(id)" +
                " ON UPDATE NO ACTION ON DELETE CASCADE)"
        )

        // Create new index on datetime + userId
        db.execSQL(
            "CREATE UNIQUE INDEX index_scaleMeasurements_userId_datetime" +
                " ON scaleMeasurements (userId, datetime)"
        )

        // Copy data from the old table
        db.execSQL(
            "INSERT INTO scaleMeasurements" +
                " SELECT id, userId, enabled, datetime, weight, fat, water, muscle," +
                " visceralFat, lbm, waist, hip, bone, chest," +
                " thigh, biceps, neck, caliper1," +
                " caliper2, caliper3, 0 as calories, comment FROM scaleMeasurementsOld"
        )

        // Delete old table
        db.execSQL("DROP TABLE scaleMeasurementsOld")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add assisted weighing and left/right amputation level to table
        db.execSQL("ALTER TABLE scaleUsers ADD assistedWeighing INTEGER NOT NULL default 0")
        db.execSQL("ALTER TABLE scaleUsers ADD leftAmputationLevel INTEGER NOT NULL default 0")
        db.execSQL("ALTER TABLE scaleUsers ADD rightAmputationLevel INTEGER NOT NULL default 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add goal enabled to scale user table
        db.execSQL("ALTER TABLE scaleUsers ADD goalEnabled INTEGER NOT NULL default 0")
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

        // --- Create measurement types idempotently (INSERT OR IGNORE over the UNIQUE
        // index created above). The OR IGNORE only works because this migration just
        // created a *unique* index on `key`; MIGRATION_12_13 later drops it, so anything
        // running after that cannot rely on this pattern — see the notes there and in
        // 13_14 / 14_15.
        fun ensureType(db: SupportSQLiteDatabase, key: MeasurementType.Key<*>, displayOrder: Int) {
            db.execSQL(
                """
                    INSERT OR IGNORE INTO MeasurementType
                        (`key`,`name`,`color`,`icon`,`unit`,`inputType`,`displayOrder`,
                         `isDerived`,`isEnabled`,`isPinned`,`isOnRightYAxis`)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(),
                arrayOf<Any?>(
                    key.legacyKeyName,
                    null,
                    key.defaultColor,
                    key.defaultIcon.name,
                    key.defaultUnit.name,
                    key.inputType.name,
                    displayOrder,
                    if (key.isDerived) 1 else 0,
                    if (key.defaultEnabled) 1 else 0,
                    if (key.defaultPinned) 1 else 0,
                    if (key.defaultOnRightYAxis) 1 else 0
                )
            )
        }

        var order = 1
        for (key in MeasurementType.allKeys) {
            ensureType(db, key, order++)
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
        //
        // Caveat, kept as-is because the migration has shipped: the OR IGNORE has no
        // conflict target here — MIGRATION_12_13 dropped the unique index on `key`, so
        // this insert is in fact unconditional. A database that came up through
        // MIGRATION_6_7 already holds HEART_RATE (6_7 seeds the whole registry) and ends
        // up with it twice. MIGRATION_15_16 merges such duplicates back onto their
        // oldest row.
        val heartRate = MeasurementType.HEART_RATE
        db.execSQL(
            """
            INSERT OR IGNORE INTO MeasurementType 
                (`key`, `name`, `color`, `icon`, `unit`, `inputType`, `displayOrder`,
                 `isDerived`, `isEnabled`, `isPinned`, `isOnRightYAxis`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                heartRate.legacyKeyName,
                null,
                heartRate.defaultColor,
                heartRate.defaultIcon.name,
                heartRate.defaultUnit.name,
                heartRate.inputType.name,
                -1, // Use a temporary displayOrder to avoid conflicts
                if (heartRate.isDerived) 1 else 0,
                if (heartRate.defaultEnabled) 1 else 0,
                if (heartRate.defaultPinned) 1 else 0,
                if (heartRate.defaultOnRightYAxis) 1 else 0
            )
        )

        // Step 2: Re-order ALL existing types to match the canonical registry order.
        // This ensures the order is identical for new installs and migrated users.
        db.beginTransaction()
        try {
            MeasurementType.allKeys.forEachIndexed { index, key ->
                val displayOrder = index + 1 // Room/SQL indices are often 1-based
                db.execSQL(
                    "UPDATE MeasurementType SET displayOrder = ? WHERE `key` = ?",
                    arrayOf<Any?>(displayOrder, key.legacyKeyName)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add the `isInternal` column used to hide raw inputs (e.g. BIA
        // impedance bands) from end-user UI while keeping them in the DB for
        // re-derivation when formulas change.
        db.execSQL(
            "ALTER TABLE MeasurementType " +
            "ADD COLUMN isInternal INTEGER NOT NULL DEFAULT 0"
        )

        // Seed the MeasurementTypes introduced for S400 dual-frequency body composition.
        // ECW, ICW and BCM are no longer predefined (they moved to the ble.* namespace,
        // see MIGRATION_15_16), so this migration stops seeding them: a ≤14 database never
        // gets the rows and the S400 handler creates them on first use instead.
        //
        // Same caveat as MIGRATION_13_14: without the unique index on `key` (dropped in
        // 12_13) the OR IGNORE never fires, and databases that came through 6_7 get these
        // a second time. MIGRATION_15_16 merges the duplicates.
        val newKeys = listOf(
            MeasurementType.IMPEDANCE, MeasurementType.IMPEDANCE_LOW, MeasurementType.PROTEIN,
        )
        newKeys.forEach { key ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO MeasurementType
                    (`key`, `name`, `color`, `icon`, `unit`, `inputType`, `displayOrder`,
                     `isDerived`, `isEnabled`, `isPinned`, `isOnRightYAxis`, `isInternal`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    key.legacyKeyName,
                    null,
                    key.defaultColor,
                    key.defaultIcon.name,
                    key.defaultUnit.name,
                    key.inputType.name,
                    -1,
                    if (key.isDerived) 1 else 0,
                    if (key.defaultEnabled) 1 else 0,
                    if (key.defaultPinned) 1 else 0,
                    if (key.defaultOnRightYAxis) 1 else 0,
                    if (key.isInternal) 1 else 0
                )
            )
        }

        // Re-apply displayOrder to keep new + existing types aligned with the
        // canonical registry order.
        db.beginTransaction()
        try {
            MeasurementType.allKeys.forEachIndexed { index, key ->
                db.execSQL(
                    "UPDATE MeasurementType SET displayOrder = ? WHERE `key` = ?",
                    arrayOf<Any?>(index + 1, key.legacyKeyName)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // One identity column for every measurement type: `builtin.weight` derived from the
        // former enum name, `ble.*` for values a scale handler contributes, `user.*` for
        // user-created types. A unique index over it then guarantees what the schema has
        // not enforced since MIGRATION_12_13 dropped the unique index on `key`.
        db.execSQL("DROP INDEX IF EXISTS `index_MeasurementType_key`")
        db.execSQL("ALTER TABLE `MeasurementType` RENAME COLUMN `key` TO `identity`")

        // --- Step 1: collapse predefined types that exist more than once ------------------
        //
        // Databases that came up through MIGRATION_6_7 carry seven duplicates: 6_7 seeds
        // the whole registry, and 13_14 / 14_15 then insert HEART_RATE, IMPEDANCE,
        // IMPEDANCE_LOW, ECW, ICW, PROTEIN and BCM a second time, because their INSERT OR
        // IGNORE lost its conflict target with 12_13.
        //
        // The duplicates are not empty: BleConnector resolved types by key and kept the
        // *last* row, so measurements taken since that upgrade were written to the
        // duplicate. Values and goals are therefore moved to the surviving row rather than
        // deleted, and only what the survivor already holds for the same measurement (or
        // user) is dropped. Deletions are explicit instead of relying on ON DELETE
        // CASCADE, whose enforcement during a migration depends on the connection's
        // foreign-key pragma.
        val idsByLegacyKey = LinkedHashMap<String, MutableList<Int>>()
        db.query(
            "SELECT id, identity FROM MeasurementType WHERE identity != ? ORDER BY id ASC",
            arrayOf<Any?>(MeasurementType.LEGACY_CUSTOM_KEY)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                idsByLegacyKey.getOrPut(cursor.getString(1)) { mutableListOf() }
                    .add(cursor.getInt(0))
            }
        }

        idsByLegacyKey.values.filter { it.size > 1 }.forEach { ids ->
            val survivor = ids.first()

            ids.drop(1).forEach { duplicate ->
                db.execSQL(
                    "UPDATE MeasurementValue SET typeId = ? WHERE typeId = ? AND measurementId " +
                        "NOT IN (SELECT measurementId FROM MeasurementValue WHERE typeId = ?)",
                    arrayOf<Any?>(survivor, duplicate, survivor)
                )
                db.execSQL(
                    "UPDATE user_goals SET measurementTypeId = ? WHERE measurementTypeId = ? " +
                        "AND userId NOT IN (SELECT userId FROM user_goals WHERE measurementTypeId = ?)",
                    arrayOf<Any?>(survivor, duplicate, survivor)
                )

                db.execSQL("DELETE FROM MeasurementValue WHERE typeId = ?", arrayOf<Any?>(duplicate))
                db.execSQL("DELETE FROM user_goals WHERE measurementTypeId = ?", arrayOf<Any?>(duplicate))
                db.execSQL("DELETE FROM MeasurementType WHERE id = ?", arrayOf<Any?>(duplicate))
            }
        }

        // --- Step 2: predefined rows get their namespaced identity ------------------------
        // 'BODY_FAT' -> 'builtin.body_fat'. Correct because every registry id is, by
        // guarded invariant, the lowercased historical enum name.
        db.execSQL(
            "UPDATE MeasurementType SET identity = 'builtin.' || lower(identity) " +
                "WHERE identity != ?",
            arrayOf<Any?>(MeasurementType.LEGACY_CUSTOM_KEY)
        )

        // --- Step 3: retire the keys only a dual-frequency BIA scale ever filled ----------
        //
        // ECW, ICW and BCM leave the predefined vocabulary: the Xiaomi S400 was the sole
        // handler writing them, and any future dual-frequency scale reuses the same ble.*
        // identities. The rows are rewritten in place, so their ids — and every value
        // pointing at them — survive untouched. The paths are deliberately flat, so the
        // derived CSV column stays the ECW / ICW / BCM these types always exported as.
        //
        // The name must be written here: once the row is no longer predefined its display
        // name comes from storage, and a migration has no Context to localise with. Fresh
        // creations through the handler get the translated name instead.
        listOf(
            Triple("builtin.ecw", "ble.ecw", "Extracellular water"),
            Triple("builtin.icw", "ble.icw", "Intracellular water"),
            Triple("builtin.bcm", "ble.bcm", "Body cell mass"),
        ).forEach { (retired, identity, name) ->
            db.execSQL(
                "UPDATE MeasurementType SET identity = ?, name = ? WHERE identity = ?",
                arrayOf<Any?>(identity, name, retired)
            )
        }

        // --- Step 4: user-created rows get user.* identities -------------------------------
        // Pre-16 has neither a constraint nor any validation on custom names, so a real
        // database can hold two types called "Schritte"; userIdentityFor numbers those
        // apart. Creating the index first would abort the migration on such a database and
        // leave the app unable to open it at all.
        val assignments = mutableListOf<Pair<Int, String>>()

        // Reserve the columns of every already-assigned row — including the ble.* ones
        // step 3 just minted, which are no longer in the registry's reserved set. Without
        // this, a v15 custom type named "ECW" would claim user.ecw and collide with
        // ble.ecw on the CSV column.
        val takenColumnKeys = mutableSetOf<String>()
        db.query(
            "SELECT identity FROM MeasurementType WHERE identity != ?",
            arrayOf<Any?>(MeasurementType.LEGACY_CUSTOM_KEY)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                takenColumnKeys.add(MeasurementType.identityColumnKey(cursor.getString(0)))
            }
        }

        db.query(
            "SELECT id, name FROM MeasurementType WHERE identity = ? ORDER BY id ASC",
            arrayOf<Any?>(MeasurementType.LEGACY_CUSTOM_KEY)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = if (cursor.isNull(1)) null else cursor.getString(1)
                val identity = MeasurementType.userIdentityFor(name, takenColumnKeys)
                takenColumnKeys.add(MeasurementType.identityColumnKey(identity))
                assignments.add(cursor.getInt(0) to identity)
            }
        }

        assignments.forEach { (id, identity) ->
            db.execSQL(
                "UPDATE MeasurementType SET identity = ? WHERE id = ?",
                arrayOf<Any?>(identity, id)
            )
        }

        // --- Step 5: re-apply the canonical order, as 13_14 and 14_15 do -------------------
        // Needed here because the merged duplicates left two rows sharing a displayOrder.
        MeasurementType.allKeys.forEachIndexed { index, key ->
            db.execSQL(
                "UPDATE MeasurementType SET displayOrder = ? WHERE identity = ?",
                arrayOf<Any?>(index + 1, key.identity)
            )
        }

        // Non-predefined rows (the retired ble.* ones and user customs) are renumbered
        // behind the predefined block, keeping their relative order. The retired rows
        // would otherwise keep the displayOrder they had as builtins and tie with the
        // freshly re-stamped values, making the list order unstable.
        val trailing = mutableListOf<Int>()
        db.query(
            "SELECT id FROM MeasurementType WHERE identity NOT LIKE 'builtin.%' " +
                "ORDER BY displayOrder ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) trailing.add(cursor.getInt(0))
        }
        trailing.forEachIndexed { index, id ->
            db.execSQL(
                "UPDATE MeasurementType SET displayOrder = ? WHERE id = ?",
                arrayOf<Any?>(MeasurementType.allKeys.size + 1 + index, id)
            )
        }

        // Only now, with duplicates merged and every row assigned: the identity becomes an
        // enforced invariant.
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_MeasurementType_identity` " +
                "ON `MeasurementType`(`identity`)"
        )
    }
}
