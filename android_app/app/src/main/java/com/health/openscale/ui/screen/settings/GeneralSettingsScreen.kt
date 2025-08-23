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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.screen.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.SupportedLanguage
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val supportedLanguagesEnumEntries = remember { SupportedLanguage.entries }

    val currentLanguageCode by sharedViewModel.appLanguageCode.collectAsState(initial = null)
    var expandedLanguageMenu by remember { mutableStateOf(false) }

    val selectedLanguage: SupportedLanguage = remember(currentLanguageCode, supportedLanguagesEnumEntries) {
        val systemDefault = SupportedLanguage.getDefault().code
        supportedLanguagesEnumEntries.find { it.code == currentLanguageCode }
            ?: supportedLanguagesEnumEntries.firstOrNull { it.code == systemDefault }
            ?: SupportedLanguage.getDefault()
    }

    val isFileLoggingEnabled by sharedViewModel.isFileLoggingEnabled.collectAsState(initial = false)
    var showLoggingActivationDialog by remember { mutableStateOf(false) }

    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val logFileToCopy = LogManager.getLogFile()
                if (logFileToCopy != null && logFileToCopy.exists()) {
                    scope.launch(Dispatchers.IO) {
                        val ok = LogManager.exportLogToUri(context, uri)
                        scope.launch {
                            if (ok) {
                                sharedViewModel.showSnackbar(context.getString(R.string.log_export_success))
                            } else {
                                sharedViewModel.showSnackbar(context.getString(R.string.log_export_error))
                            }
                        }
                    }
                }
            }
        } else {
            scope.launch {
                sharedViewModel.showSnackbar(context.getString(R.string.log_export_cancelled))
            }
        }
    }

    if (showLoggingActivationDialog) {
        AlertDialog(
            onDismissRequest = { showLoggingActivationDialog = false },
            title = { Text(text = stringResource(R.string.enable_file_logging_dialog_title)) },
            text = { Text(stringResource(R.string.enable_file_logging_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            sharedViewModel.setFileLoggingEnabled(true)
                            LogManager.updateLoggingPreference(true)
                            sharedViewModel.showSnackbar(
                                context.getString(R.string.file_logging_enabled_snackbar)
                            )
                        }
                        showLoggingActivationDialog = false
                    }
                ) { Text(stringResource(R.string.enable_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showLoggingActivationDialog = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(context.getString(R.string.settings_item_general))
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // --- Language Settings ---
        ExposedDropdownMenuBox(
            expanded = expandedLanguageMenu,
            onExpandedChange = { expandedLanguageMenu = !expandedLanguageMenu },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedLanguage.nativeDisplayName,
                onValueChange = {}, // read-only
                readOnly = true,
                label = { Text(stringResource(id = R.string.settings_language_label)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = stringResource(id = R.string.settings_language_label)
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLanguageMenu) },
                modifier = Modifier
                    .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expandedLanguageMenu,
                onDismissRequest = { expandedLanguageMenu = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                SupportedLanguage.entries.forEach { langEnumEntry ->
                    DropdownMenuItem(
                        text = { Text(langEnumEntry.nativeDisplayName) },
                        onClick = {
                            if (currentLanguageCode != langEnumEntry.code) {
                                scope.launch {
                                    sharedViewModel.setAppLanguageCode(langEnumEntry.code)
                                }
                            }
                            expandedLanguageMenu = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // --- Diagnostics ---
        Text(
            text = stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 24.dp, bottom = 8.dp)
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = stringResource(R.string.file_logging_icon_content_description),
                modifier = Modifier.padding(end = 16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.file_logging_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = isFileLoggingEnabled,
                onCheckedChange = { wantsToEnable ->
                    if (wantsToEnable) {
                        showLoggingActivationDialog = true
                    } else {
                        scope.launch {
                            sharedViewModel.setFileLoggingEnabled(false)
                            LogManager.updateLoggingPreference(false)
                            sharedViewModel.showSnackbar(
                                context.getString(R.string.file_logging_disabled_snackbar)
                            )
                        }
                    }
                }
            )
        }

        if (isFileLoggingEnabled) {
            OutlinedButton(
                onClick = {
                    val logFile = LogManager.getLogFile()
                    if (logFile != null && logFile.exists()) {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, logFile.name)
                        }
                        try {
                            createFileLauncher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            scope.launch {
                                sharedViewModel.showSnackbar(context.getString(R.string.log_export_no_app_error))
                            }
                            LogManager.e(
                                "GeneralSettingsScreen",
                                "Error launching create document intent for export",
                                e
                            )
                        }
                    } else {
                        scope.launch {
                            sharedViewModel.showSnackbar(context.getString(R.string.log_export_no_file_to_export))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.export_log_file_button))
            }
        }
    }
}
