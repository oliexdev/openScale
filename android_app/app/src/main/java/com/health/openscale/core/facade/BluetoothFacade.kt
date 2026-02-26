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
package com.health.openscale.core.facade

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.data.User
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.service.BluetoothScannerManager
import com.health.openscale.core.service.BleConnector
import com.health.openscale.ui.shared.SnackbarEvent

/**
 * Facade responsible for orchestrating Bluetooth operations.
 *
 * This class encapsulates scanning, connection management,
 * saved device handling, and user context mapping (App [User] → [ScaleUser]).
 * It exposes state as [StateFlow]s for UI consumption and emits one-shot messages
 * for transient events (e.g., Snackbar notifications).
 *
 * By consolidating Bluetooth-related logic here, [BluetoothViewModel] stays
 * lightweight and UI-focused.
 */
@Singleton
class BluetoothFacade @Inject constructor(
    private val application: Application,
    private val scaleFactory: ScaleFactory,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
    private val settingsFacade: SettingsFacade,
) {
    private val TAG = "BluetoothFacade"

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    // --- Managers ---
    private val scanner = BluetoothScannerManager(application, scope, scaleFactory)
    private val connection = BleConnector(
        scope = scope,
        scaleFactory = scaleFactory,
        measurementFacade = measurementFacade,
        getCurrentScaleUser = { currentBtScaleUser.value }
    )

    val snackbarEventsFromConnector: SharedFlow<SnackbarEvent> = connection.snackbarEvents

    // --- Publicly observable state ---
    val scannedDevices: StateFlow<List<ScannedDeviceInfo>> = scanner.scannedDevices
    val isScanning: StateFlow<Boolean> = scanner.isScanning
    val scanError: StateFlow<String?> = scanner.scanError

    val connectedDeviceAddress: StateFlow<String?> = connection.connectedDeviceAddress
    val connectionStatus: StateFlow<ConnectionStatus> = connection.connectionStatus
    val connectionError: StateFlow<String?> = connection.connectionError

    val pendingUserInteractionEvent: StateFlow<BluetoothEvent.UserInteractionRequired?> =
        connection.userInteractionRequiredEvent

    fun clearPendingUserInteraction() {
        connection.clearUserInteractionEvent()
    }

    val savedDevice: StateFlow<ScannedDeviceInfo?> =
        settingsFacade.observeSavedDevice()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    val savedTuningProfile: StateFlow<TuningProfile> =
        combine(savedDevice, settingsFacade.savedBluetoothTuneProfile) { dev, stored ->
            if (dev == null) TuningProfile.Balanced
            else runCatching { TuningProfile.valueOf(stored ?: "Balanced") }
                .getOrDefault(TuningProfile.Balanced)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), TuningProfile.Balanced)

    val savedDeviceSupport: StateFlow<DeviceSupport?> =
        combine(savedDevice, savedTuningProfile) { dev, tuning ->
            if (dev == null) return@combine null
            val base = scaleFactory.getDeviceSupportFor(dev.name, dev.address) ?: return@combine null
            base.copy(tuningProfile = tuning)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    fun setSavedTuning(profile: TuningProfile) {
        scope.launch {
            settingsFacade.saveBluetoothTuneProfile(profile.name)
        }
    }

    @Composable
    fun DeviceConfigurationUi() {
        val device by savedDevice.collectAsState()

        device?.let { dev ->
            // Create or remember the communicator for the current saved device
            val communicator = remember(dev) { scaleFactory.createCommunicator(dev) }

            // Render the device-specific UI directly from the communicator
            communicator?.DeviceConfigurationUi()
        }
    }

    // --- Current user context ---
    private val currentAppUser = MutableStateFlow<User?>(null)
    private val currentBtScaleUser = MutableStateFlow<ScaleUser?>(null)

    init {
        observeCurrentUser()
    }

    private fun observeCurrentUser() {
        scope.launch {
            userFacade.observeSelectedUser()
                .collect { user ->
                    currentAppUser.value = user
                    currentBtScaleUser.value = user?.let { toScaleUser(it) }
                    LogManager.i(TAG, "User context updated -> ${user?.name ?: "none"}")
                }
        }
    }

    private fun toScaleUser(u: User): ScaleUser =
        ScaleUser().apply {
            id = u.id
            userName = u.name
            birthday = Date(u.birthDate)
            bodyHeight = u.heightCm ?: 0f
            gender = u.gender
        }

    // --- API: Scanning & Connection ---
    fun startScan(durationMs: Long) {
        clearErrors()
        scanner.startScan(durationMs)
    }

    fun stopScan() = scanner.stopScan()

    fun connectToSavedDevice() {
        scope.launch {
            val dev = savedDevice.value
            if (dev == null) {
                LogManager.d(TAG, "No saved device snapshot found.")
                return@launch
            }

            val already = (connectionStatus.value == ConnectionStatus.CONNECTED ||
                    connectionStatus.value == ConnectionStatus.CONNECTING) &&
                    connectedDeviceAddress.value == dev.address
            if (already) return@launch

            val (supported, handlerName) = scaleFactory.getSupportingHandlerInfo(dev)
            if (!supported) {
                LogManager.w(TAG, "Saved device '${dev.name}' is not recognized as supported anymore.")
                return@launch
            }
            dev.isSupported = true
            dev.determinedHandlerDisplayName = handlerName

            connection.connectToDevice(dev)
        }
    }

    fun attemptAutoConnectToSavedDevice() = connectToSavedDevice()

    fun disconnect() = connection.disconnect()

    fun saveAsPreferred(device: ScannedDeviceInfo) {
        scope.launch {
            settingsFacade.saveSavedDevice(device)
        }
    }

    fun removeSavedDevice() {
        scope.launch {
            settingsFacade.clearSavedBluetoothScale()
            settingsFacade.clearBleDriverSettings()
            settingsFacade.saveBluetoothTuneProfile(null)
        }
    }

    fun provideUserInteractionFeedback(type: BluetoothEvent.UserInteractionType, feedbackData: Any) {
        val user = currentAppUser.value ?: run {
            connection.clearUserInteractionEvent()
            return
        }
        scope.launch {
            connection.provideUserInteractionFeedback(type,user.id, feedbackData)
            connection.clearUserInteractionEvent()
        }
    }

    fun clearErrors() {
        scanner.clearScanError()
        connection.clearConnectionError()
    }

    fun isBluetoothEnabled(): Boolean {
        val mgr = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        return mgr?.adapter?.isEnabled ?: false
    }

    fun close() {
        scanner.close()
        connection.close()
    }
}
