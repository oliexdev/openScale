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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.Trend
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.AggregatedMeasurement
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.components.MeasurementTypeFilterRow
import com.health.openscale.ui.screen.components.provideFilterTopBarAction
import com.health.openscale.ui.screen.components.rememberAddMeasurementActionButton
import com.health.openscale.ui.screen.components.rememberBluetoothActionButton
import com.health.openscale.ui.screen.components.rememberResolvedAggregationLevel
import com.health.openscale.ui.screen.dialog.DeleteConfirmationDialog
import com.health.openscale.ui.screen.dialog.UserInputDialog
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import com.health.openscale.core.utils.LocaleUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

data class TableCellData(
    val typeId: Int,
    val displayValue: String,
    val diffDisplay: String? = null,
    val trend: Trend = Trend.NOT_APPLICABLE,
    val evalState: EvaluationState? = null,
    val flagged: Boolean = false,
    val unitType: UnitType? = null,
    val rawCount: Int = 1,
)

data class TableRowDataInternal(
    val measurementId: Int,
    val timestamp: Long,
    val formattedTimestamp: String,
    val values: Map<Int, TableCellData?>,
    val isAggregated: Boolean = false,
    val periodStartMillis: Long? = null,
    val periodEndMillis: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
    drillDownStartMillis: Long? = null,
    drillDownEndMillis: Long? = null,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val isDrillDown = drillDownStartMillis != null && drillDownEndMillis != null

    // ── Aggregation ───────────────────────────────────────────────────────────
    val activeAggregationLevel by rememberResolvedAggregationLevel(
        screenContextName = SettingsPreferenceKeys.TABLE_SCREEN_CONTEXT,
        sharedViewModel   = sharedViewModel,
    )
    val effectiveAggregationLevel = if (isDrillDown) AggregationLevel.NONE else activeAggregationLevel

    // ── Data ──────────────────────────────────────────────────────────────────
    val tableUiState by if (isDrillDown) {
        sharedViewModel.drillDownFlow(drillDownStartMillis!!, drillDownEndMillis!!)
            .collectAsStateWithLifecycle(initialValue = SharedViewModel.UiState.Loading)
    } else {
        sharedViewModel.screenFlow(SettingsPreferenceKeys.TABLE_SCREEN_CONTEXT)
            .collectAsStateWithLifecycle(initialValue = SharedViewModel.UiState.Loading)
    }

    val aggregatedItems: List<AggregatedMeasurement> = remember(tableUiState) {
        when (val s = tableUiState) {
            is SharedViewModel.UiState.Success -> s.data
            else -> emptyList()
        }
    }

    // ── Column filter ─────────────────────────────────────────────────────────
    val allAvailableTypesFromVM by sharedViewModel.measurementTypes.collectAsState()
    val userEvaluationContext   by sharedViewModel.userEvaluationContext.collectAsState()

    val selectedColumnIdsFromFilter = remember { mutableStateListOf<Int>() }
    LaunchedEffect(isDrillDown, allAvailableTypesFromVM) {
        if (isDrillDown && selectedColumnIdsFromFilter.isEmpty() && allAvailableTypesFromVM.isNotEmpty()) {
            val defaultIds = allAvailableTypesFromVM
                .filter {
                    it.isEnabled &&
                            it.key != MeasurementTypeKey.DATE &&
                            it.key != MeasurementTypeKey.TIME &&
                            it.key != MeasurementTypeKey.USER
                }
                .map { it.id }
            selectedColumnIdsFromFilter.addAll(defaultIds)
        }
    }

    val displayedTypes = remember(allAvailableTypesFromVM, selectedColumnIdsFromFilter.toList()) {
        allAvailableTypesFromVM.filter { it.id in selectedColumnIdsFromFilter }
    }

    // ── Selection ─────────────────────────────────────────────────────────────
    var isInSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun toggleKey(key: String) {
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }
    fun clearKeys() { selectedKeys = emptySet() }
    fun addAllKeys(keys: Collection<String>) { selectedKeys = keys.toSet() }

    var tableDataSnapshot by remember { mutableStateOf<List<TableRowDataInternal>>(emptyList()) }

    val aggItemByPeriodStart: Map<Long, AggregatedMeasurement> = remember(aggregatedItems) {
        aggregatedItems.associateBy { it.periodStartMillis }
    }
    val snapshotByPeriodStart: Map<Long, TableRowDataInternal> = remember(tableDataSnapshot) {
        tableDataSnapshot.mapNotNull { row ->
            val ps = row.periodStartMillis ?: return@mapNotNull null
            ps to row
        }.toMap()
    }

    /**
     * Translates UI selection keys to real flat measurement IDs.
     *
     * Raw mode:        key == measurementId  → parse directly.
     * Aggregated mode: key == periodStartMillis → expand via drillDownFlow.
     *
     * FIX: Use `.filter { it is Success }.first()` instead of `.firstOrNull { it is Success }`
     * because drillDownFlow is a StateFlow starting with Loading — firstOrNull would
     * immediately grab the Loading state and return null.
     */
    suspend fun resolveSelectedMeasurementIds(): List<Int> {
        if (effectiveAggregationLevel == AggregationLevel.NONE) {
            // Raw mode (also covers drill-down): keys are direct measurement IDs
            return selectedKeys.mapNotNull { it.toIntOrNull() }
        }
        // Aggregated mode: expand each selected period to its raw measurement IDs in parallel
        val snap = snapshotByPeriodStart
        return kotlinx.coroutines.coroutineScope {
            selectedKeys.map { key ->
                async {
                    val periodStart = key.toLongOrNull() ?: return@async emptyList<Int>()
                    val row = snap[periodStart] ?: return@async emptyList<Int>()
                    val periodEnd = row.periodEndMillis ?: return@async emptyList<Int>()
                    // FIX: filter+first instead of firstOrNull — StateFlow starts with Loading,
                    // so firstOrNull always grabs Loading (not Success) and returns null.
                    sharedViewModel.drillDownFlow(periodStart, periodEnd)
                        .filter { it is SharedViewModel.UiState.Success }
                        .first()
                        .let { (it as SharedViewModel.UiState.Success).data }
                        .map { it.enriched.measurementWithValues.measurement.id }
                }
            }.awaitAll().flatten().distinct()
        }
    }

    fun rowKey(row: TableRowDataInternal): String =
        if (row.isAggregated) row.periodStartMillis!!.toString()
        else row.measurementId.toString()

    val resolvedSelectionCount = remember(selectedKeys, tableDataSnapshot) {
        if (effectiveAggregationLevel == AggregationLevel.NONE)
            selectedKeys.size
        else
            selectedKeys.sumOf { key ->
                val periodStart = key.toLongOrNull() ?: return@sumOf 0
                aggItemByPeriodStart[periodStart]?.aggregatedFromCount ?: 1
            }
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    val allUsersForDialog       by sharedViewModel.allUsers.collectAsState()
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showChangeUserDialog    by rememberSaveable { mutableStateOf(false) }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? ->
            val currentUserId = sharedViewModel.selectedUserId.value
            if (uri != null && currentUserId != null && currentUserId != 0) {
                scope.launch {
                    val resolvedIds = resolveSelectedMeasurementIds()
                    if (resolvedIds.isNotEmpty()) {
                        sharedViewModel.performCsvExport(
                            userId                 = currentUserId,
                            uri                    = uri,
                            contentResolver        = context.contentResolver,
                            filterByMeasurementIds = resolvedIds,
                        )
                        isInSelectionMode = false
                        clearKeys()
                    }
                }
            }
        },
    )

    fun deleteSelectedItems() {
        scope.launch {
            val ids = resolveSelectedMeasurementIds()
            if (ids.isEmpty()) return@launch
            var allSucceeded = true
            for (id in ids) {
                val mwv = sharedViewModel.getMeasurementById(id).first()
                if (mwv != null) {
                    val ok = sharedViewModel.deleteMeasurement(mwv.measurement, true)
                    if (!ok) { allSucceeded = false; break }
                }
            }
            if (allSucceeded)
                sharedViewModel.showSnackbar(
                    messageResId = R.string.snackbar_items_deleted_successfully,
                    formatArgs   = listOf(ids.size),
                )
            else
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_error_deleting_items)
            isInSelectionMode = false
            clearKeys()
        }
    }

    fun exportSelectedItems() {
        val ts = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        exportCsvLauncher.launch("${ts}_openscale_selected_export.csv")
    }

    fun changeUserOfSelectedItems(newUserId: Int) {
        scope.launch {
            val ids = resolveSelectedMeasurementIds()
            if (ids.isEmpty()) return@launch

            // Snapshot ALL measurements before any modifications.
            // After saveMeasurement(userId = newUserId), Room immediately re-emits the
            // flow for the current user — the measurement disappears from it because it
            // now belongs to a different user. Subsequent getMeasurementById().first()
            // calls inside the loop would return null, breaking the operation.
            val snapshots = ids.mapNotNull { id ->
                sharedViewModel.getMeasurementById(id).first()
            }
            if (snapshots.isEmpty()) return@launch

            var allSucceeded = true
            for (mwv in snapshots) {
                val ok = sharedViewModel.saveMeasurement(
                    measurement = mwv.measurement.copy(userId = newUserId),
                    values      = mwv.values.map { it.value },
                    silent      = true,
                )
                if (!ok) { allSucceeded = false; break }
            }

            if (allSucceeded)
                sharedViewModel.showSnackbar(
                    messageResId = R.string.snackbar_items_user_changed_successfully,
                    formatArgs   = listOf(snapshots.size),
                )
            else
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_error_user_changed_items)
            isInSelectionMode = false
            clearKeys()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showChangeUserDialog) {
        val usersForDialog = allUsersForDialog.filter {
            it.id != 0 && it.id != sharedViewModel.selectedUserId.value
        }
        if (usersForDialog.isNotEmpty()) {
            UserInputDialog(
                title               = stringResource(R.string.dialog_title_select_user_for_assignment),
                users               = usersForDialog,
                initialSelectedId   = usersForDialog.firstOrNull()?.id,
                measurementIcon     = MeasurementTypeIcon.IC_USER,
                iconBackgroundColor = MaterialTheme.colorScheme.primary,
                onDismiss           = { showChangeUserDialog = false },
                onConfirm           = { selectedNewUserId ->
                    if (selectedNewUserId != null) changeUserOfSelectedItems(selectedNewUserId)
                    showChangeUserDialog = false
                    // isInSelectionMode and clearKeys() are handled inside
                    // changeUserOfSelectedItems after the coroutine completes
                },
            )
        } else {
            LaunchedEffect(Unit) {
                showChangeUserDialog = false
                sharedViewModel.showSnackbar(messageResId = R.string.snackbar_no_other_users_to_change_to)
            }
        }
    }

    if (showDeleteConfirmDialog) {
        val resolvedCount = remember(selectedKeys, tableDataSnapshot) {
            if (effectiveAggregationLevel == AggregationLevel.NONE) selectedKeys.size
            else selectedKeys.sumOf { key ->
                val periodStart = key.toLongOrNull() ?: return@sumOf 0
                aggItemByPeriodStart[periodStart]?.aggregatedFromCount ?: 1
            }
        }
        DeleteConfirmationDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            onConfirm        = {
                deleteSelectedItems()
                showDeleteConfirmDialog = false
            },
            title = stringResource(R.string.dialog_title_delete_selected_items),
            text  = stringResource(
                if (resolvedCount == 1) R.string.dialog_message_delete_selected_item
                else R.string.dialog_message_delete_selected_items,
                resolvedCount,
            ),
        )
    }

    // ── Formatters ────────────────────────────────────────────────────────────
    val dateFormatterDate      = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()) }
    val dateFormatterDayOfWeek = remember { SimpleDateFormat("EE", Locale.getDefault()) }
    val dateFormatterTime      = remember { DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()) }

    val aggregationLabelFormatter: (Long, AggregationLevel) -> String =
        remember(effectiveAggregationLevel) {
            { timestamp, level ->
                val date   = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                val locale = Locale.getDefault()
                when (level) {
                    AggregationLevel.NONE  -> ""
                    AggregationLevel.DAY   -> dateFormatterDate.format(Date(timestamp))
                    AggregationLevel.WEEK  -> {
                        val wf = WeekFields.of(locale)
                        "${date.get(wf.weekBasedYear())} – ${context.getString(R.string.calendar_week_abbrev)} ${date.get(wf.weekOfWeekBasedYear())}"
                    }
                    AggregationLevel.MONTH ->
                        date.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
                    AggregationLevel.YEAR  -> date.year.toString()
                }
            }
        }

    val plausibleRangesByTypeKey = remember(displayedTypes) {
        displayedTypes.associate { type ->
            type.key to sharedViewModel.getPlausiblePercentRange(type.key)
        }
    }

    // ── Table data ────────────────────────────────────────────────────────────
    val tableData: List<TableRowDataInternal> = remember(
        aggregatedItems,
        displayedTypes,
        userEvaluationContext,
        plausibleRangesByTypeKey,
        effectiveAggregationLevel,
    ) {
        if (aggregatedItems.isEmpty() || displayedTypes.isEmpty()) {
            emptyList<TableRowDataInternal>().also { tableDataSnapshot = it }
        } else {
            aggregatedItems.map { aggItem ->
                val enrichedItem = aggItem.enriched
                val ts           = enrichedItem.measurementWithValues.measurement.timestamp
                val date         = Date(ts)
                val isAggregated = effectiveAggregationLevel != AggregationLevel.NONE

                val periodStart    = aggItem.periodStartMillis
                val periodEnd      = aggItem.periodEndMillis
                val periodRawCount = aggItem.aggregatedFromCount

                val formattedTs = if (isAggregated) {
                    "${aggregationLabelFormatter(ts, effectiveAggregationLevel)} ($periodRawCount)"
                } else {
                    "${dateFormatterDate.format(date)} (${dateFormatterDayOfWeek.format(date)})\n${dateFormatterTime.format(date)}"
                }

                val valuesByTypeId = enrichedItem.valuesWithTrend.associateBy { it.currentValue.type.id }

                val cellValues: Map<Int, TableCellData?> = displayedTypes.associate { colType ->
                    val typeId         = colType.id
                    val valueWithTrend = valuesByTypeId[typeId]

                    if (valueWithTrend != null) {
                        val originalMeasurementValue = valueWithTrend.currentValue.value
                        val actualType               = valueWithTrend.currentValue.type

                        val displayValueStr: String = when (actualType.inputType) {
                            InputFieldType.FLOAT -> originalMeasurementValue.floatValue
                                ?.let { LocaleUtils.formatValueForDisplay(it.toString(), actualType.unit) } ?: "-"
                            InputFieldType.INT   -> originalMeasurementValue.intValue
                                ?.let { LocaleUtils.formatValueForDisplay(it.toString(), actualType.unit) } ?: "-"
                            InputFieldType.TEXT  -> originalMeasurementValue.textValue ?: "-"
                            else                 -> originalMeasurementValue.textValue
                                ?: originalMeasurementValue.floatValue?.toString()
                                ?: originalMeasurementValue.intValue?.toString()
                                ?: "-"
                        }

                        val numeric: Float? = when (actualType.inputType) {
                            InputFieldType.FLOAT -> originalMeasurementValue.floatValue
                            InputFieldType.INT   -> originalMeasurementValue.intValue?.toFloat()
                            else                 -> null
                        }

                        val evalResult = userEvaluationContext?.let { ctx ->
                            if (numeric != null)
                                sharedViewModel.evaluateMeasurement(
                                    type                  = actualType,
                                    value                 = numeric,
                                    userEvaluationContext = ctx,
                                    measuredAtMillis      = ts,
                                )
                            else null
                        }

                        val noAgeBand = evalResult?.let { it.lowLimit < 0f || it.highLimit < 0f } ?: false
                        val plausible = plausibleRangesByTypeKey[actualType.key]
                        val outOfPlausibleRange = if (numeric == null || isAggregated) false
                        else plausible?.let { numeric < it.start || numeric > it.endInclusive }
                            ?: (actualType.unit == UnitType.PERCENT && (numeric < 0f || numeric > 100f))

                        val diffDisplayStr = valueWithTrend.difference?.let { diff ->
                            LocaleUtils.formatValueForDisplay(
                                value       = diff.toString(),
                                unit        = actualType.unit,
                                includeSign = (valueWithTrend.trend != Trend.NONE),
                            )
                        }

                        typeId to TableCellData(
                            typeId       = typeId,
                            displayValue = displayValueStr,
                            diffDisplay  = diffDisplayStr,
                            trend        = valueWithTrend.trend,
                            evalState    = evalResult?.state,
                            flagged      = noAgeBand || outOfPlausibleRange,
                            unitType     = actualType.unit,
                            rawCount     = periodRawCount,
                        )
                    } else {
                        typeId to TableCellData(
                            typeId       = typeId,
                            displayValue = "-",
                            diffDisplay  = null,
                            trend        = Trend.NOT_APPLICABLE,
                            evalState    = null,
                            flagged      = false,
                            unitType     = colType.unit,
                            rawCount     = 1,
                        )
                    }
                }

                TableRowDataInternal(
                    measurementId      = enrichedItem.measurementWithValues.measurement.id,
                    timestamp          = ts,
                    formattedTimestamp = formattedTs,
                    values             = cellValues,
                    isAggregated       = isAggregated,
                    periodStartMillis  = periodStart,
                    periodEndMillis    = periodEnd,
                )
            }.also { tableDataSnapshot = it }
        }
    }

    // ── Scroll + highlight ────────────────────────────────────────────────────
    val latestMeasurementId   = remember(tableData) { tableData.firstOrNull()?.measurementId }
    var highlightedItemId     by remember { mutableStateOf<Int?>(null) }
    val lazyListState         = rememberLazyListState()

    LaunchedEffect(latestMeasurementId) {
        if (latestMeasurementId != null && tableData.isNotEmpty() && !isDrillDown) {
            kotlinx.coroutines.delay(500)
            if (latestMeasurementId == tableData.firstOrNull()?.measurementId) {
                highlightedItemId = latestMeasurementId
                lazyListState.animateScrollToItem(0)
                kotlinx.coroutines.delay(1500)
                highlightedItemId = null
            }
        }
    }

    // ── Top bar ───────────────────────────────────────────────────────────────
    val tableScreenTitle = if (isDrillDown && drillDownStartMillis != null && drillDownEndMillis != null) {
        val spanDays  = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(drillDownStartMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
            Instant.ofEpochMilli(drillDownEndMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
        val midMillis = drillDownStartMillis + (drillDownEndMillis - drillDownStartMillis) / 2L
        val date      = Instant.ofEpochMilli(midMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val locale    = Locale.getDefault()
        val count     = aggregatedItems.size
        val label = when {
            spanDays <= 1  -> dateFormatterDate.format(Date(drillDownStartMillis))
            spanDays <= 8  -> {
                val wf = WeekFields.of(locale)
                "${date.get(wf.weekBasedYear())} – ${context.getString(R.string.calendar_week_abbrev)} ${date.get(wf.weekOfWeekBasedYear())}"
            }
            spanDays <= 32 -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
            else           -> date.year.toString()
        }
        "$label ($count)"
    } else {
        stringResource(R.string.route_title_table)
    }

    val addMeasurementAction = rememberAddMeasurementActionButton(sharedViewModel, navController)
    val bluetoothAction      = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    // FIX: also provide filter action in drill-down (was null before)
    val filterAction         = if (!isDrillDown) provideFilterTopBarAction(
        sharedViewModel   = sharedViewModel,
        screenContextName = SettingsPreferenceKeys.TABLE_SCREEN_CONTEXT,
    ) else null

    LaunchedEffect(tableScreenTitle, isInSelectionMode, selectedKeys.size, aggregatedItems.size) {
        sharedViewModel.setContextualSelectionMode(isInSelectionMode)
        if (isInSelectionMode) {
            sharedViewModel.setTopBarTitle(
                context.getString(R.string.items_selected_count, resolvedSelectionCount)
            )
            sharedViewModel.setTopBarActions(listOf(
                TopBarAction(
                    icon                    = Icons.Filled.SupervisorAccount,
                    contentDescriptionResId = R.string.desc_change_user,
                    onClick                 = {
                        val selectable = allUsersForDialog.filter {
                            it.id != 0 && it.id != sharedViewModel.selectedUser.value?.id
                        }
                        if (selectable.isNotEmpty()) showChangeUserDialog = true
                        else sharedViewModel.showSnackbar(messageResId = R.string.snackbar_no_other_users_to_change_to)
                    },
                ),
                TopBarAction(
                    icon                    = Icons.Filled.FileDownload,
                    contentDescriptionResId = R.string.desc_export_selected,
                    onClick                 = { exportSelectedItems() },
                ),
                TopBarAction(
                    icon                    = Icons.Filled.Delete,
                    contentDescriptionResId = R.string.desc_delete_selected,
                    onClick                 = { if (selectedKeys.isNotEmpty()) showDeleteConfirmDialog = true },
                ),
                TopBarAction(
                    icon                    = Icons.Filled.Close,
                    contentDescriptionResId = R.string.desc_cancel_selection_mode,
                    onClick                 = { isInSelectionMode = false; clearKeys() },
                ),
            ))
        } else {
            sharedViewModel.setTopBarTitle(tableScreenTitle)
            val actions = mutableListOf<TopBarAction>()
            if (!isDrillDown) {
                actions.add(bluetoothAction)
                actions.add(addMeasurementAction)
            }
            // FIX: show selection mode button also in drill-down if there are items
            if (aggregatedItems.isNotEmpty()) {
                actions.add(TopBarAction(
                    icon                    = Icons.Outlined.CheckBox,
                    contentDescriptionResId = R.string.desc_enter_selection_mode,
                    onClick                 = { isInSelectionMode = true },
                ))
            }
            filterAction?.let { actions.add(it) }
            sharedViewModel.setTopBarActions(actions)
        }
    }

    // FIX: BackHandler also active in drill-down when in selection mode
    if (isInSelectionMode) {
        BackHandler(enabled = true) {
            isInSelectionMode = false
            clearKeys()
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    val horizontalScrollState = rememberScrollState()
    val dateColMin   = 100.dp
    val dateColMax   = 180.dp
    val colWidth     = 110.dp
    val commentWidth = 250.dp

    val noColumnsOrMeasurementsMessage = stringResource(R.string.table_message_no_columns_or_measurements)
    val noMeasurementsMessage          = stringResource(R.string.no_data_available)
    val noColumnsSelectedMessage       = stringResource(R.string.table_message_no_columns_selected)
    val noDataForSelectionMessage      = stringResource(R.string.table_message_no_data_for_selection)
    val dateColumnHeader               = stringResource(R.string.table_header_date)

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isDrillDown) {
            MeasurementTypeFilterRow(
                allMeasurementTypesProvider = { allAvailableTypesFromVM },
                selectedTypeIdsFlowProvider = { sharedViewModel.selectedTableTypeIds },
                onPersistSelectedTypeIds    = { idsToSave ->
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
                    availableFilteredTypes
                        .filter {
                            it.id in listOf(
                                MeasurementTypeKey.WEIGHT.id,
                                MeasurementTypeKey.BMI.id,
                                MeasurementTypeKey.BODY_FAT.id,
                                MeasurementTypeKey.WATER.id,
                                MeasurementTypeKey.MUSCLE.id,
                                MeasurementTypeKey.COMMENT.id,
                            ) && it.isEnabled
                        }
                        .map { it.id }
                },
                onSelectionChanged = { newSelectedIds ->
                    selectedColumnIdsFromFilter.clear()
                    selectedColumnIdsFromFilter.addAll(newSelectedIds)
                },
                allowEmptySelection = false,
            )
            HorizontalDivider()
        }

        when {
            tableUiState is SharedViewModel.UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            aggregatedItems.isEmpty() && displayedTypes.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                    Text(noColumnsOrMeasurementsMessage)
                }
            aggregatedItems.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                    Text(noMeasurementsMessage)
                }
            displayedTypes.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                    Text(noColumnsSelectedMessage)
                }
            tableData.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                    Text(noDataForSelectionMessage)
                }
            else -> {
                // Header row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp)
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isInSelectionMode) {
                        val allSelected  = tableData.isNotEmpty() && selectedKeys.size == tableData.size
                        val noneSelected = selectedKeys.isEmpty()
                        val checkboxState = when {
                            allSelected  -> ToggleableState.On
                            noneSelected -> ToggleableState.Off
                            else         -> ToggleableState.Indeterminate
                        }
                        Box(
                            Modifier.fillMaxHeight().padding(horizontal = 6.dp),
                            Alignment.CenterStart,
                        ) {
                            TriStateCheckbox(state = checkboxState, onClick = {
                                when (checkboxState) {
                                    ToggleableState.On -> clearKeys()
                                    else -> {
                                        clearKeys()
                                        addAllKeys(tableData.map { rowKey(it) })
                                    }
                                }
                            })
                        }
                    }
                    TableHeaderCellInternal(
                        text      = dateColumnHeader,
                        modifier  = Modifier
                            .widthIn(min = dateColMin, max = dateColMax)
                            .padding(horizontal = 6.dp)
                            .fillMaxHeight(),
                        alignment = TextAlign.Start,
                    )
                    Row(Modifier.weight(1f).horizontalScroll(horizontalScrollState)) {
                        displayedTypes.forEach { type ->
                            val width = if (type.key == MeasurementTypeKey.COMMENT) commentWidth else colWidth
                            TableHeaderCellInternal(
                                text      = type.getDisplayName(LocalContext.current),
                                modifier  = Modifier
                                    .width(width)
                                    .padding(horizontal = 6.dp)
                                    .fillMaxHeight(),
                                alignment = TextAlign.Center,
                            )
                        }
                    }
                }
                HorizontalDivider()

                LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState) {
                    items(tableData, key = { "${it.measurementId}_${it.timestamp}" }) { rowData ->
                        val key         = rowKey(rowData)
                        val isSelected  = selectedKeys.contains(key)
                        val isHighlighted = !rowData.isAggregated && rowData.measurementId == highlightedItemId

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        isSelected && isInSelectionMode ->
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        isHighlighted ->
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                        rowData.isAggregated ->
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        else ->
                                            MaterialTheme.colorScheme.surface
                                    }
                                )
                                .clickable {
                                    if (isInSelectionMode) {
                                        toggleKey(key)
                                    } else if (rowData.isAggregated) {
                                        navController.navigate(
                                            Routes.tableDrillDown(
                                                startMillis = rowData.periodStartMillis!!,
                                                endMillis   = rowData.periodEndMillis!!,
                                            )
                                        )
                                    } else {
                                        navController.navigate(
                                            Routes.measurementDetail(
                                                rowData.measurementId,
                                                sharedViewModel.selectedUserId.value,
                                            )
                                        )
                                    }
                                }
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isInSelectionMode) {
                                Box(
                                    Modifier.fillMaxHeight().padding(horizontal = 6.dp),
                                    Alignment.CenterStart,
                                ) {
                                    Checkbox(
                                        checked         = isSelected,
                                        onCheckedChange = { toggleKey(key) },
                                    )
                                }
                            }
                            TableDataCellInternal(
                                cellData     = null,
                                fixedText    = rowData.formattedTimestamp,
                                modifier     = Modifier
                                    .widthIn(min = dateColMin, max = dateColMax)
                                    .fillMaxHeight(),
                                alignment    = TextAlign.Start,
                                isDateCell   = true,
                                isAggregated = rowData.isAggregated,
                            )
                            Row(
                                Modifier
                                    .weight(1f)
                                    .horizontalScroll(horizontalScrollState)
                                    .fillMaxHeight(),
                            ) {
                                displayedTypes.forEach { colType ->
                                    val cellData = rowData.values[colType.id]
                                    val width    = if (colType.key == MeasurementTypeKey.COMMENT) commentWidth else colWidth
                                    TableDataCellInternal(
                                        cellData     = cellData,
                                        modifier     = Modifier.width(width).fillMaxHeight(),
                                        alignment    = if (colType.key == MeasurementTypeKey.COMMENT)
                                            TextAlign.Start else TextAlign.End,
                                        isAggregated = rowData.isAggregated,
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

// ---------------------------------------------------------------------------
// Table cell composables
// ---------------------------------------------------------------------------

@Composable
fun TableHeaderCellInternal(
    text: String,
    modifier: Modifier = Modifier,
    alignment: TextAlign = TextAlign.Center,
) {
    Text(
        text       = text,
        style      = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign  = alignment,
        maxLines   = 2,
        overflow   = TextOverflow.Ellipsis,
        modifier   = modifier.padding(vertical = 4.dp).fillMaxHeight(),
    )
}

@Composable
fun TableDataCellInternal(
    cellData: TableCellData?,
    modifier: Modifier = Modifier,
    alignment: TextAlign = TextAlign.Start,
    fixedText: String? = null,
    isDateCell: Boolean = false,
    isAggregated: Boolean = false,
) {
    val symbolColWidth = 18.dp

    Box(
        modifier         = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = if (isDateCell) Alignment.CenterStart else Alignment.TopEnd,
    ) {
        if (isDateCell && fixedText != null) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                if (isAggregated) {
                    Icon(
                        imageVector        = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier           = Modifier.size(16.dp),
                        tint               = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    text       = fixedText,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isAggregated) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign  = alignment,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
        } else if (cellData != null) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (isAggregated && cellData.rawCount > 1) {
                        Text(
                            text       = "⌀",
                            style      = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isAggregated) FontWeight.SemiBold else FontWeight.Normal,
                            modifier   = Modifier.alignByBaseline().padding(end = 2.dp),
                        )
                    }
                    Text(
                        text       = cellData.displayValue,
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isAggregated) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign  = alignment,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.alignByBaseline(),
                    )
                    if (cellData.evalState != null) {
                        val symbol = when {
                            cellData.flagged -> "!"
                            cellData.evalState == EvaluationState.HIGH -> "▲"
                            cellData.evalState == EvaluationState.LOW  -> "▼"
                            else -> "●"
                        }
                        val color = if (cellData.flagged) MaterialTheme.colorScheme.error
                        else cellData.evalState.toColor()
                        Box(Modifier.width(symbolColWidth).alignByBaseline(), Alignment.CenterEnd) {
                            Text(text = symbol, color = color, style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        Spacer(Modifier.width(symbolColWidth))
                    }
                }
                if (!cellData.diffDisplay.isNullOrEmpty() && cellData.trend != Trend.NOT_APPLICABLE) {
                    Spacer(Modifier.height(1.dp))
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Row(
                            modifier              = Modifier.weight(1f),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            val trendIcon = when (cellData.trend) {
                                Trend.UP   -> Icons.Filled.ArrowUpward
                                Trend.DOWN -> Icons.Filled.ArrowDownward
                                else       -> null
                            }
                            val diffColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            if (trendIcon != null) {
                                Icon(
                                    imageVector        = trendIcon,
                                    contentDescription = null,
                                    tint               = diffColor,
                                    modifier           = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                            }
                            Text(
                                text      = cellData.diffDisplay!!,
                                style     = MaterialTheme.typography.bodySmall,
                                color     = diffColor,
                                textAlign = TextAlign.End,
                            )
                        }
                        Spacer(Modifier.width(symbolColWidth))
                    }
                }
            }
        } else {
            Text(text = "-", style = MaterialTheme.typography.bodyLarge, textAlign = alignment)
        }
    }
}