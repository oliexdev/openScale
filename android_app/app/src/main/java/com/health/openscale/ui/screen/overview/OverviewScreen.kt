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
package com.health.openscale.ui.screen.overview

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Trend
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.database.UserPreferenceKeys
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.SharedViewModel
import com.health.openscale.ui.screen.ValueWithDifference
import com.health.openscale.ui.screen.bluetooth.BluetoothViewModel
import com.health.openscale.ui.screen.bluetooth.ConnectionStatus
import com.health.openscale.ui.screen.components.LineChart
import com.health.openscale.ui.screen.components.provideFilterTopBarAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Determines the appropriate top bar action based on the Bluetooth connection status.
 * Uses the [SharedViewModel] to display Snackbars for user feedback.
 *
 * @param context The application context.
 * @param savedAddr The address of the currently saved Bluetooth scale, if any.
 * @param connStatusEnum The current connection status to the scale.
 * @param connectedDevice The address of the currently connected device, if any.
 * @param currentNavController The NavController for navigation actions.
 * @param bluetoothViewModel The ViewModel for controlling Bluetooth actions.
 * @param sharedViewModel The SharedViewModel for triggering global Snackbars.
 * @param currentDeviceName The name of the saved scale for more user-friendly messages.
 * @return A [SharedViewModel.TopBarAction] instance or null if no specific action is required.
 */
