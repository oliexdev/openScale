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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InBodyCalibration
import com.health.openscale.core.data.MeasurementType
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

    val step by viewModel.wizardStep.collectAsState()
    val baseline by viewModel.selectedBaseline.collectAsState()
    val validationError by viewModel.validationError.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val calculatedCalibration by viewModel.calculatedCalibration.collectAsState()
    val savedScaleName by viewModel.savedDeviceName.collectAsState()

    val screenTitle = stringResource(R.string.calibration_wizard_title)
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(screenTitle)
        sharedViewModel.setTopBarActions(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Step indicator banner
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

        when (step) {
            1 -> Step1BaselineSelection(
                userId = userId,
                selectedBaseline = baseline,
                onSelectBaseline = {
                    viewModel.selectBaseline(it)
                    viewModel.clearValidationError()
                },
                onCancel = { navController.popBackStack() },
                onNext = {
                    if (baseline == null) {
                        viewModel.clearValidationError()
                    } else {
                        viewModel.setWizardStep(2)
                    }
                },
                viewModel = viewModel
            )
            2 -> Step2InBodyInputs(
                viewModel = viewModel,
                onBack = {
                    viewModel.clearValidationError()
                    viewModel.setWizardStep(1)
                },
                onNext = {
                    viewModel.proceedToStep3(userId, savedScaleName) {
                        // Success handled inside viewModel setting step to 3
                    }
                }
            )
            3 -> Step3ReviewAndSave(
                calibration = calculatedCalibration,
                baseline = baseline,
                isSaving = isSaving,
                onBack = {
                    viewModel.clearValidationError()
                    viewModel.setWizardStep(2)
                },
                onSave = {
                    viewModel.saveCalibration(
                        onSuccess = {
                            sharedViewModel.showSnackbar(messageResId = R.string.calibration_saved_success)
                            navController.popBackStack()
                        },
                        onError = { errMsg ->
                            sharedViewModel.showSnackbar(message = errMsg)
                        }
                    )
                }
            )
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
        StepDot(step = 2, label = "2. InBody", isActive = currentStep >= 2, isCurrent = currentStep == 2)
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
    onCancel: () -> Unit,
    onNext: () -> Unit,
    viewModel: CalibrationViewModel
) {
    val measurements by viewModel.getEligibleBaselineMeasurements(userId).collectAsState(initial = emptyList())
    val dateFormatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    }

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
                val impedance = mwv.values.find { it.type.key == MeasurementType.IMPEDANCE }?.value?.floatValue
                val muscle = mwv.values.find { it.type.key == MeasurementType.MUSCLE }?.value?.floatValue

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectBaseline(mwv) }
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
                        }
                        Icon(
                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = onNext,
            enabled = selectedBaseline != null
        ) {
            Text(stringResource(R.string.action_next))
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2: Enter InBody Values Form
// ---------------------------------------------------------------------------
@Composable
private fun Step2InBodyInputs(
    viewModel: CalibrationViewModel,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val weight by viewModel.inBodyWeight.collectAsState()
    val isFatInKg by viewModel.isFatInKg.collectAsState()
    val fat by viewModel.inBodyFat.collectAsState()
    val muscle by viewModel.inBodyMuscle.collectAsState()
    val water by viewModel.inBodyWater.collectAsState()
    val bone by viewModel.inBodyBone.collectAsState()
    val visceralFat by viewModel.inBodyVisceralFat.collectAsState()
    val protein by viewModel.inBodyProtein.collectAsState()

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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // InBody Weight (Required)
        OutlinedTextField(
            value = weight,
            onValueChange = { viewModel.setInBodyWeight(it) },
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
                onValueChange = { viewModel.setInBodyFat(it) },
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
            onValueChange = { viewModel.setInBodyMuscle(it) },
            label = { Text(stringResource(R.string.calibration_field_muscle)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Total Body Water (kg / L)
        OutlinedTextField(
            value = water,
            onValueChange = { viewModel.setInBodyWater(it) },
            label = { Text(stringResource(R.string.calibration_field_water)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Minerals / Bone Mass (kg)
        OutlinedTextField(
            value = bone,
            onValueChange = { viewModel.setInBodyBone(it) },
            label = { Text(stringResource(R.string.calibration_field_bone)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Visceral Fat Level (1-20+)
        OutlinedTextField(
            value = visceralFat,
            onValueChange = { viewModel.setInBodyVisceralFat(it) },
            label = { Text(stringResource(R.string.calibration_field_visceral_fat)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Protein Mass (kg, optional)
        OutlinedTextField(
            value = protein,
            onValueChange = { viewModel.setInBodyProtein(it) },
            label = { Text(stringResource(R.string.calibration_field_protein)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_back))
        }
        Button(
            onClick = onNext,
            enabled = weight.isNotBlank()
        ) {
            Text(stringResource(R.string.action_next))
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Step 3: Review Calculated Offsets & Confirm
// ---------------------------------------------------------------------------
@Composable
private fun Step3ReviewAndSave(
    calibration: InBodyCalibration?,
    baseline: MeasurementWithValues?,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit
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
        val ratio = if (calibration.inBodyWeightKg > 0f) scaleWeight / calibration.inBodyWeightKg else 1f

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
                        text = "InBody Scan Weight:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = "${calibration.inBodyWeightKg} kg", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Normalization Ratio (W₀ / W_ref):",
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
                    calibration.inBodyFatPercent?.let {
                        ReviewOffsetRow("Body Fat", "$it % (Mass Conservation)")
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

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onBack, enabled = !isSaving) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_back))
        }
        Button(
            onClick = onSave,
            enabled = !isSaving && calibration != null
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_save))
            } else {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save & Activate")
            }
        }
    }
}

@Composable
private fun ReviewOffsetRow(metric: String, delta: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = metric, style = MaterialTheme.typography.bodySmall)
        Text(
            text = delta,
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

