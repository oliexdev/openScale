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
package com.health.openscale.testutil

import androidx.compose.runtime.Composable
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.ui.shared.SnackbarEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory fake of [BluetoothFacade] for ViewModel tests — no BLE stack, no device.
 * Observable state is backed by mutable flows the test can drive; actions record call markers.
 */
class FakeBluetoothFacade : BluetoothFacade {

    override val snackbarEventsFromConnector = MutableSharedFlow<SnackbarEvent>(replay = 0, extraBufferCapacity = 8)
    override val scannedDevices = MutableStateFlow<List<ScannedDeviceInfo>>(emptyList())
    override val isScanning = MutableStateFlow(false)
    override val scanError = MutableStateFlow<String?>(null)
    override val connectedDeviceAddress = MutableStateFlow<String?>(null)
    override val connectionStatus = MutableStateFlow(ConnectionStatus.NONE)
    override val connectionError = MutableStateFlow<String?>(null)
    override val pendingUserInteractionEvent = MutableStateFlow<BluetoothEvent.UserInteractionRequired?>(null)
    override val savedDevice = MutableStateFlow<ScannedDeviceInfo?>(null)
    override val savedDeviceSupport = MutableStateFlow<DeviceSupport?>(null)

    // --- test knobs / call markers ---
    var bluetoothEnabled = true
    var startScanDurationMs: Long? = null
    var stopScanCalled = false
    var connectCalled = false
    var autoConnectCalled = false
    var disconnectCalled = false
    var savedAsPreferred: ScannedDeviceInfo? = null
    var removeSavedDeviceCalled = false
    var lastSavedTuning: TuningProfile? = null
    var clearErrorsCalled = false
    var clearPendingCalled = false
    var feedback: Pair<BluetoothEvent.UserInteractionType, Any>? = null
    var closeCalled = false

    override fun startScan(durationMs: Long) { startScanDurationMs = durationMs; isScanning.value = true }
    override fun stopScan() { stopScanCalled = true; isScanning.value = false }
    override fun connectToSavedDevice() { connectCalled = true }
    override fun attemptAutoConnectToSavedDevice() { autoConnectCalled = true }
    override fun disconnect() { disconnectCalled = true }
    override fun saveAsPreferred(device: ScannedDeviceInfo) { savedAsPreferred = device }
    override fun removeSavedDevice() { removeSavedDeviceCalled = true }
    override fun setSavedTuning(profile: TuningProfile) { lastSavedTuning = profile }
    override fun clearErrors() { clearErrorsCalled = true }
    override fun clearPendingUserInteraction() { clearPendingCalled = true }
    override fun provideUserInteractionFeedback(type: BluetoothEvent.UserInteractionType, feedbackData: Any) {
        feedback = type to feedbackData
    }
    override fun isBluetoothEnabled(): Boolean = bluetoothEnabled
    override fun close() { closeCalled = true }

    @Composable
    override fun DeviceConfigurationUi() { /* no-op in tests */ }
}
