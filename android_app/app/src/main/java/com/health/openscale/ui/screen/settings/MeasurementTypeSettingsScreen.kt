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

import androidx.activity.result.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.screen.dialog.DeleteConfirmationDialog
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.coroutineScope
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

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(screenTitle)
        sharedViewModel.setTopBarAction(
            TopBarAction(icon = Icons.Default.Add, onClick = {
                onEditType(null) // Request to add a new type
            })
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
                // Apply visual effects based on whether the type is enabled
                val itemAlpha = if (type.isEnabled) 1f else 0.6f
                val textColor = if (type.isEnabled) LocalContentColor.current
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                val iconBackgroundAlpha = if (type.isEnabled) 1f else 0.7f
                val iconTintAlpha = if (type.isEnabled) 1f else 0.7f


                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .graphicsLayer(alpha = itemAlpha) // Apply transparency for disabled items
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconMeasurementType = remember(type.icon) {type.icon }

                        RoundMeasurementIcon(
                            icon = iconMeasurementType.resource,
                            iconTint = Color.Black.copy(alpha = iconTintAlpha),
                            backgroundTint = Color(type.color).copy(alpha = iconBackgroundAlpha),
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(Modifier.size(16.dp))

                        // Display measurement type name
                        Text(
                            text = type.getDisplayName(LocalContext.current),
                            modifier = Modifier.weight(1f),
                            color = textColor
                        )

                        // Edit button
                        IconButton(onClick = { onEditType(type.id) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = editContentDesc
                            )
                        }
                        // Delete button, only for custom types
                        if (type.key == MeasurementTypeKey.CUSTOM) {
                            IconButton(onClick = { typeToDelete = type }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = deleteContentDesc
                                )
                            }
                        }

                        // Drag handle for reordering
                        IconButton(
                            modifier = Modifier.draggableHandle() // Provided by the reorderable library
                                .padding(start = 8.dp), // ensure small padding for separation
                            onClick = {} // onClick is typically handled by the reorderable mechanism
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
