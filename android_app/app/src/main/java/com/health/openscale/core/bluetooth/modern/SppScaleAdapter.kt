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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.core.content.getSystemService
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * SPP (Bluetooth Classic / RFCOMM) adapter that plugs a [ScaleDeviceHandler] into a raw byte stream.
 *
 * Differences vs. GATT:
 * - There are no services/characteristics; we expose a virtual characteristic UUID
 *   ([ScaleDeviceHandler.CLASSIC_DATA_UUID]) to the handler for symmetry.
 * - The stream is "always notifying": reads are forwarded as notifications.
 * - Writes are plain byte writes to the RFCOMM socket.
 *
 * Permissions:
 * - UI layer must request BLUETOOTH_CONNECT/SCAN (Android 12+) beforehand.
 * - We still guard sensitive calls with try/catch (SecurityException) to be robust.
 */
class SppScaleAdapter(
    context: Context,
    settingsFacade: SettingsFacade,
    measurementFacade: MeasurementFacade,
    userFacade: UserFacade,
    handler: ScaleDeviceHandler,
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    private var sppSocket: BluetoothSocket? = null
    private var sppReaderJob: Job? = null
    private var sppIn: InputStream? = null
    private var sppOut: OutputStream? = null

    /**
     * Establish an RFCOMM connection and wire the byte stream to the handler.
     * Uses AndroidX Core-KTX system service helper.
     */
    @SuppressLint("MissingPermission") // Permissions are acquired in UI; here we additionally guard with try/catch
    override fun doConnect(address: String, selectedUser: ScaleUser) {
        // Modern KTX way to obtain the BluetoothManager (no deprecated getDefaultAdapter())
        val btManager: BluetoothManager? = context.getSystemService()
        val adapter: BluetoothAdapter? = btManager?.adapter

        if (adapter == null) {
            _events.tryEmit(
                BluetoothEvent.ConnectionFailed(
                    address,
                    context.getString(R.string.bt_error_no_bluetooth_adapter)
                )
            )
            return
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (t: Throwable) {
            _events.tryEmit(
                BluetoothEvent.ConnectionFailed(
                    address,
                    context.getString(R.string.bt_error_no_device_found)
                )
            )
            return
        }

        _isConnecting.value = true
        _isConnected.value = false

        scope.launch(Dispatchers.IO) {
            try {
                // Discovery interferes with connects; cancel if running (may throw if permission revoked)
                safeCancelDiscovery(adapter)

                // Create & connect SPP socket (UUID provided by handler contract)
                val socket = device.createRfcommSocketToServiceRecord(ScaleDeviceHandler.CLASSIC_DATA_UUID)
                sppSocket = socket
                socket.connect()

                sppIn = socket.inputStream
                sppOut = socket.outputStream

                _isConnecting.value = false
                _isConnected.value = true

                val name = safeDeviceName(device) // avoids SecurityException on Android 12+
                val addr = safeDeviceAddress(device)

                _events.tryEmit(BluetoothEvent.Connected(name, addr))

                // Hand off to the device handler
                val driverSettings = FacadeDriverSettings(
                    facade = settingsFacade,
                    scope = scope,
                    deviceAddress = addr,
                    handlerNamespace = handler::class.simpleName ?: "Handler"
                )
                handler.attach(sppTransport, appCallbacks, driverSettings, dataProvider)
                handler.handleConnected(selectedUser)

                // Reader loop: forward bytes as "notifications" on CLASSIC_DATA_UUID
                sppReaderJob = launch(Dispatchers.IO) {
                    val buf = ByteArray(1024)
                    try {
                        while (isActive) {
                            val n = sppIn?.read(buf) ?: -1
                            if (n <= 0) break
                            handler.handleNotification(ScaleDeviceHandler.CLASSIC_DATA_UUID, buf.copyOf(n))
                        }
                    } catch (t: Throwable) {
                        LogManager.w(TAG, "SPP read error: ${t.message}", null)
                    } finally {
                        withContext(Dispatchers.Main) {
                            _events.tryEmit(BluetoothEvent.Disconnected(addr, "SPP stream closed"))
                            cleanup(addr)
                            doDisconnect()
                        }
                    }
                }
            } catch (t: Throwable) {
                LogManager.e(TAG, "SPP connect failed: ${t.message}", t)
                _events.tryEmit(BluetoothEvent.ConnectionFailed(address, t.message ?: "SPP connect failed"))
                cleanup(address)
                doDisconnect()
            }
        }
    }

    override fun doDisconnect() {
        sppReaderJob?.cancel(); sppReaderJob = null
        runCatching { sppIn?.close() };   sppIn = null
        runCatching { sppOut?.close() };  sppOut = null
        runCatching { sppSocket?.close() }; sppSocket = null
        runCatching { handler.handleDisconnected() }
        runCatching { handler.detach() }
        _isConnected.value = false
        _isConnecting.value = false
    }

    // --- Transport exposed to the handler -------------------------------------

    private val sppTransport = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            // Not applicable for SPP: stream is always "notifying"
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            scope.launch(Dispatchers.IO) {
                try {
                    sppOut?.write(payload)
                    sppOut?.flush()
                } catch (t: Throwable) {
                    appCallbacks.onWarn(
                        R.string.bt_warn_write_failed_status,
                        "SPP",
                        t.message ?: "write failed"
                    )
                }
            }
        }

        override fun read(service: UUID, characteristic: UUID) {
            // Not applicable for SPP; reads are handled by the continuous reader loop
        }

        override fun disconnect() {
            doDisconnect()
        }
    }

    // --- Small helpers with defensive permission handling ----------------------

    @SuppressLint("MissingPermission")
    private fun safeCancelDiscovery(adapter: BluetoothAdapter) {
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (se: SecurityException) {
            LogManager.w(TAG, "cancelDiscovery blocked by missing permission", se)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String =
        try { device.name } catch (_: SecurityException) { null } ?: "Unknown"

    @SuppressLint("MissingPermission")
    private fun safeDeviceAddress(device: BluetoothDevice): String =
        try { device.address } catch (_: SecurityException) { "unknown" }

    companion object {
        private const val TAG = "SppScaleAdapter"
    }
}
