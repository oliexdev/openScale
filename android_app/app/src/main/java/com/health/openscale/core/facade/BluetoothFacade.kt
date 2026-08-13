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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
 * Abstraction over Bluetooth orchestration consumed by [com.health.openscale.ui.screen.settings.BluetoothViewModel].
 *
 * Extracted as an interface so the ViewModel can be unit-tested against a fake without the real
 * BLE stack. The production implementation is [BluetoothFacadeImpl]; Hilt binds it via
 * [BluetoothFacadeBindsModule]. Behaviour is unchanged versus the previous concrete facade.
 */
interface BluetoothFacade {
    val snackbarEventsFromConnector: SharedFlow<SnackbarEvent>

    val scannedDevices: StateFlow<List<ScannedDeviceInfo>>
    val isScanning: StateFlow<Boolean>
    val scanError: StateFlow<String?>

    val connectedDeviceAddress: StateFlow<String?>
    val connectionStatus: StateFlow<ConnectionStatus>
    val connectionError: StateFlow<String?>

    val pendingUserInteractionEvent: StateFlow<BluetoothEvent.UserInteractionRequired?>

    val savedDevice: StateFlow<ScannedDeviceInfo?>
    val savedDeviceSupport: StateFlow<DeviceSupport?>

    fun startScan(durationMs: Long)
    fun stopScan()
    fun connectToSavedDevice()
    fun attemptAutoConnectToSavedDevice()
    fun disconnect()
    fun saveAsPreferred(device: ScannedDeviceInfo)
    fun removeSavedDevice()
    fun setSavedTuning(profile: TuningProfile)
    fun clearErrors()
    fun clearPendingUserInteraction()
    fun provideUserInteractionFeedback(type: BluetoothEvent.UserInteractionType, feedbackData: Any)
    fun isBluetoothEnabled(): Boolean
    fun close()

    @Composable
    fun DeviceConfigurationUi()
}

/**
 * Facade responsible for orchestrating Bluetooth operations.
 *
 * This class encapsulates scanning, connection management,
 * saved device handling, and user context mapping (App [User] → [ScaleUser]).
 * It exposes state as [StateFlow]s for UI consumption and emits one-shot messages
 * for transient events (e.g., Snackbar notifications).
 *
 * By consolidating Bluetooth-related logic here, BluetoothViewModel stays
 * lightweight and UI-focused.
 */
@Singleton
class BluetoothFacadeImpl @Inject constructor(
    private val application: Application,
    private val scaleFactory: ScaleFactory,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
    private val settingsFacade: SettingsFacade,
) : BluetoothFacade {
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

    override val snackbarEventsFromConnector: SharedFlow<SnackbarEvent> = connection.snackbarEvents

    // --- Publicly observable state ---
    override val scannedDevices: StateFlow<List<ScannedDeviceInfo>> = scanner.scannedDevices
    override val isScanning: StateFlow<Boolean> = scanner.isScanning
    override val scanError: StateFlow<String?> = scanner.scanError

    override val connectedDeviceAddress: StateFlow<String?> = connection.connectedDeviceAddress
    override val connectionStatus: StateFlow<ConnectionStatus> = connection.connectionStatus
    override val connectionError: StateFlow<String?> = connection.connectionError

    override val pendingUserInteractionEvent: StateFlow<BluetoothEvent.UserInteractionRequired?> =
        connection.userInteractionRequiredEvent

    override fun clearPendingUserInteraction() {
        connection.clearUserInteractionEvent()
    }

    override val savedDevice: StateFlow<ScannedDeviceInfo?> =
        settingsFacade.observeSavedDevice()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    // internal only (used by savedDeviceSupport); not part of the public interface
    val savedTuningProfile: StateFlow<TuningProfile> =
        combine(savedDevice, settingsFacade.savedBluetoothTuneProfile) { dev, stored ->
            if (dev == null) TuningProfile.Balanced
            else runCatching { TuningProfile.valueOf(stored ?: "Balanced") }
                .getOrDefault(TuningProfile.Balanced)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), TuningProfile.Balanced)

    override val savedDeviceSupport: StateFlow<DeviceSupport?> =
        combine(savedDevice, savedTuningProfile) { dev, tuning ->
            if (dev == null) return@combine null
            val base = scaleFactory.getDeviceSupportFor(dev.name, dev.address) ?: return@combine null
            base.copy(tuningProfile = tuning)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    override fun setSavedTuning(profile: TuningProfile) {
        scope.launch {
            settingsFacade.saveBluetoothTuneProfile(profile.name)
        }
    }

    @Composable
    override fun DeviceConfigurationUi() {
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
            bodyHeight = u.heightCm
            gender = u.gender
        }

    // --- API: Scanning & Connection ---
    override fun startScan(durationMs: Long) {
        clearErrors()
        scanner.startScan(durationMs)
    }

    override fun stopScan() = scanner.stopScan()

    override fun connectToSavedDevice() {
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

    override fun attemptAutoConnectToSavedDevice() = connectToSavedDevice()

    override fun disconnect() = connection.disconnect()

    override fun saveAsPreferred(device: ScannedDeviceInfo) {
        scope.launch {
            settingsFacade.saveSavedDevice(device)
        }
    }

    override fun removeSavedDevice() {
        scope.launch {
            settingsFacade.clearSavedBluetoothScale()
            settingsFacade.clearBleDriverSettings()
            settingsFacade.saveBluetoothTuneProfile(null)
        }
    }

    override fun provideUserInteractionFeedback(type: BluetoothEvent.UserInteractionType, feedbackData: Any) {
        val user = currentAppUser.value ?: run {
            connection.clearUserInteractionEvent()
            return
        }
        scope.launch {
            connection.provideUserInteractionFeedback(type, user.id, feedbackData)
            connection.clearUserInteractionEvent()
        }
    }

    override fun clearErrors() {
        scanner.clearScanError()
        connection.clearConnectionError()
    }

    override fun isBluetoothEnabled(): Boolean {
        val mgr = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        return mgr?.adapter?.isEnabled ?: false
    }

    override fun close() {
        scanner.close()
        connection.close()
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface BluetoothFacadeBindsModule {
    // Invoked by Hilt's generated code only; the IDE cannot see that usage.
    @Suppress("unused")
    @Binds
    @Singleton
    fun bindBluetoothFacade(impl: BluetoothFacadeImpl): BluetoothFacade
}
