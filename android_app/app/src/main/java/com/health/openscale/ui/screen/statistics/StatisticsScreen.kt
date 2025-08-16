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
package com.health.openscale.ui.screen.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.database.UserPreferenceKeys
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.screen.EnrichedMeasurement
import com.health.openscale.ui.screen.SharedViewModel
import com.health.openscale.ui.screen.components.LineChart
import com.health.openscale.ui.screen.components.provideFilterTopBarAction
import com.health.openscale.ui.screen.components.rememberContextualTimeRangeFilter
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Data class to hold calculated statistics for a specific measurement type.
 *
 * @property minValue The minimum value recorded for the measurement type in the selected time range.
 * @property maxValue The maximum value recorded.
 * @property averageValue The average value.
 * @property firstValue The first recorded value in the time range.
 * @property firstValueDate The date of the first recorded value.
 * @property lastValue The last recorded value in the time range.
 * @property lastValueDate The date of the last recorded value.
 * @property difference The difference between the last and first value.
 */
data class MeasurementStatistics(
    val minValue: Float?,
    val maxValue: Float?,
    val averageValue: Float?,
    val firstValue: Float?,
    val firstValueDate: LocalDate?,
    val lastValue: Float?,
    val lastValueDate: LocalDate?,
    val difference: Float?
)

/**
 * Composable screen that displays statistics for various enabled measurement types.
 *
 * This screen fetches time-filtered measurement data from the [SharedViewModel],
 * calculates statistics for each relevant measurement type, and displays them
 * in individual [StatisticCard] composables. It also provides a filter action
 * in the top bar to change the time range for the statistics.
 *
 * @param sharedViewModel The ViewModel shared across screens, providing measurement data,
 *                        measurement types, and handling top bar configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(sharedViewModel: SharedViewModel) {

    val uiSelectedTimeRange by rememberContextualTimeRangeFilter(
        screenContextName = UserPreferenceKeys.STATISTICS_SCREEN_CONTEXT,
        userSettingsRepository = sharedViewModel.userSettingRepository
    )

    // Fetch time-filtered data from the ViewModel.
    val timeFilteredData by sharedViewModel.getTimeFilteredEnrichedMeasurements(uiSelectedTimeRange)
        .collectAsState(initial = emptyList())
    // Use the collected time-filtered data for statistics calculation.
    val measurementsForStatistics = timeFilteredData

    val allAvailableMeasurementTypes by sharedViewModel.measurementTypes.collectAsState()
    val isLoadingData by sharedViewModel.isBaseDataLoading.collectAsState()

    // Provide the filter action for the top bar. This action changes the filter in UserSettingRepository.
    val filterAction = provideFilterTopBarAction(
        sharedViewModel = sharedViewModel,
        screenContextName = UserPreferenceKeys.STATISTICS_SCREEN_CONTEXT
    )

    val statisticsScreenTitle = stringResource(id = R.string.route_title_statistics)
    val noRelevantMeasurementTypesMessage = stringResource(id = R.string.statistics_no_relevant_types)


    LaunchedEffect(filterAction, statisticsScreenTitle) {
        sharedViewModel.setTopBarTitle(statisticsScreenTitle)
        val actions = mutableListOf<SharedViewModel.TopBarAction>()
        filterAction?.let { actions.add(it) }
        sharedViewModel.setTopBarActions(actions)
    }

    // Filter for measurement types that are enabled and have a numeric input type (Float or Int).
    val relevantTypesForStatsDisplay = remember(allAvailableMeasurementTypes) {
        allAvailableMeasurementTypes.filter { type ->
            type.isEnabled && (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoadingData && measurementsForStatistics.isEmpty()) {
            // Show a loading indicator if data is loading and no measurements are available yet.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (measurementsForStatistics.isEmpty() && relevantTypesForStatsDisplay.isEmpty()) {
            // Show a message if no relevant measurement types are configured or no data is present.
            // This condition is refined to also check relevantTypesForStatsDisplay.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp), contentAlignment = Alignment.Center
            ) {
                Text(noRelevantMeasurementTypesMessage)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                items(relevantTypesForStatsDisplay, key = { it.id }) { measurementType ->
                    // Filter measurements relevant to the current measurement type.
                    val measurementsForThisType = remember(measurementsForStatistics, measurementType) {
                        measurementsForStatistics.filter { enrichedMeasurement ->
                            enrichedMeasurement.measurementWithValues.values.any { it.type.id == measurementType.id }
                        }
                    }

                    // Calculate statistics for the current measurement type.
                    val statistics = remember(measurementsForThisType, measurementType) {
                        calculateStatisticsForType(measurementsForThisType, measurementType)
                    }

                    // Display the statistic card if there are measurements for this type.
                    if (measurementsForThisType.isNotEmpty()) {
                        StatisticCard(
                            sharedViewModel = sharedViewModel,
                            measurementType = measurementType,
                            statistics = statistics,
                            screenContextForChart = UserPreferenceKeys.STATISTICS_SCREEN_CONTEXT
                        )
                    }
                }
            }
        }
    }
}

/**
 * Calculates statistics for a given list of enriched measurements and a target measurement type.
 *
 * It extracts numeric values for the target type, sorts them by time, and then
 * computes min, max, average, first value, last value, and the difference between
 * the first and last values.
 *
 * @param enrichedMeasurements The list of [EnrichedMeasurement] objects to process.
 * @param targetType The [MeasurementType] for which to calculate statistics.
 * @return [MeasurementStatistics] containing the calculated values.
 */
