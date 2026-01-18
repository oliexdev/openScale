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

import android.text.Layout
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import com.health.openscale.ui.theme.White
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.dashed
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.fixed
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.common.shape.markerCorneredShape
import com.patrykandpatrick.vico.compose.common.shape.rounded
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.LayeredComponent
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import kotlin.collections.firstOrNull
import kotlin.math.ceil
import kotlin.math.floor

private const val TIME_RANGE_SUFFIX = "_time_range"
private const val CUSTOM_START_DATE_MILLIS_SUFFIX = "_custom_start_date_millis"
private const val CUSTOM_END_DATE_MILLIS_SUFFIX = "_custom_end_date_millis"
private const val SELECTED_TYPES_SUFFIX = "_selected_types"
private const val SHOW_TYPE_FILTER_ROW_SUFFIX = "_show_type_filter_row"

/**
 * Represents a single, plottable data point for the chart,
 * holding both the application's domain value (LocalDate) and
 * Vico's required numeric value (Float).
 */
private data class ChartPoint(
    val date: LocalDate, // The original date from our application domain.
    val x: Float,        // The numeric representation for Vico's X-axis (e.g., epoch day).
    val y: Float         // The measurement value for the Y-axis.
)

/**
 * Represents a full data series for one line in the chart, including its metadata.
 */
private data class ChartSeries(
    val isProjected: Boolean,
    val type: MeasurementType,   // Metadata like name, color, unit.
    val points: List<ChartPoint> // The list of data points for this line.
)

/**
 * A Composable function that displays a line chart for visualizing measurement data over time.
 * It allows filtering by time range and measurement types.
 *
 * @param modifier Modifier for this composable.
 * @param sharedViewModel The [SharedViewModel] providing access to data and settings.
 * @param screenContextName A unique name for the screen or context where this chart is used.
 *                          This is used to persist filter settings uniquely for this context.
 * @param showFilterControls If true, filter controls (like time range and type selection)
 *                           might be displayed directly or through a top bar action.
 * @param showFilterTitle If true, a title indicating the current time range filter and data count is shown.
 * @param showYAxis If true, the Y-axis (vertical axis showing values) is displayed.
 * @param targetMeasurementTypeId If non-null, the chart will only display data for this specific
 *                                measurement type, and type selection filters will be hidden.
 *                                This is useful for focused views, like a detail screen for one measurement type.
 */
