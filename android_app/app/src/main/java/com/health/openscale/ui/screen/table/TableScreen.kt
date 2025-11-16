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
 */
package com.health.openscale.ui.screen.table

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.Trend
import com.health.openscale.core.data.UnitType
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.components.MeasurementTypeFilterRow
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.screen.dialog.DeleteConfirmationDialog
import com.health.openscale.ui.screen.dialog.UserInputDialog
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data for a single (non-date) table cell.
 *
 * We store **fully formatted display strings**, so UI rendering is trivial and
 * unit formatting (incl. ST -> "X st Y lb") stays consistent across the app.
 *
 * @property typeId MeasurementType ID of this column.
 * @property displayValue Final value string **including unit** (e.g., "72.4 kg", "12 st 7 lb", "22.5 %", or a free text).
 * @property diffDisplay Optional difference string **including sign & unit** (e.g., "+0.7 kg", "−1 st 2 lb").
 * @property trend Trend state that controls the arrow icon (UP/DOWN/NONE/NOT_APPLICABLE).
 * @property evalState Optional evaluation state (LOW/NORMAL/HIGH) for the status dot/triangle.
 * @property flagged When true, the cell is flagged (e.g., out of plausible range) and shown with error emphasis.
 * @property unitType Optional unit type (useful for exports or a11y; not needed by the UI for rendering).
 */
data class TableCellData(
    val typeId: Int,
    val displayValue: String,
    val diffDisplay: String? = null,
    val trend: Trend = Trend.NOT_APPLICABLE,
    val evalState: EvaluationState? = null,
    val flagged: Boolean = false,
    val unitType: UnitType? = null
)

/**
 * A single row in the table.
 *
 * @property measurementId ID of the measurement (used for navigation).
 * @property timestamp Epoch millis of the measurement.
 * @property formattedTimestamp Preformatted date/time label for the fixed left column.
 * @property values Map of column typeId -> [TableCellData] for this row.
 */
data class TableRowDataInternal(
    val measurementId: Int,
    val timestamp: Long,
    val formattedTimestamp: String,
    val values: Map<Int, TableCellData?>
)

