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
package com.health.openscale.core.bluetooth.scales

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.core.content.getSystemService
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.min

/**
 * SPP (Bluetooth Classic / RFCOMM) adapter that plugs a [ScaleDeviceHandler] into a raw byte stream.
 *
 * Tuning usage:
 * - Reconnect cooldown between attempts (common.reconnectCooldownMs)
 * - Bounded retry on initial connect (common.maxRetries + common.retryBackoffMs)
 * - Connect timeout (connectTimeoutMs)
 * - Chunked writes (writeChunkBytes + interChunkDelayMs)
 * - Small settle delay after connect (derived from interChunkDelayMs)
 */
class SppScaleAdapter(
    context: Context,
    settingsFacade: SettingsFacade,
    measurementFacade: MeasurementFacade,
    userFacade: UserFacade,
    handler: ScaleDeviceHandler,
    profile: TuningProfile = TuningProfile.Balanced
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    private val tuning: BtSppTuning = profile.forSpp()

    private var sppSocket: BluetoothSocket? = null
    private var sppReaderJob: Job? = null
    private var sppIn: InputStream? = null
    private var sppOut: OutputStream? = null

    private val writeMutex = Mutex()

    @Composable
    override fun DeviceConfigurationUi() {
        // Delegate to the actual protocol handler
        handler.DeviceConfigurationUi()
    }

    @SuppressLint("MissingPermission")
    override fun doConnect(address: String, selectedUser: ScaleUser) {
        val btManager: BluetoothManager? = context.getSystemService()
        val adapter: BluetoothAdapter? = btManager?.adapter

        if (adapter == null) {
            _events.tryEmit(BluetoothEvent.ConnectionFailed(address, context.getString(R.string.bt_error_no_bluetooth_adapter)))
            return
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (_: Throwable) {
            _events.tryEmit(BluetoothEvent.ConnectionFailed(address, context.getString(R.string.bt_error_no_device_found)))
            return
        }

        _isConnecting.value = true
        _isConnected.value = false

        scope.launch(Dispatchers.IO) {
            // Cooldown between attempts
            val since = SystemClock.elapsedRealtime() - lastDisconnectAtMs
            if (since in 1 until tuning.common.reconnectCooldownMs) {
                delay(tuning.common.reconnectCooldownMs - since)
            }

            safeCancelDiscovery(adapter)

            var attempt = 0
            while (isActive) {
                try {
                    LogManager.i(TAG, "Attempting SPP connection (attempt ${attempt + 1})")
                    val socket = device.createRfcommSocketToServiceRecord(ScaleDeviceHandler.CLASSIC_DATA_UUID)
                    sppSocket = socket

                    // --- Connect with manual timeout guard ---
                    var connected = false
                    // Start the blocking connect() in a child job
                    val connectJob = launch(Dispatchers.IO) {
                        socket.connect() // blocking call
                        connected = true
                        LogManager.i(TAG, "SPP connect() succeeded")
                    }
                    // Start a guard that closes the socket if connect takes too long
                    val guardJob = launch(Dispatchers.IO) {
                        val to = tuning.connectTimeoutMs
                        if (to > 0) {
                            delay(to)
                            if (!connected) {
                                LogManager.w(TAG, "Connect timeout reached ($to ms), closing socket")
                                // Force the connect() to abort by closing the socket
                                runCatching { socket.close() }
                            }
                        }
                    }

                    // Wait until either connect finishes or guard closes the socket
                    connectJob.join()
                    guardJob.cancel()

                    // If connect failed, connect() would have thrown and we’d be in catch{}
                    sppIn = socket.inputStream
                    sppOut = socket.outputStream

                    // Small settle delay before wiring the handler
                    val settleDelay = maxOf(50L, tuning.interChunkDelayMs * 3)
                    delay(settleDelay)

                    _isConnecting.value = false
                    _isConnected.value = true

                    val name = safeDeviceName(device)
                    val addr = safeDeviceAddress(device)
                    LogManager.i(TAG, "Connected to device $name [$addr]")
                    _events.tryEmit(BluetoothEvent.Connected(name, addr))

                    // Attach handler
                    val driverSettings = FacadeDriverSettings(
                        facade = settingsFacade,
                        scope = scope,
                        handlerNamespace = handler::class.simpleName ?: "Handler"
                    )
                    handler.attach(sppTransport, appCallbacks, driverSettings, dataProvider)
                    handler.handleConnected(selectedUser)

                    // Reader loop (idle-timeout via available()+delay)
                    sppReaderJob = launch(Dispatchers.IO) {
                        val buf = ByteArray(1024)
                        var lastRx = SystemClock.elapsedRealtime()
                        try {
                            while (isActive) {
                                val ins = sppIn ?: break
                                val avail = runCatching { ins.available() }.getOrDefault(0)

                                if (avail > 0) {
                                    val n = ins.read(buf, 0, min(buf.size, avail))
                                    if (n <= 0) break
                                    lastRx = SystemClock.elapsedRealtime()
                                    val payload = buf.copyOf(n)
                                    LogManager.d(TAG, "Received $n bytes from SPP ${payload.toHexPreview(24)}")
                                    handler.handleNotification(ScaleDeviceHandler.CLASSIC_DATA_UUID, payload)
                                } else {
                                    delay(50)
                                    val idle = SystemClock.elapsedRealtime() - lastRx
                                    if (tuning.readTimeoutMs > 0 && idle >= tuning.readTimeoutMs) {
                                        LogManager.w(TAG, "Read idle timeout reached, disconnecting")
                                        sppTransport.disconnect()
                                        break
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            LogManager.w(TAG, "SPP read error: ${t.message}", t)
                        } finally {
                            withContext(Dispatchers.Main) {
                                val da = safeDeviceAddress(device)
                                LogManager.i(TAG, "Reader loop finished, emitting disconnect")
                                _events.tryEmit(BluetoothEvent.Disconnected(da, "SPP stream closed"))
                                lastDisconnectAtMs = SystemClock.elapsedRealtime()
                                cleanup(da)
                                doDisconnect()
                            }
                        }
                    }

                    // success → exit retry loop
                    break
                } catch (t: Throwable) {
                    attempt++
                    LogManager.e(TAG, "SPP connect failed (attempt $attempt/${tuning.common.maxRetries}): ${t.message}", t)

                    if (attempt <= tuning.common.maxRetries) {
                        _events.tryEmit(
                            BluetoothEvent.DeviceMessage(
                                context.getString(R.string.bt_info_reconnecting_try, attempt, tuning.common.maxRetries),
                                address
                            )
                        )
                        delay(tuning.common.retryBackoffMs)
                        safeCancelDiscovery(adapter)
                        continue
                    } else {
                        _events.tryEmit(BluetoothEvent.ConnectionFailed(address, t.message ?: "SPP connect failed"))
                        lastDisconnectAtMs = SystemClock.elapsedRealtime()
                        cleanup(address)
                        doDisconnect()
                        break
                    }
                }
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
        lastDisconnectAtMs = SystemClock.elapsedRealtime()
    }

    // --- Transport exposed to the handler --------------------------------------------------------

    private val sppTransport = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            // Not applicable for SPP: stream is always "notifying"
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            // RFCOMM is a stream; we still pace writes and chunk large payloads
            scope.launch(Dispatchers.IO) {
                writeMutex.withLock {
                    try {
                        LogManager.d(TAG, "Starting write of ${payload.size} bytes to SPP: ${payload.toHexPreview(24)}")
                        val chunk = maxOf(1, tuning.writeChunkBytes)
                        var i = 0
                        while (i < payload.size) {
                            val end = min(i + chunk, payload.size)
                            sppOut?.write(payload, i, end - i)
                            sppOut?.flush()
                            val writtenChunk = payload.copyOfRange(i, end)
                            LogManager.d(TAG, "Wrote chunk ${i / chunk + 1}: ${writtenChunk.toHexPreview(16)}")
                            i = end
                            if (i < payload.size && tuning.interChunkDelayMs > 0) {
                                delay(tuning.interChunkDelayMs)
                            }
                        }
                        LogManager.i(TAG, "Finished writing ${payload.size} bytes to SPP")
                    } catch (t: Throwable) {
                        LogManager.e(TAG, "SPP write failed: ${t.message}", t)
                        appCallbacks.onWarn(R.string.bt_warn_write_failed_status,"SPP",t.message ?: "write failed")
                    }
                }
            }
        }

        override fun read(service: UUID, characteristic: UUID) {
            // Not applicable for SPP; reads are handled by the continuous reader loop
        }

        override fun disconnect() {
            doDisconnect()
        }

        override fun getPeripheral(): BluetoothPeripheral? = null

        override fun hasCharacteristic(
            service: UUID,
            characteristic: UUID
        ): Boolean {
            // Not applicable for SPP
            return false
        }
    }

    // --- Helpers with defensive permission handling ---------------------------------------------

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
