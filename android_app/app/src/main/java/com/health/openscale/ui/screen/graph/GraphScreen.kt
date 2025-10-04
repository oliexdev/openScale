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
package com.health.openscale.ui.screen.graph

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.Trend
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.model.ValueWithDifference
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.screen.components.MeasurementChart
import com.health.openscale.ui.screen.components.provideFilterTopBarAction
import com.health.openscale.ui.screen.overview.MeasurementValueRow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graphState by sharedViewModel.graphUiState.collectAsState()
    val allMeasurementsWithValues = remember(graphState) {
        when (graphState) {
            is SharedViewModel.UiState.Success -> (graphState as SharedViewModel.UiState.Success<List<EnrichedMeasurement>>)
                .data
                .map { it.measurementWithValues }
            else -> emptyList()
        }
    }
    val userEvalContext by sharedViewModel.userEvaluationContext.collectAsState()
    val selectedUserId by sharedViewModel.selectedUserId.collectAsState()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var sheetMeasurementId by rememberSaveable { mutableStateOf<Int?>(null) }

    var lastTapId by rememberSaveable { mutableStateOf<Int?>(null) }
    var lastTapAt by rememberSaveable { mutableStateOf(0L) }
    val doubleTapWindowMs = 600L

    val timeFilterAction = provideFilterTopBarAction(
        sharedViewModel = sharedViewModel,
        screenContextName = SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT
    )

    LaunchedEffect(timeFilterAction) {
        sharedViewModel.setTopBarTitle(context.getString(R.string.route_title_graph))
        sharedViewModel.setTopBarActions(listOfNotNull(timeFilterAction))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when (val state = graphState) {
            SharedViewModel.UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is SharedViewModel.UiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message ?: stringResource(R.string.error_loading_data))
                }
            }
            is SharedViewModel.UiState.Success -> {
                if (allMeasurementsWithValues.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_data_available))
                    }
                } else {
                    MeasurementChart(
                        modifier = Modifier.fillMaxSize(),
                        sharedViewModel = sharedViewModel,
                        screenContextName = SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT,
                        showFilterControls = true,
                        showPeriodChart = true,
                        showFilterTitle = true,
                        onPointSelected = { selectedTs ->
                            val result = sharedViewModel.findClosestMeasurement(selectedTs, allMeasurementsWithValues)
                                ?: return@MeasurementChart
                            val (idx, mwv) = result
                            val id = mwv.measurement.id
                            val now = System.currentTimeMillis()

                            if (lastTapId == id && (now - lastTapAt) <= doubleTapWindowMs) {
                                sheetMeasurementId = id
                                lastTapId = null
                                lastTapAt = 0L
                            } else {
                                lastTapId = id
                                lastTapAt = now
                            }
                        }
                    )
                }
            }
        }
    }

    val sheetMeasurement = remember(sheetMeasurementId, allMeasurementsWithValues) {
        allMeasurementsWithValues.firstOrNull { it.measurement.id == sheetMeasurementId }
    }

    if (sheetMeasurementId != null && sheetMeasurement != null) {
        LaunchedEffect(sheetMeasurementId) {
            sheetState.expand()
        }

        ModalBottomSheet(
            onDismissRequest = { sheetMeasurementId = null },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            val mwv = sheetMeasurement
            val dateStr = remember(mwv.measurement.timestamp) {
                val dateTimeFormatter =
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT,
                        Locale.getDefault()
                    )
                dateTimeFormatter.format(Date(mwv.measurement.timestamp))
            }

            val visibleValues = mwv.values.filter { it.type.isEnabled }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    val uid = selectedUserId
                    IconButton(
                        enabled = uid != null,
                        onClick = {
                            sheetMeasurementId = null
                            if (uid != null) {
                                navController.navigate(Routes.measurementDetail(mwv.measurement.id, uid))
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit_measurement_desc)
                        )
                    }

                    IconButton(
                        enabled = uid != null,
                        onClick = {
                            sheetMeasurementId = null
                            if (uid != null) {
                                scope.launch {
                                   sharedViewModel.deleteMeasurement(mwv.measurement)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete_measurement_desc)
                        )
                    }

                    IconButton(onClick = { sheetMeasurementId = null }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_button)
                        )
                    }
                }

                visibleValues.forEach { v ->
                    MeasurementValueRow(
                        sharedViewModel = sharedViewModel,
                        valueWithTrend = ValueWithDifference(
                            currentValue = v,
                            difference = null,
                            trend = Trend.NOT_APPLICABLE
                        ),
                        userEvaluationContext = userEvalContext,
                        measuredAtMillis = mwv.measurement.timestamp
                    )
                }
            }
        }
    }
}
