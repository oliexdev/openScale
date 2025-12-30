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
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.scales.DeviceCapability
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.health.openscale.ui.navigation.Routes

/**
 * Main Bluetooth settings screen.
 *
 * Responsibilities:
 * - Permission and BT enable flow.
 * - Start/stop scanning and show discovered devices.
 * - Show the currently saved device (if any), with actions:
 *     • Remove saved device
 *     • Enable debug (sets saved name to "Debug"; overview connects via Debug handler)
 * - Read-only display of DeviceSupport info for the saved device.
 *
 * Note: Connecting happens in the Overview via the BT icon, not here.
 */
@Composable
fun BluetoothScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel
) {
    val TAG = "BluetoothScreen"
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Observed state from ViewModel/facade
    val scannedDevices by bluetoothViewModel.scannedDevices.collectAsState()
    val savedDevice by bluetoothViewModel.savedDevice.collectAsState()
    val isScanning by bluetoothViewModel.isScanning.collectAsState()
    val scanError by bluetoothViewModel.scanError.collectAsState()
    val connectionError by bluetoothViewModel.connectionError.collectAsState()
    val savedSupport by bluetoothViewModel.savedDeviceSupport.collectAsState()

    // Local UI state
    var hasPermissions by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf(false) }
    var showCompatibilityDialog by remember { mutableStateOf(false) }
    var deviceToSave by remember { mutableStateOf<ScannedDeviceInfo?>(null) }

    LaunchedEffect(Unit) {
        hasPermissions = hasBtPermissions(context)
        sharedViewModel.setTopBarActions(emptyList())
    }

    // Ensure scanning stops when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            pendingScan = false
            bluetoothViewModel.requestStopDeviceScan()
        }
    }

    // Launcher for enabling Bluetooth
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (hasPermissions) {
                if (pendingScan) {
                    bluetoothViewModel.clearAllErrors()
                    if (!isScanning) {
                        LogManager.d(TAG, "Launching Bluetooth scan via enableBluetoothLauncher (user enabled BT)")
                        bluetoothViewModel.requestStartDeviceScan()
                    }
                    pendingScan = false
                }
            } else {
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

    // Launcher for runtime permissions
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val allGranted = map.values.all { it }
        hasPermissions = allGranted
        if (allGranted) {
            if (bluetoothViewModel.isBluetoothEnabled()) {
                if (pendingScan && !isScanning) {
                    bluetoothViewModel.clearAllErrors()
                    LogManager.d(TAG, "Launching Bluetooth scan via permissionsLauncher (permissions granted)")
                    bluetoothViewModel.requestStartDeviceScan()
                }
                pendingScan = false
            } else {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            pendingScan = false
            scope.launch {
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.bluetooth_permissions_required_for_scan),
                    duration = SnackbarDuration.Long
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
        val btEnabled = bluetoothViewModel.isBluetoothEnabled()

        // Permission / power gates
        when {
            !hasPermissions -> {
                PermissionRequestCard(onGrantPermissions = {
                    pendingScan = true
                    permissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                })
            }
            !btEnabled -> {
                EnableBluetoothCard(onEnableBluetooth = {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                })
            }
            else -> {
                // --- SAVED DEVICE CARD (only if a device is actually saved) ---
                val hasSaved = savedDevice != null
                if (hasSaved) {
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
                                        text = savedDevice?.name ?: stringResource(R.string.unknown_device),
                                        style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = savedDevice?.address ?: "-",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { navController.navigate(Routes.BLUETOOTH_DETAIL) }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(R.string.content_desc_edit_type),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Read-only DeviceSupport details (compact icons)
                        savedSupport?.let { support ->
                            CapabilityIconsRow(
                                support = support,
                                onExplain = { label, implemented ->
                                    scope.launch {
                                        sharedViewModel.showSnackbar(
                                            message = if (implemented)
                                                context.getString(R.string.cap_state_implemented, label)
                                            else
                                                context.getString(R.string.cap_state_supported_only, label),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.size(16.dp))
                }


                // --- SCAN BUTTON ---
                ScanButton(
                    isScanning = isScanning,
                    onToggle = {
                        if (isScanning) {
                            bluetoothViewModel.requestStopDeviceScan()
                        } else {
                            pendingScan = true
                            bluetoothViewModel.clearAllErrors()
                            when {
                                !hasPermissions -> {
                                    permissionsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT
                                        )
                                    )
                                }
                                !bluetoothViewModel.isBluetoothEnabled() -> {
                                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                }
                                else -> {
                                    LogManager.d(TAG, "Launching Bluetooth scan directly via ScanButton")

                                    bluetoothViewModel.requestStartDeviceScan()
                                    pendingScan = false
                                }
                            }
                        }
                    }
                )
            }
        }


        if (showCompatibilityDialog) {
            CompatibilityAlertDialog(
                onConfirm = {
                    deviceToSave?.let {
                        bluetoothViewModel.saveDeviceAsPreferred(it)
                        scope.launch {
                            sharedViewModel.showSnackbar(
                                context.getString(
                                    R.string.device_saved_as_preferred,
                                    it.name
                                ),
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                },
                onDismiss = {
                    showCompatibilityDialog = false
                    deviceToSave = null
                }
            )
        }

        // Combined scan/connection error presentation
        if (hasPermissions && btEnabled) {
            val errorToShow = connectionError ?: scanError
            errorToShow?.let { ErrorCard(errorMsg = it) }
        }

        // --- DEVICE LIST ---
        if (hasPermissions && btEnabled && scanError == null) {
            if (scannedDevices.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.found_devices_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 8.dp)
                        .align(Alignment.Start)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(scannedDevices, key = { it.address }) { device ->
                        DeviceCardItem(
                            deviceInfo = device,
                            savedAddress = savedDevice?.address,
                            onSavePreferred = {
                                if (device.isSupported) {
                                    deviceToSave = device
                                    showCompatibilityDialog = true
                                } else {
                                    scope.launch {
                                        sharedViewModel.showSnackbar(
                                            context.getString(
                                                R.string.device_not_supported,
                                                device.name ?: context.getString(R.string.unknown_device)
                                            ),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            } else if (!isScanning) {
                // Only show empty state when not scanning and nothing was found
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                    message = stringResource(R.string.no_devices_found_start_scan)
                )
            }
        }
    }
}

/* ---------- Reusable bits below -------------------------------------------------------------- */

@Composable
private fun ScanButton(
    isScanning: Boolean,
    onToggle: () -> Unit
) {
    Button(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth()
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
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.search_for_scales_button_desc)
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.search_for_scales_button))
        }
    }
}

/**
 * Shows a disclaimer dialog before saving a device.
 * The user must acknowledge the disclaimer to proceed with saving.
 */
@Composable
private fun CompatibilityAlertDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val projectUrl = "https://github.com/oliexdev/openScale/"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text(stringResource(R.string.compatibility_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bluetooth_compatibility_disclaimer))
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, projectUrl.toUri())
                        context.startActivity(intent)
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(id = R.string.go_to_project_website_button))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.compatibility_dialog_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

/**
 * Compact, icon-only capability row:
 * - Implemented features: normal tint (onSurface)
 * - Supported-only (not implemented): reduced alpha (visibly "disabled")
 * Tap an icon to show a short explanation via Snackbar.
 */
@Composable
private fun CapabilityIconsRow(
    support: DeviceSupport,
    onExplain: (label: String, implemented: Boolean) -> Unit
) {
    val primary = MaterialTheme.colorScheme.onSurface
    val disabled = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    // Iterate in a stable order; only show capabilities that are at least supported.
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        DeviceCapability.values().forEach { cap ->
            if (support.capabilities.contains(cap)) {
                val impl = support.implemented.contains(cap)
                val tint = if (impl) primary else disabled
                val label = stringResource(cap.labelRes)

                IconButton(onClick = {
                    onExplain(label, impl)
                }) {
                    Icon(
                        imageVector = cap.icon,
                        contentDescription = label,
                        tint = tint
                    )
                }
            }
        }
    }
}

/**
 * Card item for a discovered Bluetooth device.
 * - Name/MAC left, RSSI + ⋮-menu right (menu is anchored to the rightmost IconButton).
 * - Menu offers:
 *     • Save as preferred (if supported)
 *     • Save as debug (always available)
 */
@Composable
fun DeviceCardItem(
    deviceInfo: ScannedDeviceInfo,
    savedAddress: String?,
    onSavePreferred: () -> Unit,
) {
    val supportColor =
        if (deviceInfo.isSupported) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val isCurrentlySaved = (deviceInfo.address == savedAddress)

    ElevatedCard(
        onClick = onSavePreferred, // tapping the card still does the "save preferred" flow if supported
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: name + address + supported label
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = deviceInfo.name ?: stringResource(R.string.unknown_device),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (deviceInfo.isSupported) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = if (deviceInfo.isSupported)
                            stringResource(R.string.supported_icon_desc)
                        else
                            stringResource(R.string.not_supported_icon_desc),
                        tint = supportColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (deviceInfo.isSupported)
                            (deviceInfo.determinedHandlerDisplayName ?: stringResource(R.string.supported_label))
                        else
                            stringResource(R.string.not_supported_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = supportColor
                    )
                }
            }

            // Right: RSSI
            Text(
                text = stringResource(R.string.rssi_format, deviceInfo.rssi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Permission request helper card. */
@Composable
fun PermissionRequestCard(onGrantPermissions: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = stringResource(R.string.permissions_required_icon_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                stringResource(R.string.permissions_required_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.permissions_required_message_bluetooth),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onGrantPermissions) {
                Text(stringResource(R.string.grant_permissions_button))
            }
        }
    }
}

/** Bluetooth power-on helper card. */
@Composable
fun EnableBluetoothCard(onEnableBluetooth: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.BluetoothDisabled,
                contentDescription = stringResource(R.string.bluetooth_disabled_icon_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                stringResource(R.string.bluetooth_disabled_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.bluetooth_disabled_message_enable_for_scan),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onEnableBluetooth) {
                Text(stringResource(R.string.enable_bluetooth_button))
            }
        }
    }
}

/** Error presentation card (scan/connection). */
@Composable
fun ErrorCard(errorMsg: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.error_icon_desc),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Text(
                errorMsg,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Empty state for when there are no scanned devices. */
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
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Runtime BT permission helper. */
private fun hasBtPermissions(context: android.content.Context): Boolean {
    val scan = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_SCAN
    ) == PackageManager.PERMISSION_GRANTED
    val connect = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
    return scan && connect
}
