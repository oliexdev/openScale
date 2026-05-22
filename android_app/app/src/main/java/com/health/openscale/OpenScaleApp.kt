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
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType
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

/**
 * Generates a default list of measurement types available in the application,
 * resolving names from string resources.
 * These types are intended for insertion into the database on the first app start.
 *
 * @param context The context used to access string resources.
 * @return A list of [MeasurementType] objects.
 */
fun getDefaultMeasurementTypes(): List<MeasurementType> {
    return listOf(
        MeasurementType(key = MeasurementTypeKey.WEIGHT, unit = UnitType.KG, color = 0xFF7E57C2.toInt(), icon = MeasurementTypeIcon.IC_WEIGHT, isPinned = true, isEnabled = true, isOnRightYAxis = true),
        MeasurementType(key = MeasurementTypeKey.BMI, unit = UnitType.NONE, color = 0xFFFFCA28.toInt(), icon = MeasurementTypeIcon.IC_BMI, isDerived = true, isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BODY_FAT, unit = UnitType.PERCENT, color = 0xFFEF5350.toInt(), icon = MeasurementTypeIcon.IC_BODY_FAT, isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WATER, unit = UnitType.PERCENT, color = 0xFF29B6F6.toInt(), icon = MeasurementTypeIcon.IC_WATER, isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.MUSCLE, unit = UnitType.PERCENT, color = 0xFF66BB6A.toInt(), icon = MeasurementTypeIcon.IC_MUSCLE, isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.LBM, unit = UnitType.KG, color = 0xFF4DBAC0.toInt(), icon = MeasurementTypeIcon.IC_LBM, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BONE, unit = UnitType.KG, color = 0xFFBDBDBD.toInt(), icon = MeasurementTypeIcon.IC_BONE, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WAIST, unit = UnitType.CM, color = 0xFF78909C.toInt(), icon = MeasurementTypeIcon.IC_WAIST, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WHR, unit = UnitType.NONE, color = 0xFFFFA726.toInt(), icon = MeasurementTypeIcon.IC_WHR, isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WHTR, unit = UnitType.NONE, color = 0xFFFF7043.toInt(), icon = MeasurementTypeIcon.IC_WHTR, isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.HIPS, unit = UnitType.CM, color = 0xFF5C6BC0.toInt(), icon = MeasurementTypeIcon.IC_HIPS, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.VISCERAL_FAT, unit = UnitType.NONE, color = 0xFFD84315.toInt(), icon = MeasurementTypeIcon.IC_VISCERAL_FAT, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CHEST, unit = UnitType.CM, color = 0xFF8E24AA.toInt(), icon = MeasurementTypeIcon.IC_CHEST, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.THIGH, unit = UnitType.CM, color = 0xFFA1887F.toInt(), icon = MeasurementTypeIcon.IC_THIGH, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BICEPS, unit = UnitType.CM, color = 0xFFEC407A.toInt(), icon = MeasurementTypeIcon.IC_BICEPS, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.NECK, unit = UnitType.CM, color = 0xFFB0BEC5.toInt(), icon = MeasurementTypeIcon.IC_NECK, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER_1, unit = UnitType.CM, color = 0xFFFFF59D.toInt(), icon = MeasurementTypeIcon.IC_CALIPER1, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER_2, unit = UnitType.CM, color = 0xFFFFE082.toInt(), icon = MeasurementTypeIcon.IC_CALIPER2, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER_3, unit = UnitType.CM, color = 0xFFFFCC80.toInt(), icon = MeasurementTypeIcon.IC_CALIPER3, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER, unit = UnitType.PERCENT, color = 0xFFFB8C00.toInt(), icon = MeasurementTypeIcon.IC_FAT_CALIPER, isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BMR, unit = UnitType.KCAL, color = 0xFFAB47BC.toInt(), icon = MeasurementTypeIcon.IC_BMR, isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.TDEE, unit = UnitType.KCAL, color = 0xFF26A69A.toInt(), icon = MeasurementTypeIcon.IC_TDEE, isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.HEART_RATE, inputType = InputFieldType.INT, unit = UnitType.BPM, color = 0xFFE91E63.toInt(), icon = MeasurementTypeIcon.IC_M_HEART_RATE, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALORIES, unit = UnitType.KCAL, color = 0xFF4CAF50.toInt(), icon = MeasurementTypeIcon.IC_CALORIES, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.COMMENT, inputType = InputFieldType.TEXT, unit = UnitType.NONE, color = 0xFFE0E0E0.toInt(), icon = MeasurementTypeIcon.IC_COMMENT, isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.DATE, inputType = InputFieldType.DATE, unit = UnitType.NONE, color = 0xFF9E9E9E.toInt(), icon = MeasurementTypeIcon.IC_DATE, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.TIME, inputType = InputFieldType.TIME, unit = UnitType.NONE, color = 0xFF757575.toInt(), icon = MeasurementTypeIcon.IC_TIME, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.USER, inputType = InputFieldType.USER, unit = UnitType.NONE, color = 0xFF90A4AE.toInt(), icon = MeasurementTypeIcon.IC_USER, isEnabled = true)
    )
}

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
                    databaseRepository.insertAllMeasurementTypes(getDefaultMeasurementTypes())
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