@Composable
fun MeasurementChart(
    modifier: Modifier = Modifier,
    sharedViewModel: SharedViewModel,
    screenContextName: String,
    showFilterControls: Boolean,
    showPeriodChart: Boolean = false,
    showFilterTitle: Boolean = false,
    showYAxis: Boolean = true,
    targetMeasurementTypeId: Int? = null,
    onPointSelected: (timestamp: Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val showTypeFilterRowSetting by rememberContextualBooleanSetting(
        screenContextName = screenContextName,
        settingSuffix = SHOW_TYPE_FILTER_ROW_SUFFIX,
        observeBoolean = { key, default -> sharedViewModel.observeSetting(key, default) },
        defaultValue = showFilterControls
    )

    // The measurement type filter row is only shown if not targeting a specific type.
    val effectiveShowTypeFilterRow = if (targetMeasurementTypeId != null) false else showTypeFilterRowSetting

    val allAvailableMeasurementTypes by sharedViewModel.measurementTypes.collectAsState()
    val defaultSelectedTypesValue = remember(targetMeasurementTypeId, allAvailableMeasurementTypes) {
        if (targetMeasurementTypeId != null) {
            setOf(targetMeasurementTypeId.toString()) // If a specific type is targeted, that's the default.
        } else {
            setOf(
                MeasurementTypeKey.WEIGHT.id.toString(),
                MeasurementTypeKey.BMI.id.toString(),
                MeasurementTypeKey.BODY_FAT.id.toString(),
                MeasurementTypeKey.WATER.id.toString(),
                MeasurementTypeKey.MUSCLE.id.toString(),
                MeasurementTypeKey.COMMENT.id.toString()
            )
        }
    }

    val splitterWeight by remember(SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT, sharedViewModel) {
        sharedViewModel.observeSplitterWeight(SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT, 0.25f)
    }.collectAsState(initial = 0.25f)

    var localSplitterWeight by remember { mutableStateOf(splitterWeight) }

    LaunchedEffect(splitterWeight) {
        localSplitterWeight = splitterWeight
    }

    val showDataPointsSetting by sharedViewModel
        .showChartDataPoints
        .collectAsStateWithLifecycle(initialValue = true)

    val showGoalLinesSetting by sharedViewModel
        .showChartGoalLines
        .collectAsStateWithLifecycle(initialValue = false)

    val timeRangeState by rememberResolvedTimeRangeState(
        screenContextName = screenContextName,
        sharedViewModel = sharedViewModel
    )

    val (uiSelectedTimeRange, startTimeMillis, endTimeMillis) = timeRangeState

    val filterTitle = rememberFilterTitle(
        activeFilter = uiSelectedTimeRange,
        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis
    )

    val currentSelectedTypeIdsStrings by rememberContextualSelectedTypeIds(
        screenContextName = screenContextName,
        observeStringSet = { key, default -> sharedViewModel.observeSetting(key, default) },
        defaultSelectedTypeIds = defaultSelectedTypesValue
    )
    val currentSelectedTypeIntIds: Set<Int> = remember(currentSelectedTypeIdsStrings) {
        currentSelectedTypeIdsStrings.mapNotNull { it.toIntOrNull() }.toSet()
    }

    val typesToSmoothFlow = remember { MutableStateFlow(currentSelectedTypeIntIds) }
    LaunchedEffect(currentSelectedTypeIntIds) { typesToSmoothFlow.value = currentSelectedTypeIntIds }

    val startTimeMillisFlow = remember { MutableStateFlow(startTimeMillis) }
    val endTimeMillisFlow = remember { MutableStateFlow(endTimeMillis) }
    LaunchedEffect(startTimeMillis, endTimeMillis) {
        startTimeMillisFlow.value = startTimeMillis
        endTimeMillisFlow.value = endTimeMillis
    }

    var isChartDataLoading by remember { mutableStateOf(true) }

    val smoothedData by remember(startTimeMillis, endTimeMillis, currentSelectedTypeIntIds) {
        sharedViewModel.smoothedEnrichedMeasurements(
            startTimeMillisFlow = startTimeMillisFlow,
            endTimeMillisFlow = endTimeMillisFlow,
            typesToSmoothAndDisplayFlow = typesToSmoothFlow
        )
    }.collectAsStateWithLifecycle(initialValue = null)

    // Update loading state once data (or an empty list after loading) is received
    LaunchedEffect(smoothedData) {
        if (smoothedData != null) {
            isChartDataLoading = false
        }
    }

    var selectedPeriod by remember { mutableStateOf<PeriodDataPoint?>(null) }

    val lineChartMeasurements = remember(smoothedData, selectedPeriod) {
        if (selectedPeriod == null) smoothedData
        else (smoothedData ?: emptyList()).filter { measurement ->
            val ts = measurement.measurementWithValues.measurement.timestamp
            ts >= selectedPeriod!!.startTimestamp && ts < selectedPeriod!!.endTimestamp
        }
    }

    val measurementsForPeriodChart = remember(smoothedData) {
        (smoothedData ?: emptyList()).map { it.measurementWithValues }
    }

    val periodChartData = rememberPeriodChartData(measurementsForPeriodChart, uiSelectedTimeRange)

    // Determine which measurement types to actually plot based on current selections,
    // target ID, and whether they are enabled and have a plottable input type.
    val lineTypesToActuallyPlot = remember(allAvailableMeasurementTypes, currentSelectedTypeIntIds, targetMeasurementTypeId) {
        allAvailableMeasurementTypes.filter { type ->
            val typeIsSelected = type.id in currentSelectedTypeIntIds
            val typeIsTarget = targetMeasurementTypeId != null && type.id == targetMeasurementTypeId
            val typeIsPlotable = type.isEnabled && (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT)
            // If a target ID is provided, only that type is considered (if plotable).
            // Otherwise, selected types are considered.
            (if (targetMeasurementTypeId != null) typeIsTarget else typeIsSelected) && typeIsPlotable
        }
    }

    val selectedUserId by sharedViewModel.selectedUserId.collectAsStateWithLifecycle()

    val goalsToActuallyPlot by remember(selectedUserId, lineTypesToActuallyPlot) {
        sharedViewModel.getAllGoalsForUser(selectedUserId ?: -1)
            .map { allGoals ->
                allGoals.filter { goal ->
                    lineTypesToActuallyPlot.any { type -> type.id == goal.measurementTypeId }
                }
            }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val goalValuesForScaling = remember(goalsToActuallyPlot, showGoalLinesSetting) {
        if (showGoalLinesSetting) {
            goalsToActuallyPlot.map { it.goalValue.toFloat() }
        } else {
            emptyList()
        }
    }

    Column(modifier = modifier) {
        AnimatedVisibility(visible = effectiveShowTypeFilterRow) {
            MeasurementTypeFilterRow(
                allMeasurementTypesProvider = { allAvailableMeasurementTypes },
                selectedTypeIdsFlowProvider = {
                    sharedViewModel.observeSetting(
                        "${screenContextName}${SELECTED_TYPES_SUFFIX}",
                        defaultSelectedTypesValue
                    )
                },
                onPersistSelectedTypeIds = { newIdsSetToPersist ->
                    scope.launch {
                        sharedViewModel.saveSetting(
                            "${screenContextName}${SELECTED_TYPES_SUFFIX}",
                            newIdsSetToPersist
                        )
                    }
                },
                filterLogic = { allTypes -> // Logic to determine which types are selectable in the filter row
                    allTypes.filter {
                        it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT)
                    }
                },
                onSelectionChanged = { /* selectedIntIds ->  Currently no direct action needed here on selection change */ },
                defaultSelectionLogic = { selectableFilteredTypes ->
                    // Logic to determine which types should be selected by default *within the filter row itself*
                    // when it's first displayed or reset.
                    if (targetMeasurementTypeId != null) {
                        selectableFilteredTypes.find { it.id == targetMeasurementTypeId }
                            ?.let { listOf(it.id) } ?: emptyList()
                    } else {
                        val defaultIdsToTry = listOf(
                            MeasurementTypeKey.WEIGHT.id,
                            MeasurementTypeKey.BODY_FAT.id
                        )

                        val selectedByDefault = defaultIdsToTry.filter { defaultIntId ->
                            selectableFilteredTypes.any { selectableType -> selectableType.id == defaultIntId }
                        }

                        selectedByDefault.ifEmpty { // If default primary types aren't available, pick the first available
                            selectableFilteredTypes.firstOrNull()?.let { listOf(it.id) }
                                ?: emptyList()
                        }
                    }
                }
            )
        }

        if (showPeriodChart && periodChartData.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(localSplitterWeight)
                    .padding(horizontal = 8.dp)
            ) {
                PeriodChart(
                    modifier = Modifier.fillMaxHeight(),
                    data = periodChartData,
                    selectedPeriod = selectedPeriod,
                    onPeriodClick = { clicked ->
                        selectedPeriod = if (selectedPeriod == clicked) null else clicked
                    }
                )
            }

            // draggable divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val deltaY = dragAmount.y
                                val weightDelta = deltaY / 2000f
                                localSplitterWeight =
                                    (localSplitterWeight + weightDelta).coerceIn(0.01f, 0.8f)
                            },
                            onDragEnd = {
                                scope.launch {
                                    sharedViewModel.setSplitterWeight(
                                        SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT,
                                        localSplitterWeight
                                    )
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            }
        }

        var showNoDataMessage by remember { mutableStateOf(false) }
        var noDataMessageText by remember { mutableStateOf("") }

        LaunchedEffect(
            isChartDataLoading, lineTypesToActuallyPlot, lineChartMeasurements,
            effectiveShowTypeFilterRow, targetMeasurementTypeId, allAvailableMeasurementTypes
        ) {
            if (!isChartDataLoading) {
                if (lineTypesToActuallyPlot.isEmpty() && (lineChartMeasurements ?: emptyList()).isEmpty() && !effectiveShowTypeFilterRow && targetMeasurementTypeId == null) {
                    showNoDataMessage = true
                    noDataMessageText = if (allAvailableMeasurementTypes.none { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) })
                        context.getString(R.string.line_chart_no_plottable_types)
                    else context.getString(R.string.line_chart_no_data_to_display)
                } else if (lineTypesToActuallyPlot.isEmpty() && (lineChartMeasurements ?: emptyList()).isEmpty() && targetMeasurementTypeId != null) {
                    showNoDataMessage = true
                    noDataMessageText = context.getString(
                        R.string.line_chart_no_data_for_type_in_range,
                        allAvailableMeasurementTypes.find { it.id == targetMeasurementTypeId }?.getDisplayName(context)
                            ?: context.getString(R.string.line_chart_this_type_placeholder)
                    )
                } else {
                    showNoDataMessage = false
                }
            } else {
                showNoDataMessage = false
            }
        }

        val chartSeries = rememberChartSeries(
            enrichedMeasurements = lineChartMeasurements ?: emptyList(),
            lineTypesToActuallyPlot = lineTypesToActuallyPlot
        )

        when {
            isChartDataLoading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            showNoDataMessage -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = noDataMessageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            chartSeries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val message = if (lineTypesToActuallyPlot.isEmpty() && effectiveShowTypeFilterRow) {
                        if (currentSelectedTypeIntIds.isNotEmpty() && (smoothedData ?: emptyList()).none { m -> m.measurementWithValues.values.any { v -> v.type.id in currentSelectedTypeIntIds } }) {
                            stringResource(R.string.line_chart_no_data_for_selected_types)
                        } else if (currentSelectedTypeIntIds.isEmpty()){
                            stringResource(R.string.line_chart_please_select_types)
                        } else {
                            stringResource(R.string.line_chart_no_data_to_display)
                        }
                    } else if (lineTypesToActuallyPlot.isEmpty()) {
                        if (allAvailableMeasurementTypes.none { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) })
                            stringResource(R.string.line_chart_no_plottable_types)
                        else stringResource(R.string.line_chart_no_data_or_types_to_select)
                    } else if ((smoothedData ?: emptyList()).isEmpty() && (lineChartMeasurements ?: emptyList()).isEmpty() && currentSelectedTypeIntIds.isNotEmpty()){
                        stringResource(R.string.line_chart_no_data_to_display)
                    }
                    else {
                        stringResource(R.string.line_chart_no_data_for_selected_types)
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                val scrollState = rememberVicoScrollState()
                val zoomState = rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.Content)
                val xAxisValueFormatter = rememberXAxisValueFormatter(chartSeries)
                val yAxisValueFormatter = CartesianValueFormatter.decimal()

                val xAxis = if (targetMeasurementTypeId == null) {
                    HorizontalAxis.rememberBottom(valueFormatter = xAxisValueFormatter, guideline = null)
                } else null

                val startYAxis = if (showYAxis) VerticalAxis.rememberStart(valueFormatter = yAxisValueFormatter) else null
                val endYAxis = if (showYAxis) VerticalAxis.rememberEnd(valueFormatter = yAxisValueFormatter) else null

                val modelProducer = rememberChartModelProducer(chartSeries = chartSeries)

                val layers = rememberChartLayers(
                    chartSeries = chartSeries,
                    showDataPointsSetting = showDataPointsSetting,
                    targetMeasurementTypeId = targetMeasurementTypeId,
                    goalValuesForScaling = goalValuesForScaling
                )

                val goalDecorations = if (showGoalLinesSetting) {
                    goalsToActuallyPlot.map { goal ->
                        val typeForGoal = allAvailableMeasurementTypes.find { it.id == goal.measurementTypeId }
                        rememberGoalLine(goal = goal, type = typeForGoal)
                    }
                } else {
                    emptyList()
                }

                val markerVisibilityListener = rememberMarkerVisibilityListener(
                    chartSeries = chartSeries,
                    onPointSelected = onPointSelected
                )

                val chart = rememberCartesianChart(
                    layers = layers.toTypedArray(),
                    startAxis = startYAxis,
                    bottomAxis = xAxis,
                    endAxis = endYAxis,
                    marker = rememberMarker(),
                    markerVisibilityListener = markerVisibilityListener,
                    decorations = goalDecorations
                )

                CartesianChartHost(
                    chart = chart,
                    modelProducer = modelProducer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (showPeriodChart) 1f - localSplitterWeight else 1f),
                    scrollState = scrollState,
                    zoomState = zoomState
                )
            }
        }

        if (showFilterTitle) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 0.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = stringResource(R.string.content_description_time_range_icon),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(
                        R.string.line_chart_filter_title_template,
                        filterTitle,
                        lineChartMeasurements?.size ?: 0
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}