fun calculateStatisticsForType(
    enrichedMeasurements: List<EnrichedMeasurement>,
    targetType: MeasurementType
): MeasurementStatistics {
    // Map enriched measurements to pairs of (value, timestamp) for the target type.
    val relevantValuesWithTime: List<Pair<Float, Long>> = enrichedMeasurements.mapNotNull { enrichedMeasurement ->
        val measurementTimestamp = enrichedMeasurement.measurementWithValues.measurement.timestamp

        // Find the MeasurementValue object for the targetType.
        val measurementValueObject = enrichedMeasurement.measurementWithValues.values.find { it.type.id == targetType.id }

        if (measurementValueObject == null) {
            return@mapNotNull null
        }

        // Extract the numerical value from the MeasurementValue object.
        val floatValue: Float? = when (targetType.inputType) {
            InputFieldType.FLOAT -> measurementValueObject.value.floatValue
            InputFieldType.INT -> measurementValueObject.value.intValue?.toFloat()
            else -> null // Other types are not considered for these statistics.
        }

        if (floatValue != null) {
            Pair(floatValue, measurementTimestamp)
        } else {
            null
        }
    }.sortedBy { it.second } // Sort by timestamp.

    if (relevantValuesWithTime.isEmpty()) {
        return MeasurementStatistics(null, null, null, null, null, null, null, null)
    }

    val floatValuesOnly = relevantValuesWithTime.map { it.first }

    val minValue = floatValuesOnly.minOrNull()
    val maxValue = floatValuesOnly.maxOrNull()
    val averageValue = if (floatValuesOnly.isNotEmpty()) floatValuesOnly.average().toFloat() else null

    val firstEntry = relevantValuesWithTime.firstOrNull()
    val lastEntry = relevantValuesWithTime.lastOrNull()

    val firstValue = firstEntry?.first
    val firstValueDate = firstEntry?.second?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    val lastValue = lastEntry?.first
    val lastValueDate = lastEntry?.second?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    val difference = if (firstValue != null && lastValue != null) {
        lastValue - firstValue
    } else {
        null
    }

    return MeasurementStatistics(
        minValue = minValue,
        maxValue = maxValue,
        averageValue = averageValue,
        firstValue = firstValue,
        firstValueDate = firstValueDate,
        lastValue = lastValue,
        lastValueDate = lastValueDate,
        difference = difference
    )
}

