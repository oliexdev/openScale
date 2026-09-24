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
package com.health.openscale.ui.shared

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.usecase.GoalProgress
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.usecase.MeasurementTypeCrudUseCases
import com.health.openscale.core.usecase.SyncUseCases
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
 * Integration tests for [SharedViewModel] against the REAL facades wired to in-memory Room +
 * a test DataStore (Robolectric, no device, no production change). Uses an unconfined Main
 * dispatcher so the `stateIn` pipelines run, and awaits the Room/DataStore-backed flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var vm: SharedViewModel

    private fun user(name: String) = User(
        name = name, birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
        activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
    )

    @Before
    fun setUp() {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = RoomTestSupport.settingsFacadeFor(
            scope, File(app.cacheDir, "shared-${System.nanoTime()}.preferences_pb")
        )
        val facades = RoomTestSupport.facadesFor(app, repo, settings)
        val sync = SyncUseCases(app, MeasurementTypeCrudUseCases(repo, ApplicationProvider.getApplicationContext()))
        vm = SharedViewModel(
            facades.userFacade, facades.measurementFacade, facades.dataManagementFacade, settings, sync,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun allUsers_reflectsSeededUsers() {
        runBlocking {
            repo.insertUser(user("Alice"))
            repo.insertUser(user("Bob"))

            val users = withTimeout(5_000) { vm.allUsers.first { it.size == 2 } }
            assertThat(users.map { it.name }).containsExactly("Alice", "Bob")
        }
    }

    @Test
    fun selectUser_updatesSelectedUser() {
        runBlocking {
            repo.insertUser(user("Alice"))
            val idBob = repo.insertUser(user("Bob")).toInt()

            vm.selectUser(idBob)

            val selected = withTimeout(5_000) { vm.selectedUser.first { it?.id == idBob } }
            assertThat(selected?.name).isEqualTo("Bob")
            assertThat(vm.selectedUserId.first { it == idBob }).isEqualTo(idBob)
        }
    }

    @Test
    fun screenFlow_emitsSuccessWithAggregatedData_forSelectedUser() {
        runBlocking {
            repo.insertAllMeasurementTypes(MeasurementType.seedRows())
            val uid = repo.insertUser(user("Alice")).toInt()
            val weightTypeId = repo.getAllMeasurementTypes().first()
                .first { it.key == MeasurementType.WEIGHT }.id
            val measurementId = repo.insertMeasurement(
                Measurement(userId = uid, timestamp = System.currentTimeMillis())
            ).toInt()
            repo.insertMeasurementValue(
                MeasurementValue(measurementId = measurementId, typeId = weightTypeId, floatValue = 72.5f)
            )

            vm.selectUser(uid)

            // screenFlow emits a transient Success(emptyList()) while selectedUserId is still null
            // (before selectUser propagates); wait for the first Success that actually carries data.
            // The time range defaults to ALL_DAYS, so the inserted measurement is always in range.
            val state = withTimeout(5_000) {
                vm.screenFlow("overview")
                    .first { it is SharedViewModel.UiState.Success && it.data.isNotEmpty() }
            }
            val data = (state as SharedViewModel.UiState.Success).data
            assertThat(data).isNotEmpty()
        }
    }

    /**
     * Seeds one user with weight measurements [daysAgo] days apart and a goal carrying a start
     * point at the older of the two. Returns the user id.
     */
    private suspend fun seedUserWithStartPoint(
        startValue: Float = 85.4f,
        currentValue: Float = 82.0f,
        elapsedDays: Long = 30,
        goalValue: Float = 78f,
    ): Int {
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        val uid = repo.insertUser(user("Alice")).toInt()
        val weightTypeId = repo.getAllMeasurementTypes().first()
            .first { it.key == MeasurementType.WEIGHT }.id

        val dayMillis = 24L * 60 * 60 * 1000
        val startMillis = System.currentTimeMillis() - elapsedDays * dayMillis
        listOf(startMillis to startValue, System.currentTimeMillis() to currentValue)
            .forEach { (ts, value) ->
                val id = repo.insertMeasurement(Measurement(userId = uid, timestamp = ts)).toInt()
                repo.insertMeasurementValue(
                    MeasurementValue(measurementId = id, typeId = weightTypeId, floatValue = value)
                )
            }

        repo.insertUserGoal(
            UserGoals(
                userId = uid,
                measurementTypeId = weightTypeId,
                goalValue = goalValue,
                startDate = startMillis,
            )
        )
        return uid
    }

    @Test
    fun goalProgressFlow_isEmpty_whileTheUserKeepsNoGoals() {
        runBlocking {
            repo.insertAllMeasurementTypes(MeasurementType.seedRows())
            val uid = repo.insertUser(user("Alice")).toInt()
            vm.selectUser(uid)

            val state = withTimeout(5_000) {
                vm.goalProgressFlow.first { it is SharedViewModel.UiState.Success }
            }
            assertThat((state as SharedViewModel.UiState.Success).data).isEmpty()
        }
    }

    @Test
    fun goalProgressFlow_reportsProgressForTheStartedGoal() {
        runBlocking {
            val uid = seedUserWithStartPoint()
            vm.selectUser(uid)

            val progress = withTimeout(5_000) { awaitProgress() }.single()

            assertThat(progress.startDate).isEqualTo(progress.today.minusDays(30))
            assertThat(progress.delta).isWithin(0.01f).of(-3.4f)
            assertThat(progress.currentValue).isWithin(0.01f).of(82.0f)
            assertThat(progress.goalFraction!!).isWithin(0.01f).of(3.4f / 7.4f)
        }
    }

    /**
     * The regression that matters: each window is [start, now] by definition, so narrowing a
     * screen's time filter must not shrink it.
     */
    @Test
    fun goalProgressFlow_isUnaffectedByTheActiveTimeRangeFilter() {
        runBlocking {
            val uid = seedUserWithStartPoint()
            vm.selectUser(uid)

            val before = withTimeout(5_000) { awaitProgress() }.single()

            vm.saveSetting("overview_screen_time_range", TimeRangeFilter.LAST_7_DAYS.name)

            val after = withTimeout(5_000) { awaitProgress() }.single()

            assertThat(after.startDate).isEqualTo(before.startDate)
            assertThat(after.delta).isWithin(0.01f).of(before.delta)
        }
    }

    private suspend fun awaitProgress(): List<GoalProgress> =
        vm.goalProgressFlow
            .first { it is SharedViewModel.UiState.Success && it.data.isNotEmpty() }
            .let { (it as SharedViewModel.UiState.Success).data }
}
