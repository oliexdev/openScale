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
package com.health.openscale.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Exercises the full migration chain end-to-end on the JVM (Robolectric): a hand-built
 * legacy schema-v6 database is opened with all migrations, running MIGRATION_6_7 (the risky
 * legacy rewrite) through MIGRATION_14_15 in sequence. Verifies the enum mapping and that
 * data survives — the highest data-loss risk in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.getDatabasePath(AppDatabase.DATABASE_NAME).delete()
    }

    @Test
    fun legacyV6_migratesToCurrent_mappingEnumsAndPreservingData() = runBlocking {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        RoomTestSupport.writeLegacyV6Database(dbFile)

        // Opening with the full migration chain runs MIGRATION_6_7 .. MIGRATION_14_15 in order.
        val opened = RoomTestSupport.onDisk(context).also { db = it }
        val repo = RoomTestSupport.repositoryFor(opened)

        val users = repo.getAllUsers().first()
        assertThat(users).hasSize(1)
        val user = users.single()
        assertThat(user.name).isEqualTo("legacy-user")
        assertThat(user.gender).isEqualTo(GenderType.FEMALE)            // legacy gender=1 -> FEMALE
        assertThat(user.activityLevel).isEqualTo(ActivityLevel.MODERATE) // legacy activityLevel=2 -> MODERATE
        assertThat(user.heightCm).isWithin(0.1f).of(168f)

        val measurements = repo.getMeasurementsWithValuesForUser(user.id).first()
        assertThat(measurements).hasSize(1)
        val weight = measurements.single().values
            .mapNotNull { it.value.floatValue }
            .firstOrNull { abs(it - 72.5f) < 0.1f }
        assertThat(weight).isNotNull()

        // The rewrite seeds the default measurement types.
        assertThat(repo.getAllMeasurementTypes().first()).isNotEmpty()
    }

    /**
     * MIGRATION_15_16 re-derives S400 body-composition with each row's OWN user.
     * Seeds a MALE row that wrongly carries female-profile body fat (30.08 %) plus
     * the raw impedance bands, then asserts the migration rewrites it to the
     * male re-derivation (~17.8 %) while leaving raw inputs untouched, and that a
     * row with out-of-range impedance is left unchanged.
     */
    @Test
    fun migration15to16_reDerivesS400BodyCompositionForRowUser() {
        val opened = RoomTestSupport.onDisk(context).also { db = it }
        val sql = opened.openHelper.writableDatabase

        // --- Minimal type catalogue (raw; icon/unit values need not be valid enums here) ---
        fun type(id: Int, key: String) = sql.execSQL(
            "INSERT INTO MeasurementType (id,`key`,name,color,icon,unit,inputType,displayOrder,isDerived,isEnabled,isPinned,isOnRightYAxis,isInternal) " +
                "VALUES ($id,'$key',NULL,0,'X','X','X',$id,0,1,0,0,0)"
        )
        listOf(
            1 to "WEIGHT", 3 to "BODY_FAT", 4 to "WATER", 5 to "MUSCLE", 6 to "LBM",
            7 to "BONE", 12 to "VISCERAL_FAT", 21 to "BMR", 22 to "TDEE",
            29 to "IMPEDANCE", 30 to "IMPEDANCE_LOW", 31 to "ECW", 32 to "ICW",
            33 to "PROTEIN", 34 to "BCM",
        ).forEach { (id, key) -> type(id, key) }

        // Male, 172 cm, born 1998-06-22 → age 28 at the measurement timestamp.
        sql.execSQL(
            "INSERT INTO User (id,name,icon,birthDate,gender,heightCm,activityLevel,useAssistedWeighing,amputations) " +
                "VALUES (1,'dany','IC_DEFAULT',898560000000,'MALE',172.0,'MILD',0,'')"
        )

        fun value(measurementId: Int, typeId: Int, v: Float) = sql.execSQL(
            "INSERT INTO MeasurementValue (measurementId,typeId,floatValue) VALUES ($measurementId,$typeId,$v)"
        )

        // Measurement 1: raw weight + dual-band impedance, but body-comp written
        // with the WRONG (female) profile.
        sql.execSQL("INSERT INTO Measurement (id,userId,timestamp) VALUES (1,1,1782735000000)")
        value(1, 1, 72.9f)
        value(1, 29, 455f)
        value(1, 30, 410f)
        value(1, 3, 30.08f)   // wrong body fat
        value(1, 4, 51.18f)   // wrong water
        value(1, 6, 50.97f)   // wrong LBM

        // Measurement 2: impedance out of validated range → must stay untouched.
        // Distinct timestamp to satisfy the unique (userId, timestamp) index.
        sql.execSQL("INSERT INTO Measurement (id,userId,timestamp) VALUES (2,1,1782738600000)")
        value(2, 1, 72.9f)
        value(2, 29, 50f)
        value(2, 30, 40f)
        value(2, 3, 99f)      // sentinel; migration must not overwrite

        MIGRATION_15_16.migrate(sql)

        fun read(measurementId: Int, typeId: Int): Float? =
            sql.query("SELECT floatValue FROM MeasurementValue WHERE measurementId=$measurementId AND typeId=$typeId")
                .use { c -> if (c.moveToFirst()) c.getFloat(0) else null }

        // Row 1 re-derived for the male user.
        assertThat(read(1, 3)!!).isWithin(0.2f).of(17.8f)   // BODY_FAT
        assertThat(read(1, 4)!!).isWithin(0.5f).of(60.1f)   // WATER
        // Raw inputs untouched.
        assertThat(read(1, 1)!!).isWithin(0.01f).of(72.9f)  // WEIGHT
        assertThat(read(1, 29)!!).isWithin(0.01f).of(455f)  // IMPEDANCE

        // Row 2 left as-is (NOT_AVAILABLE inputs).
        assertThat(read(2, 3)!!).isWithin(0.01f).of(99f)
    }
}
