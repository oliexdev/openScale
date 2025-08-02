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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.SupportedLanguage
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.utils.LanguageUtil
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.database.UserSettingsRepository
import com.health.openscale.core.database.provideUserSettingsRepository
import com.health.openscale.ui.navigation.AppNavigation
import com.health.openscale.ui.screen.SharedViewModel
import com.health.openscale.ui.theme.OpenScaleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        MeasurementType(key = MeasurementTypeKey.WEIGHT, unit = UnitType.KG, color = 0xFFEF2929.toInt(), icon = "ic_weight", isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BMI, color = 0xFFF57900.toInt(), icon = "ic_bmi", isDerived = true, isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BODY_FAT, color = 0xFFFFCE44.toInt(), icon = "ic_fat", isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WATER, color = 0xFF8AE234.toInt(), icon = "ic_water", isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.MUSCLE, color = 0xFF729FCF.toInt(), icon = "ic_muscle", isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.LBM, color = 0xFFAD7FA8.toInt(), icon = "ic_lbm", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BONE, color = 0xFFE9B96E.toInt(), icon = "ic_bone", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WAIST, color = 0xFF888A85.toInt(), icon = "ic_waist", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WHR, color = 0xFF204A87.toInt(), icon = "ic_whr", isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.WHTR, color = 0xFF204A87.toInt(), icon = "ic_whtr", isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.HIPS, color = 0xFF3465A4.toInt(), icon = "ic_hip", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.VISCERAL_FAT, color = 0xFF4E9A06.toInt(), icon = "ic_visceral_fat", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CHEST, color = 0xFF5C3566.toInt(), icon = "ic_chest", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.THIGH, color = 0xFFC17D11.toInt(), icon = "ic_thigh", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BICEPS, color = 0xFFA40000.toInt(), icon = "ic_biceps", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.NECK, color = 0xFFCE5C00.toInt(), icon = "ic_neck", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER_1, color = 0xFFEDD400.toInt(), icon = "ic_caliper1", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER_2, color = 0xFF73D216.toInt(), icon = "ic_caliper2", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER_3, color = 0xFF11A879.toInt(), icon = "ic_caliper3", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALIPER, color = 0xFF555753.toInt(), icon = "ic_fat_caliper", isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.BMR, color = 0xFFBABDB6.toInt(), icon = "ic_bmr", isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.TDEE, color = 0xFFD3D7CF.toInt(), icon = "ic_tdee", isDerived = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.CALORIES, color = 0xFF2E3436.toInt(), icon = "ic_calories", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.COMMENT, inputType = InputFieldType.TEXT, unit = UnitType.NONE, color = 0xFF729FCF.toInt(), icon = "ic_comment", isPinned = true, isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.DATE, inputType = InputFieldType.DATE, unit = UnitType.NONE, color = 0xFFA40000.toInt(), icon = "ic_date", isEnabled = true),
        MeasurementType(key = MeasurementTypeKey.TIME, inputType = InputFieldType.TIME, unit = UnitType.NONE, color = 0xFF73D216.toInt(), icon = "ic_time", isEnabled = true)
    )
}

/**
 * The main entry point of the application.
 * This activity hosts the Jetpack Compose UI and initializes essential components
 * like the database, repositories, and ViewModels.
 */
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var userSettingsRepository: UserSettingsRepository // Machen Sie es zur Property

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userSettingsRepository = provideUserSettingsRepository(applicationContext)

        // --- LogManager initializing ---
        lifecycleScope.launch {
            val isFileLoggingEnabled = userSettingsRepository.isFileLoggingEnabled.first()
            LogManager.init(applicationContext, isFileLoggingEnabled)
            LogManager.d(TAG, "LogManager initialized. File logging enabled: $isFileLoggingEnabled")
        }

        // --- Language initializing ---
        lifecycleScope.launch {
            userSettingsRepository.appLanguageCode.collectLatest { languageCode ->
                val currentActivityLocale = resources.configuration.locales.get(0).language
                val targetLanguage = languageCode ?: SupportedLanguage.getDefault().code

                LogManager.d(TAG, "Observed language code: $languageCode, Current activity locale: $currentActivityLocale, Target: $targetLanguage")

                if (currentActivityLocale != targetLanguage) {
                    LogManager.i(TAG, "Language changed or first load. Applying locale: $targetLanguage and recreating activity.")
                    LanguageUtil.updateAppLocale(this@MainActivity, targetLanguage)

                    if (!isFinishing) {
                        recreate()
                    }
                } else {
                    if (!isFinishing && !isChangingConfigurations) {
                        initializeAndSetContent()
                    }
                }
            }
        }
    }

    private fun initializeAndSetContent() {
        val db = AppDatabase.getInstance(applicationContext)
        val databaseRepository = DatabaseRepository(
            database = db,
            userDao = db.userDao(),
            measurementDao = db.measurementDao(),
            measurementValueDao = db.measurementValueDao(),
            measurementTypeDao = db.measurementTypeDao()
        )

        // --- Measurement Types initializing ---
        CoroutineScope(Dispatchers.IO).launch {
            val isActuallyFirstStart = userSettingsRepository.isFirstAppStart.first()
            LogManager.d(TAG, "Checking for first app start. isFirstAppStart: $isActuallyFirstStart")
            if (isActuallyFirstStart) {
                LogManager.i(TAG, "First app start detected. Inserting default measurement types...")
                val defaultTypesToInsert = getDefaultMeasurementTypes()
                db.measurementTypeDao().insertAll(defaultTypesToInsert)
                userSettingsRepository.setFirstAppStartCompleted(false)
                LogManager.i(TAG, "Default measurement types inserted and first start marked as completed.")
            } else {
                LogManager.d(TAG, "Not the first app start. Default data should already exist.")
            }
        }

        enableEdgeToEdge()

        setContent {
            OpenScaleTheme {
                val sharedViewModel: SharedViewModel = viewModel(
                    factory = provideSharedViewModelFactory(databaseRepository, userSettingsRepository)
                )

                val view = LocalView.current
                if (!view.isInEditMode) {
                    DisposableEffect(Unit) {
                        val window = this@MainActivity.window
                        val insetsController = WindowCompat.getInsetsController(window, view)
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                        onDispose { }
                    }
                }
                AppNavigation(sharedViewModel)
            }
        }
    }
}

/**
 * Provides a [ViewModelProvider.Factory] for creating [SharedViewModel] instances.
 * This allows for dependency injection into the ViewModel.
 *
 * @param databaseRepository The repository for accessing database operations.
 * @param userSettingsRepository The repository for accessing user preferences.
 * @return A [ViewModelProvider.Factory] for [SharedViewModel].
 */
private fun provideSharedViewModelFactory(
    databaseRepository: DatabaseRepository,
    userSettingsRepository: UserSettingsRepository
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SharedViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SharedViewModel(databaseRepository, userSettingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}