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
    private val application: Application, // Used for context and string resources
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
            // Snackbar for user feedback when a device is set as preferred by ConnectionManager
            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_scale_saved_as_preferred, name),
                SnackbarDuration.Short
            )
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
    /** Emits a [BluetoothEvent.UserInteractionRequired] when the connected scale needs user input (e.g., user selection). Null otherwise. */
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
        clearAllErrors() // Clear previous scan/connection errors.
        LogManager.d(TAG, "Prerequisites met. Delegating scan start to BluetoothScannerManager.")
        bluetoothScannerManager.startScan(SCAN_DURATION_MS)
    }

    /**
     * Requests the [BluetoothScannerManager] to stop an ongoing device scan.
     */
    fun requestStopDeviceScan() {
        LogManager.i(TAG, "User requested to stop device scan. Delegating to BluetoothScannerManager.")
        bluetoothScannerManager.stopScan()
    }

    // --- Connection Control ---

    /**
     * Attempts to connect to the saved preferred Bluetooth scale.
     * Retrieves device info from [userSettingsRepository] and then delegates
     * to [BluetoothConnectionManager]. It also re-evaluates device support via [ScaleFactory].
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
                // delay(200) // Optional delay if needed for scan to fully stop.
            }

            if (!validateConnectionPrerequisites(name, isManualConnect = false)) {
                // For automatic attempts, validateConnectionPrerequisites shows a Snackbar for errors.
                return@launch
            }

            if (address != null && name != null) {
                // For a saved device, we need to re-evaluate its support status using ScaleFactory,
                // as supported handlers might change with app updates or if the device firmware changed.
                LogManager.d(TAG, "Re-evaluating support for saved device '$name' ($address) using ScaleFactory.")

                // Create a temporary ScannedDeviceInfo object for the saved device.
                // RSSI, serviceUuids, manufacturerData are not critical here as ScaleFactory
                // primarily uses name/address for matching against known handlers.
                val deviceInfoForConnect = ScannedDeviceInfo(
                    name = name,
                    address = address,
                    rssi = 0,
                    serviceUuids = emptyList(),
                    manufacturerData = null,
                    isSupported = false, // This will be determined by getSupportingHandlerInfo
                    determinedHandlerDisplayName = null // This will also be determined
                )

                val (isPotentiallySupported, handlerNameFromFactory) = scaleFactory.getSupportingHandlerInfo(deviceInfoForConnect)
                deviceInfoForConnect.isSupported = isPotentiallySupported
                deviceInfoForConnect.determinedHandlerDisplayName = handlerNameFromFactory

                if (!deviceInfoForConnect.isSupported) {
                    LogManager.w(TAG, "Saved device '$name' ($address) is currently not supported by ScaleFactory. Connection aborted.")
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
     * Checks for permissions and Bluetooth enabled status.
     *
     * @param deviceNameForMessage The name/identifier of the device for logging/messages.
     * @param isManualConnect `true` if this is a direct user action to connect, `false` for automated attempts.
     *                        This influences how errors are reported (e.g., setting an error in ConnectionManager vs. just a Snackbar).
     * @return `true` if all prerequisites are met, `false` otherwise.
     */
    private fun validateConnectionPrerequisites(deviceNameForMessage: String?, isManualConnect: Boolean): Boolean {
        refreshPermissionsStatus() // Always get the latest permission status.

        val devicePlaceholder = application.getString(R.string.device_placeholder_name) // Default like "the device"

        if (!permissionsGranted.value) {
            val errorMsg = application.getString(
                R.string.bt_snackbar_permissions_required_to_connect,
                deviceNameForMessage ?: devicePlaceholder
            )
            LogManager.w(TAG, "Connection prerequisite failed for '${deviceNameForMessage ?: "unknown device"}': Bluetooth permissions missing.")
            if (isManualConnect) {
                // For manual attempts, set an error in the ConnectionManager to reflect in UI state.
                bluetoothConnectionManager.setExternalConnectionError(errorMsg)
            } else {
                // For automatic attempts (e.g., auto-connect), a Snackbar might be sufficient.
                sharedViewModel.showSnackbar(errorMsg, SnackbarDuration.Long)
            }
            return false
        }
        if (!isBluetoothEnabled()) {
            val errorMsg = application.getString(
                R.string.bt_snackbar_bluetooth_disabled_to_connect,
                deviceNameForMessage ?: devicePlaceholder
            )
            LogManager.w(TAG, "Connection prerequisite failed for '${deviceNameForMessage ?: "unknown device"}': Bluetooth is disabled.")
            if (isManualConnect) {
                bluetoothConnectionManager.setExternalConnectionError(errorMsg)
            } else {
                sharedViewModel.showSnackbar(errorMsg, SnackbarDuration.Long)
            }
            return false
        }
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

    /**
     * Clears a pending user interaction event from the [BluetoothConnectionManager].
     * This is typically called after the user has responded or wants to dismiss the interaction.
     */
    fun clearPendingUserInteraction() {
        LogManager.d(TAG, "Requesting to clear pending user interaction event.")
        bluetoothConnectionManager.clearUserInteractionEvent()
    }

    /**
     * Processes user-provided feedback for a pending Bluetooth user interaction event.
     * This is used, for example, when a scale requires the user to be selected from a list.
     *
     * @param interactionType The type of interaction that occurred (e.g., USER_SELECTION).
     * @param feedbackData The data provided by the user (e.g., the selected user's index or ID).
     */
    fun processUserInteraction(interactionType: UserInteractionType, feedbackData: Any) {
        viewModelScope.launch {
            val localCurrentAppUser = currentAppUser // Use local copy for thread-safety in coroutine
            if (localCurrentAppUser == null || localCurrentAppUser.id == 0) {
                LogManager.w(TAG, "User interaction processing aborted: No current app user selected.")
                sharedViewModel.showSnackbar(
                    application.getString(R.string.bt_snackbar_error_no_app_user_selected),
                    SnackbarDuration.Short // Or .Long if more prominent message needed
                )
                bluetoothConnectionManager.clearUserInteractionEvent() // Clear the prompt as it cannot be handled
                return@launch
            }
            val appUserId = localCurrentAppUser.id // This is the app's internal user ID

            val uiHandler = Handler(Looper.getMainLooper())

            LogManager.d(TAG, "Processing user interaction: Type=$interactionType, AppUserID=$appUserId, Data=$feedbackData")
            bluetoothConnectionManager.provideUserInteractionFeedback(
                interactionType,
                appUserId, // Pass the app's user ID
                feedbackData,
                uiHandler // Pass handler if ConnectionManager needs it for specific tasks
            )

            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_user_input_processed),
                SnackbarDuration.Short
            )

            clearPendingUserInteraction()
        }
    }

    // --- Device Preferences ---

    /**
     * Saves the given scanned device as the preferred Bluetooth scale in user settings.
     * @param device The [ScannedDeviceInfo] of the device to save.
     */
    fun saveDeviceAsPreferred(device: ScannedDeviceInfo) {
        viewModelScope.launch {
            val nameToSave = device.name ?: application.getString(R.string.unknown_scale_name) // Provide a default name from resources if null.
            LogManager.i(TAG, "User requested to save device as preferred: Name='${device.name}', Address='${device.address}'. Saving as '$nameToSave'.")
            userSettingsRepository.saveBluetoothScale(device.address, nameToSave)
            sharedViewModel.showSnackbar(
                application.getString(R.string.bt_snackbar_scale_saved_as_preferred, nameToSave),
                SnackbarDuration.Short
            )
        }
    }

    // --- Permissions and System State Methods ---

    /**
     * Checks if the necessary Bluetooth permissions are currently granted.
     * Handles different permission sets for Android S (API 31) and above.
     * @return `true` if permissions are granted, `false` otherwise.
     */
    private fun checkInitialPermissions(): Boolean {
        val hasConnect = ContextCompat.checkSelfPermission(
            application, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val hasScan = ContextCompat.checkSelfPermission(
            application, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !hasConnect && !hasScan -> {
                LogManager.w(TAG, "Missing permissions: BLUETOOTH_CONNECT & BLUETOOTH_SCAN → BLE disabled (no connect, no scan).")
            }
            !hasConnect -> {
                LogManager.w(TAG, "Missing permission: BLUETOOTH_CONNECT → cannot perform GATT ops (connect/read/write).")
            }
            !hasScan -> {
                LogManager.w(TAG, "Missing permission: BLUETOOTH_SCAN → cannot scan/pre-scan (no discovery, less reliable connect).")
            }
            else -> {
                LogManager.d(TAG, "All required Bluetooth permissions granted (SCAN & CONNECT).")
            }
        }

        return hasConnect && hasScan
    }

    /**
     * Refreshes the `permissionsGranted` StateFlow by re-checking the current permission status.
     * Should be called when the app regains focus or when permissions might have changed
     * (e.g., after user grants them in system settings).
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
        if (!isEnabled) {
            LogManager.w(TAG, "Bluetooth adapter is disabled.") // Log only if disabled for less verbose logging.
        }
        return isEnabled
    }

    /**
     * Attempts to automatically connect to the saved preferred Bluetooth scale, if one exists
     * and the app is not already connected or connecting to it.
     * This might be called on ViewModel initialization or when the app comes to the foreground.
     */
    @SuppressLint("MissingPermission") // connectToSavedDevice handles permission checks internally.
    fun attemptAutoConnectToSavedScale() {
        viewModelScope.launch {
            val address = savedScaleAddress.value
            val name = savedScaleName.value

            if (address != null && name != null) {
                LogManager.i(TAG, "Attempting auto-connect to saved scale: '$name' ($address).")
                // Check if already connected or in the process of connecting to the *same* saved device.
                if ((connectionStatus.value == ConnectionStatus.CONNECTED || connectionStatus.value == ConnectionStatus.CONNECTING) &&
                    connectedDeviceAddress.value == address
                ) {
                    LogManager.d(TAG, "Auto-connect: Already connected or connecting to '$name' ($address). No action needed.")
                    return@launch
                }
                connectToSavedDevice()
            } else {
                LogManager.d(TAG, "Auto-connect attempt: No saved scale found.")
                // Optionally, show a non-blocking snackbar if desired, though auto-attempts are usually silent on "not found"
                // sharedViewModel.showSnackbar(application.getString(R.string.bt_snackbar_no_scale_saved), SnackbarDuration.Short)
            }
        }
    }


    /**
     * Called when the ViewModel is about to be destroyed.
     * Ensures that resources used by Bluetooth managers are released (e.g., stopping scans,
     * disconnecting devices, closing underlying Bluetooth resources like GATT connections or broadcast receivers).
     */
    override fun onCleared() {
        super.onCleared()
        LogManager.i(TAG, "BluetoothViewModel onCleared. Releasing resources from managers.")
        bluetoothScannerManager.close() // Tell scanner manager to clean up
        bluetoothConnectionManager.close() // Tell connection manager to clean up
        LogManager.i(TAG, "BluetoothViewModel onCleared completed.")
    }
}
