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

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

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
    profile: TuningProfile = TuningProfile.Balanced,
    private val central: BluetoothCentralManager
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    private val tuning: BleGattTuning = profile.forGatt()
    private var currentPeripheral: BluetoothPeripheral? = null

    private val opQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private data class PendingOp(
        val id: Long,
        val deferred: CompletableDeferred<Unit>
    )

    private val deferredMap = ConcurrentHashMap<UUID, PendingOp>()
    private var nextOpId = 0L

    private val ioMutex = Mutex()
    private var connectAttempts = 0

    // MTU synchronization for Android 17 (API 37)
    private var mtuDeferred = CompletableDeferred<Int>()

    init {
        // Worker coroutine processes queued BLE operations sequentially
        scope.launch {
            for (op in opQueue) {
                // wait until BLE connection is established
                while (!_isConnected.value) {
                    delay(10.milliseconds)
                }

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

    @Composable
    override fun DeviceConfigurationUi() {
        // Delegate to the actual protocol handler
        handler.DeviceConfigurationUi()
    }

    private suspend fun ioGap(ms: Long) {
        if (ms > 0) delay(ms.milliseconds)
    }

    // -------------------------------------------------------------------------------------------------
    // System-level events routed from BluetoothFacade
    // -------------------------------------------------------------------------------------------------
    fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
        if (peripheral.address != targetAddress) return
        LogManager.i(TAG, "Found $targetAddress → stop scan + connect")
        central.stopScan()
        scope.launch {
            if (tuning.connectAfterScanDelayMs > 0) delay(tuning.connectAfterScanDelayMs.milliseconds)
            central.connect(peripheral, peripheralCallback)
        }
    }

    fun onConnected(peripheral: BluetoothPeripheral) {
        if (peripheral.address != targetAddress) return
        scope.launch {
            currentPeripheral = peripheral
            _isConnected.value = true
            _isConnecting.value = false
            _events.tryEmit(BluetoothEvent.Connected(peripheral.name, peripheral.address))
        }
    }

    fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
        if (peripheral.address != targetAddress) return
        scope.launch {
            LogManager.e(TAG, "Connection failed ${peripheral.address}: $status")
            
            // Android 17: Fatal GATT table failures
            val statusInt = status.value
            if (statusInt == 133 || statusInt == 257) {
                LogManager.e(TAG, "Fatal GATT failure ($statusInt). Enforcing 5s system cooldown.")
                _events.tryEmit(BluetoothEvent.ConnectionFailed(peripheral.address, "System Bluetooth Exhausted (Cooldown 5s)"))
                cleanup()
                delay(5000.milliseconds) // Mandatory wait to allow Android 17 table to clear
                return@launch
            }

            if (connectAttempts < tuning.common.maxRetries) {
                val nextTry = connectAttempts + 1
                _events.tryEmit(
                    BluetoothEvent.DeviceMessage(
                        context.getString(R.string.bt_info_reconnecting_try, nextTry, tuning.common.maxRetries),
                        peripheral.address
                    )
                )
                connectAttempts = nextTry
                delay(tuning.common.retryBackoffMs.milliseconds)
                runCatching { central.stopScan() }
                central.scanForPeripheralsWithAddresses(setOf(peripheral.address))
                _isConnecting.value = true
            } else {
                _events.tryEmit(BluetoothEvent.ConnectionFailed(peripheral.address, status.toString()))
                cleanup()
            }
        }
    }

    fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
        if (peripheral.address != targetAddress) return
        scope.launch {
            LogManager.i(TAG, "Disconnected ${peripheral.address}: $status")
            
            // Handle fatal status during disconnect/operation
            val statusInt = status.value
            if (statusInt == 133 || statusInt == 257) {
                LogManager.e(TAG, "Fatal GATT failure during operation ($statusInt). Enforcing 5s system cooldown.")
                delay(5000.milliseconds)
            }

            // Immediately fail-fast any pending operations in the queue
            // to prevent hitting Android 17 status=257 for attempting to write to a closed client.
            ioMutex.lock()
            try {
                mtuDeferred.completeExceptionally(IOException("Scale disconnected during MTU negotiation"))
                deferredMap.values.forEach { pending ->
                    pending.deferred.completeExceptionally(IOException("Scale disconnected: ${status.name}"))
                }
                deferredMap.clear()
            } finally {
                ioMutex.unlock()
            }

            runCatching { handler.handleDisconnected() }
            runCatching { handler.detach() }
            lastDisconnectAtMs = SystemClock.elapsedRealtime()
            if (peripheral.address == targetAddress) {
                _events.tryEmit(BluetoothEvent.Disconnected(peripheral.address, status.toString()))
                cleanup()
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

            scope.launch {
                // Android 17: Stability delay after discovery
                if (tuning.postDiscoverDelayMs > 0) delay(tuning.postDiscoverDelayMs.milliseconds)

                if (tuning.requestHighConnectionPriority) runCatching { peripheral.requestConnectionPriority(ConnectionPriority.HIGH) }
                
                // Android 17: Explicitly wait for MTU before starting data I/O
                if (tuning.requestMtuBytes > 23) {
                    LogManager.d(TAG, "Requesting MTU ${tuning.requestMtuBytes} and waiting for negotiation...")
                    mtuDeferred = CompletableDeferred()
                    runCatching { peripheral.requestMtu(tuning.requestMtuBytes) }
                    
                    try {
                        withTimeout(2500.milliseconds) {
                            val mtu = mtuDeferred.await()
                            LogManager.i(TAG, "MTU negotiation complete: $mtu bytes")
                        }
                    } catch (e: Exception) {
                        LogManager.w(TAG, "MTU negotiation timed out or failed. Link may be unstable.")
                    }
                }

                val user = selectedUserSnapshot ?: run {
                    central.cancelConnection(peripheral); return@launch
                }

                val driverSettings = FacadeDriverSettings(
                    facade = settingsFacade,
                    scope = scope,
                    handlerNamespace = handler::class.simpleName ?: "Handler"
                )

                handler.attach(transport, appCallbacks, driverSettings, dataProvider, scope)
                handler.handleConnected(user)
            }
        }

        override fun onMtuChanged(peripheral: BluetoothPeripheral, mtu: Int, status: GattStatus) {
            LogManager.d(TAG, "onMtuChanged: status=$status, mtu=$mtu")
            mtuDeferred.complete(mtu)
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            LogManager.d(TAG,"\u2190 write response chr=${characteristic.uuid} len=${value.size} status=${status} ${value.toHexPreview(24)}")

            deferredMap[characteristic.uuid]?.let { op ->
                op.deferred.complete(Unit)
                deferredMap.remove(characteristic.uuid)
            }
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            LogManager.d(TAG,"\u2190 notify state chr=${characteristic.uuid} status=${status}")

            deferredMap[characteristic.uuid]?.let { op ->
                op.deferred.complete(Unit)
                deferredMap.remove(characteristic.uuid)
            }
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            LogManager.d(TAG,"\u2190 received data chr=${characteristic.uuid} len=${value.size} status=${status} ${value.toHexPreview(24)}")

            handler.handleNotification(characteristic.uuid, value)

            deferredMap[characteristic.uuid]?.let { op ->
                op.deferred.complete(Unit)
                deferredMap.remove(characteristic.uuid)
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Transport exposed to handler; operations are queued automatically
    // -------------------------------------------------------------------------------------------------
    private val transport = object : ScaleDeviceHandler.Transport {

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            opQueue.trySend {
                val p = currentPeripheral ?: return@trySend
                LogManager.d(TAG, "→ set notify on chr=$characteristic svc=$service")

                val opId = ++nextOpId
                val deferred = CompletableDeferred<Unit>()
                deferredMap[characteristic] = PendingOp(opId, deferred)

                val started = p.startNotify(service, characteristic)
                if (!started) {
                    LogManager.w(TAG, "Failed to initiate notify for $characteristic")
                   // appCallbacks.onWarn(R.string.bt_warn_notify_failed, characteristic.toString())
                    deferred.complete(Unit)
                    deferredMap.remove(characteristic)
                }

                try {
                    // Wait with timeout from tuning
                    withTimeout(tuning.operationTimeoutMs.milliseconds) {
                        deferred.await()
                    }
                } catch (_: TimeoutCancellationException) {
                    LogManager.w(TAG, "Timeout waiting for notify on $characteristic")
                } finally {
                    val current = deferredMap[characteristic]
                    if (current?.id == opId) {
                        deferredMap.remove(characteristic)
                    }
                    deferred.cancel()
                }

                ioGap(tuning.notifySetupDelayMs)
            }
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            opQueue.trySend {
                val p = currentPeripheral ?: return@trySend
                val ch = p.getCharacteristic(service, characteristic) ?: return@trySend

                val opId = ++nextOpId
                val deferred = CompletableDeferred<Unit>()
                deferredMap[characteristic] = PendingOp(opId, deferred)

                if (!withResponse && (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)) {
                    // WRITE_NO_RESPONSE not advertised by the peripheral, but the caller requires
                    // an unacknowledged write. Patch the in-memory property bitmask so the stack
                    // sends a WRITE_COMMAND instead of a WRITE_REQUEST (Android 17 enforces this).
                    LogManager.w(TAG, "Characteristic $characteristic: WRITE_NO_RESPONSE not advertised; patching properties for compatibility.")
                    try {
                        val field = BluetoothGattCharacteristic::class.java.getDeclaredField("mProperties")
                        field.isAccessible = true
                        field.setInt(ch, field.getInt(ch) or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                        LogManager.i(TAG, "Characteristic $characteristic: WRITE_NO_RESPONSE property patched successfully.")
                    } catch (e: Exception) {
                        LogManager.e(TAG, "Failed to patch characteristic $characteristic properties", e)
                    }
                }

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

                LogManager.d(TAG,"\u2192 write to chr=$characteristic svc=$service len=${payload.size} withResp=$withResponse ${payload.toHexPreview(24)}")

                try {
                    withTimeout(tuning.operationTimeoutMs.milliseconds) {
                        deferred.await()
                    }
                } catch (_: TimeoutCancellationException) {
                    LogManager.w(TAG, "Timeout waiting for write on $characteristic")
                } finally {
                    val current = deferredMap[characteristic]
                    if (current?.id == opId) {
                        deferredMap.remove(characteristic)
                    }
                    deferred.cancel()
                }

                ioGap(tuning.postWriteDelayMs)
            }
        }

        override fun read(service: UUID, characteristic: UUID) {
            opQueue.trySend {
                val p = currentPeripheral ?: return@trySend
                p.getCharacteristic(service, characteristic) ?: return@trySend

                val opId = ++nextOpId
                val deferred = CompletableDeferred<Unit>()
                deferredMap[characteristic] = PendingOp(opId, deferred)

                p.readCharacteristic(service, characteristic)

                LogManager.d(TAG,"\u2192 read from chr=$characteristic svc=$service")

                try {
                    withTimeout(tuning.operationTimeoutMs.milliseconds) {
                        deferred.await()
                    }
                } catch (_: TimeoutCancellationException) {
                    LogManager.w(TAG, "Timeout waiting for read on $characteristic")
                } finally {
                    val current = deferredMap[characteristic]
                    if (current?.id == opId) {
                        deferredMap.remove(characteristic)
                    }
                    deferred.cancel()
                }

                ioGap(tuning.postReadDelayMs)
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
        val sinceLastDisconnect = SystemClock.elapsedRealtime() - lastDisconnectAtMs
        val waitMs = (tuning.common.reconnectCooldownMs - sinceLastDisconnect).coerceAtLeast(0)

        connectAttempts = 0
        _isConnected.value = false
        _isConnecting.value = true

        runCatching { central.stopScan() }

        scope.launch {
            if (waitMs > 0) delay(waitMs.milliseconds)
            try {
                central.scanForPeripheralsWithAddresses(setOf(address))
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to start scan/connect: ${e.message}", e)
                _events.tryEmit(
                    BluetoothEvent.ConnectionFailed(
                        address,
                        e.message ?: context.getString(R.string.bt_error_generic)
                    )
                )
                cleanup()
            }
        }
    }

    override fun doDisconnect() {
        runCatching { central.stopScan() }
        currentPeripheral?.let { runCatching { central.cancelConnection(it) } }
        currentPeripheral = null
    }

    override fun close() {
        // Stop accepting new BLE operations
        runCatching { opQueue.close() }
        // Do NOT close centralManager as it is shared singleton!
        super.close()
    }
}
