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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.BackupInterval
import com.health.openscale.core.data.User
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri


/**
 * Represents items in the data management settings list.
 */
sealed class DataManagementSettingListItem {
    /**
     * Represents an actionable item in the settings list.
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
 * Composable screen for managing application data.
 * Allows users to export/import data, backup/restore the database,
 * and manage automatic backup settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementSettingsScreen(
    navController: NavController,
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

    val showUserSelectionDialogForDelete by settingsViewModel.showUserSelectionDialogForDelete.collectAsState()
    val userPendingDeletion by settingsViewModel.userPendingDeletion.collectAsState()
    val showDeleteConfirmationDialog by settingsViewModel.showDeleteConfirmationDialog.collectAsState()
    var showRestoreConfirmationDialog by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- Automatic Backup Settings from ViewModel ---
    val autoBackupGloballyEnabled by settingsViewModel.autoBackupEnabledGlobally.collectAsState()
    val autoBackupLocationUriString by settingsViewModel.autoBackupLocationUri.collectAsState()
    val autoBackupInterval by settingsViewModel.autoBackupInterval.collectAsState()
    val autoBackupCreateNewFile by settingsViewModel.autoBackupCreateNewFile.collectAsState()
    val autoBackupLastSuccessfulTimestamp by settingsViewModel.autoBackupLastSuccessfulTimestamp.collectAsState()
    val isAutoBackupLocationConfigured = autoBackupLocationUriString != null

    // Effective state: global switch is on AND a location is configured.
    val isAutoBackupEffectivelyEnabled by remember(autoBackupGloballyEnabled, isAutoBackupLocationConfigured) {
        mutableStateOf(autoBackupGloballyEnabled && isAutoBackupLocationConfigured)
    }


    val lastBackupStatusText by remember(
        isAutoBackupEffectivelyEnabled,
        autoBackupLocationUriString,
        autoBackupGloballyEnabled,
        autoBackupLastSuccessfulTimestamp,
        context
    ) {
        mutableStateOf(
            if (isAutoBackupEffectivelyEnabled) {
                if (autoBackupLastSuccessfulTimestamp > 0L) {
                    val timestamp = autoBackupLastSuccessfulTimestamp
                    val date = Date(timestamp)
                    val dateFormat = DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT,
                        Locale.getDefault()
                    )
                    val formattedTime = dateFormat.format(date)
                    context.getString(R.string.settings_last_backup_status_successful, formattedTime)
                } else {
                    context.getString(R.string.settings_last_backup_status_never)
                }
            } else if (autoBackupGloballyEnabled && !isAutoBackupLocationConfigured) {
                context.getString(R.string.settings_backup_location_not_configured_for_auto)
            } else {
                context.getString(R.string.settings_auto_backups_disabled)
            }
        )
    }

    val selectedBackupIntervalDisplay = remember(autoBackupInterval, context) {
        autoBackupInterval.getDisplayName(context)
    }
    var showBackupIntervalDialog by remember { mutableStateOf(false) }

    val backupBehaviorSupportingText by remember(autoBackupCreateNewFile, context) {
        mutableStateOf(
            if (autoBackupCreateNewFile) context.getString(R.string.settings_backup_behavior_new_file)
            else context.getString(R.string.settings_backup_behavior_overwrite)
        )
    }

    val currentBackupLocationUserDisplay by remember(autoBackupLocationUriString, context) {
        mutableStateOf(
            if (autoBackupLocationUriString != null) {
                try {
                    DocumentFile.fromTreeUri(context, Uri.parse(autoBackupLocationUriString!!))?.name
                        ?: context.getString(R.string.settings_backup_location_selected_folder)
                } catch (e: Exception) {
                    context.getString(R.string.settings_backup_location_error_accessing)
                }
            } else {
                context.getString(R.string.settings_backup_location_not_configured)
            }
        )
    }

    val canOpenSelectedBackupLocation by remember(autoBackupLocationUriString) {
        mutableStateOf(autoBackupLocationUriString != null)
    }

    var activeSafActionUserId by remember { mutableStateOf<Int?>(null) }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                activeSafActionUserId?.let { userId ->
                    settingsViewModel.performCsvExport(userId, fileUri, context.contentResolver)
                    activeSafActionUserId = null
                }
            }
        }
    )

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                activeSafActionUserId?.let { userId ->
                    settingsViewModel.performCsvImport(userId, fileUri, context.contentResolver)
                    activeSafActionUserId = null
                }
            }
        }
    )

    val manualBackupDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"), // Using generic MIME type for DB backup
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                settingsViewModel.performDatabaseBackup(fileUri, context.applicationContext, context.contentResolver)
            }
        }
    )

    val restoreDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                // Confirmation dialog is shown before launching, restore directly
                settingsViewModel.performDatabaseRestore(fileUri, context.applicationContext, context.contentResolver)
            }
        }
    )

    val selectAutoBackupDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                coroutineScope.launch {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    settingsViewModel.setAutoBackupLocationUri(context.applicationContext,uri.toString())
                    // If user selects a folder, enable auto backups globally if not already.
                    if (!autoBackupGloballyEnabled) {
                        settingsViewModel.setAutoBackupEnabledGlobally(context.applicationContext,true)
                    }
                }
                Toast.makeText(context, context.getString(R.string.settings_backup_location_selected_toast,
                    DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected folder"), Toast.LENGTH_SHORT).show()
            } else {
                // User cancelled or no folder selected
                if (!isAutoBackupLocationConfigured) { // Only if no location was configured before
                    coroutineScope.launch { settingsViewModel.setAutoBackupEnabledGlobally(context.applicationContext,false) }
                }
                Toast.makeText(context, R.string.settings_backup_location_selection_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
    )

    LaunchedEffect(key1 = settingsViewModel) {
        settingsViewModel.safEvent.collect { event ->
            when (event) {
                is SafEvent.RequestCreateFile -> {
                    activeSafActionUserId = event.userId
                    if (event.actionId == SettingsViewModel.ACTION_ID_BACKUP_DB) {
                        manualBackupDbLauncher.launch(event.suggestedName)
                    } else {
                        exportCsvLauncher.launch(event.suggestedName)
                    }
                }
                is SafEvent.RequestOpenFile -> {
                    activeSafActionUserId = event.userId
                    if (event.actionId == SettingsViewModel.ACTION_ID_RESTORE_DB) {
                        // For DB restore, we show a confirmation dialog first.
                        // The actual launch happens after confirmation. This SAF event is for when that's confirmed.
                        restoreDbLauncher.launch(arrayOf("*/*")) // Generic MIME type for DB files
                    } else {
                        val mimeTypes = arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")
                        importCsvLauncher.launch(mimeTypes)
                    }
                }
            }
        }
    }

    val regularDataManagementItems = remember(users, isAnyOperationLoading, isLoadingExport, isLoadingImport, isLoadingBackup, isLoadingRestore, context) {
        buildList {
            add(DataManagementSettingListItem.ActionItem(context.getString(R.string.settings_export_measurements_csv), Icons.Default.FileDownload, { if (!isAnyOperationLoading) settingsViewModel.startExportProcess() }, users.isNotEmpty() && !isAnyOperationLoading, isLoading = isLoadingExport))
            add(DataManagementSettingListItem.ActionItem(context.getString(R.string.settings_import_measurements_csv), Icons.Default.FileUpload, { if (!isAnyOperationLoading) settingsViewModel.startImportProcess() }, users.isNotEmpty() && !isAnyOperationLoading, isLoading = isLoadingImport))
            add(DataManagementSettingListItem.ActionItem(context.getString(R.string.settings_backup_database_manual), Icons.Default.CloudDownload, { if (!isAnyOperationLoading) settingsViewModel.startDatabaseBackup() }, !isAnyOperationLoading, isLoading = isLoadingBackup))
            add(DataManagementSettingListItem.ActionItem(context.getString(R.string.settings_restore_database), Icons.Filled.CloudUpload, { if (!isAnyOperationLoading) showRestoreConfirmationDialog = true }, !isAnyOperationLoading, isLoading = isLoadingRestore))
        }
    }

    val destructiveDataManagementItems = remember(users, isAnyOperationLoading, isLoadingDeletion, isLoadingEntireDatabaseDeletion, context) {
        buildList {
            add(DataManagementSettingListItem.ActionItem(context.getString(R.string.settings_delete_all_measurement_data), Icons.Default.DeleteForever, { if (!isAnyOperationLoading) settingsViewModel.initiateDeleteAllUserDataProcess() }, users.isNotEmpty() && !isAnyOperationLoading, true, isLoadingDeletion))
            add(DataManagementSettingListItem.ActionItem(context.getString(R.string.settings_delete_entire_database), Icons.Default.WarningAmber, { if (!isAnyOperationLoading) settingsViewModel.initiateDeleteEntireDatabaseProcess() }, !isAnyOperationLoading, true, isLoadingEntireDatabaseDeletion))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(regularDataManagementItems.size) { index ->
            val item = regularDataManagementItems[index]
            SettingsCardItem(item.label, icon = item.icon, onClick = item.onClick, enabled = item.enabled, isDestructive = item.isDestructive, isLoading = item.isLoading)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.settings_auto_backup_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 1. Enable/Disable Automatic Backups (Toggle)
        item {
            SettingsCardItem(
                label = stringResource(R.string.settings_enable_auto_backups),
                onClick = {
                    if (!isAnyOperationLoading) {
                        val newCheckedState = !autoBackupGloballyEnabled
                        if (newCheckedState && !isAutoBackupLocationConfigured) {
                            selectAutoBackupDirectoryLauncher.launch(null) // URI (null) means "pick a new folder"
                        } else {
                            coroutineScope.launch { settingsViewModel.setAutoBackupEnabledGlobally(context.applicationContext,newCheckedState) }
                        }
                    }
                },
                enabled = !isAnyOperationLoading,
                customLeadingContent = {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = stringResource(R.string.content_desc_auto_backups_toggle),
                        tint = if (isAutoBackupEffectivelyEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Switch(
                        checked = autoBackupGloballyEnabled,
                        onCheckedChange = { newCheckedState ->
                            if (!isAnyOperationLoading) {
                                if (newCheckedState && !isAutoBackupLocationConfigured) {
                                    selectAutoBackupDirectoryLauncher.launch(null)
                                } else {
                                    coroutineScope.launch { settingsViewModel.setAutoBackupEnabledGlobally(context.applicationContext,newCheckedState) }
                                }
                            }
                        },
                        enabled = !isAnyOperationLoading
                    )
                }
            )
        }

        // 2. Backup Location Configuration (only visible if global switch is on)
        if (autoBackupGloballyEnabled) {
            item {
                SettingsCardItem(
                    label = stringResource(R.string.settings_backup_location_label),
                    supportingText = currentBackupLocationUserDisplay,
                    onClick = {
                        if (!isAnyOperationLoading) {
                            selectAutoBackupDirectoryLauncher.launch(null) // Allow changing/re-selecting
                        }
                    },
                    enabled = !isAnyOperationLoading,
                    customLeadingContent = { Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.content_desc_backup_location_icon)) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.End) {
                            if (canOpenSelectedBackupLocation && autoBackupLocationUriString != null) {
                                IconButton(
                                    onClick = {
                                        if (!isAnyOperationLoading) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.setDataAndType(autoBackupLocationUriString!!.toUri(), "vnd.android.document/directory")
                                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                context.startActivity(intent)
                                            } catch (e: ActivityNotFoundException) {
                                                Toast.makeText(context, R.string.settings_backup_location_open_error_no_app, Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, R.string.settings_backup_location_open_error, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    enabled = !isAnyOperationLoading
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.content_desc_open_backup_location_icon))
                                }
                            }
                            IconButton(
                                onClick = {
                                    if (!isAnyOperationLoading) {
                                        selectAutoBackupDirectoryLauncher.launch(null)
                                    }
                                },
                                enabled = !isAnyOperationLoading
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.content_desc_change_backup_location_icon))
                            }
                        }
                    }
                )
            }
        }

        // 3. Further Auto-Backup settings (only visible if *effectively* enabled)
        if (isAutoBackupEffectivelyEnabled) {
            item {
                SettingsCardItem(
                    label = stringResource(R.string.settings_last_backup_status_label),
                    supportingText = lastBackupStatusText,
                    onClick = { /* Could show more details or trigger a manual sync if needed */ },
                    enabled = !isAnyOperationLoading,
                    customLeadingContent = { Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.content_desc_backup_status_icon)) }
                )
            }
            item {
                SettingsCardItem(
                    label = stringResource(R.string.settings_backup_interval_label),
                    supportingText = selectedBackupIntervalDisplay,
                    onClick = { if (!isAnyOperationLoading) showBackupIntervalDialog = true },
                    enabled = !isAnyOperationLoading,
                    customLeadingContent = { Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.content_desc_backup_interval_icon)) },
                    trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.content_desc_change_interval_icon)) }
                )
            }
            item {
                SettingsCardItem(
                    label = stringResource(R.string.settings_backup_behavior_label),
                    supportingText = backupBehaviorSupportingText,
                    onClick = { if (!isAnyOperationLoading) {
                        coroutineScope.launch { settingsViewModel.setAutoBackupCreateNewFile(!autoBackupCreateNewFile) }
                    }},
                    enabled = !isAnyOperationLoading,
                    customLeadingContent = { Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.content_desc_backup_behavior_icon)) },
                    trailingContent = {
                        Switch(
                            checked = autoBackupCreateNewFile,
                            onCheckedChange = { isChecked ->
                                if (!isAnyOperationLoading) {
                                    coroutineScope.launch { settingsViewModel.setAutoBackupCreateNewFile(isChecked) }
                                }
                            },
                            enabled = !isAnyOperationLoading
                        )
                    }
                )
            }
        }

        if (destructiveDataManagementItems.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(destructiveDataManagementItems.size) { index ->
                val item = destructiveDataManagementItems[index]
                SettingsCardItem(item.label, icon = item.icon, onClick = item.onClick, enabled = item.enabled, isDestructive = item.isDestructive, isLoading = item.isLoading)
            }
        }
    }

    if (showBackupIntervalDialog) {
        val intervalEnumValues = remember { BackupInterval.entries.toList() }
        SelectionDialogEnum(
            title = stringResource(R.string.dialog_title_select_backup_interval),
            options = intervalEnumValues,
            selectedOption = autoBackupInterval,
            onOptionSelected = { selectedEnumInterval ->
                coroutineScope.launch { settingsViewModel.setAutoBackupInterval(context.applicationContext,selectedEnumInterval) }
            },
            optionToDisplayName = { it.getDisplayName(context) },
            onDismissRequest = { showBackupIntervalDialog = false }
        )
    }

    if (showDeleteEntireDatabaseConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { if (!isLoadingEntireDatabaseDeletion) settingsViewModel.cancelDeleteEntireDatabaseConfirmation() },
            icon = { Icon(Icons.Filled.WarningAmber, contentDescription = stringResource(R.string.content_desc_warning_icon), tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.dialog_title_delete_entire_database_confirmation), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = { Text(stringResource(R.string.dialog_message_delete_entire_database_confirmation)) },
            confirmButton = { TextButton({ settingsViewModel.confirmDeleteEntireDatabase(context.applicationContext) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), enabled = !isLoadingEntireDatabaseDeletion) { if (isLoadingEntireDatabaseDeletion) CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error) else Text(stringResource(R.string.button_yes_delete_all)) } },
            dismissButton = { TextButton({ settingsViewModel.cancelDeleteEntireDatabaseConfirmation() }, enabled = !isLoadingEntireDatabaseDeletion) { Text(stringResource(R.string.cancel_button)) } }
        )
    }

    if (showUserSelectionDialogForExport) {
        UserSelectionDialog(users, { settingsViewModel.proceedWithExportForUser(it) }, { if (!isLoadingExport) settingsViewModel.cancelUserSelectionForExport() }, stringResource(R.string.dialog_title_export_select_user), !isLoadingExport, !isLoadingExport)
    }

    if (showUserSelectionDialogForImport) {
        UserSelectionDialog(users, { settingsViewModel.proceedWithImportForUser(it) }, { if (!isLoadingImport) settingsViewModel.cancelUserSelectionForImport() }, stringResource(R.string.dialog_title_import_select_user), !isLoadingImport, !isLoadingImport)
    }

    if (showUserSelectionDialogForDelete) {
        UserSelectionDialog(users, { settingsViewModel.proceedWithDeleteForUser(it) }, { if (!isLoadingDeletion) settingsViewModel.cancelUserSelectionForDelete() }, stringResource(R.string.dialog_title_delete_select_user), !isLoadingDeletion, !isLoadingDeletion)
    }

    if (showDeleteConfirmationDialog) {
        userPendingDeletion?.let { user ->
            AlertDialog(
                onDismissRequest = { if (!isLoadingDeletion) settingsViewModel.cancelDeleteConfirmation() },
                icon = { Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.content_desc_delete_icon), tint = MaterialTheme.colorScheme.error) },
                title = { Text(stringResource(R.string.dialog_title_delete_user_data_confirmation), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.dialog_message_delete_user_data_confirmation, user.name)) },
                confirmButton = { TextButton({ settingsViewModel.confirmActualDeletion() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), enabled = !isLoadingDeletion) { if (isLoadingDeletion) CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error) else Text(stringResource(R.string.button_yes_delete_all)) } },
                dismissButton = { TextButton({ settingsViewModel.cancelDeleteConfirmation() }, enabled = !isLoadingDeletion) { Text(stringResource(R.string.cancel_button)) } }
            )
        }
    }

    if (showRestoreConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { if (!isLoadingRestore) showRestoreConfirmationDialog = false },
            icon = { Icon(Icons.Filled.CloudUpload, contentDescription = stringResource(R.string.content_desc_restore_icon)) },
            title = { Text(stringResource(R.string.dialog_title_restore_database_confirmation), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dialog_message_restore_database_confirmation)) },
            confirmButton = { TextButton({ showRestoreConfirmationDialog = false; settingsViewModel.startDatabaseRestore() /* This now triggers SAF event */ }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), enabled = !isLoadingRestore) { if (isLoadingRestore) CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error) else Text(stringResource(R.string.button_yes_restore)) } },
            dismissButton = { TextButton({ showRestoreConfirmationDialog = false }, enabled = !isLoadingRestore) { Text(stringResource(R.string.cancel_button)) } }
        )
    }
}

