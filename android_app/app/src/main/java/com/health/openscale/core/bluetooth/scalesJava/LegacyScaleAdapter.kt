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

import android.R.attr.description
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.compose.foundation.layout.size
import androidx.core.graphics.values
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleCommunicator
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.database.UserSettingsRepository
import com.health.openscale.core.database.provideUserSettingsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Date
import kotlin.random.Random
import kotlin.text.find
import kotlin.text.toDouble

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

    private suspend fun provideUserDataToLegacyDriver() {
        try {
            currentInternalUser?.let { bluetoothDriverInstance.setSelectedScaleUser(it) }

            LogManager.d(TAG, "Attempting to load user data for legacy driver: ${bluetoothDriverInstance.driverName()}")
            val userListFromDb = databaseRepository.getAllUsers().first()

            val legacyScaleUserList = userListFromDb.map { kotlinUser ->
                 ScaleUser().apply {
                     setId(kotlinUser.id)
                     setUserName(kotlinUser.name)
                     setBirthday(Date(kotlinUser.birthDate))
                     setBodyHeight(if (kotlinUser.heightCm == null) 0f else kotlinUser.heightCm)
                     setGender(kotlinUser.gender)
                     setActivityLevel(kotlinUser.activityLevel)

                     // TODO setInitialWeight(kotlinUser.initialWeight)
                     // TODO setGoalWeight(kotlinUser.goalWeight)
                     // TODO usw.
                 }
            }

            bluetoothDriverInstance.setScaleUserList(legacyScaleUserList)

            LogManager.i(TAG, "Successfully provided ${userListFromDb.size} users to legacy driver ${bluetoothDriverInstance.driverName()}.")

            val userSettingsRepository = provideUserSettingsRepository(applicationContext)
            val keyName = "unique_number_base"
            var base: Int = userSettingsRepository.observeSetting(keyName, 0).first()
            if (base == 0) {
                base = Random.nextInt(100, 65535)
                userSettingsRepository.saveSetting(keyName, base)
                LogManager.i(TAG, "Generated and saved unique_number_base=$base in DataStore")
            } else {
                LogManager.d(TAG, "Loaded unique_number_base=$base from DataStore")
            }

            bluetoothDriverInstance.setUniqueNumber(base)
            this.currentInternalUser?.let { userId ->
                if (userId.id != -1) {
                LogManager.d(TAG, "Attempting to load last measurement for user ID: $userId using existing repository methods.")
                    val lastMeasurementWithValues: com.health.openscale.core.model.MeasurementWithValues? =
                        databaseRepository.getMeasurementsWithValuesForUser(userId.id)
                            .map { measurements ->
                                measurements.maxByOrNull { it.measurement.timestamp }
                            }
                            .first()

                    if (lastMeasurementWithValues != null) {
                        val legacyScaleMeasurement = ScaleMeasurement().apply {
                            setDateTime(Date(lastMeasurementWithValues.measurement.timestamp))
                            setUserId(lastMeasurementWithValues.measurement.userId)
                            setWeight(lastMeasurementWithValues.values.find { it.type.key == MeasurementTypeKey.WEIGHT }?.value?.floatValue ?: 0.0f)
                            setFat(lastMeasurementWithValues.values.find { it.type.key == MeasurementTypeKey.BODY_FAT }?.value?.floatValue ?: 0.0f)
                            setWater(lastMeasurementWithValues.values.find { it.type.key == MeasurementTypeKey.WATER }?.value?.floatValue ?: 0.0f)
                            setMuscle(lastMeasurementWithValues.values.find { it.type.key == MeasurementTypeKey.MUSCLE }?.value?.floatValue ?: 0.0f)
                            setBone(lastMeasurementWithValues.values.find { it.type.key == MeasurementTypeKey.BONE }?.value?.floatValue ?: 0.0f)
                            setVisceralFat(lastMeasurementWithValues.values.find { it.type.key == MeasurementTypeKey.VISCERAL_FAT }?.value?.floatValue ?: 0.0f)
                            setLbm(lastMeasurementWithValues.values.find { it.type.key == MeasurementTypeKey.LBM }?.value?.floatValue ?: 0.0f)
                        }
                        bluetoothDriverInstance.setCachedLastMeasurementForSelectedUser(legacyScaleMeasurement)
                        LogManager.i(TAG, "Successfully provided last measurement for user $userId to legacy driver.")
                    } else {
                        bluetoothDriverInstance.setCachedLastMeasurementForSelectedUser(null)
                        LogManager.d(TAG, "No last measurement found for user $userId.")
                    }
                } else {
                    bluetoothDriverInstance.setCachedLastMeasurementForSelectedUser(null)
                }
            } ?: run {
                bluetoothDriverInstance.setCachedLastMeasurementForSelectedUser(null)
                LogManager.d(TAG, "No current app user ID set, cannot load last measurement.")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error providing user data to legacy driver ${bluetoothDriverInstance.driverName()}", e)
            _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage("Fehler beim Laden der Benutzerdaten für ${bluetoothDriverInstance.driverName()}",
                bluetoothDriverInstance.scaleMacAddress.toString()
            ))
            bluetoothDriverInstance.setCachedLastMeasurementForSelectedUser(null)
            LogManager.d(TAG, "No current app user ID set, cannot load last measurement.")
        }
    }

    /**
     * Handles messages received from the legacy [BluetoothCommunication] driver.
     * It translates these messages into [BluetoothEvent]s and updates the adapter's state.
     */
    private inner class DriverEventHandler(adapter: LegacyScaleAdapter) : Handler(Looper.getMainLooper()) {
        private val adapterRef: WeakReference<LegacyScaleAdapter> = WeakReference(adapter)

        override fun handleMessage(msg: Message) {
            val adapter = adapterRef.get() ?: return // Adapter instance might have been garbage collected

            val status = BluetoothCommunication.BT_STATUS.entries.getOrNull(msg.what)
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
                BluetoothCommunication.BT_STATUS.USER_INTERACTION_REQUIRED -> {
                    val rawPayload = msg.obj
                    if (rawPayload is Array<*> && rawPayload.size == 2 && rawPayload[0] is UserInteractionType) {
                        val interactionType = rawPayload[0] as UserInteractionType
                        val eventDataForUi = rawPayload[1]

                        LogManager.d(TAG, "USER_INTERACTION_REQUIRED ($interactionType) for $deviceIdentifier. Forwarding data: $eventDataForUi")

                        adapter._eventsFlow.tryEmit(
                            BluetoothEvent.UserInteractionRequired(
                                deviceIdentifier = deviceIdentifier,
                                data = eventDataForUi,
                                interactionType = interactionType
                            )
                        )

                    } else {
                        LogManager.w(TAG, "Received USER_INTERACTION_REQUIRED with invalid or incomplete payload structure: $rawPayload")
                        adapter._eventsFlow.tryEmit(BluetoothEvent.DeviceMessage(
                            applicationContext.getString(R.string.legacy_adapter_event_invalid_interaction_payload),
                            deviceIdentifier
                        ))
                    }
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

    override fun connect(address: String, scaleUser: ScaleUser?) {
        adapterScope.launch {
            val currentDeviceName = currentTargetAddress ?: bluetoothDriverInstance.driverName()
            if (_isConnected.value || _isConnecting.value) {
                LogManager.w(TAG, "connect: Already connected/connecting to $currentDeviceName. Ignoring request for $address.")
                if (currentTargetAddress != address && currentTargetAddress != null) {
                    val message = applicationContext.getString(R.string.legacy_adapter_connect_busy, currentTargetAddress)
                    _eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(address, message))
                } else if (currentTargetAddress == null) {
                    // This case implies isConnecting is true but currentTargetAddress is null,
                    // which might indicate a race condition or an incomplete previous cleanup.
                    // Allow proceeding with the new connection attempt.
                    LogManager.d(TAG, "connect: Retrying connection for $address to ${bluetoothDriverInstance.driverName()} while isConnecting=true but currentTargetAddress=null")
                } else {
                    // Already connecting to or connected to the same deviceAddress
                    return@launch
                }
            }

            LogManager.i(TAG, "connect: REQUEST for address $address to driver ${bluetoothDriverInstance.driverName()}")
            _isConnecting.value = true
            _isConnected.value = false
            currentTargetAddress = address // Store the address being connected to
            currentInternalUser = scaleUser

            LogManager.d(TAG, "connect: Internal user for connection: ${currentInternalUser?.id}")

            provideUserDataToLegacyDriver()

            LogManager.d(TAG, "connect: Calling connect() on Java driver instance (${bluetoothDriverInstance.driverName()}) for $address.")
            try {
                bluetoothDriverInstance.connect(address)
            } catch (e: Exception) {
                LogManager.e(TAG, "connect: Exception while calling bluetoothDriverInstance.connect() for $address to ${bluetoothDriverInstance.driverName()}", e)
                val message = applicationContext.getString(R.string.legacy_adapter_connect_exception, bluetoothDriverInstance.driverName(), e.message)
                _eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(address, message))
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

    override fun processUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any,
        uiHandler: Handler
    ) {
        bluetoothDriverInstance.processUserInteractionFeedback(interactionType, appUserId, feedbackData, uiHandler)
    }
}
