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
package com.health.openscale.ui.screen.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.getDefaultMeasurementTypes
import com.health.openscale.testutil.MainDispatcherRule
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Integration tests for [SettingsViewModel] against the REAL facades + in-memory Room
 * (Robolectric, no device, no production change). Asserts persisted DB effects of the
 * CRUD operations the screen triggers. SAF/CSV/WorkManager paths are not exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var vm: SettingsViewModel

    private fun user(name: String) = User(
        name = name, birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
        activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
    )

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(getDefaultMeasurementTypes())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = RoomTestSupport.settingsFacadeFor(
            scope, File(app.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
        )
        val facades = RoomTestSupport.facadesFor(app, repo, settings)
        vm = SettingsViewModel(
            app,
            facades.userFacade,
            facades.dataManagementFacade,
            facades.measurementFacade,
            RoomTestSupport.reminderUseCaseFor(app, repo, settings),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addUser_persistsToDatabase() = runBlocking {
        val id = vm.addUser(user("Alice"))
        assertThat(id).isGreaterThan(0L)
        assertThat(repo.getAllUsers().first().map { it.name }).contains("Alice")
    }

    @Test
    fun deleteUser_removesFromDatabase() = runBlocking {
        val id = vm.addUser(user("Alice")).toInt()
        val stored = repo.getAllUsers().first().first { it.id == id }

        // deleteUser is fire-and-forget (runs on viewModelScope); await the DB reflecting the delete.
        vm.deleteUser(stored, reseatSelection = false)

        withTimeout(5_000) { repo.getAllUsers().first { users -> users.none { it.id == id } } }
        assertThat(repo.getAllUsers().first().any { it.id == id }).isFalse()
    }

    @Test
    fun createUserWithGoals_persistsUserAndGoals() = runBlocking {
        repo.insertAllMeasurementTypes(getDefaultMeasurementTypes())
        val typeId = repo.getAllMeasurementTypes().first().first().id

        vm.createUserWithGoals(
            user("Alice"),
            listOf(UserGoals(userId = 0, measurementTypeId = typeId, goalValue = 80f)),
        )

        val uid = withTimeout(5_000) {
            repo.getAllUsers().first { users -> users.any { it.name == "Alice" } }
        }.first { it.name == "Alice" }.id
        val goals = withTimeout(5_000) { repo.getAllGoalsForUser(uid).first { it.isNotEmpty() } }
        assertThat(goals.map { it.measurementTypeId }).containsExactly(typeId)
        assertThat(goals.first().goalValue).isEqualTo(80f)
    }

    @Test
    fun updateUserWithGoals_reconcilesGoals() = runBlocking {
        repo.insertAllMeasurementTypes(getDefaultMeasurementTypes())
        val types = repo.getAllMeasurementTypes().first()
        val keptType = types[0].id
        val removedType = types[1].id

        val uid = vm.addUser(user("Alice")).toInt()
        repo.insertUserGoal(UserGoals(userId = uid, measurementTypeId = keptType, goalValue = 70f))
        repo.insertUserGoal(UserGoals(userId = uid, measurementTypeId = removedType, goalValue = 90f))
        val stored = repo.getAllUsers().first().first { it.id == uid }
        val original = repo.getAllGoalsForUser(uid).first()

        // Keep keptType (changed 70 → 75), drop removedType.
        vm.updateUserWithGoals(
            user = stored,
            pendingGoals = listOf(UserGoals(userId = uid, measurementTypeId = keptType, goalValue = 75f)),
            originalGoals = original,
        )

        // Wait for the FINAL reconciled state (removed gone AND kept updated to 75), not an
        // intermediate emission between the delete and the update.
        val after = withTimeout(5_000) {
            repo.getAllGoalsForUser(uid).first { goals ->
                goals.none { it.measurementTypeId == removedType } &&
                    goals.any { it.measurementTypeId == keptType && it.goalValue == 75f }
            }
        }
        assertThat(after.map { it.measurementTypeId }).containsExactly(keptType)
        assertThat(after.first { it.measurementTypeId == keptType }.goalValue).isEqualTo(75f)
    }

    @Test
    fun addMeasurementType_persistsToDatabase() = runBlocking {
        val before = repo.getAllMeasurementTypes().first().size

        vm.addMeasurementType(
            MeasurementType(
                key = MeasurementTypeKey.CUSTOM,
                name = "MyMetric",
                inputType = InputFieldType.FLOAT,
                unit = UnitType.NONE,
                isEnabled = true,
            )
        ).join()

        val after = repo.getAllMeasurementTypes().first()
        assertThat(after.size).isEqualTo(before + 1)
        assertThat(after.any { it.name == "MyMetric" }).isTrue()
    }

    @Test
    fun togglePinnedState_pinsAnUnpinnedType() {
        runBlocking {
            val unpinnedId = repo.getAllMeasurementTypes().first().first { !it.isPinned }.id

            vm.togglePinnedState(listOf(unpinnedId))

            val types = withTimeout(5_000) {
                repo.getAllMeasurementTypes().first { list -> list.first { it.id == unpinnedId }.isPinned }
            }
            assertThat(types.first { it.id == unpinnedId }.isPinned).isTrue()
        }
    }
}