/**
 * A private helper that remembers and processes a list of [EnrichedMeasurement] into a list of [ChartSeries].
 * It creates separate [ChartSeries] for both the actual measurements and any available
 * future projections, marking them with the `isProjected` flag.
 *
 * @param enrichedMeasurements The complete list of enriched measurement data, including potential projections.
 * @param lineTypesToActuallyPlot The specific measurement types to include in the series.
 * @return A memoized list of [ChartSeries] for both real and projected data, or an emptyList.
 */
@Composable
private fun rememberChartSeries(
    enrichedMeasurements: List<EnrichedMeasurement>,
    lineTypesToActuallyPlot: List<MeasurementType>
): List<ChartSeries> {
    return remember(enrichedMeasurements, lineTypesToActuallyPlot) {
        if (enrichedMeasurements.isEmpty() || lineTypesToActuallyPlot.isEmpty()) {
            return@remember emptyList()
        }

        // --- Step 1: Create series for the REAL measurements ---
        val realSeries = lineTypesToActuallyPlot.mapNotNull { type ->
            val dateValuePairs = mutableMapOf<LocalDate, Float>()
            enrichedMeasurements.forEach { em ->
                val mwv = em.measurementWithValues
                mwv.values.find { it.type.id == type.id }?.let { valueWithType ->
                    val yValue = when (type.inputType) {
                        InputFieldType.FLOAT -> valueWithType.value.floatValue
                        InputFieldType.INT -> valueWithType.value.intValue?.toFloat()
                        else -> null
                    }
                    yValue?.let {
                        val date = Instant.ofEpochMilli(mwv.measurement.timestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        dateValuePairs[date] = it
                    }
                }
            }

            if (dateValuePairs.isNotEmpty()) {
                val chartPoints = dateValuePairs.toList()
                    .sortedBy { it.first }
                    .map { (date, value) ->
                        ChartPoint(date = date, x = date.toEpochDay().toFloat(), y = value)
                    }
                ChartSeries(isProjected = false, type = type, points = chartPoints) // isProjected = false
            } else {
                null
            }
        }

        // --- Step 2: Create series for the PROJECTED measurements ---
        val projectionData = enrichedMeasurements.firstOrNull()?.measurementWithValuesProjected ?: emptyList()

        val projectedSeries = if (projectionData.isNotEmpty()) {
            // Group the flat projection list by type
            val groupedProjections = projectionData.groupBy { it.values.first().type.id }

            lineTypesToActuallyPlot.mapNotNull { type ->
                val projectedValuesForType = groupedProjections[type.id]
                if (projectedValuesForType != null && projectedValuesForType.isNotEmpty()) {
                    val chartPoints = projectedValuesForType.map { mwv ->
                        val date = Instant.ofEpochMilli(mwv.measurement.timestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        ChartPoint(
                            date = date,
                            x = date.toEpochDay().toFloat(),
                            y = mwv.values.first().value.floatValue!!
                        )
                    }.sortedBy { it.x }
                    ChartSeries(isProjected = true, type = type, points = chartPoints) // isProjected = true
                } else {
                    null
                }
            }
        } else {
            emptyList()
        }

        // --- Step 3: Combine both lists ---
        realSeries + projectedSeries
    }
}

/**
 * A private helper that creates, remembers, and updates a [CartesianChartModelProducer].
 * It encapsulates the entire logic of transforming the prepared [ChartSeries] data
 * into Vico's chart model, separating real from projected data.
 *
 * @param chartSeries The list of all processed series data to be plotted.
 * @return A memoized and updated [CartesianChartModelProducer].
 */
@Composable
private fun rememberChartModelProducer(
    chartSeries: List<ChartSeries>
): CartesianChartModelProducer {
    // 1. Create and remember the model producer instance.
    val modelProducer = remember { CartesianChartModelProducer() }

    // 2. This LaunchedEffect observes the data and updates the producer.
    //    It runs whenever the chartSeries data changes.
    LaunchedEffect(chartSeries) {
        // --- Separate the series into four distinct groups ---
        val mainSeriesStart = chartSeries.filter { !it.isProjected && !it.type.isOnRightYAxis }
        val mainSeriesEnd = chartSeries.filter { !it.isProjected && it.type.isOnRightYAxis }
        val projectedSeriesStart = chartSeries.filter { it.isProjected && !it.type.isOnRightYAxis }
        val projectedSeriesEnd = chartSeries.filter { it.isProjected && it.type.isOnRightYAxis }

        // 3. Update the Vico model producer in a transaction.
        modelProducer.runTransaction {
            if (chartSeries.isNotEmpty()) {
                // Layer 0: Main (solid) lines on the START axis
                if (mainSeriesStart.isNotEmpty()) {
                    lineSeries {
                        mainSeriesStart.forEach { series ->
                            series(
                                x = series.points.map { it.x },
                                y = series.points.map { it.y },
                            )
                        }
                    }
                }

                // Layer 1: Main (solid) lines on the END axis
                if (mainSeriesEnd.isNotEmpty()) {
                    lineSeries {
                        mainSeriesEnd.forEach { series ->
                            series(
                                x = series.points.map { it.x },
                                y = series.points.map { it.y },
                            )
                        }
                    }
                }

                // Layer 2: Projected (dashed) lines on the START axis
                if (projectedSeriesStart.isNotEmpty()) {
                    lineSeries {
                        projectedSeriesStart.forEach { series ->
                            series(
                                x = series.points.map { it.x },
                                y = series.points.map { it.y },
                            )
                        }
                    }
                }

                // Layer 3: Projected (dashed) lines on the END axis
                if (projectedSeriesEnd.isNotEmpty()) {
                    lineSeries {
                        projectedSeriesEnd.forEach { series ->
                            series(
                                x = series.points.map { it.x },
                                y = series.points.map { it.y },
                            )
                        }
                    }
                }

            } else {
                // Clear all layers if there is no data.
                lineSeries { }
                lineSeries { }
                lineSeries { }
                lineSeries { }
            }
        }
    }
    // 4. Return the producer instance for the ChartHost to use.
    return modelProducer
}


/**
 * A private helper that creates and remembers the layers for drawing the lines on the chart.
 * It creates separate layers for main (solid) and projected (dashed) lines.
 *
 * @param chartSeries The complete list of processed series data to be plotted.
 * @param showDataPointsSetting Whether to display dots on the data points of the main series.
 * @param targetMeasurementTypeId A flag to indicate if the chart is in a focused "statistics" mode.
 * @param goalValuesForScaling A list of goal values to be used for scaling the Y-axis.
 * @return A list of [LineCartesianLayer]s to be rendered by the chart.
 */
@Composable
private fun rememberChartLayers(
    chartSeries: List<ChartSeries>,
    showDataPointsSetting: Boolean,
    targetMeasurementTypeId: Int?,
    goalValuesForScaling: List<Float> = emptyList()
): List<LineCartesianLayer> {
    // 1. Separate series and their colors into four distinct groups.
    val mainSeriesStart = remember(chartSeries) { chartSeries.filter { !it.isProjected && !it.type.isOnRightYAxis } }
    val mainSeriesEnd = remember(chartSeries) { chartSeries.filter { !it.isProjected && it.type.isOnRightYAxis } }
    val projectedSeriesStart = remember(chartSeries) { chartSeries.filter { it.isProjected && !it.type.isOnRightYAxis } }
    val projectedSeriesEnd = remember(chartSeries) { chartSeries.filter { it.isProjected && it.type.isOnRightYAxis } }

    val mainColorsStart = remember(mainSeriesStart) { mainSeriesStart.map { Color(it.type.color) } }
    val mainColorsEnd = remember(mainSeriesEnd) { mainSeriesEnd.map { Color(it.type.color) } }
    val projectedColorsStart = remember(projectedSeriesStart) { projectedSeriesStart.map { Color(it.type.color) } }
    val projectedColorsEnd = remember(projectedSeriesEnd) { projectedSeriesEnd.map { Color(it.type.color) } }

    // 2. Create a shared range provider.
    val rangeProvider = remember(goalValuesForScaling) {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val allMinima = goalValuesForScaling.map { it.toDouble() } + minY
                val effectiveMin = allMinima.minOrNull() ?: minY

                val delta = maxY - minY
                return if (delta == 0.0) effectiveMin - 1.0 else floor(effectiveMin - 0.1 * delta)
            }

            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val allMaxima = goalValuesForScaling.map { it.toDouble() } + maxY
                val effectiveMax = allMaxima.maxOrNull() ?: maxY

                val delta = maxY - minY
                return if (delta == 0.0) effectiveMax + 1.0 else ceil(effectiveMax + 0.1 * delta)
            }
        }
    }

    // 3. Create the four layers, one for each group.

    // Layer 0: Main (solid) lines on START axis
    val mainLayerStart = if (mainSeriesStart.isNotEmpty()) {
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                mainColorsStart.map { color ->
                    createLineSpec(color, targetMeasurementTypeId != null, showDataPointsSetting, isProjection = false)
                }
            ),
            verticalAxisPosition = Axis.Position.Vertical.Start,
            rangeProvider = rangeProvider
        )
    } else null

    // Layer 1: Main (solid) lines on END axis
    val mainLayerEnd = if (mainSeriesEnd.isNotEmpty()) {
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                mainColorsEnd.map { color ->
                    createLineSpec(color, targetMeasurementTypeId != null, showDataPointsSetting, isProjection = false)
                }
            ),
            verticalAxisPosition = Axis.Position.Vertical.End,
            rangeProvider = rangeProvider
        )
    } else null

    // Layer 2: Projected (dashed) lines on START axis
    val projectionLayerStart = if (projectedSeriesStart.isNotEmpty()) {
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                projectedColorsStart.map { color ->
                    createLineSpec(color, statisticsMode = false, showPoints = false, isProjection = true)
                }
            ),
            verticalAxisPosition = Axis.Position.Vertical.Start,
            rangeProvider = rangeProvider
        )
    } else null

    // Layer 3: Projected (dashed) lines on END axis
    val projectionLayerEnd = if (projectedSeriesEnd.isNotEmpty()) {
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                projectedColorsEnd.map { color ->
                    createLineSpec(color, statisticsMode = false, showPoints = false, isProjection = true)
                }
            ),
            verticalAxisPosition = Axis.Position.Vertical.End,
            rangeProvider = rangeProvider
        )
    } else null

    // 4. Return all non-null layers in the correct order for Vico.
    return remember(mainLayerStart, mainLayerEnd, projectionLayerStart, projectionLayerEnd) {
        listOfNotNull(
            mainLayerStart,
            mainLayerEnd,
            projectionLayerStart,
            projectionLayerEnd
        )
    }
}



