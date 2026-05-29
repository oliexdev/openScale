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
package com.health.openscale.core.worker

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.database.AppDatabase
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
 * Runs [ReminderWorker.doWork] on the JVM (Robolectric + WorkManager test harness) with real
 * dependencies. With default settings the reminder is disabled, so doWork must finish with
 * success after a no-op reschedule (cancel) — without crashing on notifications/WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun doWork_returnsSuccess_whenReminderDisabledByDefault() = runBlocking {
        db = RoomTestSupport.inMemory(context)
        val repo = RoomTestSupport.repositoryFor(db)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "reminder-${System.nanoTime()}.preferences_pb") },
        )
        val settings = SettingsFacadeImpl(dataStore)
        val reminderUseCase = RoomTestSupport.reminderUseCaseFor(context as android.app.Application, repo, settings)

        val worker = TestListenableWorkerBuilder<ReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ReminderWorker(appContext, workerParameters, settings, reminderUseCase)
            })
            .build()

        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    }
}
