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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AlignHorizontalRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.screen.dialog.DeleteConfirmationDialog
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Composable screen for managing and reordering measurement types.
 * It displays a list of available measurement types, allowing users to
 * edit, delete (custom types), and change their display order via drag-and-drop.
 *
 * @param sharedViewModel The [SharedViewModel] for accessing shared app state, like measurement types and setting top bar properties.
 * @param settingsViewModel The [SettingsViewModel] for performing update or delete operations on measurement types.
 * @param onEditType Callback invoked when the user taps the edit icon for a type or the add icon in the top bar.
 *                   Passes the ID of the type to edit, or null to add a new type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementTypeSettingsScreen(
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel,
    onEditType: (Int?) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val measurementTypes by sharedViewModel.measurementTypes.collectAsState()

    var isInSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedTypeIds = remember { mutableStateListOf<Int>() }

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogText by remember { mutableStateOf("") }

    val onTogglePinned = {
        settingsViewModel.togglePinnedState(selectedTypeIds.toList())
        isInSelectionMode = false
    }
    val onToggleEnabled = {
        settingsViewModel.toggleEnabledState(selectedTypeIds.toList())
        isInSelectionMode = false
    }
    val onToggleAxis = {
        settingsViewModel.toggleAxisState(selectedTypeIds.toList())
        isInSelectionMode = false
    }

    // Remember and sort the list based on displayOrder. This list is used by the reorderable component.
    var list by remember(measurementTypes) {
        mutableStateOf(measurementTypes.sortedBy { it.displayOrder })
    }

    val lazyListState = rememberLazyListState()
    // rememberReorderableLazyListState enables drag-and-drop reordering
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            // Update the local list state when an item is moved
            list = list.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }.also { updatedList ->
                // Persist the new display order for each type in the updated list
                updatedList.forEachIndexed { index, type ->
                    settingsViewModel.updateMeasurementType(type.copy(displayOrder = index), showSnackbar = false)
                }
            }
        }
    )

    var typeToDelete by remember { mutableStateOf<MeasurementType?>(null) }

    // Retrieve string for the top bar title in the Composable context
    val screenTitle = stringResource(R.string.measurement_type_settings_title)
    val dragHandleContentDesc = stringResource(R.string.content_desc_drag_handle_sort)
    val editContentDesc = stringResource(R.string.content_desc_edit_type)
    val deleteContentDesc = stringResource(R.string.content_desc_delete_type)

    LaunchedEffect(Unit,isInSelectionMode, selectedTypeIds.size) {
        if (isInSelectionMode) {
            val areAllSelectedPinned = selectedTypeIds.all { id -> list.find { it.id == id }?.isPinned == true }
            val areAllSelectedEnabled = selectedTypeIds.all { id -> list.find { it.id == id }?.isEnabled == true }
            val areAllSelectedOnRightAxis = selectedTypeIds.all { id -> list.find { it.id == id }?.isOnRightYAxis == true }

            sharedViewModel.setTopBarActions(
                listOf(
                    // Enable/Disable Action
                    TopBarAction(
                        icon = if (areAllSelectedEnabled) Icons.Filled.ToggleOn else Icons.Filled.ToggleOff,
                        onClick = {
                            pendingAction = onToggleEnabled
                            val actionVerbRes = if (areAllSelectedEnabled) R.string.action_disable else R.string.action_enable
                            val actionTitleVerb = context.getString(actionVerbRes).replaceFirstChar { it.titlecase() } // "Enable", "Disable"
                            dialogTitle = context.getString(R.string.dialog_title_confirm_generic, actionTitleVerb)
                            dialogText = context.getString(R.string.dialog_text_confirm_generic_verb, selectedTypeIds.size, context.getString(actionVerbRes))
                        }
                    ),
                    // Pin/Unpin Action
                    TopBarAction(
                        icon = if (areAllSelectedPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        onClick = {
                            pendingAction = onTogglePinned
                            val actionVerbRes = if (areAllSelectedPinned) R.string.action_unpin else R.string.action_pin
                            val actionTitleVerb = context.getString(actionVerbRes).replaceFirstChar { it.titlecase() } // "Pin", "Unpin"
                            dialogTitle = context.getString(R.string.dialog_title_confirm_generic, actionTitleVerb)
                            dialogText = context.getString(R.string.dialog_text_confirm_generic_verb, selectedTypeIds.size, context.getString(actionVerbRes))
                        }
                    ),
                    // Change Axis Action
                    TopBarAction(
                        icon = Icons.Filled.SwapHoriz,
                        onClick = {
                            pendingAction = onToggleAxis
                            val actionVerbRes = if (areAllSelectedOnRightAxis) R.string.action_move_to_left_axis else R.string.action_move_to_right_axis
                            val actionTitleVerb = context.getString(actionVerbRes).replaceFirstChar { it.titlecase() }
                            dialogTitle = context.getString(R.string.dialog_title_confirm_generic, actionTitleVerb)
                            dialogText = context.getString(R.string.dialog_text_confirm_generic_verb, selectedTypeIds.size, context.getString(actionVerbRes))
                        }
                    ),
                    // Exit Selection Mode Action
                    TopBarAction(
                        icon = Icons.Filled.Close,
                        onClick = { isInSelectionMode = false }
                    )
                )
            )
            sharedViewModel.setTopBarTitle("${selectedTypeIds.size} selected")
        } else {
            // Standard-TopBar
            selectedTypeIds.clear()
            sharedViewModel.setTopBarTitle(screenTitle)
            sharedViewModel.setTopBarActions(
                listOf(
                    TopBarAction(
                        icon = Icons.Outlined.CheckBox,
                        onClick = { isInSelectionMode = true }
                    ),
                    TopBarAction(
                        icon = Icons.Default.Add,
                        onClick = { onEditType(null) }
                    )
                )
            )
        }
    }

    BackHandler(enabled = isInSelectionMode) {
        isInSelectionMode = false
    }

    if (pendingAction != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(dialogTitle) },
            text = { Text(dialogText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction?.invoke()
                        pendingAction = null
                    }
                ) {
                    Text(stringResource(R.string.confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    typeToDelete?.let { type ->
        DeleteConfirmationDialog(
            onDismissRequest = { typeToDelete = null },
            onConfirm = {
                coroutineScope.launch {
                    settingsViewModel.deleteMeasurementType(type)
                }
            },
            title = stringResource(R.string.dialog_title_delete_type),
            text = stringResource(R.string.dialog_text_delete_type, type.getDisplayName(context))
        )
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        itemsIndexed(list, key = { _, item -> item.id }) { _, type ->
            ReorderableItem(reorderableState, key = type.id) { isDragging ->
                val isSelected = type.id in selectedTypeIds
                // Apply visual effects based on whether the type is enabled
                val cardColors = if (isSelected) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                }
                val itemAlpha = if (type.isEnabled) 1f else 0.6f
                val textColor = if (type.isEnabled) LocalContentColor.current
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                val iconBackgroundAlpha = if (type.isEnabled) 1f else 0.7f
                val iconTintAlpha = if (type.isEnabled) 1f else 0.7f


                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .graphicsLayer(alpha = itemAlpha) // Apply transparency for disabled items
                        .combinedClickable(
                            onClick = {
                                if (isInSelectionMode) {
                                    if (isSelected) selectedTypeIds.remove(type.id) else selectedTypeIds.add(
                                        type.id
                                    )
                                    if (selectedTypeIds.isEmpty()) isInSelectionMode = false
                                } else {
                                    onEditType(type.id)
                                }
                            },
                            onLongClick = {
                                if (!isInSelectionMode) {
                                    isInSelectionMode = true
                                    selectedTypeIds.add(type.id)
                                }
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp), // Adjust padding
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isInSelectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (isSelected) selectedTypeIds.remove(type.id) else selectedTypeIds.add(type.id)
                                    if (selectedTypeIds.isEmpty()) isInSelectionMode = false
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }

                        // Icon
                        val iconMeasurementType = remember(type.icon) {type.icon }
                        RoundMeasurementIcon(
                            icon = iconMeasurementType.resource,
                            iconTint = Color.Black.copy(alpha = iconTintAlpha),
                            backgroundTint = Color(type.color).copy(alpha = iconBackgroundAlpha),
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(Modifier.size(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            // first row name
                            Text(
                                text = type.getDisplayName(LocalContext.current),
                                color = textColor,
                                style = MaterialTheme.typography.titleMedium, // Name ist jetzt etwas größer
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.size(4.dp))

                            // second row meta information
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (type.unit.displayName.isNotBlank()) {
                                    Text(
                                        text = type.unit.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = textColor.copy(alpha = 0.8f)
                                    )
                                }

                                if (type.isPinned) {
                                    Icon(
                                        imageVector = Icons.Outlined.PushPin,
                                        contentDescription = stringResource(R.string.measurement_type_label_pinned),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (type.isDerived) {
                                    Icon(
                                        imageVector = Icons.Default.Functions,
                                        contentDescription = stringResource(R.string.formula_label_lbm),
                                        modifier = Modifier.size(14.dp),
                                        tint = textColor.copy(alpha = 0.8f)
                                    )
                                }

                                if (type.isOnRightYAxis) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.AlignHorizontalRight,
                                        contentDescription = stringResource(R.string.measurement_type_label_on_right_y_axis),
                                        modifier = Modifier.size(14.dp),
                                        tint = textColor.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.size(8.dp))

                        // Edit button
                        IconButton(onClick = { onEditType(type.id) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = editContentDesc
                            )
                        }

                        // Delete button is only shown for custom types
                        if (type.key == MeasurementTypeKey.CUSTOM) {
                            IconButton(onClick = { typeToDelete = type }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = deleteContentDesc,
                                    tint = MaterialTheme.colorScheme.error // Use error color for destructive actions
                                )
                            }
                        }

                        // Drag handle for reordering
                        IconButton(
                            modifier = Modifier
                                .draggableHandle()
                                .padding(start = 8.dp),
                            onClick = {}
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = dragHandleContentDesc
                            )
                        }
                    }
                }

            }
        }
    }
}