/**
 * Composable that displays a card with statistics for a single measurement type.
 *
 * The card includes the measurement type's name and icon, min/max/average values,
 * a line chart showing the trend, and the first value, last value, and the
 * difference between them.
 *
 * @param sharedViewModel The [SharedViewModel] instance.
 * @param measurementType The [MeasurementType] for which statistics are displayed.
 * @param statistics The calculated [MeasurementStatistics] for this type.
 * @param screenContextForChart A context name string used for the embedded [LineChart].
 */
@Composable
fun StatisticCard(
    sharedViewModel: SharedViewModel,
    measurementType: MeasurementType,
    statistics: MeasurementStatistics,
    screenContextForChart: String
) {
    val unitSymbol = remember(measurementType.unit) { measurementType.unit.displayName }
    // Decimal format for displaying values.
    val decimalFormat = remember { DecimalFormat("#,##0.0#") }

    // Helper function to format a nullable Float value with its unit.
    fun formatValueWithUnit(value: Float?, default: String = "-"): String {
        return value?.let { "${decimalFormat.format(it)} $unitSymbol" } ?: default
    }

    // Helper function to format a nullable Float value for the difference display (without unit initially).
    fun formatValueForDiff(value: Float?, default: String = "-"): String {
        return value?.let { decimalFormat.format(it) } ?: default
    }

    val contentDescIncrease = stringResource(id = R.string.statistics_content_desc_increase)
    val contentDescDecrease = stringResource(id = R.string.statistics_content_desc_decrease)
    val contentDescNoChange = stringResource(id = R.string.statistics_content_desc_no_change)

    val statMinLabel = stringResource(id = R.string.statistics_label_min)
    val statMaxLabel = stringResource(id = R.string.statistics_label_max)
    val statAvgLabel = stringResource(id = R.string.statistics_label_average)


    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // --- TOP ROW: Icon, Name, Min/Max/Avg ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Icon and Name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val iconMeasurementType = remember(measurementType.icon) { measurementType.icon }

                    RoundMeasurementIcon(
                        icon = iconMeasurementType,
                        backgroundTint = Color(measurementType.color),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = measurementType.getDisplayName(LocalContext.current),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                // Min/Max/Avg Values
                Column(horizontalAlignment = Alignment.End) {
                    Text("$statMinLabel: ${formatValueWithUnit(statistics.minValue)}", style = MaterialTheme.typography.bodySmall)
                    Text("$statMaxLabel: ${formatValueWithUnit(statistics.maxValue)}", style = MaterialTheme.typography.bodySmall)
                    Text("$statAvgLabel: ${formatValueWithUnit(statistics.averageValue)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- MIDDLE: LineChart ---
            LineChart(
                sharedViewModel = sharedViewModel,
                screenContextName = screenContextForChart,
                showFilterControls = false, // Filter controls are global for the screen
                targetMeasurementTypeId = measurementType.id,
                showYAxis = false, // Keep it compact
                showFilterTitle = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp) // Fixed height for the chart
            )

            Spacer(modifier = Modifier.height(16.dp)) // Space after the chart

            // --- BOTTOM ROW: First Value (left), DIFFERENCE (center, optional), Last Value (right) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // First Value (left aligned)
                Text(
                    text = formatValueWithUnit(statistics.firstValue),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )

                // Difference (center aligned, shown if available)
                if (statistics.difference != null) {
                    val diffValue = statistics.difference
                    val diffPrefix = if (diffValue > 0) "+" else "" // Add "+" for positive differences

                    // Determine icon and content description based on the difference value
                    val (diffIcon, description) = when {
                        diffValue > 0 -> Icons.Filled.ArrowUpward to contentDescIncrease
                        diffValue < 0 -> Icons.Filled.ArrowDownward to contentDescDecrease
                        else -> Icons.Filled.Remove to contentDescNoChange
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = diffIcon,
                            contentDescription = description,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            // Display difference with sign and unit
                            text = "$diffPrefix${formatValueForDiff(diffValue)} $unitSymbol",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // If no difference, occupy the space to maintain layout.
                    Spacer(Modifier.weight(1f))
                }

                // Last Value (right aligned)
                Text(
                    text = formatValueWithUnit(statistics.lastValue),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
