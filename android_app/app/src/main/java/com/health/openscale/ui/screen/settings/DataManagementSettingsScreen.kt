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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.User


/**
 * Represents items in the data management settings list.
 * Can be an action item or a header.
 */
sealed class DataManagementSettingListItem {
    /**
     * Represents an actionable item in the settings list.
     * @param label The text label for the item.
     * @param icon The icon for the item.
     * @param onClick The lambda to execute when the item is clicked.
     * @param enabled Whether the item is clickable and interactive.
     * @param isDestructive If true, indicates a potentially dangerous action, often styled differently (e.g., with error colors).
     * @param isLoading If true, shows a loading indicator instead of the icon, and the item might be disabled.
     */
    data class ActionItem(
        val label: String,
        val icon: ImageVector,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
        val isDestructive: Boolean = false,
        val isLoading: Boolean = false
    ) : DataManagementSettingListItem()
}

/**
 * Composable screen for managing application data, including import/export of measurements,
 * database backup/restore, and deletion of user data or the entire database.
 *
 * @param navController The NavController for navigation purposes (currently not used in this specific screen's internal logic but good for context).
 * @param settingsViewModel The ViewModel that handles the business logic for data management operations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementSettingsScreen(
    navController: NavController, // Not directly used in this composable's logic but passed for potential future use or consistency
    settingsViewModel: SettingsViewModel
) {
    val users by settingsViewModel.allUsers.collectAsState()
    val showUserSelectionDialogForExport by settingsViewModel.showUserSelectionDialogForExport.collectAsState()
    val showUserSelectionDialogForImport by settingsViewModel.showUserSelectionDialogForImport.collectAsState()

    val isLoadingExport by settingsViewModel.isLoadingExport.collectAsState()
    val isLoadingImport by settingsViewModel.isLoadingImport.collectAsState()
    val isLoadingDeletion by settingsViewModel.isLoadingDeletion.collectAsState()
    val isLoadingBackup by settingsViewModel.isLoadingBackup.collectAsState()
    val isLoadingRestore by settingsViewModel.isLoadingRestore.collectAsState()
    val isLoadingEntireDatabaseDeletion by settingsViewModel.isLoadingEntireDatabaseDeletion.collectAsState()
    val showDeleteEntireDatabaseConfirmationDialog by settingsViewModel.showDeleteEntireDatabaseConfirmationDialog.collectAsState()

    val isAnyOperationLoading = isLoadingExport || isLoadingImport || isLoadingDeletion ||
            isLoadingBackup || isLoadingRestore || isLoadingEntireDatabaseDeletion

    // States for the deletion process
    val showUserSelectionDialogForDelete by settingsViewModel.showUserSelectionDialogForDelete.collectAsState()
    val userPendingDeletion by settingsViewModel.userPendingDeletion.collectAsState()
    val showDeleteConfirmationDialog by settingsViewModel.showDeleteConfirmationDialog.collectAsState()
    var showRestoreConfirmationDialog by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    var activeSafActionUserId by remember { mutableStateOf<Int?>(null) } // Stores user ID for SAF actions like CSV export/import

    // --- ActivityResultLauncher for CSV Export ---
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                activeSafActionUserId?.let { userId ->
                    settingsViewModel.performCsvExport(userId, fileUri, context.contentResolver)
                    activeSafActionUserId = null // Reset after use
                }
            }
        }
    )

    // --- ActivityResultLauncher for CSV Import ---
    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                activeSafActionUserId?.let { userId ->
                    settingsViewModel.performCsvImport(userId, fileUri, context.contentResolver)
                    activeSafActionUserId = null // Reset after use
                }
            }
        }
    )

    // --- ActivityResultLauncher for DB Backup ---
    val backupDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"), // Allow any file type as we suggest the name
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                // activeSafActionUserId is not relevant here as it's a global backup.
                settingsViewModel.performDatabaseBackup(fileUri, context.applicationContext, context.contentResolver)
            }
        }
    )

    // --- ActivityResultLauncher for DB Restore ---
    val restoreDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                // activeSafActionUserId is not relevant here.
                settingsViewModel.performDatabaseRestore(fileUri, context.applicationContext, context.contentResolver)
            }
        }
    )

    // Collect SAF events from ViewModel to trigger file pickers
    LaunchedEffect(key1 = settingsViewModel) {
        settingsViewModel.safEvent.collect { event ->
            when (event) {
                is SafEvent.RequestCreateFile -> {
                    activeSafActionUserId = event.userId // Retain for CSV export if applicable
                    if (event.actionId == SettingsViewModel.ACTION_ID_BACKUP_DB) {
                        backupDbLauncher.launch(event.suggestedName)
                    } else { // Assumption: other CreateFile is CSV export
                        exportCsvLauncher.launch(event.suggestedName)
                    }
                }
                is SafEvent.RequestOpenFile -> {
                    activeSafActionUserId = event.userId // Retain for CSV import if applicable
                    if (event.actionId == SettingsViewModel.ACTION_ID_RESTORE_DB) {
                        // For DB Restore, we might expect specific MIME types,
                        // e.g., "application/octet-stream" or "application/x-sqlite3" for .db,
                        // or "application/zip" if using ZIPs.
                        // Using a general type for now:
                        restoreDbLauncher.launch(arrayOf("*/*"))
                    } else { // Assumption: other OpenFile is CSV import
                        val mimeTypes = arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "application/csv",
                            "text/plain"
                        )
                        importCsvLauncher.launch(mimeTypes)
                    }
                }
            }
        }
    }

    val regularDataManagementItems = buildList {
        add(
            DataManagementSettingListItem.ActionItem(
                label = stringResource(R.string.settings_export_measurements_csv),
                icon = Icons.Default.FileDownload,
                onClick = {
                    if (!isAnyOperationLoading) settingsViewModel.startExportProcess()
                },
                enabled = users.isNotEmpty() && !isAnyOperationLoading,
                isLoading = isLoadingExport
            )
        )
        add(
            DataManagementSettingListItem.ActionItem(
                label = stringResource(R.string.settings_import_measurements_csv),
                icon = Icons.Default.FileUpload,
                onClick = {
                    if (!isAnyOperationLoading) settingsViewModel.startImportProcess()
                },
                enabled = users.isNotEmpty() && !isAnyOperationLoading,
                isLoading = isLoadingImport
            )
        )
        add(
            DataManagementSettingListItem.ActionItem(
                label = stringResource(R.string.settings_backup_database),
                icon = Icons.Default.CloudDownload,
                onClick = {
                    if (!isAnyOperationLoading) settingsViewModel.startDatabaseBackup()
                },
                enabled = !isAnyOperationLoading, // Always enabled if no other operation is loading
                isLoading = isLoadingBackup
            )
        )
        add(
            DataManagementSettingListItem.ActionItem(
                label = stringResource(R.string.settings_restore_database),
                icon = Icons.Filled.CloudUpload,
                onClick = {
                    if (!isAnyOperationLoading) showRestoreConfirmationDialog = true
                },
                enabled = !isAnyOperationLoading, // Always enabled if no other operation is loading
                isLoading = isLoadingRestore
            )
        )
    }

    val destructiveDataManagementItems = buildList {
        add(
            DataManagementSettingListItem.ActionItem(
                label = stringResource(R.string.settings_delete_all_measurement_data),
                icon = Icons.Default.DeleteForever,
                onClick = {
                    if (!isAnyOperationLoading) settingsViewModel.initiateDeleteAllUserDataProcess()
                },
                enabled = users.isNotEmpty() && !isAnyOperationLoading, // Disable if no users or other operation loading
                isDestructive = true,
                isLoading = isLoadingDeletion
            )
        )

        add(
            DataManagementSettingListItem.ActionItem(
                label = stringResource(R.string.settings_delete_entire_database),
                icon = Icons.Default.WarningAmber, // Or another appropriate icon
                onClick = {
                    if (!isAnyOperationLoading) settingsViewModel.initiateDeleteEntireDatabaseProcess()
                },
                enabled = !isAnyOperationLoading, // Always enable if no other operation is loading
                isDestructive = true,
                isLoading = isLoadingEntireDatabaseDeletion
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Regular Actions
        items(regularDataManagementItems.size) { index ->
            val item = regularDataManagementItems[index]
            SettingsCardItem(
                label = item.label,
                icon = item.icon,
                onClick = item.onClick,
                enabled = item.enabled,
                isDestructive = item.isDestructive, // Will be false here
                isLoading = item.isLoading
            )
        }

        if (destructiveDataManagementItems.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.settings_danger_zone),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(destructiveDataManagementItems.size) { index ->
                val item = destructiveDataManagementItems[index]
                SettingsCardItem(
                    label = item.label,
                    icon = item.icon,
                    onClick = item.onClick,
                    enabled = item.enabled,
                    isDestructive = item.isDestructive,
                    isLoading = item.isLoading // Pass isLoading to the item
                )
            }
        }
    }

    // UserSelectionDialog for Export
    if (showUserSelectionDialogForExport) {
        UserSelectionDialog(
            users = users,
            onUserSelected = { userId -> settingsViewModel.proceedWithExportForUser(userId) },
            onDismiss = { if (!isLoadingExport) settingsViewModel.cancelUserSelectionForExport() },
            title = stringResource(R.string.dialog_title_export_select_user),
            confirmButtonEnabled = !isLoadingExport,
            itemClickEnabled = !isLoadingExport
        )
    }

    // UserSelectionDialog for Import
    if (showUserSelectionDialogForImport) {
        UserSelectionDialog(
            users = users,
            onUserSelected = { userId -> settingsViewModel.proceedWithImportForUser(userId) },
            onDismiss = { if (!isLoadingImport) settingsViewModel.cancelUserSelectionForImport() },
            title = stringResource(R.string.dialog_title_import_select_user),
            confirmButtonEnabled = !isLoadingImport,
            itemClickEnabled = !isLoadingImport
        )
    }

    // UserSelectionDialog for Delete User Data
    if (showUserSelectionDialogForDelete) {
        UserSelectionDialog(
            users = users,
            onUserSelected = { userId -> settingsViewModel.proceedWithDeleteForUser(userId) },
            onDismiss = { if (!isLoadingDeletion) settingsViewModel.cancelUserSelectionForDelete() },
            title = stringResource(R.string.dialog_title_delete_select_user),
            confirmButtonEnabled = !isLoadingDeletion,
            itemClickEnabled = !isLoadingDeletion
        )
    }

    // Confirmation dialog for deleting a specific user's data (shown AFTER a user is selected)
    if (showDeleteConfirmationDialog) {
        userPendingDeletion?.let { userToDelete -> // Use the user stored in the ViewModel
            AlertDialog(
                onDismissRequest = { if (!isLoadingDeletion) settingsViewModel.cancelDeleteConfirmation() },
                title = { Text(stringResource(R.string.dialog_title_delete_user_data_confirmation), fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        stringResource(R.string.dialog_message_delete_user_data_confirmation, userToDelete.name),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            settingsViewModel.confirmActualDeletion()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        enabled = !isLoadingDeletion
                    ) {
                        if (isLoadingDeletion) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.button_yes_delete_all))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { settingsViewModel.cancelDeleteConfirmation() },
                        enabled = !isLoadingDeletion
                    ) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }
    }

    // Confirmation dialog for deleting the entire database
    if (showDeleteEntireDatabaseConfirmationDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isLoadingEntireDatabaseDeletion) { // Only allow closing if not currently deleting
                    settingsViewModel.cancelDeleteEntireDatabaseConfirmation()
                }
            },
            icon = { Icon(Icons.Filled.WarningAmber, contentDescription = stringResource(R.string.content_desc_warning_icon), tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(stringResource(R.string.dialog_title_delete_entire_database_confirmation), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            },
            text = {
                Text(stringResource(R.string.dialog_message_delete_entire_database_confirmation))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.confirmDeleteEntireDatabase(context.applicationContext)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    enabled = !isLoadingEntireDatabaseDeletion
                ) {
                    if (isLoadingEntireDatabaseDeletion) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.button_yes_delete_all))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { settingsViewModel.cancelDeleteEntireDatabaseConfirmation() },
                    enabled = !isLoadingEntireDatabaseDeletion
                ) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    // Confirmation dialog for restoring the database
    if (showRestoreConfirmationDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isLoadingRestore) showRestoreConfirmationDialog = false // Only dismiss if not loading
            },
            icon = { Icon(Icons.Filled.CloudUpload, contentDescription = stringResource(R.string.content_desc_restore_icon)) },
            title = {
                Text(stringResource(R.string.dialog_title_restore_database_confirmation), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(stringResource(R.string.dialog_message_restore_database_confirmation))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmationDialog = false
                        settingsViewModel.startDatabaseRestore() // This will trigger the SAF event
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), // Destructive action
                    enabled = !isLoadingRestore
                ) {
                    if (isLoadingRestore) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.button_yes_restore))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmationDialog = false
                    },
                    enabled = !isLoadingRestore
                ) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }
}

