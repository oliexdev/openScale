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
package com.health.openscale.ui.screen.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.SnackbarDuration
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.User
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.SharedViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Represents the various states of a Bluetooth connection.
 */
enum class ConnectionStatus {
    NONE, IDLE, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED
}

/**
 * ViewModel for Bluetooth interactions: scanning, connection, data handling.
 * Coordinates with [BluetoothScannerManager] and [BluetoothConnectionManager].
 */
class BluetoothViewModel(
    private val application: Application, // Used for context and string resources
    val sharedViewModel: SharedViewModel
) : ViewModel() {

    private companion object {
        const val TAG = "BluetoothViewModel"
        const val SCAN_DURATION_MS = 20000L
    }

    private val databaseRepository = sharedViewModel.databaseRepository
    val userSettingsRepository = sharedViewModel.userSettingRepository

    private var currentAppUser: User? = null
    private var currentBtScaleUser: ScaleUser? = null
    private var currentAppUserId: Int = 0

    private val scaleFactory = ScaleFactory(application.applicationContext, databaseRepository)
    private val bluetoothScannerManager = BluetoothScannerManager(application, viewModelScope, scaleFactory)

    private val bluetoothConnectionManager = BluetoothConnectionManager(
        context = application.applicationContext,
        scope = viewModelScope,
        scaleFactory = scaleFactory,
        databaseRepository = databaseRepository,
        sharedViewModel = sharedViewModel,
        getCurrentScaleUser = { currentBtScaleUser },
        onSavePreferredDevice = { address, name ->
            // Snackbar for user feedback when a device is set as preferred by ConnectionManager
            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_scale_saved_as_preferred, name),
                SnackbarDuration.Short
            )
        }
    )

    val scannedDevices: StateFlow<List<ScannedDeviceInfo>> = bluetoothScannerManager.scannedDevices
    val isScanning: StateFlow<Boolean> = bluetoothScannerManager.isScanning
    val scanError: StateFlow<String?> = bluetoothScannerManager.scanError

    val connectedDeviceAddress: StateFlow<String?> = bluetoothConnectionManager.connectedDeviceAddress
    val connectionStatus: StateFlow<ConnectionStatus> = bluetoothConnectionManager.connectionStatus
    val connectionError: StateFlow<String?> = bluetoothConnectionManager.connectionError

    private val _permissionsGranted = MutableStateFlow(checkInitialPermissions())
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    val savedScaleAddress: StateFlow<String?> = userSettingsRepository.savedBluetoothScaleAddress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)
    val savedScaleName: StateFlow<String?> = userSettingsRepository.savedBluetoothScaleName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val pendingUserInteractionEvent: StateFlow<BluetoothEvent.UserInteractionRequired?> =
        bluetoothConnectionManager.userInteractionRequiredEvent

    init {
        LogManager.i(TAG, "ViewModel initialized. Setting up user observation.")
        observeUserChanges()
    }

    private fun observeUserChanges() {
        viewModelScope.launch {
            sharedViewModel.selectedUser.filterNotNull().collectLatest { appUser ->
                LogManager.d(TAG, "User selected via SharedViewModel: ${appUser.name}. Updating context.")
                updateCurrentUserContext(appUser)
            }
        }
        viewModelScope.launch {
            if (sharedViewModel.selectedUser.value == null) {
                userSettingsRepository.currentUserId.filterNotNull().collectLatest { userId ->
                    if (userId != 0) {
                        databaseRepository.getUserById(userId).filterNotNull().firstOrNull()?.let { userDetails ->
                            if (currentAppUserId != userDetails.id) {
                                LogManager.d(TAG, "User changed via UserSettingsRepository: ${userDetails.name}. Updating context.")
                                updateCurrentUserContext(userDetails)
                            }
                        } ?: run {
                            LogManager.w(TAG, "User with ID $userId from settings not found in database. Clearing context.")
                            clearUserContext()
                        }
                    } else {
                        LogManager.d(TAG, "No current user ID set in settings. Clearing context.")
                        clearUserContext()
                    }
                }
            }
        }
    }

    private fun updateCurrentUserContext(appUser: User) {
        currentAppUser = appUser
        currentAppUserId = appUser.id
        currentBtScaleUser = convertAppUserToBtScaleUser(appUser)
        LogManager.i(TAG, "User context updated for Bluetooth operations: User '${currentBtScaleUser?.userName}' (App ID: ${currentAppUserId})")
    }

    private fun clearUserContext() {
        currentAppUser = null
        currentAppUserId = 0
        currentBtScaleUser = null
        LogManager.i(TAG, "User context cleared for Bluetooth operations.")
    }

    private fun convertAppUserToBtScaleUser(appUser: User): ScaleUser {
        return ScaleUser().apply {
            id = appUser.id
            userName = appUser.name
            birthday = Date(appUser.birthDate)
            bodyHeight = appUser.heightCm ?: 0f
            gender = appUser.gender
        }
    }

    @SuppressLint("MissingPermission")
    fun requestStartDeviceScan() {
        LogManager.i(TAG, "User requested to start device scan.")
        refreshPermissionsStatus()

        if (!permissionsGranted.value) {
            LogManager.w(TAG, "Scan request denied: Bluetooth permissions missing.")
            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_permissions_required_to_scan),
                SnackbarDuration.Long
            )
            return
        }
        if (!isBluetoothEnabled()) {
            LogManager.w(TAG, "Scan request denied: Bluetooth is disabled.")
            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_bluetooth_disabled_to_scan),
                SnackbarDuration.Long
            )
            return
        }
        clearAllErrors()
        LogManager.d(TAG, "Prerequisites met. Delegating scan start to BluetoothScannerManager.")
        bluetoothScannerManager.startScan(SCAN_DURATION_MS)
    }

    fun requestStopDeviceScan() {
        LogManager.i(TAG, "User requested to stop device scan. Delegating to BluetoothScannerManager.")
        bluetoothScannerManager.stopScan()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceInfo: ScannedDeviceInfo) {
        val deviceDisplayName = deviceInfo.name ?: deviceInfo.address
        LogManager.i(TAG, "User requested to connect to device: $deviceDisplayName")

        if (isScanning.value) {
            LogManager.d(TAG, "Scan is active, stopping it before initiating connection to $deviceDisplayName.")
            requestStopDeviceScan()
        }

        if (!validateConnectionPrerequisites(deviceDisplayName, isManualConnect = true)) {
            return
        }

        LogManager.d(TAG, "Prerequisites for connecting to $deviceDisplayName met. Delegating to BluetoothConnectionManager.")
        bluetoothConnectionManager.connectToDevice(deviceInfo)
    }

    @SuppressLint("MissingPermission")
    fun connectToSavedDevice() {
        viewModelScope.launch {
            val address = savedScaleAddress.value
            val name = savedScaleName.value
            LogManager.i(TAG, "User or system requested to connect to saved device: Name='$name', Address='$address'")

            if (isScanning.value) {
                LogManager.d(TAG, "Scan is active, stopping it before connecting to saved device '$name'.")
                requestStopDeviceScan()
            }

            if (!validateConnectionPrerequisites(name, isManualConnect = false)) {
                return@launch
            }

            if (address != null && name != null) {
                LogManager.d(TAG, "Re-evaluating support for saved device '$name' ($address) using ScaleFactory.")
                val deviceInfoForConnect = ScannedDeviceInfo(
                    name = name, address = address, rssi = 0, serviceUuids = emptyList(),
                    manufacturerData = null, isSupported = false, determinedHandlerDisplayName = null
                )
                val (isPotentiallySupported, handlerNameFromFactory) = scaleFactory.getSupportingHandlerInfo(deviceInfoForConnect)
                deviceInfoForConnect.isSupported = isPotentiallySupported
                deviceInfoForConnect.determinedHandlerDisplayName = handlerNameFromFactory

                if (!deviceInfoForConnect.isSupported) {
                    LogManager.w(TAG, "Saved device '$name' ($address) is currently not supported. Connection aborted.")
                    sharedViewModel.showSnackbar(
                        application.getString(R.string.bt_snackbar_saved_scale_no_longer_supported, name),
                        SnackbarDuration.Long
                    )
                    return@launch
                }
                LogManager.d(TAG, "Saved device '$name' is supported. Delegating connection to BluetoothConnectionManager.")
                bluetoothConnectionManager.connectToDevice(deviceInfoForConnect)
            } else {
                LogManager.w(TAG, "Attempted to connect to saved device, but no device is saved.")
                sharedViewModel.showSnackbar(
                    application.getString(R.string.bt_snackbar_no_scale_saved),
                    SnackbarDuration.Short
                )
            }
        }
    }

    /**
     * Validates common prerequisites for initiating a Bluetooth connection.
     * @return `true` if all prerequisites are met, `false` otherwise.
     */
    private fun validateConnectionPrerequisites(deviceNameForMessage: String?, isManualConnect: Boolean): Boolean {
        refreshPermissionsStatus()

        val devicePlaceholder = application.getString(R.string.device_placeholder_name) // "the device"

        if (!permissionsGranted.value) {
            val errorMsg = application.getString(
                R.string.bt_snackbar_permissions_required_to_connect,
                deviceNameForMessage ?: devicePlaceholder
            )
            LogManager.w(TAG, "Connection prerequisite failed: $errorMsg")
            if (isManualConnect) {
                bluetoothConnectionManager.setExternalConnectionError(errorMsg)
            } else {
                sharedViewModel.showSnackbar(errorMsg, SnackbarDuration.Long)
            }
            return false
        }
        if (!isBluetoothEnabled()) {
            val errorMsg = application.getString(
                R.string.bt_snackbar_bluetooth_disabled_to_connect,
                deviceNameForMessage ?: devicePlaceholder
            )
            LogManager.w(TAG, "Connection prerequisite failed: $errorMsg")
            if (isManualConnect) {
                bluetoothConnectionManager.setExternalConnectionError(errorMsg)
            } else {
                sharedViewModel.showSnackbar(errorMsg, SnackbarDuration.Long)
            }
            return false
        }
        return true
    }

    fun disconnectDevice() {
        LogManager.i(TAG, "User requested to disconnect device. Delegating to BluetoothConnectionManager.")
        bluetoothConnectionManager.disconnect()
    }

    fun clearAllErrors() {
        LogManager.d(TAG, "Clearing all scan and connection errors.")
        bluetoothScannerManager.clearScanError()
        bluetoothConnectionManager.clearConnectionError()
    }

    fun clearPendingUserInteraction() {
        LogManager.d(TAG, "Requesting to clear pending user interaction event.")
        bluetoothConnectionManager.clearUserInteractionEvent()
    }

    fun processUserInteraction(interactionType: UserInteractionType, feedbackData: Any) {
        viewModelScope.launch {
            val localCurrentAppUser = currentAppUser // Use local copy for thread safety check
            if (localCurrentAppUser == null || localCurrentAppUser.id == 0) {
                sharedViewModel.showSnackbar(
                    application.getString(R.string.bt_snackbar_error_no_app_user_selected),
                    SnackbarDuration.Short // Assuming short duration, adjust if needed
                )
                bluetoothConnectionManager.clearUserInteractionEvent()
                return@launch
            }
            val appUserId = localCurrentAppUser.id

            // BluetoothConnectionManager now internally uses viewModelScope for its operations,
            // so direct Handler passing might be less critical if its methods are suspend or use its own scope.
            // If direct MainLooper operations are still needed within provideUserInteractionFeedback:
            val uiHandler = Handler(Looper.getMainLooper())

            bluetoothConnectionManager.provideUserInteractionFeedback(
                interactionType,
                appUserId,
                feedbackData,
                uiHandler // Pass if strictly needed by the manager for immediate UI thread tasks
            )

            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_user_input_processed),
                SnackbarDuration.Short
            )

            clearPendingUserInteraction()
        }
    }

    fun saveDeviceAsPreferred(device: ScannedDeviceInfo) {
        viewModelScope.launch {
            val nameToSave = device.name ?: application.getString(R.string.unknown_scale_name) // Default name from resources
            LogManager.i(TAG, "User requested to save device as preferred: Name='${device.name}', Address='${device.address}'. Saving as '$nameToSave'.")
            userSettingsRepository.saveBluetoothScale(device.address, nameToSave)
            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_scale_saved_as_preferred, nameToSave),
                SnackbarDuration.Short
            )
        }
    }

    private fun checkInitialPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun refreshPermissionsStatus() {
        val currentStatus = checkInitialPermissions()
        if (_permissionsGranted.value != currentStatus) {
            _permissionsGranted.value = currentStatus
            LogManager.i(TAG, "Bluetooth permission status refreshed: ${if (currentStatus) "Granted" else "Denied"}.")
        }
    }

    fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        return bluetoothManager?.adapter?.isEnabled ?: false
    }

    @SuppressLint("MissingPermission")
    fun attemptAutoConnectToSavedScale() {
        viewModelScope.launch {
            val address = savedScaleAddress.value
            val name = savedScaleName.value

            if (address != null && name != null) {
                LogManager.i(TAG, "Attempting auto-connect to saved scale: '$name' ($address).")
                if ((connectionStatus.value == ConnectionStatus.CONNECTED || connectionStatus.value == ConnectionStatus.CONNECTING) &&
                    connectedDeviceAddress.value == address
                ) {
                    LogManager.d(TAG, "Auto-connect: Already connected or connecting to '$name' ($address). No action needed.")
                    return@launch
                }
                connectToSavedDevice()
            } else {
                LogManager.d(TAG, "Auto-connect attempt: No saved scale found.")
                // Optionally show a (non-blocking) snackbar if desired, though usually auto-attempts are silent on "not found"
                // sharedViewModel.showSnackbar(application.getString(R.string.bt_snackbar_no_scale_saved), SnackbarDuration.Short)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        LogManager.i(TAG, "BluetoothViewModel onCleared. Releasing resources from managers.")
        bluetoothScannerManager.close()
        bluetoothConnectionManager.close()
        LogManager.i(TAG, "BluetoothViewModel onCleared completed.")
    }
}
