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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.R
import com.health.openscale.core.data.BodyScanCalibration
import com.health.openscale.core.data.CalibrationScope
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.usecase.BodyScanCalibrationUseCases
import com.health.openscale.core.usecase.BodyScanFormInput
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel managing body scan calibration state, wizard flow, and persistence.
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val calibrationUseCases: BodyScanCalibrationUseCases,
    private val settingsFacade: SettingsFacade
) : ViewModel() {

    private val TAG = "CalibrationViewModel"

    val isCalibrationEnabled: StateFlow<Boolean> = settingsFacade.isBodyScanCalibrationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val savedDeviceName: StateFlow<String?> = settingsFacade.observeSavedDevice()
        .map { it?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setCalibrationEnabled(userId: Int, enabled: Boolean) {
        viewModelScope.launch {
            calibrationUseCases.setCalibrationEnabledForUser(userId, enabled)
        }
    }

    fun observeActiveCalibration(userId: Int): Flow<BodyScanCalibration?> =
        calibrationUseCases.observeCalibrationForUser(userId)

    fun getEligibleBaselineMeasurements(userId: Int): Flow<List<MeasurementWithValues>> =
        calibrationUseCases.getEligibleBaselineMeasurements(userId)

    // --- Wizard State ---
    private val _wizardStep = MutableStateFlow(1)
    val wizardStep: StateFlow<Int> = _wizardStep.asStateFlow()

    private val _selectedBaseline = MutableStateFlow<MeasurementWithValues?>(null)
    val selectedBaseline: StateFlow<MeasurementWithValues?> = _selectedBaseline.asStateFlow()

    private val _scanWeight = MutableStateFlow("")
    val scanWeight: StateFlow<String> = _scanWeight.asStateFlow()

    private val _isFatInKg = MutableStateFlow(true)
    val isFatInKg: StateFlow<Boolean> = _isFatInKg.asStateFlow()

    private val _scanFat = MutableStateFlow("")
    val scanFat: StateFlow<String> = _scanFat.asStateFlow()

    private val _scanMuscle = MutableStateFlow("")
    val scanMuscle: StateFlow<String> = _scanMuscle.asStateFlow()

    private val _scanWater = MutableStateFlow("")
    val scanWater: StateFlow<String> = _scanWater.asStateFlow()

    private val _scanBone = MutableStateFlow("")
    val scanBone: StateFlow<String> = _scanBone.asStateFlow()

    private val _scanVisceralFat = MutableStateFlow("")
    val scanVisceralFat: StateFlow<String> = _scanVisceralFat.asStateFlow()

    private val _scanProtein = MutableStateFlow("")
    val scanProtein: StateFlow<String> = _scanProtein.asStateFlow()

    private val _calibrationScope = MutableStateFlow(CalibrationScope.ALL_HISTORY)
    val calibrationScope: StateFlow<CalibrationScope> = _calibrationScope.asStateFlow()

    fun setCalibrationScope(scope: CalibrationScope) {
        _calibrationScope.value = scope
        _selectedBaseline.value?.let { baseline ->
            val applyFrom = scope.calculateApplyFromTimestamp(baseline.measurement.timestamp)
            _calculatedCalibration.value = _calculatedCalibration.value?.copy(applyFromTimestamp = applyFrom)
        }
    }

    private val _calculatedCalibration = MutableStateFlow<BodyScanCalibration?>(null)
    val calculatedCalibration: StateFlow<BodyScanCalibration?> = _calculatedCalibration.asStateFlow()

    private val _effectiveBaseline = MutableStateFlow<MeasurementWithValues?>(null)
    val effectiveBaseline: StateFlow<MeasurementWithValues?> = _effectiveBaseline.asStateFlow()

    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    fun setWizardStep(step: Int) {
        _wizardStep.value = step
    }

    fun selectBaseline(measurement: MeasurementWithValues) {
        _selectedBaseline.value = measurement
    }

    fun setScanWeight(value: String) { _scanWeight.value = value }
    fun setIsFatInKg(inKg: Boolean) { _isFatInKg.value = inKg }
    fun setScanFat(value: String) { _scanFat.value = value }
    fun setScanMuscle(value: String) { _scanMuscle.value = value }
    fun setScanWater(value: String) { _scanWater.value = value }
    fun setScanBone(value: String) { _scanBone.value = value }
    fun setScanVisceralFat(value: String) { _scanVisceralFat.value = value }
    fun setScanProtein(value: String) { _scanProtein.value = value }

    private fun String.toFloatLocaleSafe(): Float? = trim().replace(',', '.').toFloatOrNull()

    private fun toKilograms(value: Float, unit: UnitType, totalWeightKg: Float): Float = when (unit) {
        UnitType.PERCENT -> (value / 100f) * totalWeightKg
        UnitType.KG -> value
        UnitType.LB -> ConverterUtils.toKilogram(value, WeightUnit.LB)
        UnitType.ST -> ConverterUtils.toKilogram(value, WeightUnit.ST)
        else -> if (value <= 100f && totalWeightKg > 30f) (value / 100f) * totalWeightKg else value
    }

    fun clearValidationError() {
        _validationError.value = null
    }

    fun setValidationError(error: String) {
        _validationError.value = error
    }

    fun proceedToStep3(userId: Int, scaleName: String? = null, onSuccess: () -> Unit) {
        val baseline = _selectedBaseline.value
        if (baseline == null) {
            _validationError.value = "Please select a baseline scale measurement."
            return
        }

        val weight = _scanWeight.value.toFloatLocaleSafe()
        if (weight == null || weight <= 10f || weight > 350f) {
            _validationError.value = "Body scan weight must be between 10 kg and 350 kg."
            return
        }

        val fatVal = _scanFat.value.toFloatLocaleSafe()
        val fatPercent = if (!_isFatInKg.value) fatVal else null
        val fatKg = if (_isFatInKg.value) fatVal else null

        val muscleKg = _scanMuscle.value.toFloatLocaleSafe()
        val waterLiters = _scanWater.value.toFloatLocaleSafe()
        val boneKg = _scanBone.value.toFloatLocaleSafe()
        val vfVal = _scanVisceralFat.value.toFloatLocaleSafe()
        val proteinKg = _scanProtein.value.toFloatLocaleSafe()

        val input = BodyScanFormInput(
            scanWeightKg = weight,
            scanFatPercent = fatPercent,
            scanFatKg = fatKg,
            scanMuscleKg = muscleKg,
            scanWaterLiters = waterLiters,
            scanBoneKg = boneKg,
            scanVisceralFat = vfVal,
            scanProteinKg = proteinKg
        )

        val error = calibrationUseCases.validateInputs(input, baseline)
        if (error != null) {
            _validationError.value = error
            return
        }

        viewModelScope.launch {
            try {
                val effectiveScaleName = scaleName ?: savedDeviceName.value ?: settingsFacade.observeSavedDevice().first()?.name
                val uncalibratedBaseline = calibrationUseCases.getEffectiveUncalibratedBaseline(userId, baseline)
                val calibration = calibrationUseCases.calculateCalibration(
                    userId = userId,
                    baseline = baseline,
                    input = input,
                    scaleName = effectiveScaleName,
                    scope = _calibrationScope.value
                )
                _calculatedCalibration.value = calibration
                _effectiveBaseline.value = uncalibratedBaseline
                _validationError.value = null
                _wizardStep.value = 3
                onSuccess()
            } catch (e: Exception) {
                LogManager.e(TAG, "Error calculating calibration: ${e.message}", e)
                _validationError.value = "Failed to calculate calibration: ${e.message}"
            }
        }
    }

    fun saveCalibration(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val calibration = _calculatedCalibration.value
        val baseline = _selectedBaseline.value
        if (calibration == null || baseline == null) {
            onError("Calibration data missing.")
            return
        }

        _isSaving.value = true
        viewModelScope.launch {
            try {
                calibrationUseCases.saveCalibration(calibration, baseline)
                _isSaving.value = false
                resetWizard()
                onSuccess()
            } catch (e: Exception) {
                LogManager.e(TAG, "Error saving calibration: ${e.message}", e)
                _isSaving.value = false
                onError(e.message ?: "Failed to save calibration.")
            }
        }
    }

    fun resetCalibration(userId: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            calibrationUseCases.deleteCalibrationForUser(userId)
            onSuccess()
        }
    }

    fun resetWizard() {
        _wizardStep.value = 1
        _selectedBaseline.value = null
        _scanWeight.value = ""
        _isFatInKg.value = true
        _scanFat.value = ""
        _scanMuscle.value = ""
        _scanWater.value = ""
        _scanBone.value = ""
        _scanVisceralFat.value = ""
        _scanProtein.value = ""
        _calculatedCalibration.value = null
        _calibrationScope.value = CalibrationScope.ALL_HISTORY
        _effectiveBaseline.value = null
        _validationError.value = null
        _isSaving.value = false
    }

    fun getComparisonRows(): List<CalibrationComparisonRow> {
        val calibration = _calculatedCalibration.value ?: return emptyList()
        val baseline = _effectiveBaseline.value ?: _selectedBaseline.value ?: return emptyList()

        val scaleWeight = calibration.scaleBaselineWeightKg
        val scaleFat = baseline.values.find { it.type.key == MeasurementType.BODY_FAT }?.value?.floatValue
        val scaleFfmKg: Float? = run {
            val lbmItem = baseline.values.find { it.type.key == MeasurementType.LBM }
            val lbmVal = lbmItem?.value?.floatValue
            if (lbmVal != null) {
                toKilograms(lbmVal, lbmItem.type.unit, scaleWeight)
            } else {
                scaleFat?.let { scaleWeight * (1f - it / 100f) }
            }
        }

        val scaleMuscleItem = baseline.values.find { it.type.key == MeasurementType.MUSCLE }
        val scaleMuscleKg = scaleMuscleItem?.value?.floatValue?.let { toKilograms(it, scaleMuscleItem.type.unit, scaleWeight) }

        val scaleWaterItem = baseline.values.find { it.type.key == MeasurementType.WATER }
        val scaleWaterKg = scaleWaterItem?.value?.floatValue?.let { toKilograms(it, scaleWaterItem.type.unit, scaleWeight) }

        val scaleBoneItem = baseline.values.find { it.type.key == MeasurementType.BONE }
        val scaleBoneKg = scaleBoneItem?.value?.floatValue?.let { toKilograms(it, scaleBoneItem.type.unit, scaleWeight) }

        val scaleVisceralFat = baseline.values.find { it.type.key == MeasurementType.VISCERAL_FAT }?.value?.floatValue
        val scaleProteinItem = baseline.values.find { it.type.key == MeasurementType.PROTEIN }
        val scaleProteinKg = scaleProteinItem?.value?.floatValue?.let { toKilograms(it, scaleProteinItem.type.unit, scaleWeight) }

        fun formatOffset(offset: Float, unit: String): String {
            val sign = if (offset > 0) "+" else ""
            val u = if (unit.isNotBlank()) " $unit" else ""
            val formatted = String.format(Locale.US, "%.2f", offset)
            return "$sign$formatted$u"
        }

        return listOfNotNull(
            calibration.scanFatPercent?.let {
                val diff = scaleFat?.let { sf -> it - sf }
                CalibrationComparisonRow(
                    labelResId = R.string.measurement_type_body_fat,
                    scaleText = scaleFat?.let { sf -> String.format(Locale.US, "%.1f %%", sf) } ?: "-",
                    scanText = String.format(Locale.US, "%.1f %%", it),
                    adjustmentText = diff?.let { d -> formatOffset(d, "%") } ?: "-"
                )
            },
            calibration.scanMuscleKg?.let {
                CalibrationComparisonRow(
                    labelResId = R.string.calibration_metric_skeletal_muscle,
                    scaleText = scaleMuscleKg?.let { sm -> String.format(Locale.US, "%.1f kg", sm) } ?: "-",
                    scanText = String.format(Locale.US, "%.1f kg", it),
                    adjustmentText = calibration.offsetMuscleKg?.let { om -> formatOffset(om, "kg") } ?: "-"
                )
            },
            calibration.offsetFfmKg?.let {
                val scanFfm = calibration.scanFatPercent?.let { fp -> scaleWeight * (1f - fp / 100f) }
                    ?: scaleFfmKg?.plus(it)
                CalibrationComparisonRow(
                    labelResId = R.string.calibration_metric_fat_free_mass,
                    scaleText = scaleFfmKg?.let { sf -> String.format(Locale.US, "%.1f kg", sf) } ?: "-",
                    scanText = scanFfm?.let { sf -> String.format(Locale.US, "%.1f kg", sf) } ?: "-",
                    adjustmentText = formatOffset(it, "kg")
                )
            },
            calibration.scanWaterLiters?.let {
                CalibrationComparisonRow(
                    labelResId = R.string.calibration_metric_total_body_water,
                    scaleText = scaleWaterKg?.let { sw -> String.format(Locale.US, "%.1f kg", sw) } ?: "-",
                    scanText = String.format(Locale.US, "%.1f L", it),
                    adjustmentText = calibration.offsetWaterKg?.let { ow -> formatOffset(ow, "kg") } ?: "-"
                )
            },
            calibration.scanVisceralFat?.let {
                CalibrationComparisonRow(
                    labelResId = R.string.measurement_type_visceral_fat,
                    scaleText = scaleVisceralFat?.let { svf -> String.format(Locale.US, "%.1f", svf) } ?: "-",
                    scanText = String.format(Locale.US, "%.1f", it),
                    adjustmentText = calibration.offsetVisceralFat?.let { ovf -> formatOffset(ovf, "") } ?: "-"
                )
            },
            calibration.scanBoneKg?.let {
                CalibrationComparisonRow(
                    labelResId = R.string.measurement_type_bone,
                    scaleText = scaleBoneKg?.let { sb -> String.format(Locale.US, "%.1f kg", sb) } ?: "-",
                    scanText = String.format(Locale.US, "%.1f kg", it),
                    adjustmentText = calibration.offsetBoneKg?.let { ob -> formatOffset(ob, "kg") } ?: "-"
                )
            },
            calibration.scanProteinKg?.let {
                CalibrationComparisonRow(
                    labelResId = R.string.measurement_type_protein,
                    scaleText = scaleProteinKg?.let { sp -> String.format(Locale.US, "%.1f kg", sp) } ?: "-",
                    scanText = String.format(Locale.US, "%.1f kg", it),
                    adjustmentText = calibration.offsetProteinKg?.let { op -> formatOffset(op, "kg") } ?: "-"
                )
            }
        )
    }
}

data class CalibrationComparisonRow(
    val labelResId: Int,
    val scaleText: String,
    val scanText: String,
    val adjustmentText: String
)
