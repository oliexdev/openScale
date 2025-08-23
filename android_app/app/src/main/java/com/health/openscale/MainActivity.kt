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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.health.openscale.core.data.SupportedLanguage
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.navigation.AppNavigation
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.theme.OpenScaleTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * The main entry point of the application.
 * This activity hosts the Jetpack Compose UI and initializes essential components
 * like the database, repositories, and ViewModels.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var settingsFacade: SettingsFacade

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Language initializing ---
        lifecycleScope.launch {
            settingsFacade.appLanguageCode.collectLatest { languageCode ->
                val currentActivityLocale = resources.configuration.locales.get(0).language
                val targetLanguage = languageCode ?: SupportedLanguage.getDefault().code

                LogManager.d(TAG, "Observed language code: $languageCode, Current activity locale: $currentActivityLocale, Target: $targetLanguage")

                if (currentActivityLocale != targetLanguage) {
                    LogManager.i(TAG, "Language changed or first load. Applying locale: $targetLanguage and recreating activity.")
                    LocaleUtils.updateAppLocale(this@MainActivity, targetLanguage)

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
                val sharedViewModel: SharedViewModel = hiltViewModel()

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