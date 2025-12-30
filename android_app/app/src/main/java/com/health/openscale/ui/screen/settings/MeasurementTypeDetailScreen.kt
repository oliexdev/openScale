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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.BodyFatFormulaOption
import com.health.openscale.core.data.BodyWaterFormulaOption
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.LbmFormulaOption
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType
import com.health.openscale.ui.components.MeasurementIcon
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.screen.dialog.ColorPickerDialog
import com.health.openscale.ui.screen.dialog.IconPickerDialog
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.launch
import kotlin.text.lowercase

private sealed interface PendingDialog {
    data class UnitChange(val from: UnitType, val to: UnitType) : PendingDialog
    data class FormulaOnBodyFat(val option: BodyFatFormulaOption) : PendingDialog
    data class FormulaOnBodyWater(val option: BodyWaterFormulaOption) : PendingDialog
    data class FormulaOnLBM(val option: LbmFormulaOption) : PendingDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementTypeDetailScreen(
    navController: NavController,
    typeId: Int,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val measurementTypes by sharedViewModel.measurementTypes.collectAsState()
    val originalExistingType = remember(measurementTypes, typeId) {
        measurementTypes.find { it.id == typeId }
    }
    val isEdit = typeId != -1

    val currentMeasurementTypeKey = remember(originalExistingType, isEdit) {
        if (isEdit) originalExistingType?.key ?: MeasurementTypeKey.CUSTOM else MeasurementTypeKey.CUSTOM
    }

    val allowedUnitsForKey = remember(currentMeasurementTypeKey) { currentMeasurementTypeKey.allowedUnitTypes }
    val allowedInputTypesForKey = remember(currentMeasurementTypeKey) { currentMeasurementTypeKey.allowedInputType }

    var name by remember { mutableStateOf(originalExistingType?.getDisplayName(context).orEmpty()) }
    var selectedUnit by remember {
        val u = originalExistingType?.unit
        mutableStateOf(if (u != null && u in allowedUnitsForKey) u else allowedUnitsForKey.firstOrNull() ?: UnitType.NONE)
    }
    var selectedInputType by remember {
        val itp = originalExistingType?.inputType
        mutableStateOf(if (itp != null && itp in allowedInputTypesForKey) itp else allowedInputTypesForKey.firstOrNull() ?: InputFieldType.FLOAT)
    }
    var selectedColor by remember { mutableStateOf(originalExistingType?.color ?: 0xFFFFA726.toInt()) }
    var selectedIcon by remember { mutableStateOf(originalExistingType?.icon ?: MeasurementTypeIcon.IC_DEFAULT) }
    var isEnabled by remember { mutableStateOf(originalExistingType?.isEnabled ?: true) }
    var isPinned by remember { mutableStateOf(originalExistingType?.isPinned ?: false) }
    var isOnRightYAxis by remember { mutableStateOf(originalExistingType?.isOnRightYAxis ?: false) }

    val bodyFatFormulaOption by sharedViewModel.selectedBodyFatFormula.collectAsState(BodyFatFormulaOption.OFF)
    val bodyWaterFormulaOption by sharedViewModel.selectedBodyWaterFormula.collectAsState(BodyWaterFormulaOption.OFF)
    val lbmFormulaOption by sharedViewModel.selectedLbmFormula.collectAsState(LbmFormulaOption.OFF)

    var bodyFatFormula by remember(bodyFatFormulaOption) { mutableStateOf(bodyFatFormulaOption) }
    var bodyWaterFormula by remember(bodyWaterFormulaOption) { mutableStateOf(bodyWaterFormulaOption) }
    var lbmFormula by remember(lbmFormulaOption) { mutableStateOf(lbmFormulaOption) }

    var formulaInfoTitle by remember { mutableStateOf<String?>(null) }
    var formulaInfoText by remember { mutableStateOf<String?>(null) }

    var pendingDialog by remember { mutableStateOf<PendingDialog?>(null) }

    var expandedUnit by remember { mutableStateOf(false) }
    var expandedInputType by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    val titleEdit = stringResource(R.string.measurement_type_detail_title_edit)
    val titleAdd = stringResource(R.string.measurement_type_detail_title_add)

    val unitDropdownEnabled by remember(allowedUnitsForKey) { derivedStateOf { allowedUnitsForKey.size > 1 } }
    val inputTypeDropdownEnabled by remember(allowedInputTypesForKey) { derivedStateOf { allowedInputTypesForKey.size > 1 } }

    LaunchedEffect(originalExistingType, allowedUnitsForKey) {
        originalExistingType?.unit?.let { if (it in allowedUnitsForKey && it != selectedUnit) selectedUnit = it }
        if (allowedUnitsForKey.isNotEmpty() && selectedUnit !in allowedUnitsForKey) selectedUnit = allowedUnitsForKey.first()
        if (allowedUnitsForKey.isEmpty() && selectedUnit != UnitType.NONE) selectedUnit = UnitType.NONE
    }
    LaunchedEffect(originalExistingType, allowedInputTypesForKey) {
        originalExistingType?.inputType?.let { if (it in allowedInputTypesForKey && it != selectedInputType) selectedInputType = it }
        if (allowedInputTypesForKey.isNotEmpty() && selectedInputType !in allowedInputTypesForKey) selectedInputType = allowedInputTypesForKey.first()
        if (allowedInputTypesForKey.isEmpty()) selectedInputType = InputFieldType.FLOAT
    }

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(if (isEdit) titleEdit else titleAdd)
        sharedViewModel.setTopBarAction(
            TopBarAction(icon = Icons.Default.Save, onClick = {
                if (name.isBlank()) {
                    Toast.makeText(context, R.string.toast_enter_valid_data, Toast.LENGTH_SHORT).show()
                    return@TopBarAction
                }

                val finalKey = if (isEdit) originalExistingType?.key ?: MeasurementTypeKey.CUSTOM else MeasurementTypeKey.CUSTOM

                val derivedForType = when (currentMeasurementTypeKey) {
                    MeasurementTypeKey.BODY_FAT -> bodyFatFormula != BodyFatFormulaOption.OFF
                    MeasurementTypeKey.WATER    -> bodyWaterFormula != BodyWaterFormulaOption.OFF
                    MeasurementTypeKey.LBM      -> lbmFormula != LbmFormulaOption.OFF
                    else -> originalExistingType?.isDerived ?: false
                }

                val updatedType = MeasurementType(
                    id = originalExistingType?.id ?: 0,
                    name = name,
                    icon = selectedIcon,
                    color = selectedColor,
                    unit = selectedUnit,
                    inputType = selectedInputType,
                    displayOrder = originalExistingType?.displayOrder ?: measurementTypes.size,
                    isEnabled = isEnabled,
                    isPinned = isPinned,
                    key = finalKey,
                    isDerived = derivedForType,
                    isOnRightYAxis = isOnRightYAxis
                )

                scope.launch {
                    when (currentMeasurementTypeKey) {
                        MeasurementTypeKey.BODY_FAT -> if (bodyFatFormula != bodyFatFormulaOption) sharedViewModel.setSelectedBodyFatFormula(bodyFatFormula)
                        MeasurementTypeKey.WATER    -> if (bodyWaterFormula != bodyWaterFormulaOption) sharedViewModel.setSelectedBodyWaterFormula(bodyWaterFormula)
                        MeasurementTypeKey.LBM      -> if (lbmFormula != lbmFormulaOption) sharedViewModel.setSelectedLbmFormula(lbmFormula)
                        else -> Unit
                    }
                }

                val unitChanged = isEdit && (originalExistingType?.unit != selectedUnit)
                val needsConversion =
                    unitChanged &&
                            (originalExistingType?.inputType == InputFieldType.FLOAT) &&
                            (selectedInputType == InputFieldType.FLOAT)

                if (isEdit && originalExistingType != null) {
                    if (needsConversion) {
                        settingsViewModel.updateMeasurementTypeWithConversion(
                            originalType = originalExistingType,
                            updatedType = updatedType
                        )
                    } else {
                        settingsViewModel.updateMeasurementType(updatedType)
                    }
                    navController.popBackStack()
                } else {
                    settingsViewModel.addMeasurementType(updatedType)
                    navController.popBackStack()
                }
            })
        )
    }