fun determineBluetoothTopBarAction(
    context : Context,
    savedAddr: String?,
    connStatusEnum: ConnectionStatus,
    connectedDevice: String?,
    currentNavController: NavController,
    bluetoothViewModel: BluetoothViewModel,
    sharedViewModel: SharedViewModel,
    currentDeviceName: String?
): SharedViewModel.TopBarAction? {
    // Logic to determine if a connection or disconnection process is currently active
    val btConnectingOrDisconnecting = savedAddr != null &&
            (connStatusEnum == ConnectionStatus.CONNECTING || connStatusEnum == ConnectionStatus.DISCONNECTING) &&
            // When connecting, connectedDevice might be null or the address being connected to.
            // When disconnecting, connectedDevice should be the address of the device being disconnected.
            (connectedDevice == savedAddr || connStatusEnum == ConnectionStatus.CONNECTING || (connStatusEnum == ConnectionStatus.DISCONNECTING && connectedDevice == savedAddr))

    val deviceNameForMessage = currentDeviceName ?: context.getString(R.string.fallback_device_name_saved_scale)

    return when {
        // Case 1: Connection or disconnection process is actively running
        btConnectingOrDisconnecting -> SharedViewModel.TopBarAction(
            icon = Icons.AutoMirrored.Filled.BluetoothSearching, // Icon for "searching" or "working"
            contentDescription = context.getString(R.string.bluetooth_action_connecting_disconnecting_desc),
            onClick = {
                // Typically, the button is not interactive during this time,
                // but a Snackbar can confirm the ongoing process.
                sharedViewModel.showSnackbar(
                    message = context.getString(
                        when (connStatusEnum) {
                            ConnectionStatus.CONNECTING -> R.string.snackbar_bluetooth_connecting_to
                            ConnectionStatus.DISCONNECTING -> R.string.snackbar_bluetooth_disconnecting_from
                            else -> R.string.snackbar_bluetooth_processing_with // Fallback
                        },
                        deviceNameForMessage
                    ),
                    duration = SnackbarDuration.Short
                )
            }
        )

        // Case 2: No Bluetooth scale is saved
        savedAddr == null -> SharedViewModel.TopBarAction(
            icon = Icons.Default.Bluetooth, // Default Bluetooth icon
            contentDescription = context.getString(R.string.bluetooth_action_no_scale_saved_desc),
            onClick = {
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.snackbar_bluetooth_no_scale_saved),
                    duration = SnackbarDuration.Short
                )
                currentNavController.navigate(Routes.BLUETOOTH_SETTINGS)
            }
        )

        // Case 3: Successfully connected to the saved scale
        savedAddr == connectedDevice && connStatusEnum == ConnectionStatus.CONNECTED -> SharedViewModel.TopBarAction(
            icon = Icons.Filled.BluetoothConnected, // Icon for "connected"
            contentDescription = context.getString(R.string.bluetooth_action_disconnect_desc, deviceNameForMessage),
            onClick = {
                // Trigger the action first, then show the Snackbar
                bluetoothViewModel.disconnectDevice() // IMPORTANT: Trigger disconnection here!
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.snackbar_bluetooth_disconnecting_from, deviceNameForMessage), // Adjusted message
                    duration = SnackbarDuration.Short
                )
            }
        )

        // Case 4: Connection error, and an address is saved
        connStatusEnum == ConnectionStatus.FAILED && savedAddr != null -> SharedViewModel.TopBarAction(
            icon = Icons.Filled.Error, // Error icon
            contentDescription = context.getString(R.string.bluetooth_action_retry_connection_desc, deviceNameForMessage),
            onClick = {
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.snackbar_bluetooth_retry_connection, deviceNameForMessage),
                    duration = SnackbarDuration.Short
                )
                bluetoothViewModel.connectToSavedDevice()
            }
        )

        // Case 5: Connection error, and NO address is saved
        connStatusEnum == ConnectionStatus.FAILED && savedAddr == null -> SharedViewModel.TopBarAction(
            icon = Icons.Filled.Error, // Error icon
            contentDescription = context.getString(R.string.bluetooth_action_error_check_settings_desc),
            onClick = {
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.snackbar_bluetooth_error_check_settings),
                    duration = SnackbarDuration.Short
                )
                currentNavController.navigate(Routes.BLUETOOTH_SETTINGS)
            }
        )

        // Case 6: Saved device exists but is not connected (disconnected, idle, etc.)
        // This case also covers if connStatusEnum = DISCONNECTED, IDLE, or NONE.
        savedAddr != null && (connStatusEnum == ConnectionStatus.DISCONNECTED || connStatusEnum == ConnectionStatus.IDLE || connStatusEnum == ConnectionStatus.NONE) -> SharedViewModel.TopBarAction(
            icon = Icons.Filled.BluetoothDisabled, // Icon for "disconnected" or "ready to connect"
            contentDescription = context.getString(R.string.bluetooth_action_connect_to_desc, deviceNameForMessage),
            onClick = {
                sharedViewModel.showSnackbar(
                    message = context.getString(R.string.snackbar_bluetooth_attempting_connection, deviceNameForMessage),
                    duration = SnackbarDuration.Short
                )
                bluetoothViewModel.connectToSavedDevice()
            }
        )

        // Fallback: If an address is saved, but the state was not specifically covered above,
        // offer to connect. Ideally, this shouldn't be hit often if the logic above is complete.
        // If no device is saved and there's no error/connection attempt,
        // this was already covered by 'savedAddr == null' (leads to settings).
        else -> {
            if (savedAddr != null) {
                // This serves as a generic "Connect" button if a rare state occurs
                SharedViewModel.TopBarAction(
                    icon = Icons.Filled.BluetoothDisabled,
                    contentDescription = context.getString(R.string.bluetooth_action_connect_to_desc, deviceNameForMessage),
                    onClick = {
                        sharedViewModel.showSnackbar(
                            message = context.getString(R.string.snackbar_bluetooth_attempting_connection, deviceNameForMessage),
                            duration = SnackbarDuration.Short
                        )
                        bluetoothViewModel.connectToSavedDevice()
                    }
                )
            } else {
                // If really no other condition applies and no device is saved,
                // and the above cases haven't been met, "Go to settings" is a safe default.
                // This will likely only be hit if connStatusEnum has an unexpected value
                // and savedAddr is null, but that should already be covered by "Case 2".
                // For safety, nonetheless:
                SharedViewModel.TopBarAction(
                    icon = Icons.Default.Bluetooth,
                    contentDescription = context.getString(R.string.bluetooth_action_check_settings_desc),
                    onClick = {
                        sharedViewModel.showSnackbar(
                            message = context.getString(R.string.snackbar_bluetooth_check_settings),
                            duration = SnackbarDuration.Short
                        )
                        currentNavController.navigate(Routes.BLUETOOTH_SETTINGS)
                    }
                )
            }
        }
    }
}

