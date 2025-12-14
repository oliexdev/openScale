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
package com.health.openscale.core.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.ScanFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.core.util.isNotEmpty

/**
 * Data class to hold information about a scanned Bluetooth LE device.
 *
 * @property name The advertised name of the device. Can be null.
 * @property address The MAC address of the device.
 * @property rssi The received signal strength indicator (RSSI) in dBm.
 * @property serviceUuids A list of service UUIDs advertised by the device.
 * @property manufacturerData Manufacturer-specific data advertised by the device.
 * @property isSupported Flag indicating whether openScale has a handler for this device.
 * @property determinedHandlerDisplayName The display name of the handler determined for this device, if any.
 */
data class ScannedDeviceInfo(
    var name: String,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<UUID>,
    val manufacturerData: SparseArray<ByteArray>?,
    var isSupported: Boolean = false,
    var determinedHandlerDisplayName: String? = null
)

/**
 * Manages Bluetooth LE device scanning operations using the Blessed library.
 *
 * This class handles starting, stopping, and processing scan results. It exposes
 * [StateFlow]s for discovered devices, scanning status, and scan errors, allowing
 * UI components or ViewModels to observe scanning activity.
 *
 * @param context The application context.
 * @param externalScope A [CoroutineScope] (typically from a ViewModel) for launching tasks like scan timeouts.
 * @param scaleFactory An instance of [ScaleFactory] used to determine device support and handler information.
 */
