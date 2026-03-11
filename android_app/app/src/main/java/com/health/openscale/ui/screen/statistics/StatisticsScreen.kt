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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.screen.components.MeasurementChart
import com.health.openscale.ui.screen.components.provideFilterTopBarAction
import com.health.openscale.ui.screen.components.rememberAddMeasurementActionButton
import com.health.openscale.ui.screen.components.rememberBluetoothActionButton
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Data class to hold calculated statistics for a specific measurement type.
 */
data class MeasurementStatistics(
    val minValue: Float?,
    val maxValue: Float?,
    val averageValue: Float?,
    val firstValue: Float?,
    val firstValueDate: LocalDate?,
    val lastValue: Float?,
    val lastValueDate: LocalDate?,
    val difference: Float?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
) {
    val allTypes by sharedViewModel.measurementTypes.collectAsState()

    // screenFlow is cached — no second pipeline is built even though MeasurementChart
    // calls the same flow internally for each StatisticCard.
    val statsUiState by sharedViewModel
        .screenFlow(SettingsPreferenceKeys.STATISTICS_SCREEN_CONTEXT)
        .collectAsStateWithLifecycle(initialValue = SharedViewModel.UiState.Loading)

    val bluetoothAction      = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    val addMeasurementAction = rememberAddMeasurementActionButton(sharedViewModel, navController)
    val filterAction         = provideFilterTopBarAction(
        sharedViewModel   = sharedViewModel,
        screenContextName = SettingsPreferenceKeys.STATISTICS_SCREEN_CONTEXT,
    )
    val title               = stringResource(R.string.route_title_statistics)
    val noRelevantTypesMsg  = stringResource(R.string.statistics_no_relevant_types)
    val noDataMsg           = stringResource(R.string.no_data_available)

    // Use Unit as key — filterAction is recreated on every recomposition
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(title)
        sharedViewModel.setTopBarActions(listOfNotNull(bluetoothAction, addMeasurementAction, filterAction))
    }

    val relevantTypes = remember(allTypes) {
        allTypes.filter {
            it.isEnabled &&
                    (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT)
        }
    }

    Column(Modifier.fillMaxSize()) {
        when (val state = statsUiState) {
            is SharedViewModel.UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is SharedViewModel.UiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text      = state.message ?: stringResource(R.string.error_loading_data),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            is SharedViewModel.UiState.Success -> {
                // Unpack AggregatedMeasurement → EnrichedMeasurement for stats calculation.
                // Statistics always operate on the raw enriched values, never on averages.
                val enrichedData: List<EnrichedMeasurement> = remember(state.data) {
                    state.data.map { it.enriched }
                }

                if (enrichedData.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(noDataMsg, textAlign = TextAlign.Center)
                    }
                    return@Column
                }

                if (relevantTypes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(noRelevantTypesMsg, textAlign = TextAlign.Center)
                    }
                    return@Column
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    items(relevantTypes, key = { it.id }) { type ->
                        val measurementsForType = remember(enrichedData, type) {
                            enrichedData.filter { em ->
                                em.measurementWithValues.values.any { it.type.id == type.id }
                            }
                        }
                        if (measurementsForType.isNotEmpty()) {
                            val stats = remember(measurementsForType, type) {
                                calculateStatisticsForType(measurementsForType, type)
                            }
                            StatisticCard(
                                sharedViewModel       = sharedViewModel,
                                measurementType       = type,
                                statistics            = stats,
                                screenContextForChart = SettingsPreferenceKeys.STATISTICS_SCREEN_CONTEXT,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Calculates statistics for a given list of enriched measurements and a target type.
 * This function is unchanged — it already works on [EnrichedMeasurement].
 */
fun calculateStatisticsForType(
    enrichedMeasurements: List<EnrichedMeasurement>,
    targetType: MeasurementType,
): MeasurementStatistics {
    val relevantValuesWithTime: List<Pair<Float, Long>> = enrichedMeasurements.mapNotNull { em ->
        val ts  = em.measurementWithValues.measurement.timestamp
        val mvo = em.measurementWithValues.values.find { it.type.id == targetType.id }
            ?: return@mapNotNull null
        val floatValue: Float? = when (targetType.inputType) {
            InputFieldType.FLOAT -> mvo.value.floatValue
            InputFieldType.INT   -> mvo.value.intValue?.toFloat()
            else                 -> null
        }
        floatValue?.let { it to ts }
    }.sortedBy { it.second }

    if (relevantValuesWithTime.isEmpty()) {
        return MeasurementStatistics(null, null, null, null, null, null, null, null)
    }

    val floatValuesOnly = relevantValuesWithTime.map { it.first }
    val firstEntry      = relevantValuesWithTime.first()
    val lastEntry       = relevantValuesWithTime.last()

    return MeasurementStatistics(
        minValue       = floatValuesOnly.minOrNull(),
        maxValue       = floatValuesOnly.maxOrNull(),
        averageValue   = floatValuesOnly.average().toFloat(),
        firstValue     = firstEntry.first,
        firstValueDate = Instant.ofEpochMilli(firstEntry.second)
            .atZone(ZoneId.systemDefault()).toLocalDate(),
        lastValue      = lastEntry.first,
        lastValueDate  = Instant.ofEpochMilli(lastEntry.second)
            .atZone(ZoneId.systemDefault()).toLocalDate(),
        difference     = lastEntry.first - firstEntry.first,
    )
}

@Composable
fun StatisticCard(
    sharedViewModel: SharedViewModel,
    measurementType: MeasurementType,
    statistics: MeasurementStatistics,
    screenContextForChart: String,
) {
    val unit = remember(measurementType.unit) { measurementType.unit }

    fun fmt(value: Float?, default: String = "-"): String =
        value?.let { LocaleUtils.formatValueForDisplay(it.toString(), unit) } ?: default

    fun fmtDiff(value: Float?, default: String = "-"): String =
        value?.let { LocaleUtils.formatValueForDisplay(it.toString(), unit, includeSign = true) } ?: default

    val contentDescIncrease = stringResource(R.string.statistics_content_desc_increase)
    val contentDescDecrease = stringResource(R.string.statistics_content_desc_decrease)
    val contentDescNoChange = stringResource(R.string.statistics_content_desc_no_change)
    val statMinLabel        = stringResource(R.string.statistics_label_min)
    val statMaxLabel        = stringResource(R.string.statistics_label_max)
    val statAvgLabel        = stringResource(R.string.statistics_label_average)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Icon + Name  /  Min·Max·Avg
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier.weight(1f),
                ) {
                    val iconMeasurementType = remember(measurementType.icon) { measurementType.icon }
                    RoundMeasurementIcon(
                        icon           = iconMeasurementType.resource,
                        backgroundTint = Color(measurementType.color),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text       = measurementType.getDisplayName(LocalContext.current),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("$statMinLabel: ${fmt(statistics.minValue)}", style = MaterialTheme.typography.bodySmall)
                    Text("$statMaxLabel: ${fmt(statistics.maxValue)}", style = MaterialTheme.typography.bodySmall)
                    Text("$statAvgLabel: ${fmt(statistics.averageValue)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Embedded chart — hits the screenFlow cache, no extra pipeline
            MeasurementChart(
                sharedViewModel         = sharedViewModel,
                screenContextName       = screenContextForChart,
                showFilterControls      = false,
                targetMeasurementTypeId = measurementType.id,
                showYAxis               = false,
                showFilterTitle         = false,
                modifier                = Modifier.fillMaxWidth().height(100.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // First value / Difference / Last value
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = fmt(statistics.firstValue),
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )

                if (statistics.difference != null) {
                    val diffValue = statistics.difference
                    val (diffIcon, description) = when {
                        diffValue > 0 -> Icons.Filled.ArrowUpward  to contentDescIncrease
                        diffValue < 0 -> Icons.Filled.ArrowDownward to contentDescDecrease
                        else          -> Icons.Filled.Remove        to contentDescNoChange
                    }
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier              = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector        = diffIcon,
                            contentDescription = description,
                            modifier           = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text  = fmtDiff(diffValue),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Text(
                    text      = fmt(statistics.lastValue),
                    style     = MaterialTheme.typography.bodySmall,
                    modifier  = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}