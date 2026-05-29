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
package com.health.openscale.core.usecase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests [ReminderUseCase.isReminderNeeded] — the "should we nudge the user?" decision — against
 * in-memory Room + a real DataStore-backed SettingsFacade (Robolectric, no device).
 *
 * Note: the "already weighed today?" branch relies on `android.text.format.DateUtils.isToday`,
 * which is not reliably implemented under Robolectric, so those date-sensitive cases are left to
 * on-device testing. The enable/selected-user/empty-history gates below are fully covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderUseCaseTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var settings: SettingsFacadeImpl
    private lateinit var reminder: ReminderUseCase
    private var userId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(app.cacheDir, "reminder-${System.nanoTime()}.preferences_pb"),
        )
        reminder = RoomTestSupport.reminderUseCaseFor(app, repo, settings)
        userId = repo.insertUser(
            User(
                name = "u", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
            )
        ).toInt()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reminderDisabled_isNotNeeded() = runBlocking {
        // default: reminderEnabled == false
        assertThat(reminder.isReminderNeeded()).isFalse()
    }

    @Test
    fun enabledButNoSelectedUser_isNotNeeded() = runBlocking {
        settings.setReminderEnabled(true)
        // currentUserId stays null
        assertThat(reminder.isReminderNeeded()).isFalse()
    }

    @Test
    fun enabledWithUserButNoMeasurements_isNeeded() = runBlocking {
        settings.setReminderEnabled(true)
        settings.setCurrentUserId(userId)
        assertThat(reminder.isReminderNeeded()).isTrue()
    }
}
