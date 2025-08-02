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

import android.content.Context
import android.util.SparseArray
import com.health.openscale.core.bluetooth.scales.DummyScaleHandler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.bluetooth.scalesJava.BluetoothCommunication
import com.health.openscale.core.bluetooth.scalesJava.BluetoothYunmaiSE_Mini
import com.health.openscale.core.bluetooth.scalesJava.LegacyScaleAdapter
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.bluetooth.ScannedDeviceInfo
import java.util.UUID

/**
 * Factory class responsible for creating appropriate [ScaleCommunicator] instances
 * for different Bluetooth scale devices. It decides whether to use a modern Kotlin-based
 * handler or a legacy Java-based adapter.
 */
class ScaleFactory(
    private val applicationContext: Context,
    private val databaseRepository: DatabaseRepository // Needed for LegacyScaleAdapter
) {
    private val TAG = "ScaleHandlerFactory"

    // List of modern Kotlin-based device handlers.
    // These are checked first for device compatibility.
    private val modernKotlinHandlers: List<ScaleDeviceHandler> = listOf(
        DummyScaleHandler("Mi Scale"), // Recognizes devices with "Mi Scale" in their name
        DummyScaleHandler("Beurer"),   // Recognizes devices with "Beurer" in their name
        DummyScaleHandler("BF700")     // Recognizes devices with "BF700" in their name
    )

    /**
     * Attempts to create a legacy Java Bluetooth driver instance based on the device name.
     * This method contains the logic to map device names to specific Java driver classes.
     *
     * @param context The application context.
     * @param deviceName The name of the Bluetooth device.
     * @return A [BluetoothCommunication] instance if a matching driver is found, otherwise null.
     */
    private fun createLegacyJavaDriver(context: Context?, deviceName: String): BluetoothCommunication? {
        // val name = deviceName.lowercase() // deviceName is already used directly below, toLowerCase is not strictly needed if comparisons handle case.

        // Currently, only Yunmai drivers are active examples.
        // The extensive list of commented-out drivers can be re-enabled or migrated as needed.
        if (deviceName.startsWith("YUNMAI-SIGNAL") || deviceName.startsWith("YUNMAI-ISM")) {
            return BluetoothYunmaiSE_Mini(context, true)
        }
        if (deviceName.startsWith("YUNMAI-ISSE")) {
            return BluetoothYunmaiSE_Mini(context, false)
        }
        // Add other legacy driver instantiations here based on deviceName.
        // Example:
        // if (name.startsWith("some_legacy_device")) {
        //     return SomeLegacyDeviceDriver(context)
        // }
        return null
    }

    /**
     * Creates a [ScaleCommunicator] using the legacy Java driver approach.
     * It wraps a [BluetoothCommunication] instance (Java driver) in a [LegacyScaleAdapter].
     *
     * @param identifier The device name or other identifier used to find a legacy Java driver.
     * @return A [LegacyScaleAdapter] instance if a suitable Java driver is found, otherwise null.
     */
    private fun createLegacyCommunicator(identifier: String): ScaleCommunicator? {
        val javaDriverInstance = createLegacyJavaDriver(applicationContext, identifier)
        return if (javaDriverInstance != null) {
            LogManager.i(TAG, "Creating LegacyScaleAdapter with Java driver '${javaDriverInstance.javaClass.simpleName}'.")
            LegacyScaleAdapter(
                applicationContext = applicationContext,
                bluetoothDriverInstance = javaDriverInstance,
                databaseRepository = databaseRepository
            )
        } else {
            LogManager.w(TAG, "Could not create LegacyScaleAdapter: No Java driver found for '$identifier'.")
            null
        }
    }

    /**
     * Creates a [ScaleCommunicator] based on a modern [ScaleDeviceHandler].
     * This method is conceptual for now, as the current DummyScaleHandlers are not full communicators.
     * In a full implementation, this might return the handler itself if it's a ScaleCommunicator,
     * or wrap it in a modern adapter.
     *
     * @param handler The [ScaleDeviceHandler] that can handle the device.
     * @return A [ScaleCommunicator] instance if one can be provided by or for the handler, otherwise null.
     */
    private fun createModernCommunicator(handler: ScaleDeviceHandler): ScaleCommunicator? {
        LogManager.i(TAG, "Attempting to create modern communicator for handler '${handler.getDriverName()}'.")
        // If the ScaleDeviceHandler itself is a ScaleCommunicator:
        if (handler is ScaleCommunicator) {
            return handler
        } else {
            // Placeholder: Logic to wrap the handler in a specific "ModernScaleCommunicator"
            // if the handler itself isn't a ScaleCommunicator.
            // e.g., return ModernScaleAdapter(applicationContext, handler, databaseRepository)
            LogManager.w(TAG, "Modern handler '${handler.getDriverName()}' is not a ScaleCommunicator, and no wrapper is implemented.")
            return null
        }
    }

    /**
     * Creates the most suitable [ScaleCommunicator] for the given scanned device.
     * It prioritizes modern Kotlin-based handlers and falls back to legacy adapters if necessary.
     *
     * @param deviceInfo Information about the scanned Bluetooth device.
     * @return A [ScaleCommunicator] instance if a suitable handler or adapter is found, otherwise null.
     */
    fun createCommunicator(deviceInfo: ScannedDeviceInfo): ScaleCommunicator? {
        // The `determinedHandlerDisplayName` from ScannedDeviceInfo can be useful here if it was
        // specifically set by getSupportingHandlerInfo for a known handler.
        // Otherwise, `deviceInfo.name` is the primary identifier for the logic here.
        val primaryIdentifier = deviceInfo.name ?: "UnknownDevice"
        LogManager.d(TAG, "createCommunicator: Searching for communicator for '${primaryIdentifier}' (${deviceInfo.address}). Handler hint: '${deviceInfo.determinedHandlerDisplayName}'")

        // 1. Check if a modern Kotlin handler explicitly supports the device.
        for (handler in modernKotlinHandlers) {
            if (handler.canHandleDevice(
                    deviceName = deviceInfo.name,
                    deviceAddress = deviceInfo.address,
                    serviceUuids = deviceInfo.serviceUuids,
                    manufacturerData = deviceInfo.manufacturerData
                )) {
                LogManager.i(TAG, "Modern Kotlin handler '${handler.getDriverName()}' claims '${primaryIdentifier}'.")
                val modernCommunicator = createModernCommunicator(handler)
                if (modernCommunicator != null) {
                    LogManager.i(TAG, "Modern communicator '${modernCommunicator.javaClass.simpleName}' created for '${primaryIdentifier}'.")
                    return modernCommunicator
                } else {
                    LogManager.w(TAG, "Modern handler '${handler.getDriverName()}' claimed '${primaryIdentifier}', but failed to create a communicator.")
                }
            }
        }
        LogManager.d(TAG, "No modern Kotlin handler actively claimed '${primaryIdentifier}' or could create a communicator.")

        // 2. Fallback to legacy adapter if no modern handler matched or created a communicator.
        //    The device name (or a specific legacy handler name, if known from `determinedHandlerDisplayName`) is used.
        val identifierForLegacy = deviceInfo.determinedHandlerDisplayName ?: primaryIdentifier
        LogManager.i(TAG, "Attempting fallback to legacy adapter for identifier '${identifierForLegacy}'.")
        val legacyCommunicator = createLegacyCommunicator(identifierForLegacy)
        if (legacyCommunicator != null) {
            LogManager.i(TAG, "Legacy communicator '${legacyCommunicator.javaClass.simpleName}' created for device (identifier: '${identifierForLegacy}').")
            return legacyCommunicator
        }

        LogManager.w(TAG, "No suitable communicator (neither modern nor legacy) found for device (name: '${deviceInfo.name}', address: '${deviceInfo.address}', handler hint: '${deviceInfo.determinedHandlerDisplayName}').")
        return null
    }

    /**
     * Checks if any known handler (modern Kotlin or legacy Java-based) can theoretically support the given device.
     * This can be used by the UI to indicate if a device is potentially recognizable.
     *
     * @param deviceName The name of the Bluetooth device.
     * @param deviceAddress The MAC address of the device.
     * @param serviceUuids A list of advertised service UUIDs.
     * @param manufacturerData Manufacturer-specific data from the advertisement.
     * @return A Pair where `first` is true if a handler is found, and `second` is the name of the handler/driver, or null.
     */
    fun getSupportingHandlerInfo(
        deviceName: String?,
        deviceAddress: String,
        serviceUuids: List<UUID>,
        manufacturerData: SparseArray<ByteArray>?
    ): Pair<Boolean, String?> {
        val primaryIdentifier = deviceName ?: "UnknownDevice"
        // LogManager.d(TAG, "getSupportingHandlerInfo for: '$primaryIdentifier', Addr: $deviceAddress, UUIDs: ${serviceUuids.size}, ManuData: ${manufacturerData != null}")

        // Check modern handlers first
        for (handler in modernKotlinHandlers) {
            if (handler.canHandleDevice(deviceName, deviceAddress, serviceUuids, manufacturerData)) {
                // LogManager.d(TAG, "getSupportingHandlerInfo: Modern handler '${handler.getDriverName()}' matches '$primaryIdentifier'.")
                return true to handler.getDriverName() // The "driver name" of the modern handler
            }
        }

        // Then check if a legacy driver would exist based on the name
        if (deviceName != null) {
            val legacyJavaDriver = createLegacyJavaDriver(applicationContext, deviceName)
            if (legacyJavaDriver != null) {
                // LogManager.d(TAG, "getSupportingHandlerInfo: Legacy driver '${legacyJavaDriver.javaClass.simpleName}' matches '$deviceName'.")
                // Return the driver name from the BluetoothCommunication interface if available and meaningful.
                return true to legacyJavaDriver.driverName() // Assumes BluetoothCommunication has a driverName() method.
            }
        }
        LogManager.d(TAG, "getSupportingHandlerInfo: No supporting handler found for '$primaryIdentifier'.")
        return false to null
    }
}
