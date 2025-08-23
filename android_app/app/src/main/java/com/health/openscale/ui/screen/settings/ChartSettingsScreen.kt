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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChartSettingsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel
) {
    val chartSettingsScreenTitle = stringResource(R.string.settings_item_chart)
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(chartSettingsScreenTitle)
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // --- READ settings via SharedViewModel (delegiert an SettingsFacade) ---
    val showDataPoints by sharedViewModel
        .showChartDataPoints
        .collectAsStateWithLifecycle(initialValue = true)

    val selectedAlgorithm by sharedViewModel
        .selectedSmoothingAlgorithm
        .collectAsStateWithLifecycle(initialValue = SmoothingAlgorithm.NONE)

    val currentAlphaState by sharedViewModel
        .smoothingAlpha
        .collectAsStateWithLifecycle(initialValue = 0.5f)

    val currentWindowSizeState by sharedViewModel
        .smoothingWindowSize
        .collectAsStateWithLifecycle(initialValue = 5)

    val availableAlgorithms = remember { SmoothingAlgorithm.values().toList() }
    var algorithmDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Show Data Points Setting ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.setting_show_chart_points),
                style = MaterialTheme.typography.titleMedium
            )
            Switch(
                checked = showDataPoints,
                onCheckedChange = { newValue ->
                    scope.launch {
                        sharedViewModel.setShowChartDataPoints(newValue)
                    }
                }
            )
        }

        // --- Smoothing Algorithm Setting ---
        ExposedDropdownMenuBox(
            expanded = algorithmDropdownExpanded,
            onExpandedChange = { algorithmDropdownExpanded = !algorithmDropdownExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedAlgorithm.getDisplayName(context),
                onValueChange = { /* read-only */ },
                readOnly = true,
                label = { Text(stringResource(R.string.setting_smoothing_algorithm)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = algorithmDropdownExpanded) },
                modifier = Modifier
                    .menuAnchor(type = MenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .clickable { algorithmDropdownExpanded = true },
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            ExposedDropdownMenu(
                expanded = algorithmDropdownExpanded,
                onDismissRequest = { algorithmDropdownExpanded = false },
                modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true)
            ) {
                availableAlgorithms.forEach { algorithm ->
                    DropdownMenuItem(
                        text = { Text(algorithm.getDisplayName(context)) },
                        onClick = {
                            scope.launch {
                                sharedViewModel.setChartSmoothingAlgorithm(algorithm)
                            }
                            algorithmDropdownExpanded = false
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = selectedAlgorithm != SmoothingAlgorithm.NONE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (selectedAlgorithm == SmoothingAlgorithm.EXPONENTIAL_SMOOTHING) {
                    val alphaAsInt = (currentAlphaState * 10).roundToInt()
                    val alphaStepperRange = 1..9
                    IntegerStepper(
                        label = stringResource(R.string.setting_smoothing_alpha),
                        value = alphaAsInt,
                        onValueChange = { newIntValue ->
                            val newFloatValue = newIntValue / 10f
                            scope.launch {
                                sharedViewModel.setChartSmoothingAlpha(newFloatValue)
                            }
                        },
                        valueRange = alphaStepperRange,
                        valueRepresentation = { intValue -> String.format("%.1f", intValue / 10f) },
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }

                if (selectedAlgorithm == SmoothingAlgorithm.SIMPLE_MOVING_AVERAGE) {
                    IntegerStepper(
                        label = stringResource(R.string.setting_smoothing_window_size),
                        value = currentWindowSizeState,
                        onValueChange = { newValue ->
                            scope.launch {
                                sharedViewModel.setChartSmoothingWindowSize(newValue)
                            }
                        },
                        valueRange = 2..50,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * A reusable Composable for an integer input with + and - buttons.
 */
@Composable
private fun IntegerStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    valueRepresentation: ((Int) -> String)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = {
                    if (value > valueRange.first) onValueChange(value - 1)
                },
                enabled = value > valueRange.first
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.trend_decreased_desc, label)
                )
            }
            Text(
                text = valueRepresentation?.invoke(value) ?: value.toString(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(
                onClick = {
                    if (value < valueRange.last) onValueChange(value + 1)
                },
                enabled = value < valueRange.last
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.trend_increased_desc, label)
                )
            }
        }
    }
}
