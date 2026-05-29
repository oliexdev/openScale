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
}
