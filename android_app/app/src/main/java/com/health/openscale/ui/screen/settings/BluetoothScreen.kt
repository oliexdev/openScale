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

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.ui.screen.SharedViewModel
import com.health.openscale.ui.screen.bluetooth.BluetoothViewModel
import com.health.openscale.ui.screen.bluetooth.ScannedDeviceInfo
import kotlinx.coroutines.launch

/**
 * Composable function for the Bluetooth screen.
 * It handles Bluetooth permissions, enabling Bluetooth, scanning for devices,
 * displaying scanned devices, and saving a preferred scale.
 *
 * @param sharedViewModel The [SharedViewModel] for showing snackbars and accessing shared app functionalities.
 * @param bluetoothViewModel The [BluetoothViewModel] for managing Bluetooth state and operations.
 */
@Composable
fun BluetoothScreen(
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val scannedDevices by bluetoothViewModel.scannedDevices.collectAsState()
    val isScanning by bluetoothViewModel.isScanning.collectAsState()
    val scanError by bluetoothViewModel.scanError.collectAsState()
    val connectionError by bluetoothViewModel.connectionError.collectAsState()
    val hasPermissions by bluetoothViewModel.permissionsGranted.collectAsState()

    val savedDeviceAddress by bluetoothViewModel.savedScaleAddress.collectAsState()
    val savedDeviceName by bluetoothViewModel.savedScaleName.collectAsState()

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        bluetoothViewModel.refreshPermissionsStatus()
        val allGranted = permissionsMap.values.all { it }
        if (allGranted) {
            if (!bluetoothViewModel.isBluetoothEnabled()) {
                scope.launch {
                    sharedViewModel.showSnackbar(
                        message = context.getString(R.string.bluetooth_enable_for_scan),
                        duration = SnackbarDuration.Short
                    )
                }
            }
        } else {
            scope.launch {
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.bluetooth_permissions_required_for_scan),
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        bluetoothViewModel.refreshPermissionsStatus()
        if (result.resultCode == Activity.RESULT_OK) {
            if (!bluetoothViewModel.permissionsGranted.value) {
                scope.launch {
                    sharedViewModel.showSnackbar(
                        message = context.getString(R.string.bluetooth_enabled_permissions_missing),
                        duration = SnackbarDuration.Short
                    )
                }
            }
        } else {
            scope.launch {
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.bluetooth_must_be_enabled_for_scan),
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val currentBluetoothEnabledStatus = bluetoothViewModel.isBluetoothEnabled()

        // Status and action area (Scan button or info cards)
        if (!hasPermissions) {
            PermissionRequestCard(onGrantPermissions = {
                permissionsLauncher.launch(
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                )
            })
        } else if (!currentBluetoothEnabledStatus) {
            EnableBluetoothCard(onEnableBluetooth = {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            })
        } else {
            // DISPLAY SAVED SCALE (always visible if one is saved)
            savedDeviceAddress?.let { address ->
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.saved_scale_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = savedDeviceName ?: stringResource(R.string.unknown_device),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Scan button
            Button(
                onClick = {
                    if (isScanning) {
                        bluetoothViewModel.requestStopDeviceScan()
                    } else {
                        bluetoothViewModel.clearAllErrors() // Clear previous errors before starting a new scan
                        bluetoothViewModel.requestStartDeviceScan()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.stop_scan_button))
                } else {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_for_scales_button_desc))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.search_for_scales_button))
                }
            }
        }

        // Error display
        if (hasPermissions && currentBluetoothEnabledStatus) {
            val errorToShow = connectionError ?: scanError
            errorToShow?.let { errorMsg ->
                ErrorCard(errorMsg = errorMsg)
            }
        }

        // Device list
        if (hasPermissions && currentBluetoothEnabledStatus && scanError == null) {
            if (scannedDevices.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.found_devices_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 8.dp)
                        .align(Alignment.Start)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f), // Takes up the remaining space
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(scannedDevices, key = { it.address }) { device ->
                        DeviceCardItem(
                            deviceInfo = device,
                            isCurrentlySaved = device.address == savedDeviceAddress,
                            onClick = {
                                bluetoothViewModel.requestStopDeviceScan() // Stop scan before any action
                                if (device.isSupported) {
                                    // Save device as preferred scale
                                    // (implicitly overwrites any previously saved scale)
                                    bluetoothViewModel.saveDeviceAsPreferred(device)
                                    scope.launch {
                                        sharedViewModel.showSnackbar(
                                            context.getString(R.string.device_saved_as_preferred, device.name ?: context.getString(R.string.unknown_device)),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                    // NO automatic connection attempt anymore
                                } else { // Device is not supported
                                    scope.launch {
                                        sharedViewModel.showSnackbar(
                                            context.getString(R.string.device_not_supported, device.name ?: context.getString(R.string.unknown_device)),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            } else if (!isScanning) { // Only show empty state if not currently scanning and no devices found
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                    message = stringResource(R.string.no_devices_found_start_scan)
                )
            }
        }
    }
}

/**
 * Composable that displays a card requesting Bluetooth permissions.
 *
 * @param onGrantPermissions Callback invoked when the user clicks the button to grant permissions.
 */
@Composable
fun PermissionRequestCard(onGrantPermissions: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.permissions_required_icon_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(stringResource(R.string.permissions_required_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.permissions_required_message_bluetooth),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onGrantPermissions) {
                Text(stringResource(R.string.grant_permissions_button))
            }
        }
    }
}

/**
 * Composable that displays a card prompting the user to enable Bluetooth.
 *
 * @param onEnableBluetooth Callback invoked when the user clicks the button to enable Bluetooth.
 */
@Composable
fun EnableBluetoothCard(onEnableBluetooth: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = stringResource(R.string.bluetooth_disabled_icon_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(stringResource(R.string.bluetooth_disabled_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.bluetooth_disabled_message_enable_for_scan),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onEnableBluetooth) {
                Text(stringResource(R.string.enable_bluetooth_button))
            }
        }
    }
}

