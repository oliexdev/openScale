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

import android.R.attr.enabled
import android.R.attr.label
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.derivedStateOf
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
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType
import com.health.openscale.ui.components.MeasurementIcon
import com.health.openscale.ui.screen.SharedViewModel
import com.health.openscale.ui.screen.dialog.ColorPickerDialog
import com.health.openscale.ui.screen.dialog.IconPickerDialog
import kotlin.text.lowercase

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
    // Stores the original state of the measurement type before any UI changes.
    // Crucial for the conversion logic to have the true original state.
    val originalExistingType = remember(measurementTypes, typeId) {
        measurementTypes.find { it.id == typeId }
    }
    val isEdit = typeId != -1

    // Determine the MeasurementTypeKey for the allowed units logic.
    // For new types, it's always CUSTOM; for existing types, it's the type's key.
    val currentMeasurementTypeKey = remember(originalExistingType, isEdit) {
        if (isEdit) originalExistingType?.key ?: MeasurementTypeKey.CUSTOM
        else MeasurementTypeKey.CUSTOM
    }

    // Get the list of allowed units based on the key.
    val allowedUnitsForKey = remember(currentMeasurementTypeKey) {
        currentMeasurementTypeKey.allowedUnitTypes
    }

    val allowedInputTypesForKey = remember(currentMeasurementTypeKey) {
        currentMeasurementTypeKey.allowedInputType
    }

    var name by remember { mutableStateOf(originalExistingType?.getDisplayName(context).orEmpty()) }

    // Safely set selectedUnit. If the existing unit isn't allowed or if no existing unit,
    // use the first allowed unit.
    var selectedUnit by remember {
        val initialUnit = originalExistingType?.unit
        if (initialUnit != null && initialUnit in allowedUnitsForKey) {
            mutableStateOf(initialUnit)
        } else {
            mutableStateOf(allowedUnitsForKey.firstOrNull() ?: UnitType.NONE)
        }
    }

    var selectedInputType by remember {
        val initialInputType = originalExistingType?.inputType
        if (initialInputType != null && initialInputType in allowedInputTypesForKey) {
            mutableStateOf(initialInputType)
        } else {
            mutableStateOf(allowedInputTypesForKey.firstOrNull() ?: InputFieldType.FLOAT)
        }
    }
    var selectedColor by remember { mutableStateOf(originalExistingType?.color ?: 0xFFFFA726.toInt()) }
    var selectedIcon by remember { mutableStateOf(originalExistingType?.icon ?: MeasurementTypeIcon.IC_DEFAULT) }
    var isEnabled by remember { mutableStateOf(originalExistingType?.isEnabled ?: true) }
    var isPinned by remember { mutableStateOf(originalExistingType?.isPinned ?: false) }
    var isOnRightYAxis by remember { mutableStateOf(originalExistingType?.isOnRightYAxis ?: false) }

    var expandedUnit by remember { mutableStateOf(false) }
    var expandedInputType by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingUpdatedType by remember { mutableStateOf<MeasurementType?>(null) }

    val titleEdit = stringResource(R.string.measurement_type_detail_title_edit)
    val titleAdd = stringResource(R.string.measurement_type_detail_title_add)

    val unitDropdownEnabled by remember(allowedUnitsForKey) {
        derivedStateOf { allowedUnitsForKey.size > 1 }
    }
    val inputTypeDropdownEnabled by remember(allowedInputTypesForKey) {
        derivedStateOf { allowedInputTypesForKey.size > 1 }
    }

    LaunchedEffect(originalExistingType, allowedUnitsForKey) {
        val currentUnitInExistingType = originalExistingType?.unit
        if (currentUnitInExistingType != null && currentUnitInExistingType in allowedUnitsForKey) {
            if (selectedUnit != currentUnitInExistingType) { // Only update if different to avoid recomposition loops
                selectedUnit = currentUnitInExistingType
            }
        } else if (allowedUnitsForKey.isNotEmpty() && selectedUnit !in allowedUnitsForKey) {
            selectedUnit = allowedUnitsForKey.first()
        } else if (allowedUnitsForKey.isEmpty() && selectedUnit != UnitType.NONE) {
            // This case should ideally not be reached if keys are well-defined.
            selectedUnit = UnitType.NONE
        }
    }

    LaunchedEffect(originalExistingType, allowedInputTypesForKey) {
        val currentInputTypeInExistingType = originalExistingType?.inputType
        if (currentInputTypeInExistingType != null && currentInputTypeInExistingType in allowedInputTypesForKey) {
            if (selectedInputType != currentInputTypeInExistingType) {
                selectedInputType = currentInputTypeInExistingType
            }
        } else if (allowedInputTypesForKey.isNotEmpty() && selectedInputType !in allowedInputTypesForKey) {
            selectedInputType = allowedInputTypesForKey.first()
        } else if (allowedInputTypesForKey.isEmpty()) {
            selectedInputType = InputFieldType.FLOAT
        }
    }

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(if (isEdit) titleEdit else titleAdd)
        sharedViewModel.setTopBarAction(
            SharedViewModel.TopBarAction(icon = Icons.Default.Save, onClick = {
                if (name.isNotBlank()) {
                    // When creating the updatedType, use the key of the originalExistingType if it's an edit.
                    // For new types, it's MeasurementTypeKey.CUSTOM.
                    val finalKey = if (isEdit) originalExistingType?.key ?: MeasurementTypeKey.CUSTOM else MeasurementTypeKey.CUSTOM

                    val currentUpdatedType = MeasurementType(
                        id = originalExistingType?.id ?: 0,
                        name = name,
                        icon = selectedIcon,
                        color = selectedColor,
                        unit = selectedUnit,
                        inputType = selectedInputType,
                        displayOrder = originalExistingType?.displayOrder ?: measurementTypes.size,
                        isEnabled = isEnabled,
                        isPinned = isPinned,
                        key = finalKey, // Use the correct key
                        isDerived = originalExistingType?.isDerived ?: false,
                        isOnRightYAxis = isOnRightYAxis
                    )

                    if (isEdit && originalExistingType != null) {
                        val unitChanged = originalExistingType.unit != currentUpdatedType.unit
                        val inputTypesAreFloat = originalExistingType.inputType == InputFieldType.FLOAT && currentUpdatedType.inputType == InputFieldType.FLOAT

                        if (unitChanged && inputTypesAreFloat) {
                            pendingUpdatedType = currentUpdatedType
                            showConfirmDialog = true
                        } else {
                            settingsViewModel.updateMeasurementType(currentUpdatedType)
                            navController.popBackStack()
                        }
                    } else {
                        settingsViewModel.addMeasurementType(currentUpdatedType)
                        navController.popBackStack()
                    }
                } else {
                    Toast.makeText(context, R.string.toast_enter_valid_data, Toast.LENGTH_SHORT).show()
                }
            })
        )
    }

    if (showConfirmDialog && originalExistingType != null && pendingUpdatedType != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.measurement_type_dialog_confirm_unit_change_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.measurement_type_dialog_confirm_unit_change_message,
                        originalExistingType.getDisplayName(context),
                        originalExistingType.unit.displayName.lowercase().replaceFirstChar { it.uppercase() },
                        pendingUpdatedType!!.unit.displayName.lowercase().replaceFirstChar { it.uppercase() }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsViewModel.updateMeasurementTypeAndConvertDataViewModelCentric(
                        originalType = originalExistingType,
                        updatedType = pendingUpdatedType!!
                    )
                    showConfirmDialog = false
                    navController.popBackStack()
                }) { Text(stringResource(R.string.confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text(stringResource(R.string.cancel_button)) }
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

        if (!isEdit || (originalExistingType?.key == MeasurementTypeKey.CUSTOM)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.measurement_type_label_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = "",
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
                )
            },
            colors = TextFieldDefaults.colors(
                disabledTextColor = LocalContentColor.current,
                disabledIndicatorColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent
            )
        )

        OutlinedTextField(
            value = "",
            onValueChange = {}, // Read-only
            label = { Text(stringResource(R.string.measurement_type_label_icon)) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showIconPicker = true },
            readOnly = true,
            enabled = false, // To make it look like a display field
            trailingIcon = {
                MeasurementIcon(
                    icon = selectedIcon,
                )
            },
            colors = TextFieldDefaults.colors(
                disabledTextColor = LocalContentColor.current,
                disabledIndicatorColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent
            )
        )

        if (unitDropdownEnabled) {
            ExposedDropdownMenuBox(
                expanded = expandedUnit && unitDropdownEnabled,
                onExpandedChange = {
                    if (unitDropdownEnabled) expandedUnit = !expandedUnit
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedSettingRow(
                    label = stringResource(R.string.measurement_type_label_unit),
                    surfaceModifier = Modifier
                        .menuAnchor(
                            type = MenuAnchorType.PrimaryNotEditable,
                            enabled = unitDropdownEnabled
                        )
                        .clickable(enabled = unitDropdownEnabled) {
                            if (unitDropdownEnabled) expandedUnit = true
                        },
                    controlContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedUnit.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (unitDropdownEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            if (unitDropdownEnabled) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedUnit)
                            }
                        }
                    }
                )

                if (unitDropdownEnabled) {
                    ExposedDropdownMenu(
                        expanded = expandedUnit,
                        onDismissRequest = { expandedUnit = false },
                        modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true)
                    ) {
                        allowedUnitsForKey.forEach { unit ->
                            DropdownMenuItem(
                                text = {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Text(
                                            text = unit.displayName,
                                            modifier = Modifier.padding(end = 32.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    selectedUnit = unit
                                    expandedUnit = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // InputFieldType Dropdown
        if (inputTypeDropdownEnabled) {
            ExposedDropdownMenuBox(
                expanded = expandedInputType,
                onExpandedChange = { expandedInputType = !expandedInputType }
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = selectedInputType.name.lowercase().replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    label = { Text(stringResource(R.string.measurement_type_label_input_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedInputType) },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedInputType,
                    onDismissRequest = { expandedInputType = false }
                ) {
                    allowedInputTypesForKey.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    type.name.lowercase().replaceFirstChar { it.uppercase() })
                            },
                            onClick = {
                                selectedInputType = type
                                expandedInputType = false
                            }
                        )
                    }
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

    if (showColorPicker) {
        ColorPickerDialog(
            currentColor = Color(selectedColor),
            onColorSelected = { selectedColor = it.toArgb() },
            onDismiss = { showColorPicker = false }
        )
    }

    if (showIconPicker) {
        IconPickerDialog(
            iconBackgroundColor = Color(selectedColor),
            onIconSelected = {
                selectedIcon = it
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false }
        )
    }
}

@Composable
private fun OutlinedSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    controlContent: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OutlinedTextFieldDefaults.MinHeight)
            .then(surfaceModifier),
        shape = OutlinedTextFieldDefaults.shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            controlContent()
        }
    }
}