class BluetoothScannerManager(
    private val context: Context,
    private val externalScope: CoroutineScope,
    private val scaleFactory: ScaleFactory
) {
    private companion object {
        const val TAG = "BluetoothScannerMgr"
    }

    // Ensures Blessed library callbacks are executed on the main thread.
    private val blessedBluetoothHandler = Handler(Looper.getMainLooper())
    private val centralManager: BluetoothCentralManager by lazy {
        BluetoothCentralManager(context, centralManagerCallback, blessedBluetoothHandler)
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedDeviceInfo>>(emptyList())
    /**
     * Emits the current list of discovered and processed [ScannedDeviceInfo] objects.
     * The list is sorted by support status (supported first), then by RSSI (strongest signal first),
     * and finally by device name.
     */
    val scannedDevices: StateFlow<List<ScannedDeviceInfo>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    /**
     * Emits `true` if a Bluetooth LE scan is currently active, `false` otherwise.
     */
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    /**
     * Emits error messages related to the scanning process.
     * Emits `null` if there is no current error or an error has been cleared.
     */
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private var scanTimeoutJob: Job? = null
    // Stores unique devices found during a scan, keyed by MAC address, for efficient updates.
    private val deviceMap = mutableMapOf<String, ScannedDeviceInfo>()

    /**
     * Starts a Bluetooth LE scan for a specified duration.
     *
     * Prerequisites (e.g., Bluetooth enabled, permissions granted) are checked.
     * If a scan is already in progress, this method returns without action.
     *
     * @param scanDurationMs The duration in milliseconds for the scan.
     *                       The scan automatically stops after this period if not manually stopped earlier.
     */
    @SuppressLint("MissingPermission") // Permissions are expected to be checked by the calling ViewModel.
    fun startScan(scanDurationMs: Long) {
        if (!validateScanPrerequisites()) {
            return
        }

        if (_isScanning.value || centralManager.isScanning) {
            LogManager.d(TAG, "Scan is already in progress.")
            return
        }
        LogManager.i(TAG, "Starting device scan for $scanDurationMs ms.")

        deviceMap.clear()
        _scannedDevices.value = emptyList()
        _scanError.value = null // Clear previous errors.
        _isScanning.value = true

        try {
            centralManager.scanForPeripherals()
        } catch (e: Exception) {
            LogManager.e(TAG, "Exception while starting scan: ${e.message}", e)
            _scanError.value = "Error starting scan: ${e.localizedMessage ?: "Unknown error"}"
            _isScanning.value = false
            return
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = externalScope.launch {
            delay(scanDurationMs)
            if (_isScanning.value) {
                LogManager.i(TAG, "Scan timeout reached after $scanDurationMs ms.")
                stopScanInternal(isTimeout = true)
            }
        }
    }

    /**
     * Stops the currently active Bluetooth LE scan.
     */
    fun stopScan() {
        stopScanInternal(isTimeout = false)
    }

    /**
     * Internal implementation for stopping the scan.
     * @param isTimeout Indicates if the stop was triggered by a timeout.
     */
    private fun stopScanInternal(isTimeout: Boolean) {
        if (!_isScanning.value && !centralManager.isScanning) {
            return // Scan not active.
        }
        LogManager.i(TAG, "Stopping device scan. Triggered by timeout: $isTimeout")
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null

        try {
            if (centralManager.isScanning) {
                centralManager.stopScan()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Exception while stopping scan: ${e.message}", e)
            // Optionally, an error could be set here, but it's often not critical for a stop action.
        }
        _isScanning.value = false

        if (isTimeout && deviceMap.isEmpty()) {
            _scanError.value = "No devices found."
        }
        LogManager.d(TAG, "Scan stopped. Found devices: ${deviceMap.size}")
    }

    /**
     * Validates if conditions are met to start a scan (e.g., Bluetooth enabled).
     * Note: Permission checks are the responsibility of the calling ViewModel.
     *
     * @return `true` if prerequisites are met, `false` otherwise.
     *         If `false`, `_scanError` is updated with the reason.
     */
    private fun validateScanPrerequisites(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (bluetoothManager?.adapter?.isEnabled != true) {
            LogManager.w(TAG, "Scan prerequisites not met: Bluetooth is disabled.")
            _scanError.value = "Bluetooth is disabled. Please enable it to scan."
            return false
        }

        if (_isScanning.value) {
            LogManager.d(TAG, "Scan is already in progress (checked in validate).")
            return false
        }
        _scanError.value = null // Clear errors if prerequisites are met.
        return true
    }

    /**
     * Clears any active scan error message from `scanError` StateFlow.
     */
    fun clearScanError() {
        if (_scanError.value != null) {
            _scanError.value = null
        }
    }

    /**
     * Releases resources used by the scanner, including the Blessed [BluetoothCentralManager].
     * Call this when the scanner is no longer needed (e.g., in ViewModel's `onCleared`).
     */
    fun close() {
        LogManager.i(TAG, "Closing BluetoothScannerManager.")
        stopScanInternal(isTimeout = false) // Ensure scan is stopped.
        try {
            // Crucial to close BluetoothCentralManager to release system resources
            // and unregister internal broadcast receivers used by the Blessed library.
            centralManager.close()
            LogManager.d(TAG, "Blessed BluetoothCentralManager closed successfully.")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error closing Blessed BluetoothCentralManager: ${e.message}", e)
        }
    }

    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        @SuppressLint("MissingPermission") // Permissions are handled before scan initiation.
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            val deviceName = peripheral.name
            val deviceAddress = peripheral.address
            val rssi = scanResult.rssi
            val serviceUuids: List<UUID> = scanResult.scanRecord?.serviceUuids?.mapNotNull { it?.uuid } ?: emptyList()
            val manufacturerData: SparseArray<ByteArray>? = scanResult.scanRecord?.manufacturerSpecificData

            val newDevice = ScannedDeviceInfo(
                name = deviceName,
                address = deviceAddress,
                rssi = rssi,
                serviceUuids = serviceUuids,
                manufacturerData = manufacturerData,
                isSupported = false, // will be determined in the next getSupportingHandlerInfo
                determinedHandlerDisplayName = null // // will be determined in the next getSupportingHandlerInfo
            )

            val (isSupported, handlerName) = scaleFactory.getSupportingHandlerInfo(newDevice)

            newDevice.isSupported = isSupported
            newDevice.determinedHandlerDisplayName = handlerName

            val existingDevice = deviceMap[newDevice.address]
            var listShouldBeUpdated = false

            if (existingDevice != null) {
                // Update criteria: if RSSI changed, or if key device info (name, support, handler, services, manufacturer data) has improved or changed.
                val nameChangedToKnown = newDevice.name != null && existingDevice.name == null
                val supportStatusImproved = !existingDevice.isSupported && newDevice.isSupported
                val handlerChanged = newDevice.determinedHandlerDisplayName != existingDevice.determinedHandlerDisplayName
                val serviceUuidsUpdated = newDevice.serviceUuids.isNotEmpty() && newDevice.serviceUuids != existingDevice.serviceUuids
                val manuDataUpdated = newDevice.manufacturerData != null && !newDevice.manufacturerData.contentEquals(existingDevice.manufacturerData)

                if (newDevice.rssi != existingDevice.rssi || nameChangedToKnown || supportStatusImproved || handlerChanged || serviceUuidsUpdated || manuDataUpdated) {
                    deviceMap[newDevice.address] = existingDevice.copy(
                        name = newDevice.name ?: existingDevice.name, // Prefer new name if available.
                        rssi = newDevice.rssi,
                        isSupported = existingDevice.isSupported || newDevice.isSupported, // Retain 'supported' status if ever true.
                        determinedHandlerDisplayName = newDevice.determinedHandlerDisplayName ?: existingDevice.determinedHandlerDisplayName,
                        serviceUuids = if (newDevice.serviceUuids.isNotEmpty()) newDevice.serviceUuids else existingDevice.serviceUuids,
                        manufacturerData = newDevice.manufacturerData ?: existingDevice.manufacturerData
                    )
                    listShouldBeUpdated = true
                }
            } else {
                // Add new device if it's supported, or has a meaningful name, or provides service/manufacturer data.
                // This avoids populating the list with devices that have no identifying information and are not supported.
                if (newDevice.isSupported ||
                    !newDevice.name.isNullOrEmpty() ||
                    newDevice.serviceUuids.isNotEmpty() ||
                    (newDevice.manufacturerData != null && newDevice.manufacturerData.isNotEmpty())
                ) {
                    deviceMap[newDevice.address] = newDevice
                    listShouldBeUpdated = true
                }
            }

            if (listShouldBeUpdated) {
                // Filter ensures only devices that are supported or have a meaningful name (not generic "Unknown Device") are emitted.
                // Sorting provides a consistent and user-friendly order.
                _scannedDevices.value = deviceMap.values
                    .filter { it.isSupported || (!it.name.isNullOrEmpty() && it.name != "Unbekanntes Gerät" && it.name != "Unknown Device") }
                    .sortedWith(compareByDescending<ScannedDeviceInfo> { it.isSupported }
                        .thenByDescending { it.rssi }
                        .thenBy { it.name?.lowercase() ?: "zzzz" }) // "zzzz" ensures null names sort last.
                    .toList()
            }
        }

        override fun onScanFailed(scanFailure: ScanFailure) {
            LogManager.e(TAG, "Bluetooth scan failed: $scanFailure")
            externalScope.launch {
                _scanError.value = "Bluetooth Scan Failed: $scanFailure"
                _isScanning.value = false
                scanTimeoutJob?.cancel() // Stop scan timeout if scan fails.
            }
        }
    }

}
