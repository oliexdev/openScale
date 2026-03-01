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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.ui.shared.SharedViewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.collections.toTypedArray

/**
 * Represents a single, plottable data point for the chart,
 * holding both the application's domain value (LocalDate) and
 * Vico's required numeric value (Float).
 */
internal data class ChartPoint(
    val date: LocalDate,
    val x: Float,
    val y: Float
)

/**
 * Represents a full data series for one line in the chart, including its metadata.
 */
internal data class ChartSeries(
    val isProjected: Boolean,
    val type: MeasurementType,
    val points: List<ChartPoint>
)

/**
 * A Composable that displays a line chart for visualizing measurement data over time.
 * Supports time range filtering, type selection, smoothing, projections, and goal lines.
 *
 * @param modifier Modifier for this composable.
 * @param sharedViewModel The [SharedViewModel] providing access to data and settings.
 * @param screenContextName A unique name used to persist filter settings for this context.
 * @param showFilterControls If true, the measurement type filter row is shown by default.
 * @param showPeriodChart If true, a period overview chart is shown above the main chart.
 * @param showFilterTitle If true, a title showing the current time range and data count is shown.
 * @param showYAxis If true, both Y-axes (start and end) are displayed.
 * @param targetMeasurementTypeId If non-null, only this type is shown. Hides the type filter row.
 * @param onPointSelected Callback invoked with the timestamp when the user selects a data point.
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
    val effectiveShowTypeFilterRow = if (targetMeasurementTypeId != null) false else showTypeFilterRowSetting

    val allAvailableMeasurementTypes by sharedViewModel.measurementTypes.collectAsState()
    val defaultSelectedTypesValue = remember(targetMeasurementTypeId) {
        if (targetMeasurementTypeId != null) {
            setOf(targetMeasurementTypeId.toString())
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
    LaunchedEffect(splitterWeight) { localSplitterWeight = splitterWeight }

    val showDataPointsSetting by sharedViewModel.showChartDataPoints.collectAsStateWithLifecycle(initialValue = true)
    val showGoalLinesSetting by sharedViewModel.showChartGoalLines.collectAsStateWithLifecycle(initialValue = false)
    val isSmoothingActive by remember {
        sharedViewModel.selectedSmoothingAlgorithm.map { it != SmoothingAlgorithm.NONE }
    }.collectAsStateWithLifecycle(initialValue = false)

    val timeRangeState by rememberResolvedTimeRangeState(screenContextName, sharedViewModel)
    val (uiSelectedTimeRange, startTimeMillis, endTimeMillis) = timeRangeState
    val filterTitle = rememberFilterTitle(uiSelectedTimeRange, startTimeMillis, endTimeMillis)

    val currentSelectedTypeIdsStrings by rememberContextualSelectedTypeIds(
        screenContextName = screenContextName,
        observeStringSet = { key, default -> sharedViewModel.observeSetting(key, default) },
        defaultSelectedTypeIds = defaultSelectedTypesValue
    )
    val currentSelectedTypeIntIds: Set<Int> = remember(currentSelectedTypeIdsStrings) {
        currentSelectedTypeIdsStrings.mapNotNull { it.toIntOrNull() }.toSet()
    }

    var isChartDataLoading by remember { mutableStateOf(true) }
    val smoothedData by remember(startTimeMillis, endTimeMillis, currentSelectedTypeIntIds) {
        sharedViewModel.smoothedEnrichedMeasurements(
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            typeIds = currentSelectedTypeIntIds
        )
    }.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(smoothedData) {
        if (smoothedData != null) isChartDataLoading = false
    }

    var selectedPeriod by remember { mutableStateOf<PeriodDataPoint?>(null) }

    val filteredMeasurements = remember(smoothedData, selectedPeriod) {
        val data = smoothedData ?: emptyList()
        if (selectedPeriod == null) data
        else data.filter { measurement ->
            val ts = measurement.measurementWithValues.measurement.timestamp
            ts >= selectedPeriod!!.startTimestamp && ts < selectedPeriod!!.endTimestamp
        }
    }

    val measurementsForPeriodChart = remember(smoothedData) {
        (smoothedData ?: emptyList()).map { it.measurementWithValues }
    }
    val periodChartData = rememberPeriodChartData(measurementsForPeriodChart, uiSelectedTimeRange)

    val lineTypesToActuallyPlot = remember(allAvailableMeasurementTypes, currentSelectedTypeIntIds, targetMeasurementTypeId) {
        allAvailableMeasurementTypes.filter { type ->
            val typeIsSelected = type.id in currentSelectedTypeIntIds
            val typeIsTarget = targetMeasurementTypeId != null && type.id == targetMeasurementTypeId
            val typeIsPlottable = type.isEnabled && (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT)
            (if (targetMeasurementTypeId != null) typeIsTarget else typeIsSelected) && typeIsPlottable
        }
    }

    val selectedUserId by sharedViewModel.selectedUserId.collectAsStateWithLifecycle()
    val goalsToActuallyPlot by remember(selectedUserId, lineTypesToActuallyPlot) {
        sharedViewModel.getAllGoalsForUser(selectedUserId ?: -1)
            .map { allGoals -> allGoals.filter { goal -> lineTypesToActuallyPlot.any { it.id == goal.measurementTypeId } } }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val goalValuesForScaling = remember(goalsToActuallyPlot, showGoalLinesSetting) {
        if (showGoalLinesSetting) goalsToActuallyPlot.map { it.goalValue.toFloat() } else emptyList()
    }

    Column(modifier = modifier) {
        AnimatedVisibility(visible = effectiveShowTypeFilterRow) {
            MeasurementTypeFilterRow(
                allMeasurementTypesProvider = { allAvailableMeasurementTypes },
                selectedTypeIdsFlowProvider = {
                    sharedViewModel.observeSetting("${screenContextName}${SELECTED_TYPES_SUFFIX}", defaultSelectedTypesValue)
                },
                onPersistSelectedTypeIds = { newIds ->
                    scope.launch { sharedViewModel.saveSetting("${screenContextName}${SELECTED_TYPES_SUFFIX}", newIds) }
                },
                filterLogic = { allTypes ->
                    allTypes.filter { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) }
                },
                onSelectionChanged = { },
                defaultSelectionLogic = { selectableFilteredTypes ->
                    if (targetMeasurementTypeId != null) {
                        selectableFilteredTypes.find { it.id == targetMeasurementTypeId }?.let { listOf(it.id) } ?: emptyList()
                    } else {
                        val defaultIdsToTry = listOf(MeasurementTypeKey.WEIGHT.id, MeasurementTypeKey.BODY_FAT.id)
                        val selectedByDefault = defaultIdsToTry.filter { id -> selectableFilteredTypes.any { it.id == id } }
                        selectedByDefault.ifEmpty { selectableFilteredTypes.firstOrNull()?.let { listOf(it.id) } ?: emptyList() }
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
                    onPeriodClick = { clicked -> selectedPeriod = if (selectedPeriod == clicked) null else clicked }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                localSplitterWeight = (localSplitterWeight + dragAmount.y / 2000f).coerceIn(0.01f, 0.8f)
                            },
                            onDragEnd = {
                                scope.launch { sharedViewModel.setSplitterWeight(SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT, localSplitterWeight) }
                            }
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

        LaunchedEffect(isChartDataLoading, lineTypesToActuallyPlot, filteredMeasurements, effectiveShowTypeFilterRow, targetMeasurementTypeId, allAvailableMeasurementTypes) {
            if (!isChartDataLoading) {
                val measurements = filteredMeasurements
                showNoDataMessage = when {
                    lineTypesToActuallyPlot.isEmpty() && measurements.isEmpty() && !effectiveShowTypeFilterRow && targetMeasurementTypeId == null -> {
                        noDataMessageText = if (allAvailableMeasurementTypes.none { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) })
                            context.getString(R.string.line_chart_no_plottable_types)
                        else context.getString(R.string.line_chart_no_data_to_display)
                        true
                    }
                    lineTypesToActuallyPlot.isEmpty() && measurements.isEmpty() && targetMeasurementTypeId != null -> {
                        noDataMessageText = context.getString(
                            R.string.line_chart_no_data_for_type_in_range,
                            allAvailableMeasurementTypes.find { it.id == targetMeasurementTypeId }?.getDisplayName(context)
                                ?: context.getString(R.string.line_chart_this_type_placeholder)
                        )
                        true
                    }
                    else -> false
                }
            } else {
                showNoDataMessage = false
            }
        }

        val chartSeries = remember(filteredMeasurements, lineTypesToActuallyPlot, targetMeasurementTypeId) {
            val series = filteredMeasurements.toSmoothedChartSeries(lineTypesToActuallyPlot)
            // In statistics mode, filter out projections
            if (targetMeasurementTypeId != null) series.filter { !it.isProjected } else series
        }
        val rawChartSeries = remember(filteredMeasurements, lineTypesToActuallyPlot) {
            filteredMeasurements.toRawChartSeries(lineTypesToActuallyPlot)
        }

        when {
            isChartDataLoading -> {
                Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            showNoDataMessage -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text = noDataMessageText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            chartSeries.isEmpty() -> {
                // Pre-compute expensive checks for message logic
                val hasNoPlottableTypes = remember(allAvailableMeasurementTypes) {
                    allAvailableMeasurementTypes.none { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) }
                }
                val hasDataForSelectedTypes = remember(smoothedData, currentSelectedTypeIntIds) {
                    (smoothedData ?: emptyList()).any { m -> m.measurementWithValues.values.any { v -> v.type.id in currentSelectedTypeIntIds } }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val message = when {
                        lineTypesToActuallyPlot.isEmpty() && effectiveShowTypeFilterRow -> when {
                            currentSelectedTypeIntIds.isNotEmpty() && !hasDataForSelectedTypes ->
                                stringResource(R.string.line_chart_no_data_for_selected_types)
                            currentSelectedTypeIntIds.isEmpty() -> stringResource(R.string.line_chart_please_select_types)
                            else -> stringResource(R.string.line_chart_no_data_to_display)
                        }
                        lineTypesToActuallyPlot.isEmpty() ->
                            if (hasNoPlottableTypes)
                                stringResource(R.string.line_chart_no_plottable_types)
                            else stringResource(R.string.line_chart_no_data_or_types_to_select)
                        (smoothedData ?: emptyList()).isEmpty() && filteredMeasurements.isEmpty() && currentSelectedTypeIntIds.isNotEmpty() ->
                            stringResource(R.string.line_chart_no_data_to_display)
                        else -> stringResource(R.string.line_chart_no_data_for_selected_types)
                    }
                    Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            else -> {
                val scrollState = rememberVicoScrollState()
                val zoomState = rememberVicoZoomState(zoomEnabled = true, initialZoom = Zoom.Content)

                val xAxis = if (targetMeasurementTypeId == null) {
                    HorizontalAxis.rememberBottom(valueFormatter = rememberXAxisValueFormatter(chartSeries), guideline = null)
                } else null
                val startYAxis = if (showYAxis) VerticalAxis.rememberStart(valueFormatter = CartesianValueFormatter.decimal()) else null
                val endYAxis = if (showYAxis) VerticalAxis.rememberEnd(valueFormatter = CartesianValueFormatter.decimal()) else null

                val modelProducer = rememberChartModelProducer(chartSeries, rawChartSeries, isSmoothingActive, showDataPointsSetting)
                val layers = rememberChartLayers(chartSeries, rawChartSeries, isSmoothingActive, showDataPointsSetting, targetMeasurementTypeId, goalValuesForScaling)

                val typeById = remember(allAvailableMeasurementTypes) {
                    allAvailableMeasurementTypes.associateBy { it.id }
                }
                val goalDecorations = if (showGoalLinesSetting) {
                    goalsToActuallyPlot.map { goal ->
                        rememberGoalLine(goal = goal, type = typeById[goal.measurementTypeId])
                    }
                } else emptyList()

                val chart = rememberCartesianChart(
                    layers = layers.toTypedArray(),
                    startAxis = startYAxis,
                    bottomAxis = xAxis,
                    endAxis = endYAxis,
                    marker = rememberMarker(),
                    markerVisibilityListener = rememberMarkerVisibilityListener(chartSeries, onPointSelected),
                    decorations = goalDecorations
                )

                CartesianChartHost(
                    chart = chart,
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().weight(if (showPeriodChart) 1f - localSplitterWeight else 1f),
                    scrollState = scrollState,
                    zoomState = zoomState
                )
            }
        }

        if (showFilterTitle) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp),
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
                    text = stringResource(R.string.line_chart_filter_title_template, filterTitle, filteredMeasurements.size),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Transforms enriched measurements into [ChartSeries] using RAW (original, unsmoothed) values.
 * Used to display the original data points as dots when smoothing is active.
 */
