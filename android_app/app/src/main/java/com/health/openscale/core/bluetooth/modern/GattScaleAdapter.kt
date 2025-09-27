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

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// -------------------------------------------------------------------------------------------------
// GATT adapter (BLE)
// - scans for a specific address and connects via Blessed
// - enables notifications, handles read/write with pacing (BleTuning)
// - forwards notifications to handler.onNotification()
// -------------------------------------------------------------------------------------------------

class GattScaleAdapter(
    context: Context,
    settingsFacade: SettingsFacade,
    measurementFacade: MeasurementFacade,
    userFacade: UserFacade,
    handler: ScaleDeviceHandler,
    profile: TuningProfile = TuningProfile.Balanced
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    private val tuning: BleGattTuning = profile.forGatt()
    private lateinit var central: BluetoothCentralManager
    private var currentPeripheral: BluetoothPeripheral? = null

    private val opQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val currentDeferred = AtomicReference<CompletableDeferred<*>?>(null)
    private val ioMutex = Mutex()

    private var connectAttempts = 0

    init {
        // Worker coroutine processes queued BLE operations sequentially
        scope.launch {
            for (op in opQueue) {
                try {
                    ioMutex.lock()
                    op()
                } catch (t: Throwable) {
                    LogManager.e(TAG, "BLE operation failed", t)
                } finally {
                    ioMutex.unlock()
                }
            }
        }
    }

    private suspend fun ioGap(ms: Long) {
        if (ms > 0) delay(ms)
    }

    // -------------------------------------------------------------------------------------------------
    // Bluetooth central callbacks
    // -------------------------------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------------------------------
    // Peripheral callback receives all GATT events
    // -------------------------------------------------------------------------------------------------
    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            LogManager.d(TAG, "Services discovered for ${peripheral.address}")
            currentPeripheral = peripheral

            if (tuning.requestHighConnectionPriority) runCatching { peripheral.requestConnectionPriority(ConnectionPriority.HIGH) }
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

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            (currentDeferred.getAndSet(null) as? CompletableDeferred<Unit>)?.complete(Unit)
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            (currentDeferred.getAndSet(null) as? CompletableDeferred<Unit>)?.complete(Unit)
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            (currentDeferred.getAndSet(null) as? CompletableDeferred<ByteArray>)?.complete(value)

            handler.handleNotification(characteristic.uuid, value)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Transport exposed to handler; operations are queued automatically
    // -------------------------------------------------------------------------------------------------
    private val transport = object : ScaleDeviceHandler.Transport {

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            opQueue.trySend {
                val p = currentPeripheral ?: return@trySend
                val ch = p.getCharacteristic(service, characteristic) ?: return@trySend

                val deferred = CompletableDeferred<Unit>()
                currentDeferred.set(deferred)

                val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                val cccd = ch.getDescriptor(cccdUuid)

                if (cccd != null) {
                    // CCCD exists → enable notification/indication
                    val success = runCatching { p.setNotify(ch, true) }.getOrElse {
                        LogManager.w(TAG,"Failed to enable notify for $characteristic: ${it.message}")
                        false
                    }
                    if (!success) {
                        appCallbacks.onWarn(R.string.bt_warn_notify_failed, characteristic.toString())
                        deferred.complete(Unit)
                    }
                } else {
                    // No CCCD → check if indication is supported
                    when {
                        ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> {
                            LogManager.i(TAG, "Characteristic $characteristic has no CCCD but supports INDICATE; enabling indication")
                            val success = runCatching { p.setNotify(ch, true) }.getOrElse {
                                LogManager.w(TAG,"Failed to enable indicate for $characteristic: ${it.message}")
                                false
                            }
                            if (!success) appCallbacks.onWarn(R.string.bt_warn_notify_failed, characteristic.toString())
                        }
                        ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> {
                            LogManager.i(TAG, "Characteristic $characteristic has no CCCD but supports NOTIFY; enabling notify")
                            val success = runCatching { p.setNotify(ch, true) }.getOrElse {
                                LogManager.w(TAG,"Failed to enable notify for $characteristic: ${it.message}")
                                false
                            }
                            if (!success) appCallbacks.onWarn(R.string.bt_warn_notify_failed, characteristic.toString())
                        }
                        else -> {
                            LogManager.w(TAG,"Characteristic $characteristic has no CCCD and does not support notify/indicate")
                        }
                    }
                }

                try {
                    // Wait with timeout from tuning
                    withTimeout(tuning.operationTimeoutMs) {
                        deferred.await()
                    }
                } catch (e: Exception) {
                    LogManager.w(TAG,"Timeout waiting for notify/indicate enable on $characteristic")
                }

                ioGap(tuning.notifySetupDelayMs)
            }
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            opQueue.trySend {
                val p = currentPeripheral ?: return@trySend
                val ch = p.getCharacteristic(service, characteristic) ?: return@trySend

                val deferred = CompletableDeferred<Unit>()
                currentDeferred.set(deferred)

                val supportsWriteNoResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                val supportsWriteResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0

                val type = when {
                    withResponse && supportsWriteResponse -> WriteType.WITH_RESPONSE
                    !withResponse && supportsWriteNoResponse -> WriteType.WITHOUT_RESPONSE
                    supportsWriteResponse -> {
                        LogManager.w(TAG, "Characteristic $characteristic does not support WITHOUT_RESPONSE, using WITH_RESPONSE instead")
                        WriteType.WITH_RESPONSE
                    }
                    supportsWriteNoResponse -> {
                        LogManager.w(TAG, "Characteristic $characteristic does not support WITH_RESPONSE, using WITHOUT_RESPONSE instead")
                        WriteType.WITHOUT_RESPONSE
                    }
                    else -> {
                        LogManager.w(TAG, "Characteristic $characteristic does not support writing")
                        return@trySend
                    }
                }

                ioGap(if (withResponse) tuning.writeWithResponseDelayMs else tuning.writeWithoutResponseDelayMs)
                p.writeCharacteristic(service, characteristic, payload, type)

                try {
                    withTimeout(tuning.operationTimeoutMs) {
                        deferred.await()
                    }
                } catch (t: Throwable) {
                    LogManager.w(TAG, "Timeout waiting for write on $characteristic")
                }

                ioGap(tuning.postWriteDelayMs)
            }
        }

        override fun read(service: UUID, characteristic: UUID, onResult: (ByteArray) -> Unit) {
            opQueue.trySend {
                val p = currentPeripheral ?: return@trySend
                val ch = p.getCharacteristic(service, characteristic) ?: return@trySend

                val deferred = CompletableDeferred<ByteArray>()
                currentDeferred.set(deferred)

                p.readCharacteristic(service, characteristic)

                val value = try {
                    withTimeout(tuning.operationTimeoutMs) {
                        deferred.await()
                    }
                } catch (t: Throwable) {
                    LogManager.w(TAG, "Timeout waiting for read on $characteristic")
                    ByteArray(0) // fallback if timeout occurs
                }

                onResult(value)
            }
        }

        override fun disconnect() {
            currentPeripheral?.let { central.cancelConnection(it) }
        }

        override fun getPeripheral(): BluetoothPeripheral? = currentPeripheral

        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean {
            val p = currentPeripheral ?: return false
            return p.getCharacteristic(service, characteristic) != null
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------------------------------
    override fun doConnect(address: String, selectedUser: ScaleUser) {
        if (!::central.isInitialized) {
            central = BluetoothCentralManager(context, centralCallback, mainHandler)
        }

        val sinceLastDisconnect = SystemClock.elapsedRealtime() - lastDisconnectAtMs
        val waitMs = (tuning.common.reconnectCooldownMs - sinceLastDisconnect).coerceAtLeast(0)

        connectAttempts = 0
        _isConnected.value = false
        _isConnecting.value = true

        runCatching { central.stopScan() }

        scope.launch {
            if (waitMs > 0) delay(waitMs)
            try {
                central.scanForPeripheralsWithAddresses(arrayOf(address))
            } catch (e: Exception) {
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