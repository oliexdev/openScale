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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.Trend
import com.health.openscale.core.service.MeasurementEvaluator
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data for a single (non-date) table cell.
 */
data class TableCellData(
    val typeId: Int,
    val displayValue: String,
    val unit: String,
    val difference: Float? = null,
    val trend: Trend = Trend.NOT_APPLICABLE,
    val originalInputType: InputFieldType,
    val evalState: EvaluationState? = null,  // computed evaluation state
    val flagged: Boolean = false             // true => show "!" in error color
)

/**
 * A single row in the table.
 */
data class TableRowDataInternal(
    val measurementId: Int,
    val timestamp: Long,
    val formattedTimestamp: String,
    val values: Map<Int, TableCellData?>
)

/**
 * Table of measurements with a fixed date column and horizontally scrollable value columns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel
) {
    val scope = rememberCoroutineScope()
    val enrichedMeasurements by sharedViewModel.enrichedMeasurementsFlow.collectAsState()
    val allAvailableTypesFromVM by sharedViewModel.measurementTypes.collectAsState()
    val userEvaluationContext by sharedViewModel.userEvaluationContext.collectAsState()

    // Column selection state provided by filter row.
    val selectedColumnIdsFromFilter = remember { mutableStateListOf<Int>() }

    val displayedTypes =
        remember(allAvailableTypesFromVM, selectedColumnIdsFromFilter.toList()) {
            allAvailableTypesFromVM.filter { it.id in selectedColumnIdsFromFilter }
        }

    // Transform enriched measurements -> table rows (compute eval state here).
    val tableData = remember(enrichedMeasurements, displayedTypes, allAvailableTypesFromVM, userEvaluationContext) {
        if (enrichedMeasurements.isEmpty() || displayedTypes.isEmpty()) {
            emptyList()
        } else {
            val dateFormatterDate = SimpleDateFormat.getDateInstance(
                SimpleDateFormat.MEDIUM,
                Locale.getDefault()
            )
            val dateFormatterTime = SimpleDateFormat.getTimeInstance(
                SimpleDateFormat.SHORT,
                Locale.getDefault()
            )

            enrichedMeasurements.map { enrichedItem ->
                val ts = enrichedItem.measurementWithValues.measurement.timestamp

                val cellValues = displayedTypes.associate { colType ->
                    val typeId = colType.id
                    val valueWithTrend = enrichedItem.valuesWithTrend.find { it.currentValue.type.id == typeId }

                    if (valueWithTrend != null) {
                        val originalMeasurementValue = valueWithTrend.currentValue.value
                        val actualType = valueWithTrend.currentValue.type

                        val displayValueStr = when (actualType.inputType) {
                            InputFieldType.FLOAT -> originalMeasurementValue.floatValue?.let { "%.1f".format(Locale.getDefault(), it) } ?: "-"
                            InputFieldType.INT -> originalMeasurementValue.intValue?.toString() ?: "-"
                            InputFieldType.TEXT -> originalMeasurementValue.textValue ?: "-"
                            else -> originalMeasurementValue.textValue
                                ?: originalMeasurementValue.floatValue?.toString()
                                ?: originalMeasurementValue.intValue?.toString()
                                ?: "-"
                        }

                        val unitStr = if (displayValueStr != "-") actualType.unit.displayName else ""

                        // --- Compute evaluation state and flags (like in Overview) ---
                        val numeric: Float? = when (actualType.inputType) {
                            InputFieldType.FLOAT -> originalMeasurementValue.floatValue
                            InputFieldType.INT   -> originalMeasurementValue.intValue?.toFloat()
                            else                 -> null
                        }

                        val ctx = userEvaluationContext
                        val evalResult = if (ctx != null && numeric != null) {
                            sharedViewModel.evaluateMeasurement(
                                typeKey = actualType.key,
                                value = numeric,
                                userEvaluationContext = ctx,
                                measuredAtMillis = ts
                            )
                        } else null

                        val noAgeBand = evalResult?.let { it.lowLimit < 0f || it.highLimit < 0f } ?: false
                        val plausible = sharedViewModel.getPlausiblePercentRange(actualType.key)
                        val outOfPlausibleRange =
                            if (numeric == null) {
                                false
                            } else {
                                plausible?.let { numeric < it.start || numeric > it.endInclusive }
                                    ?: (unitStr == "%" && (numeric < 0f || numeric > 100f))
                            }

                        typeId to TableCellData(
                            typeId = typeId,
                            displayValue = displayValueStr,
                            unit = unitStr,
                            difference = valueWithTrend.difference,
                            trend = valueWithTrend.trend,
                            originalInputType = actualType.inputType,
                            evalState = evalResult?.state,
                            flagged = noAgeBand || outOfPlausibleRange
                        )
                    } else {
                        typeId to TableCellData(
                            typeId = typeId,
                            displayValue = "-",
                            unit = colType.unit.displayName,
                            difference = null,
                            trend = Trend.NOT_APPLICABLE,
                            originalInputType = colType.inputType,
                            evalState = null,
                            flagged = false
                        )
                    }
                }

                TableRowDataInternal(
                    measurementId = enrichedItem.measurementWithValues.measurement.id,
                    timestamp = ts,
                    formattedTimestamp = dateFormatterDate.format(Date(ts)) + "\n" + dateFormatterTime.format(Date(ts)),
                    values = cellValues
                )
            }
        }
    }

    val tableScreenTitle = stringResource(id = R.string.route_title_table)
    val noColumnsOrMeasurementsMessage = stringResource(id = R.string.table_message_no_columns_or_measurements)
    val noMeasurementsMessage = stringResource(id = R.string.no_data_available)
    val noColumnsSelectedMessage = stringResource(id = R.string.table_message_no_columns_selected)
    val noDataForSelectionMessage = stringResource(id = R.string.table_message_no_data_for_selection)
    val dateColumnHeader = stringResource(id = R.string.table_header_date)

    LaunchedEffect(Unit, tableScreenTitle) {
        sharedViewModel.setTopBarTitle(tableScreenTitle)
    }

    val horizontalScrollState = rememberScrollState()
    val dateColMin = 100.dp
    val dateColMax = 160.dp
    val colWidth = 110.dp
    val commentWidth = 250.dp

    Column(modifier = Modifier.fillMaxSize()) {
        // --- FILTER SELECTION ROW ---
        MeasurementTypeFilterRow(
            allMeasurementTypesProvider = { allAvailableTypesFromVM },
            selectedTypeIdsFlowProvider = {
                sharedViewModel.selectedTableTypeIds
            },
            onPersistSelectedTypeIds = { idsToSave ->
                scope.launch {
                    sharedViewModel.saveSelectedTableTypeIds(idsToSave)
                }
            },
            filterLogic = { allTypes -> allTypes.filter { it.isEnabled } },
            defaultSelectionLogic = { availableFilteredTypes ->
                val defaultDesiredTypeIds = listOf(
                    MeasurementTypeKey.WEIGHT.id,
                    MeasurementTypeKey.BMI.id,
                    MeasurementTypeKey.BODY_FAT.id,
                    MeasurementTypeKey.WATER.id,
                    MeasurementTypeKey.MUSCLE.id,
                    MeasurementTypeKey.COMMENT.id
                )
                availableFilteredTypes
                    .filter { it.id in defaultDesiredTypeIds && it.isEnabled }
                    .map { it.id }
            },
            onSelectionChanged = { newSelectedIds ->
                selectedColumnIdsFromFilter.clear()
                selectedColumnIdsFromFilter.addAll(newSelectedIds)
            },
            allowEmptySelection = false
        )
        HorizontalDivider()

        // --- TABLE CONTENT ---
        when {
            enrichedMeasurements.isEmpty() && displayedTypes.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp), Alignment.Center
                ) { Text(noColumnsOrMeasurementsMessage) }
            }

            enrichedMeasurements.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp), Alignment.Center
                ) { Text(noMeasurementsMessage) }
            }

            displayedTypes.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp), Alignment.Center
                ) { Text(noColumnsSelectedMessage) }
            }

            tableData.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp), Alignment.Center
                ) { Text(noDataForSelectionMessage) }
            }

            else -> {
                // --- HEADER ROW ---
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp)
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableHeaderCellInternal(
                        text = dateColumnHeader,
                        modifier = Modifier
                            .widthIn(min = dateColMin, max = dateColMax)
                            .padding(horizontal = 6.dp)
                            .fillMaxHeight(),
                        alignment = TextAlign.Start
                    )
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        displayedTypes.forEach { type ->
                            val width = if (type.key == MeasurementTypeKey.COMMENT) commentWidth else colWidth
                            TableHeaderCellInternal(
                                text = type.getDisplayName(LocalContext.current),
                                modifier = Modifier
                                    .width(width)
                                    .padding(horizontal = 6.dp)
                                    .fillMaxHeight(),
                                alignment = TextAlign.Center
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
                                            sharedViewModel.selectedUserId.value
                                        )
                                    )
                                }
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Date cell (fixed column)
                            TableDataCellInternal(
                                cellData = null,
                                fixedText = rowData.formattedTimestamp,
                                modifier = Modifier
                                    .widthIn(min = dateColMin, max = dateColMax)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .fillMaxHeight(),
                                alignment = TextAlign.Start,
                                isDateCell = true
                            )
                            // Scrollable value cells
                            Row(
                                Modifier
                                    .weight(1f)
                                    .horizontalScroll(horizontalScrollState)
                                    .fillMaxHeight()
                            ) {
                                displayedTypes.forEach { colType ->
                                    val cellData = rowData.values[colType.id]
                                    val width = if (colType.key == MeasurementTypeKey.COMMENT) commentWidth else colWidth
                                    TableDataCellInternal(
                                        cellData = cellData,
                                        modifier = Modifier
                                            .width(width)
                                            .fillMaxHeight(),
                                        alignment = if (colType.key == MeasurementTypeKey.COMMENT) TextAlign.Start else TextAlign.End
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
}

/**
 * Header cell.
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
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .fillMaxHeight()
    )
}

/**
 * Data cell (handles date or value; adds eval symbol near the value).
 */
