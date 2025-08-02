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
package com.health.openscale.core.bluetooth.scalesJava

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleCommunicator
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Adapter that adapts a legacy `BluetoothCommunication` (Java driver) instance
 * to the `ScaleCommunicator` interface (without getScaleInfo).
 * The identity of the scale is determined by the passed `bluetoothDriverInstance`.
 *
 * @property applicationContext The application context, used for accessing string resources.
 * @property bluetoothDriverInstance The specific legacy Java Bluetooth driver instance.
 * @property databaseRepository Repository for database operations, currently unused in this adapter but kept for potential future use.
 */
class LegacyScaleAdapter(
    private val applicationContext: Context,
    private val bluetoothDriverInstance: BluetoothCommunication, // The specific driver instance
    private val databaseRepository: DatabaseRepository // Maintained for potential future use, though not directly used in current logic
) : ScaleCommunicator {

    companion object {
        private const val TAG = "LegacyScaleAdapter"
    }

    private val adapterScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("LegacyScaleAdapterScope"))

    private val _eventsFlow =
        MutableSharedFlow<BluetoothEvent>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * A [SharedFlow] that emits [BluetoothEvent]s from the scale driver.
     */
    val events: SharedFlow<BluetoothEvent> = _eventsFlow.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    override val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private var currentTargetAddress: String? = null
    private var currentInternalUser: ScaleUser? = null

    private val driverEventHandler = DriverEventHandler(this)

    init {
        LogManager.i(TAG, "CONSTRUCTOR with driver instance: ${bluetoothDriverInstance.javaClass.name} (${bluetoothDriverInstance.driverName()})")
        bluetoothDriverInstance.registerCallbackHandler(driverEventHandler)
    }

    /**
     * Handles messages received from the legacy [BluetoothCommunication] driver.
     * It translates these messages into [BluetoothEvent]s and updates the adapter's state.
     */
    private inner class DriverEventHandler(adapter: LegacyScaleAdapter) : Handler(Looper.getMainLooper()) {
        private val adapterRef: WeakReference<LegacyScaleAdapter> = WeakReference(adapter)

        override fun handleMessage(msg: Message) {
            val adapter = adapterRef.get() ?: return // Adapter instance might have been garbage collected

            val status = BluetoothCommunication.BT_STATUS.values().getOrNull(msg.what)
            val eventData = msg.obj
            val arg1 = msg.arg1
            val arg2 = msg.arg2

            LogManager.d(TAG, "DriverEventHandler: Message received - what: ${msg.what} ($status), obj: $eventData, arg1: $arg1, arg2: $arg2")

            val deviceIdentifier = adapter.currentTargetAddress ?: adapter.bluetoothDriverInstance.driverName()

            when (status) {
                BluetoothCommunication.BT_STATUS.RETRIEVE_SCALE_DATA -> {
                    if (eventData is ScaleMeasurement) {
                        LogManager.i(TAG, "RETRIEVE_SCALE_DATA: Weight: ${eventData.weight}")
                        adapter._eventsFlow.tryEmit(BluetoothEvent.MeasurementReceived(eventData, deviceIdentifier))
                    } else {
                        LogManager.w(TAG, "RETRIEVE_SCALE_DATA: Unexpected data type: $eventData")
                        // Optionally, emit an error or generic message event
                        adapter._eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(
                            applicationContext.getString(R.string.legacy_adapter_event_unexpected_data, eventData?.javaClass?.simpleName ?: "null"),
                            deviceIdentifier
                        ))
                    }
                }
                BluetoothCommunication.BT_STATUS.INIT_PROCESS -> {
                    adapter._isConnecting.value = true
                    val infoText = eventData as? String ?: applicationContext.getString(R.string.legacy_adapter_event_initializing)
                    LogManager.d(TAG, "INIT_PROCESS: $infoText")
                    adapter._eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(infoText, deviceIdentifier))
                }
                BluetoothCommunication.BT_STATUS.CONNECTION_ESTABLISHED -> {
                    LogManager.i(TAG, "CONNECTION_ESTABLISHED to $deviceIdentifier (Target: ${adapter.currentTargetAddress})")
                    adapter._isConnected.value = true
                    adapter._isConnecting.value = false
                    // adapter.currentTargetAddress should not be null here if connection is established
                    adapter._eventsFlow.tryEmit(BluetoothEvent.Connected(deviceIdentifier, adapter.currentTargetAddress!!))
                }
                BluetoothCommunication.BT_STATUS.CONNECTION_DISCONNECT, BluetoothCommunication.BT_STATUS.CONNECTION_LOST -> {
                    val reasonKey = if (status == BluetoothCommunication.BT_STATUS.CONNECTION_LOST) R.string.legacy_adapter_event_connection_lost else R.string.legacy_adapter_event_connection_disconnected
                    val reasonString = applicationContext.getString(reasonKey)
                    val additionalInfo = eventData as? String ?: ""
                    val fullMessage = if (additionalInfo.isNotEmpty()) "$reasonString - $additionalInfo" else reasonString

                    LogManager.i(TAG, "$status for $deviceIdentifier: $fullMessage")
                    adapter._isConnected.value = false
                    adapter._isConnecting.value = false
                    adapter._eventsFlow.tryEmit(BluetoothEvent.Disconnected(deviceIdentifier, fullMessage))
                    adapter.cleanupAfterDisconnect()
                }
                BluetoothCommunication.BT_STATUS.NO_DEVICE_FOUND -> {
                    val additionalInfo = eventData as? String ?: ""
                    val message = applicationContext.getString(R.string.legacy_adapter_event_device_not_found, additionalInfo).trim()
                    LogManager.w(TAG, "NO_DEVICE_FOUND for $deviceIdentifier. Info: $additionalInfo")
                    adapter._isConnected.value = false
                    adapter._isConnecting.value = false
                    adapter._eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(deviceIdentifier, message))
                    adapter.cleanupAfterDisconnect()
                }
                BluetoothCommunication.BT_STATUS.UNEXPECTED_ERROR -> {
                    val additionalInfo = eventData as? String ?: ""
                    val message = applicationContext.getString(R.string.legacy_adapter_event_unexpected_error, additionalInfo).trim()
                    LogManager.e(TAG, "UNEXPECTED_ERROR for $deviceIdentifier. Info: $additionalInfo")
                    adapter._isConnected.value = false
                    adapter._isConnecting.value = false
                    adapter._eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(deviceIdentifier, message))
                    adapter.cleanupAfterDisconnect()
                }
                BluetoothCommunication.BT_STATUS.SCALE_MESSAGE -> {
                    try {
                        val messageResId = arg1
                        val messageArg = eventData
                        val messageText = if (messageArg != null) {
                            adapter.applicationContext.getString(messageResId, messageArg.toString())
                        } else {
                            adapter.applicationContext.getString(messageResId)
                        }
                        LogManager.d(TAG, "SCALE_MESSAGE: $messageText (ID: $messageResId)")
                        adapter._eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(messageText, deviceIdentifier))
                    } catch (e: Exception) {
                        LogManager.e(TAG, "Error retrieving SCALE_MESSAGE string resource (ID $arg1)", e)
                        val fallbackMessage = applicationContext.getString(R.string.legacy_adapter_event_scale_message_fallback, arg1, eventData?.toString() ?: "N/A")
                        adapter._eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(fallbackMessage, deviceIdentifier))
                    }
                }
                BluetoothCommunication.BT_STATUS.CHOOSE_SCALE_USER -> {
                    LogManager.d(TAG, "CHOOSE_SCALE_USER for $deviceIdentifier: Data: $eventData")
                    var userListDescription = applicationContext.getString(R.string.legacy_adapter_event_user_selection_required)
                    if (eventData is List<*>) {
                        val stringList = eventData.mapNotNull { item ->
                            if (item is ScaleUser) {
                                applicationContext.getString(R.string.legacy_adapter_event_user_details, item.id, item.age, item.bodyHeight)
                            } else {
                                item.toString()
                            }
                        }
                        if (stringList.isNotEmpty()) {
                            userListDescription = stringList.joinToString(separator = "\n")
                        }
                    } else if (eventData != null) {
                        userListDescription = eventData.toString()
                    }
                    adapter._eventsFlow.tryEmit(BluetoothEvent.UserSelectionRequired(userListDescription, deviceIdentifier, eventData))
                }
                BluetoothCommunication.BT_STATUS.ENTER_SCALE_USER_CONSENT -> {
                    val appScaleUserId = arg1
                    val scaleUserIndex = arg2
                    LogManager.d(TAG, "ENTER_SCALE_USER_CONSENT for $deviceIdentifier: AppUserID: $appScaleUserId, ScaleUserIndex: $scaleUserIndex. Data: $eventData")
                    val message = applicationContext.getString(R.string.legacy_adapter_event_user_consent_required, appScaleUserId, scaleUserIndex)
                    adapter._eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(message, deviceIdentifier))
                }
                else -> {
                    LogManager.w(TAG, "Unknown BT_STATUS ($status) or message (what=${msg.what}) from driver ${adapter.bluetoothDriverInstance.driverName()} received.")
                    adapter._eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(
                        applicationContext.getString(R.string.legacy_adapter_event_unknown_status, status?.name ?: msg.what.toString()),
                        deviceIdentifier
                    ))
                }
            }
        }
    }

    override fun connect(deviceAddress: String, uiScaleUser: ScaleUser?, appUserId: Int?) {
        adapterScope.launch {
            val currentDeviceName = currentTargetAddress ?: bluetoothDriverInstance.driverName()
            if (_isConnected.value || _isConnecting.value) {
                LogManager.w(TAG, "connect: Already connected/connecting to $currentDeviceName. Ignoring request for $deviceAddress.")
                if (currentTargetAddress != deviceAddress && currentTargetAddress != null) {
                    val message = applicationContext.getString(R.string.legacy_adapter_connect_busy, currentTargetAddress)
                    _eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(deviceAddress, message))
                } else if (currentTargetAddress == null) {
                    // This case implies isConnecting is true but currentTargetAddress is null,
                    // which might indicate a race condition or an incomplete previous cleanup.
                    // Allow proceeding with the new connection attempt.
                    LogManager.d(TAG, "connect: Retrying connection for $deviceAddress to ${bluetoothDriverInstance.driverName()} while isConnecting=true but currentTargetAddress=null")
                } else {
                    // Already connecting to or connected to the same deviceAddress
                    return@launch
                }
            }

            LogManager.i(TAG, "connect: REQUEST for address $deviceAddress to driver ${bluetoothDriverInstance.driverName()}, UI ScaleUser ID: ${uiScaleUser?.id}, AppUserID: $appUserId")
            _isConnecting.value = true
            _isConnected.value = false
            currentTargetAddress = deviceAddress // Store the address being connected to
            currentInternalUser = uiScaleUser

            LogManager.d(TAG, "connect: Internal user for connection: ${currentInternalUser?.id}, AppUserID: $appUserId")

            currentInternalUser?.let { bluetoothDriverInstance.setSelectedScaleUser(it) }
            appUserId?.let { bluetoothDriverInstance.setSelectedScaleUserId(it) }

            LogManager.d(TAG, "connect: Calling connect() on Java driver instance (${bluetoothDriverInstance.driverName()}) for $deviceAddress.")
            try {
                bluetoothDriverInstance.connect(deviceAddress)
            } catch (e: Exception) {
                LogManager.e(TAG, "connect: Exception while calling bluetoothDriverInstance.connect() for $deviceAddress to ${bluetoothDriverInstance.driverName()}", e)
                val message = applicationContext.getString(R.string.legacy_adapter_connect_exception, bluetoothDriverInstance.driverName(), e.message)
                _eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(deviceAddress, message))
                cleanupAfterDisconnect() // Ensure state is reset
            }
        }
    }

    override fun disconnect() {
        adapterScope.launch {
            val deviceNameToLog = currentTargetAddress ?: bluetoothDriverInstance.driverName()
            LogManager.i(TAG, "disconnect: REQUEST for $deviceNameToLog")
            if (!_isConnected.value && !_isConnecting.value) {
                LogManager.d(TAG, "disconnect: Neither connected nor connecting to $deviceNameToLog. No action.")
                return@launch
            }
            bluetoothDriverInstance.disconnect()
            // Status flags will be updated by handler events (CONNECTION_DISCONNECT),
            // but we can set them here to inform the UI more quickly.
            // However, this might lead to premature UI updates if the driver's disconnect is asynchronous
            // and fails. Relying on the handler event is safer for final state.
            // _isConnected.value = false // Consider removing if handler is reliable
            // _isConnecting.value = false // Consider removing if handler is reliable
            // cleanupAfterDisconnect() is called by the handler.
        }
    }

    /**
     * Cleans up internal state after a disconnection or connection failure.
     * Resets connection flags and clears stored target address and user.
     */
    private fun cleanupAfterDisconnect() {
        val deviceName = currentTargetAddress ?: bluetoothDriverInstance.driverName()
        LogManager.d(TAG, "cleanupAfterDisconnect: Cleaning up for $deviceName (address was $currentTargetAddress)")
        _isConnected.value = false
        _isConnecting.value = false
        currentTargetAddress = null
        currentInternalUser = null
        LogManager.i(TAG, "cleanupAfterDisconnect: Cleanup completed for ${bluetoothDriverInstance.driverName()}.")
    }

    override fun requestMeasurement() {
        val deviceNameToLog = currentTargetAddress ?: bluetoothDriverInstance.driverName()
        LogManager.d(TAG, "requestMeasurement: CALLED for $deviceNameToLog")
        adapterScope.launch {
            if (!_isConnected.value || currentTargetAddress == null) { // Explicitly check currentTargetAddress for an active connection
                LogManager.w(TAG, "requestMeasurement: Not connected or no active address for measurement request to $deviceNameToLog.")
                _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(applicationContext.getString(R.string.legacy_adapter_request_measurement_not_connected), deviceNameToLog))
                return@launch
            }
            LogManager.i(TAG, "requestMeasurement: For legacy driver (${bluetoothDriverInstance.driverName()}), measurement is usually triggered automatically. No generic action here.")
            _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(applicationContext.getString(R.string.legacy_adapter_request_measurement_auto), deviceNameToLog))
        }
    }

    /**
     * Releases resources used by this adapter, including unregistering the callback
     * from the Bluetooth driver and canceling the coroutine scope.
     * This method should be called when the adapter is no longer needed to prevent memory leaks.
     */
    fun release() {
        val deviceName = bluetoothDriverInstance.driverName()
        LogManager.i(TAG, "release: Adapter for driver $deviceName is being released. Current target address: $currentTargetAddress")
        bluetoothDriverInstance.registerCallbackHandler(null) // Important to prevent leaks
        // Ensures any ongoing connection is terminated.
        if (_isConnected.value || _isConnecting.value) {
            // Using a separate launch for disconnect to avoid issues if the scope is cancelling.
            // However, the scope will be cancelled immediately after.
            // The driver's disconnect should ideally be robust.
            CoroutineScope(Dispatchers.IO).launch { // Use a temporary scope for this last operation if needed
                bluetoothDriverInstance.disconnect()
            }
        }
        adapterScope.cancel("LegacyScaleAdapter for $deviceName released")
        LogManager.i(TAG, "release: AdapterScope for $deviceName cancelled.")
    }

    /**
     * Informs the legacy driver about the user's selection for a scale user.
     * This is typically called in response to a [BluetoothEvent.UserSelectionRequired] event.
     *
     * @param appUserId The application-specific user ID.
     * @param scaleUserIndex The index of the user on the scale.
     */
    fun selectLegacyScaleUserIndex(appUserId: Int, scaleUserIndex: Int) {
        adapterScope.launch {
            LogManager.i(TAG, "selectLegacyScaleUserIndex for ${bluetoothDriverInstance.driverName()}: AppUserID: $appUserId, ScaleUserIndex: $scaleUserIndex")
            bluetoothDriverInstance.selectScaleUserIndexForAppUserId(appUserId, scaleUserIndex, driverEventHandler)
        }
    }

    /**
     * Sends the user's consent value to the legacy scale driver.
     * This is typically called after the scale requests user consent.
     *
     * @param appUserId The application-specific user ID.
     * @param consentValue The consent value (specific to the driver's protocol).
     */
    fun setLegacyScaleUserConsent(appUserId: Int, consentValue: Int) {
        adapterScope.launch {
            LogManager.i(TAG, "setLegacyScaleUserConsent for ${bluetoothDriverInstance.driverName()}: AppUserID: $appUserId, ConsentValue: $consentValue")
            bluetoothDriverInstance.setScaleUserConsent(appUserId, consentValue, driverEventHandler)
        }
    }

    /**
     * Retrieves the name of the managed Bluetooth driver/device.
     * Can be used externally if the name is needed and only a reference to the adapter is available.
     *
     * @return The name of the driver or a fallback class name if an error occurs.
     */
    fun getManagedDeviceName(): String {
        return try {
            bluetoothDriverInstance.driverName()
        } catch (e: Exception) {
            LogManager.w(TAG, "Error getting driverName() in getManagedDeviceName. Falling back to simple class name.", e)
            bluetoothDriverInstance.javaClass.simpleName
        }
    }

    override fun getEventsFlow(): SharedFlow<BluetoothEvent> {
        return events
    }
}
