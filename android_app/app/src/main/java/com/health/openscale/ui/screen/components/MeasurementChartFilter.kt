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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Provides a [TopBarAction] for filtering the line chart.
 * Includes time range selection, aggregation level selection,
 * and toggling the measurement type filter row.
 *
 * @param sharedViewModel The [SharedViewModel] to access settings.
 * @param screenContextName The context name to scope the filter settings. If null, no action is provided.
 * @return A [TopBarAction] configuration for the filter menu, or null if context is not provided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun provideFilterTopBarAction(
    sharedViewModel: SharedViewModel,
    screenContextName: String?
): TopBarAction? {
    if (screenContextName == null) return null

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // --- Time range state ---
    val targetTimeRangeKeyName = "${screenContextName}${TIME_RANGE_SUFFIX}"
    val currentPersistedTimeRangeName by sharedViewModel
        .observeSetting(targetTimeRangeKeyName, TimeRangeFilter.ALL_DAYS.name)
        .collectAsState(initial = TimeRangeFilter.ALL_DAYS.name)
    val activeTimeRange = remember(currentPersistedTimeRangeName) {
        TimeRangeFilter.entries.find { it.name == currentPersistedTimeRangeName }
            ?: TimeRangeFilter.ALL_DAYS
    }

    // --- Aggregation level state ---
    // Only shown for Graph, Table, and Statistics screens
    val showAggregation = screenContextName in listOf(
        SettingsPreferenceKeys.OVERVIEW_SCREEN_CONTEXT,
        SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT,
        SettingsPreferenceKeys.TABLE_SCREEN_CONTEXT
    )
    val activeAggregationLevel by sharedViewModel
        .observeAggregationLevel(screenContextName)
        .collectAsState(initial = AggregationLevel.NONE)

    // --- Type filter row state ---
    val targetShowFilterRowKeyName = "${screenContextName}${SHOW_TYPE_FILTER_ROW_SUFFIX}"
    val currentShowFilterRowSetting by sharedViewModel
        .observeSetting(targetShowFilterRowKeyName, true)
        .collectAsState(initial = true)

    var showMenuState by rememberSaveable { mutableStateOf(false) }
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
                ) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        ) { DateRangePicker(state = dateRangePickerState) }
    }

    return TopBarAction(
        icon = Icons.Default.FilterList,
        contentDescription = stringResource(R.string.content_description_filter_chart_data),
        onClick = { showMenuState = !showMenuState }
    ) {
        DropdownMenu(expanded = showMenuState, onDismissRequest = { showMenuState = false }) {

            // --- Section 1: Time range ---
            Text(
                text = stringResource(R.string.filter_section_time_range),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            TimeRangeFilter.entries.forEach { timeRange ->
                DropdownMenuItem(
                    text = { Text(timeRange.getDisplayName(context)) },
                    leadingIcon = {
                        if (activeTimeRange == timeRange) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(
                                    R.string.content_description_time_range_selected,
                                    timeRange.getDisplayName(context)
                                )
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        showMenuState = false
                        if (timeRange == TimeRangeFilter.CUSTOM) {
                            showDateRangePicker = true
                        } else {
                            scope.launch {
                                sharedViewModel.saveSetting(targetTimeRangeKeyName, timeRange.name)
                            }
                        }
                    }
                )
            }

            // --- Section 2: Aggregation (only for Graph, Table, Statistics) ---
            if (showAggregation) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.filter_section_aggregation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                AggregationLevel.entries.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.getDisplayName(context)) },
                        leadingIcon = {
                            if (activeAggregationLevel == level) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(
                                        R.string.content_description_aggregation_selected,
                                        level.getDisplayName(context)
                                    )
                                )
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            showMenuState = false
                            scope.launch {
                                sharedViewModel.saveAggregationLevel(screenContextName, level)
                            }
                        }
                    )
                }
            }

            // --- Section 3: Type filter row toggle (not for Statistics) ---
            if (screenContextName != SettingsPreferenceKeys.STATISTICS_SCREEN_CONTEXT) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_item_measurement_filter)) },
                    leadingIcon = {
                        if (currentShowFilterRowSetting) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.content_description_measurement_filter_visible)
                            )
                        } else {
                            Icon(
                                Icons.Filled.CheckBoxOutlineBlank,
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
                        showMenuState = false
                    }
                )
            }
        }
    }
}