@Composable
fun TableDataCellInternal(
    cellData: TableCellData?,
    modifier: Modifier = Modifier,
    alignment: TextAlign = TextAlign.Start,
    fixedText: String? = null,
    isDateCell: Boolean = false
) {
    val symbolColWidth = 18.dp // stable space for symbol

    Box(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = if (isDateCell) Alignment.CenterStart else Alignment.TopEnd
    ) {
        if (isDateCell && fixedText != null) {
            Text(
                text = fixedText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = alignment,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        } else if (cellData != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                // --- Line 1: Value + Symbol in one Row ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    val unitPart = if (cellData.unit.isNotEmpty()) " ${cellData.unit}" else ""
                    Text(
                        text = "${cellData.displayValue}$unitPart",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = alignment,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .alignByBaseline()
                    )

                    if (cellData.evalState != null) {
                        val symbol = when {
                            cellData.flagged -> "!"
                            cellData.evalState == EvaluationState.HIGH -> "▲"
                            cellData.evalState == EvaluationState.LOW  -> "▼"
                            else -> "●"
                        }
                        val color = if (cellData.flagged) {
                            MaterialTheme.colorScheme.error
                        } else {
                            cellData.evalState.toColor()
                        }
                        Box(
                            modifier = Modifier
                                .width(symbolColWidth)
                                .alignByBaseline(), // keep baseline alignment with value
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = symbol,
                                color = color,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(symbolColWidth))
                    }
                }

                // --- Line 2: Diff stays under the value ---
                if (cellData.difference != null && cellData.trend != Trend.NOT_APPLICABLE) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            val trendIconVector = when (cellData.trend) {
                                Trend.UP -> Icons.Filled.ArrowUpward
                                Trend.DOWN -> Icons.Filled.ArrowDownward
                                else -> null
                            }
                            val diffColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            if (trendIconVector != null) {
                                Icon(
                                    imageVector = trendIconVector,
                                    contentDescription = null,
                                    tint = diffColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                            }
                            val diffText =
                                (if (cellData.difference > 0 && cellData.trend != Trend.NONE) "+" else "") +
                                        when (cellData.originalInputType) {
                                            InputFieldType.FLOAT -> "%.1f".format(Locale.getDefault(), cellData.difference)
                                            InputFieldType.INT   -> cellData.difference.toInt().toString()
                                            else                 -> ""
                                        } +
                                        (if (cellData.unit.isNotEmpty()) " ${cellData.unit}" else "")
                            Text(
                                text = diffText,
                                style = MaterialTheme.typography.bodySmall,
                                color = diffColor,
                                textAlign = TextAlign.End
                            )
                        }
                        Spacer(modifier = Modifier.width(symbolColWidth))
                    }
                }
            }
        } else {
            Text(
                text = "-",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = alignment,
                modifier = Modifier.fillMaxHeight()
            )
        }
    }
}
