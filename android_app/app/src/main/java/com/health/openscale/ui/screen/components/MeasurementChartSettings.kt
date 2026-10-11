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

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.health.openscale.R
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.ui.shared.SharedViewModel
import java.util.Date

internal const val TIME_RANGE_SUFFIX = "_time_range"
internal const val AGGREGATION_LEVEL_SUFFIX = "_aggregation_level"
internal const val CUSTOM_START_DATE_MILLIS_SUFFIX = "_custom_start_date_millis"
internal const val CUSTOM_END_DATE_MILLIS_SUFFIX = "_custom_end_date_millis"
internal const val SELECTED_TYPES_SUFFIX = "_selected_types"
internal const val SHOW_TYPE_FILTER_ROW_SUFFIX = "_show_type_filter_row"

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
    val context by sharedViewModel.filterContext(screenContextName, TIME_RANGE_SUFFIX)
        .collectAsState(initial = screenContextName)
    val timeRangeKey = remember(context) { "${context}${TIME_RANGE_SUFFIX}" }
    val persistedTimeRangeName by sharedViewModel
        .observeSetting(timeRangeKey, defaultFilter.name)
        .collectAsState(initial = defaultFilter.name)

    val activeTimeRange = remember(persistedTimeRangeName) {
        TimeRangeFilter.entries.find { it.name == persistedTimeRangeName } ?: defaultFilter
    }

    val customStartKey = remember(context) { "${context}${CUSTOM_START_DATE_MILLIS_SUFFIX}" }
    val customStartMillis by sharedViewModel.observeSetting(customStartKey, 0L).collectAsState(initial = 0L)

    val customEndKey = remember(context) { "${context}${CUSTOM_END_DATE_MILLIS_SUFFIX}" }
    val customEndMillis by sharedViewModel.observeSetting(customEndKey, 0L).collectAsState(initial = 0L)

    return remember(activeTimeRange, customStartMillis, customEndMillis) {
        val (start, end) = activeTimeRange.resolveBounds(customStartMillis, customEndMillis)
        mutableStateOf(Triple(activeTimeRange, start, end))
    }
}

@Composable
internal fun rememberResolvedAggregationLevel(
    screenContextName: String,
    sharedViewModel: SharedViewModel,
    defaultLevel: AggregationLevel = AggregationLevel.NONE
): State<AggregationLevel> {
    val context by sharedViewModel.filterContext(screenContextName, AGGREGATION_LEVEL_SUFFIX)
        .collectAsState(initial = screenContextName)
    val key = remember(context) { "${context}${AGGREGATION_LEVEL_SUFFIX}" }
    val persisted by sharedViewModel
        .observeSetting(key, defaultLevel.name)
        .collectAsState(initial = defaultLevel.name)

    return remember(persisted) {
        mutableStateOf(
            AggregationLevel.entries.find { it.name == persisted } ?: defaultLevel
        )
    }
}

/**
 * Creates a human-readable title for the current time filter state.
 */
@Composable
internal fun rememberFilterTitle(
    activeFilter: TimeRangeFilter,
    startTimeMillis: Long?,
    endTimeMillis: Long?
): String {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateFormat(context) }

    return when {
        activeFilter != TimeRangeFilter.CUSTOM || startTimeMillis == null ->
            activeFilter.getDisplayName(context)

        // An open end is the point of a fixed starting date, so name it rather than falling back
        // to the generic "Custom Days".
        endTimeMillis == null ->
            stringResource(
                R.string.time_range_custom_since,
                dateFormat.format(Date(startTimeMillis))
            )

        else -> stringResource(
            R.string.time_range_custom_from_to,
            dateFormat.format(Date(startTimeMillis)),
            dateFormat.format(Date(endTimeMillis))
        )
    }
}

/**
 * Remembers a set of selected measurement type IDs (as strings) that is persisted
 * in [SettingsFacade] based on the provided [screenContextName].
 *
 * @param screenContextName The unique context name for this setting.
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
 * @param settingSuffix The specific suffix for this boolean setting.
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