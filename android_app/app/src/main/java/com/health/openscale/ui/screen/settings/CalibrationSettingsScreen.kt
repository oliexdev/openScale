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

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InBodyCalibration
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.shared.SharedViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CalibrationSettingsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val user by sharedViewModel.selectedUser.collectAsState()
    val userId = user?.id ?: 1

    val isEnabled by viewModel.isCalibrationEnabled.collectAsState()
    val calibration by viewModel.observeActiveCalibration(userId).collectAsState(initial = null)

    var showResetDialog by remember { mutableStateOf(false) }

    val screenTitle = stringResource(R.string.calibration_title)
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(screenTitle)
        sharedViewModel.setTopBarActions(emptyList())
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.calibration_reset_confirm_title)) },
            text = { Text(stringResource(R.string.calibration_reset_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetCalibration(userId) {
                            sharedViewModel.showSnackbar(messageResId = R.string.calibration_reset_success)
                        }
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.calibration_btn_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Master Toggle Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.calibration_enable_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.calibration_enable_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { viewModel.setCalibrationEnabled(it) }
                    )
                }
            }
        }

        // --- 2. Active Calibration Card or Empty State ---
        val activeCalibration = calibration
        if (activeCalibration != null) {
            ActiveCalibrationCard(
                calibration = activeCalibration,
                onRecalibrate = {
                    viewModel.resetWizard()
                    navController.navigate(Routes.CALIBRATION_WIZARD)
                },
                onReset = { showResetDialog = true }
            )
        } else {
            EmptyCalibrationCard(
                onStartWizard = {
                    viewModel.resetWizard()
                    navController.navigate(Routes.CALIBRATION_WIZARD)
                }
            )
        }
    }
}

@Composable
private fun ActiveCalibrationCard(
    calibration: InBodyCalibration,
    onRecalibrate: () -> Unit,
    onReset: () -> Unit
) {
    val dateFormatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.calibration_current_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            HorizontalDivider()

            // Details metadata
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetadataRow(
                    label = stringResource(R.string.calibration_date),
                    value = dateFormatter.format(Date(calibration.createdAt))
                )
                MetadataRow(
                    label = stringResource(R.string.calibration_baseline_weighin),
                    value = dateFormatter.format(Date(calibration.scaleMeasurementTimestamp))
                )
                if (!calibration.scaleName.isNullOrBlank()) {
                    MetadataRow(
                        label = stringResource(R.string.calibration_paired_scale),
                        value = calibration.scaleName
                    )
                }
                MetadataRow(
                    label = stringResource(R.string.calibration_field_weight),
                    value = "${calibration.inBodyWeightKg} kg"
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.calibration_step3_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            // Offsets summary table
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = stringResource(R.string.calibration_summary_header_metric),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.5f)
                    )
                    Text(
                        text = stringResource(R.string.calibration_summary_header_inbody),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.2f)
                    )
                    Text(
                        text = stringResource(R.string.calibration_summary_header_offset),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.3f)
                    )
                }

                HorizontalDivider()

                // Row: FFM / Fat-Free Mass
                calibration.offsetFfmKg?.let { offset ->
                    OffsetTableRow(
                        metric = "Fat-Free Mass (FFM)",
                        inBodyValue = "Anchor",
                        offsetValue = formatOffset(offset, "kg")
                    )
                }

                // Row: Body Fat %
                calibration.inBodyFatPercent?.let { bf ->
                    OffsetTableRow(
                        metric = "Body Fat",
                        inBodyValue = "$bf %",
                        offsetValue = "Mass Balance"
                    )
                }

                // Row: Muscle Mass
                calibration.offsetMuscleKg?.let { offset ->
                    OffsetTableRow(
                        metric = "Skeletal Muscle (SMM)",
                        inBodyValue = "${calibration.inBodyMuscleKg ?: "—"} kg",
                        offsetValue = formatOffset(offset, "kg")
                    )
                }

                // Row: Water
                calibration.offsetWaterKg?.let { offset ->
                    OffsetTableRow(
                        metric = "Body Water (TBW)",
                        inBodyValue = "${calibration.inBodyWaterLiters ?: "—"} L",
                        offsetValue = formatOffset(offset, "kg")
                    )
                }

                // Row: Bone
                calibration.offsetBoneKg?.let { offset ->
                    OffsetTableRow(
                        metric = "Bone Mass (Minerals)",
                        inBodyValue = "${calibration.inBodyBoneKg ?: "—"} kg",
                        offsetValue = formatOffset(offset, "kg")
                    )
                }

                // Row: Visceral Fat
                calibration.offsetVisceralFat?.let { offset ->
                    OffsetTableRow(
                        metric = "Visceral Fat",
                        inBodyValue = "${calibration.inBodyVisceralFat ?: "—"}",
                        offsetValue = formatOffset(offset, "")
                    )
                }

                // Row: Protein
                calibration.offsetProteinKg?.let { offset ->
                    OffsetTableRow(
                        metric = "Protein Mass",
                        inBodyValue = "${calibration.inBodyProteinKg ?: "—"} kg",
                        offsetValue = formatOffset(offset, "kg")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.calibration_btn_reset), modifier = Modifier.padding(start = 6.dp))
                }

                Button(
                    onClick = onRecalibrate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.calibration_btn_recalibrate), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyCalibrationCard(onStartWizard: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = stringResource(R.string.calibration_no_profile),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.calibration_no_profile_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onStartWizard) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.calibration_btn_new), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OffsetTableRow(metric: String, inBodyValue: String, offsetValue: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = metric, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
        Text(text = inBodyValue, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
        Text(
            text = offsetValue,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1.3f)
        )
    }
}

private fun formatOffset(offset: Float, unit: String): String {
    val sign = if (offset > 0) "+" else ""
    val u = if (unit.isNotBlank()) " $unit" else ""
    val formatted = String.format(java.util.Locale.US, "%.2f", offset)
    return "$sign$formatted$u"
}

