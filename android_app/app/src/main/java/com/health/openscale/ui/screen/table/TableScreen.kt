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
package com.health.openscale.ui.screen.components // Using package from the provided code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Trend
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.SharedViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents the data for a single cell in the table, excluding the date cell.
 *
 * @property typeId The ID of the measurement type this cell data represents.
 * @property displayValue The formatted string value to display in the cell.
 * @property unit The unit of the measurement.
 * @property difference The difference from the previous measurement of the same type, if applicable.
 * @property trend The trend (up, down, none, not applicable) compared to the previous measurement.
 * @property originalInputType The original [InputFieldType] of the measurement.
 */
data class TableCellData(
    val typeId: Int,
    val displayValue: String,
    val unit: String,
    val difference: Float? = null,
    val trend: Trend = Trend.NOT_APPLICABLE,
    val originalInputType: InputFieldType
)

/**
 * Represents the internal data structure for a single row in the table.
 *
 * @property measurementId The unique ID of the measurement this row corresponds to.
 * @property timestamp The timestamp of the measurement.
 * @property formattedTimestamp The formatted date and time string for display.
 * @property values A map where the key is the measurement type ID (`typeId`) and the value
 *                  is the [TableCellData] for that type in this row.
 */
data class TableRowDataInternal(
    val measurementId: Int,
    val timestamp: Long,
    val formattedTimestamp: String,
    val values: Map<Int, TableCellData?>
)

