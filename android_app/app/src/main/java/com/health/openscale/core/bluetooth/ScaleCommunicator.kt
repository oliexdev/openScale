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

import androidx.compose.runtime.Composable
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain events emitted by a [ScaleCommunicator].
 *
 * Notes for broadcast-only devices (advertisement parsing, no GATT):
 * - The adapter emits [Listening] when scanning starts for the target MAC.
 * - When a final (stabilized) measurement was published and scanning stops, it emits [BroadcastComplete].
 * - For such devices, [Connected] is typically never emitted.
 */
sealed class BluetoothEvent {
    enum class UserInteractionType {
        CHOOSE_USER,
        ENTER_CONSENT
    }

    /** Emitted when scanning starts for a broadcast-only device. */
    data class Listening(val deviceAddress: String) : BluetoothEvent()

    /** Emitted after a broadcast-only flow has completed (e.g., stabilized measurement parsed). */
    data class BroadcastComplete(val deviceAddress: String) : BluetoothEvent()

    /** Emitted when a GATT connection has been established. */
    data class Connected(val deviceName: String, val deviceAddress: String) : BluetoothEvent()

    /** Emitted when an existing GATT connection has been disconnected. */
    data class Disconnected(val deviceAddress: String, val reason: String? = null) : BluetoothEvent()

    /** Emitted when a connection attempt to a device failed. */
    data class ConnectionFailed(val deviceAddress: String, val error: String) : BluetoothEvent()

    /** Emitted when a parsed measurement is available. */
    data class MeasurementReceived(
        val measurement: ScaleMeasurement,
        val deviceAddress: String
    ) : BluetoothEvent()

    /** Emitted for generic device-related errors. */
    data class Error(val deviceAddress: String, val error: String) : BluetoothEvent()

    /** Emitted for miscellaneous device/user-visible messages. */
    data class DeviceMessage(val message: String, val deviceAddress: String) : BluetoothEvent()

    /** Emitted when user interaction is required (e.g., pick user, enter consent code). */
    data class UserInteractionRequired(
        val deviceIdentifier: String,
        val data: Any?,
        val interactionType: UserInteractionType,
    ) : BluetoothEvent()
}

/**
 * A generic interface for communicating with Bluetooth scales.
 * Implementations may be GATT-based or broadcast-only (advertisement parsing).
 */
interface ScaleCommunicator {

    /** Indicates whether a connection attempt (or scan for broadcast devices) is in progress. */
    val isConnecting: StateFlow<Boolean>

    /** Indicates whether a GATT connection is active. For broadcast-only devices this is always `false`. */
    val isConnected: StateFlow<Boolean>

    /** Start communicating with a device identified by [address]. Binds the session to [scaleUser]. */
    fun connect(address: String, scaleUser: ScaleUser?)

    /** Terminate the current session (disconnect or stop scanning). */
    fun disconnect()

    /** Request a measurement (if supported; some devices only push asynchronously). */
    fun requestMeasurement()

    /**
     * Renders the device-specific configuration UI.
     * This allows the device handler to inject custom settings fields
     * (like bind keys or user slots) into the settings screen.
     */
    @Composable
    fun DeviceConfigurationUi()

    /** Stream of [BluetoothEvent] emitted by the communicator. */
    fun getEventsFlow(): SharedFlow<BluetoothEvent>

    /** Deliver feedback for a previously requested user interaction. */
    suspend fun processUserInteractionFeedback(
        interactionType: BluetoothEvent.UserInteractionType,
        appUserId: Int,
        feedbackData: Any
    )
}
