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

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.SupportedLanguage
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.dialog.TimeInputDialog
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

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
    var hasLogFile by remember { mutableStateOf(false) }

    var showLoggingActivationDialog by remember { mutableStateOf(false) }

    // Reminder state
    val isReminderEnabled by sharedViewModel.reminderEnabled.collectAsState(initial = false)
    val reminderText by sharedViewModel.reminderText.collectAsState(initial = "")
    val reminderHour by sharedViewModel.reminderHour.collectAsState(initial = 9)
    val reminderMinute by sharedViewModel.reminderMinute.collectAsState(initial = 0)
    val reminderDays by sharedViewModel.reminderDays.collectAsState(initial = emptySet())

    var showTimePicker by remember { mutableStateOf(false) }
    var expandedDays by remember { mutableStateOf(false) }

    val dayOrder = listOf(
        java.time.DayOfWeek.MONDAY to stringResource(R.string.monday_short),
        java.time.DayOfWeek.TUESDAY to stringResource(R.string.tuesday_short),
        java.time.DayOfWeek.WEDNESDAY to stringResource(R.string.wednesday_short),
        java.time.DayOfWeek.THURSDAY to stringResource(R.string.thursday_short),
        java.time.DayOfWeek.FRIDAY to stringResource(R.string.friday_short),
        java.time.DayOfWeek.SATURDAY to stringResource(R.string.saturday_short),
        java.time.DayOfWeek.SUNDAY to stringResource(R.string.sunday_short),
    )

    fun selectedDaysLabel(selected: Set<String>): String {
        val labels = dayOrder.filter { selected.contains(it.first.name) }.map { it.second }
        return when {
            labels.isEmpty() -> "—"
            labels.size == 7 -> context.getString(R.string.all)
            else -> labels.joinToString(", ")
        }
    }

    val requestPostNotif = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                if (reminderText.isBlank()) {
                    sharedViewModel.setReminderText(context.getString(R.string.reminder_default_text))
                }
                sharedViewModel.setReminderEnabled(true)
                sharedViewModel.showSnackbar(context.getString(R.string.reminder_enabled_snackbar))
                settingsViewModel.requestReminderReschedule()
            } else {
                sharedViewModel.setReminderEnabled(false)
                sharedViewModel.showSnackbar(context.getString(R.string.permission_denied))
            }
        }
    }

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
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.enable_file_logging_dialog_message),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.enable_file_logging_dialog_message_warning),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            },
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

    LaunchedEffect(isFileLoggingEnabled) {
        val file = LogManager.getLogFile()
        if (file != null && file.exists()) {
            hasLogFile = true
        }
    }

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(context.getString(R.string.settings_item_general))
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // --- Language ---
        Text(
            text = stringResource(R.string.settings_language_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )

        Column(modifier = Modifier.padding(16.dp)) {
            ExposedDropdownMenuBox(
                expanded = expandedLanguageMenu,
                onExpandedChange = { expandedLanguageMenu = !expandedLanguageMenu },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedLanguage.nativeDisplayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(id = R.string.settings_language_label)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = stringResource(id = R.string.settings_language_label)
                        )
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLanguageMenu)
                    },
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
        }

        // --- Reminder ---
        SettingsSectionTitle(text = stringResource(R.string.settings_reminder_title))

        SettingsGroup(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            title = stringResource(R.string.settings_reminder_enable_label),
            checked = isReminderEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    val notGranted = needsPermission &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED

                    if (notGranted) {
                        requestPostNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        scope.launch {
                            if (reminderText.isBlank()) {
                                sharedViewModel.setReminderText(context.getString(R.string.reminder_default_text))
                            }
                            sharedViewModel.setReminderEnabled(true)
                            sharedViewModel.showSnackbar(context.getString(R.string.reminder_enabled_snackbar))
                            settingsViewModel.requestReminderReschedule()
                        }
                    }
                } else {
                    scope.launch {
                        sharedViewModel.setReminderEnabled(false)
                        sharedViewModel.showSnackbar(context.getString(R.string.reminder_disabled_snackbar))
                        settingsViewModel.requestReminderReschedule()
                    }
                }
            },
            content = {
                OutlinedTextField(
                    value = reminderText,
                    onValueChange = { newValue ->
                        scope.launch {
                            sharedViewModel.setReminderText(newValue)
                            settingsViewModel.requestReminderReschedule()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(id = R.string.settings_reminder_text_label)) },
                    placeholder = { Text(stringResource(id = R.string.settings_reminder_text_placeholder)) }
                )

                ExposedDropdownMenuBox(
                    expanded = expandedDays,
                    onExpandedChange = { expandedDays = !expandedDays },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    OutlinedTextField(
                        value = selectedDaysLabel(reminderDays),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_reminder_days_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDays) },
                        modifier = Modifier
                            .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedDays,
                        onDismissRequest = { expandedDays = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        dayOrder.forEach { (day, label) ->
                            val selected = reminderDays.contains(day.name)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = selected, onCheckedChange = null)
                                        Text(text = label, modifier = Modifier.padding(start = 8.dp))
                                    }
                                },
                                onClick = {
                                    val new = reminderDays.toMutableSet().apply {
                                        if (selected) remove(day.name) else add(day.name)
                                    }.toSet()
                                    scope.launch {
                                        sharedViewModel.setReminderDays(new)
                                        settingsViewModel.requestReminderReschedule()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_reminder_time_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        val hh = reminderHour.toString().padStart(2, '0')
                        val mm = reminderMinute.toString().padStart(2, '0')
                        Text("$hh:$mm")
                    }
                }

                if (showTimePicker) {
                    val initialTs = remember(reminderHour, reminderMinute) {
                        Calendar.getInstance().apply {
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                            set(Calendar.HOUR_OF_DAY, reminderHour.coerceIn(0, 23))
                            set(Calendar.MINUTE, reminderMinute.coerceIn(0, 59))
                        }.timeInMillis
                    }
                    TimeInputDialog(
                        title = stringResource(id = R.string.settings_reminder_time_label),
                        initialTimestamp = initialTs,
                        measurementIcon = MeasurementTypeIcon.IC_TIME,
                        iconBackgroundColor = MaterialTheme.colorScheme.primary,
                        onDismiss = { showTimePicker = false },
                        onConfirm = { pickedMillis ->
                            val cal = Calendar.getInstance().apply { timeInMillis = pickedMillis }
                            val h = cal.get(Calendar.HOUR_OF_DAY)
                            val m = cal.get(Calendar.MINUTE)
                            scope.launch {
                                sharedViewModel.setReminderHour(h)
                                sharedViewModel.setReminderMinute(m)
                                settingsViewModel.requestReminderReschedule()
                            }
                        }
                    )
                }
            })

        // --- Diagnostics ---
        SettingsSectionTitle(text = stringResource(R.string.diagnostics_title))

        SettingsGroup(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = stringResource(R.string.file_logging_icon_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            title = stringResource(R.string.file_logging_label),
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
            },
            content = {
            },
            persistentContent = {
                if (isFileLoggingEnabled || hasLogFile) {
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
                                        sharedViewModel.showSnackbar(
                                            context.getString(R.string.log_export_no_app_error)
                                        )
                                    }
                                    LogManager.e("GeneralSettingsScreen",
                                        "Error launching create document intent for export", e)
                                }
                            } else {
                                scope.launch {
                                    sharedViewModel.showSnackbar(
                                        context.getString(R.string.log_export_no_file_to_export)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.export_log_file_button))
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp)
    )
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    )
}

@Composable
fun SettingsGroup(
    leadingIcon: @Composable (() -> Unit)? = null,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
    persistentContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val borderColor = if (checked)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .then(
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, borderColor, MaterialTheme.shapes.medium)
                    .background(container)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .semantics { contentDescription = title }
                .clickable { onCheckedChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                leadingIcon?.invoke()
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = if (leadingIcon != null) 12.dp else 0.dp)
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }

        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (checked) {
            Spacer(Modifier.height(8.dp))
            content()
        }

        if (persistentContent != null) {
            if (!checked) Spacer(Modifier.height(8.dp))
            persistentContent()
        }
    }
}