/**
 * The main screen for displaying an overview of measurements, user status, and Bluetooth controls.
 * It allows users to view their measurement history, add new measurements, and manage Bluetooth scale connections.
 *
 * @param navController The [NavController] used for navigating between screens.
 * @param sharedViewModel The [SharedViewModel] providing access to shared data like user selection,
 *                        measurements, and top bar configuration.
 * @param bluetoothViewModel The [BluetoothViewModel] for managing Bluetooth state and actions.
 */
@Composable
fun OverviewScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel
) {
    val selectedUserId by sharedViewModel.selectedUserId.collectAsState()
    val context = LocalContext.current // Used for Toasts and string resources

    // Time filter action for the top bar, specific to this screen's context
    val timeFilterAction = provideFilterTopBarAction(
        sharedViewModel = sharedViewModel,
        screenContextName = UserPreferenceKeys.OVERVIEW_SCREEN_CONTEXT
    )
    val enrichedMeasurements by sharedViewModel.enrichedMeasurementsFlow.collectAsState()
    val isLoading by sharedViewModel.isBaseDataLoading.collectAsState()

    // --- Chart selection logic reverted to local state management ---
    val allMeasurementTypes by sharedViewModel.measurementTypes.collectAsState()

    val localSelectedOverviewGraphTypeIntIds = remember { mutableStateListOf<Int>() }

    // Derived list of MeasurementType objects that are selected for the chart.
    val selectedLineTypesForOverviewChart = remember(allMeasurementTypes, localSelectedOverviewGraphTypeIntIds.toList()) {
        allMeasurementTypes.filter { type ->
            type.id in localSelectedOverviewGraphTypeIntIds &&
                    type.isEnabled && // Ensure the type is globally enabled
                    (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT) // Ensure it's a plottable type
        }
    }
    // --- End of reverted chart selection logic ---

    val savedDeviceAddress by bluetoothViewModel.savedScaleAddress.collectAsState()
    val connectionStatus by bluetoothViewModel.connectionStatus.collectAsState()
    val connectedDeviceAddr by bluetoothViewModel.connectedDeviceAddress.collectAsState()
    val savedDeviceNameString by bluetoothViewModel.savedScaleName.collectAsState()

    // Determine the Bluetooth action for the top bar
    val bluetoothTopBarAction = determineBluetoothTopBarAction(
        context = context,
        savedAddr = savedDeviceAddress,
        connStatusEnum = connectionStatus,
        connectedDevice = connectedDeviceAddr,
        currentNavController = navController,
        bluetoothViewModel = bluetoothViewModel,
        sharedViewModel = sharedViewModel,
        currentDeviceName = savedDeviceNameString
    )

    // LaunchedEffect to configure the top bar based on the current state
    LaunchedEffect(
        selectedUserId,
        isLoading,
        enrichedMeasurements.isNotEmpty(),
        bluetoothTopBarAction,
        selectedLineTypesForOverviewChart.isNotEmpty(),
        timeFilterAction,
        savedDeviceAddress,
        connectionStatus,
        connectedDeviceAddr
    ) {
        sharedViewModel.setTopBarTitle(context.getString(R.string.route_title_overview))
        val actions = mutableListOf<SharedViewModel.TopBarAction>()

        // 0. Add Bluetooth action (if determined) at the beginning
        bluetoothTopBarAction?.let { btAction ->
            actions.add(btAction)
        }

        // 1. Add "Add Measurement" icon
        actions.add(
            SharedViewModel.TopBarAction(
                icon = Icons.Default.Add,
                contentDescription = context.getString(R.string.action_add_measurement_desc),
                onClick = {
                    if (selectedUserId != null) {
                        navController.navigate(Routes.measurementDetail(measurementId = null, userId = selectedUserId!!))
                    } else {
                        Toast.makeText(context, context.getString(R.string.toast_select_user_first), Toast.LENGTH_SHORT).show()
                    }
                }
            )
        )

        // Condition for showing filter icons
        if (selectedUserId != null && (!isLoading || enrichedMeasurements.isNotEmpty())) {
            // Show time filter if the chart is visible (i.e., types are selected locally) or if not loading
            if (selectedLineTypesForOverviewChart.isNotEmpty() || !isLoading) {
                timeFilterAction?.let { actions.add(it) }
            }
        }
        sharedViewModel.setTopBarActions(actions)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedUserId == null) {
            // Display a card prompting user selection if no user is active
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                NoUserSelectedCard(navController = navController)
            }
        } else {
            // Display content for the selected user

            // Loading state for the chart (if data is loading and measurements are empty)
            if (isLoading && enrichedMeasurements.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp), // Height of the chart area
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (!isLoading) { // Show chart if not loading
                Box(modifier = Modifier.fillMaxWidth()) {
                    LineChart(
                        sharedViewModel = sharedViewModel, // Still useful for other chart data if needed
                        screenContextName = UserPreferenceKeys.OVERVIEW_SCREEN_CONTEXT, // Still useful for context
                        showFilterControls = true, // Allow user to select types to display on chart
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(bottom = 8.dp),
                        showYAxis = false
                    )
                }
            }

            // Divider: shown if measurements exist OR if no chart types are selected (list shown directly)
            if (enrichedMeasurements.isNotEmpty() || selectedLineTypesForOverviewChart.isEmpty()) { // << USE LOCAL STATE
                HorizontalDivider()
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isLoading && enrichedMeasurements.isEmpty()) {
                    // Loading is handled by the CircularProgressIndicator above
                } else if (!isLoading && enrichedMeasurements.isEmpty() && selectedUserId != null) {
                    // If not loading, and there are no measurements for the selected user
                    NoMeasurementsCard(
                        navController = navController,
                        selectedUserId = selectedUserId
                    )
                } else if (enrichedMeasurements.isNotEmpty()) {
                    // Display the list of measurements
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(
                            items = enrichedMeasurements,
                            key = { _, item -> item.measurementWithValues.measurement.id }
                        ) { _, enrichedItem ->
                            MeasurementCard(
                                measurementWithValues = enrichedItem.measurementWithValues,
                                processedValuesForDisplay = enrichedItem.valuesWithTrend,
                                onEdit = {
                                    navController.navigate(
                                        Routes.measurementDetail(
                                            enrichedItem.measurementWithValues.measurement.id,
                                            selectedUserId!!
                                        )
                                    )
                                },
                                onDelete = {
                                    sharedViewModel.deleteMeasurement(enrichedItem.measurementWithValues.measurement)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A Composable card displayed when no user is currently selected/active.
 * It prompts the user to add or select a user.
 *
 * @param navController The [NavController] for navigating to the user creation/selection screen.
 */
@Composable
fun NoUserSelectedCard(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f), // Take 90% of width
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PersonSearch,
                    contentDescription = null, // Decorative icon
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.no_user_selected_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.no_user_selected_message),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        // Navigate to user detail screen with -1 to indicate new user creation
                        navController.navigate(Routes.userDetail(-1))
                    },
                    modifier = Modifier.fillMaxWidth(0.8f) // Take 80% of card width
                ) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = null, // Decorative icon within button
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.action_add_user))
                }
            }
        }
    }
}

