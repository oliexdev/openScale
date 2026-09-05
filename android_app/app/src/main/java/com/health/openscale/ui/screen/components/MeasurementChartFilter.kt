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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.health.openscale.R
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
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
    val settingsContext by sharedViewModel.filterContext(screenContextName, TIME_RANGE_SUFFIX)
        .collectAsState(initial = screenContextName)
    val isTimeRangeLinked by sharedViewModel.linkedAcrossScreens(TIME_RANGE_SUFFIX)
        .collectAsState(initial = false)
    val isAggregationLinked by sharedViewModel.linkedAcrossScreens(AGGREGATION_LEVEL_SUFFIX)
        .collectAsState(initial = false)
    val targetTimeRangeKeyName = "${settingsContext}${TIME_RANGE_SUFFIX}"
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

    // Read back so reopening the dialog shows the range that is actually in effect.
    val customStartMillis by sharedViewModel
        .observeSetting("${settingsContext}${CUSTOM_START_DATE_MILLIS_SUFFIX}", 0L)
        .collectAsState(initial = 0L)
    val customEndMillis by sharedViewModel
        .observeSetting("${settingsContext}${CUSTOM_END_DATE_MILLIS_SUFFIX}", 0L)
        .collectAsState(initial = 0L)

    if (showDateRangePicker) {
        val dateRangePickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = customStartMillis.takeIf { it > 0L }
                ?.let(::localDayStartToPickerMillis),
            initialSelectedEndDateMillis = customEndMillis.takeIf { it > 0L }
                ?.let(::localDayStartToPickerMillis),
        )
        val selectedStart = dateRangePickerState.selectedStartDateMillis
        val selectedEnd = dateRangePickerState.selectedEndDateMillis

        // Persists the picked range and switches the filter over to it. A null [endMillis] is
        // stored as 0L, which resolveBounds reads as "open end".
        val applyRange: (startMillis: Long, endMillis: Long?) -> Unit = { startMillis, endMillis ->
            showDateRangePicker = false
            scope.launch {
                sharedViewModel.saveSetting(
                    "${settingsContext}${CUSTOM_START_DATE_MILLIS_SUFFIX}",
                    pickerMillisToLocalDayStart(startMillis)
                )
                sharedViewModel.saveSetting(
                    "${settingsContext}${CUSTOM_END_DATE_MILLIS_SUFFIX}",
                    endMillis?.let(::pickerMillisToLocalDayStart) ?: 0L
                )
                sharedViewModel.saveSetting(
                    targetTimeRangeKeyName,
                    TimeRangeFilter.CUSTOM.name
                )
            }
        }

        // Material 3 presents range selection as a full-screen dialog. The range picker has no
        // month arrows - it is one continuously scrolling list of months - so inside a standard
        // dialog only a single month is visible at a time and it reads as if it were stuck.
        Dialog(
            onDismissRequest = { showDateRangePicker = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // The dialog gets its own window, so the activity's edge-to-edge setup does not reach
            // it and the status bar icons would keep their default light tint on a light sheet.
            // Deriving the tint from the sheet's own luminance stays right whichever way the theme
            // was resolved - system, manual override or high contrast.
            val containerColor = DatePickerDefaults.colors().containerColor
            val view = LocalView.current
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view)
                        .isAppearanceLightStatusBars = containerColor.luminance() > 0.5f
                }
            }

            // One continuous sheet: the app bar takes the date picker's own container colour so
            // there is no seam between the bar and the picker header below it.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = containerColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.time_range_custom_dialog_title)) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showDateRangePicker = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel_button)
                                )
                            }
                        },
                        actions = {
                            if (selectedEnd != null) {
                                TextButton(
                                    onClick = {
                                        dateRangePickerState.setSelection(selectedStart, null)
                                    }
                                ) {
                                    Text(stringResource(R.string.time_range_custom_clear_end))
                                }
                            }
                            TextButton(
                                onClick = {
                                    selectedStart?.let { applyRange(it, selectedEnd) }
                                },
                                // A start on its own is a complete choice: "from this day onwards".
                                enabled = selectedStart != null
                            ) { Text(stringResource(R.string.dialog_ok)) }
                        }
                    )

                    DateRangePicker(
                        state = dateRangePickerState,
                        modifier = Modifier.weight(1f),
                        // The title slot sits above the headline and carries the one thing the
                        // picker itself cannot express: what an empty end date means, and how to
                        // get back to it once a date has been chosen.
                        title = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, end = 24.dp, top = 16.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(
                                        if (selectedEnd == null) R.string.time_range_custom_hint_open_end
                                        else R.string.time_range_custom_hint_fixed_end
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    return TopBarAction(
        icon = Icons.Default.FilterList,
        contentDescription = stringResource(R.string.content_description_filter_chart_data),
        onClick = { showMenuState = !showMenuState }
    ) {
        DropdownMenu(expanded = showMenuState, onDismissRequest = { showMenuState = false }) {

            // --- Section 1: Time range ---
            FilterSectionHeader(
                title = stringResource(R.string.filter_section_time_range),
                linked = isTimeRangeLinked,
                linkedDescription = stringResource(R.string.content_description_time_range_linked),
                unlinkedDescription = stringResource(R.string.content_description_time_range_unlinked),
                onLinkedChange = { linked ->
                    scope.launch { sharedViewModel.setLinkedAcrossScreens(TIME_RANGE_SUFFIX, linked) }
                }
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
                FilterSectionHeader(
                    title = stringResource(R.string.filter_section_aggregation),
                    linked = isAggregationLinked,
                    linkedDescription = stringResource(R.string.content_description_aggregation_linked),
                    unlinkedDescription = stringResource(R.string.content_description_aggregation_unlinked),
                    onLinkedChange = { linked ->
                        scope.launch { sharedViewModel.setLinkedAcrossScreens(AGGREGATION_LEVEL_SUFFIX, linked) }
                    }
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

            // --- Section 3: Type filter row toggle (not for Statistics and Insights) ---
            // Insights renders its row unconditionally — it is the only way to pick the type the
            // whole screen is about — so the toggle would be a dead entry there.
            if (screenContextName !in listOf(
                    SettingsPreferenceKeys.STATISTICS_SCREEN_CONTEXT,
                    SettingsPreferenceKeys.INSIGHTS_SCREEN_CONTEXT,
                )
            ) {
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
 * A section heading in the filter menu, carrying the toggle that decides whether the choices
 * below it apply to this screen alone or to every screen.
 *
 * The toggle belongs on the heading rather than in the list: it sets the scope of the entries,
 * it is not one more entry to pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSectionHeader(
    title: String,
    linked: Boolean,
    linkedDescription: String,
    unlinkedDescription: String,
    onLinkedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        val description = if (linked) linkedDescription else unlinkedDescription
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(description) } },
            state = rememberTooltipState()
        ) {
            IconToggleButton(checked = linked, onCheckedChange = onLinkedChange) {
                Icon(
                    imageVector = if (linked) Icons.Default.Link else Icons.Default.LinkOff,
                    contentDescription = description,
                    tint = if (linked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
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

        // Same calendar-week rule the aggregation uses, so a bar and its table row cover the
        // same days and carry the same week number.
        val weekFields   = LocaleUtils.systemWeekFields()
        val firstDayOfWeek = weekFields.firstDayOfWeek

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
            ChronoUnit.WEEKS  -> minDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
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
                ChronoUnit.WEEKS  -> date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
                ChronoUnit.MONTHS -> date.withDayOfMonth(1)
                else              -> date.withDayOfYear(1)
            }
        }

        val locale = Locale.getDefault()
        val labelFormatter: (LocalDate) -> String = { date ->
            when (groupingUnit) {
                ChronoUnit.DAYS   -> date.format(DateTimeFormatter.ofPattern("d LLL", locale))
                ChronoUnit.WEEKS  -> "W${date.get(weekFields.weekOfWeekBasedYear())}"
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

/**
 * Converts a [DateRangePicker] selection into the start of that calendar day in the local zone.
 *
 * The picker reports UTC midnight of the day the user tapped. Stored unchanged, that is 22:00 of
 * the previous day at UTC+2 - so an evening measurement from the day before the chosen start would
 * be swept into the range. Interpreting the value as a bare calendar date and re-anchoring it
 * locally keeps the boundary on the day the user actually picked.
 */
private fun pickerMillisToLocalDayStart(pickerMillis: Long): Long =
    Instant.ofEpochMilli(pickerMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

/** Inverse of [pickerMillisToLocalDayStart], for seeding the picker from a stored range. */
private fun localDayStartToPickerMillis(localMillis: Long): Long =
    Instant.ofEpochMilli(localMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