/**
 * Composable item for displaying a setting in a card layout.
 * It includes a label, an icon (or a loading indicator), and handles click actions.
 *
 * @param label The text label for the setting.
 * @param icon The icon to display for the setting.
 * @param onClick The lambda to execute when the item is clicked.
 * @param enabled Whether the item is clickable and interactive. Defaults to true.
 * @param isDestructive If true, indicates a potentially dangerous action, styled with error colors. Defaults to false.
 * @param isLoading If true, shows a loading indicator instead of the icon and disables clicks. Defaults to false.
 */
@Composable
fun SettingsCardItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    isLoading: Boolean = false
) {
    // Clickability is determined by both 'enabled' and not 'isLoading'
    val currentClickable = enabled && !isLoading

    val baseTextColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface // Or onBackground / onSurfaceVariant as per your theme
    }

    val baseIconColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary // Or onSurfaceVariant etc. depending on design
    }

    // Text color adjusted for enabled state (ignoring isLoading for visual disabled state)
    val textColor = if (!enabled) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        baseTextColor
    }

    // Icon color adjusted for enabled state
    val iconColor = if (!enabled) {
        baseIconColor.copy(alpha = 0.38f) // Use the base color (primary or error) and reduce alpha
    } else {
        baseIconColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(enabled = currentClickable, onClick = onClick) // Clickability controlled here
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium, // Consider titleSmall or bodyLarge based on importance
                    color = textColor
                )
            },
            leadingContent = {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) { // Box for consistent icon/loader size
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), // Slightly smaller than the box for padding
                            strokeWidth = 2.dp,
                            color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = label, // Basic content description
                            tint = iconColor
                        )
                    }
                }
            }
            // No trailing content in this design, but can be added if needed.
        )
    }
}