/**
 * Composable screen that displays measurement data in a tabular format.
 *
 * The table shows a fixed date column and scrollable columns for selected measurement types.
 * Each cell can display the measured value and its trend/difference compared to the previous one.
 * Users can filter which measurement types are displayed as columns.
 * Tapping on a row navigates to the detailed view of that measurement.
 *
 * @param navController The NavController for navigation.
 * @param sharedViewModel The [SharedViewModel] providing measurement data, types, and UI state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel
) {
    val scope = rememberCoroutineScope()
    val enrichedMeasurements by sharedViewModel.enrichedMeasurementsFlow.collectAsState()
    val isLoading by sharedViewModel.isBaseDataLoading.collectAsState()
    val allAvailableTypesFromVM by sharedViewModel.measurementTypes.collectAsState()

    // Holds the IDs of columns selected by the user via the filter row.
    val selectedColumnIdsFromFilter = remember { mutableStateListOf<Int>() }

    // Determines the actual measurement types to display as columns based on user selection.
    val displayedTypes =
        remember(allAvailableTypesFromVM, selectedColumnIdsFromFilter.toList()) {
            allAvailableTypesFromVM.filter { type ->
                type.id in selectedColumnIdsFromFilter
            }
        }

    // Transforms enriched measurements into a list of TableRowDataInternal for easier rendering.
    val tableData = remember(enrichedMeasurements, displayedTypes, allAvailableTypesFromVM) {
        if (enrichedMeasurements.isEmpty() || displayedTypes.isEmpty()) {
            emptyList()
        } else {
            // Date formatter for the timestamp column.
            val dateFormatter = SimpleDateFormat("E, dd.MM.yy HH:mm", Locale.getDefault())

            enrichedMeasurements.map { enrichedItem -> // enrichedItem is EnrichedMeasurement
                val cellValues = displayedTypes.associate { colType -> // Iterate over sorted, displayed types
                    val typeId = colType.id
                    // Find the corresponding value with trend from the enrichedItem
                    val valueWithTrend = enrichedItem.valuesWithTrend.find { it.currentValue.type.id == typeId }

                    if (valueWithTrend != null) {
                        val originalMeasurementValue = valueWithTrend.currentValue.value // This is MeasurementValue
                        val actualType = valueWithTrend.currentValue.type // This is MeasurementType

                        val displayValueStr = when (actualType.inputType) {
                            InputFieldType.FLOAT -> originalMeasurementValue.floatValue?.let { "%.1f".format(Locale.getDefault(), it) } ?: "-"
                            InputFieldType.INT -> originalMeasurementValue.intValue?.toString() ?: "-"
                            InputFieldType.TEXT -> originalMeasurementValue.textValue ?: "-"
                            // Add other InputFieldTypes here if needed (DATE, TIME etc.)
                            else -> originalMeasurementValue.textValue ?: originalMeasurementValue.floatValue?.toString() ?: originalMeasurementValue.intValue?.toString() ?: "-"
                        }
                        val unitStr = if (displayValueStr != "-") actualType.unit.displayName else ""

                        typeId to TableCellData(
                            typeId = typeId,
                            displayValue = displayValueStr,
                            unit = unitStr,
                            difference = valueWithTrend.difference, // Use directly
                            trend = valueWithTrend.trend,           // Use directly
                            originalInputType = actualType.inputType
                        )
                    } else {
                        // Fallback: No value for this type in this specific measurement
                        // (e.g., if the type was not measured).
                        // Use colType (the type from the column definition) for default info.
                        typeId to TableCellData(
                            typeId = typeId,
                            displayValue = "-",
                            unit = colType.unit.displayName, // Show unit even if no value, for consistency
                            difference = null,
                            trend = Trend.NOT_APPLICABLE,
                            originalInputType = colType.inputType
                        )
                    }
                }
                TableRowDataInternal(
                    measurementId = enrichedItem.measurementWithValues.measurement.id,
                    timestamp = enrichedItem.measurementWithValues.measurement.timestamp,
                    formattedTimestamp = dateFormatter.format(Date(enrichedItem.measurementWithValues.measurement.timestamp)),
                    values = cellValues // cellValues is already Map<Int, TableCellData?>
                )
            }
        }
    }

    val tableScreenTitle = stringResource(id = R.string.route_title_table)
    val noColumnsOrMeasurementsMessage = stringResource(id = R.string.table_message_no_columns_or_measurements)
    val noMeasurementsMessage = stringResource(id = R.string.table_message_no_measurements)
    val noColumnsSelectedMessage = stringResource(id = R.string.table_message_no_columns_selected)
    val noDataForSelectionMessage = stringResource(id = R.string.table_message_no_data_for_selection)
    val dateColumnHeader = stringResource(id = R.string.table_header_date)


    LaunchedEffect(Unit, tableScreenTitle) {
        sharedViewModel.setTopBarTitle(tableScreenTitle)
    }

    val horizontalScrollState = rememberScrollState()
    val dateColumnWidth = 130.dp
    val minDataCellWidth = 110.dp // Slightly wider to accommodate value + difference

    Column(modifier = Modifier.fillMaxSize()) {
        // --- FILTER SELECTION ROW ---
        MeasurementTypeFilterRow(
            allMeasurementTypesProvider = { allAvailableTypesFromVM },
            selectedTypeIdsFlowProvider = { sharedViewModel.userSettingRepository.selectedTableTypeIds },
            onPersistSelectedTypeIds = { idsToSave -> // idsToSave is Set<String>
                scope.launch {
                    sharedViewModel.userSettingRepository.saveSelectedTableTypeIds(idsToSave)
                }
            },
            // Logic to determine which types are available for selection in the filter row.
            // Example: only show enabled types.
            filterLogic = { allTypes ->
                allTypes.filter { it.isEnabled }
            },
            // Logic to determine which types are selected by default.
            // Example: enabled types that are also marked as default for table view.
            defaultSelectionLogic = { availableFilteredTypes ->
                availableFilteredTypes.filter { it.isEnabled }.map { it.id }
            },
            onSelectionChanged = { newSelectedIds ->
                selectedColumnIdsFromFilter.clear()
                selectedColumnIdsFromFilter.addAll(newSelectedIds)
            },
            allowEmptySelection = false // Or true, depending on desired behavior
        )
        HorizontalDivider()

        // --- TABLE CONTENT ---
        if (isLoading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp), Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (enrichedMeasurements.isEmpty() && displayedTypes.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp), Alignment.Center
            ) { Text(noColumnsOrMeasurementsMessage) }
        } else if (enrichedMeasurements.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp), Alignment.Center
            ) { Text(noMeasurementsMessage) }
        } else if (displayedTypes.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp), Alignment.Center
            ) { Text(noColumnsSelectedMessage) }
        } else if (tableData.isEmpty()) {
            // This case implies data exists, but not for the currently selected combination of columns.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp), Alignment.Center
            ) { Text(noDataForSelectionMessage) }
        } else {
            // --- HEADER ROW ---
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 8.dp) // Vertical padding for the header row
                    .height(IntrinsicSize.Min), // Ensures cells in row have same height, accommodating multi-line text
                verticalAlignment = Alignment.CenterVertically
            ) {
                TableHeaderCellInternal(
                    text = dateColumnHeader,
                    modifier = Modifier
                        .width(dateColumnWidth)
                        .padding(horizontal = 6.dp) // Padding within the header cell
                        .fillMaxHeight(),
                    alignment = TextAlign.Start
                )
                // Scrollable header cells for measurement types
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    displayedTypes.forEach { type ->
                        TableHeaderCellInternal(
                            text = type.getDisplayName(LocalContext.current), // Measurement type name as header
                            modifier = Modifier
                                .width(minDataCellWidth)
                                .padding(horizontal = 6.dp) // Padding within the header cell
                                .fillMaxHeight(),
                            alignment = TextAlign.End // Align numeric headers to the end
                        )
                    }
                }
            }
            HorizontalDivider()

            // --- DATA ROWS ---
            LazyColumn(Modifier.fillMaxSize()) {
                items(tableData, key = { it.measurementId }) { rowData ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(
                                    Routes.measurementDetail(
                                        rowData.measurementId,
                                        sharedViewModel.selectedUserId.value // Pass current user ID if needed by detail screen
                                    )
                                )
                            }
                            .height(IntrinsicSize.Min), // Important for variable cell height based on content
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Fixed date cell
                        TableDataCellInternal(
                            cellData = null, // No TableCellData for the date itself
                            fixedText = rowData.formattedTimestamp,
                            modifier = Modifier
                                .width(dateColumnWidth)
                                .background(MaterialTheme.colorScheme.surface) // Ensure consistent background
                                .fillMaxHeight(),
                            alignment = TextAlign.Start,
                            isDateCell = true
                        )
                        // Scrollable data cells
                        Row(
                            Modifier
                                .weight(1f)
                                .horizontalScroll(horizontalScrollState)
                                .fillMaxHeight()
                        ) {
                            displayedTypes.forEach { colType ->
                                val cellData = rowData.values[colType.id]
                                TableDataCellInternal(
                                    cellData = cellData,
                                    modifier = Modifier
                                        .width(minDataCellWidth)
                                        .fillMaxHeight(), // Ensures cells in row have same height
                                    alignment = TextAlign.End // Numeric data usually aligned to end
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * A composable function for rendering a header cell in the table.
 *
 * @param text The text to display in the header cell.
 * @param modifier The modifier to be applied to the Text composable.
 * @param alignment The text alignment within the cell.
 */
