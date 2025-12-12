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

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.navigation.AppNavigation
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.theme.OpenScaleTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


/**
 * The main entry point of the application.
 * This activity hosts the Jetpack Compose UI and initializes essential components
 * like the database, repositories, and ViewModels.
 * The language handling follows the official Android guide for per-app language preferences.
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
        enableEdgeToEdge()

        setContent {
            OpenScaleTheme {
                // For APIs before Android 13 (Tiramisu), we need to manually
                // listen for language changes and recreate the activity.
                // For API 33+, the system handles this automatically via LocaleManager.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    val languageCode by settingsFacade.appLanguageCode.collectAsStateWithLifecycle(initialValue = null)

                    // This effect runs when languageCode changes.
                    LaunchedEffect(languageCode) {
                        val currentActivityLocale = this@MainActivity.resources.configuration.locales[0]

                        // Only recreate if the language has been set and differs from the current activity locale.
                        if (languageCode != null && currentActivityLocale.language != languageCode) {
                            LogManager.i(TAG, "Language setting changed to '$languageCode' on pre-Tiramisu device. Recreating activity.")
                            recreate()
                        }
                    }
                }

                // The main UI of the app.
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
