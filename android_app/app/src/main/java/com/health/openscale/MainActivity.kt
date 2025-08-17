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
import com.health.openscale.core.data.MeasurementTypeIcon
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
 * The main entry point of the application.
 * This activity hosts the Jetpack Compose UI and initializes essential components
 * like the database, repositories, and ViewModels.
 */
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val appInstance: OpenScaleApp by lazy { application as OpenScaleApp }
    private val userSettingsRepository: UserSettingsRepository by lazy { appInstance.userSettingsRepository }
    private val databaseRepository: DatabaseRepository by lazy { appInstance.databaseRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        LogManager.d(TAG, "Initializing and setting content.")
        enableEdgeToEdge()

        setContent {
            OpenScaleTheme {
                val sharedViewModel: SharedViewModel = viewModel(
                    factory = provideSharedViewModelFactory(application, databaseRepository, userSettingsRepository)
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
    application : Application,
    databaseRepository: DatabaseRepository,
    userSettingsRepository: UserSettingsRepository
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SharedViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SharedViewModel(application, databaseRepository, userSettingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}