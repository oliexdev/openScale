package com.health.openscale.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.ui.screen.SharedViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val showDataPoints by sharedViewModel.userSettingRepository.showChartDataPoints.collectAsState(true)
    val selectedAlgorithm by sharedViewModel.userSettingRepository.chartSmoothingAlgorithm.collectAsState(SmoothingAlgorithm.NONE)
    val currentAlphaState by sharedViewModel.userSettingRepository.chartSmoothingAlpha.collectAsState(0.5f)
    val currentWindowSizeState by sharedViewModel.userSettingRepository.chartSmoothingWindowSize.collectAsState(5)

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
                    coroutineScope.launch {
                        sharedViewModel.userSettingRepository.setShowChartDataPoints(newValue)
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
                onValueChange = { /* Read-only */ },
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
                            coroutineScope.launch {
                                sharedViewModel.userSettingRepository.setChartSmoothingAlgorithm(algorithm)
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
                            coroutineScope.launch {
                                sharedViewModel.userSettingRepository.setChartSmoothingAlpha(newFloatValue)
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
                            coroutineScope.launch {
                                sharedViewModel.userSettingRepository.setChartSmoothingWindowSize(newValue)
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
 * (IntegerStepper code remains the same as your provided version)
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
                    if (value > valueRange.first) {
                        onValueChange(value - 1)
                    }
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
                    if (value < valueRange.last) {
                        onValueChange(value + 1)
                    }
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
