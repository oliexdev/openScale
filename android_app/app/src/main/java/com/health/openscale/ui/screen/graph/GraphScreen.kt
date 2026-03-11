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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.Trend
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.model.ValueWithDifference
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.components.MeasurementChart
import com.health.openscale.ui.screen.components.provideFilterTopBarAction
import com.health.openscale.ui.screen.components.rememberAddMeasurementActionButton
import com.health.openscale.ui.screen.components.rememberBluetoothActionButton
import com.health.openscale.ui.screen.dialog.DeleteConfirmationDialog
import com.health.openscale.ui.screen.overview.MeasurementValueRow
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val graphState by sharedViewModel
        .screenFlow(SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT, useSmoothing = true)
        .collectAsStateWithLifecycle(initialValue = SharedViewModel.UiState.Loading)

    val allMeasurementsWithValues = remember(graphState) {
        when (val s = graphState) {
            is SharedViewModel.UiState.Success -> s.data.map { it.enriched.measurementWithValues }
            else -> emptyList()
        }
    }

    val userEvalContext by sharedViewModel.userEvaluationContext.collectAsState()
    val selectedUserId  by sharedViewModel.selectedUserId.collectAsState()

    // ── BottomSheet state ─────────────────────────────────────────────────────
    val sheetState        = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var sheetMeasurementId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDeleteDialog   by remember { mutableStateOf(false) }

    // ── Double-tap detection ──────────────────────────────────────────────────
    var lastTapId  by rememberSaveable { mutableStateOf<Int?>(null) }
    var lastTapAt  by rememberSaveable { mutableStateOf(0L) }
    val doubleTapWindowMs = 600L

    // ── Top bar ───────────────────────────────────────────────────────────────
    val bluetoothAction     = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    val addMeasurementAction = rememberAddMeasurementActionButton(sharedViewModel, navController)
    val timeFilterAction    = provideFilterTopBarAction(
        sharedViewModel   = sharedViewModel,
        screenContextName = SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT,
    )

    // Use Unit as key — timeFilterAction is recreated on every recomposition so using
    // it as a key would re-fire this effect every time. The title and actions are
    // stable after the first composition.
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(context.getString(R.string.route_title_graph))
        sharedViewModel.setTopBarActions(listOfNotNull(bluetoothAction, addMeasurementAction, timeFilterAction))
    }

    // ── Delete dialog ─────────────────────────────────────────────────────────
    // Derived from screenFlow data — no separate state needed
    val sheetEnrichedMeasurement = remember(sheetMeasurementId, graphState) {
        when (val s = graphState) {
            is SharedViewModel.UiState.Success ->
                s.data.firstOrNull {
                    it.enriched.measurementWithValues.measurement.id == sheetMeasurementId
                }?.enriched
            else -> null
        }
    }

    if (showDeleteDialog && sheetEnrichedMeasurement != null) {
        val enrichedItem  = sheetEnrichedMeasurement
        val weightValue   = enrichedItem.valuesWithTrend.find {
            it.currentValue.type.key == MeasurementTypeKey.WEIGHT
        }
        val weightString  = weightValue?.currentValue?.let {
            LocaleUtils.formatValueForDisplay(it.value.floatValue.toString(), it.type.unit)
        } ?: ""
        val formattedDate = remember(enrichedItem.measurementWithValues.measurement.timestamp) {
            DateFormat
                .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(Date(enrichedItem.measurementWithValues.measurement.timestamp))
        }

        DeleteConfirmationDialog(
            onDismissRequest = { showDeleteDialog = false },
            onConfirm        = {
                scope.launch {
                    sharedViewModel.deleteMeasurement(enrichedItem.measurementWithValues.measurement)
                }
                sheetMeasurementId = null
                showDeleteDialog   = false
            },
            title = stringResource(R.string.dialog_title_delete_item),
            text  = stringResource(R.string.dialog_message_delete_item, formattedDate, weightString),
        )
    }

    // ── Main content ──────────────────────────────────────────────────────────
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
                    // MeasurementChart internally calls screenFlow(GRAPH_CONTEXT, useSmoothing=true)
                    // which hits the ViewModel cache — no second pipeline is created.
                    MeasurementChart(
                        modifier          = Modifier.fillMaxSize(),
                        sharedViewModel   = sharedViewModel,
                        screenContextName = SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT,
                        showFilterControls = true,
                        showPeriodChart   = true,
                        showFilterTitle   = true,
                        onPointSelected   = { selectedTs ->
                            val result = sharedViewModel.findClosestMeasurement(
                                selectedTs,
                                allMeasurementsWithValues,
                            ) ?: return@MeasurementChart

                            val (_, mwv) = result
                            val id  = mwv.measurement.id
                            val now = System.currentTimeMillis()

                            if (lastTapId == id && (now - lastTapAt) <= doubleTapWindowMs) {
                                sheetMeasurementId = id
                                lastTapId = null
                                lastTapAt = 0L
                            } else {
                                lastTapId = id
                                lastTapAt = now
                            }
                        },
                    )
                }
            }
        }
    }

    // ── BottomSheet ───────────────────────────────────────────────────────────
    val sheetMeasurement = remember(sheetMeasurementId, allMeasurementsWithValues) {
        allMeasurementsWithValues.firstOrNull { it.measurement.id == sheetMeasurementId }
    }

    if (sheetMeasurementId != null && sheetMeasurement != null) {
        LaunchedEffect(sheetMeasurementId) { sheetState.expand() }

        ModalBottomSheet(
            onDismissRequest = { sheetMeasurementId = null },
            sheetState       = sheetState,
            dragHandle       = { BottomSheetDefaults.DragHandle() },
            containerColor   = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            val mwv     = sheetMeasurement
            val dateStr = remember(mwv.measurement.timestamp) {
                DateFormat
                    .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                    .format(Date(mwv.measurement.timestamp))
            }
            val visibleValues = mwv.values.filter { it.type.isEnabled }

            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = dateStr,
                        style    = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val uid = selectedUserId
                    IconButton(
                        enabled = uid != null,
                        onClick = {
                            sheetMeasurementId = null
                            if (uid != null) {
                                navController.navigate(
                                    Routes.measurementDetail(mwv.measurement.id, uid)
                                )
                            }
                        },
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit_measurement_desc),
                        )
                    }
                    IconButton(
                        enabled = uid != null,
                        onClick = { showDeleteDialog = true },
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete_measurement_desc),
                        )
                    }
                    IconButton(onClick = { sheetMeasurementId = null }) {
                        Icon(
                            imageVector        = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_button),
                        )
                    }
                }

                visibleValues.forEach { v ->
                    MeasurementValueRow(
                        sharedViewModel       = sharedViewModel,
                        valueWithTrend        = ValueWithDifference(
                            currentValue = v,
                            difference   = null,
                            trend        = Trend.NOT_APPLICABLE,
                        ),
                        userEvaluationContext = userEvalContext,
                        measuredAtMillis      = mwv.measurement.timestamp,
                    )
                }
            }
        }
    }
}