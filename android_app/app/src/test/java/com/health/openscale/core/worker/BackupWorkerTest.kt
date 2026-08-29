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
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.AutoBackupError
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs [BackupWorker.doWork] on the JVM (Robolectric + WorkManager test harness), with real
 * dependencies (in-memory Room repository + DataStore-backed settings). Verifies the common
 * "auto-backup disabled" path returns success without attempting any file IO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun doWork_returnsSuccess_whenAutoBackupDisabled() = runBlocking {
        val settings = newSettings() // auto-backup defaults to disabled

        val result = newWorker(settings).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    /**
     * A failing attempt must record why it failed and must not touch the last *successful*
     * timestamp — otherwise the UI degrades to "Last backup: Never" and the real cause is lost.
     */
    @Test
    fun doWork_recordsError_andKeepsLastSuccess_whenLocationNotWritable() = runBlocking {
        val settings = newSettings()
        settings.setAutoBackupEnabledGlobally(true)
        settings.setAutoBackupLocationUri(
            "content://com.android.externalstorage.documents/tree/primary%3Adoes-not-exist"
        )
        settings.setAutoBackupLastSuccessfulTimestamp(PREVIOUS_SUCCESS)

        val result = newWorker(settings).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        assertThat(settings.autoBackupLastError.first()).isEqualTo(AutoBackupError.LOCATION_INACCESSIBLE)
        assertThat(settings.autoBackupLastErrorTimestamp.first()).isGreaterThan(0L)
        assertThat(settings.autoBackupLastSuccessfulTimestamp.first()).isEqualTo(PREVIOUS_SUCCESS)
    }

    private fun newSettings(): SettingsFacadeImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "bw-${System.nanoTime()}.preferences_pb") },
        )
        return SettingsFacadeImpl(dataStore)
    }

    private fun newWorker(settings: SettingsFacadeImpl): BackupWorker {
        db = RoomTestSupport.inMemory(context)
        val repository = RoomTestSupport.repositoryFor(db)
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = BackupWorker(appContext, workerParameters, settings, repository)
            })
            .build()
    }

    private companion object {
        const val PREVIOUS_SUCCESS = 1_700_000_000_000L
    }
}
