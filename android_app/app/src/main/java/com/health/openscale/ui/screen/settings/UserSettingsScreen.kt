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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.User
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.screen.dialog.DeleteConfirmationDialog
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.launch

/**
 * Composable screen that displays a list of users.
 *
 * This screen allows users to view existing users, initiate editing of a user,
 * or add a new user. It observes the list of users from [SharedViewModel]
 * and uses [SettingsViewModel] for user deletion.
 *
 * @param sharedViewModel The ViewModel shared across different screens, used for top bar configuration and accessing the user list.
 * @param settingsViewModel The ViewModel responsible for user-related settings operations, like deleting a user.
 * @param onEditUser Callback invoked when the user taps the edit button for a user or the add user button.
 *                   It receives the user's ID for editing, or null for adding a new user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel,
    onEditUser: (userId: Int?) -> Unit
) {
    val users by sharedViewModel.allUsers.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Pre-load strings for LaunchedEffect
    val usersTitle = stringResource(id = R.string.user_settings_title)
    val editActionContentDescription = stringResource(id = R.string.user_settings_content_description_edit)
    val deleteActionContentDescription = stringResource(id = R.string.user_settings_content_description_delete)
    val addUserContentDescription = stringResource(id = R.string.user_settings_content_description_add_user)
    var userToDelete by remember { mutableStateOf<User?>(null) }

    userToDelete?.let { user ->
        DeleteConfirmationDialog(
            onDismissRequest = { userToDelete = null },
            onConfirm = {
                coroutineScope.launch {
                    settingsViewModel.deleteUser(user)
                }
            },
            title = stringResource(R.string.dialog_title_delete_user, user.name),
            text = stringResource(R.string.dialog_text_delete_user)
        )
    }

    LaunchedEffect(Unit, usersTitle) { // Add usersTitle to keys to re-run if it could change (e.g. language change)
        sharedViewModel.setTopBarTitle(usersTitle) // "Users"
        sharedViewModel.setTopBarAction(
            TopBarAction(
                icon = Icons.Default.Add,
                onClick = {
                    onEditUser(null) // null indicates adding a new user
                },
                contentDescription = addUserContentDescription
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .padding(16.dp)
    ) {
        items(users) { user ->
            // Calculate age. This will be recalculated if user.birthDate changes.
            val age = remember(user.birthDate) {
                CalculationUtils.ageOn(System.currentTimeMillis(), user.birthDate)
            }

            ListItem(
                leadingContent = {
                    RoundMeasurementIcon(
                        icon = user.icon.resource,
                        size = 24.dp,
                        backgroundTint = Color.Transparent,
                        iconTint = LocalContentColor.current
                    )
                },
                headlineContent = { Text(user.name) },
                supportingContent = {
                    Text(
                        stringResource(
                            id = R.string.user_settings_item_details_conditional,
                            age,
                            user.gender.getDisplayName(LocalContext.current)
                        )
                    )
                }
                ,
                trailingContent = {
                    Row {
                        IconButton(onClick = { onEditUser(user.id) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = editActionContentDescription // "Edit"
                            )
                        }
                        IconButton(
                            onClick = {
                                userToDelete = user
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = deleteActionContentDescription
                            )
                        }
                    }
                }
            )
        }
    }
}
