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

import android.bluetooth.le.ScanResult
import android.os.SystemClock
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.ConnectionPriority
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// -------------------------------------------------------------------------------------------------
// GATT adapter (BLE)
// - scans for a specific address and connects via Blessed
// - enables notifications, handles read/write with pacing (BleTuning)
// - forwards notifications to handler.onNotification()
// -------------------------------------------------------------------------------------------------

class GattScaleAdapter(
    context: android.content.Context,
    settingsFacade: SettingsFacade,
    measurementFacade: MeasurementFacade,
    userFacade: UserFacade,
    handler: ScaleDeviceHandler,
    profile: TuningProfile = TuningProfile.Balanced
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    private val tuning = profile.forGatt()

    private lateinit var central: BluetoothCentralManager
    private var currentPeripheral: BluetoothPeripheral? = null

    private val ioMutex = Mutex()
    private suspend fun ioGap(ms: Long) { if (ms > 0) delay(ms) }

    private var connectAttempts = 0

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            if (peripheral.address != targetAddress) return
            LogManager.i(TAG, "Found $targetAddress → stop scan + connect")
            central.stopScan()
            scope.launch {
                if (tuning.connectAfterScanDelayMs > 0) delay(tuning.connectAfterScanDelayMs)
                central.connectPeripheral(peripheral, peripheralCallback)
            }
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            scope.launch {
                currentPeripheral = peripheral
                _isConnected.value = true
                _isConnecting.value = false
                _events.tryEmit(BluetoothEvent.Connected(peripheral.name ?: "Unknown", peripheral.address))
            }
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            scope.launch {
                LogManager.e(TAG, "Connection failed ${peripheral.address}: $status")
                if (connectAttempts < tuning.common.maxRetries) {
                    val nextTry = connectAttempts + 1
                    _events.tryEmit(
                        BluetoothEvent.DeviceMessage(
                            context.getString(R.string.bt_info_reconnecting_try, nextTry, tuning.common.maxRetries),
                            peripheral.address
                        )
                    )
                    connectAttempts = nextTry
                    delay(tuning.common.retryBackoffMs)
                    runCatching { central.stopScan() }
                    central.scanForPeripheralsWithAddresses(arrayOf(peripheral.address))
                    _isConnecting.value = true
                } else {
                    _events.tryEmit(BluetoothEvent.ConnectionFailed(peripheral.address, status.toString()))
                    cleanup(peripheral.address)
                }
            }
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            scope.launch {
                LogManager.i(TAG, "Disconnected ${peripheral.address}: $status")
                runCatching { handler.handleDisconnected() }
                runCatching { handler.detach() }
                lastDisconnectAtMs = SystemClock.elapsedRealtime()
                if (peripheral.address == targetAddress) {
                    _events.tryEmit(BluetoothEvent.Disconnected(peripheral.address, status.toString()))
                    cleanup(peripheral.address)
                }
            }
        }
    }

    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            LogManager.d(TAG, "Services discovered for ${peripheral.address}")
            currentPeripheral = peripheral

            if (tuning.requestHighConnectionPriority) runCatching { peripheral.requestConnectionPriority(
                ConnectionPriority.HIGH) }
            if (tuning.requestMtuBytes > 23) runCatching { peripheral.requestMtu(tuning.requestMtuBytes) }

            val user = selectedUserSnapshot ?: run {
                central.cancelConnection(peripheral); return
            }

            val driverSettings = FacadeDriverSettings(
                facade = settingsFacade,
                scope = scope,
                deviceAddress = peripheral.address,
                handlerNamespace = handler::class.simpleName ?: "Handler"
            )

            handler.attach(transport, appCallbacks, driverSettings, dataProvider)
            handler.handleConnected(user)
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status == GattStatus.SUCCESS) {
                handler.handleNotification(characteristic.uuid, value)
            } else {
                appCallbacks.onWarn(
                    R.string.bt_warn_notify_state_failed_status,
                    characteristic.uuid.toString(),
                    status.toString()
                )
            }
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status != GattStatus.SUCCESS) {
                appCallbacks.onWarn(
                    R.string.bt_warn_write_failed_status,
                    characteristic.uuid.toString(),
                    status.toString()
                )
            }
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status != GattStatus.SUCCESS) {
                appCallbacks.onWarn(
                    R.string.bt_warn_notify_state_failed_status,
                    characteristic.uuid.toString(),
                    status.toString()
                )
            }
        }
    }

    private val transport = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            scope.launch {
                ioMutex.withLock {
                    val p = currentPeripheral ?: run {
                        appCallbacks.onWarn(R.string.bt_warn_no_peripheral_for_setnotify, characteristic.toString()); return@withLock
                    }
                    val ch = p.getCharacteristic(service, characteristic) ?: run {
                        appCallbacks.onWarn(R.string.bt_warn_characteristic_not_found, characteristic.toString()); return@withLock
                    }
                    ioGap(tuning.notifySetupDelayMs)
                    if (!p.setNotify(ch, true)) {
                        appCallbacks.onWarn(R.string.bt_warn_notify_failed, characteristic.toString())
                    }
                }
            }
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            scope.launch {
                ioMutex.withLock {
                    val p = currentPeripheral ?: run {
                        appCallbacks.onWarn(R.string.bt_warn_no_peripheral_for_write, characteristic.toString()); return@withLock
                    }
                    val ch = p.getCharacteristic(service, characteristic) ?: run {
                        appCallbacks.onWarn(R.string.bt_warn_characteristic_not_found, characteristic.toString()); return@withLock
                    }
                    val type = if (withResponse) WriteType.WITH_RESPONSE else WriteType.WITHOUT_RESPONSE
                    ioGap(if (withResponse) tuning.writeWithResponseDelayMs else tuning.writeWithoutResponseDelayMs)
                    p.writeCharacteristic(service, characteristic, payload, type)
                    ioGap(tuning.postWriteDelayMs)
                }
            }
        }

        override fun read(service: UUID, characteristic: UUID) {
            scope.launch {
                ioMutex.withLock {
                    val p = currentPeripheral ?: run {
                        appCallbacks.onWarn(R.string.bt_warn_no_peripheral_for_read, characteristic.toString()); return@withLock
                    }
                    p.readCharacteristic(service, characteristic)
                }
            }
        }

        override fun disconnect() {
            currentPeripheral?.let { central.cancelConnection(it) }
        }
    }

    override fun doConnect(address: String, selectedUser: ScaleUser) {
        // Lazily create the central if this is the first connect attempt.
        if (!::central.isInitialized) {
            central = BluetoothCentralManager(context, centralCallback, mainHandler)
        }

        // Respect a cooldown between disconnect and next connect attempt to avoid stack churn.
        val sinceLastDisconnect = SystemClock.elapsedRealtime() - lastDisconnectAtMs
        val waitMs = (tuning.common.reconnectCooldownMs - sinceLastDisconnect).coerceAtLeast(0)

        // Reset session-local counters/flags for this attempt.
        connectAttempts = 0
        _isConnected.value = false
        _isConnecting.value = true

        // Ensure no stale scan is running from a previous attempt.
        runCatching { central.stopScan() }

        // Defer starting the scan/connect until the cooldown has elapsed.
        scope.launch {
            if (waitMs > 0) {
                LogManager.d(TAG, "Reconnect cooldown: waiting ${waitMs} ms before scanning $address")
                delay(waitMs)
            }

            try {
                // Start a directed scan for the target MAC; Blessed will call back to centralCallback.
                central.scanForPeripheralsWithAddresses(arrayOf(address))
            } catch (e: Exception) {
                // If scanning/connecting fails immediately, surface a failure event and clean up state.
                LogManager.e(TAG, "Failed to start scan/connect: ${e.message}", e)
                _events.tryEmit(
                    BluetoothEvent.ConnectionFailed(
                        address,
                        e.message ?: context.getString(R.string.bt_error_generic)
                    )
                )
                cleanup(address)
            }
        }
    }


    override fun doDisconnect() {
        runCatching { if (::central.isInitialized) central.stopScan() }
        currentPeripheral?.let { runCatching { central.cancelConnection(it) } }
        currentPeripheral = null
    }
}