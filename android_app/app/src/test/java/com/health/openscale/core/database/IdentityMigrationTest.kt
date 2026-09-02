/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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
package com.health.openscale.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [MIGRATION_15_16] against a hand-built v15 `MeasurementType` table.
 *
 * v15 has neither a unique constraint nor any validation on custom type names, and
 * databases that came up through MIGRATION_6_7 carry seven duplicated predefined keys.
 * Backfilling identities and only then creating the unique index — with the duplicates
 * merged first — is what keeps the migration from aborting and leaving the app unable to
 * open its own database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityMigrationTest {

    private lateinit var dbFile: File
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** The tables exactly as schemas/15.json declares the parts this migration touches. */
    private val createV15 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `MeasurementType` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `key` TEXT NOT NULL, `name` TEXT, `color` INTEGER NOT NULL, `icon` TEXT NOT NULL,
          `unit` TEXT NOT NULL, `inputType` TEXT NOT NULL, `displayOrder` INTEGER NOT NULL,
          `isDerived` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL,
          `isOnRightYAxis` INTEGER NOT NULL, `isInternal` INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_MeasurementType_key` ON `MeasurementType`(`key`)",
        """
        CREATE TABLE IF NOT EXISTS `MeasurementValue` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `measurementId` INTEGER NOT NULL,
          `typeId` INTEGER NOT NULL, `floatValue` REAL, `intValue` INTEGER,
          `textValue` TEXT, `dateValue` INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `user_goals` (
          `userId` INTEGER NOT NULL, `measurementTypeId` INTEGER NOT NULL,
          `goalValue` REAL NOT NULL, PRIMARY KEY(`userId`, `measurementTypeId`)
        )
        """.trimIndent(),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = File(context.cacheDir, "v15-${System.nanoTime()}.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createV15.forEach(db::execSQL)
                    override fun onUpgrade(db: SupportSQLiteDatabase, o: Int, n: Int) = Unit
                })
                .build()
        )
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        dbFile.delete()
    }

    @Test
    fun `predefined rows are rewritten in place to builtin identities`() {
        insertType("WEIGHT")
        insertType("BODY_FAT")

        MIGRATION_15_16.migrate(db)

        assertThat(identities()).containsExactly("builtin.weight", "builtin.body_fat").inOrder()
    }

    @Test
    fun `duplicated predefined rows are merged onto their oldest row`() {
        val survivor = insertType("HEART_RATE")
        insertType("HEART_RATE")

        MIGRATION_15_16.migrate(db)

        assertThat(ids()).containsExactly(survivor)
        assertThat(identities()).containsExactly("builtin.heart_rate")
    }

    @Test
    fun `values written to the duplicate move to the survivor, none are lost`() {
        val survivor = insertType("HEART_RATE")
        val duplicate = insertType("HEART_RATE")
        // BleConnector resolved by key and kept the last row, so measurements taken after
        // the faulty 2.x upgrade hang off the duplicate.
        insertValue(measurementId = 1, typeId = duplicate, value = 62f)
        insertValue(measurementId = 2, typeId = duplicate, value = 71f)
        insertValue(measurementId = 3, typeId = survivor, value = 58f)

        MIGRATION_15_16.migrate(db)

        assertThat(valuesFor(survivor)).containsExactly(62f, 71f, 58f)
    }

    @Test
    fun `a measurement holding both rows keeps the older row's value`() {
        val survivor = insertType("HEART_RATE")
        val duplicate = insertType("HEART_RATE")
        insertValue(measurementId = 1, typeId = survivor, value = 60f)
        insertValue(measurementId = 1, typeId = duplicate, value = 99f)

        MIGRATION_15_16.migrate(db)

        assertThat(valuesFor(survivor)).containsExactly(60f)
    }

    @Test
    fun `goals pointing at the duplicate move too`() {
        val survivor = insertType("WEIGHT")
        val duplicate = insertType("WEIGHT")
        insertGoal(userId = 1, typeId = duplicate, value = 70f)
        insertGoal(userId = 2, typeId = survivor, value = 65f)
        insertGoal(userId = 2, typeId = duplicate, value = 99f)

        MIGRATION_15_16.migrate(db)

        assertThat(goalsFor(survivor)).containsExactly(1 to 70f, 2 to 65f)
    }

    @Test
    fun `duplicate custom names are numbered instead of aborting the migration`() {
        insertType("CUSTOM", name = "Schritte")
        insertType("CUSTOM", name = "Schritte")
        insertType("CUSTOM", name = "schritte")

        MIGRATION_15_16.migrate(db)

        assertThat(identities())
            .containsExactly("user.schritte", "user.schritte_2", "user.schritte_3").inOrder()
    }

    @Test
    fun `a custom type named like a predefined one cannot take its column`() {
        insertType("WEIGHT")
        insertType("CUSTOM", name = "Weight")

        MIGRATION_15_16.migrate(db)

        assertThat(identities()).containsExactly("builtin.weight", "user.weight_2").inOrder()
    }

    @Test
    fun `the unique index exists afterwards and actually bites`() {
        insertType("CUSTOM", name = "Schritte")

        MIGRATION_15_16.migrate(db)

        val duplicate = runCatching {
            db.execSQL(
                "INSERT INTO MeasurementType (`identity`,`name`,`color`,`icon`,`unit`,`inputType`," +
                    "`displayOrder`,`isDerived`,`isEnabled`,`isPinned`,`isOnRightYAxis`,`isInternal`) " +
                    "VALUES ('user.schritte','x',0,'IC_DEFAULT','NONE','FLOAT',0,0,1,0,0,0)"
            )
        }
        assertThat(duplicate.isFailure).isTrue()
    }

    // --- retiring the dual-frequency BIA keys ----------------------------------

    @Test
    fun `ECW, ICW and BCM become device types without moving a single value`() {
        val ecw = insertType("ECW")
        val icw = insertType("ICW")
        val bcm = insertType("BCM")
        insertValue(measurementId = 1, typeId = ecw, value = 24.6f)
        insertValue(measurementId = 2, typeId = bcm, value = 31.4f)

        MIGRATION_15_16.migrate(db)

        // Rewritten in place: ids and the values hanging off them survive untouched.
        assertThat(ids()).containsExactly(ecw, icw, bcm).inOrder()
        assertThat(identities()).containsExactly("ble.ecw", "ble.icw", "ble.bcm").inOrder()
        assertThat(valuesFor(ecw)).containsExactly(24.6f)
        assertThat(valuesFor(bcm)).containsExactly(31.4f)
    }

    @Test
    fun `the retired rows get a stored name, their localized key is gone`() {
        insertType("ICW")

        MIGRATION_15_16.migrate(db)

        assertThat(names().single()).isEqualTo("Intracellular water")
    }

    @Test
    fun `retired duplicates from a 2x upgrade are merged first`() {
        val survivor = insertType("ECW")
        val duplicate = insertType("ECW")
        insertValue(measurementId = 1, typeId = duplicate, value = 24.6f)

        MIGRATION_15_16.migrate(db)

        assertThat(ids()).containsExactly(survivor)
        assertThat(identities()).containsExactly("ble.ecw")
        assertThat(valuesFor(survivor)).containsExactly(24.6f)
    }

    @Test
    fun `the retired CSV columns stay what they always were`() {
        insertType("ECW")

        MIGRATION_15_16.migrate(db)

        assertThat(com.health.openscale.core.data.MeasurementType.identityColumnKey(identities().single()))
            .isEqualTo("ECW")
    }

    @Test
    fun `a custom type named ECW cannot claim the freshly retired ble column`() {
        // The combined case the isolated tests miss: a real v15 install has the ECW row
        // AND may have a same-named custom type. The retired ble.ecw column must be
        // reserved before user slugs are assigned.
        insertType("ECW")
        insertType("CUSTOM", name = "ECW")

        MIGRATION_15_16.migrate(db)

        assertThat(identities()).containsExactly("ble.ecw", "user.ecw_2").inOrder()
    }

    @Test
    fun `retired and custom rows are renumbered behind the predefined block`() {
        // The retired rows kept the displayOrder 14_15 gave them as builtins; left alone
        // they would tie with the freshly re-stamped predefined values and make the list
        // order unstable.
        insertType("WEIGHT")
        insertType("ECW")
        insertType("CUSTOM", name = "Schritte")

        MIGRATION_15_16.migrate(db)

        val orders = mutableMapOf<String, Int>()
        db.query("SELECT identity, displayOrder FROM MeasurementType").use { c ->
            while (c.moveToNext()) orders[c.getString(0)] = c.getInt(1)
        }
        assertThat(orders.values.toList()).containsNoDuplicates()
        val builtinMax = com.health.openscale.core.data.MeasurementType.allKeys.size
        assertThat(orders["builtin.weight"]).isAtMost(builtinMax)
        assertThat(orders["ble.ecw"]).isGreaterThan(builtinMax)
        assertThat(orders["user.schritte"]).isGreaterThan(builtinMax)
    }

    // --- helpers ---------------------------------------------------------------

    @Test
    fun `a full master dataset survives the migration without losing a value`() {
        // The v15 vocabulary as the 2.x upgrade chain really leaves it: all 34 predefined
        // keys, of which seven exist twice (6_7 seeds everything, 13_14/14_15 insert them
        // again), plus custom types including a duplicated name.
        val v15Keys = listOf(
            "WEIGHT", "BMI", "BODY_FAT", "WATER", "MUSCLE", "LBM", "BONE", "WAIST", "WHR",
            "WHTR", "HIPS", "VISCERAL_FAT", "CHEST", "THIGH", "BICEPS", "NECK", "CALIPER_1",
            "CALIPER_2", "CALIPER_3", "CALIPER", "BMR", "TDEE", "HEART_RATE", "IMPEDANCE",
            "IMPEDANCE_LOW", "ECW", "ICW", "BCM", "PROTEIN", "CALORIES", "COMMENT", "DATE",
            "TIME", "USER"
        )
        val idByKey = v15Keys.associateWith { insertType(it) }
        val duplicated = listOf("HEART_RATE", "IMPEDANCE", "IMPEDANCE_LOW", "ECW", "ICW", "PROTEIN", "BCM")
        val dupIdByKey = duplicated.associateWith { insertType(it) }
        val schritte = insertType("CUSTOM", "Schritte")
        val schritte2 = insertType("CUSTOM", "Schritte")
        val umlaut = insertType("CUSTOM", "Körperumfang")

        // One unique value per (measurement, type) so any loss or mix-up is visible.
        var next = 1f
        val planned = mutableMapOf<Int, MutableList<Float>>()
        fun value(measurementId: Int, typeId: Int) {
            insertValue(measurementId, typeId, next)
            planned.getOrPut(typeId) { mutableListOf() } += next
            next += 1f
        }
        val numericKeys = v15Keys - listOf("COMMENT", "DATE", "TIME", "USER")
        numericKeys.forEach { value(1, idByKey.getValue(it)) }
        // Weigh-ins taken after the 2.x upgrade landed on the duplicate rows:
        duplicated.forEach { value(2, dupIdByKey.getValue(it)) }
        value(3, schritte); value(3, schritte2); value(3, umlaut)
        // The single intended deletion: measurement 1 already holds PROTEIN on the
        // survivor, so the duplicate's copy for the same measurement is a true duplicate.
        insertValue(1, dupIdByKey.getValue("PROTEIN"), 999f)

        insertGoal(1, idByKey.getValue("WEIGHT"), 75f)
        insertGoal(1, dupIdByKey.getValue("ECW"), 40f)   // must move to the surviving ECW row
        insertGoal(2, schritte, 10_000f)

        fun count(table: String): Int {
            db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToNext(); return c.getInt(0) }
        }
        val valuesBefore = count("MeasurementValue")

        MIGRATION_15_16.migrate(db)

        // Exactly one row gone — the same-measurement duplicate; nothing else.
        assertThat(count("MeasurementValue")).isEqualTo(valuesBefore - 1)
        assertThat(count("user_goals")).isEqualTo(3)

        // Merged duplicates: the survivor now holds its own and the duplicate's values.
        duplicated.forEach { key ->
            val expected = planned.getValue(idByKey.getValue(key)) +
                planned.getValue(dupIdByKey.getValue(key))
            assertThat(valuesFor(idByKey.getValue(key))).containsExactlyElementsIn(expected)
        }
        // Every other row was rewritten in place — same id, same values, new identity.
        (numericKeys - duplicated).forEach { key ->
            assertThat(valuesFor(idByKey.getValue(key)))
                .containsExactlyElementsIn(planned.getValue(idByKey.getValue(key)))
        }
        listOf(schritte, schritte2, umlaut).forEach { id ->
            assertThat(valuesFor(id)).containsExactlyElementsIn(planned.getValue(id))
        }

        // Identities landed where the vocabulary says: ble.* for the retired trio,
        // user.* for customs, builtin.* for the rest — all unique, none blank.
        fun identityOf(id: Int): String {
            db.query("SELECT identity FROM MeasurementType WHERE id = ?", arrayOf<Any?>(id))
                .use { c -> c.moveToNext(); return c.getString(0) }
        }
        assertThat(identityOf(idByKey.getValue("ECW"))).isEqualTo("ble.ecw")
        assertThat(identityOf(idByKey.getValue("WEIGHT"))).isEqualTo("builtin.weight")
        assertThat(identityOf(schritte)).startsWith("user.")
        assertThat(identityOf(schritte2)).startsWith("user.")
        assertThat(identityOf(umlaut)).startsWith("user.")
        val all = identities()
        assertThat(all).containsNoDuplicates()
        assertThat(all).doesNotContain("")

        // Goals: the duplicate ECW goal followed its values to the surviving row.
        val goalTargets = mutableListOf<Int>()
        db.query("SELECT measurementTypeId FROM user_goals ORDER BY userId ASC, measurementTypeId ASC")
            .use { c -> while (c.moveToNext()) goalTargets += c.getInt(0) }
        assertThat(goalTargets).containsExactly(
            idByKey.getValue("WEIGHT"), idByKey.getValue("ECW"), schritte
        )
    }

    private fun insertType(key: String, name: String? = null): Int {
        db.execSQL(
            "INSERT INTO MeasurementType (`key`,`name`,`color`,`icon`,`unit`,`inputType`," +
                "`displayOrder`,`isDerived`,`isEnabled`,`isPinned`,`isOnRightYAxis`,`isInternal`) " +
                "VALUES (?,?,0,'IC_DEFAULT','NONE','FLOAT',0,0,1,0,0,0)",
            arrayOf<Any?>(key, name)
        )
        var id = -1
        db.query("SELECT last_insert_rowid()").use { c -> if (c.moveToNext()) id = c.getInt(0) }
        return id
    }

    private fun insertValue(measurementId: Int, typeId: Int, value: Float) {
        db.execSQL(
            "INSERT INTO MeasurementValue (`measurementId`,`typeId`,`floatValue`) VALUES (?,?,?)",
            arrayOf<Any?>(measurementId, typeId, value)
        )
    }

    private fun insertGoal(userId: Int, typeId: Int, value: Float) {
        db.execSQL(
            "INSERT INTO user_goals (`userId`,`measurementTypeId`,`goalValue`) VALUES (?,?,?)",
            arrayOf<Any?>(userId, typeId, value)
        )
    }

    private fun identities(): List<String> = readColumn("SELECT identity FROM MeasurementType ORDER BY id ASC")

    private fun ids(): List<Int> {
        val out = mutableListOf<Int>()
        db.query("SELECT id FROM MeasurementType ORDER BY id ASC")
            .use { c -> while (c.moveToNext()) out += c.getInt(0) }
        return out
    }

    private fun valuesFor(typeId: Int): List<Float> {
        val out = mutableListOf<Float>()
        db.query("SELECT floatValue FROM MeasurementValue WHERE typeId = ? ORDER BY id ASC", arrayOf<Any?>(typeId))
            .use { c -> while (c.moveToNext()) out += c.getFloat(0) }
        return out
    }

    private fun goalsFor(typeId: Int): List<Pair<Int, Float>> {
        val out = mutableListOf<Pair<Int, Float>>()
        db.query("SELECT userId, goalValue FROM user_goals WHERE measurementTypeId = ? ORDER BY userId ASC", arrayOf<Any?>(typeId))
            .use { c -> while (c.moveToNext()) out += c.getInt(0) to c.getFloat(1) }
        return out
    }

    private fun readColumn(sql: String): List<String> {
        val out = mutableListOf<String>()
        db.query(sql).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    private fun names(): List<String?> {
        val out = mutableListOf<String?>()
        db.query("SELECT name FROM MeasurementType ORDER BY id ASC")
            .use { c -> while (c.moveToNext()) out += if (c.isNull(0)) null else c.getString(0) }
        return out
    }
}