/**
 * Provides a [SharedViewModel.TopBarAction] for filtering the line chart.
 * This includes options for selecting the time range and toggling the visibility
 * of the measurement type filter row.
 *
 * @param sharedViewModel The [SharedViewModel] to access settings.
 * @param screenContextName The context name to scope the filter settings. If null, no action is provided.
 * @return A [SharedViewModel.TopBarAction] configuration for the filter menu, or null if context is not provided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun provideFilterTopBarAction(
    sharedViewModel: SharedViewModel,
    screenContextName: String?
): TopBarAction? {

    if (screenContextName == null) return null // Context name is essential for settings persistence

    val scope = rememberCoroutineScope()

    // --- Time Range Setting ---
    val targetTimeRangeKeyName = "${screenContextName}${TIME_RANGE_SUFFIX}"
    val defaultTimeRangeValue = TimeRangeFilter.ALL_DAYS.name // Default if no setting found
    val currentPersistedTimeRangeName by sharedViewModel
        .observeSetting(targetTimeRangeKeyName, defaultTimeRangeValue)
        .collectAsState(initial = defaultTimeRangeValue)
    val activeTimeRange = remember(currentPersistedTimeRangeName) {
        TimeRangeFilter.entries.find { it.name == currentPersistedTimeRangeName } ?: TimeRangeFilter.ALL_DAYS
    }

    // --- Show MeasurementTypeFilterRow Setting ---
    val targetShowFilterRowKeyName = "${screenContextName}${SHOW_TYPE_FILTER_ROW_SUFFIX}"
    // The default value here is for the TopBarAction's initial state if no setting exists.
    // LineChart itself uses `showFilterControls` passed to it as its initial display default.
    val defaultShowFilterRowForTopBar = true
    val currentShowFilterRowSetting by sharedViewModel
        .observeSetting(targetShowFilterRowKeyName, defaultShowFilterRowForTopBar)
        .collectAsState(initial = defaultShowFilterRowForTopBar)

    var showMenuState by rememberSaveable { mutableStateOf(false) } // Controls dropdown menu visibility
    var showDateRangePicker by remember { mutableStateOf(false) }

    if (showDateRangePicker) {
        val dateRangePickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDateRangePicker = false
                        val startMillis = dateRangePickerState.selectedStartDateMillis
                        val endMillis = dateRangePickerState.selectedEndDateMillis
                        if (startMillis != null && endMillis != null) {
                            scope.launch {
                                sharedViewModel.saveSetting(
                                    "${screenContextName}${CUSTOM_START_DATE_MILLIS_SUFFIX}",
                                    startMillis
                                )
                                sharedViewModel.saveSetting(
                                    "${screenContextName}${CUSTOM_END_DATE_MILLIS_SUFFIX}",
                                    endMillis
                                )
                                sharedViewModel.saveSetting(
                                    targetTimeRangeKeyName,
                                    TimeRangeFilter.CUSTOM.name
                                )
                            }
                        }
                    },
                    enabled = dateRangePickerState.selectedEndDateMillis != null
                ) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        ) {
            DateRangePicker(state = dateRangePickerState)
        }
    }

    return TopBarAction(
        icon = Icons.Default.FilterList,
        contentDescription = stringResource(R.string.content_description_filter_chart_data), // Accessibility
        onClick = { showMenuState = !showMenuState }
    ) { // Content of the DropdownMenu
        DropdownMenu(
            expanded = showMenuState,
            onDismissRequest = { showMenuState = false }
        ) {
            // Time Range Options
            TimeRangeFilter.entries.forEach { timeRange ->
                DropdownMenuItem(
                    text = { Text(timeRange.getDisplayName(LocalContext.current)) },
                    leadingIcon = {
                        if (activeTimeRange == timeRange) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(
                                    R.string.content_description_time_range_selected,
                                    timeRange.getDisplayName(LocalContext.current) // Same i18n consideration as above
                                )
                            )
                        } else {
                            // Optional: Maintain alignment by adding a spacer if no icon
                            Spacer(Modifier.width(24.dp)) // Width of the Check icon
                        }
                    },
                    onClick = {
                        showMenuState = false
                        if (timeRange == TimeRangeFilter.CUSTOM) {
                            showDateRangePicker = true
                        } else {
                            scope.launch { sharedViewModel.saveSetting(targetTimeRangeKeyName, timeRange.name) }
                        }
                    }
                )
            }

            // The option to toggle the measurement type filter row is not shown for the Statistics screen,
            // as it has its own dedicated type selection mechanism.
            if (screenContextName != SettingsPreferenceKeys.STATISTICS_SCREEN_CONTEXT) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Toggle MeasurementTypeFilterRow Option
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_item_measurement_filter)) },
                    leadingIcon = {
                        if (currentShowFilterRowSetting) {
                            Icon(
                                imageVector = Icons.Default.Check, // Indicates filter row is currently SHOWN
                                contentDescription = stringResource(R.string.content_description_measurement_filter_visible)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.CheckBoxOutlineBlank, // Indicates filter row is HIDDEN
                                contentDescription = stringResource(R.string.content_description_measurement_filter_hidden)
                            )
                        }
                    },
                    onClick = {
                        scope.launch {
                            sharedViewModel.saveSetting(
                                targetShowFilterRowKeyName,
                                !currentShowFilterRowSetting
                            )
                        }
                        showMenuState = false // Close menu after selection
                    }
                )
            }
        }
    }
}


/**
 * A private helper that remembers and calculates the data needed for the [PeriodChart].
 * It groups measurements by a dynamic time unit (day, week, month, year)
 * based on the total time span of the provided data.
 *
 * @param measurementsForPeriodChart The list of measurements to be processed.
 * @param uiSelectedTimeRange The currently active time range filter.
 * @return A memoized list of [PeriodDataPoint]s ready for rendering.
 */
