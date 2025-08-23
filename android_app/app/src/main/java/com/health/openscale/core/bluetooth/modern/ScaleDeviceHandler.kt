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
package com.health.openscale.core.bluetooth.modern

import android.util.SparseArray
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.data.MeasurementTypeKey // Required for DeviceValue
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// ---- Data classes for abstracting measurement data provided by the handler ----

/**
 * Represents a single value of a specific measurement type from the device.
 * @param typeKey The key identifying the type of measurement (e.g., weight, body fat).
 * @param value The actual value of the measurement.
 */
data class DeviceValue(
    val typeKey: MeasurementTypeKey,
    val value: Any
)

/**
 * Represents a complete measurement reading from the device.
 * @param timestamp The time the measurement was taken, in milliseconds since epoch. Defaults to current time.
 * @param values A list of [DeviceValue] objects representing the different components of the measurement.
 * @param deviceIdentifier An optional identifier for the device that produced the measurement (e.g., MAC address).
 * @param scaleUserIndex Optional: Indicates if this measurement originates from a specific user profile on the scale.
 *                       The value is the index/ID of the user on the scale, if known.
 *                       Can be useful for later assignment or filtering of measurements.
 * @param isStableMeasurement Optional: Indicates if this measurement is a "stable" or "final" reading.
 *                            Some scales send intermediate values before the final weight is determined.
 *                            Defaults to true if not otherwise specified.
 */
data class DeviceMeasurement(
    val timestamp: Long = System.currentTimeMillis(),
    val values: List<DeviceValue>,
    val deviceIdentifier: String? = null,
    val scaleUserIndex: Int? = null,
    val isStableMeasurement: Boolean = true
)

// ---- End of data classes for abstraction ----

/**
 * Represents the data provided by a handler for selecting a user on the scale.
 * Handler implementations should derive a more specific data class or use this as a base.
 */
interface ScaleUserListItem {
    /** Text to be displayed in the UI for this user item. */
    val displayData: String
    /** The internal ID that the handler requires to identify this user on the scale. */
    val scaleInternalId: Any
}

/**
 * Example implementation for a generic user list item.
 * @param displayData Text to be displayed in the UI.
 * @param scaleInternalId The internal ID used by the scale/handler.
 */
data class GenericScaleUserListItem(
    override val displayData: String,
    override val scaleInternalId: Any
) : ScaleUserListItem


/**
 * Defines various events that a [ScaleDeviceHandler] can emit to communicate its state
 * and data to the application.
 */
sealed class ScaleDeviceEvent {
    /** The handler is starting to search for the device (advertising) or establishing a GATT connection. */
    data object PreparingConnection : ScaleDeviceEvent() // Renamed from Connecting for more generality

    /** The handler is actively scanning for advertising packets from the device. Only relevant for advertising-based handlers. */
    data object ScanningForAdvertisement : ScaleDeviceEvent()

    /**
     * A connection to the device has been successfully established.
     * @param message A descriptive message about the established connection.
     */
    data class ConnectionEstablished(val message: String) : ScaleDeviceEvent()

    /**
     * The device-specific initialization sequence has been successfully completed.
     * For GATT-based handlers, this usually means notifications are set up and initial checks are done.
     * For advertising-based handlers, this might not be relevant or could be sent directly after the first data reception.
     */
    data object InitializationComplete : ScaleDeviceEvent()

    /** The connection to the device was lost unexpectedly, or the target device was no longer found (during scanning). */
    data object ConnectionLost : ScaleDeviceEvent()

    /** The connection to the device was actively disconnected, or the scan was stopped. */
    data object Disconnected : ScaleDeviceEvent()

    /**
     * An error occurred during communication or device handling.
     * @param message A descriptive error message.
     * @param throwable An optional [Throwable] associated with the error.
     * @param errorCode An optional, handler-specific error code.
     */
    data class Error(val message: String, val throwable: Throwable? = null, val errorCode: Int? = null) : ScaleDeviceEvent()

    /**
     * A new measurement, parsed from the device, is available.
     * @param measurement The [DeviceMeasurement] containing the data.
     */
    data class DeviceMeasurementAvailable(val measurement: DeviceMeasurement) : ScaleDeviceEvent()

    /**
     * An informational message for the user.
     * @param text The message text (can be null if `stringResId` is used).
     * @param stringResId An optional string resource ID for localization.
     * @param payload Optional structured data associated with the info message.
     */
    data class InfoMessage(
        val text: String? = null,
        val stringResId: Int? = null,
        val payload: Any? = null // For optionally more structured info data
    ) : ScaleDeviceEvent()

    data class UserInteractionRequired(
        val interactionType: UserInteractionType,
        val data: Any? = null, // Daten vom Handler an die UI
        val requestContext: Any? = null
    ) : ScaleDeviceEvent()
}

/**
 * Interface for a device-specific handler that manages communication with a Bluetooth scale.
 * It abstracts the low-level Bluetooth operations and provides a standardized way to interact
 * with different types of scales.
 */
interface ScaleDeviceHandler {

    /**
     * @return The display name of this scale driver/handler (e.g., "Mi Scale v2 Handler").
     */
    fun getDriverName(): String

    /**
     * A unique ID for this handler type. Can be, for example, the class name.
     * Important for persistence and later retrieval of the correct handler.
     */
    val handlerId: String

    /**
     * Indicates whether this handler primarily communicates via advertising data (`true`)
     * or GATT connections (`false`).
     * This can help the Bluetooth management layer optimize scans and connection attempts.
     * Defaults to `false` (GATT-based).
     */
    val communicatesViaAdvertising: Boolean
        get() = false // Default is GATT-based

    /**
     * Checks if this handler can manage communication with the specified device.
     *
     * @param deviceName The advertised name of the Bluetooth device.
     * @param deviceAddress The MAC address of the device.
     * @param serviceUuids A list of advertised service UUIDs.
     * @param manufacturerData Manufacturer-specific data from the advertisement.
     * @return `true` if this handler can handle the device, `false` otherwise.
     */
    fun canHandleDevice(
        deviceName: String?,
        deviceAddress: String,
        serviceUuids: List<UUID>,
        manufacturerData: SparseArray<ByteArray>?
    ): Boolean

    /**
     * Prepares communication, potentially scans for advertising data or establishes a GATT connection,
     * performs the necessary initialization sequence, and starts receiving data.
     *
     * @param deviceAddress The MAC address of the Bluetooth device.
     * @param currentAppUserAttributes Optional attributes of the current app user that the handler might
     *                                 need for initialization or user synchronization.
     *                                 The handler should explicitly request what it needs via [ScaleDeviceEvent.UserAttributesRequired]
     *                                 if these are insufficient or missing.
     * @return A [Flow] of [ScaleDeviceEvent]s.
     */
    fun connectAndReceiveEvents(
        deviceAddress: String,
        currentAppUserAttributes: Map<String, Any>? = null
    ): Flow<ScaleDeviceEvent>

    /**
     * Disconnects from the currently connected device and cleans up resources.
     */
    suspend fun disconnect()

    /**
     * Sends a device-specific command to the scale.
     * This is an escape-hatch function for handler-specific actions not covered by
     * the standard events/methods.
     * Its use should be minimized to maintain abstraction.
     *
     * @param commandId An identifier for the command.
     * @param commandData Optional data for the command.
     * @return A result object indicating success/failure and optional response data.
     */
    // suspend fun sendRawCommand(commandId: String, commandData: Any? = null): CommandResult // Commented out for now as it increases complexity
}

// data class CommandResult(val success: Boolean, val responseData: Any? = null) // For sendRawCommand
