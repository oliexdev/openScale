
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

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction

/**
 * Creates and remembers a [TopBarAction] for Bluetooth connectivity.
 * This helper encapsulates permission handling and Bluetooth activation logic.
 */
@Composable
fun rememberBluetoothActionButton(
    bluetoothViewModel: BluetoothViewModel,
    sharedViewModel: SharedViewModel,
    navController: NavController
): TopBarAction {
    val TAG = "BluetoothActionButton"
    val context = LocalContext.current

    // Launcher for Bluetooth permissions
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            bluetoothViewModel.connectToSavedDevice()
        }
    }

    // Launcher for enabling Bluetooth hardware
    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            bluetoothViewModel.connectToSavedDevice()
        }
    }

    val connStatus by bluetoothViewModel.connectionStatus.collectAsState()
    val savedDevice by bluetoothViewModel.savedDevice.collectAsState()
    val connectedDevice by bluetoothViewModel.connectedDeviceAddress.collectAsState()
    val currentUser by sharedViewModel.selectedUser.collectAsState()

    return remember(connStatus, savedDevice, connectedDevice, currentUser) {
        val savedAddr = savedDevice?.address
        val deviceName = savedDevice?.name ?: context.getString(R.string.fallback_device_name_saved_scale)

        val isBusy = savedAddr != null &&
                (connStatus == ConnectionStatus.CONNECTING || connStatus == ConnectionStatus.DISCONNECTING)

        when {
            // 1. Connection in progress
            isBusy -> TopBarAction(
                icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                contentDescription = context.getString(R.string.bluetooth_action_connecting_disconnecting_desc),
                onClick = {
                    sharedViewModel.showSnackbar(
                        message = context.getString(
                            when (connStatus) {
                                ConnectionStatus.CONNECTING    -> R.string.snackbar_bluetooth_connecting_to
                                ConnectionStatus.DISCONNECTING -> R.string.snackbar_bluetooth_disconnecting_from
                                else                           -> R.string.snackbar_bluetooth_processing_with
                            },
                            deviceName
                        ),
                        duration = SnackbarDuration.Short
                    )
                }
            )

            // 2. No scale paired yet
            savedAddr == null -> TopBarAction(
                icon = Icons.Default.Bluetooth,
                contentDescription = context.getString(R.string.bluetooth_action_no_scale_saved_desc),
                onClick = {
                    sharedViewModel.setPendingReferenceUserForBle(null)
                    sharedViewModel.showSnackbar(
                        message = context.getString(R.string.snackbar_bluetooth_no_scale_saved),
                        duration = SnackbarDuration.Short
                    )
                    navController.navigate(Routes.BLUETOOTH_SETTINGS)
                }
            )

            // 3. Already connected -> Disconnect on click
            connStatus == ConnectionStatus.CONNECTED -> TopBarAction(
                icon = Icons.Filled.BluetoothConnected,
                contentDescription = context.getString(R.string.bluetooth_action_disconnect_desc, deviceName),
                onClick = {
                    sharedViewModel.setPendingReferenceUserForBle(null)
                    bluetoothViewModel.disconnectDevice()
                    sharedViewModel.showSnackbar(
                        message = context.getString(R.string.snackbar_bluetooth_disconnecting_from, deviceName),
                        duration = SnackbarDuration.Short
                    )
                }
            )

            // 4. Disconnected -> Request permissions/enable BT/connect
            else -> TopBarAction(
                icon = Icons.Filled.BluetoothDisabled,
                contentDescription = context.getString(R.string.bluetooth_action_disconnect_desc, deviceName),
                onClick = {
                    // Check for BOTH permissions (Scan and Connect)
                    val hasScanPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    val hasConnectPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

                    val hasAllPermissions = hasScanPerm && hasConnectPerm

                    if (!hasAllPermissions) {
                        // Launch request for both permissions at once
                        permissionsLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    } else if (!bluetoothViewModel.isBluetoothEnabled()) {
                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } else if (currentUser?.useAssistedWeighing == true) {
                        // Trigger the global dialog via SharedViewModel
                        sharedViewModel.setPendingReferenceUserForBle(null)
                        sharedViewModel.setPendingAssistedWeighingUser(currentUser)
                    } else {
                        sharedViewModel.showSnackbar(
                            message = context.getString(R.string.snackbar_bluetooth_attempting_connection, deviceName),
                            duration = SnackbarDuration.Short
                        )
                        LogManager.d(TAG, "User clicked bluetooth icon connect → trying to connect to saved device $deviceName")

                        sharedViewModel.setPendingReferenceUserForBle(null)
                        bluetoothViewModel.connectToSavedDevice()
                    }
                }
            )
        }
    }
}