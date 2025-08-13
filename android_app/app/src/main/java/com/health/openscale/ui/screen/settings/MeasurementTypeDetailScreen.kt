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

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType
import com.health.openscale.ui.screen.SharedViewModel
import com.health.openscale.ui.screen.dialog.ColorPickerDialog
import com.health.openscale.ui.screen.dialog.IconPickerDialog
import com.health.openscale.ui.screen.dialog.getIconResIdByName
import kotlin.text.lowercase

/**
 * Composable screen for creating or editing a [MeasurementType].
 * It allows users to define the name, unit, input type, color, icon,
 * and enabled/pinned status for a measurement type.
 *
 * @param navController NavController for navigating back after saving or cancelling.
 * @param typeId The ID of the [MeasurementType] to edit. If -1, a new type is being created.
 * @param sharedViewModel The [SharedViewModel] for accessing shared app state like existing measurement types and setting top bar properties.
 * @param settingsViewModel The [SettingsViewModel] for performing add or update operations on measurement types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementTypeDetailScreen(
    navController: NavController,
    typeId: Int,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current

    val measurementTypes by sharedViewModel.measurementTypes.collectAsState()
    val existingType = remember(measurementTypes, typeId) {
        measurementTypes.find { it.id == typeId }
    }
    val isEdit = typeId != -1

    var name by remember { mutableStateOf(existingType?.getDisplayName(context).orEmpty()) }
    var selectedUnit by remember { mutableStateOf(existingType?.unit ?: UnitType.NONE) }
    var selectedInputType by remember { mutableStateOf(existingType?.inputType ?: InputFieldType.FLOAT) }
    var selectedColor by remember { mutableStateOf(existingType?.color ?: 0xFF6200EE.toInt()) } // Default color
    var selectedIcon by remember { mutableStateOf(existingType?.icon ?: "ic_weight") } // Default icon
    var isEnabled by remember { mutableStateOf(existingType?.isEnabled ?: true) } // Default to true for new types
    var isPinned by remember { mutableStateOf(existingType?.isPinned ?: false) } // Default to false for new types
    var isOnRightYAxis by remember { mutableStateOf(existingType?.isOnRightYAxis ?: false) }

    var expandedUnit by remember { mutableStateOf(false) }
    var expandedInputType by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingUpdatedType by remember { mutableStateOf<MeasurementType?>(null) }

    val titleEdit = stringResource(R.string.measurement_type_detail_title_edit)
    val titleAdd = stringResource(R.string.measurement_type_detail_title_add)

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(
            if (isEdit) titleEdit
            else titleAdd
        )
        sharedViewModel.setTopBarAction(
            SharedViewModel.TopBarAction(icon = Icons.Default.Save, onClick = {
                if (name.isNotBlank()) {
                    val updatedType = MeasurementType(
                        id = existingType?.id ?: 0, // Use 0 for new types, Room will autogenerate
                        name = name,
                        icon = selectedIcon,
                        color = selectedColor,
                        unit = selectedUnit,
                        inputType = selectedInputType,
                        displayOrder = existingType?.displayOrder ?: measurementTypes.size,
                        isEnabled = isEnabled,
                        isPinned = isPinned,
                        key = existingType?.key ?: MeasurementTypeKey.CUSTOM, // New types are custom
                        isDerived = existingType?.isDerived ?: false, // New types are not derived by default
                        isOnRightYAxis = isOnRightYAxis
                    )

                    if (isEdit) {
                        val unitChanged = existingType!!.unit != updatedType.unit
                        val inputTypesAreFloat = existingType!!.inputType == InputFieldType.FLOAT && updatedType.inputType == InputFieldType.FLOAT

                        if (unitChanged && inputTypesAreFloat) {
                            pendingUpdatedType = updatedType
                            showConfirmDialog = true
                        } else {
                            settingsViewModel.updateMeasurementType(updatedType)
                            navController.popBackStack()
                        }
                    } else {
                        settingsViewModel.addMeasurementType(updatedType)
                        navController.popBackStack()
                    }
                } else {
                    Toast.makeText(context, R.string.toast_enter_valid_data, Toast.LENGTH_SHORT).show()
                }
            })
        )
    }

    if (showConfirmDialog && existingType != null && pendingUpdatedType != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.measurement_type_dialog_confirm_unit_change_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.measurement_type_dialog_confirm_unit_change_message,
                        existingType!!.getDisplayName(context),
                        existingType!!.unit.name.lowercase().replaceFirstChar { it.uppercase() },
                        pendingUpdatedType!!.unit.name.lowercase().replaceFirstChar { it.uppercase() }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsViewModel.updateMeasurementTypeAndConvertDataViewModelCentric(
                        originalType = existingType!!,
                        updatedType = pendingUpdatedType!!
                    )
                    showConfirmDialog = false
                    navController.popBackStack()
                }) {
                    Text(stringResource(R.string.confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedSettingRow(label = stringResource(R.string.measurement_type_label_enabled)) {
            Switch(
                checked = isEnabled,
                onCheckedChange = { isEnabled = it }
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.measurement_type_label_name)) },
            modifier = Modifier.fillMaxWidth()
        )

        // Color Selector
        OutlinedTextField(
            value = String.format("#%06X", 0xFFFFFF and selectedColor), // Display color hex string
            onValueChange = {}, // Read-only
            label = { Text(stringResource(R.string.measurement_type_label_color)) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showColorPicker = true },
            readOnly = true,
            enabled = false, // To make it look like a display field that's clickable
            trailingIcon = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(selectedColor))
                        .border(1.dp, Color.Gray, CircleShape) // Visually indicate the color
                )
            },
            colors = TextFieldDefaults.colors( // Custom colors to make it look enabled despite being readOnly
                disabledTextColor = LocalContentColor.current,
                disabledIndicatorColor = MaterialTheme.colorScheme.outline, // Standard outline
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant, // Standard label color
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent // No background fill
            )
        )

        // Icon Selector
        OutlinedTextField(
            value = selectedIcon, // Display selected icon name
            onValueChange = {}, // Read-only
            label = { Text(stringResource(R.string.measurement_type_label_icon)) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showIconPicker = true },
            readOnly = true,
            enabled = false, // To make it look like a display field
            trailingIcon = {
                Icon(
                    painter = runCatching {
                        painterResource(id = getIconResIdByName(selectedIcon))
                    }.getOrElse {
                        // Fallback icon if resource name is invalid or not found
                        Icons.Filled.QuestionMark
                    } as Painter, // Cast is safe due to getOrElse structure
                    contentDescription = stringResource(R.string.content_desc_selected_icon_preview),
                    modifier = Modifier.size(24.dp)
                )
            },
            colors = TextFieldDefaults.colors( // Custom colors for consistent look
                disabledTextColor = LocalContentColor.current,
                disabledIndicatorColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent
            )
        )

        // UnitType Dropdown
        ExposedDropdownMenuBox(
            expanded = expandedUnit,
            onExpandedChange = { expandedUnit = !expandedUnit }
        ) {
            OutlinedTextField(
                readOnly = true,
                value = selectedUnit.name.lowercase().replaceFirstChar { it.uppercase() }, // Format for display
                onValueChange = {},
                label = { Text(stringResource(R.string.measurement_type_label_unit)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedUnit)
                },
                modifier = Modifier
                    .menuAnchor( // Required for ExposedDropdownMenu
                        type = MenuAnchorType.PrimaryNotEditable,
                        enabled = true
                    )
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expandedUnit,
                onDismissRequest = { expandedUnit = false }
            ) {
                UnitType.entries.forEach { unit ->
                    DropdownMenuItem(
                        text = { Text(unit.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            selectedUnit = unit
                            expandedUnit = false
                        }
                    )
                }
            }
        }

        // InputFieldType Dropdown
        ExposedDropdownMenuBox(
            expanded = expandedInputType,
            onExpandedChange = { expandedInputType = !expandedInputType }
        ) {
            OutlinedTextField(
                readOnly = true,
                value = selectedInputType.name.lowercase().replaceFirstChar { it.uppercase() }, // Format for display
                onValueChange = {},
                label = { Text(stringResource(R.string.measurement_type_label_input_type)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedInputType)
                },
                modifier = Modifier
                    .menuAnchor( // Required for ExposedDropdownMenu
                        type = MenuAnchorType.PrimaryNotEditable,
                        enabled = true
                    )
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expandedInputType,
                onDismissRequest = { expandedInputType = false }
            ) {
                InputFieldType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            selectedInputType = type
                            expandedInputType = false
                        }
                    )
                }
            }
        }

        OutlinedSettingRow(label = stringResource(R.string.measurement_type_label_pinned)) {
            Switch(
                checked = isPinned,
                onCheckedChange = { isPinned = it }
            )
        }

        OutlinedSettingRow(label = stringResource(R.string.measurement_type_label_on_right_y_axis)) {
            Switch(
                checked = isOnRightYAxis,
                onCheckedChange = { isOnRightYAxis = it }
            )
        }
    }

    // Color Picker Dialog
    if (showColorPicker) {
        ColorPickerDialog(
            currentColor = Color(selectedColor),
            onColorSelected = {
                selectedColor = it.toArgb()
                // showColorPicker = false // Keep picker open until explicitly dismissed by user
            },
            onDismiss = { showColorPicker = false }
        )
    }

    // Icon Picker Dialog
    if (showIconPicker) {
        IconPickerDialog(
            onIconSelected = {
                selectedIcon = it
                showIconPicker = false // Close picker after selection
            },
            onDismiss = { showIconPicker = false }
        )
    }
}

/**
 * A private composable function that creates a row styled like an [OutlinedTextField]
 * but designed to hold a label and a custom control (e.g., a [Switch]).
 *
 * @param label The text to display as the label for this setting row.
 * @param modifier Modifier for this composable.
 * @param controlContent A composable lambda that defines the control to be placed on the right side of the row.
 */
@Composable
private fun OutlinedSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    controlContent: @Composable () -> Unit
) {
    Surface( // Surface for the border and background, mimicking OutlinedTextField
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OutlinedTextFieldDefaults.MinHeight), // Minimum height similar to OutlinedTextField
        shape = OutlinedTextFieldDefaults.shape, // Shape similar to OutlinedTextField
        color = MaterialTheme.colorScheme.surface, // Background color (can be customized)
        border = BorderStroke( // Border
            width = 1.dp, // OutlinedTextFieldDefaults.UnfocusedBorderThickness is internal, so using 1.dp
            color = MaterialTheme.colorScheme.outline // Border color similar to OutlinedTextField
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding( // Internal padding similar to OutlinedTextField
                    start = 16.dp, // Similar to OutlinedTextFieldTokens.InputLeadingPadding
                    end = 16.dp,   // Similar to OutlinedTextFieldTokens.InputTrailingPadding
                    top = 8.dp,    // Less top padding as the label is centered vertically
                    bottom = 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween // Pushes label to start, control to end
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge, // Style for the "label"
                color = MaterialTheme.colorScheme.onSurfaceVariant // Color of the "label"
            )
            controlContent() // The Switch or other control is placed here
        }
    }
}