/**
 * Remembers and calculates the data needed for the [PeriodChart].
 * Groups measurements by a dynamic time unit (day, week, month, year)
 * based on the total time span of the provided data.
 *
 * @param measurementsForPeriodChart The list of measurements to be processed.
 * @param uiSelectedTimeRange The currently active time range filter.
 * @return A memoized list of [PeriodDataPoint]s ready for rendering.
 */
@Composable
internal fun rememberPeriodChartData(
    measurementsForPeriodChart: List<MeasurementWithValues>,
    uiSelectedTimeRange: TimeRangeFilter
): List<PeriodDataPoint> {
    return remember(measurementsForPeriodChart, uiSelectedTimeRange) {
        if (measurementsForPeriodChart.isEmpty()) return@remember emptyList()

        val minDate = measurementsForPeriodChart.minOf {
            Instant.ofEpochMilli(it.measurement.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        val maxDate = measurementsForPeriodChart.maxOf {
            Instant.ofEpochMilli(it.measurement.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        }

        val totalDays = ChronoUnit.DAYS.between(minDate, maxDate).toInt()
        val groupingUnit: ChronoUnit
        val intervalSize: Long = 1

        groupingUnit = when {
            totalDays <= 7   -> ChronoUnit.DAYS
            totalDays <= 30  -> ChronoUnit.WEEKS
            totalDays <= 365 -> ChronoUnit.MONTHS
            else             -> ChronoUnit.YEARS
        }

        val allPeriods = mutableListOf<LocalDate>()
        var cursor = when (groupingUnit) {
            ChronoUnit.DAYS   -> minDate
            ChronoUnit.WEEKS  -> minDate.with(DayOfWeek.MONDAY)
            ChronoUnit.MONTHS -> minDate.withDayOfMonth(1)
            else              -> minDate.withDayOfYear(1)
        }

        while (!cursor.isAfter(maxDate)) {
            allPeriods.add(cursor)
            cursor = when (groupingUnit) {
                ChronoUnit.DAYS   -> cursor.plusDays(intervalSize)
                ChronoUnit.WEEKS  -> cursor.plusWeeks(intervalSize)
                ChronoUnit.MONTHS -> cursor.plusMonths(intervalSize)
                else              -> cursor.plusYears(intervalSize)
            }
        }

        while (allPeriods.size < 5) {
            cursor = when (groupingUnit) {
                ChronoUnit.DAYS   -> allPeriods.first().minusDays(intervalSize)
                ChronoUnit.WEEKS  -> allPeriods.first().minusWeeks(intervalSize)
                ChronoUnit.MONTHS -> allPeriods.first().minusMonths(intervalSize)
                else              -> allPeriods.first().minusYears(intervalSize)
            }
            allPeriods.add(0, cursor)
        }

        val grouped = measurementsForPeriodChart.groupBy { mwv ->
            val date = Instant.ofEpochMilli(mwv.measurement.timestamp)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            when (groupingUnit) {
                ChronoUnit.DAYS   -> date
                ChronoUnit.WEEKS  -> date.with(DayOfWeek.MONDAY)
                ChronoUnit.MONTHS -> date.withDayOfMonth(1)
                else              -> date.withDayOfYear(1)
            }
        }

        val locale = Locale.getDefault()
        val labelFormatter: (LocalDate) -> String = { date ->
            when (groupingUnit) {
                ChronoUnit.DAYS   -> date.format(DateTimeFormatter.ofPattern("d LLL", locale))
                ChronoUnit.WEEKS  -> "W${date.get(WeekFields.of(locale).weekOfWeekBasedYear())}"
                ChronoUnit.MONTHS -> date.format(DateTimeFormatter.ofPattern("LLL yy", locale))
                else              -> date.year.toString()
            }
        }

        allPeriods.mapIndexed { index, periodStart ->
            val periodEnd = if (index + 1 < allPeriods.size)
                allPeriods[index + 1].atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else
                maxDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            PeriodDataPoint(
                label = labelFormatter(periodStart),
                count = (grouped[periodStart] ?: emptyList()).size,
                startTimestamp = periodStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                endTimestamp = periodEnd
            )
        }
    }
}