    fun requestFormulaChange(newValue: Any) {
        val turningOn = when (newValue) {
            is BodyFatFormulaOption   -> newValue != BodyFatFormulaOption.OFF
            is BodyWaterFormulaOption -> newValue != BodyWaterFormulaOption.OFF
            is LbmFormulaOption       -> newValue != LbmFormulaOption.OFF
            else -> false
        }
        if (turningOn) {
            pendingDialog = when (newValue) {
                is BodyFatFormulaOption   -> PendingDialog.FormulaOnBodyFat(newValue)
                is BodyWaterFormulaOption -> PendingDialog.FormulaOnBodyWater(newValue)
                is LbmFormulaOption       -> PendingDialog.FormulaOnLBM(newValue)
                else -> null
            }
        } else {
            when (newValue) {
                is BodyFatFormulaOption   -> { bodyFatFormula = newValue; }
                is BodyWaterFormulaOption -> { bodyWaterFormula = newValue; }
                is LbmFormulaOption       -> { lbmFormula = newValue; }
            }
        }
    }

    pendingDialog?.let { dlg ->
        val titleId = when (dlg) {
            is PendingDialog.UnitChange -> R.string.measurement_type_dialog_confirm_unit_change_title
            is PendingDialog.FormulaOnBodyFat,
            is PendingDialog.FormulaOnBodyWater,
            is PendingDialog.FormulaOnLBM -> R.string.formula_warning_title
        }

        val message = when (dlg) {
            is PendingDialog.UnitChange -> {
                val typeName = (originalExistingType?.getDisplayName(context) ?: name)
                val fromName = selectedUnit.displayName.lowercase().replaceFirstChar { it.uppercase() }
                val toName   = dlg.to.displayName.lowercase().replaceFirstChar { it.uppercase() }
                context.getString(
                    R.string.measurement_type_dialog_confirm_unit_change_message,
                    typeName, fromName, toName
                )
            }
            is PendingDialog.FormulaOnBodyFat,
            is PendingDialog.FormulaOnBodyWater,
            is PendingDialog.FormulaOnLBM -> {
                context.getString(R.string.formula_warning_message)
            }
        }

        AlertDialog(
            onDismissRequest = { pendingDialog = null },
            icon= {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(titleId),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (dlg) {
                            is PendingDialog.UnitChange -> {
                                selectedUnit = dlg.to
                            }
                            is PendingDialog.FormulaOnBodyFat -> {
                                bodyFatFormula = dlg.option
                            }
                            is PendingDialog.FormulaOnBodyWater -> {
                                bodyWaterFormula = dlg.option
                            }
                            is PendingDialog.FormulaOnLBM -> {
                                lbmFormula = dlg.option
                            }
                        }
                        pendingDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDialog = null }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }



    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedSettingRow(label = stringResource(R.string.measurement_type_label_enabled)) {
            Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
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
            onValueChange = {},
            label = { Text(stringResource(R.string.measurement_type_label_color)) },
            modifier = Modifier.fillMaxWidth().clickable { showColorPicker = true },
            readOnly = true,
            enabled = false,
            trailingIcon = {
                Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(selectedColor)))
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
            onValueChange = {},
            label = { Text(stringResource(R.string.measurement_type_label_icon)) },
            modifier = Modifier.fillMaxWidth().clickable { showIconPicker = true },
            readOnly = true,
            enabled = false,
            trailingIcon = { MeasurementIcon(icon = selectedIcon.resource) },
            colors = TextFieldDefaults.colors(
                disabledTextColor = LocalContentColor.current,
                disabledIndicatorColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent
            )
        )