@Composable
private fun rememberPeriodChartData(
    measurementsForPeriodChart: List<MeasurementWithValues>,
    uiSelectedTimeRange: TimeRangeFilter
): List<PeriodDataPoint> {
    return remember(measurementsForPeriodChart, uiSelectedTimeRange) {
        if (measurementsForPeriodChart.isEmpty()) return@remember emptyList()

        // Determine min and max date of filtered measurements
        val minDate = measurementsForPeriodChart.minOf {
            Instant.ofEpochMilli(it.measurement.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
        val maxDate = measurementsForPeriodChart.maxOf {
            Instant.ofEpochMilli(it.measurement.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }

        // Decide grouping dynamically
        val totalDays = ChronoUnit.DAYS.between(minDate, maxDate).toInt()
        val groupingUnit: ChronoUnit
        val intervalSize: Long

        when {
            totalDays <= 7 -> {
                groupingUnit = ChronoUnit.DAYS
                intervalSize = 1
            }
            totalDays <= 30 -> {
                groupingUnit = ChronoUnit.WEEKS
                intervalSize = 1
            }
            totalDays <= 365 -> {
                groupingUnit = ChronoUnit.MONTHS
                intervalSize = 1
            }
            else -> {
                groupingUnit = ChronoUnit.YEARS
                intervalSize = 1
            }
        }

        // Generate periods from minDate to maxDate
        val allPeriods = mutableListOf<LocalDate>()
        var cursor = when (groupingUnit) {
            ChronoUnit.DAYS -> minDate
            ChronoUnit.WEEKS -> minDate.with(DayOfWeek.MONDAY)
            ChronoUnit.MONTHS -> minDate.withDayOfMonth(1)
            else -> minDate.withDayOfYear(1)
        }

        while (!cursor.isAfter(maxDate)) {
            allPeriods.add(cursor)
            cursor = when (groupingUnit) {
                ChronoUnit.DAYS -> cursor.plusDays(intervalSize)
                ChronoUnit.WEEKS -> cursor.plusWeeks(intervalSize)
                ChronoUnit.MONTHS -> cursor.plusMonths(intervalSize)
                else -> cursor.plusYears(intervalSize)
            }
        }

        // Ensure minimum 5 periods for better chart appearance
        while (allPeriods.size < 5) {
            cursor = when (groupingUnit) {
                ChronoUnit.DAYS -> allPeriods.first().minusDays(intervalSize)
                ChronoUnit.WEEKS -> allPeriods.first().minusWeeks(intervalSize)
                ChronoUnit.MONTHS -> allPeriods.first().minusMonths(intervalSize)
                else -> allPeriods.first().minusYears(intervalSize)
            }
            allPeriods.add(0, cursor)
        }

        // Group measurements by period
        val grouped = measurementsForPeriodChart.groupBy { mwv ->
            val date = Instant.ofEpochMilli(mwv.measurement.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            when (groupingUnit) {
                ChronoUnit.DAYS -> date
                ChronoUnit.WEEKS -> date.with(DayOfWeek.MONDAY)
                ChronoUnit.MONTHS -> date.withDayOfMonth(1)
                else -> date.withDayOfYear(1)
            }
        }

        // Localized label formatter
        val locale = Locale.getDefault()
        val labelFormatter: (LocalDate) -> String = { date ->
            when (groupingUnit) {
                ChronoUnit.DAYS -> date.format(DateTimeFormatter.ofPattern("d LLL", locale))
                ChronoUnit.WEEKS -> "W${date.get(WeekFields.of(locale).weekOfWeekBasedYear())}"
                ChronoUnit.MONTHS -> date.format(DateTimeFormatter.ofPattern("LLL yy", locale))
                else -> date.year.toString()
            }
        }

        // Map all periods to PeriodDataPoint, even empty ones
        allPeriods.mapIndexed { index, periodStart ->
            val periodEnd = if (index + 1 < allPeriods.size)
                allPeriods[index + 1].atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else
                maxDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val measurementsInPeriod = grouped[periodStart] ?: emptyList()

            PeriodDataPoint(
                label = labelFormatter(periodStart),
                count = measurementsInPeriod.size,
                startTimestamp = periodStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                endTimestamp = periodEnd
            )
        }
    }
}


/**
 * Remembers a [CartesianValueFormatter] for the X-axis that converts epoch day float values
 * back to formatted date strings using a provided map.
 *
 * @param chartSeries The list of all processed series data to be plotted.
 * @return A memoized [CartesianValueFormatter].
 */
@Composable
private fun rememberXAxisValueFormatter(
    chartSeries: List<ChartSeries>,
): CartesianValueFormatter = remember(chartSeries) {
    val xToDatesMap = chartSeries.flatMap { it.points }.associate { it.x to it.date }

    CartesianValueFormatter { context, value, _ -> // `value` is the x-axis value (epochDay as float)
        val x = value.toFloat()

        (xToDatesMap[x] ?: LocalDate.ofEpochDay(value.toLong())).format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

/**
 * Creates a [LineCartesianLayer.Line] specification for a single series in the chart.
 *
 * @param color The color of the line and points.
 * @param statisticsMode If true, an area fill is added below the line, and points are hidden.
 *                       This is typically used when `targetMeasurementTypeId` is set.
 * @param showPoints If true, points are displayed on the line (unless in statisticsMode or for projections).
 * @param isProjection If true, creates a dashed line specification for projection data.
 * @return A configured [LineCartesianLayer.Line].
 */
private fun createLineSpec(
    color: Color,
    statisticsMode: Boolean,
    showPoints: Boolean,
    isProjection: Boolean = false
): LineCartesianLayer.Line {
    val lineStroke = if (isProjection) {
        // Create a dashed line for projections
        LineCartesianLayer.LineStroke.dashed(
            thickness = 2.dp,
            dashLength = 4.dp,
            gapLength = 4.dp
        )
    } else {
        // Create a solid line for actual measurements
        LineCartesianLayer.LineStroke.Continuous(
            thicknessDp = 2f,
        )
    }

    val lineFill = LineCartesianLayer.LineFill.single( // Defines the color of the line itself
        fill = Fill(color.toArgb())
    )

    return LineCartesianLayer.Line(
        fill = lineFill,
        stroke = lineStroke,
        // Area fill is shown in statistics mode, but never for projections
        areaFill = if (statisticsMode && !isProjection) LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.2f).toArgb())) else null,
        // Points on the line are shown unless in statistics mode or for projections
        pointProvider = if (showPoints && !statisticsMode && !isProjection) {
            LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.point(ShapeComponent(fill(color.copy(alpha = 0.7f)), CorneredShape.Pill), 6.dp)
            )
        } else null,
        pointConnector = LineCartesianLayer.PointConnector.cubic()
    )
}

/**
 * A private helper composable that creates and remembers a [HorizontalLine] decoration for a given goal.
 *
 * @param goal The [UserGoals] object for which to create the line.
 * @param type The corresponding [MeasurementType] to get the color and axis position from.
 * @return A remembered [HorizontalLine] object.
 */
@Composable
private fun rememberGoalLine(goal: UserGoals, type: MeasurementType?): HorizontalLine {
    val goalColor = type?.let { Color(it.color) } ?: MaterialTheme.colorScheme.onSurface
    val goalFill = fill(goalColor.copy(alpha = 0.7f))
    val line = rememberLineComponent(
        fill = goalFill,
        thickness = 2.dp,
    )

    val labelComponent =
        rememberTextComponent(
            color = White,
            margins = insets(start = 6.dp),
            padding = insets(start = 8.dp, end = 8.dp, bottom = 2.dp, top = 2.dp),
            background =
                shapeComponent(goalFill, CorneredShape.rounded(topLeft = 4.dp, topRight = 4.dp, bottomLeft = 4.dp, bottomRight = 4.dp)),
        )

    return remember(goal, line) {
        HorizontalLine(
            y = { goal.goalValue.toDouble() },
            line = line,
            labelComponent = labelComponent,
            label = { LocaleUtils.formatValueForDisplay(goal.goalValue.toString(), type?.unit ?: UnitType.NONE) },
            verticalAxisPosition = if (type?.isOnRightYAxis == true) {
                Axis.Position.Vertical.End
            } else {
                Axis.Position.Vertical.Start
            }
        )
    }
}


/**
 * Remembers and configures a [CartesianMarker] for displaying details when a data point is interacted with.
 *
 * @param valueFormatter The formatter for the value displayed in the marker.
 * @param showIndicator If true, an indicator (like a dot) is shown on the line at the marker's position.
 * @return A memoized [CartesianMarker].
 */
@Composable
fun rememberMarker(
    // Keeping this public as it's a significant, potentially reusable component configuration
    valueFormatter: DefaultCartesianMarker.ValueFormatter =
        DefaultCartesianMarker.ValueFormatter.default(), // Uses default formatting for the value
    showIndicator: Boolean = true,
): CartesianMarker {
    val labelBackgroundShape = markerCorneredShape(CorneredShape.Corner.Rounded)
    val labelBackground =
        rememberShapeComponent(
            fill = fill(MaterialTheme.colorScheme.background),
            shape = labelBackgroundShape,
            strokeThickness = 1.dp,
            strokeFill = fill(MaterialTheme.colorScheme.outline), // Outline for the label
        )
    val label =
        rememberTextComponent(
            // Text component for the marker
            color = MaterialTheme.colorScheme.onSurface, // Text color
            textAlignment = Layout.Alignment.ALIGN_CENTER,
            padding = insets(horizontal = 8.dp, vertical = 4.dp), // Padding within the label
            background = labelBackground,
            minWidth = TextComponent.MinWidth.fixed(40.dp), // Minimum width for the label
        )
    val indicatorFrontComponent =
        rememberShapeComponent(fill(MaterialTheme.colorScheme.surface), CorneredShape.Pill)
    val guideline = rememberAxisGuidelineComponent()

    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = valueFormatter,
        indicator = // Custom indicator drawing logic
            if (showIndicator) {
                { color -> // `color` is the color of the series line
                    LayeredComponent(
                        back = ShapeComponent(fill(color.copy(alpha = 0.15f)), CorneredShape.Pill),
                        front =
                            LayeredComponent(
                                back = ShapeComponent(
                                    fill = fill(color),
                                    shape = CorneredShape.Pill
                                ),
                                front = indicatorFrontComponent,
                                padding = insets(5.dp),
                            ),
                        padding = insets(10.dp),
                    )
                }
            } else {
                null // No indicator if showIndicator is false
            },
        indicatorSize = 36.dp, // Overall size of the indicator area
        guideline = guideline, // Vertical guideline that follows the marker
    )
}

/**
 * A private helper that creates and remembers a visibility listener for the chart's marker.
 * It encapsulates the logic to find the correct timestamp from the marker's position
 * and trigger the onPointSelected callback.
 *
 * @param chartSeries The list of all processed series data, used to find the correct point.
 * @param onPointSelected The callback to invoke with the determined timestamp.
 * @return A configured [CartesianMarkerVisibilityListener] instance.
 */
@Composable
private fun rememberMarkerVisibilityListener(
    chartSeries: List<ChartSeries>,
    onPointSelected: (timestamp: Long) -> Unit
): CartesianMarkerVisibilityListener {
    // This state holds the last X position of the marker before it's hidden.
    val lastX = remember { mutableStateOf<Float?>(null) }

    return remember(chartSeries, onPointSelected) {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                // When the marker appears, store its X position.
                lastX.value = targets.lastOrNull()?.x?.toFloat()
            }

            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                // Continuously update the X position as the user scrubs through the chart.
                lastX.value = targets.lastOrNull()?.x?.toFloat()
            }

            override fun onHidden(marker: CartesianMarker) {
                val x = lastX.value ?: return

                // We search through all points of all series to find the one matching the X value.
                val point = chartSeries
                    .flatMap { it.points }
                    .find { it.x == x }

                if (point != null) {
                    // We found the exact ChartPoint, so we can use its original `date`.
                    val timestamp = point.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onPointSelected(timestamp)
                } else {
                    // Fallback, should rarely happen. This is the same as the old code's `?:`.
                    val date = LocalDate.ofEpochDay(x.toLong())
                    val timestamp = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onPointSelected(timestamp)
                }
            }
        }
    }
}

