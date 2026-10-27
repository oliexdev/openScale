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
import com.health.openscale.core.data.InBodyCalibration
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.usecase.InBodyCalibrationUseCases
import com.health.openscale.core.usecase.InBodyFormInput
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
import javax.inject.Inject

/**
 * ViewModel managing InBody calibration state, wizard flow, and persistence.
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val calibrationUseCases: InBodyCalibrationUseCases,
    private val settingsFacade: SettingsFacade
) : ViewModel() {

    private val TAG = "CalibrationViewModel"

    val isCalibrationEnabled: StateFlow<Boolean> = settingsFacade.isInBodyCalibrationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val savedDeviceName: StateFlow<String?> = settingsFacade.observeSavedDevice()
        .map { it?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setCalibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsFacade.setInBodyCalibrationEnabled(enabled)
        }
    }

    fun observeActiveCalibration(userId: Int): Flow<InBodyCalibration?> =
        calibrationUseCases.observeCalibrationForUser(userId)

    fun getEligibleBaselineMeasurements(userId: Int): Flow<List<MeasurementWithValues>> =
        calibrationUseCases.getEligibleBaselineMeasurements(userId)

    // --- Wizard State ---
    private val _wizardStep = MutableStateFlow(1)
    val wizardStep: StateFlow<Int> = _wizardStep.asStateFlow()

    private val _selectedBaseline = MutableStateFlow<MeasurementWithValues?>(null)
    val selectedBaseline: StateFlow<MeasurementWithValues?> = _selectedBaseline.asStateFlow()

    private val _inBodyWeight = MutableStateFlow("")
    val inBodyWeight: StateFlow<String> = _inBodyWeight.asStateFlow()

    private val _isFatInKg = MutableStateFlow(true)
    val isFatInKg: StateFlow<Boolean> = _isFatInKg.asStateFlow()

    private val _inBodyFat = MutableStateFlow("")
    val inBodyFat: StateFlow<String> = _inBodyFat.asStateFlow()

    private val _inBodyMuscle = MutableStateFlow("")
    val inBodyMuscle: StateFlow<String> = _inBodyMuscle.asStateFlow()

    private val _inBodyWater = MutableStateFlow("")
    val inBodyWater: StateFlow<String> = _inBodyWater.asStateFlow()

    private val _inBodyBone = MutableStateFlow("")
    val inBodyBone: StateFlow<String> = _inBodyBone.asStateFlow()

    private val _inBodyVisceralFat = MutableStateFlow("")
    val inBodyVisceralFat: StateFlow<String> = _inBodyVisceralFat.asStateFlow()

    private val _inBodyProtein = MutableStateFlow("")
    val inBodyProtein: StateFlow<String> = _inBodyProtein.asStateFlow()

    private val _calculatedCalibration = MutableStateFlow<InBodyCalibration?>(null)
    val calculatedCalibration: StateFlow<InBodyCalibration?> = _calculatedCalibration.asStateFlow()

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

    fun setInBodyWeight(value: String) { _inBodyWeight.value = value }
    fun setIsFatInKg(inKg: Boolean) { _isFatInKg.value = inKg }
    fun setInBodyFat(value: String) { _inBodyFat.value = value }
    fun setInBodyMuscle(value: String) { _inBodyMuscle.value = value }
    fun setInBodyWater(value: String) { _inBodyWater.value = value }
    fun setInBodyBone(value: String) { _inBodyBone.value = value }
    fun setInBodyVisceralFat(value: String) { _inBodyVisceralFat.value = value }
    fun setInBodyProtein(value: String) { _inBodyProtein.value = value }

    private fun String.toFloatLocaleSafe(): Float? = trim().replace(',', '.').toFloatOrNull()

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

        val weight = _inBodyWeight.value.toFloatLocaleSafe()
        if (weight == null || weight <= 10f || weight > 350f) {
            _validationError.value = "InBody weight must be between 10 kg and 350 kg."
            return
        }

        val fatVal = _inBodyFat.value.toFloatLocaleSafe()
        val fatPercent = if (!_isFatInKg.value) fatVal else null
        val fatKg = if (_isFatInKg.value) fatVal else null

        val muscleKg = _inBodyMuscle.value.toFloatLocaleSafe()
        val waterLiters = _inBodyWater.value.toFloatLocaleSafe()
        val boneKg = _inBodyBone.value.toFloatLocaleSafe()
        val vfVal = _inBodyVisceralFat.value.toFloatLocaleSafe()
        val proteinKg = _inBodyProtein.value.toFloatLocaleSafe()

        val input = InBodyFormInput(
            inBodyWeightKg = weight,
            inBodyFatPercent = fatPercent,
            inBodyFatKg = fatKg,
            inBodyMuscleKg = muscleKg,
            inBodyWaterLiters = waterLiters,
            inBodyBoneKg = boneKg,
            inBodyVisceralFat = vfVal,
            inBodyProteinKg = proteinKg
        )

        val error = calibrationUseCases.validateInputs(input, baseline)
        if (error != null) {
            _validationError.value = error
            return
        }

        viewModelScope.launch {
            try {
                val effectiveScaleName = scaleName ?: savedDeviceName.value ?: settingsFacade.observeSavedDevice().first()?.name
                val calibration = calibrationUseCases.calculateCalibration(
                    userId = userId,
                    baseline = baseline,
                    input = input,
                    scaleName = effectiveScaleName
                )
                _calculatedCalibration.value = calibration
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
        _inBodyWeight.value = ""
        _isFatInKg.value = true
        _inBodyFat.value = ""
        _inBodyMuscle.value = ""
        _inBodyWater.value = ""
        _inBodyBone.value = ""
        _inBodyVisceralFat.value = ""
        _inBodyProtein.value = ""
        _calculatedCalibration.value = null
        _validationError.value = null
        _isSaving.value = false
    }
}

