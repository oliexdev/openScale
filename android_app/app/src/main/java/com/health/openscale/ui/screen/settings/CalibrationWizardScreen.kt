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

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.BodyScanCalibration
import com.health.openscale.core.data.CalibrationScope
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.ConverterUtils
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
    val effectiveBaseline by viewModel.effectiveBaseline.collectAsState()
    val validationError by viewModel.validationError.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val calculatedCalibration by viewModel.calculatedCalibration.collectAsState()
    val savedScaleName by viewModel.savedDeviceName.collectAsState()
    val scanWeight by viewModel.scanWeight.collectAsState()
    val calibrationScope by viewModel.calibrationScope.collectAsState()

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
                        baseline = effectiveBaseline ?: baseline,
                        selectedScope = calibrationScope,
                        onSelectScope = { viewModel.setCalibrationScope(it) }
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
// Step 3: Review Calibration & Confirm
// ---------------------------------------------------------------------------
private data class ComparisonMetricRow(
    val label: String,
    val scaleText: String,
    val scanText: String,
    val adjustmentText: String
)

@Composable
private fun Step3ReviewAndSave(
    calibration: BodyScanCalibration?,
    baseline: MeasurementWithValues?,
    selectedScope: CalibrationScope,
    onSelectScope: (CalibrationScope) -> Unit
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

            val scaleFat = baseline.values.find { it.type.key == MeasurementType.BODY_FAT }?.value?.floatValue

            val scaleMuscleItem = baseline.values.find { it.type.key == MeasurementType.MUSCLE }
            val scaleMuscleKg: Float? = scaleMuscleItem?.value?.floatValue?.let { v ->
                when (scaleMuscleItem.type.unit) {
                    UnitType.PERCENT -> (v / 100f) * scaleWeight
                    UnitType.KG -> v
                    UnitType.LB -> ConverterUtils.toKilogram(v, WeightUnit.LB)
                    UnitType.ST -> ConverterUtils.toKilogram(v, WeightUnit.ST)
                    else -> if (v <= 100f && scaleWeight > 30f) (v / 100f) * scaleWeight else v
                }
            }

            val scaleFfmKg: Float? = run {
                val lbmItem = baseline.values.find { it.type.key == MeasurementType.LBM }
                val lbmVal = lbmItem?.value?.floatValue
                if (lbmVal != null) {
                    when (lbmItem.type.unit) {
                        UnitType.KG -> lbmVal
                        UnitType.LB -> ConverterUtils.toKilogram(lbmVal, WeightUnit.LB)
                        UnitType.ST -> ConverterUtils.toKilogram(lbmVal, WeightUnit.ST)
                        else -> lbmVal
                    }
                } else {
                    scaleFat?.let { scaleWeight * (1f - it / 100f) }
                }
            }

            val scaleWaterItem = baseline.values.find { it.type.key == MeasurementType.WATER }
            val scaleWaterKg: Float? = scaleWaterItem?.value?.floatValue?.let { v ->
                when (scaleWaterItem.type.unit) {
                    UnitType.PERCENT -> (v / 100f) * scaleWeight
                    UnitType.KG -> v
                    else -> if (v <= 100f && scaleWeight > 30f) (v / 100f) * scaleWeight else v
                }
            }

            val scaleBone = baseline.values.find { it.type.key == MeasurementType.BONE }?.value?.floatValue
            val scaleVisceralFat = baseline.values.find { it.type.key == MeasurementType.VISCERAL_FAT }?.value?.floatValue
            val scaleProtein = baseline.values.find { it.type.key == MeasurementType.PROTEIN }?.value?.floatValue

            // Weight Alignment Card
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Home Scale",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f kg", scaleWeight),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "vs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Reference Scan",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f kg", calibration.scanWeightKg),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (kotlin.math.abs(scaleWeight - calibration.scanWeightKg) > 0.05f) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = stringResource(
                                R.string.calibration_weight_alignment_desc,
                                String.format(Locale.US, "%.1f", scaleWeight)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Comparison Rows
            val rows = listOfNotNull(
                calibration.scanFatPercent?.let {
                    val diff = scaleFat?.let { sf -> it - sf }
                    ComparisonMetricRow(
                        label = "Body Fat",
                        scaleText = scaleFat?.let { sf -> String.format(Locale.US, "%.1f %%", sf) } ?: "—",
                        scanText = String.format(Locale.US, "%.1f %%", it),
                        adjustmentText = diff?.let { d -> formatOffset(d, "%") } ?: "—"
                    )
                },
                calibration.scanMuscleKg?.let {
                    ComparisonMetricRow(
                        label = "Skeletal Muscle (SMM)",
                        scaleText = scaleMuscleKg?.let { sm -> String.format(Locale.US, "%.1f kg", sm) } ?: "—",
                        scanText = String.format(Locale.US, "%.1f kg", it),
                        adjustmentText = calibration.offsetMuscleKg?.let { om -> formatOffset(om, "kg") } ?: "—"
                    )
                },
                calibration.offsetFfmKg?.let {
                    val scanFfm = calibration.scanFatPercent?.let { fp -> scaleWeight * (1f - fp / 100f) }
                        ?: scaleFfmKg?.plus(it)
                    ComparisonMetricRow(
                        label = "Fat-Free Mass (FFM)",
                        scaleText = scaleFfmKg?.let { sf -> String.format(Locale.US, "%.1f kg", sf) } ?: "—",
                        scanText = scanFfm?.let { sf -> String.format(Locale.US, "%.1f kg", sf) } ?: "—",
                        adjustmentText = formatOffset(it, "kg")
                    )
                },
                calibration.scanWaterLiters?.let {
                    ComparisonMetricRow(
                        label = "Total Body Water",
                        scaleText = scaleWaterKg?.let { sw -> String.format(Locale.US, "%.1f kg", sw) } ?: "—",
                        scanText = String.format(Locale.US, "%.1f L", it),
                        adjustmentText = calibration.offsetWaterKg?.let { ow -> formatOffset(ow, "kg") } ?: "—"
                    )
                },
                calibration.scanVisceralFat?.let {
                    ComparisonMetricRow(
                        label = "Visceral Fat",
                        scaleText = scaleVisceralFat?.let { svf -> String.format(Locale.US, "%.1f", svf) } ?: "—",
                        scanText = String.format(Locale.US, "%.1f", it),
                        adjustmentText = calibration.offsetVisceralFat?.let { ovf -> formatOffset(ovf, "") } ?: "—"
                    )
                },
                calibration.scanBoneKg?.let {
                    ComparisonMetricRow(
                        label = "Bone Mass",
                        scaleText = scaleBone?.let { sb -> String.format(Locale.US, "%.1f kg", sb) } ?: "—",
                        scanText = String.format(Locale.US, "%.1f kg", it),
                        adjustmentText = calibration.offsetBoneKg?.let { ob -> formatOffset(ob, "kg") } ?: "—"
                    )
                },
                calibration.scanProteinKg?.let {
                    ComparisonMetricRow(
                        label = "Protein Mass",
                        scaleText = scaleProtein?.let { sp -> String.format(Locale.US, "%.1f kg", sp) } ?: "—",
                        scanText = String.format(Locale.US, "%.1f kg", it),
                        adjustmentText = calibration.offsetProteinKg?.let { op -> formatOffset(op, "kg") } ?: "—"
                    )
                }
            )

            // Side-by-side Table Card
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    // Header Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.calibration_summary_header_metric),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1.4f)
                        )
                        Text(
                            text = stringResource(R.string.calibration_summary_header_scale),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.85f)
                        )
                        Text(
                            text = stringResource(R.string.calibration_summary_header_scan),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.85f)
                        )
                        Text(
                            text = stringResource(R.string.calibration_summary_header_offset),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1.0f)
                        )
                    }

                    HorizontalDivider()

                    rows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1.4f)
                            )
                            Text(
                                text = row.scaleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.85f)
                            )
                            Text(
                                text = row.scanText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.85f)
                            )
                            Text(
                                text = row.adjustmentText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1.0f)
                            )
                        }
                        if (index < rows.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }

            // Calibration Scope Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.calibration_scope_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = stringResource(R.string.calibration_scope_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    CalibrationScope.entries.forEach { scope ->
                        val isSelected = scope == selectedScope
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    onClick = { onSelectScope(scope) },
                                    role = Role.RadioButton
                                ),
                            shape = MaterialTheme.shapes.small,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null
                                )
                                Text(
                                    text = stringResource(scope.labelResId),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Explainer Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.calibration_dynamic_adaptation_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.calibration_dynamic_adaptation_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatOffset(offset: Float, unit: String): String {
    val sign = if (offset > 0) "+" else ""
    val u = if (unit.isNotBlank()) " $unit" else ""
    val formatted = String.format(Locale.US, "%.2f", offset)
    return "$sign$formatted$u"
}