internal fun List<EnrichedMeasurement>.toRawChartSeries(
    types: List<MeasurementType>
): List<ChartSeries> {
    if (isEmpty() || types.isEmpty()) return emptyList()

    val typeById = types.associateBy { it.id }
    val zone = ZoneId.systemDefault()

    // Single pass over all measurements
    val pointsByTypeId = mutableMapOf<Int, MutableList<ChartPoint>>()

    forEach { em ->
        val date = Instant.ofEpochMilli(em.measurementWithValues.measurement.timestamp)
            .atZone(zone).toLocalDate()
        val x = date.toEpochDay().toFloat()

        em.measurementWithValues.values.forEach { vt ->
            val type = typeById[vt.type.id] ?: return@forEach
            val yValue = when (type.inputType) {
                InputFieldType.FLOAT -> vt.value.floatValue
                InputFieldType.INT -> vt.value.intValue?.toFloat()
                else -> null
            } ?: return@forEach

            pointsByTypeId.getOrPut(type.id) { mutableListOf() }
                .add(ChartPoint(date = date, x = x, y = yValue))
        }
    }

    return types.mapNotNull { type ->
        val points = pointsByTypeId[type.id]?.sortedBy { it.x }
        if (!points.isNullOrEmpty()) ChartSeries(isProjected = false, type = type, points = points)
        else null
    }
}

