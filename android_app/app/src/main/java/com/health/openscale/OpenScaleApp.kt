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
package com.health.openscale

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltAndroidApp
class OpenScaleApp : Application(), Configuration.Provider {
    companion object {
        private const val TAG = "OpenScaleApp"
    }
    @Inject
    lateinit var settingsFacade: SettingsFacade
    @Inject
    lateinit var databaseRepository: DatabaseRepository
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        initializeLogging()
        initializeDefaultData()
    }

    private fun initializeLogging() {
        applicationScope.launch {
            val isFileLoggingEnabled = try {
                settingsFacade.isFileLoggingEnabled.first()
            } catch (e: Exception) {
                // Log to standard Android Log if our LogManager or DataStore fails early
                Log.e(TAG, "Failed to retrieve isFileLoggingEnabled setting", e)
                false
            }
            LogManager.init(applicationContext, isFileLoggingEnabled)
            LogManager.i(TAG, "LogManager initialized. File logging enabled: $isFileLoggingEnabled")
        }
    }

    private fun initializeDefaultData() {
        applicationScope.launch(Dispatchers.IO) { // Use IO dispatcher for database operations
            try {
                val isFirstActualStart = settingsFacade.isFirstAppStart.first()
                LogManager.d(TAG, "Checking for first app start. isFirstAppStart: $isFirstActualStart")

                if (isFirstActualStart) {
                    LogManager.i(TAG, "First app start detected. Inserting default measurement types...")
                    databaseRepository.insertAllMeasurementTypes(MeasurementType.seedRows())
                    settingsFacade.setFirstAppStartCompleted(false)
                    LogManager.i(TAG, "Default measurement types inserted and first start marked as completed.")
                } else {
                    LogManager.d(TAG, "Not the first app start. Default data should already exist.")
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error during first-start data initialization", e)
            }
        }
    }


    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
}