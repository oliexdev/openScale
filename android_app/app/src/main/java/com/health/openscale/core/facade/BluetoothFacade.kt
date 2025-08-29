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
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.SnackbarDuration
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.data.User
import com.health.openscale.core.database.DatabaseRepository
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

    val savedScaleAddress: StateFlow<String?> =
        settingsFacade.savedBluetoothScaleAddress.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)
    val savedScaleName: StateFlow<String?> =
        settingsFacade.savedBluetoothScaleName.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

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

    fun connectTo(device: ScannedDeviceInfo) {
        val (supported, handlerName) = scaleFactory.getSupportingHandlerInfo(device)
        if (!supported) {
            LogManager.w(TAG, "Device ${device.name} is not supported by this app")
            return
        }
        device.isSupported = true
        device.determinedHandlerDisplayName = handlerName
        connection.connectToDevice(device)
    }

    fun connectToSavedDevice() {
        scope.launch {
            val address = savedScaleAddress.value
            val name = savedScaleName.value
            if (address == null || name == null) {
                return@launch
            }
            val already = (connectionStatus.value == ConnectionStatus.CONNECTED || connectionStatus.value == ConnectionStatus.CONNECTING) &&
                    connectedDeviceAddress.value == address
            if (!already) {
                val dev = ScannedDeviceInfo(name, address, 0, emptyList(), null, false, null)
                connectTo(dev)
            }
        }
    }

    fun attemptAutoConnectToSavedDevice() = connectToSavedDevice()

    fun disconnect() = connection.disconnect()

    fun saveAsPreferred(device: ScannedDeviceInfo) {
        scope.launch {
            val display = device.name ?: application.getString(R.string.unknown_device)
            settingsFacade.saveBluetoothScale(device.address, display)
        }
    }

    fun provideUserInteractionFeedback(type: BluetoothEvent.UserInteractionType, feedbackData: Any) {
        val user = currentAppUser.value ?: run {
            connection.clearUserInteractionEvent()
            return
        }
        connection.provideUserInteractionFeedback(type, user.id, feedbackData, Handler(Looper.getMainLooper()))
        connection.clearUserInteractionEvent()
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