/**
 * Composable that displays an error message in a card.
 *
 * @param errorMsg The error message to display.
 */
@Composable
fun ErrorCard(errorMsg: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp) // Consistent padding
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.error_icon_desc), // Generic error description
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Text(
                errorMsg, // Error messages from ViewModel are usually already localized or technical
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Composable that displays an empty state message with an icon.
 * Typically used when a list is empty.
 *
 * @param icon The [ImageVector] to display.
 * @param message The message to display below the icon.
 */
@Composable
fun EmptyState(icon: ImageVector, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // Decorative icon
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Composable that displays a card for a scanned Bluetooth device.
 *
 * @param deviceInfo The [ScannedDeviceInfo] containing details about the device.
 * @param isCurrentlySaved Boolean indicating if this device is the currently saved preferred scale.
 * @param onClick Callback invoked when the card is clicked.
 */
@Composable
fun DeviceCardItem(
    deviceInfo: ScannedDeviceInfo,
    isCurrentlySaved: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val supportColor = if (deviceInfo.isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val unknownDeviceName = stringResource(R.string.unknown_device_placeholder)

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = deviceInfo.name ?: unknownDeviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (isCurrentlySaved) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(R.string.saved_scale_icon_desc),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    text = deviceInfo.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        imageVector = if (deviceInfo.isSupported) Icons.Filled.CheckCircle else Icons.Filled.HighlightOff,
                        contentDescription = if (deviceInfo.isSupported) stringResource(R.string.supported_icon_desc) else stringResource(R.string.not_supported_icon_desc),
                        tint = supportColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (deviceInfo.isSupported) {
                            deviceInfo.determinedHandlerDisplayName ?: stringResource(R.string.supported_label)
                        } else {
                            stringResource(R.string.not_supported_label)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = supportColor,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text( // RSSI value is technical, typically not translated directly but its unit could be.
                text = stringResource(R.string.rssi_format, deviceInfo.rssi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