/**
 * A Composable card displayed when a user is selected but has no measurements recorded yet.
 * It prompts the user to add their first measurement.
 *
 * @param navController The [NavController] for navigating to the measurement creation screen.
 * @param selectedUserId The ID of the currently selected user, to pass to the measurement creation screen.
 */
@Composable
fun NoMeasurementsCard(navController: NavController, selectedUserId: Int?) {
    Box(
        modifier = Modifier
            .fillMaxSize() // Important: To occupy the space assigned by Box(weight(1f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Assessment, // Icon suggesting measurement/stats
                    contentDescription = null, // Decorative icon
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.no_measurements_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.no_measurements_message),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton( // A less prominent button style
                    onClick = {
                        if (selectedUserId != null) {
                            // Navigate to measurement detail screen for new measurement
                            navController.navigate(Routes.measurementDetail(measurementId = null, userId = selectedUserId))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    enabled = selectedUserId != null // Button is enabled only if a user is selected
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null, // Decorative icon within button
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.action_add_measurement))
                }
            }
        }
    }
}


/**
 * A Composable card that displays a single measurement entry, including its date,
 * pinned values, and an expandable section for non-pinned values.
 * Provides actions to edit or delete the measurement.
 *
 * @param measurementWithValues The [MeasurementWithValues] object containing the measurement data and its associated values.
 * @param processedValuesForDisplay A list of [ValueWithDifference] objects, derived from the measurement,
 *                                  including trend information and formatted for display.
 * @param onEdit Callback function triggered when the edit action is selected.
 * @param onDelete Callback function triggered when the delete action is selected.
 */