/**
 * Transforms enriched measurements into [ChartSeries] using SMOOTHED values from [valuesWithTrend].
 * Also includes projected (future) data if available.
 */
internal fun List<EnrichedMeasurement>.toSmoothedChartSeries(
    types: List<MeasurementType>
): List<ChartSeries> {
    if (isEmpty() || types.isEmpty()) return emptyList()

    val typeById = types.associateBy { it.id }
    val zone = ZoneId.systemDefault()

    // Single pass over all measurements
    val pointsByTypeId = mutableMapOf<Int, MutableList<ChartPoint>>()

    forEach { em ->
        val date = Instant.ofEpochMilli(em.measurementWithValues.measurement.timestamp)
            .atZone(zone).toLocalDate()
        val x = date.toEpochDay().toFloat()

        em.valuesWithTrend.forEach { vwt ->
            val vt = vwt.currentValue
            val type = typeById[vt.type.id] ?: return@forEach
            val yValue = when (type.inputType) {
                InputFieldType.FLOAT -> vt.value.floatValue
                InputFieldType.INT -> vt.value.intValue?.toFloat()
                else -> null
            } ?: return@forEach

            pointsByTypeId.getOrPut(type.id) { mutableListOf() }
                .add(ChartPoint(date = date, x = x, y = yValue))
        }
    }

    val realSeries = types.mapNotNull { type ->
        val points = pointsByTypeId[type.id]?.sortedBy { it.x }
        if (!points.isNullOrEmpty()) ChartSeries(isProjected = false, type = type, points = points)
        else null
    }

    // Projected (future) series - collected from ALL measurements
    val allProjections = flatMap { it.measurementWithValuesProjected }

    val projectedSeries = if (allProjections.isNotEmpty()) {
        val groupedProjections = allProjections.groupBy { it.values.first().type.id }
        types.mapNotNull { type ->
            val projectedValuesForType = groupedProjections[type.id]
            if (!projectedValuesForType.isNullOrEmpty()) {
                val chartPoints = projectedValuesForType
                    .map { mwv ->
                        val date = Instant.ofEpochMilli(mwv.measurement.timestamp)
                            .atZone(zone).toLocalDate()
                        ChartPoint(date = date, x = date.toEpochDay().toFloat(), y = mwv.values.first().value.floatValue!!)
                    }
                    .sortedBy { it.x }
                ChartSeries(isProjected = true, type = type, points = chartPoints)
            } else null
        }
    } else emptyList()

    return realSeries + projectedSeries
}