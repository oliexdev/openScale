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

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.ui.screen.dialog.NumberInputDialog
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch

/**
 * Screen to manage all settings for a specific, saved Bluetooth device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothDetailScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // --- Observe state from ViewModel ---
    val savedDevice by bluetoothViewModel.savedDevice.collectAsStateWithLifecycle()
    val savedSupport by bluetoothViewModel.savedDeviceSupport.collectAsStateWithLifecycle()
    val currentTuningProfile = savedSupport?.tuningProfile ?: TuningProfile.Balanced
    val isDeveloperActive = (savedDevice?.name.orEmpty() == "Debug")

    // --- Observe Smart Assignment Settings ---
    val isSmartAssignmentEnabled by bluetoothViewModel.isSmartAssignmentEnabled.collectAsStateWithLifecycle(false)
    val tolerancePercent by bluetoothViewModel.smartAssignmentTolerancePercent.collectAsStateWithLifecycle(10)
    val ignoreOutsideTolerance by bluetoothViewModel.smartAssignmentIgnoreOutsideTolerance.collectAsStateWithLifecycle(false)

    // --- UI State for dropdowns and dialogs ---
    var tuningDropdownExpanded by remember { mutableStateOf(false) }
    val availableTuningProfiles = remember { TuningProfile.entries.toList() }
    var showToleranceDialog by remember { mutableStateOf(false) }

    if (showToleranceDialog) {
        NumberInputDialog(
            title = stringResource(R.string.tolerance_label),
            initialValue = tolerancePercent.toString(),
            inputType = InputFieldType.INT,
            unit = UnitType.PERCENT,
            measurementIcon = MeasurementTypeIcon.IC_DEFAULT,
            iconBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
            onDismiss = { showToleranceDialog = false },
            onConfirm = { newValue ->
                scope.launch {
                    bluetoothViewModel.setSmartAssignmentTolerancePercent(newValue.toIntOrNull() ?: 10)
                }
                showToleranceDialog = false
            }
        )
    }

    LaunchedEffect(savedDevice) {
        val deviceName = savedDevice?.name ?: context.getString(R.string.unknown_device)
        val title = context.getString(R.string.title_device_settings_for, deviceName)
        sharedViewModel.setTopBarTitle(title)
        sharedViewModel.setTopBarActions(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(all = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSectionTitle(title = stringResource(R.string.scale_configuration_title))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                bluetoothViewModel.DeviceConfigurationUi()
            }
        }

        // --- DEVICE TUNING SECTION ---
        SettingsSectionTitle(title = stringResource(R.string.bluetooth_tuning_title))
        ExposedDropdownMenuBox(
            expanded = tuningDropdownExpanded,
            onExpandedChange = { tuningDropdownExpanded = !tuningDropdownExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = stringResource(id = currentTuningProfile.labelRes),
                onValueChange = { /* read-only */ },
                readOnly = true,
                label = { Text(stringResource(R.string.tuning_profile_label)) },
                leadingIcon = {
                    Icon(
                        imageVector = currentTuningProfile.icon,
                        contentDescription = null
                    )
                },                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tuningDropdownExpanded) },
                modifier = Modifier
                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .clickable { tuningDropdownExpanded = true },
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            ExposedDropdownMenu(
                expanded = tuningDropdownExpanded,
                onDismissRequest = { tuningDropdownExpanded = false },
                modifier = Modifier.exposedDropdownSize(matchAnchorWidth = true)
            ) {
                availableTuningProfiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(stringResource(id = profile.labelRes)) },
                        leadingIcon = { Icon(imageVector = profile.icon, contentDescription = null) },
                        onClick = {
                            scope.launch { bluetoothViewModel.setSavedTuning(profile) }
                            tuningDropdownExpanded = false
                        }
                    )
                }
            }
        }

        // --- BLUETOOTH MEASUREMENT SECTION ---
        SettingsSectionTitle(title = stringResource(R.string.bluetooth_measurement_title))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    label = stringResource(R.string.smart_assignment_assignment),
                    icon = Icons.Default.People,
                    onClick = { scope.launch { bluetoothViewModel.setSmartAssignmentEnabled(!isSmartAssignmentEnabled) } }
                ) {
                    Switch(
                        checked = isSmartAssignmentEnabled,
                        onCheckedChange = null
                    )
                }

                AnimatedVisibility(visible = isSmartAssignmentEnabled) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        SettingsRow(
                            label = stringResource(R.string.tolerance_label),
                            description = stringResource(R.string.tolerance_desc),
                            onClick = { showToleranceDialog = true }
                        ) {
                            Text(
                                text = "$tolerancePercent%",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        SettingsRow(
                            label = stringResource(R.string.ignore_outside_tolerance_title),
                            description = stringResource(R.string.ignore_outside_tolerance_desc),
                            onClick = { scope.launch { bluetoothViewModel.setSmartAssignmentIgnoreOutsideTolerance(!ignoreOutsideTolerance) } }
                        ) {
                            Switch(
                                checked = ignoreOutsideTolerance,
                                onCheckedChange = null
                            )
                        }
                    }
                }
            }
        }

        // --- DEVELOPER SECTION ---
        SettingsSectionTitle(title = stringResource(R.string.developer_section_title))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    label = stringResource(R.string.bluetooth_developer_mode),
                    icon = Icons.Default.BugReport,
                    onClick = {
                        val currentDevice = savedDevice
                        if (currentDevice != null) {
                            if (isDeveloperActive) {
                                // Deactivating Debug Mode: Restore original name and handler.
                                bluetoothViewModel.saveDeviceAsPreferred(
                                    ScannedDeviceInfo(name = currentDevice.determinedHandlerDisplayName!!, address = currentDevice.address, rssi = 0, serviceUuids = currentDevice.serviceUuids, manufacturerData = currentDevice.manufacturerData, isSupported = true, determinedHandlerDisplayName = currentDevice.determinedHandlerDisplayName)
                                )
                            } else {
                                // Activating Debug Mode: Change name and handler, but keep the address.
                                bluetoothViewModel.saveDeviceAsPreferred(
                                    ScannedDeviceInfo(name = "Debug", address = currentDevice.address, rssi = 0, serviceUuids = currentDevice.serviceUuids, manufacturerData = currentDevice.manufacturerData, isSupported = true, determinedHandlerDisplayName = currentDevice.name)
                                )
                            }
                        }
                    }
                ) {
                    Switch(
                        checked = isDeveloperActive,
                        onCheckedChange = null
                    )
                }

                AnimatedVisibility(visible = isDeveloperActive) {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(
                            modifier = Modifier.padding(start = 0.dp, top = 16.dp, bottom = 8.dp, end = 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.developer_banner_active),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.developer_banner_enable_logs_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- DANGER ZONE CARD ---
        SettingsSectionTitle(
            title = stringResource(R.string.danger_zone_title),
            titleColor = MaterialTheme.colorScheme.error
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    bluetoothViewModel.removeSavedDevice()
                    navController.popBackStack()
                },
            verticalAlignment = Alignment.CenterVertically)
        {
            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.bluetooth_remove_saved_device), color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * A helper composable to create a consistent section title.
 */
@Composable
private fun SettingsSectionTitle(
    title: String,
    titleColor: Color = MaterialTheme.colorScheme.primary
) {
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = titleColor,
        fontWeight = FontWeight.Bold,
    )
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * A helper composable for a consistent settings row layout.
 */
@Composable
private fun SettingsRow(
    label: String,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}