/**
 * Composable dialog for selecting a user from a list.
 *
 * @param users The list of [User] objects to display for selection.
 * @param onUserSelected Lambda called with the selected user's ID.
 * @param onDismiss Lambda called when the dialog is dismissed (e.g., by clicking the cancel button or outside the dialog).
 * @param title The title of the dialog.
 * @param confirmButtonEnabled Controls the enabled state of the dismiss ("Cancel") button. Defaults to true.
 * @param itemClickEnabled Controls whether the user list items are clickable. Defaults to true.
 */
@Composable
fun UserSelectionDialog(
    users: List<User>,
    onUserSelected: (userId: Int) -> Unit,
    onDismiss: () -> Unit,
    title: String,
    confirmButtonEnabled: Boolean = true,
    itemClickEnabled: Boolean = true
) {
    if (users.isEmpty()) {
        // If the dialog is shown with no users, dismiss it immediately.
        // It's better to prevent opening the dialog if users list is empty (logic in ViewModel).
        LaunchedEffect(Unit) { // Ensure onDismiss is called within a composition
            onDismiss()
        }
        return
    }

    AlertDialog(
        onDismissRequest = { if (confirmButtonEnabled) onDismiss() }, // Allow dismiss only if not blocked
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) }, // Or headlineSmall
        text = {
            LazyColumn {
                items(users.size) { index ->
                    val user = users[index]
                    val textColor = if (itemClickEnabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    Text(
                        text = user.name,
                        style = MaterialTheme.typography.bodyLarge, // Or subtitle1
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = itemClickEnabled) { // Control item clickability
                                onUserSelected(user.id)
                            }
                            .padding(vertical = 12.dp),
                        color = textColor
                    )
                    if (index < users.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) // Added vertical padding
                    }
                }
            }
        },
        confirmButton = { // In this dialog, the AlertDialog's "confirmButton" acts as our "Cancel" button.
            TextButton(
                onClick = onDismiss,
                enabled = confirmButtonEnabled // Control enabled state of the "Cancel" button
            ) {
                Text(stringResource(R.string.cancel_button))
            }
        }
        // No dismissButton is explicitly defined here as the confirmButton serves as "Cancel".
        // Tapping outside or back press is handled by onDismissRequest.
    )
}