@Composable
fun TableHeaderCellInternal(
    text: String,
    modifier: Modifier = Modifier,
    alignment: TextAlign = TextAlign.Center
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = alignment,
        maxLines = 2, // Allow up to two lines for longer headers
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp) // Vertical padding for text within the header cell
            .fillMaxHeight() // Ensures the cell takes up the full height of the header row
    )
}

/**
 * A composable function for rendering a data cell in the table.
 *
 * This cell can display either a fixed text (for date cells) or formatted measurement data
 * including value, unit, and trend indicator.
 *
 * @param cellData The [TableCellData] to display. Null for date cells if `fixedText` is provided.
 * @param modifier The modifier to be applied to the cell's Box container.
 * @param alignment The text alignment for the primary content of the cell.
 * @param fixedText A fixed string to display, used primarily for the date cell.
 * @param isDateCell A boolean indicating if this cell is the fixed date cell.
 */
@Composable
fun TableDataCellInternal(
    cellData: TableCellData?,
    modifier: Modifier = Modifier,
    alignment: TextAlign = TextAlign.Start,
    fixedText: String? = null,
    isDateCell: Boolean = false
) {
    Box(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp), // Padding inside each cell
        // Date cells are aligned to CenterStart, value cells to TopEnd for better layout with potential difference text
        contentAlignment = if (isDateCell) Alignment.CenterStart else Alignment.TopEnd
    ) {
        if (isDateCell && fixedText != null) {
            // Display for the fixed date cell
            Text(
                text = fixedText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = alignment,
                maxLines = 2, // Allow date to wrap if necessary
                overflow = TextOverflow.Ellipsis
            )
        } else if (cellData != null) {
            // Display for measurement data cells
            Column(horizontalAlignment = Alignment.End) { // Align content to the end (right)
                Text(
                    text = "${cellData.displayValue}${cellData.unit}",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Display difference and trend if available
                if (cellData.difference != null && cellData.trend != Trend.NOT_APPLICABLE) {
                    Spacer(modifier = Modifier.height(1.dp)) // Small space between value and difference
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        val trendIconVector = when (cellData.trend) {
                            Trend.UP -> Icons.Filled.ArrowUpward
                            Trend.DOWN -> Icons.Filled.ArrowDownward
                            else -> null // No icon for Trend.NONE or Trend.NOT_APPLICABLE
                        }
                        // Use a subtle color for the difference text and icon
                        val diffColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        val trendContentDescription = when (cellData.trend) {
                            Trend.UP -> stringResource(R.string.table_trend_up)
                            Trend.DOWN -> stringResource(R.string.table_trend_down)
                            else -> null
                        }

                        if (trendIconVector != null) {
                            Icon(
                                imageVector = trendIconVector,
                                contentDescription = trendContentDescription,
                                tint = diffColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                        }
                        Text(
                            text = (if (cellData.difference > 0 && cellData.trend != Trend.NONE) "+" else "") + // Add "+" for positive changes
                                    when (cellData.originalInputType) { // Format difference based on original type
                                        InputFieldType.FLOAT -> "%.1f".format(Locale.getDefault(), cellData.difference)
                                        InputFieldType.INT -> cellData.difference.toInt().toString()
                                        else -> "" // Should not happen for types with difference
                                    } + " ${cellData.unit}", // Append unit to the difference
                            style = MaterialTheme.typography.bodySmall,
                            color = diffColor,
                            textAlign = TextAlign.End
                        )
                    }
                } else if (cellData.originalInputType == InputFieldType.FLOAT || cellData.originalInputType == InputFieldType.INT) {
                    // Add a spacer if there's no difference to maintain consistent cell height for numeric types
                    // The height should roughly match the space taken by the difference text and icon.
                    Spacer(modifier = Modifier.height((MaterialTheme.typography.bodySmall.fontSize.value + 4).dp)) // Adjust dp as needed
                }
            }
        } else {
            // Fallback for empty cells (should ideally not happen if data is processed correctly)
            Text(
                text = "-", // Placeholder for empty data
                style = MaterialTheme.typography.bodyLarge,
                textAlign = alignment,
                modifier = Modifier.fillMaxHeight() // Maintain cell height
            )
        }
    }
}
