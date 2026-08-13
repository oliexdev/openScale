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
package com.health.openscale.ui.screen.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.User
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel

/**
 * Global dialog that asks the user to pick a reference user for assisted weighing
 * (e.g. weighing a baby or pet). Shown app-wide above the navigation host.
 */
@Composable
fun AssistedWeighingDialog(
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
    allUsers: List<User>
) {
    val pendingAssistedUser by sharedViewModel.pendingAssistedWeighingUser.collectAsState()

    pendingAssistedUser?.let { currentTargetUser ->
        // Filter users who can serve as a reference (not the user himself, and not someone also using assisted weighing)
        val availableReferenceUsers = allUsers.filter { user ->
            user.id != currentTargetUser.id && !user.useAssistedWeighing
        }

        if (availableReferenceUsers.isEmpty()) {
            // No one else is there to be a reference
            LaunchedEffect(currentTargetUser) {
                sharedViewModel.showSnackbar(messageResId = R.string.error_no_reference_users_available)
                sharedViewModel.setPendingAssistedWeighingUser(null)
                sharedViewModel.setPendingReferenceUserForBle(null)
            }
        } else {
            UserInputDialog(
                title = stringResource(R.string.dialog_title_select_reference_user_for, currentTargetUser.name),
                users = availableReferenceUsers,
                initialSelectedId = availableReferenceUsers.firstOrNull()?.id,
                measurementIcon = MeasurementTypeIcon.IC_USER,
                iconBackgroundColor = MaterialTheme.colorScheme.primaryContainer,
                onDismiss = {
                    sharedViewModel.setPendingAssistedWeighingUser(null)
                    sharedViewModel.setPendingReferenceUserForBle(null)
                },
                onConfirm = { selectedUserId ->
                    val selectedReferenceUser = availableReferenceUsers.find { it.id == selectedUserId }
                    if (selectedReferenceUser != null) {
                        // 1. Set the reference user in the logic
                        sharedViewModel.setPendingReferenceUserForBle(selectedReferenceUser)

                        // 2. Trigger connection
                        bluetoothViewModel.connectToSavedDevice()
                    } else {
                        sharedViewModel.setPendingReferenceUserForBle(null)
                    }
                    // Close the dialog
                    sharedViewModel.setPendingAssistedWeighingUser(null)
                }
            )
        }
    }
}
