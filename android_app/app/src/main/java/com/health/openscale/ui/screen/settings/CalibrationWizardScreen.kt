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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.BodyScanCalibration
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.ui.shared.SharedViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CalibrationWizardScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val user by sharedViewModel.selectedUser.collectAsState()
    val userId = user?.id ?: 1
    val context = LocalContext.current

    val step by viewModel.wizardStep.collectAsState()
    val baseline by viewModel.selectedBaseline.collectAsState()
    val validationError by viewModel.validationError.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val calculatedCalibration by viewModel.calculatedCalibration.collectAsState()
    val savedScaleName by viewModel.savedDeviceName.collectAsState()
    val scanWeight by viewModel.scanWeight.collectAsState()

    val screenTitle = stringResource(R.string.calibration_wizard_title)
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(screenTitle)
        sharedViewModel.setTopBarActions(emptyList())
    }

    Scaffold(
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (step) {
                        1 -> {
                            OutlinedButton(onClick = { navController.popBackStack() }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Button(
                                onClick = {
                                    val hasBodyFat = baseline?.values?.any {
                                        it.type.key == MeasurementType.BODY_FAT || it.type.key == MeasurementType.LBM
                                    } == true
                                    if (baseline == null) {
                                        viewModel.clearValidationError()
                                    } else if (!hasBodyFat) {
                                        viewModel.setValidationError(
                                            context.getString(R.string.calibration_error_baseline_missing_body_comp)
                                        )
                                    } else {
                                        viewModel.clearValidationError()
                                        viewModel.setWizardStep(2)
                                    }
                                },
                                enabled = baseline != null
                            ) {
                                Text(stringResource(R.string.action_next))
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                        2 -> {
                            OutlinedButton(onClick = {
                                viewModel.clearValidationError()
                                viewModel.setWizardStep(1)
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_back))
                            }
                            Button(
                                onClick = {
                                    viewModel.proceedToStep3(userId, savedScaleName) {
                                        // Success handled inside viewModel setting step to 3
                                    }
                                },
                                enabled = scanWeight.isNotBlank()
                            ) {
                                Text(stringResource(R.string.action_next))
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                        3 -> {
                            OutlinedButton(
                                onClick = {
                                    viewModel.clearValidationError()
                                    viewModel.setWizardStep(2)
                                },
                                enabled = !isSaving
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_back))
                            }
                            Button(
                                onClick = {
                                    viewModel.saveCalibration(
                                        onSuccess = {
                                            sharedViewModel.showSnackbar(messageResId = R.string.calibration_saved_success)
                                            navController.popBackStack()
                                        },
                                        onError = { errMsg ->
                                            sharedViewModel.showSnackbar(message = errMsg)
                                        }
                                    )
                                },
                                enabled = !isSaving && calculatedCalibration != null
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.action_save))
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Step indicator banner (pinned at top)
            StepIndicator(currentStep = step)

            // Validation Error banner if present
            validationError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Scrollable Content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (step) {
                    1 -> Step1BaselineSelection(
                        userId = userId,
                        selectedBaseline = baseline,
                        onSelectBaseline = {
                            viewModel.selectBaseline(it)
                            viewModel.clearValidationError()
                        },
                        viewModel = viewModel
                    )
                    2 -> Step2BodyScanInputs(
                        viewModel = viewModel
                    )
                    3 -> Step3ReviewAndSave(
                        calibration = calculatedCalibration,
                        baseline = baseline
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepDot(step = 1, label = "1. Baseline", isActive = currentStep >= 1, isCurrent = currentStep == 1)
        HorizontalDivider(modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        StepDot(step = 2, label = "2. Body Scan", isActive = currentStep >= 2, isCurrent = currentStep == 2)
        HorizontalDivider(modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        StepDot(step = 3, label = "3. Review", isActive = currentStep >= 3, isCurrent = currentStep == 3)
    }
}

@Composable
private fun StepDot(step: Int, label: String, isActive: Boolean, isCurrent: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val color = when {
            isCurrent -> MaterialTheme.colorScheme.primary
            isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.outline
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .border(2.dp, color, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Step 1: Baseline Weigh-In Selection
// ---------------------------------------------------------------------------
@Composable
private fun Step1BaselineSelection(
    userId: Int,
    selectedBaseline: MeasurementWithValues?,
    onSelectBaseline: (MeasurementWithValues) -> Unit,
    viewModel: CalibrationViewModel
) {
    val measurements by viewModel.getEligibleBaselineMeasurements(userId).collectAsState(initial = emptyList())
    val context = LocalContext.current
    val dateFormatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.calibration_step1_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.calibration_step1_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (measurements.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.calibration_step1_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                measurements.take(15).forEach { mwv ->
                    val isSelected = selectedBaseline?.measurement?.id == mwv.measurement.id
                    val weight = mwv.values.find { it.type.key == MeasurementType.WEIGHT }?.value?.floatValue
                    val fat = mwv.values.find { it.type.key == MeasurementType.BODY_FAT }?.value?.floatValue
                    val lbm = mwv.values.find { it.type.key == MeasurementType.LBM }?.value?.floatValue
                    val impedance = mwv.values.find { it.type.key == MeasurementType.IMPEDANCE }?.value?.floatValue
                    val muscle = mwv.values.find { it.type.key == MeasurementType.MUSCLE }?.value?.floatValue
                    val hasBodyFat = fat != null || lbm != null

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (hasBodyFat) {
                                    onSelectBaseline(mwv)
                                } else {
                                    viewModel.setValidationError(
                                        context.getString(R.string.calibration_error_baseline_missing_body_comp)
                                    )
                                }
                            }
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.medium
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dateFormatter.format(Date(mwv.measurement.timestamp)),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = "Weight: ${weight ?: "—"} kg",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (fat != null) {
                                        Text(
                                            text = "Fat: $fat %",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (muscle != null) {
                                        Text(
                                            text = "Muscle: $muscle %",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                if (impedance != null) {
                                    Text(
                                        text = "Impedance: ${impedance.toInt()} Ω",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (!hasBodyFat) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                text = stringResource(R.string.calibration_baseline_missing_body_comp_warning),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }
                            Icon(
                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (!hasBodyFat) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                else if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2: Enter Body Scan Values Form
// ---------------------------------------------------------------------------
@Composable
private fun Step2BodyScanInputs(
    viewModel: CalibrationViewModel
) {
    val weight by viewModel.scanWeight.collectAsState()
    val isFatInKg by viewModel.isFatInKg.collectAsState()
    val fat by viewModel.scanFat.collectAsState()
    val muscle by viewModel.scanMuscle.collectAsState()
    val water by viewModel.scanWater.collectAsState()
    val bone by viewModel.scanBone.collectAsState()
    val visceralFat by viewModel.scanVisceralFat.collectAsState()
    val protein by viewModel.scanProtein.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.calibration_step2_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.calibration_step2_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))

        // Body Scan Weight (Required)
        OutlinedTextField(
            value = weight,
            onValueChange = { viewModel.setScanWeight(it) },
            label = { Text(stringResource(R.string.calibration_field_weight) + " *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Body Fat (toggleable between kg and %)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = fat,
                onValueChange = { viewModel.setScanFat(it) },
                label = {
                    Text(
                        if (isFatInKg) stringResource(R.string.calibration_field_fat)
                        else stringResource(R.string.calibration_field_fat_percent)
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = isFatInKg,
                onClick = { viewModel.setIsFatInKg(!isFatInKg) },
                label = { Text(if (isFatInKg) "Unit: kg" else "Unit: %") }
            )
        }

        // Skeletal Muscle Mass (kg)
        OutlinedTextField(
            value = muscle,
            onValueChange = { viewModel.setScanMuscle(it) },
            label = { Text(stringResource(R.string.calibration_field_muscle)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Total Body Water (kg / L)
        OutlinedTextField(
            value = water,
            onValueChange = { viewModel.setScanWater(it) },
            label = { Text(stringResource(R.string.calibration_field_water)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Minerals / Bone Mass (kg)
        OutlinedTextField(
            value = bone,
            onValueChange = { viewModel.setScanBone(it) },
            label = { Text(stringResource(R.string.calibration_field_bone)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Visceral Fat Level (1-20+)
        OutlinedTextField(
            value = visceralFat,
            onValueChange = { viewModel.setScanVisceralFat(it) },
            label = { Text(stringResource(R.string.calibration_field_visceral_fat)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Protein Mass (kg, optional)
        OutlinedTextField(
            value = protein,
            onValueChange = { viewModel.setScanProtein(it) },
            label = { Text(stringResource(R.string.calibration_field_protein)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// Step 3: Review Calculated Offsets & Confirm
// ---------------------------------------------------------------------------
@Composable
private fun Step3ReviewAndSave(
    calibration: BodyScanCalibration?,
    baseline: MeasurementWithValues?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.calibration_step3_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.calibration_step3_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (calibration != null && baseline != null) {
            val scaleWeight = baseline.values.find { it.type.key == MeasurementType.WEIGHT }?.value?.floatValue ?: 0f
            val ratio = if (calibration.scanWeightKg > 0f) scaleWeight / calibration.scanWeightKg else 1f

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Weight normalization summary
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Scale Baseline Weight:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = "$scaleWeight kg", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Body Scan Weight:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = "${calibration.scanWeightKg} kg", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Normalization Ratio (W₀ / W_scan):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(Locale.US, "%.3f", ratio),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = "Calculated Offsets (Δ)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Offsets table
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        calibration.offsetFfmKg?.let {
                            ReviewOffsetRow("Fat-Free Mass (FFM)", formatOffset(it, "kg"))
                        }
                        calibration.scanFatPercent?.let {
                            ReviewOffsetRow("Body Fat", "$it % (Mass Balance)")
                        }
                        calibration.offsetMuscleKg?.let {
                            ReviewOffsetRow("Skeletal Muscle (SMM)", formatOffset(it, "kg"))
                        }
                        calibration.offsetWaterKg?.let {
                            ReviewOffsetRow("Total Body Water (TBW)", formatOffset(it, "kg"))
                        }
                        calibration.offsetBoneKg?.let {
                            ReviewOffsetRow("Bone Mass (Minerals)", formatOffset(it, "kg"))
                        }
                        calibration.offsetVisceralFat?.let {
                            ReviewOffsetRow("Visceral Fat", formatOffset(it, ""))
                        }
                        calibration.offsetProteinKg?.let {
                            ReviewOffsetRow("Protein Mass", formatOffset(it, "kg"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewOffsetRow(metric: String, offsetText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = metric, style = MaterialTheme.typography.bodySmall)
        Text(
            text = offsetText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatOffset(offset: Float, unit: String): String {
    val sign = if (offset > 0) "+" else ""
    val u = if (unit.isNotBlank()) " $unit" else ""
    val formatted = String.format(java.util.Locale.US, "%.2f", offset)
    return "$sign$formatted$u"
}