/**
 * Creates a human-readable title for the current time filter state.
 */
@Composable
private fun rememberFilterTitle(
    activeFilter: TimeRangeFilter,
    startTimeMillis: Long?,
    endTimeMillis: Long?
): String {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateFormat(context) }

    return when {
        // If a custom range is selected AND has valid dates
        activeFilter == TimeRangeFilter.CUSTOM && startTimeMillis != null && endTimeMillis != null -> {
            val startDate = dateFormat.format(Date(startTimeMillis))
            val endDate = dateFormat.format(Date(endTimeMillis))
            stringResource(R.string.time_range_custom_from_to, startDate, endDate)
        }
        // For all predefined ranges (or if custom is somehow invalid)
        else -> {
            activeFilter.getDisplayName(context)
        }
    }
}

/**
 * Remembers and resolves the complete time filter state: the selected enum,
 * and the calculated start/end timestamps. This is the single source of truth for the chart's time filtering.
 *
 * @return A [State] holding a [Triple] of (activeTimeRange, startTimeMillis, endTimeMillis).
 */
@Composable
internal fun rememberResolvedTimeRangeState(
    screenContextName: String,
    sharedViewModel: SharedViewModel,
    defaultFilter: TimeRangeFilter = TimeRangeFilter.ALL_DAYS
): State<Triple<TimeRangeFilter, Long?, Long?>> {
    // 1. Observe the selected TimeRangeFilter type from settings
    val timeRangeKey = remember(screenContextName) { "${screenContextName}${TIME_RANGE_SUFFIX}" }
    val persistedTimeRangeName by sharedViewModel
        .observeSetting(timeRangeKey, defaultFilter.name)
        .collectAsState(initial = defaultFilter.name)

    val activeTimeRange = remember(persistedTimeRangeName) {
        TimeRangeFilter.entries.find { it.name == persistedTimeRangeName } ?: defaultFilter
    }

    // 2. Observe the custom start/end dates from settings
    val customStartKey = remember(screenContextName) { "${screenContextName}${CUSTOM_START_DATE_MILLIS_SUFFIX}" }
    val customStartMillis by sharedViewModel.observeSetting(customStartKey, -1L).collectAsState(initial = -1L)

    val customEndKey = remember(screenContextName) { "${screenContextName}${CUSTOM_END_DATE_MILLIS_SUFFIX}" }
    val customEndMillis by sharedViewModel.observeSetting(customEndKey, -1L).collectAsState(initial = -1L)

    // 3. Calculate the final start/end timestamps and combine everything into a Triple
    return remember(activeTimeRange, customStartMillis, customEndMillis) {
        mutableStateOf(
            run {
                val (start, end) = when (activeTimeRange) {
                    TimeRangeFilter.ALL_DAYS -> null to null
                    TimeRangeFilter.CUSTOM -> {
                        val customStart = if (customStartMillis != -1L) customStartMillis else null
                        val customEnd = if (customEndMillis != -1L) customEndMillis else null
                        customStart to customEnd
                    }
                    else -> { // Predefined ranges
                        val cal = java.util.Calendar.getInstance()
                        val endTime = cal.timeInMillis
                        when (activeTimeRange) {
                            TimeRangeFilter.LAST_7_DAYS -> cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                            TimeRangeFilter.LAST_30_DAYS -> cal.add(java.util.Calendar.DAY_OF_YEAR, -30)
                            TimeRangeFilter.LAST_365_DAYS -> cal.add(java.util.Calendar.DAY_OF_YEAR, -365)
                            else -> { /* no-op */ }
                        }
                        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                        cal.set(java.util.Calendar.MINUTE, 0)
                        cal.set(java.util.Calendar.SECOND, 0)
                        cal.set(java.util.Calendar.MILLISECOND, 0)
                        val startTime = cal.timeInMillis
                        startTime to endTime
                    }
                }

                Triple(activeTimeRange, start, end)
            }
        )
    }
}


