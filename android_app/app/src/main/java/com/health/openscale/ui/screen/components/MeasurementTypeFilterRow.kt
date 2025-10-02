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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.ui.components.RoundMeasurementIcon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * A Composable that displays a horizontal row of circular icons representing measurement types,
 * allowing the user to select one or more types. The selection is persisted and can be
 * observed via a Flow.
 *
 * This component handles:
 * - Displaying available measurement types based on a filter logic.
 * - Loading initial selection from a persisted Flow or applying default selection logic.
 * - Updating the UI and persisting changes when the user selects/deselects types.
 * - Reacting to external changes in the persisted selection Flow.
 *
 * @param allMeasurementTypesProvider A lambda function that returns the complete list of available [MeasurementType]s.
 * @param selectedTypeIdsFlowProvider A lambda function that returns a [Flow] emitting the set of currently persisted selected measurement type IDs (as Strings).
 * @param onPersistSelectedTypeIds A lambda function called when the selection changes and needs to be persisted. It receives the set of selected type IDs (as Strings).
 * @param onSelectionChanged A lambda function called when the displayed selection changes. It receives a list of selected type IDs (as Ints).
 * @param filterLogic A lambda function that filters the `allMeasurementTypesProvider` list to determine which types are actually selectable and displayed in this row.
 * @param defaultSelectionLogic A lambda function that determines the default selection of type IDs (as Ints) if no persisted selection is found or if the persisted selection is invalid for the currently available types.
 * @param modifier The [Modifier] to be applied to this Composable.
 * @param allowEmptySelection If true, the user can deselect all types. If false, at least one type must remain selected (if there's more than one option).
 * @param iconBoxSize The size of the circular background for each measurement type icon.
 * @param iconSize The size of the measurement type icon itself.
 * @param spaceBetweenItems The horizontal spacing between each measurement type item in the row.
 */
@Composable
fun MeasurementTypeFilterRow(
    allMeasurementTypesProvider: () -> List<MeasurementType>,
    selectedTypeIdsFlowProvider: () -> Flow<Set<String>>,
    onPersistSelectedTypeIds: (Set<String>) -> Unit,
    onSelectionChanged: (List<Int>) -> Unit,
    filterLogic: (List<MeasurementType>) -> List<MeasurementType>,
    defaultSelectionLogic: (List<MeasurementType>) -> List<Int>,
    modifier: Modifier = Modifier,
    allowEmptySelection: Boolean = true,
    iconBoxSize: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    spaceBetweenItems: Dp = 8.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectableTypes = remember(allMeasurementTypesProvider, filterLogic) {
        filterLogic(allMeasurementTypesProvider())
    }

    val selectedTypeIdsFlow = remember(selectedTypeIdsFlowProvider) { selectedTypeIdsFlowProvider() }

    var displayedSelectedIds by remember { mutableStateOf<List<Int>>(emptyList()) }
    var isInitialized by remember { mutableStateOf(false) }

    // Effect 1: Initial loading from the Flow or applying default logic.
    LaunchedEffect(selectableTypes, selectedTypeIdsFlow, defaultSelectionLogic) {
        val savedTypeIdsSet = selectedTypeIdsFlow.firstOrNull() ?: emptySet()
        val initialIdsToDisplay: List<Int>

        if (selectableTypes.isNotEmpty()) {
            if (savedTypeIdsSet.isNotEmpty()) {
                // Filter saved IDs to include only those present in the current selectableTypes
                val validPersistedIds = savedTypeIdsSet
                    .mapNotNull { it.toIntOrNull() }
                    .filter { id -> selectableTypes.any { type -> type.id == id } }

                if (validPersistedIds.isEmpty()) {
                    // If persisted IDs are all invalid for current selectable types, or if none were persisted
                    // that are currently selectable, apply default logic.
                    val defaultIds = defaultSelectionLogic(selectableTypes)
                    initialIdsToDisplay = defaultIds
                    // Persist these defaults only if the original saved set was non-empty but resulted in no valid IDs,
                    // or if the default set is different from what was (emptily) loaded.
                    if (savedTypeIdsSet.isNotEmpty() || defaultIds.map { it.toString() }.toSet() != savedTypeIdsSet) {
                        onPersistSelectedTypeIds(defaultIds.map { it.toString() }.toSet())
                    }
                } else {
                    initialIdsToDisplay = validPersistedIds
                }
            } else {
                // No saved selection, apply default logic and persist it.
                val defaultIds = defaultSelectionLogic(selectableTypes)
                initialIdsToDisplay = defaultIds
                onPersistSelectedTypeIds(defaultIds.map { it.toString() }.toSet())
            }

            // Update displayed state and notify callback if different or not yet initialized
            if (displayedSelectedIds.toSet() != initialIdsToDisplay.toSet()) {
                displayedSelectedIds = initialIdsToDisplay
                onSelectionChanged(initialIdsToDisplay)
            } else if (!isInitialized) {
                // Ensure onSelectionChanged is called at least once with the initial state
                onSelectionChanged(initialIdsToDisplay)
            }
        } else {
            // No selectable types are available
            if (displayedSelectedIds.isNotEmpty() || savedTypeIdsSet.isNotEmpty()) {
                // Clear any previous selection if types become unavailable
                displayedSelectedIds = emptyList()
                onPersistSelectedTypeIds(emptySet()) // Persist empty set
                onSelectionChanged(emptyList())
            } else if (!isInitialized) {
                onSelectionChanged(emptyList())
            }
        }
        isInitialized = true
    }

    // Effect 2: React to changes from the Flow AFTER initialization.
    LaunchedEffect(isInitialized, selectedTypeIdsFlow, allMeasurementTypesProvider, filterLogic) {
        if (isInitialized) {
            selectedTypeIdsFlow
                .distinctUntilChanged()
                .collect { newPersistedSet ->
                    // Recalculate selectable types in case they changed externally
                    val currentAllTypes = allMeasurementTypesProvider()
                    val currentAvailableTypesForFilter = filterLogic(currentAllTypes)

                    val newIdsFromFlow = newPersistedSet
                        .mapNotNull { it.toIntOrNull() }
                        .filter { id -> currentAvailableTypesForFilter.any { type -> type.id == id } }

                    if (newIdsFromFlow.toSet() != displayedSelectedIds.toSet()) {
                        displayedSelectedIds = newIdsFromFlow
                        onSelectionChanged(newIdsFromFlow)
                    }
                }
        }
    }

    // Do not render the row if there are no selectable types and initialization is complete.
    if (selectableTypes.isEmpty() && isInitialized) {
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(spaceBetweenItems),
        verticalAlignment = Alignment.CenterVertically
    ) {
        selectableTypes.forEach { type ->
            val isSelected = type.id in displayedSelectedIds
            val iconBackgroundColor = if (isSelected) Color(type.color) else MaterialTheme.colorScheme.surfaceVariant
            val iconColor = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant // Consider MaterialTheme.colorScheme.onPrimary for selected state if type.color is primary-like

            RoundMeasurementIcon(
                icon = type.icon.resource,
                backgroundTint = iconBackgroundColor,
                iconTint = iconColor,
                size = iconSize,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = isInitialized,
                        onClick = {
                            val currentSelectionMutable = displayedSelectedIds.toMutableList()
                            val currentlySelectedInList = type.id in currentSelectionMutable

                            if (currentlySelectedInList) {
                                // Only allow deselection if empty selection is allowed or if more than one item is selected
                                if (allowEmptySelection || currentSelectionMutable.size > 1) {
                                    currentSelectionMutable.remove(type.id)
                                } else {
                                    // Prevent deselecting the last item if allowEmptySelection is false
                                    return@clickable
                                }
                            } else {
                                currentSelectionMutable.add(type.id)
                            }

                            val newSelectedIdsList = currentSelectionMutable.toList()
                            displayedSelectedIds = newSelectedIdsList
                            onSelectionChanged(newSelectedIdsList)

                            scope.launch {
                                val setToPersist = newSelectedIdsList.map { it.toString() }.toSet()
                                onPersistSelectedTypeIds(setToPersist)
                            }
                        }
                    )
            )
        }
    }
}
