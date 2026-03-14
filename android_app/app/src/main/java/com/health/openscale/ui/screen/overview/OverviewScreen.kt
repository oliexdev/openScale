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
package com.health.openscale.ui.screen.overview

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.Trend
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.AggregatedMeasurement
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.model.ValueWithDifference
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.components.LinearGauge
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.components.MeasurementChart
import com.health.openscale.ui.screen.components.UserGoalChip
import com.health.openscale.ui.screen.components.provideFilterTopBarAction
import com.health.openscale.ui.screen.components.rememberAddMeasurementActionButton
import com.health.openscale.ui.screen.components.rememberBluetoothActionButton
import com.health.openscale.ui.screen.components.rememberResolvedAggregationLevel
import com.health.openscale.ui.screen.dialog.DeleteConfirmationDialog
import com.health.openscale.ui.screen.dialog.UserGoalDialog
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs


// ---------------------------------------------------------------------------
// OverviewScreen
// ---------------------------------------------------------------------------

@Composable
fun OverviewScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
    // When non-null the screen acts as a drill-down: shows raw measurements for this
    // period only — no chart, no aggregation toggle, no goals section.
    drillDownStartMillis: Long? = null,
    drillDownEndMillis: Long? = null,
) {
    val isDrillDown = drillDownStartMillis != null && drillDownEndMillis != null

    val selectedUserId by sharedViewModel.selectedUserId.collectAsState()
    val context = LocalContext.current
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()
    var highlightedMeasurementId by rememberSaveable { mutableStateOf<Int?>(null) }

    // ── Splitter ──────────────────────────────────────────────────────────────
    val splitterWeight by remember(SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT, sharedViewModel) {
        sharedViewModel.observeSplitterWeight(SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT, 0.3f)
    }.collectAsState(initial = 0.3f)
    var localSplitterWeight by remember { mutableStateOf(splitterWeight) }
    LaunchedEffect(splitterWeight) { localSplitterWeight = splitterWeight }

    // ── Aggregation ───────────────────────────────────────────────────────────
    val activeAggregationLevel by rememberResolvedAggregationLevel(
        screenContextName = SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT,
        sharedViewModel   = sharedViewModel,
    )
    val isAggregated = !isDrillDown && activeAggregationLevel != AggregationLevel.NONE

    // ── Data ──────────────────────────────────────────────────────────────────
    // Normal mode: screenFlow — cached, reacts to user/time-range/aggregation changes.
    // Drill-down mode: drillDownFlow — uncached, fixed window, always AggregationLevel.NONE.
    val overviewState by if (isDrillDown) {
        sharedViewModel.drillDownFlow(drillDownStartMillis!!, drillDownEndMillis!!)
            .collectAsStateWithLifecycle(initialValue = SharedViewModel.UiState.Loading)
    } else {
        sharedViewModel.screenFlow(SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT)
            .collectAsStateWithLifecycle(initialValue = SharedViewModel.UiState.Loading)
    }

    // Flat list of AggregatedMeasurement for the current state
    val aggregatedItems: List<AggregatedMeasurement> = remember(overviewState) {
        when (val s = overviewState) {
            is SharedViewModel.UiState.Success -> s.data
            else -> emptyList()
        }
    }

    // ── Top-bar actions ───────────────────────────────────────────────────────
    val bluetoothAction      = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    val addMeasurementAction = rememberAddMeasurementActionButton(sharedViewModel, navController)
    val timeFilterAction     = if (isDrillDown) null else provideFilterTopBarAction(
        sharedViewModel   = sharedViewModel,
        screenContextName = SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT,
    )

    // ── Goals + UI helpers ────────────────────────────────────────────────────
    val allMeasurementTypes  by sharedViewModel.measurementTypes.collectAsState()
    val goalDialogContextData by sharedViewModel.userGoalDialogContext.collectAsState()
    val userGoals by if (selectedUserId != null && selectedUserId != 0) {
        sharedViewModel.getAllGoalsForUser(selectedUserId!!).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<UserGoals>()) }
    }
    val isGoalsSectionExpanded by sharedViewModel.myGoalsExpandedOverview.collectAsState(initial = true)
    val userEvalContext        by sharedViewModel.userEvaluationContext.collectAsState()
    val currentSelectedUser   by sharedViewModel.selectedUser.collectAsState()

    var currentSelectedMeasurementId  by rememberSaveable { mutableStateOf<Int?>(null) }
    var currentSelectedAggregatedTs   by rememberSaveable { mutableStateOf<Long?>(null) }

    // O(1) lookup maps — rebuilt only when aggregatedItems changes, not on every recomposition.
    val aggregatedItemById: Map<Int, AggregatedMeasurement> = remember(aggregatedItems) {
        aggregatedItems.associateBy { it.enriched.measurementWithValues.measurement.id }
    }
    val aggregatedItemByTs: Map<Long, AggregatedMeasurement> = remember(aggregatedItems) {
        aggregatedItems.associateBy { it.enriched.measurementWithValues.measurement.timestamp }
    }
    // MeasurementType lookup map — avoids O(t) linear scan per goal chip.
    val typeById: Map<Int, MeasurementType> = remember(allMeasurementTypes) {
        allMeasurementTypes.associateBy { it.id }
    }

    // Goal reference: the MeasurementWithValues the goal chips compare against.
    // In aggregated mode: the selected period's averaged entry.
    // In raw mode: the selected (or topmost) raw entry.
    val goalReferenceMeasurement: MeasurementWithValues? = remember(
        currentSelectedMeasurementId,
        currentSelectedAggregatedTs,
        aggregatedItemById,
        aggregatedItemByTs,
        isAggregated,
    ) {
        val fallback = aggregatedItems.firstOrNull()?.enriched?.measurementWithValues
        if (isAggregated) {
            val ts = currentSelectedAggregatedTs
            if (ts != null)
                aggregatedItemByTs[ts]?.enriched?.measurementWithValues ?: fallback
            else
                fallback
        } else {
            if (currentSelectedMeasurementId != null)
                aggregatedItemById[currentSelectedMeasurementId]?.enriched?.measurementWithValues ?: fallback
            else
                fallback
        }
    }

    // ── Delete dialog ─────────────────────────────────────────────────────────
    var measurementToDelete by remember { mutableStateOf<AggregatedMeasurement?>(null) }
    measurementToDelete?.let { aggItem ->
        val enrichedItem  = aggItem.enriched
        val weightValue   = enrichedItem.valuesWithTrend.find { it.currentValue.type.key == MeasurementTypeKey.WEIGHT }
        val weightString  = weightValue?.currentValue?.let {
            LocaleUtils.formatValueForDisplay(it.value.floatValue.toString(), it.type.unit)
        } ?: ""
        val formattedDate = remember(enrichedItem.measurementWithValues.measurement.timestamp) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(Date(enrichedItem.measurementWithValues.measurement.timestamp))
        }
        DeleteConfirmationDialog(
            onDismissRequest = { measurementToDelete = null },
            onConfirm        = {
                scope.launch {
                    sharedViewModel.deleteMeasurement(enrichedItem.measurementWithValues.measurement)
                }
                measurementToDelete = null
            },
            title = stringResource(R.string.dialog_title_delete_item),
            text  = stringResource(R.string.dialog_message_delete_item, formattedDate, weightString),
        )
    }

    // ── Top bar ───────────────────────────────────────────────────────────────
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, selectedUserId, bluetoothAction, timeFilterAction, isDrillDown, aggregatedItems) {
        fun updateTopBar() {
            if (isDrillDown) {
                val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                val title = "${fmt.format(Date(drillDownStartMillis!!))} – ${fmt.format(Date(drillDownEndMillis!! - 1))}"
                sharedViewModel.setTopBarTitle(title)
                sharedViewModel.setTopBarActions(emptyList())
            } else {
                sharedViewModel.setTopBarTitle(context.getString(R.string.route_title_overview))
                val actions = mutableListOf<TopBarAction>()
                actions.add(bluetoothAction)
                actions.add(addMeasurementAction)
                timeFilterAction?.let { actions.add(it) }
                sharedViewModel.setTopBarActions(actions)
            }
        }
        updateTopBar()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateTopBar()
                val target = sharedViewModel.lastDrillDownPeriodStart.value ?: return@LifecycleEventObserver
                val idx = aggregatedItems.indexOfFirst { it.periodStartMillis == target }
                if (idx >= 0) {
                    scope.launch {
                        listState.scrollToItem(idx)
                        sharedViewModel.setLastDrillDownPeriodStart(null)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Main UI ───────────────────────────────────────────────────────────────
    when {
        overviewState is SharedViewModel.UiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        selectedUserId == null && overviewState !is SharedViewModel.UiState.Loading -> {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                NoUserSelectedCard(navController = navController)
            }
        }

        selectedUserId != null && overviewState !is SharedViewModel.UiState.Loading -> {
            Column(modifier = Modifier.fillMaxSize()) {
                when (val state = overviewState) {
                    is SharedViewModel.UiState.Success -> {

                        if (aggregatedItems.isEmpty()) {
                            Box(
                                Modifier.weight(1f).fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                NoMeasurementsCard(
                                    navController  = navController,
                                    selectedUserId = selectedUserId,
                                )
                            }
                        } else {
                            // Scroll to top when the newest item changes
                            val topId = aggregatedItems.firstOrNull()
                                ?.enriched?.measurementWithValues?.measurement?.id
                            LaunchedEffect(topId, aggregatedItems.size) {
                                if (topId != null && !listState.isScrollInProgress) {
                                    delay(60)
                                    listState.smartScrollTo(0)
                                }
                            }

                            // ── Chart + divider + goals (hidden in drill-down) ────────
                            if (!isDrillDown) {
                                Box(modifier = Modifier.weight(localSplitterWeight)) {
                                    MeasurementChart(
                                        sharedViewModel   = sharedViewModel,
                                        screenContextName = SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT,
                                        showFilterControls = true,
                                        modifier          = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        showYAxis         = false,
                                        onPointSelected   = { selectedTs ->
                                            if (isAggregated) {
                                                // AggregatedMeasurement carries pre-computed period bounds —
                                                // no need to call periodBoundsFor().
                                                val idx = aggregatedItems.indexOfFirst { item ->
                                                    selectedTs in item.periodStartMillis until item.periodEndMillis
                                                }
                                                if (idx >= 0) {
                                                    val itemTs = aggregatedItems[idx]
                                                        .enriched.measurementWithValues.measurement.timestamp
                                                    scope.launch {
                                                        listState.smartScrollTo(idx)
                                                        highlightedMeasurementId =
                                                            aggregatedItems[idx].enriched.measurementWithValues.measurement.id
                                                        currentSelectedAggregatedTs = itemTs
                                                        delay(600)
                                                        highlightedMeasurementId = null
                                                    }
                                                }
                                            } else {
                                                val listForFind = aggregatedItems.map {
                                                    it.enriched.measurementWithValues
                                                }
                                                sharedViewModel
                                                    .findClosestMeasurement(selectedTs, listForFind)
                                                    ?.let { (targetIndex, mwv) ->
                                                        val targetId = mwv.measurement.id
                                                        scope.launch {
                                                            listState.smartScrollTo(targetIndex)
                                                            highlightedMeasurementId          = targetId
                                                            currentSelectedMeasurementId      = targetId
                                                            delay(600)
                                                            if (highlightedMeasurementId == targetId)
                                                                highlightedMeasurementId = null
                                                        }
                                                    }
                                            }
                                        },
                                    )
                                }

                                // Draggable divider
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDrag    = { change, dragAmount ->
                                                    change.consume()
                                                    localSplitterWeight =
                                                        (localSplitterWeight + dragAmount.y / 2000f)
                                                            .coerceIn(0.01f, 0.8f)
                                                },
                                                onDragEnd = {
                                                    scope.launch {
                                                        sharedViewModel.setSplitterWeight(
                                                            SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT,
                                                            localSplitterWeight,
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    )
                                }

                                // Goals section
                                if (userGoals.isNotEmpty()) {
                                    Column {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    scope.launch {
                                                        sharedViewModel.setMyGoalsExpandedOverview(
                                                            !isGoalsSectionExpanded
                                                        )
                                                    }
                                                }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier         = Modifier.weight(1f),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text  = stringResource(R.string.my_goals_label),
                                                        style = MaterialTheme.typography.titleMedium,
                                                    )
                                                    if (!isGoalsSectionExpanded && userGoals.isNotEmpty()) {
                                                        Text(
                                                            text  = " (${userGoals.size})",
                                                            style = MaterialTheme.typography.titleMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            }
                                            Icon(
                                                imageVector        = if (isGoalsSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (isGoalsSectionExpanded)
                                                    stringResource(R.string.action_show_less_desc)
                                                else
                                                    stringResource(R.string.action_show_more_desc),
                                            )
                                        }

                                        AnimatedVisibility(visible = isGoalsSectionExpanded) {
                                            Column {
                                                LazyRow(
                                                    modifier            = Modifier.fillMaxWidth(),
                                                    contentPadding      = PaddingValues(horizontal = 16.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    items(
                                                        userGoals,
                                                        key = { goal -> "${goal.userId}_${goal.measurementTypeId}" },
                                                    ) { goal ->
                                                        if (goal.userId == currentSelectedUser!!.id) {
                                                            val measurementType = typeById[goal.measurementTypeId]
                                                            if (measurementType != null) {
                                                                UserGoalChip(
                                                                    userGoal             = goal,
                                                                    measurementType      = measurementType,
                                                                    referenceMeasurement = goalReferenceMeasurement,
                                                                    onClick              = {
                                                                        if (currentSelectedUser!!.id != 0 &&
                                                                            goal.userId == currentSelectedUser!!.id
                                                                        ) {
                                                                            sharedViewModel.showUserGoalDialogWithContext(
                                                                                type         = measurementType,
                                                                                existingGoal = goal,
                                                                            )
                                                                        }
                                                                    },
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                if (goalReferenceMeasurement?.measurement?.timestamp != null) {
                                                    Row(
                                                        modifier              = Modifier
                                                            .fillMaxWidth()
                                                            .padding(top = 4.dp, bottom = 4.dp, end = 16.dp),
                                                        horizontalArrangement = Arrangement.End,
                                                        verticalAlignment     = Alignment.CenterVertically,
                                                    ) {
                                                        val shortDateTimeFormatter = remember {
                                                            DateFormat.getDateTimeInstance(
                                                                DateFormat.MEDIUM,
                                                                DateFormat.SHORT,
                                                                Locale.getDefault(),
                                                            )
                                                        }
                                                        Icon(
                                                            imageVector        = Icons.Outlined.Link,
                                                            contentDescription = stringResource(R.string.my_goals_label),
                                                            modifier           = Modifier.size(14.dp),
                                                            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            text  = shortDateTimeFormatter.format(
                                                                Date(goalReferenceMeasurement.measurement.timestamp)
                                                            ),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            } // end !isDrillDown chart+goals block

                            // ── Main list ─────────────────────────────────────────────
                            LazyColumn(
                                state                 = listState,
                                modifier              = Modifier
                                    .weight(if (isDrillDown) 1f else 1f - localSplitterWeight)
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement   = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(
                                    items = aggregatedItems,
                                    key   = { _, item ->
                                        if (isAggregated)
                                            item.enriched.measurementWithValues.measurement.timestamp
                                        else
                                            item.enriched.measurementWithValues.measurement.id
                                    },
                                ) { _, aggItem ->
                                    val enrichedItem = aggItem.enriched
                                    val ts           = enrichedItem.measurementWithValues.measurement.timestamp

                                    if (isAggregated) {
                                        // aggregatedFromCount and period bounds come directly from
                                        // AggregatedMeasurement — no recomputation needed.
                                        val periodStart = aggItem.periodStartMillis
                                        val periodEnd   = aggItem.periodEndMillis
                                        MeasurementCard(
                                            sharedViewModel            = sharedViewModel,
                                            measurementWithValues      = enrichedItem.measurementWithValues,
                                            processedValuesForDisplay  = enrichedItem.valuesWithTrend,
                                            userEvaluationContext      = userEvalContext,
                                            onClick = {
                                                currentSelectedAggregatedTs = ts
                                                sharedViewModel.setLastDrillDownPeriodStart(periodStart)
                                                navController.navigate(Routes.overviewDrillDown(periodStart, periodEnd))
                                            },
                                            onEdit                     = {
                                                currentSelectedAggregatedTs = ts
                                                navController.navigate(
                                                    Routes.overviewDrillDown(periodStart, periodEnd)
                                                )
                                            },
                                            onDelete                   = null,
                                            isAggregated               = true,
                                            rawCount                   = aggItem.aggregatedFromCount,
                                            aggregatedPeriodLabel      = activeAggregationLevel.periodLabel(
                                                timestamp            = ts,
                                                calendarWeekAbbrev  = context.getString(R.string.calendar_week_abbrev),
                                            ),
                                        )
                                    } else {
                                        MeasurementCard(
                                            sharedViewModel           = sharedViewModel,
                                            measurementWithValues     = enrichedItem.measurementWithValues,
                                            processedValuesForDisplay = enrichedItem.valuesWithTrend,
                                            userEvaluationContext     = userEvalContext,
                                            onClick                   = {
                                                currentSelectedMeasurementId =
                                                    enrichedItem.measurementWithValues.measurement.id
                                            },
                                            onEdit                    = {
                                                navController.navigate(
                                                    Routes.measurementDetail(
                                                        enrichedItem.measurementWithValues.measurement.id,
                                                        selectedUserId!!,
                                                    )
                                                )
                                            },
                                            onDelete                  = { measurementToDelete = aggItem },
                                            isHighlighted             = (highlightedMeasurementId ==
                                                    enrichedItem.measurementWithValues.measurement.id),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is SharedViewModel.UiState.Error -> {
                        Box(
                            modifier         = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(state.message ?: stringResource(R.string.error_loading_data))
                        }
                    }

                    SharedViewModel.UiState.Loading -> {
                        Box(
                            modifier         = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
            }
        }

        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Unexpected state")
            }
        }
    }

    // ── Goal dialog ───────────────────────────────────────────────────────────
    if (goalDialogContextData.showDialog) {
        val dialogContext        = goalDialogContextData
        val userIdForDialog      = currentSelectedUser?.id
        if (dialogContext.typeForDialog == null || userIdForDialog == null || userIdForDialog == 0) {
            LaunchedEffect(goalDialogContextData.showDialog) {
                sharedViewModel.dismissUserGoalDialogWithContext()
            }
        } else {
            UserGoalDialog(
                navController         = navController,
                existingUserGoal      = dialogContext.existingGoalForDialog,
                allMeasurementTypes   = allMeasurementTypes,
                allGoalsOfCurrentUser = userGoals,
                onDismiss             = { sharedViewModel.dismissUserGoalDialogWithContext() },
                onConfirm             = { measurementTypeId, goalValueString, goalTargetDate ->
                    val finalGoalValueFloat = goalValueString.replace(',', '.').toFloatOrNull()
                    when {
                        finalGoalValueFloat != null -> {
                            val goalToProcess = UserGoals(
                                userId            = userIdForDialog,
                                measurementTypeId = measurementTypeId,
                                goalValue         = finalGoalValueFloat,
                                goalTargetDate    = goalTargetDate,
                            )
                            if (dialogContext.existingGoalForDialog != null)
                                sharedViewModel.updateUserGoal(goalToProcess)
                            else
                                sharedViewModel.insertUserGoal(goalToProcess)
                            sharedViewModel.dismissUserGoalDialogWithContext()
                        }
                        goalValueString.isBlank() && dialogContext.existingGoalForDialog != null -> {
                            return@UserGoalDialog
                        }
                        goalValueString.isBlank() -> {
                            Toast.makeText(context, R.string.toast_goal_value_cannot_be_empty, Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            val typeName = allMeasurementTypes
                                .find { it.id == measurementTypeId }
                                ?.getDisplayName(context) ?: "Value"
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_invalid_number_format_short, typeName),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                onDelete = { _, measurementTypeIdToDelete ->
                    sharedViewModel.deleteUserGoal(userIdForDialog, measurementTypeIdToDelete)
                    Toast.makeText(context, R.string.toast_goal_deleted, Toast.LENGTH_SHORT).show()
                    sharedViewModel.dismissUserGoalDialogWithContext()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

suspend fun LazyListState.smartScrollTo(index: Int) {
    val dist = abs(firstVisibleItemIndex - index)
    if (dist > 20) scrollToItem(index) else animateScrollToItem(index)
}

// ---------------------------------------------------------------------------
// MeasurementCard
// ---------------------------------------------------------------------------

@Composable
fun MeasurementCard(
    sharedViewModel: SharedViewModel,
    measurementWithValues: MeasurementWithValues,
    processedValuesForDisplay: List<ValueWithDifference>,
    userEvaluationContext: UserEvaluationContext?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    isHighlighted: Boolean = false,
    isAggregated: Boolean = false,
    rawCount: Int = 1,
    aggregatedPeriodLabel: String = "",
) {
    val surfaceAtElevation = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val isLightSurface     = surfaceAtElevation.luminance() > 0.5f
    val tintAlpha          = if (isLightSurface) 0.16f else 0.12f
    val highlightColor     = MaterialTheme.colorScheme.primary.copy(alpha = tintAlpha)
        .compositeOver(surfaceAtElevation)
    val highlightBorder    = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    val measuredAtMillis   = measurementWithValues.measurement.timestamp
    val expandedTypeIds    = remember { mutableStateMapOf<Int, Boolean>() }

    val headerLabel = if (isAggregated) {
        "$aggregatedPeriodLabel ($rawCount)"
    } else {
        remember(measurementWithValues.measurement.timestamp, Locale.getDefault()) {
            val ts     = measurementWithValues.measurement.timestamp
            val locale = Locale.getDefault()
            "${DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ts))} " +
                    DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(ts))
        }
    }

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val pinnedValues = remember(processedValuesForDisplay) {
        processedValuesForDisplay.filter { it.currentValue.type.isPinned && it.currentValue.type.isEnabled }
    }
    val nonPinnedValues = remember(processedValuesForDisplay) {
        processedValuesForDisplay.filter { !it.currentValue.type.isPinned && it.currentValue.type.isEnabled }
    }
    val allActiveProcessedValues = remember(processedValuesForDisplay) {
        processedValuesForDisplay.filter { it.currentValue.type.isEnabled }
    }

    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border    = if (isHighlighted) highlightBorder else null,
        colors    = if (isHighlighted)
            CardDefaults.cardColors(containerColor = highlightColor)
        else
            CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            ) {
                Text(
                    text     = headerLabel,
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val iconButtonSize  = 36.dp
                val actionIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)

                if (isAggregated) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(iconButtonSize)) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(iconButtonSize)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete_measurement_desc, headerLabel),
                                tint               = actionIconColor,
                            )
                        }
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(iconButtonSize)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit_measurement_desc, headerLabel),
                            tint               = actionIconColor,
                        )
                    }
                    if (nonPinnedValues.isNotEmpty() && pinnedValues.isEmpty()) {
                        IconButton(
                            onClick  = { isExpanded = !isExpanded },
                            modifier = Modifier.size(iconButtonSize),
                        ) {
                            Icon(
                                imageVector        = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(
                                    if (isExpanded) R.string.action_show_less_desc else R.string.action_show_more_desc
                                ),
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(
                    start   = 16.dp,
                    end     = 16.dp,
                    top     = if (pinnedValues.isNotEmpty()) 8.dp else 0.dp,
                    bottom  = 0.dp,
                ),
            ) {
                if (pinnedValues.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        pinnedValues.forEach { valueWithTrend ->
                            MeasurementRowExpandable(
                                sharedViewModel       = sharedViewModel,
                                valueWithTrend        = valueWithTrend,
                                userEvaluationContext = userEvaluationContext,
                                measuredAtMillis      = measuredAtMillis,
                                expandedTypeIds       = expandedTypeIds,
                                valuePrefix           = if (isAggregated && rawCount > 1) "⌀ " else "",
                            )
                        }
                    }
                }
            }

            if (nonPinnedValues.isNotEmpty()) {
                AnimatedVisibility(visible = isExpanded || pinnedValues.isEmpty()) {
                    Column(
                        modifier            = Modifier.padding(start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        nonPinnedValues.forEach { valueWithTrend ->
                            MeasurementRowExpandable(
                                sharedViewModel       = sharedViewModel,
                                valueWithTrend        = valueWithTrend,
                                userEvaluationContext = userEvaluationContext,
                                measuredAtMillis      = measuredAtMillis,
                                expandedTypeIds       = expandedTypeIds,
                                valuePrefix           = if (isAggregated && rawCount > 1) "⌀ " else "",
                            )
                        }
                    }
                }
            }

            if (nonPinnedValues.isNotEmpty() && (pinnedValues.isNotEmpty() || !isExpanded)) {
                if (isExpanded || pinnedValues.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            top    = if (isExpanded && nonPinnedValues.isNotEmpty()) 4.dp
                            else if (pinnedValues.isNotEmpty()) 8.dp else 0.dp,
                            bottom = 0.dp,
                        ),
                    )
                }
                TextButton(
                    onClick   = { isExpanded = !isExpanded },
                    modifier  = Modifier.fillMaxWidth().height(48.dp),
                    shape     = MaterialTheme.shapes.extraSmall,
                ) {
                    Icon(
                        imageVector        = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        tint               = MaterialTheme.colorScheme.secondary,
                        contentDescription = stringResource(
                            if (isExpanded) R.string.action_show_less_desc else R.string.action_show_more_desc
                        ),
                    )
                }
            }

            if (allActiveProcessedValues.isEmpty()) {
                Text(
                    text      = stringResource(R.string.no_active_values_for_measurement),
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier  = Modifier.padding(16.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            if (pinnedValues.isNotEmpty() && nonPinnedValues.isEmpty() && allActiveProcessedValues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MeasurementValueRow + MeasurementRowExpandable (unchanged logic)
// ---------------------------------------------------------------------------

@Composable
fun MeasurementValueRow(
    sharedViewModel: SharedViewModel,
    valueWithTrend: ValueWithDifference,
    userEvaluationContext: UserEvaluationContext?,
    measuredAtMillis: Long,
    valuePrefix: String = "",
) {
    val type          = valueWithTrend.currentValue.type
    val originalValue = valueWithTrend.currentValue.value
    val difference    = valueWithTrend.difference
    val trend         = valueWithTrend.trend
    val unitName      = type.unit.displayName
    val context       = LocalContext.current

    val displayValue = when (type.inputType) {
        InputFieldType.FLOAT -> originalValue.floatValue?.let { LocaleUtils.formatValueForDisplay(it.toString(), type.unit) }
        InputFieldType.INT   -> originalValue.intValue?.let { LocaleUtils.formatValueForDisplay(it.toString(), type.unit) }
        InputFieldType.TEXT  -> originalValue.textValue
        InputFieldType.DATE  -> originalValue.dateValue?.let { DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(it)) }
        InputFieldType.TIME  -> originalValue.dateValue?.let { DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(it)) }
        InputFieldType.USER  -> null
    } ?: "-"

    val iconMeasurementType = remember(type.icon) { type.icon }
    val numeric: Float? = when (type.inputType) {
        InputFieldType.FLOAT -> originalValue.floatValue
        InputFieldType.INT   -> originalValue.intValue?.toFloat()
        else -> null
    }

    val evalResult = remember(valueWithTrend, userEvaluationContext, measuredAtMillis) {
        if (userEvaluationContext != null && numeric != null)
            sharedViewModel.evaluateMeasurement(
                type                  = type,
                value                 = numeric,
                userEvaluationContext = userEvaluationContext,
                measuredAtMillis      = measuredAtMillis,
            )
        else null
    }

    val noAgeBand: Boolean = evalResult?.let { it.lowLimit < 0f || it.highLimit < 0f } ?: false
    val plausible          = sharedViewModel.getPlausiblePercentRange(type.key)
    val outOfPlausibleRange = if (numeric == null) false
    else plausible?.let { numeric < it.start || numeric > it.endInclusive }
        ?: (unitName == "%" && (numeric < 0f || numeric > 100f))

    val flagged    = noAgeBand || outOfPlausibleRange
    val evalState  = evalResult?.state ?: EvaluationState.UNDEFINED
    val evalSymbol = if (flagged) "!" else when (evalState) {
        EvaluationState.LOW     -> "▼"
        EvaluationState.NORMAL  -> "●"
        EvaluationState.HIGH    -> "▲"
        EvaluationState.UNDEFINED -> "●"
    }
    val evalColor = if (flagged) MaterialTheme.colorScheme.error else evalState.toColor()

    Row(
        modifier          = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            RoundMeasurementIcon(icon = iconMeasurementType.resource, backgroundTint = Color(type.color))
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text     = type.getDisplayName(context),
                    style    = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (difference != null && trend != Trend.NOT_APPLICABLE) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val trendIconVector = when (trend) {
                            Trend.UP   -> Icons.Filled.ArrowUpward
                            Trend.DOWN -> Icons.Filled.ArrowDownward
                            else       -> null
                        }
                        val subtle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        if (trendIconVector != null) {
                            Icon(
                                imageVector        = trendIconVector,
                                contentDescription = trend.name,
                                tint               = subtle,
                                modifier           = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text  = when (type.inputType) {
                                InputFieldType.FLOAT, InputFieldType.INT ->
                                    LocaleUtils.formatValueForDisplay(
                                        value       = difference.toString(),
                                        unit        = type.unit,
                                        includeSign = (trend != Trend.NONE),
                                    )
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = subtle,
                        )
                    }
                } else if (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT) {
                    Spacer(modifier = Modifier.height((MaterialTheme.typography.bodySmall.fontSize.value + 2).dp))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(start = 8.dp),
        ) {
            if (valuePrefix.isNotEmpty()) {
                Text(
                    text       = valuePrefix,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Text(
                text       = displayValue,
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = if (valuePrefix.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                textAlign  = TextAlign.End,
            )
            Spacer(Modifier.width(6.dp))
            Text(text = evalSymbol, color = evalColor, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun EvaluationErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Filled.Error,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text  = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun MeasurementRowExpandable(
    sharedViewModel: SharedViewModel,
    valueWithTrend: ValueWithDifference,
    userEvaluationContext: UserEvaluationContext?,
    measuredAtMillis: Long,
    expandedTypeIds: MutableMap<Int, Boolean>,
    modifier: Modifier = Modifier,
    gaugeHeightDp: Dp = 80.dp,
    valuePrefix: String = "",
) {
    val type    = valueWithTrend.currentValue.type
    val numeric: Float? = when (type.inputType) {
        InputFieldType.FLOAT -> valueWithTrend.currentValue.value.floatValue
        InputFieldType.INT   -> valueWithTrend.currentValue.value.intValue?.toFloat()
        else -> null
    }

    val evalResult = remember(valueWithTrend, userEvaluationContext, measuredAtMillis) {
        if (userEvaluationContext == null || numeric == null) null
        else sharedViewModel.evaluateMeasurement(
            type                  = type,
            value                 = numeric,
            userEvaluationContext = userEvaluationContext,
            measuredAtMillis      = measuredAtMillis,
        )
    }

    val noAgeBand           = evalResult?.let { it.lowLimit < 0f || it.highLimit < 0f } ?: false
    val unitName            = type.unit.displayName
    val plausible           = sharedViewModel.getPlausiblePercentRange(type.key)
    val outOfPlausibleRange = if (numeric == null) false
    else plausible?.let { numeric < it.start || numeric > it.endInclusive }
        ?: (unitName == "%" && (numeric < 0f || numeric > 100f))

    val canExpand = (evalResult != null && !noAgeBand) || noAgeBand || outOfPlausibleRange

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = canExpand) {
                    val cur = expandedTypeIds[type.id] ?: false
                    expandedTypeIds[type.id] = !cur
                },
        ) {
            MeasurementValueRow(
                sharedViewModel       = sharedViewModel,
                valueWithTrend        = valueWithTrend,
                userEvaluationContext = userEvaluationContext,
                measuredAtMillis      = measuredAtMillis,
                valuePrefix           = valuePrefix,
            )
        }

        AnimatedVisibility(visible = canExpand && (expandedTypeIds[type.id] == true)) {
            when {
                noAgeBand -> EvaluationErrorBanner(
                    message = stringResource(R.string.eval_no_age_band)
                )
                outOfPlausibleRange -> {
                    val p = sharedViewModel.getPlausiblePercentRange(type.key) ?: (0f..100f)
                    EvaluationErrorBanner(
                        message = stringResource(
                            R.string.eval_out_of_plausible_range_percent,
                            p.start,
                            p.endInclusive,
                        )
                    )
                }
                evalResult != null -> {
                    val (displayValue, displayLow, displayHigh) = remember(evalResult, type.unit) {
                        val targetUnit = type.unit
                        val baseUnit   = when (type.key) {
                            MeasurementTypeKey.WEIGHT, MeasurementTypeKey.LBM -> UnitType.KG
                            MeasurementTypeKey.WAIST                          -> UnitType.CM
                            else                                               -> UnitType.PERCENT
                        }
                        if (baseUnit != targetUnit) {
                            Triple(
                                ConverterUtils.convertFloatValueUnit(evalResult.value, baseUnit, targetUnit),
                                if (evalResult.lowLimit >= 0f)
                                    ConverterUtils.convertFloatValueUnit(evalResult.lowLimit, baseUnit, targetUnit)
                                else null,
                                ConverterUtils.convertFloatValueUnit(evalResult.highLimit, baseUnit, targetUnit),
                            )
                        } else {
                            Triple(
                                evalResult.value,
                                if (evalResult.lowLimit < 0f) null else evalResult.lowLimit,
                                evalResult.highLimit,
                            )
                        }
                    }
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)) {
                        LinearGauge(
                            value         = displayValue,
                            lowLimit      = displayLow,
                            highLimit     = displayHigh,
                            modifier      = Modifier.fillMaxWidth().height(gaugeHeightDp),
                            labelProvider = { value ->
                                LocaleUtils.formatValueForDisplay(value.toString(), type.unit)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state cards
// ---------------------------------------------------------------------------

@Composable
fun NoUserSelectedCard(navController: NavController) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier  = Modifier.fillMaxWidth(0.9f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier              = Modifier.padding(horizontal = 24.dp, vertical = 32.dp).fillMaxWidth(),
                horizontalAlignment   = Alignment.CenterHorizontally,
                verticalArrangement   = Arrangement.spacedBy(16.dp),
            ) {
                Icon(imageVector = Icons.Filled.PersonSearch, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text(text = stringResource(R.string.no_user_selected_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(text = stringResource(R.string.no_user_selected_message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { navController.navigate(Routes.userDetail(-1)) }, modifier = Modifier.fillMaxWidth(0.8f)) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.action_add_user))
                }
            }
        }
    }
}

@Composable
fun NoMeasurementsCard(navController: NavController, selectedUserId: Int?) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier  = Modifier.fillMaxWidth(0.9f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier            = Modifier.padding(horizontal = 24.dp, vertical = 32.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(imageVector = Icons.Filled.Assessment, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(text = stringResource(R.string.no_measurements_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(text = stringResource(R.string.no_measurements_message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    onClick  = { if (selectedUserId != null) navController.navigate(Routes.measurementDetail(measurementId = null, userId = selectedUserId)) },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    enabled  = selectedUserId != null,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.action_add_measurement))
                }
            }
        }
    }
}