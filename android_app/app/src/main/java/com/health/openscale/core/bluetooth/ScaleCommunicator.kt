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
package com.health.openscale.core.bluetooth

import android.os.Handler
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Defines the events that can be emitted by a [ScaleCommunicator].
 */
sealed class BluetoothEvent {
    enum class UserInteractionType {
        CHOOSE_USER,
        ENTER_CONSENT
    }

    /**
     * Event triggered when a connection to a device has been successfully established.
     * @param deviceName The name of the connected device.
     * @param deviceAddress The MAC address of the connected device.
     */
    data class Connected(val deviceName: String, val deviceAddress: String) : BluetoothEvent()

    /**
     * Event triggered when an existing connection to a device has been disconnected.
     * @param deviceAddress The MAC address of the disconnected device.
     * @param reason An optional reason for the disconnection (e.g., "Connection lost", "Manually disconnected").
     */
    data class Disconnected(val deviceAddress: String, val reason: String? = null) : BluetoothEvent()

    /**
     * Event triggered when a connection attempt to a device has failed.
     * @param deviceAddress The MAC address of the device to which the connection failed.
     * @param error An error message describing the reason for the failure.
     */
    data class ConnectionFailed(val deviceAddress: String, val error: String) : BluetoothEvent()

    /**
     * Event triggered when measurement data has been received from the scale.
     * Uses [ScaleMeasurement] as the common data format.
     * @param measurement The received [ScaleMeasurement] object.
     * @param deviceAddress The MAC address of the device from which the measurement originated.
     */
    data class MeasurementReceived(
        val measurement: ScaleMeasurement,
        val deviceAddress: String
    ) : BluetoothEvent()

    /**
     * Event triggered when a general error related to a device occurs.
     * @param deviceAddress The MAC address of the device associated with the error.
     * @param error An error message describing the issue.
     */
    data class Error(val deviceAddress: String, val error: String) : BluetoothEvent()

    /**
     * Event triggered when a text message (e.g., status or instruction) is received from the device.
     * @param message The received message.
     * @param deviceAddress The MAC address of the device from which the message originated.
     */
    data class DeviceMessage(val message: String, val deviceAddress: String) : BluetoothEvent()

    /**
     * Event triggered when user interaction is required to select a user on the scale.
     * This is often used when a scale supports multiple users and the app needs to clarify
     * which app user corresponds to the scale user.
     * @param deviceIdentifier The identifier (e.g., MAC address) of the device requiring user selection.
     * @param data Optional data associated with the event, potentially containing information about users on the scale.
     *                 The exact type should be defined by the communicator implementation if more specific data is available.
     */
    data class UserInteractionRequired(
        val deviceIdentifier: String,
        val data: Any?,
        val interactionType: UserInteractionType,
    ) : BluetoothEvent()
}

/**
 * A generic interface for communication with a Bluetooth scale.
 * This interface abstracts the specific Bluetooth implementation (e.g., legacy Bluetooth or BLE).
 */
interface ScaleCommunicator {

    /**
     * A [StateFlow] indicating whether a connection attempt to a device is currently in progress.
     * `true` if a connection attempt is active, `false` otherwise.
     */
    val isConnecting: StateFlow<Boolean>

    /**
     * A [StateFlow] indicating whether an active connection to a device currently exists.
     * `true` if connected, `false` otherwise.
     */
    val isConnected: StateFlow<Boolean>

    /**
     * Initiates a connection attempt to the device with the specified MAC address.
     * @param address The MAC address of the target device.
     * @param scaleUser The user to be selected or used on the scale (optional).
     */
    fun connect(address: String, scaleUser: ScaleUser?)

    /**
     * Disconnects the existing connection to the currently connected device.
     */
    fun disconnect()

    /**
     * Explicitly requests a new measurement from the connected device.
     * (Note: Not always supported or required by all scale devices).
     */
    fun requestMeasurement()

    /**
     * Provides a [SharedFlow] that emits [BluetoothEvent]s.
     * Consumers can collect events from this flow to react to connection changes,
     * received measurements, errors, and other device-related events.
     * @return A [SharedFlow] of [BluetoothEvent]s.
     */
    fun getEventsFlow(): SharedFlow<BluetoothEvent>

    /**
     * Processes feedback received from the user for a previously requested interaction.
     */
    fun processUserInteractionFeedback(
        interactionType: BluetoothEvent.UserInteractionType,
        appUserId: Int,
        feedbackData: Any,
        uiHandler: Handler
    )
}