/**
 * Remembers a set of selected measurement type IDs (as strings) that is persisted
 * in [SettingsFacade] based on the provided [screenContextName].
 *
 * @param screenContextName The unique context name for this setting.
 * @param settingsFacade The repository to observe and save the setting.
 * @param defaultSelectedTypeIds The default set of type IDs to use if no setting is found.
 * @return A [State] holding the current [Set] of selected type IDs (strings).
 */
@Composable
fun rememberContextualSelectedTypeIds(
    screenContextName: String,
    observeStringSet: (key: String, default: Set<String>) -> kotlinx.coroutines.flow.Flow<Set<String>>,
    defaultSelectedTypeIds: Set<String> = emptySet()
): State<Set<String>> {
    val key = remember(screenContextName) { "${screenContextName}_selected_types" }
    return observeStringSet(key, defaultSelectedTypeIds).collectAsState(initial = defaultSelectedTypeIds)
}

/**
 * Remembers a boolean setting value that is persisted in [SettingsFacade]
 * based on the provided [screenContextName] and [settingSuffix].
 *
 * @param screenContextName The unique context name for this setting.
 * @param settingSuffix The specific suffix for this boolean setting (e.g., "_show_filter").
 * @param settingsFacade The repository to observe and save the setting.
 * @param defaultValue The default boolean value to use if no setting is found.
 * @return A [State] holding the current boolean value.
 */
@Composable
fun rememberContextualBooleanSetting(
    screenContextName: String,
    settingSuffix: String,
    observeBoolean: (key: String, default: Boolean) -> kotlinx.coroutines.flow.Flow<Boolean>,
    defaultValue: Boolean
): State<Boolean> {
    val key = remember(screenContextName, settingSuffix) { "${screenContextName}${settingSuffix}" }
    return observeBoolean(key, defaultValue).collectAsState(initial = defaultValue)
}
