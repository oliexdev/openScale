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
import androidx.compose.material3.SnackbarDuration // Keep if used directly, otherwise remove
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.core.bluetooth.BluetoothEvent
// ScaleCommunicator no longer needed directly here
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
// ScaleMeasurement no longer needed directly here for saveMeasurementFromEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.scalesJava.BluetoothCommunication
// Measurement, MeasurementTypeKey, MeasurementValue no longer needed directly here
import com.health.openscale.core.data.User
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.SharedViewModel
// kotlinx.coroutines.Dispatchers no longer needed directly here for saveMeasurement
// kotlinx.coroutines.Job no longer needed directly here for communicatorJob
// kotlinx.coroutines.delay no longer needed directly here for disconnect-timeout
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
    /** No connection activity. */
    NONE,
    /** Bluetooth adapter is present and enabled, but not actively scanning or connected. */
    IDLE,
    /** No active connection to a device. */
    DISCONNECTED,
    /** Attempting to establish a connection to a device. */
    CONNECTING,
    /** Successfully connected to a device. */
    CONNECTED,
    /** In the process of disconnecting from a device. */
    DISCONNECTING,
    /** A connection attempt or an established connection has failed. */
    FAILED
}

/**
 * ViewModel responsible for managing Bluetooth interactions, including device scanning,
 * connection, and data handling. It coordinates with [BluetoothScannerManager] for scanning
 * and [BluetoothConnectionManager] for connection lifecycle and data events.
 *
 * This ViewModel also manages user context relevant to Bluetooth operations and exposes
 * StateFlows for UI observation.
 *
 * @param application The application context.
 * @param sharedViewModel A [SharedViewModel] instance for accessing shared resources like
 *                        repositories and for displaying global UI messages (e.g., Snackbars).
 */