        // Formula pickers use local state
        when (currentMeasurementTypeKey) {
            MeasurementTypeKey.BODY_FAT -> {
                FormulaPickerRow(
                    label = stringResource(R.string.formula_label_body_fat),
                    currentText = bodyFatFormula.displayName(context),
                    options = BodyFatFormulaOption.entries,
                    optionLabel = { it.displayName(context) },
                    optionSubtitle = { it.shortDescription(context) },
                    onInfo = { opt ->
                        formulaInfoTitle = opt.displayName(context)
                        formulaInfoText  = opt.longDescription(context)
                    },
                    onSelect = { requestFormulaChange( it) }
                )
            }
            MeasurementTypeKey.WATER -> {
                FormulaPickerRow(
                    label = stringResource(R.string.formula_label_body_water),
                    currentText = bodyWaterFormula.displayName(context),
                    options = BodyWaterFormulaOption.entries,
                    optionLabel = { it.displayName(context) },
                    optionSubtitle = { it.shortDescription(context) },
                    onInfo = { opt ->
                        formulaInfoTitle = opt.displayName(context)
                        formulaInfoText  = opt.longDescription(context)
                    },
                    onSelect = { requestFormulaChange(it) }
                )
            }
            MeasurementTypeKey.LBM -> {
                FormulaPickerRow(
                    label = stringResource(R.string.formula_label_lbm),
                    currentText = lbmFormula.displayName(context),
                    options = LbmFormulaOption.entries,
                    optionLabel = { it.displayName(context) },
                    optionSubtitle = { it.shortDescription(context) },
                    onInfo = { opt ->
                        formulaInfoTitle = opt.displayName(context)
                        formulaInfoText  = opt.longDescription(context)
                    },
                    onSelect = { requestFormulaChange(it) }
                )
            }
            else -> Unit
        }

