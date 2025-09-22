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
package com.health.openscale.ui.screen.dialog

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.components.RoundMeasurementIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserGoalDialog(
    navController: NavController,
    existingUserGoal: UserGoals?,
    allMeasurementTypes: List<MeasurementType>,
    allGoalsOfCurrentUser: List<UserGoals>,
    onDismiss: () -> Unit,
    onConfirm: (measurementTypeId: Int, goalValueString: String) -> Unit,
    onDelete: (userId: Int, measurementTypeId: Int) -> Unit
) {
    val context = LocalContext.current
    val isEditing = existingUserGoal != null

    val targetableTypes = remember(allMeasurementTypes, allGoalsOfCurrentUser, isEditing) {
        val baseTargetableTypes = allMeasurementTypes.filter {
            (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) && !it.isDerived
        }

        if (isEditing) {
            baseTargetableTypes
        } else {
            val typesWithExistingGoalsIds = allGoalsOfCurrentUser.map { it.measurementTypeId }.toSet()
            baseTargetableTypes.filter { it.id !in typesWithExistingGoalsIds }.also {
            }
        }
    }


    var selectedTypeState by remember(targetableTypes, existingUserGoal, isEditing) {
        mutableStateOf(
            if (isEditing) {
                allMeasurementTypes.find { it.id == existingUserGoal!!.measurementTypeId }
            } else {
                targetableTypes.firstOrNull()
            }
        )
    }

    var currentGoalValueString by remember(existingUserGoal, selectedTypeState?.id) {
        mutableStateOf(
            if (isEditing && selectedTypeState != null) {
                existingUserGoal?.goalValue?.toString()?.replace(',', '.') ?: ""
            } else {
                ""
            }
        )
    }

    val dialogTitle = remember(isEditing, selectedTypeState) {
        val typeName = selectedTypeState?.getDisplayName(context) ?: context.getString(R.string.measurement_type_custom_default_name)
        if (isEditing) {
            context.getString(R.string.dialog_title_edit_goal, typeName)
        } else {
            context.getString(R.string.dialog_title_add_goal, typeName)
        }
    }

    var showGoalInputArea by remember(isEditing, selectedTypeState) {
        mutableStateOf(isEditing || (!isEditing && selectedTypeState != null))
    }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTypeState?.id, isEditing) {
        if (!isEditing) {
            currentGoalValueString = ""
            showGoalInputArea = selectedTypeState != null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExposedDropdownMenuBox(
                        expanded = typeDropdownExpanded,
                        onExpandedChange = {
                            if (!isEditing && targetableTypes.isNotEmpty()) {
                                typeDropdownExpanded = !typeDropdownExpanded
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = selectedTypeState?.getDisplayName(context)
                                ?: stringResource(if (targetableTypes.isEmpty()) R.string.info_no_targetable_types else R.string.placeholder_select_type),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.measurement_type_label_name)) },
                            leadingIcon = {
                                selectedTypeState?.let {
                                    RoundMeasurementIcon(
                                        icon = it.icon.resource,
                                        backgroundTint = Color(it.color),
                                        size = 16.dp
                                    )
                                }
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selectedTypeState != null) {
                                        IconButton(
                                            onClick = {
                                                selectedTypeState?.let {
                                                    navController.navigate(Routes.measurementTypeDetail(typeId = it.id))
                                                }
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                modifier = Modifier.size(20.dp),
                                                contentDescription = stringResource(R.string.content_desc_edit_type)
                                            )
                                        }
                                        Spacer(Modifier.width(4.dp))
                                    }

                                    if (!isEditing && targetableTypes.isNotEmpty()) {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                }
                            },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            enabled = !isEditing && targetableTypes.isNotEmpty()
                        )
                        if (!isEditing && targetableTypes.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = typeDropdownExpanded,
                                onDismissRequest = { typeDropdownExpanded = false }
                            ) {
                                targetableTypes.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.getDisplayName(context)) },
                                        leadingIcon = {
                                            RoundMeasurementIcon(icon = type.icon.resource, backgroundTint = Color(type.color), size = 16.dp)
                                        },
                                        onClick = {
                                            if (selectedTypeState?.id != type.id) {
                                                selectedTypeState = type
                                            }
                                            typeDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (selectedTypeState != null && showGoalInputArea) {
                    NumberInputField(
                        initialValue = currentGoalValueString,
                        inputType = selectedTypeState!!.inputType,
                        unit = selectedTypeState!!.unit,
                        onValueChange = { newValue ->
                            currentGoalValueString = newValue
                        },
                        label = stringResource(R.string.measurement_type_label_goal)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedTypeState != null) {
                        if (currentGoalValueString.isNotBlank() || isEditing) {
                            onConfirm(selectedTypeState!!.id, currentGoalValueString)
                            onDismiss()
                        } else {
                            Toast.makeText(context, R.string.toast_goal_value_cannot_be_empty, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, R.string.toast_select_measurement_type, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = selectedTypeState != null
            ) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            Row {
                if (isEditing && existingUserGoal != null && selectedTypeState != null) {
                    TextButton(
                        onClick = {
                            onDelete(existingUserGoal.userId, existingUserGoal.measurementTypeId)
                            onDismiss()
                        }
                    ) {
                        Text(
                            stringResource(R.string.delete_button_label),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        }
    )
}