@Composable
fun SettingsCardItem(
    label: String,
    supportingText: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    isLoading: Boolean = false,
    customLeadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val currentClickable = enabled && !isLoading
    val baseTextColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val textColor = if (!enabled) baseTextColor.copy(alpha = 0.38f) else baseTextColor
    val baseIconColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val iconColorToUse = if (!enabled) baseIconColor.copy(alpha = 0.38f) else baseIconColor

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp) // Consistent padding
            .clickable(enabled = currentClickable, onClick = onClick)
    ) {
        ListItem(
            headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge, color = textColor) },
            supportingContent = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) } },
            leadingContent = customLeadingContent ?: icon?.let {
                {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) { // Ensure icon and progress indicator are same size
                        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        else Icon(it, contentDescription = label, tint = iconColorToUse)
                    }
                }
            },
            trailingContent = trailingContent
        )
    }
}

/**
 * A generic selection dialog for Enums or any list of items
 * where each item needs a display name.
 */
@Composable
fun <T> SelectionDialogEnum(
    title: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    optionToDisplayName: (T) -> String,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    val displayName = optionToDisplayName(option)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (option == selectedOption),
                                onClick = {
                                    onOptionSelected(option)
                                    onDismissRequest() // Dismiss after selection
                                }
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (option == selectedOption),
                            onClick = null // RadioButton is controlled by Row's selectable
                        )
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

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
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = { if (confirmButtonEnabled) onDismiss() },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn {
                items(users.size) { index ->
                    val user = users[index]
                    val textColor = if (itemClickEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    Text(
                        user.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = itemClickEnabled) { onUserSelected(user.id) }
                            .padding(vertical = 12.dp),
                        color = textColor
                    )
                    if (index < users.size - 1) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onDismiss, enabled = confirmButtonEnabled) { Text(stringResource(R.string.cancel_button)) } }
    )
}