        if (unitDropdownEnabled) {
            ExposedDropdownMenuBox(
                expanded = expandedUnit && unitDropdownEnabled,
                onExpandedChange = { if (unitDropdownEnabled) expandedUnit = !expandedUnit },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedSettingRow(
                    label = stringResource(R.string.measurement_type_label_unit),
                    surfaceModifier = Modifier
                        .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = unitDropdownEnabled)
                        .clickable(enabled = unitDropdownEnabled) { if (unitDropdownEnabled) expandedUnit = true },
                    controlContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedUnit.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (unitDropdownEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            if (unitDropdownEnabled) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedUnit)
                        }
                    }
                )
                if (unitDropdownEnabled) {
                    ExposedDropdownMenu(
                        expanded = expandedUnit,
                        onDismissRequest = { expandedUnit = false },
                        modifier = Modifier.exposedDropdownSize(matchAnchorWidth = true)
                    ) {
                        allowedUnitsForKey.forEach { unit ->
                            DropdownMenuItem(
                                text = {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                        Text(unit.displayName, modifier = Modifier.padding(end = 32.dp))
                                    }
                                },
                                onClick = {
                                    expandedUnit = false
                                    if (unit == selectedUnit) return@DropdownMenuItem

                                    val needsConfirm =
                                        isEdit &&
                                                (originalExistingType?.inputType == InputFieldType.FLOAT) &&
                                                (selectedInputType == InputFieldType.FLOAT)

                                    if (needsConfirm) {
                                        pendingDialog = PendingDialog.UnitChange(from = selectedUnit, to = unit)
                                    } else {
                                        selectedUnit = unit
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (inputTypeDropdownEnabled) {
            ExposedDropdownMenuBox(expanded = expandedInputType, onExpandedChange = { expandedInputType = !expandedInputType }) {
                OutlinedTextField(
                    readOnly = true,
                    value = selectedInputType.name.lowercase().replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    label = { Text(stringResource(R.string.measurement_type_label_input_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedInputType) },
                    modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expandedInputType, onDismissRequest = { expandedInputType = false }) {
                    allowedInputTypesForKey.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = { selectedInputType = type; expandedInputType = false }
                        )
                    }
                }
            }
        }

        OutlinedSettingRow(label = stringResource(R.string.measurement_type_label_pinned)) {
            Switch(checked = isPinned, onCheckedChange = { isPinned = it })
        }
        if (selectedInputType == InputFieldType.FLOAT || selectedInputType == InputFieldType.INT) {
            OutlinedSettingRow(label = stringResource(R.string.measurement_type_label_on_right_y_axis)) {
                Switch(checked = isOnRightYAxis, onCheckedChange = { isOnRightYAxis = it })
            }
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
            iconTintColor = Color.Black,
            availableIcons = MeasurementTypeIcon.entries.map { it.resource },
            onIconSelected =  { selectedResource ->
                selectedIcon = MeasurementTypeIcon.entries.first { it.resource == selectedResource };
                showIconPicker = false },
            onDismiss = { showIconPicker = false }
        )
    }

    if (formulaInfoTitle != null && formulaInfoText != null) {
        AlertDialog(
            onDismissRequest = { formulaInfoTitle = null; formulaInfoText = null },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            title = {
                Text(
                    text = formulaInfoTitle!!,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = formulaInfoText!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { formulaInfoTitle = null; formulaInfoText = null }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> FormulaPickerRow(
    label: String,
    currentText: String,
    options: List<T>,
    optionLabel: (T) -> String,
    optionSubtitle: ((T) -> String)? = null,
    onInfo: ((T) -> Unit)? = null,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedSettingRow(
            label = label,
            surfaceModifier = Modifier
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .clickable { expanded = true },
            controlContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentText,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(min = 24.dp, max = 240.dp)
                            .padding(end = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize(matchAnchorWidth = true)
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                optionLabel(opt),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                            optionSubtitle?.let { sub ->
                                Text(
                                    sub(opt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                    },
                    trailingIcon = if (onInfo != null) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onInfo(opt) },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(opt)
                    }
                )
            }
        }
    }
}
