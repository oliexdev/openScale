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
package com.health.openscale.ui.screen.overview

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.screen.dialog.DateInputDialog
import com.health.openscale.ui.screen.dialog.NumberInputDialog
import com.health.openscale.ui.screen.dialog.TextInputDialog
import com.health.openscale.ui.screen.dialog.TimeInputDialog
import com.health.openscale.ui.screen.dialog.UserInputDialog
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A screen for creating a new measurement or editing an existing one.
 * It displays a list of available measurement types and allows users to input values for them.
 *
 * @param navController The NavController for navigation.
 * @param measurementId The ID of the measurement to edit. If -1, a new measurement is created.
 * @param userId The ID of the user for whom the measurement is being recorded/edited.
 * @param sharedViewModel The SharedViewModel providing access to data and actions.
 */
@Composable
fun MeasurementDetailScreen(
    navController: NavController,
    measurementId: Int,
    userId: Int,
    sharedViewModel: SharedViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Holds the string representation of measurement values, keyed by MeasurementType ID.
    val valuesState = remember { mutableStateMapOf<Int, String>() }
    var isPendingNavigation by rememberSaveable { mutableStateOf(false) }
    var measurementTimestampState by remember { mutableStateOf(System.currentTimeMillis()) }
    var currentMeasurementDbId by remember { mutableStateOf(0) } // DB ID of the current measurement being edited (0 for new)
    var currentUserIdState by remember { mutableStateOf(userId) } // User ID for the measurement

    // Controls which generic input dialog (Number or Text) is shown, based on MeasurementType.
    var dialogTargetType by remember { mutableStateOf<MeasurementType?>(null) }

    // Flags for date and time dialogs that edit the main measurement timestamp.
    var showDatePickerForMainTimestamp by remember { mutableStateOf(false) }
    var showTimePickerForMainTimestamp by remember { mutableStateOf(false) }

    val allMeasurementTypes by sharedViewModel.measurementTypes.collectAsState()
    val lastMeasurementToPreloadFrom by sharedViewModel.lastMeasurementOfSelectedUser.collectAsState()
    val loadedData by sharedViewModel.currentMeasurementWithValues.collectAsState()

    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.getDefault()) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()) }

    val allUsers by sharedViewModel.allUsers.collectAsState()
    var pendingUserId by remember { mutableStateOf<Int?>(null) }
    var showUserPicker by remember { mutableStateOf(false) }

    // Show a loading indicator if navigation is pending (e.g., after saving).
    if (isPendingNavigation) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Set the current measurement ID in ViewModel and update the top bar title.
    LaunchedEffect(measurementId) {
        sharedViewModel.setCurrentMeasurementId(measurementId)
        sharedViewModel.setTopBarTitle(
            if (measurementId == -1) context.getString(R.string.title_new_measurement)
            else context.getString(R.string.title_edit_measurement)
        )
    }

    // Load data for an existing measurement or preload values for a new measurement.
    LaunchedEffect(loadedData, measurementId, userId, allMeasurementTypes, lastMeasurementToPreloadFrom) {
        if (measurementId != -1 && measurementId != 0) { // Editing an existing measurement
            loadedData?.let { data ->
                currentMeasurementDbId = data.measurement.id
                currentUserIdState = data.measurement.userId // Use UserID from the loaded measurement
                measurementTimestampState = data.measurement.timestamp
                pendingUserId = null
                valuesState.clear()
                data.values.forEach { mvWithType ->
                    // Populate valuesState for non-date/time, enabled types.
                    if (mvWithType.type.isEnabled && mvWithType.type.inputType != InputFieldType.DATE && mvWithType.type.inputType != InputFieldType.TIME) {
                        val valueString = when (mvWithType.type.inputType) {
                            InputFieldType.FLOAT -> mvWithType.value.floatValue?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                            InputFieldType.INT -> mvWithType.value.intValue?.toString() ?: ""
                            InputFieldType.TEXT -> mvWithType.value.textValue ?: ""
                            else -> "" // Should not happen for these types
                        }
                        if (valueString.isNotEmpty()) {
                            valuesState[mvWithType.type.id] = valueString
                        }
                    }
                }
            }
        } else { // Creating a new measurement (measurementId is -1 or 0)
            currentMeasurementDbId = 0
            currentUserIdState = userId // Use the passed userId for a new measurement
            measurementTimestampState = System.currentTimeMillis() // Always use current timestamp for new
            pendingUserId = null
            valuesState.clear()

            // Preload values from the user's last measurement, if available and types are loaded.
            if (allMeasurementTypes.isNotEmpty() && lastMeasurementToPreloadFrom != null) {
                // Ensure the last measurement belongs to the current user.
                if (lastMeasurementToPreloadFrom!!.measurement.userId == userId) {
                    lastMeasurementToPreloadFrom!!.values.forEach { mvFromLast ->
                        val correspondingType = allMeasurementTypes.find { it.id == mvFromLast.type.id }
                        if (correspondingType != null &&
                            correspondingType.isEnabled &&
                            correspondingType.inputType != InputFieldType.DATE &&
                            correspondingType.inputType != InputFieldType.TIME
                        ) {
                            val valueString = when (correspondingType.inputType) {
                                InputFieldType.FLOAT -> mvFromLast.value.floatValue?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                                InputFieldType.INT -> mvFromLast.value.intValue?.toString() ?: ""
                                InputFieldType.TEXT -> mvFromLast.value.textValue ?: ""
                                else -> ""
                            }
                            if (valueString.isNotEmpty()) {
                                valuesState[correspondingType.id] = valueString
                            }
                        }
                    }
                } else {
                    // Log if preloading is skipped due to user mismatch (for debugging).
                    // Consider using a formal logger if this becomes a common scenario to debug.
                    println("DEBUG: lastMeasurementToPreloadFrom.userId (${lastMeasurementToPreloadFrom!!.measurement.userId}) != currentScreenUserId ($userId). Not preloading values.")
                }
            }
        }
    }

    // Configure the top bar save action.
    LaunchedEffect(currentUserIdState, measurementTimestampState, valuesState.toMap()) {
        sharedViewModel.setTopBarAction(
            TopBarAction(
                icon = Icons.Default.Save,
                contentDescription = context.getString(R.string.action_save_measurement),
                onClick = {
                    val effectiveUserIdForSave = pendingUserId ?: currentUserIdState

                    if (effectiveUserIdForSave == -1) {
                        Toast.makeText(context, R.string.toast_no_user_selected, Toast.LENGTH_SHORT).show()
                        return@TopBarAction
                    }

                    if (currentMeasurementDbId == 0 &&
                        lastMeasurementToPreloadFrom != null &&
                        lastMeasurementToPreloadFrom!!.measurement.userId == effectiveUserIdForSave &&
                        measurementTimestampState == lastMeasurementToPreloadFrom!!.measurement.timestamp
                    ) {
                        Toast.makeText(context, R.string.toast_duplicate_timestamp, Toast.LENGTH_LONG).show()
                        return@TopBarAction
                    }

                    val measurementToSave = Measurement(
                        id = currentMeasurementDbId,
                        userId = effectiveUserIdForSave,
                        timestamp = measurementTimestampState
                    )

                    val valueList = mutableListOf<MeasurementValue>()
                    var allConversionsOk = true

                    allMeasurementTypes
                        .filterNot { it.inputType == InputFieldType.DATE || it.inputType == InputFieldType.TIME } // Date/Time handled by main timestamp
                        .filterNot { it.isDerived } // Derived values are calculated, not input
                        .forEach { type ->
                            val inputString = valuesState[type.id]?.trim()

                            if (inputString.isNullOrBlank()) return@forEach // Skip empty values

                            val existingValueId = if (measurementId != -1 && measurementId != 0) {
                                loadedData?.values?.find { v -> v.type.id == type.id }?.value?.id
                                    ?: 0
                            } else 0

                            var floatVal: Float? = null
                            var intVal: Int? = null
                            var textVal: String? = null

                            when (type.inputType) {
                                InputFieldType.FLOAT -> {
                                    floatVal = inputString.toFloatOrNull()
                                    if (floatVal == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.toast_invalid_number_format,
                                                type.getDisplayName(context),
                                                inputString
                                            ),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        allConversionsOk = false
                                    }
                                }

                                InputFieldType.INT -> {
                                    intVal = inputString.toIntOrNull()
                                    if (intVal == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.toast_invalid_integer_format,
                                                type.getDisplayName(context),
                                                inputString
                                            ),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        allConversionsOk = false
                                    }
                                }

                                InputFieldType.TEXT -> {
                                    textVal = inputString
                                }

                                else -> { /* Should not happen due to filters */
                                }
                            }

                            if (!allConversionsOk) return@TopBarAction // Stop processing if a conversion error occurred.

                            valueList.add(
                                MeasurementValue(
                                    id = existingValueId,
                                    measurementId = 0, // This will be set by the ViewModel/Repository upon insertion.
                                    typeId = type.id,
                                    floatValue = floatVal,
                                    intValue = intVal,
                                    textValue = textVal,
                                    dateValue = null // Date/Time values are not stored this way.
                                )
                            )
                        }

                    if (allConversionsOk) {
                        scope.launch {
                            sharedViewModel.saveMeasurement(measurementToSave, valueList)
                        }
                        pendingUserId = null
                        isPendingNavigation = true // Trigger loading indicator and navigate back.
                        navController.popBackStack()
                    }
                })
        )
    }

    // Show loading indicator while data for an existing measurement is being fetched.
    if (measurementId != -1 && measurementId != 0 && loadedData == null) {
        Box(
            modifier = Modifier
                .fillMaxSize() // Changed from fillMaxWidth().padding() to fillMaxSize() for consistency
                .padding(16.dp), // Padding can remain if desired for the indicator's position
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        // Main content: List of measurement value edit rows.
        Column(modifier = Modifier.padding(16.dp)) {
            LazyColumn {
                val activeMeasurementTypes = allMeasurementTypes.filter { it.isEnabled }

                items(activeMeasurementTypes, key = { it.id }) { type ->
                    val displayValue: String
                    val currentValueForIncrementDecrement: String? // Used for increment/decrement operations

                    when (type.inputType) {
                        InputFieldType.DATE -> {
                            displayValue = dateFormat.format(Date(measurementTimestampState))
                            currentValueForIncrementDecrement = null // Not applicable
                        }
                        InputFieldType.TIME -> {
                            displayValue = timeFormat.format(Date(measurementTimestampState))
                            currentValueForIncrementDecrement = null // Not applicable
                        }
                        InputFieldType.USER -> {
                            val effectiveUserId = pendingUserId ?: currentUserIdState
                            val selectedUserName = allUsers
                                .firstOrNull { it.id == effectiveUserId }
                                ?.name
                                ?: stringResource(R.string.placeholder_empty_value)

                            displayValue = selectedUserName
                            currentValueForIncrementDecrement = null
                        }
                        else -> { // For FLOAT, INT, TEXT
                            displayValue = valuesState[type.id] ?: ""
                            currentValueForIncrementDecrement = valuesState[type.id]
                        }
                    }

                    MeasurementValueEditRow(
                        type = type,
                        value = if (displayValue.isBlank() && type.inputType != InputFieldType.DATE && type.inputType != InputFieldType.TIME) {
                            stringResource(R.string.placeholder_empty_value) // Default placeholder for empty non-date/time values
                        } else displayValue,
                        onEditClick = {
                            if (!type.isDerived) {
                                when (type.inputType) {
                                    InputFieldType.DATE -> showDatePickerForMainTimestamp = true
                                    InputFieldType.TIME -> showTimePickerForMainTimestamp = true
                                    InputFieldType.USER -> showUserPicker = true
                                    else -> dialogTargetType = type // Show generic dialog
                                }
                            }
                        },
                        showIncrementDecrement = (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT) && !type.isDerived,
                        onIncrement = if ((type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT) && !type.isDerived) {
                            {
                                val currentStr = currentValueForIncrementDecrement ?: if (type.inputType == InputFieldType.FLOAT) "0.0" else "0"
                                valuesState[type.id] = incrementValue(currentStr, type)
                            }
                        } else null,
                        onDecrement = if ((type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT) && !type.isDerived) {
                            {
                                val currentStr = currentValueForIncrementDecrement ?: if (type.inputType == InputFieldType.FLOAT) "0.0" else "0"
                                valuesState[type.id] = decrementValue(currentStr, type)
                            }
                        } else null
                    )
                }
            }
        }
    }

    // --- Dialogs for FLOAT, INT, TEXT based on dialogTargetType ---
    dialogTargetType?.let { currentType ->
        val measurementTypeIcon = remember(currentType.icon) { currentType.icon }
        val typeColor = remember(currentType.color) { Color(currentType.color) }
        val initialDialogValue = valuesState[currentType.id] ?: when (currentType.inputType) {
            InputFieldType.FLOAT -> "0.0" // Default for empty float
            InputFieldType.INT -> "0"   // Default for empty int
            else -> ""
        }
        val dialogTitle = stringResource(R.string.dialog_title_edit_value, currentType.getDisplayName(context))

        when (currentType.inputType) {
            InputFieldType.FLOAT, InputFieldType.INT -> {
                NumberInputDialog(
                    title = dialogTitle,
                    initialValue = initialDialogValue,
                    inputType = currentType.inputType,
                    unit = currentType.unit,
                    measurementIcon = measurementTypeIcon,
                    iconBackgroundColor = typeColor,
                    onDismiss = { dialogTargetType = null },
                    onConfirm = { confirmedValue ->
                        val trimmedValue = confirmedValue.trim()
                        if (trimmedValue.isEmpty()) {
                            valuesState.remove(currentType.id) // Clear value if input is empty
                            dialogTargetType = null
                        } else {
                            var isValid = false
                            if (currentType.inputType == InputFieldType.FLOAT) {
                                val floatOrNull = trimmedValue.toFloatOrNull()
                                if (floatOrNull != null) {
                                    valuesState[currentType.id] = floatOrNull.toString()
                                    isValid = true
                                } else {
                                    Toast.makeText(context, context.getString(R.string.toast_invalid_number_format_short, currentType.getDisplayName(context)), Toast.LENGTH_SHORT).show()
                                }
                            } else { // INT
                                val intOrNull = trimmedValue.toIntOrNull()
                                if (intOrNull != null) {
                                    valuesState[currentType.id] = intOrNull.toString()
                                    isValid = true
                                } else {
                                    Toast.makeText(context, context.getString(R.string.toast_invalid_integer_format_short, currentType.getDisplayName(context)), Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (isValid) {
                                dialogTargetType = null // Dismiss dialog only on valid input
                            }
                        }
                    }
                )
            }
            InputFieldType.TEXT -> {
                TextInputDialog(
                    title = dialogTitle,
                    initialValue = initialDialogValue,
                    measurementIcon = measurementTypeIcon,
                    iconBackgroundColor = typeColor,
                    onDismiss = { dialogTargetType = null },
                    onConfirm = { confirmedValue ->
                        val finalValue = confirmedValue.trim()
                        if (finalValue.isEmpty()) {
                            valuesState.remove(currentType.id)
                        } else {
                            valuesState[currentType.id] = finalValue
                        }
                        dialogTargetType = null
                    }
                    // Consider `singleLine = true` if appropriate for your TextInputDialog.
                    // If multiline input is needed for specific text types, adjust TextInputDialog and pass the parameter here.
                )
            }
            else -> { /* Should not be reached as DATE/TIME have their own flags and derived are not editable here. */ }
        }
    }

    // --- Dialogs for the main measurement timestamp (measurementTimestampState) ---
    if (showDatePickerForMainTimestamp) {
        val triggeringType = allMeasurementTypes.find { it.key == MeasurementTypeKey.DATE }
        val dateDialogTitle = stringResource(R.string.dialog_title_change_value, triggeringType?.getDisplayName(context) ?: stringResource(R.string.label_date))
        DateInputDialog(
            title = dateDialogTitle,
            initialTimestamp = measurementTimestampState,
            measurementIcon = triggeringType?.icon ?: MeasurementTypeIcon.IC_DATE,
            iconBackgroundColor = triggeringType?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary,
            onDismiss = { showDatePickerForMainTimestamp = false },
            onConfirm = { newDateMillis ->
                // 1. Get the original timestamp and create a calendar in UTC to avoid local timezone issues.
                val originalCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = measurementTimestampState
                }

                // 2. Extract ONLY the time components (hour, minute, second, millisecond) from the original timestamp.
                val timeOfDayMillis = originalCal.get(Calendar.HOUR_OF_DAY) * 3600_000L +
                        originalCal.get(Calendar.MINUTE) * 60_000L +
                        originalCal.get(Calendar.SECOND) * 1000L +
                        originalCal.get(Calendar.MILLISECOND)

                // 3. Add the original time of day to the new date.
                // This correctly combines the new date (from DatePicker) with the old time.
                measurementTimestampState = newDateMillis + timeOfDayMillis

                showDatePickerForMainTimestamp = false
            }
        )
    }

    if (showTimePickerForMainTimestamp) {
        val triggeringType = allMeasurementTypes.find { it.key == MeasurementTypeKey.TIME }
        val timeDialogTitle = stringResource(R.string.dialog_title_change_value, triggeringType?.getDisplayName(context) ?: stringResource(R.string.label_time))
        TimeInputDialog(
            title = timeDialogTitle,
            initialTimestamp = measurementTimestampState,
            measurementIcon = triggeringType?.icon ?: MeasurementTypeIcon.IC_TIME,
            iconBackgroundColor = triggeringType?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary,
            onDismiss = { showTimePickerForMainTimestamp = false },
            onConfirm = { newTimeMillis ->
                val newCal = Calendar.getInstance().apply { timeInMillis = newTimeMillis }
                val currentCal = Calendar.getInstance().apply { timeInMillis = measurementTimestampState }
                currentCal.set(Calendar.HOUR_OF_DAY, newCal.get(Calendar.HOUR_OF_DAY))
                currentCal.set(Calendar.MINUTE, newCal.get(Calendar.MINUTE))
                measurementTimestampState = currentCal.timeInMillis
                showTimePickerForMainTimestamp = false
            }
        )
    }

    if (showUserPicker) {
        val triggeringType = allMeasurementTypes.find { it.key == MeasurementTypeKey.USER }
        val userDialogTitle = stringResource(R.string.dialog_title_change_value, triggeringType?.getDisplayName(context) ?: stringResource(R.string.measurement_type_user))

        UserInputDialog(
            title = userDialogTitle,
            users = allUsers,
            initialSelectedId = pendingUserId ?: currentUserIdState,
            measurementIcon = triggeringType?.icon ?: MeasurementTypeIcon.IC_USER,
            iconBackgroundColor = triggeringType?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary,
            onDismiss = { showUserPicker = false },
            onConfirm = { id ->
                pendingUserId = id
                showUserPicker = false
            }
        )
    }
}

/**
 * A Composable that displays a row for editing a single measurement value.
 * It shows the measurement type's icon, name, current value, and an edit button.
 * For numeric types, it can also show increment/decrement buttons.
 *
 * @param type The [MeasurementType] this row represents.
 * @param value The current string value to display for the measurement type.
 * @param onEditClick Lambda triggered when the user clicks the row or edit button.
 * @param showIncrementDecrement Whether to show increment and decrement buttons (for numeric types).
 * @param onIncrement Lambda triggered when the increment button is clicked.
 * @param onDecrement Lambda triggered when the decrement button is clicked.
 */
@Composable
fun MeasurementValueEditRow(
    type: MeasurementType,
    value: String,
    onEditClick: () -> Unit,
    showIncrementDecrement: Boolean,
    onIncrement: (() -> Unit)? = null,
    onDecrement: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onEditClick, enabled = !type.isDerived) // Clicking row triggers edit, disabled for derived
    ) {
        RoundMeasurementIcon(
            icon = type.icon.resource,
            backgroundTint = Color(type.color),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = type.getDisplayName(context), style = MaterialTheme.typography.bodyLarge)
            val displayText = when (type.inputType) {
                InputFieldType.FLOAT, InputFieldType.INT -> LocaleUtils.formatValueForDisplay(value, type.unit)
                InputFieldType.TEXT, InputFieldType.USER, InputFieldType.DATE, InputFieldType.TIME -> value
                else -> ""
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showIncrementDecrement && onIncrement != null && onDecrement != null && !type.isDerived) {
            Column { // Layout for increment/decrement buttons
                IconButton(onClick = onIncrement, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.content_desc_increase_value, type.getDisplayName(context)))
                }
                IconButton(onClick = onDecrement, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.content_desc_decrease_value, type.getDisplayName(context)))
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))

        // Show edit button only if the type is not derived.
        if (!type.isDerived) {
            IconButton(onClick = onEditClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.content_desc_edit_value, type.getDisplayName(context)))
            }
        }
    }
}

fun incrementValue(value: String, type: MeasurementType): String {
    return when (type.inputType) {
        InputFieldType.INT -> (value.toIntOrNull()?.plus(1) ?: 1).toString()
        InputFieldType.FLOAT -> {
            val step = when (type.unit) {
                UnitType.KG, UnitType.LB, UnitType.PERCENT, UnitType.INCH -> 0.1f
                UnitType.CM -> 0.5f
                UnitType.KCAL -> 10f
                else -> 0.1f
            }

            (value.toFloatOrNull()?.plus(step) ?: 0.1f).toString()
        }
        else -> value
    }
}

fun decrementValue(value: String, type: MeasurementType): String {
    return when (type.inputType) {
        InputFieldType.INT -> (value.toIntOrNull()?.minus(1) ?: 0).toString()
        InputFieldType.FLOAT -> {
            val step = when (type.unit) {
                UnitType.KG, UnitType.LB, UnitType.PERCENT, UnitType.INCH -> 0.1f
                UnitType.CM -> 0.5f
                UnitType.KCAL -> 10f
                else -> 0.1f
            }

            (value.toFloatOrNull()?.minus(step) ?: 0.1f).toString()
        }
        else -> value
    }
}