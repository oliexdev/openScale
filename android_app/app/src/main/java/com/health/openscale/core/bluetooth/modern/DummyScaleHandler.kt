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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID


class DummyScaleHandler(private val driverName: String) : ScaleDeviceHandler {
    override fun getDriverName(): String {
        return driverName
    }

    // Updated signature to match the interface
    override fun canHandleDevice(
        deviceName: String?,
        deviceAddress: String, // Added this parameter
        serviceUuids: List<UUID>,
        manufacturerData: SparseArray<ByteArray>?
    ): Boolean {
        // Implement your logic to check if this handler can handle the device
        // For example, based on the deviceName:
        return deviceName?.contains(driverName, ignoreCase = true) == true
    }

    // --- Implement missing members ---
    override val handlerId: String
        get() = "dummy_handler_$driverName" // Example implementation

    override fun connectAndReceiveEvents(
        deviceAddress: String,
        currentAppUserAttributes: Map<String, Any>?
    ): Flow<ScaleDeviceEvent> {
        // Dummy implementation: return an empty flow or a flow that emits some dummy events
        println("DummyScaleHandler: connectAndReceiveEvents called for $deviceAddress")
        return flow {
            // emit(DummyScaleEvent("Connected")) // Example
            // emit(DummyScaleEvent("Weight: 70.5")) // Example
        }
    }

    override suspend fun disconnect() {
        // Dummy implementation
        println("DummyScaleHandler: disconnect called")
        // Add actual disconnection logic here
    }
}