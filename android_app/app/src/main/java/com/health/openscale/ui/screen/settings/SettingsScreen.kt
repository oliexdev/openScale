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
package com.health.openscale.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.shared.SharedViewModel

/**
 * Represents an item in the main settings screen.
 *
 * @param label The text label displayed for the settings item.
 * @param icon The [ImageVector] to be displayed as an icon for the item.
 * @param route The navigation route associated with this settings item.
 * @param contentDescription Optional content description for the icon, for accessibility.
 */
data class SettingsItem(
    val label: String,
    val icon: ImageVector,
    val route: String,
    val contentDescription: String? = null
)

/**
 * Composable function for the main settings screen.
 * It displays a list of settings categories that the user can navigate to.
 *
 * @param navController The [NavController] used for navigating to different settings screens.
 * @param sharedViewModel The [SharedViewModel] used to update shared UI elements like the top bar title.
 * @param settingsViewModel The [SettingsViewModel], passed for consistency but not directly used in this screen's primary logic.
 */
@Composable
fun SettingsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel
) {
    // Define strings for titles and content descriptions in the Composable context
    val generalSettingsLabel = stringResource(R.string.settings_item_general)
    val userSettingsLabel = stringResource(R.string.settings_item_user)
    val measurementTypesLabel = stringResource(R.string.settings_item_measurement_types)
    val bluetoothLabel = stringResource(R.string.settings_item_bluetooth)
    val chartSettingsLabel = stringResource(R.string.settings_item_chart)
    val dataManagementLabel = stringResource(R.string.settings_item_data_management)
    val aboutLabel = stringResource(R.string.settings_item_about)

    val items = listOf(
        SettingsItem(
            label = generalSettingsLabel,
            icon = Icons.Default.Tune,
            route = Routes.GENERAL_SETTINGS,
            contentDescription = generalSettingsLabel
        ),
        SettingsItem(
            label = userSettingsLabel,
            icon = Icons.Default.Person,
            route = Routes.USER_SETTINGS,
            contentDescription = userSettingsLabel
        ),
        SettingsItem(
            label = measurementTypesLabel,
            icon = Icons.Default.Edit,
            route = Routes.MEASUREMENT_TYPES,
            contentDescription = measurementTypesLabel
        ),
        SettingsItem(
            label = bluetoothLabel,
            icon = Icons.Filled.Bluetooth,
            route = Routes.BLUETOOTH_SETTINGS,
            contentDescription = bluetoothLabel
        ),
        SettingsItem(
            label = chartSettingsLabel,
            icon = Icons.AutoMirrored.Filled.ShowChart,
            route = Routes.CHART_SETTINGS,
            contentDescription = chartSettingsLabel
        ),
        SettingsItem(
            label = dataManagementLabel,
            icon = Icons.Filled.Storage,
            route = Routes.DATA_MANAGEMENT_SETTINGS,
            contentDescription = dataManagementLabel
        ),
        SettingsItem(
            label = aboutLabel,
            icon = Icons.Default.Info,
            route = Routes.ABOUT_SETTINGS,
            contentDescription = aboutLabel
        )
    )

    val settingsScreenTitle = stringResource(R.string.route_title_settings)
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(settingsScreenTitle)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(all = 8.dp) // Add some overall padding to the column
    ) {
        items.forEach { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth() // Make card take full width
                    .padding(vertical = 8.dp) // Consistent vertical padding
                    .clickable {
                        navController.navigate(item.route)
                    }
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.contentDescription ?: item.label // Fallback CD
                        )
                    }
                )
            }
        }
    }
}
