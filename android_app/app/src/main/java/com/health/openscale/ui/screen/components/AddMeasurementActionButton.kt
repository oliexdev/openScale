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
package com.health.openscale.ui.screen.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction

/**
 * Creates and remembers a [TopBarAction] for manually adding a new measurement.
 *
 * This action navigates to the measurement detail screen if a user is selected,
 * otherwise it shows a snackbar prompting the user to select a user first.
 */
@Composable
fun rememberAddMeasurementActionButton(
    sharedViewModel: SharedViewModel,
    navController: NavController
): TopBarAction {
    val context = LocalContext.current
    val selectedUserId by sharedViewModel.selectedUserId.collectAsState()

    // We use remember to ensure the onClick lambda is stable as long as the inputs don't change.
    return remember(selectedUserId, navController) {
        TopBarAction(
            icon = Icons.Default.Add,
            contentDescription = context.getString(R.string.action_add_measurement_desc),
            onClick = {
                if (selectedUserId != null && selectedUserId != 0) {
                    navController.navigate(
                        Routes.measurementDetail(
                            measurementId = null, // null for a new measurement
                            userId = selectedUserId!!
                        )
                    )
                } else {
                    // Show a snackbar if no user is selected
                    sharedViewModel.showSnackbar(
                        message = context.getString(R.string.toast_select_user_first),
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }
}
