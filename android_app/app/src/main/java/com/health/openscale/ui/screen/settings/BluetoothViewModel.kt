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

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.ui.shared.SnackbarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel bridging the [com.health.openscale.core.facade.BluetoothFacade] and the UI.
 *
 * It:
 * - Exposes state flows from [com.health.openscale.core.facade.BluetoothFacade] directly to the UI.
 * - Collects one-shot messages from the facade and translates them into [com.health.openscale.ui.shared.SnackbarEvent].
 * - Provides simple delegation methods for scanning, connection, and device management.
 *
 * The ViewModel itself remains lightweight and contains no Bluetooth-specific logic.
 */
@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val bt: BluetoothFacade,
    private val settingsFacade: SettingsFacade
) : ViewModel() {

    companion object { private const val SCAN_DURATION_MS = 20_000L }

    // --- Exposed state from facade ---
    val scannedDevices = bt.scannedDevices
    val isScanning = bt.isScanning
    val scanError = bt.scanError

    val connectedDeviceAddress = bt.connectedDeviceAddress
    val connectionStatus = bt.connectionStatus
    val connectionError = bt.connectionError

    val pendingUserInteractionEvent = bt.pendingUserInteractionEvent
    val savedDevice = bt.savedDevice
    val savedDeviceSupport = bt.savedDeviceSupport

    val isSmartAssignmentEnabled = settingsFacade.isSmartAssignmentEnabled
    val smartAssignmentTolerancePercent = settingsFacade.smartAssignmentTolerancePercent
    val smartAssignmentIgnoreOutsideTolerance = settingsFacade.smartAssignmentIgnoreOutsideTolerance

    fun setSmartAssignmentEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsFacade.setSmartAssignmentEnabled(enabled)
    }

    fun setSmartAssignmentTolerancePercent(tolerance: Int) = viewModelScope.launch {
        settingsFacade.setSmartAssignmentTolerancePercent(tolerance)
    }

    fun setSmartAssignmentIgnoreOutsideTolerance(ignore: Boolean) = viewModelScope.launch {
        settingsFacade.setSmartAssignmentIgnoreOutsideTolerance(ignore)
    }

    // --- Snackbar events for UI ---
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(replay = 0, extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            bt.snackbarEventsFromConnector.collect { evt ->
                _snackbarEvents.emit(evt)
            }
        }
    }

    // --- Delegated actions ---
    @Composable
    fun DeviceConfigurationUi() = bt.DeviceConfigurationUi()

    fun requestStartDeviceScan() {
        if (!bt.isBluetoothEnabled()) {
            emitSnack(R.string.bluetooth_must_be_enabled_for_scan, SnackbarDuration.Long)
            return
        }
        bt.startScan(SCAN_DURATION_MS)
    }

    fun requestStopDeviceScan() = bt.stopScan()

    fun connectToSavedDevice() = bt.connectToSavedDevice()

    fun disconnectDevice() = bt.disconnect()

    fun isBluetoothEnabled(): Boolean = bt.isBluetoothEnabled()

    fun saveDeviceAsPreferred(device: ScannedDeviceInfo) = bt.saveAsPreferred(device)

    fun removeSavedDevice() { bt.removeSavedDevice() }

    fun setSavedTuning(profile: TuningProfile) = bt.setSavedTuning(profile)

    fun clearAllErrors() = bt.clearErrors()

    fun attemptAutoConnectToSavedScale() = bt.attemptAutoConnectToSavedDevice()

    fun provideUserInteractionFeedback(type: BluetoothEvent.UserInteractionType, feedbackData: Any) =
        bt.provideUserInteractionFeedback(type, feedbackData)

    fun clearPendingUserInteraction() = bt.clearPendingUserInteraction()

    private fun emitSnack(
        resId: Int,
        duration: SnackbarDuration = SnackbarDuration.Short,
        args: List<Any> = emptyList()
    ) = viewModelScope.launch {
        _snackbarEvents.emit(
            SnackbarEvent(
                messageResId = resId,
                messageFormatArgs = args,
                duration = duration
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        bt.close()
    }
}