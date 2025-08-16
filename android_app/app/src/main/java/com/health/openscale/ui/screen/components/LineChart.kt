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
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import com.health.openscale.core.database.UserPreferenceKeys
import com.health.openscale.core.database.UserSettingsRepository
import com.health.openscale.core.database.UserSettingsRepositoryImpl
import com.health.openscale.ui.screen.SharedViewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.fixed
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.common.shape.markerCorneredShape
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis.ItemPlacer.Companion.count
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.LayeredComponent
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

internal val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
internal val X_TO_DATE_MAP_KEY = ExtraStore.Key<Map<Float, LocalDate>>() // Key for storing date mapping in chart model
private const val TIME_RANGE_SUFFIX = "_time_range"
private const val SELECTED_TYPES_SUFFIX = "_selected_types"
private const val SHOW_TYPE_FILTER_ROW_SUFFIX = "_show_type_filter_row"

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
fun LineChart(
    modifier: Modifier = Modifier,
    sharedViewModel: SharedViewModel,
    screenContextName: String,
    showFilterControls: Boolean,
    showFilterTitle: Boolean = false,
    showYAxis: Boolean = true,
    targetMeasurementTypeId: Int? = null
) {
    val scope = rememberCoroutineScope()
    val userSettingsRepository = sharedViewModel.userSettingRepository

    val showDataPointsSetting by userSettingsRepository.showChartDataPoints.collectAsState(initial = true)

    val uiSelectedTimeRange by rememberContextualTimeRangeFilter(
        screenContextName = screenContextName,
        userSettingsRepository = userSettingsRepository
    )
    val showTypeFilterRowSetting by rememberContextualBooleanSetting(
        screenContextName = screenContextName,
        settingSuffix = SHOW_TYPE_FILTER_ROW_SUFFIX,
        userSettingsRepository = userSettingsRepository,
        defaultValue = showFilterControls // Initial default based on what the caller suggests
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
    val currentSelectedTypeIdsStrings by rememberContextualSelectedTypeIds(
        screenContextName = screenContextName,
        userSettingsRepository = userSettingsRepository,
        defaultSelectedTypeIds = defaultSelectedTypesValue
    )
    val currentSelectedTypeIntIds: Set<Int> = remember(currentSelectedTypeIdsStrings) {
        currentSelectedTypeIdsStrings.mapNotNull { stringId: String -> stringId.toIntOrNull() }.toSet()
    }

    val timeRangeFlow = remember { MutableStateFlow(uiSelectedTimeRange) }
    LaunchedEffect(uiSelectedTimeRange) {
        timeRangeFlow.value = uiSelectedTimeRange
    }

    val typesToSmoothFlow = remember { MutableStateFlow(currentSelectedTypeIntIds) }
    LaunchedEffect(currentSelectedTypeIntIds) {
        typesToSmoothFlow.value = currentSelectedTypeIntIds
    }

    val smoothedData by sharedViewModel
        .getSmoothedEnrichedMeasurements(
            timeRangeFlow = timeRangeFlow,
            typesToSmoothAndDisplayFlow = typesToSmoothFlow
        )
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val fullyFilteredEnrichedMeasurements = remember(smoothedData, currentSelectedTypeIntIds) {
        sharedViewModel.filterEnrichedMeasurementsByTypes(smoothedData, currentSelectedTypeIntIds)
    }

    // Extracting measurements with their values for plotting.
    val measurementsWithValues = remember(fullyFilteredEnrichedMeasurements) {
        fullyFilteredEnrichedMeasurements.map { it.measurementWithValues }
    }

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

    Column(modifier = modifier) {
        AnimatedVisibility(visible = effectiveShowTypeFilterRow) {
            MeasurementTypeFilterRow(
                allMeasurementTypesProvider = { allAvailableMeasurementTypes },
                selectedTypeIdsFlowProvider = {
                    userSettingsRepository.observeSetting(
                        "${screenContextName}${SELECTED_TYPES_SUFFIX}",
                        defaultSelectedTypesValue // This is the Set<String> for the FilterRow state
                    )
                },
                onPersistSelectedTypeIds = { newIdsSetToPersist ->
                    scope.launch {
                        userSettingsRepository.saveSetting(
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


        if (showFilterTitle) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
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
                        uiSelectedTimeRange.getDisplayName(LocalContext.current),
                        measurementsWithValues.size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Early exit if there's absolutely nothing to do (no plotable types AND no data AND filter not visible)
        // This is a general "empty state" for the chart area.
        if (lineTypesToActuallyPlot.isEmpty() && measurementsWithValues.isEmpty() && !effectiveShowTypeFilterRow && targetMeasurementTypeId == null) {
            Box(
                modifier = Modifier
                    .weight(1f) // Takes up available vertical space in the Column
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    // Provide a more specific message if no types are plottable at all.
                    if (allAvailableMeasurementTypes.none { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) })
                        stringResource(R.string.line_chart_no_plottable_types)
                    else stringResource(R.string.line_chart_no_data_to_display)
                )
            }
            return@Column // Exits the Column Composable early
        }else if (lineTypesToActuallyPlot.isEmpty() && measurementsWithValues.isEmpty() && targetMeasurementTypeId != null) {
            // Specific empty state when a target type is specified, but no data exists for it.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(
                        R.string.line_chart_no_data_for_type_in_range,
                        allAvailableMeasurementTypes.find { it.id == targetMeasurementTypeId }?.getDisplayName(LocalContext.current)
                            ?: stringResource(R.string.line_chart_this_type_placeholder)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        // State to hold the processed series data for the chart.
        var seriesEntries by remember { mutableStateOf<List<Pair<MeasurementType, List<Pair<LocalDate, Float>>>>>(emptyList()) }
        // State to hold the mapping from X-axis float values (epoch days) back to LocalDate objects.
        var xToDatesMapForStore by remember { mutableStateOf<Map<Float, LocalDate>>(emptyMap()) }

        // Process measurement data into series for the chart when relevant inputs change.
        LaunchedEffect(measurementsWithValues, lineTypesToActuallyPlot) {
            val calculatedSeriesEntries = lineTypesToActuallyPlot.mapNotNull { type ->
                val dateValuePairs = mutableMapOf<LocalDate, Float>()
                measurementsWithValues.forEach { mwv -> // MeasurementWithValues
                    mwv.values.find { it.type.id == type.id }?.let { valueWithType ->
                        val yValue = when (type.inputType) {
                            InputFieldType.FLOAT -> valueWithType.value.floatValue
                            InputFieldType.INT -> valueWithType.value.intValue?.toFloat()
                            else -> null // Should not happen due to lineTypesToActuallyPlot filter
                        }
                        yValue?.let {
                            val date = Instant.ofEpochMilli(mwv.measurement.timestamp)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            // If multiple values exist for the same type on the same day,
                            // the last one processed will overwrite previous ones.
                            // Consider averaging or other aggregation if needed.
                            dateValuePairs[date] = it
                        }
                    }
                }
                if (dateValuePairs.isNotEmpty()) {
                    type to dateValuePairs.toList().sortedBy { it.first } // Sort by date for correct line plotting
                } else {
                    null // No data for this type
                }
            }
            seriesEntries = calculatedSeriesEntries

            // Create the X-axis value to LocalDate map for formatting axis labels.
            if (calculatedSeriesEntries.isNotEmpty()) {
                val allDates = calculatedSeriesEntries.flatMap { (_, pairs) -> pairs.map { it.first } }.distinct()
                xToDatesMapForStore = allDates.associateBy { it.toEpochDay().toFloat() }
            } else {
                xToDatesMapForStore = emptyMap()
            }
        }

        // Second check: if after processing, no series are available to plot (e.g., data existed but not for selected types).
        if (seriesEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f) // Takes up available vertical space
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val message = if (lineTypesToActuallyPlot.isEmpty() && effectiveShowTypeFilterRow) {
                    // Filter row is visible, but either nothing is selected or no data for selection.
                    if (measurementsWithValues.isEmpty() && currentSelectedTypeIntIds.isNotEmpty()) stringResource(R.string.line_chart_no_data_for_selected_types)
                    else if (measurementsWithValues.isEmpty()) stringResource(R.string.line_chart_no_data_to_display)
                    else stringResource(R.string.line_chart_please_select_types)
                } else if (lineTypesToActuallyPlot.isEmpty()) {
                    // Filter not visible and no types to plot (likely because default is empty or no plottable types overall).
                    if (allAvailableMeasurementTypes.none { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) })
                        stringResource(R.string.line_chart_no_plottable_types)
                    else stringResource(R.string.line_chart_no_data_or_types_to_select)
                } else if (measurementsWithValues.isEmpty()){ // Types selected, but no data entries at all.
                    stringResource(R.string.line_chart_no_data_to_display)
                }
                else { // Types selected, data exists, but not for these specific types.
                    stringResource(R.string.line_chart_no_data_for_selected_types)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Column // Exits the Column Composable early
        }

        val seriesEntriesForStartAxis = remember(seriesEntries) {
            seriesEntries.filter { (type, _) ->
                !type.isOnRightYAxis
            }
        }
        val typeColorsForStartAxis = remember(seriesEntriesForStartAxis) {
            seriesEntriesForStartAxis.map { (type, _) ->
                if (type.color != 0) Color(type.color) else Color.Gray
            }
        }

        val seriesEntriesForEndAxis = remember(seriesEntries) {
            seriesEntries.filter { (type, _) ->
                type.isOnRightYAxis
            }
        }
        val typeColorsForEndAxis = remember(seriesEntriesForEndAxis) {
            seriesEntriesForEndAxis.map { (type, _) ->
                if (type.color != 0) Color(type.color) else Color.Gray
            }
        }

        val modelProducer = remember { CartesianChartModelProducer() }

        LaunchedEffect(seriesEntriesForStartAxis, seriesEntriesForEndAxis, xToDatesMapForStore) {
            if (seriesEntriesForStartAxis.isNotEmpty() || seriesEntriesForEndAxis.isNotEmpty()) {
                modelProducer.runTransaction {
                    if (seriesEntriesForStartAxis.isNotEmpty()) {
                        lineSeries {
                            seriesEntriesForStartAxis.forEach { (_, sortedDateValuePairs) ->
                                val xValues = sortedDateValuePairs.map { it.first.toEpochDay().toFloat() }
                                val yValues = sortedDateValuePairs.map { it.second }
                                if (xValues.isNotEmpty()) {
                                    series(x = xValues, y = yValues)
                                }
                            }
                        }
                    }

                    if (seriesEntriesForEndAxis.isNotEmpty()) {
                        lineSeries {
                            seriesEntriesForEndAxis.forEach { (_, sortedDateValuePairs) ->
                                val xValues = sortedDateValuePairs.map { it.first.toEpochDay().toFloat() }
                                val yValues = sortedDateValuePairs.map { it.second }
                                if (xValues.isNotEmpty()) {
                                    series(x = xValues, y = yValues)
                                }
                            }
                        }
                    }
                    extras { it[X_TO_DATE_MAP_KEY] = xToDatesMapForStore }
                }
            } else {
                modelProducer.runTransaction {
                    lineSeries {}
                    lineSeries {}
                    extras { it.remove(X_TO_DATE_MAP_KEY) }
                }
            }
        }

        val scrollState = rememberVicoScrollState()
        val zoomState = rememberVicoZoomState(
            zoomEnabled = true,
            initialZoom = Zoom.Content, // Zoom to fit content initially
        )

        val xAxisValueFormatter = rememberXAxisValueFormatter(X_TO_DATE_MAP_KEY, DATE_FORMATTER)
        val yAxisValueFormatter = CartesianValueFormatter.decimal() // Standard decimal formatting for Y-axis

        // Conditionally create X-axis; hide if a specific targetMeasurementTypeId is set (for cleaner detail view).
        val xAxis = if (targetMeasurementTypeId == null) {
            HorizontalAxis.rememberBottom(
                valueFormatter = xAxisValueFormatter,
                guideline = null, // No guideline for X-axis for cleaner look
            )
        } else {
            null // Hide X-axis when showing a single, targeted measurement type
        }

        val rangeProvider = remember {
            object : CartesianLayerRangeProvider {
                override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                    val r = maxY - minY
                    return if (r == 0.0) minY - 1.0 else floor(minY - 0.1 * r)
                }
                override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                    val r = maxY - minY
                    return if (r == 0.0) maxY + 1.0 else ceil(maxY + 0.1 * r)
                }
            }
        }

        // Conditionally create Y-axis.
        val startYAxis = if (showYAxis) {
            VerticalAxis.rememberStart(valueFormatter = yAxisValueFormatter)
        } else { null }

        val endYAxis = if (showYAxis) {
            VerticalAxis.rememberEnd(valueFormatter = yAxisValueFormatter)
        } else { null }

        val lineProviderForStartAxis = remember(seriesEntriesForStartAxis, typeColorsForStartAxis) {
            LineCartesianLayer.LineProvider.series(
                seriesEntriesForStartAxis.mapIndexedNotNull { index, _ ->
                    if (index < typeColorsForStartAxis.size) {
                        createLineSpec(
                            color = typeColorsForStartAxis[index],
                            statisticsMode = targetMeasurementTypeId != null,
                            showPoints = showDataPointsSetting
                        )
                    } else null
                }
            )
        }
        val lineLayerForStartAxis = if (seriesEntriesForStartAxis.isNotEmpty()) {
            rememberLineCartesianLayer(
                lineProvider = lineProviderForStartAxis,
                verticalAxisPosition = Axis.Position.Vertical.Start,
                rangeProvider = rangeProvider
            )
        } else {
            null
        }

        val lineProviderForEndAxis = remember(seriesEntriesForEndAxis, typeColorsForEndAxis) {
            LineCartesianLayer.LineProvider.series(
                seriesEntriesForEndAxis.mapIndexedNotNull { index, _ ->
                    if (index < typeColorsForEndAxis.size) {
                        createLineSpec(
                            color = typeColorsForEndAxis[index],
                            statisticsMode = targetMeasurementTypeId != null,
                            showPoints = showDataPointsSetting
                        )
                    } else null
                }
            )
        }
        val lineLayerForEndAxis = if (seriesEntriesForEndAxis.isNotEmpty()) {
            rememberLineCartesianLayer(
                lineProvider = lineProviderForEndAxis,
                verticalAxisPosition = Axis.Position.Vertical.End,
                rangeProvider = rangeProvider
                )
        } else {
            null
        }

        val layers : List<LineCartesianLayer> = remember(lineLayerForStartAxis, lineLayerForEndAxis) {
            listOfNotNull(lineLayerForStartAxis, lineLayerForEndAxis)
        }

        val chart = rememberCartesianChart(
            layers = layers.toTypedArray(),
            startAxis = startYAxis, // left Y-axis
            bottomAxis = xAxis,  // X-axis
            endAxis = endYAxis, // right Y-axis
            marker = rememberMarker() // Interactive marker for data points
        )

        CartesianChartHost(
            chart = chart,
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // Occupy available vertical space
            scrollState = scrollState,
            zoomState = zoomState
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
@Composable
fun provideFilterTopBarAction(
    sharedViewModel: SharedViewModel,
    screenContextName: String?
): SharedViewModel.TopBarAction? {

    if (screenContextName == null) return null // Context name is essential for settings persistence

    val userSettingsRepository = sharedViewModel.userSettingRepository
    val scope = rememberCoroutineScope()

    // --- Time Range Setting ---
    val targetTimeRangeKeyName = "${screenContextName}${TIME_RANGE_SUFFIX}"
    val defaultTimeRangeValue = TimeRangeFilter.ALL_DAYS.name // Default if no setting found
    val currentPersistedTimeRangeName by userSettingsRepository.observeSetting(targetTimeRangeKeyName, defaultTimeRangeValue)
        .collectAsState(initial = defaultTimeRangeValue)
    val activeTimeRange = remember(currentPersistedTimeRangeName) {
        TimeRangeFilter.entries.find { it.name == currentPersistedTimeRangeName } ?: TimeRangeFilter.ALL_DAYS
    }

    // --- Show MeasurementTypeFilterRow Setting ---
    val targetShowFilterRowKeyName = "${screenContextName}${SHOW_TYPE_FILTER_ROW_SUFFIX}"
    // The default value here is for the TopBarAction's initial state if no setting exists.
    // LineChart itself uses `showFilterControls` passed to it as its initial display default.
    val defaultShowFilterRowForTopBar = true
    val currentShowFilterRowSetting by userSettingsRepository.observeSetting(targetShowFilterRowKeyName, defaultShowFilterRowForTopBar)
        .collectAsState(initial = defaultShowFilterRowForTopBar)

    var showMenuState by remember { mutableStateOf(false) } // Controls dropdown menu visibility

    return SharedViewModel.TopBarAction(
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
                        scope.launch {
                            userSettingsRepository.saveSetting(targetTimeRangeKeyName, timeRange.name)
                        }
                        showMenuState = false // Close menu after selection
                    }
                )
            }

            // The option to toggle the measurement type filter row is not shown for the Statistics screen,
            // as it has its own dedicated type selection mechanism.
            if (screenContextName != UserPreferenceKeys.STATISTICS_SCREEN_CONTEXT) {
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
                            userSettingsRepository.saveSetting(
                                targetShowFilterRowKeyName,
                                !currentShowFilterRowSetting // Toggle the setting
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
 * Remembers a [CartesianValueFormatter] for the X-axis that converts epoch day float values
 * back to formatted date strings using a provided map.
 *
 * @param xToDateMapKey The [ExtraStore.Key] used to retrieve the date mapping from the chart model.
 * @param dateFormatter The [DateTimeFormatter] to format the [LocalDate].
 * @return A memoized [CartesianValueFormatter].
 */
@Composable
private fun rememberXAxisValueFormatter(
    xToDateMapKey: ExtraStore.Key<Map<Float, LocalDate>>,
    dateFormatter: DateTimeFormatter
): CartesianValueFormatter = remember(xToDateMapKey, dateFormatter) {
    CartesianValueFormatter { context, value, _ -> // `value` is the x-axis value (epochDay as float)
        val chartModel = context.model
        val xToDatesMap = chartModel.extraStore[xToDateMapKey] // Retrieve map from chart model
        val xKey = value.toFloat()

        (xToDatesMap[xKey] ?: LocalDate.ofEpochDay(value.toLong()))
            .format(dateFormatter)
    }
}

/**
 * Creates a [LineCartesianLayer.Line] specification for a single series in the chart.
 *
 * @param color The color of the line and points.
 * @param statisticsMode If true, an area fill is added below the line, and points are hidden.
 *                       This is typically used when `targetMeasurementTypeId` is set.
 * @param showPoints If true, points are displayed on the line (unless in statisticsMode).
 * @return A configured [LineCartesianLayer.Line].
 */
private fun createLineSpec(color: Color, statisticsMode : Boolean, showPoints: Boolean): LineCartesianLayer.Line {
    val lineStroke = LineCartesianLayer.LineStroke.Continuous(
        thicknessDp = 2f,
    )

    val lineFill = LineCartesianLayer.LineFill.single( // Defines the color of the line itself
        fill = Fill(color.toArgb())
    )

    return LineCartesianLayer.Line(
        fill = lineFill,
        stroke = lineStroke,
        // Area fill is shown in statistics mode (e.g., when a single type is focused)
        areaFill = if (statisticsMode) LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.2f).toArgb())) else null,
        // Points on the line are shown unless in statistics mode
        pointProvider = if (showPoints && !statisticsMode) {
            LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.point(ShapeComponent(fill(color.copy(alpha = 0.7f)), CorneredShape.Pill), 6.dp)
            )
        } else null,
        // dataLabel = null,         // No data labels on points
        pointConnector = LineCartesianLayer.PointConnector.cubic()
    )
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
 * Remembers a [TimeRangeFilter] value that is persisted in [UserSettingsRepository]
 * based on the provided [screenContextName].
 *
 * @param screenContextName The unique context name for this setting.
 * @param userSettingsRepository The repository to observe and save the setting.
 * @param defaultFilter The default [TimeRangeFilter] to use if no setting is found.
 * @return A [State] holding the current [TimeRangeFilter].
 */
@Composable
fun rememberContextualTimeRangeFilter(
    screenContextName: String,
    userSettingsRepository: UserSettingsRepository,
    defaultFilter: TimeRangeFilter = TimeRangeFilter.ALL_DAYS
): State<TimeRangeFilter> {
    val timeRangeKeyName = remember(screenContextName) { "${screenContextName}${TIME_RANGE_SUFFIX}" }
    val persistedTimeRangeName by userSettingsRepository.observeSetting(timeRangeKeyName, defaultFilter.name)
        .collectAsState(initial = defaultFilter.name)

    // Using `derivedStateOf` might be slightly more optimal if TimeRangeFilter.entries could change,
    // but for enums, `remember` with `persistedTimeRangeName` as key is fine.
    return remember(persistedTimeRangeName) {
        mutableStateOf(
            TimeRangeFilter.entries.find { it.name == persistedTimeRangeName } ?: defaultFilter
        )
    }
}

/**
 * Remembers a set of selected measurement type IDs (as strings) that is persisted
 * in [UserSettingsRepository] based on the provided [screenContextName].
 *
 * @param screenContextName The unique context name for this setting.
 * @param userSettingsRepository The repository to observe and save the setting.
 * @param defaultSelectedTypeIds The default set of type IDs to use if no setting is found.
 * @return A [State] holding the current [Set] of selected type IDs (strings).
 */
@Composable
fun rememberContextualSelectedTypeIds(
    screenContextName: String,
    userSettingsRepository: UserSettingsRepository,
    defaultSelectedTypeIds: Set<String> = emptySet()
): State<Set<String>> {
    val selectedTypesKeyName = remember(screenContextName) { "${screenContextName}${SELECTED_TYPES_SUFFIX}" }
    // Directly collect the flow as state.
    return userSettingsRepository.observeSetting(selectedTypesKeyName, defaultSelectedTypeIds)
        .collectAsState(initial = defaultSelectedTypeIds)
}

/**
 * Remembers a boolean setting value that is persisted in [UserSettingsRepository]
 * based on the provided [screenContextName] and [settingSuffix].
 *
 * @param screenContextName The unique context name for this setting.
 * @param settingSuffix The specific suffix for this boolean setting (e.g., "_show_filter").
 * @param userSettingsRepository The repository to observe and save the setting.
 * @param defaultValue The default boolean value to use if no setting is found.
 * @return A [State] holding the current boolean value.
 */
@Composable
fun rememberContextualBooleanSetting(
    screenContextName: String,
    settingSuffix: String,
    userSettingsRepository: UserSettingsRepository,
    defaultValue: Boolean
): State<Boolean> {
    val keyName = remember(screenContextName, settingSuffix) { "${screenContextName}${settingSuffix}" }
    // Directly collect the flow as state.
    return userSettingsRepository.observeSetting(keyName, defaultValue)
        .collectAsState(initial = defaultValue)
}