class BluetoothViewModel(
    private val application: Application,
    val sharedViewModel: SharedViewModel
) : ViewModel() {

    private companion object {
        const val TAG = "BluetoothViewModel"
        const val SCAN_DURATION_MS = 20000L // Default scan duration: 20 seconds
    }

    // Access to repositories is passed to the managers.
    private val databaseRepository = sharedViewModel.databaseRepository
    val userSettingsRepository = sharedViewModel.userSettingRepository

    // --- User Context (managed by ViewModel, used by ConnectionManager) ---
    private var currentAppUser: User? = null
    private var currentBtScaleUser: ScaleUser? = null // Derived from currentAppUser for Bluetooth operations
    private var currentAppUserId: Int = 0

    // --- Dependencies (ScaleFactory is passed to managers) ---
    private val scaleFactory = ScaleFactory(application.applicationContext, databaseRepository)

    // --- BluetoothScannerManager (manages device scanning) ---
    private val bluetoothScannerManager = BluetoothScannerManager(application, viewModelScope, scaleFactory)

    // --- BluetoothConnectionManager (manages device connection and data events) ---
    private val bluetoothConnectionManager = BluetoothConnectionManager(
        context = application.applicationContext,
        scope = viewModelScope,
        scaleFactory = scaleFactory,
        databaseRepository = databaseRepository,
        sharedViewModel = sharedViewModel,
        getCurrentScaleUser = { currentBtScaleUser },
        onSavePreferredDevice = { address, name ->
            // Save preferred device when ConnectionManager successfully connects and indicates to do so.
            // Snackbar for user feedback can be shown here or in ConnectionManager; here is fine.
            viewModelScope.launch {
                userSettingsRepository.saveBluetoothScale(address, name)
                sharedViewModel.showSnackbar("$name saved as preferred scale.", SnackbarDuration.Short)
            }
        }
    )

    // --- Scan State Flows (from BluetoothScannerManager) ---
    /** Emits the list of discovered Bluetooth devices. */
    val scannedDevices: StateFlow<List<ScannedDeviceInfo>> = bluetoothScannerManager.scannedDevices
    /** Emits `true` if a Bluetooth scan is currently active, `false` otherwise. */
    val isScanning: StateFlow<Boolean> = bluetoothScannerManager.isScanning
    /** Emits error messages related to the scanning process, or null if no error. */
    val scanError: StateFlow<String?> = bluetoothScannerManager.scanError

    // --- Connection State Flows (from BluetoothConnectionManager) ---
    /** Emits the MAC address of the currently connected device, or null if not connected. */
    val connectedDeviceAddress: StateFlow<String?> = bluetoothConnectionManager.connectedDeviceAddress
    /** Emits the current [ConnectionStatus] of the Bluetooth device. */
    val connectionStatus: StateFlow<ConnectionStatus> = bluetoothConnectionManager.connectionStatus
    /** Emits connection-related error messages, or null if no error. */
    val connectionError: StateFlow<String?> = bluetoothConnectionManager.connectionError


    // --- Permissions and System State (managed by ViewModel) ---
    private val _permissionsGranted = MutableStateFlow(checkInitialPermissions())
    /** Emits `true` if all necessary Bluetooth permissions are granted, `false` otherwise. */
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    // --- Saved Device Info (for UI display and auto-connect logic) ---
    /** Emits the MAC address of the saved preferred Bluetooth scale, or null if none is saved. */
    val savedScaleAddress: StateFlow<String?> = userSettingsRepository.savedBluetoothScaleAddress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)
    /** Emits the name of the saved preferred Bluetooth scale, or null if none is saved. */
    val savedScaleName: StateFlow<String?> = userSettingsRepository.savedBluetoothScaleName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    // --- UI Interaction for User Selection (triggered by ConnectionManager callback) ---
    val pendingUserInteractionEvent: StateFlow<BluetoothEvent.UserInteractionRequired?> =
        bluetoothConnectionManager.userInteractionRequiredEvent

    init {
        LogManager.i(TAG, "ViewModel initialized. Setting up user observation.")
        observeUserChanges()
        // attemptAutoConnectToSavedScale() // Can be enabled if auto-connect on ViewModel init is desired.
    }

    /**
     * Observes changes to the selected application user and updates the Bluetooth user context accordingly.
     * This ensures that operations like saving measurements or providing user data to the scale
     * use the correct user profile.
     */
    private fun observeUserChanges() {
        viewModelScope.launch {
            // Observe user selected via SharedViewModel (e.g., user picker in UI)
            sharedViewModel.selectedUser.filterNotNull().collectLatest { appUser ->
                LogManager.d(TAG, "User selected via SharedViewModel: ${appUser.name}. Updating context.")
                updateCurrentUserContext(appUser)
            }
        }
        viewModelScope.launch {
            // Fallback: Observe current user ID from settings if no user is selected via SharedViewModel.
            // This handles scenarios where the app starts and a default user is already set.
            if (sharedViewModel.selectedUser.value == null) {
                userSettingsRepository.currentUserId.filterNotNull().collectLatest { userId ->
                    if (userId != 0) {
                        databaseRepository.getUserById(userId).filterNotNull().firstOrNull()?.let { userDetails ->
                            if (currentAppUserId != userDetails.id) { // Only update if the user actually changed.
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

    /**
     * Updates the internal state for the current application user and the corresponding Bluetooth scale user.
     * @param appUser The [User] object representing the current application user.
     */
    private fun updateCurrentUserContext(appUser: User) {
        currentAppUser = appUser
        currentAppUserId = appUser.id
        currentBtScaleUser = convertAppUserToBtScaleUser(appUser)
        LogManager.i(TAG, "User context updated for Bluetooth operations: User '${currentBtScaleUser?.userName}' (App ID: ${currentAppUserId})")
    }

    /**
     * Clears the current user context. Called when no user is selected or found.
     */
    private fun clearUserContext() {
        currentAppUser = null
        currentAppUserId = 0
        currentBtScaleUser = null
        LogManager.i(TAG, "User context cleared for Bluetooth operations.")
    }

    /**
     * Converts an application [User] object to a [ScaleUser] object,
     * which is the format expected by some Bluetooth scale drivers.
     * @param appUser The application [User] to convert.
     * @return A [ScaleUser] representation.
     */
    private fun convertAppUserToBtScaleUser(appUser: User): ScaleUser {
        return ScaleUser().apply {
            // Note: ScaleUser.id often corresponds to the on-scale user slot (1-N),
            // while appUser.id is the database ID. Some drivers might use appUser.id directly
            // if the scale supports arbitrary user identifiers or if we manage mapping externally.
            // For now, using appUser.id as a general identifier for the ScaleUser.
            id = appUser.id
            userName = appUser.name
            birthday = Date(appUser.birthDate) // Ensure birthDate is in millis
            bodyHeight = appUser.heightCm ?: 0f // Default to 0f if height is null
            gender = appUser.gender
        }
    }

    // --- Scan Control ---

    /**
     * Requests the [BluetoothScannerManager] to start scanning for devices.
     * Checks for necessary permissions and Bluetooth enabled status before initiating the scan.
     */
    @SuppressLint("MissingPermission") // Permissions are checked before calling the manager.
    fun requestStartDeviceScan() {
        LogManager.i(TAG, "User requested to start device scan.")
        refreshPermissionsStatus() // Ensure permission state is up-to-date.

        if (!permissionsGranted.value) {
            LogManager.w(TAG, "Scan request denied: Bluetooth permissions missing.")
            sharedViewModel.showSnackbar("Bluetooth permissions are required to scan for devices.", SnackbarDuration.Long)
            return
        }
        if (!isBluetoothEnabled()) {
            LogManager.w(TAG, "Scan request denied: Bluetooth is disabled.")
            sharedViewModel.showSnackbar("Bluetooth is disabled. Please enable it to scan for devices.", SnackbarDuration.Long)
            return
        }
        clearAllErrors() // Clear previous scan/connection errors.
        LogManager.d(TAG, "Prerequisites met. Delegating scan start to BluetoothScannerManager.")
        bluetoothScannerManager.startScan(SCAN_DURATION_MS)
    }

    /**
     * Requests the [BluetoothScannerManager] to stop an ongoing device scan.
     */
    fun requestStopDeviceScan() {
        LogManager.i(TAG, "User requested to stop device scan. Delegating to BluetoothScannerManager.")
        // The `isTimeout` parameter is an internal detail for the scanner manager;
        // from ViewModel's perspective, it's a manual stop request.
        bluetoothScannerManager.stopScan()
    }

    // --- Connection Control ---

    /**
     * Initiates a connection attempt to the specified Bluetooth device.
     * If a scan is active, it will be stopped first.
     * Prerequisites like permissions and Bluetooth status are validated.
     *
     * @param deviceInfo The [ScannedDeviceInfo] of the device to connect to.
     */
    @SuppressLint("MissingPermission") // Permissions are checked by validateConnectionPrerequisites.
    fun connectToDevice(deviceInfo: ScannedDeviceInfo) {
        val deviceDisplayName = deviceInfo.name ?: deviceInfo.address
        LogManager.i(TAG, "User requested to connect to device: $deviceDisplayName")

        if (isScanning.value) {
            LogManager.d(TAG, "Scan is active, stopping it before initiating connection to $deviceDisplayName.")
            requestStopDeviceScan()
            // Optional: A small delay could be added here if needed to ensure scan stop completes,
            // but usually the managers handle sequential operations gracefully.
            // viewModelScope.launch { delay(200) }
        }

        if (!validateConnectionPrerequisites(deviceDisplayName, isManualConnect = true)) {
            // validateConnectionPrerequisites logs and shows Snackbar for errors.
            return
        }

        LogManager.d(TAG, "Prerequisites for connecting to $deviceDisplayName met. Delegating to BluetoothConnectionManager.")
        bluetoothConnectionManager.connectToDevice(deviceInfo)
    }


    /**
     * Attempts to connect to the saved preferred Bluetooth scale.
     * Retrieves device info from [userSettingsRepository] and then delegates
     * to [BluetoothConnectionManager].
     */
    @SuppressLint("MissingPermission") // Permissions are checked by validateConnectionPrerequisites.
    fun connectToSavedDevice() {
        viewModelScope.launch {
            val address = savedScaleAddress.value
            val name = savedScaleName.value
            LogManager.i(TAG, "User or system requested to connect to saved device: Name='$name', Address='$address'")

            if (isScanning.value) {
                LogManager.d(TAG, "Scan is active, stopping it before connecting to saved device '$name'.")
                requestStopDeviceScan()
                // delay(200) // Optional delay
            }

            if (!validateConnectionPrerequisites(name, isManualConnect = false)) {
                // If isManualConnect is false, validateConnectionPrerequisites shows a Snackbar
                // but doesn't set an error in ConnectionManager, which is fine for auto-attempts.
                return@launch
            }

            if (address != null && name != null) {
                // For a saved device, we need to re-evaluate its support status using ScaleFactory,
                // as supported handlers might change with app updates.
                LogManager.d(TAG, "Re-evaluating support for saved device '$name' ($address) using ScaleFactory.")

                val deviceInfoForConnect = ScannedDeviceInfo(
                    name = name,
                    address = address,
                    rssi = 0, // RSSI is not relevant for a direct connection attempt to a saved device.
                    serviceUuids = emptyList(),
                    manufacturerData = null,
                    isSupported = false, // will be determined by getSupportingHandlerInfo
                    determinedHandlerDisplayName = null // will be determined by getSupportingHandlerInfo
                )

                val (isPotentiallySupported, handlerNameFromFactory) = scaleFactory.getSupportingHandlerInfo(deviceInfoForConnect)
                deviceInfoForConnect.isSupported = isPotentiallySupported
                deviceInfoForConnect.determinedHandlerDisplayName = handlerNameFromFactory

                if (!deviceInfoForConnect.isSupported) {
                    LogManager.w(TAG, "Saved device '$name' ($address) is currently not supported by ScaleFactory. Connection aborted.")
                    // This error is specific to connecting to a *saved* device that's no longer supported.
                    // The ConnectionManager might not have a dedicated error state for this nuance if it only expects
                    // ScannedDeviceInfo for connection attempts. Showing a Snackbar is a direct user feedback.
                    sharedViewModel.showSnackbar("Saved scale '$name' is no longer supported.", SnackbarDuration.Long)
                    // We don't want to set a generic connectionError in BluetoothConnectionManager here,
                    // as no connection attempt was made *through* it yet.
                    return@launch
                }
                LogManager.d(TAG, "Saved device '$name' is supported. Delegating connection to BluetoothConnectionManager.")
                bluetoothConnectionManager.connectToDevice(deviceInfoForConnect)
            } else {
                LogManager.w(TAG, "Attempted to connect to saved device, but no device is saved.")
                sharedViewModel.showSnackbar("No Bluetooth scale saved in settings.", SnackbarDuration.Short)
            }
        }
    }

    /**
     * Validates common prerequisites for initiating a Bluetooth connection.
     * Checks for permissions and Bluetooth enabled status.
     *
     * @param deviceName The name/identifier of the device for logging/messages.
     * @param isManualConnect `true` if this is a direct user action to connect, `false` for automated attempts.
     *                        This influences how errors are reported (e.g., setting an error in ConnectionManager vs. just a Snackbar).
     * @return `true` if all prerequisites are met, `false` otherwise.
     */
    private fun validateConnectionPrerequisites(deviceName: String?, isManualConnect: Boolean): Boolean {
        refreshPermissionsStatus() // Always get the latest permission status.

        if (!permissionsGranted.value) {
            val errorMsg = "Bluetooth permissions are required to connect to ${deviceName ?: "the device"}."
            LogManager.w(TAG, "Connection prerequisite failed for '${deviceName ?: "device"}': $errorMsg")
            if (isManualConnect) {
                // For manual attempts, set an error in the ConnectionManager to reflect in UI state.
                bluetoothConnectionManager.setExternalConnectionError(errorMsg)
            } else {
                // For automatic attempts (e.g., auto-connect), a Snackbar might be sufficient without altering permanent error state.
                sharedViewModel.showSnackbar(errorMsg, SnackbarDuration.Long)
            }
            return false
        }
        if (!isBluetoothEnabled()) {
            val errorMsg = "Bluetooth is disabled. Please enable it to connect to ${deviceName ?: "the device"}."
            LogManager.w(TAG, "Connection prerequisite failed for '${deviceName ?: "device"}': $errorMsg")
            if (isManualConnect) {
                bluetoothConnectionManager.setExternalConnectionError(errorMsg)
            } else {
                sharedViewModel.showSnackbar(errorMsg, SnackbarDuration.Long)
            }
            return false
        }
        // User ID check is now more nuanced and handled within BluetoothConnectionManager,
        // as its necessity can be handler-specific.
        // LogManager.d(TAG, "Connection prerequisites met for ${deviceName ?: "device"}.")
        return true
    }


    /**
     * Requests the [BluetoothConnectionManager] to disconnect from the currently connected device.
     */
    fun disconnectDevice() {
        LogManager.i(TAG, "User requested to disconnect device. Delegating to BluetoothConnectionManager.")
        bluetoothConnectionManager.disconnect()
    }

    // --- Error Handling ---

    /**
     * Clears all error states managed by both the scanner and connection managers.
     */
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
            val currentAppUser = sharedViewModel.selectedUser.value
            if (currentAppUser == null || currentAppUser.id == 0) {
                sharedViewModel.showSnackbar("Fehler: Kein App-Benutzer ausgewählt.")
                bluetoothConnectionManager.clearUserInteractionEvent()
                return@launch
            }
            val appUserId = currentAppUser.id

            clearPendingUserInteraction()
            val uiHandler = Handler(Looper.getMainLooper())

            bluetoothConnectionManager.provideUserInteractionFeedback(
                interactionType,
                appUserId,
                feedbackData,
                uiHandler
            )

            sharedViewModel.showSnackbar("Benutzereingabe verarbeitet.", SnackbarDuration.Short)
        }
    }

    // --- Device Preferences ---

    /**
     * Saves the given scanned device as the preferred Bluetooth scale in user settings.
     * @param device The [ScannedDeviceInfo] of the device to save.
     */
    fun saveDeviceAsPreferred(device: ScannedDeviceInfo) {
        viewModelScope.launch {
            val nameToSave = device.name ?: "Unknown Scale" // Provide a default name if null.
            LogManager.i(TAG, "User requested to save device as preferred: Name='${device.name}', Address='${device.address}'. Saving as '$nameToSave'.")
            userSettingsRepository.saveBluetoothScale(device.address, nameToSave)
            sharedViewModel.showSnackbar("'$nameToSave' saved as preferred scale.", SnackbarDuration.Short)
            // The savedScaleAddress/Name flows will update automatically, triggering any observers.
        }
    }

    // --- Permissions and System State Methods ---

    /**
     * Checks if the necessary Bluetooth permissions are currently granted.
     * Handles different permission sets for Android S (API 31) and above vs. older versions.
     * @return `true` if permissions are granted, `false` otherwise.
     */
    private fun checkInitialPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            // For older Android versions (below S)
            ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Refreshes the `permissionsGranted` StateFlow by re-checking the current permission status.
     * Should be called when the app regains focus or when permissions might have changed.
     */
    fun refreshPermissionsStatus() {
        val currentStatus = checkInitialPermissions()
        if (_permissionsGranted.value != currentStatus) {
            _permissionsGranted.value = currentStatus
            LogManager.i(TAG, "Bluetooth permission status refreshed: ${if (currentStatus) "Granted" else "Denied"}.")
        }
    }

    /**
     * Checks if the Bluetooth adapter is currently enabled on the device.
     * @return `true` if Bluetooth is enabled, `false` otherwise.
     */
    fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val isEnabled = bluetoothManager?.adapter?.isEnabled ?: false
        // LogManager.v(TAG, "Bluetooth enabled status check: $isEnabled") // Potentially too verbose for frequent checks
        return isEnabled
    }

    // Logic for handling Bluetooth events directly, saving measurements, observing communicator,
    // and releasing communicator has been moved to BluetoothConnectionManager.

    /**
     * Attempts to automatically connect to the saved preferred Bluetooth scale, if one exists
     * and the app is not already connected or connecting to it.
     * This might be called on ViewModel initialization or when the app comes to the foreground.
     */
    @SuppressLint("MissingPermission") // connectToSavedDevice handles permission checks.
    fun attemptAutoConnectToSavedScale() {
        viewModelScope.launch {
            val address = savedScaleAddress.value
            val name = savedScaleName.value

            if (address != null && name != null) {
                LogManager.i(TAG, "Attempting auto-connect to saved scale: '$name' ($address).")
                // Check if already connected or connecting to the target device.
                if ((connectionStatus.value == ConnectionStatus.CONNECTED || connectionStatus.value == ConnectionStatus.CONNECTING) &&
                    connectedDeviceAddress.value == address
                ) {
                    LogManager.d(TAG, "Auto-connect: Already connected or connecting to '$name' ($address). No action needed.")
                    return@launch
                }
                // Delegate to the standard method for connecting to a saved device.
                connectToSavedDevice()
            } else {
                LogManager.d(TAG, "Auto-connect attempt: No saved scale found.")
            }
        }
    }


    /**
     * Called when the ViewModel is about to be destroyed.
     * Ensures that resources used by Bluetooth managers are released (e.g., stopping scans,
     * disconnecting devices, closing underlying Bluetooth resources).
     */
    override fun onCleared() {
        super.onCleared()
        LogManager.i(TAG, "BluetoothViewModel onCleared. Releasing resources from managers.")
        bluetoothScannerManager.close()
        bluetoothConnectionManager.close()
        LogManager.i(TAG, "BluetoothViewModel onCleared completed.")
    }
}