@Composable
fun MeasurementCard(
    measurementWithValues: MeasurementWithValues,
    processedValuesForDisplay: List<ValueWithDifference>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormatted = remember(measurementWithValues.measurement.timestamp) {
        SimpleDateFormat("E, dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date(measurementWithValues.measurement.timestamp))
    }

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    // Separate values into pinned and non-pinned lists for distinct display logic
    val pinnedValues = remember(processedValuesForDisplay) {
        processedValuesForDisplay.filter { it.currentValue.type.isPinned && it.currentValue.type.isEnabled }
    }
    val nonPinnedValues = remember(processedValuesForDisplay) {
        processedValuesForDisplay.filter { !it.currentValue.type.isPinned && it.currentValue.type.isEnabled }
    }
    // All active (enabled) values to check if any data should be displayed
    val allActiveProcessedValues = remember(processedValuesForDisplay) {
        processedValuesForDisplay.filter { it.currentValue.type.isEnabled }
    }


    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Header row: Date and action buttons (Edit, Delete, Expand/Collapse)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
            ) {
                Text(
                    text = dateFormatted,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f) // Date takes available space
                )
                val iconButtonSize = 36.dp // Standard size for action icons
                val actionIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)

                IconButton(onClick = onEdit, modifier = Modifier.size(iconButtonSize)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.action_edit_measurement_desc, dateFormatted),
                        tint = actionIconColor
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(iconButtonSize)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete_measurement_desc, dateFormatted),
                        tint = actionIconColor
                    )
                }

                // Conditional expand/collapse icon button for non-pinned values,
                // only shown if there are non-pinned values and no pinned values (to avoid duplicate expand button logic)
                if (nonPinnedValues.isNotEmpty() && pinnedValues.isEmpty()) {
                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(iconButtonSize)) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(if (isExpanded) R.string.action_show_less_desc else R.string.action_show_more_desc)
                        )
                    }
                }
            }

            // Section for pinned measurement values (always visible if present)
            Column(
                modifier = Modifier.padding(
                    start = 16.dp, end = 16.dp,
                    top = if (pinnedValues.isNotEmpty()) 8.dp else 0.dp, // Add top padding only if there are pinned values
                    bottom = 0.dp // Bottom padding handled by AnimatedVisibility or Spacer later
                )
            ) {
                if (pinnedValues.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        pinnedValues.forEach { valueWithTrend ->
                            MeasurementValueRow(valueWithTrend)
                        }
                    }
                }
            }

            // Animated section for non-pinned measurement values (collapsible)
            if (nonPinnedValues.isNotEmpty()) {
                AnimatedVisibility(visible = isExpanded || pinnedValues.isEmpty()) { // Also visible if no pinned values and not expanded (default state)
                    Column(
                        modifier = Modifier.padding(
                            start = 16.dp, end = 16.dp,
                            top = if (pinnedValues.isNotEmpty()) 12.dp else 8.dp, // Smaller top padding if no pinned values
                            bottom = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        nonPinnedValues.forEach { valueWithTrend ->
                            MeasurementValueRow(valueWithTrend)
                        }
                    }
                }
            }

            // Footer: Expand/Collapse TextButton (only if there are non-pinned values and also pinned values,
            // or if there are non-pinned values and it's not the default expanded state for only non-pinned).
            if (nonPinnedValues.isNotEmpty() && (pinnedValues.isNotEmpty() || !isExpanded) ) {
                // Show divider if the expandable section is visible or if pinned items are present (button will always be there)
                if (isExpanded || pinnedValues.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(
                        top = if (isExpanded && nonPinnedValues.isNotEmpty()) 4.dp else if (pinnedValues.isNotEmpty()) 8.dp else 0.dp,
                        bottom = 0.dp
                    ))
                }

                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp), // Consistent height for the button
                    shape = MaterialTheme.shapes.extraSmall // Less rounded corners for a subtle look
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        tint = MaterialTheme.colorScheme.secondary, // Use secondary color for emphasis
                        contentDescription = stringResource(if (isExpanded) R.string.action_show_less_desc else R.string.action_show_more_desc)
                    )
                }
            }

            // Message if no active measurement values are present for this entry
            if (allActiveProcessedValues.isEmpty()) {
                Text(
                    stringResource(R.string.no_active_values_for_measurement),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            // Add padding at the end of the card if only pinned values are shown and no footer (expand/collapse button) is present
            if (pinnedValues.isNotEmpty() && nonPinnedValues.isEmpty() && allActiveProcessedValues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * A row Composable that displays a single measurement value, including its type icon,
 * name, value, unit, and trend indicator if applicable.
 *
 * @param valueWithTrend The [ValueWithDifference] object containing the current value,
 *                       type information, difference from a previous value, and trend.
 */
@Composable
fun MeasurementValueRow(valueWithTrend: ValueWithDifference) {
    val type = valueWithTrend.currentValue.type
    val originalValue = valueWithTrend.currentValue.value // This is Measurement.Value object
    val difference = valueWithTrend.difference
    val trend = valueWithTrend.trend

    val displayValue = when (type.inputType) {
        InputFieldType.FLOAT -> originalValue.floatValue?.let { "%.1f".format(Locale.getDefault(), it) }
        InputFieldType.INT -> originalValue.intValue?.toString()
        InputFieldType.TEXT -> originalValue.textValue
        InputFieldType.DATE -> originalValue.dateValue?.let {
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it))
        }
        InputFieldType.TIME -> originalValue.dateValue?.let {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        }
    } ?: "-" // Default to dash if value is null

    val context = LocalContext.current
    val iconId = remember(type.icon) {
        // Attempt to get the drawable resource ID for the type's icon name
        // This relies on the icon name string matching a drawable resource name
        context.resources.getIdentifier(type.icon, "drawable", context.packageName)
    }
    // Dynamic content description for the icon based on type name
    val iconContentDescription = stringResource(R.string.measurement_type_icon_desc, type.getDisplayName(context))
    // Fallback content description if the icon is not found (e.g. shows question mark)
    val unknownTypeContentDescription = stringResource(R.string.measurement_type_icon_unknown_desc, type.getDisplayName(context))


    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left part: Icon and Type Name
        Row(
            modifier = Modifier.weight(1f), // Takes available space, pushing value & trend to the right
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp) // Standardized size for the icon container
                    .clip(CircleShape)
                    .background(Color(type.color)),
                contentAlignment = Alignment.Center
            ) {
                if (iconId != 0) { // Check if the resource ID is valid
                    Icon(
                        painter = painterResource(id = iconId),
                        contentDescription = type.getDisplayName(context),
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    // Fallback icon if the specified icon resource is not found
                    Icon(
                        imageVector = Icons.Filled.QuestionMark,
                        contentDescription = unknownTypeContentDescription,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Black
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = type.getDisplayName(context),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (difference != null && trend != Trend.NOT_APPLICABLE) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val trendIconVector = when (trend) {
                            Trend.UP -> Icons.Filled.ArrowUpward
                            Trend.DOWN -> Icons.Filled.ArrowDownward
                            Trend.NONE -> null
                            else -> null
                        }
                        val subtleGrayColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        if (trendIconVector != null) {
                            Icon(
                                imageVector = trendIconVector,
                                contentDescription = trend.name,
                                tint = subtleGrayColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text = (if (difference > 0 && trend != Trend.NONE) "+" else "") +
                                    when (type.inputType) {
                                        InputFieldType.FLOAT -> "%.1f".format(Locale.getDefault(), difference)
                                        InputFieldType.INT -> difference.toInt().toString()
                                        else -> ""
                                    } + " ${type.unit.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = subtleGrayColor
                        )
                    }
                } else if (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT) {
                    Spacer(modifier = Modifier.height((MaterialTheme.typography.bodySmall.fontSize.value + 2).dp))
                }
            }
        }
        Text(
            text = "$displayValue ${type.unit.displayName}",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