/**
 * Table of measurements with a fixed date column and horizontally scrollable value columns.
 *
 * - Left column shows the date/time (fixed).
 * - Right side contains user-selected measurement columns (scrollable).
 * - Cells show a formatted value, an evaluation symbol, and (if present) a diff row with trend arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enrichedMeasurements by sharedViewModel.enrichedMeasurementsFlow.collectAsState()
    val allAvailableTypesFromVM by sharedViewModel.measurementTypes.collectAsState()
    val userEvaluationContext by sharedViewModel.userEvaluationContext.collectAsState()

    // Column selection state provided by filter row.
    val selectedColumnIdsFromFilter = remember { mutableStateListOf<Int>() }
    var isInSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedItemIds = remember { mutableStateListOf<Int>() }
    val allUsersForDialog by sharedViewModel.allUsers.collectAsState()

    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showChangeUserDialog by rememberSaveable { mutableStateOf(false) }

    val displayedTypes =
        remember(allAvailableTypesFromVM, selectedColumnIdsFromFilter.toList()) {
            allAvailableTypesFromVM.filter { it.id in selectedColumnIdsFromFilter }
        }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? ->
            val currentUserId = sharedViewModel.selectedUserId.value
            if (uri != null && selectedItemIds.isNotEmpty() && currentUserId != null && currentUserId != 0) {
                sharedViewModel.performCsvExport(
                    userId = currentUserId,
                    uri = uri,
                    contentResolver = context.contentResolver,
                    filterByMeasurementIds = selectedItemIds.toList()
                )
                isInSelectionMode = false
                selectedItemIds.clear()
            }
        }
    )

    // Transform measurements -> table rows (compute eval state & formatted strings here).
    val tableData = remember(enrichedMeasurements, displayedTypes, allAvailableTypesFromVM, userEvaluationContext) {
        if (enrichedMeasurements.isEmpty() || displayedTypes.isEmpty()) {
            emptyList()
        } else {
            val dateFormatterDate = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            val dateFormatterTime = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())

            enrichedMeasurements.map { enrichedItem ->
                val ts = enrichedItem.measurementWithValues.measurement.timestamp

                val cellValues: Map<Int, TableCellData?> = displayedTypes.associate { colType ->
                    val typeId = colType.id
                    val valueWithTrend = enrichedItem.valuesWithTrend.find { it.currentValue.type.id == typeId }

                    if (valueWithTrend != null) {
                        val originalMeasurementValue = valueWithTrend.currentValue.value
                        val actualType = valueWithTrend.currentValue.type

                        // Build the final value string for display (includes unit for numeric types).
                        val displayValueStr: String = when (actualType.inputType) {
                            InputFieldType.FLOAT -> originalMeasurementValue.floatValue?.let {
                                LocaleUtils.formatValueForDisplay(it.toString(), actualType.unit)
                            } ?: "-"
                            InputFieldType.INT -> originalMeasurementValue.intValue?.let {
                                LocaleUtils.formatValueForDisplay(it.toString(), actualType.unit)
                            } ?: "-"
                            InputFieldType.TEXT -> originalMeasurementValue.textValue ?: "-"
                            else -> {
                                // Fallback for any other input type: prefer text, then float, then int
                                originalMeasurementValue.textValue
                                    ?: originalMeasurementValue.floatValue?.toString()
                                    ?: originalMeasurementValue.intValue?.toString()
                                    ?: "-"
                            }
                        }

                        // Numeric value (only for evaluation flags).
                        val numeric: Float? = when (actualType.inputType) {
                            InputFieldType.FLOAT -> originalMeasurementValue.floatValue
                            InputFieldType.INT -> originalMeasurementValue.intValue?.toFloat()
                            else -> null
                        }

                        // Compute evaluation state if possible (same logic as other screens).
                        val ctx = userEvaluationContext
                        val evalResult = if (ctx != null && numeric != null) {
                            sharedViewModel.evaluateMeasurement(
                                type = actualType,
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
                                // If there is no configured plausible range, use a % fallback for UnitType.PERCENT
                                plausible?.let { numeric < it.start || numeric > it.endInclusive }
                                    ?: (actualType.unit == UnitType.PERCENT && (numeric < 0f || numeric > 100f))
                            }

                        // Pre-format the diff (includes sign & unit). Only show "+" when trend != NONE.
                        val diffDisplayStr = valueWithTrend.difference?.let { diff ->
                            LocaleUtils.formatValueForDisplay(
                                value = diff.toString(),
                                unit = actualType.unit,
                                includeSign = (valueWithTrend.trend != Trend.NONE)
                            )
                        }

                        typeId to TableCellData(
                            typeId = typeId,
                            displayValue = displayValueStr,
                            diffDisplay = diffDisplayStr,
                            trend = valueWithTrend.trend,
                            evalState = evalResult?.state,
                            flagged = noAgeBand || outOfPlausibleRange,
                            unitType = actualType.unit
                        )
                    } else {
                        // No value for this type in this measurement -> placeholder cell.
                        typeId to TableCellData(
                            typeId = typeId,
                            displayValue = "-",
                            diffDisplay = null,
                            trend = Trend.NOT_APPLICABLE,
                            evalState = null,
                            flagged = false,
                            unitType = colType.unit
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

    fun deleteSelectedItems(selectedItemIds : List<Int>) {
        if (selectedItemIds.isEmpty()) {
            return
        }

        scope.launch {
            var allSucceeded = true

            for (id in selectedItemIds) {
                val measurementWithValues = sharedViewModel.getMeasurementById(id).firstOrNull()

                if (measurementWithValues != null) {
                    val success = sharedViewModel.deleteMeasurement(measurementWithValues.measurement, true)

                    if (!success) {
                        allSucceeded = false
                        break
                    }
                }
            }

            if (allSucceeded) {
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_items_deleted_successfully, formatArgs = listOf(selectedItemIds.size))
            } else {
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_error_deleting_items)
            }
        }
    }

    fun exportSelectedItems(selectedItemIds: List<Int>) {
        if (selectedItemIds.isEmpty()) {
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val fileName = "${timestamp}_openscale_selected_export.csv"
        exportCsvLauncher.launch(fileName)
    }

    fun changeUserOfSelectedItems(selectedItemIds : List<Int>, newUserId : Int) {
        if (selectedItemIds.isEmpty()) {
            return
        }

        scope.launch {
            var allSucceeded = true

            for (id in selectedItemIds) {
                val measurementWithValues = sharedViewModel.getMeasurementById(id).firstOrNull()

                if (measurementWithValues != null) {
                    val originalMeasurement = measurementWithValues.measurement
                    val originalValues = measurementWithValues.values.map { it.value }

                    val updatedMeasurement = originalMeasurement.copy(userId = newUserId)

                    val success = sharedViewModel.saveMeasurement(updatedMeasurement, originalValues, true)

                    if (!success) {
                        allSucceeded = false
                        break
                    }
                }
            }

            if (allSucceeded) {
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_items_user_changed_successfully, formatArgs = listOf(selectedItemIds.size))
            } else {
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_error_user_changed_items)
            }
        }
    }

    if (showChangeUserDialog) {
        val usersForDialog = allUsersForDialog.filter { user ->
            user.id != 0 && user.id != sharedViewModel.selectedUserId.value
        }
        if (usersForDialog.isNotEmpty()) {
            UserInputDialog(
                title = stringResource(R.string.dialog_title_select_user_for_assignment),
                users = usersForDialog,
                initialSelectedId = usersForDialog.firstOrNull()?.id,
                measurementIcon = MeasurementTypeIcon.IC_USER,
                iconBackgroundColor = MaterialTheme.colorScheme.primary,
                onDismiss = {
                    showChangeUserDialog = false
                },
                onConfirm = { selectedNewUserId ->
                    if (selectedNewUserId != null) {
                        changeUserOfSelectedItems(selectedItemIds.toList(), selectedNewUserId)
                    }
                    showChangeUserDialog = false
                    isInSelectionMode = false
                    selectedItemIds.clear()
                }
            )
        } else {
            LaunchedEffect(Unit) {
                showChangeUserDialog = false
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_no_other_users_to_change_to)
            }
        }
    }

    if (showDeleteConfirmDialog) {
        val messageResId = if (selectedItemIds.size == 1) {
            R.string.dialog_message_delete_selected_item
        } else {
            R.string.dialog_message_delete_selected_items
        }

        DeleteConfirmationDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
            },
            onConfirm = {
                deleteSelectedItems(selectedItemIds.toList())
                isInSelectionMode = false
                selectedItemIds.clear()
            },
            title = stringResource(id = R.string.dialog_title_delete_selected_items),
            text = stringResource(id = messageResId, selectedItemIds.size)
        )
    }

    LaunchedEffect(Unit, tableScreenTitle, isInSelectionMode, selectedItemIds.toList(), enrichedMeasurements) {
        sharedViewModel.setContextualSelectionMode(isInSelectionMode)

        if (isInSelectionMode) {
            sharedViewModel.setTopBarTitle(context.getString(R.string.items_selected_count, selectedItemIds.size))

            val actions = mutableListOf<TopBarAction>()

            actions.add(
                TopBarAction(
                    icon = Icons.Filled.SupervisorAccount,
                    contentDescriptionResId = R.string.desc_change_user,
                    onClick = {
                        val usersSelectable = allUsersForDialog.filter { it.id != 0 && it.id != sharedViewModel.selectedUser.value?.id }

                        if (usersSelectable.isNotEmpty()) {
                            showChangeUserDialog = true
                        } else {
                            sharedViewModel.showSnackbar(messageResId = R.string.snackbar_no_other_users_to_change_to)
                        }
                    }
                )
            )
            actions.add(
                TopBarAction(
                    icon = Icons.Filled.FileDownload,
                    contentDescriptionResId = R.string.desc_export_selected,
                    onClick = {
                        exportSelectedItems(selectedItemIds)
                    }
                )
            )
            actions.add(
                TopBarAction(
                    icon = Icons.Filled.Delete,
                    contentDescriptionResId = R.string.desc_delete_selected,
                    onClick = {
                        if (selectedItemIds.isNotEmpty()) {
                            showDeleteConfirmDialog = true
                        }
                    }
                )
            )

            actions.add(
                TopBarAction(
                    icon = Icons.Filled.Close,
                    contentDescriptionResId = R.string.desc_cancel_selection_mode,
                    onClick = {
                        isInSelectionMode = false
                        selectedItemIds.clear()
                    }
                )
            )

            sharedViewModel.setTopBarActions(actions)

        } else {
            sharedViewModel.setTopBarTitle(tableScreenTitle)

            val defaultActions = mutableListOf<TopBarAction>()
            if (!enrichedMeasurements.isEmpty()) {
                defaultActions.add(
                    TopBarAction(
                        icon = Icons.Outlined.CheckBox,
                        contentDescriptionResId = R.string.desc_enter_selection_mode,
                        onClick = { isInSelectionMode = true }
                    )
                )
            }

            sharedViewModel.setTopBarActions(defaultActions)
        }
    }

    if (isInSelectionMode) {
        BackHandler(enabled = true) {
            isInSelectionMode = false
            selectedItemIds.clear()
        }
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
            selectedTypeIdsFlowProvider = { sharedViewModel.selectedTableTypeIds },
            onPersistSelectedTypeIds = { idsToSave ->
                scope.launch { sharedViewModel.saveSelectedTableTypeIds(idsToSave) }
            },
            filterLogic = { allTypes ->
                allTypes.filter {
                    it.isEnabled &&
                    it.key != MeasurementTypeKey.DATE &&
                    it.key != MeasurementTypeKey.TIME &&
                    it.key != MeasurementTypeKey.USER
                }
            },
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
                    if (isInSelectionMode) {
                        val allItemsSelected = tableData.isNotEmpty() && selectedItemIds.size == tableData.size
                        val noItemsSelected = selectedItemIds.isEmpty()

                        val checkboxState = when {
                            allItemsSelected -> ToggleableState.On
                            noItemsSelected -> ToggleableState.Off
                            else -> ToggleableState.Indeterminate
                        }

                        Box(modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            TriStateCheckbox(
                                state = checkboxState,
                                onClick = {
                                    when (checkboxState) {
                                        ToggleableState.On -> selectedItemIds.clear()
                                        ToggleableState.Off -> {
                                            selectedItemIds.clear()
                                            selectedItemIds.addAll(tableData.map { it.measurementId })
                                        }
                                        ToggleableState.Indeterminate -> {
                                            selectedItemIds.clear()
                                            selectedItemIds.addAll(tableData.map { it.measurementId })
                                        }
                                    }
                                }
                            )
                        }
                    }

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
                        val isSelected = selectedItemIds.contains(rowData.measurementId)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected && isInSelectionMode) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                )
                                .clickable {
                                    if (isInSelectionMode) {
                                        if (isSelected) {
                                            selectedItemIds.remove(rowData.measurementId)
                                        } else {
                                            selectedItemIds.add(rowData.measurementId)
                                        }
                                    } else {
                                        navController.navigate(
                                            Routes.measurementDetail(
                                                rowData.measurementId,
                                                sharedViewModel.selectedUserId.value
                                            )
                                        )
                                    }
                                }
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Date cell (fixed column)
                            if (isInSelectionMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                selectedItemIds.add(rowData.measurementId)
                                            } else {
                                                selectedItemIds.remove(rowData.measurementId)
                                            }
                                        }
                                    )
                                }
                            }

                            TableDataCellInternal(
                                cellData = null,
                                fixedText = rowData.formattedTimestamp,
                                modifier = Modifier
                                    .widthIn(min = dateColMin, max = dateColMax)
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
 * Header cell for a column.
 *
 * @param text Header label.
 * @param modifier Layout modifier.
 * @param alignment Text alignment within the header cell.
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
 * Data cell renderer (handles both date cells and value cells).
 *
 * - For date cells, pass [fixedText] and set [isDateCell] = true.
 * - For value cells, pass [cellData]; it shows:
 *   - Line 1: formatted value + evaluation symbol.
 *   - Line 2: formatted diff with a trend arrow (if present).
 *
 * @param cellData The cell payload (null for date column).
 * @param modifier Layout modifier.
 * @param alignment Text alignment in the value cell.
 * @param fixedText Preformatted date/time string for the date column.
 * @param isDateCell True if this is the fixed date column.
 */
@Composable
fun TableDataCellInternal(
    cellData: TableCellData?,
    modifier: Modifier = Modifier,
    alignment: TextAlign = TextAlign.Start,
    fixedText: String? = null,
    isDateCell: Boolean = false
) {
    val symbolColWidth = 18.dp // stable space for the evaluation symbol

    Box(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = if (isDateCell) Alignment.CenterStart else Alignment.TopEnd
    ) {
        if (isDateCell && fixedText != null) {
            // Fixed left column (date/time)
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
                // --- Line 1: Value + evaluation symbol ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = cellData.displayValue, // already includes unit (if numeric)
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
                                .alignByBaseline(), // baseline-align with value text
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

                // --- Line 2: Diff (with arrow) ---
                if (!cellData.diffDisplay.isNullOrEmpty() && cellData.trend != Trend.NOT_APPLICABLE) {
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
                            Text(
                                text = cellData.diffDisplay!!, // e.g., "+0.7 kg" or "−1 st 2 lb"
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
            // Empty value cell placeholder
            Text(
                text = "-",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = alignment,
                modifier = Modifier.fillMaxHeight()
            )
        }
    }
}
