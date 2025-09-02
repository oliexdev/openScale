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

import android.R.attr.type
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.ScaleCommunicator
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.*
import com.welie.blessed.WriteType.WITHOUT_RESPONSE
import com.welie.blessed.WriteType.WITH_RESPONSE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.Int

/**
 * Describes BLE link timing and retry behavior used by the adapter when talking to a scale.
 *
 * These values are mainly about *pacing* (small delays) and *robustness* (cooldowns/retries).
 * Some scales/firmware are sensitive to how quickly notifications are enabled or writes are
 * issued in sequence. Small gaps drastically reduce GATT 133 errors and write failures.
 *
 * NOTE: This adapter currently uses internal defaults (see GAP_* and retry constants below).
 * The [BleTuning] type is provided to make the intent explicit and to allow future wiring
 * so device handlers can request different timings if needed.
 */
data class BleTuning(
    // I/O pacing (ms)
    val notifySetupDelayMs: Long,
    val writeWithResponseDelayMs: Long,
    val writeWithoutResponseDelayMs: Long,
    val postWriteDelayMs: Long,
    // Connect/retry policy
    val reconnectCooldownMs: Long,
    val retryBackoffMs: Long,
    val maxRetries: Int,
    val connectAfterScanDelayMs: Long,
    // Link behavior hints
    val requestHighConnectionPriority: Boolean,
    val requestMtuBytes: Int
)

/**
 * Suggested presets that tend to work across vendors:
 *
 * - Balanced: good default for most scales.
 * - Conservative: more gaps, helpful for flaky radios/older Androids.
 * - Aggressive: tighter timings, for fast/science-mode devices.
 *
 *  NOTE: The adapter consumes values from bleTuning (or Balanced profile if null).
 */
sealed class BleTuningProfile {
    object Balanced : BleTuningProfile()
    object Conservative : BleTuningProfile()
    object Aggressive : BleTuningProfile()
}

/** Convert a preset into concrete numbers. */
fun BleTuningProfile.asTuning(): BleTuning = when (this) {
    BleTuningProfile.Balanced -> BleTuning(
        notifySetupDelayMs = 120,
        writeWithResponseDelayMs = 80,
        writeWithoutResponseDelayMs = 35,
        postWriteDelayMs = 20,
        reconnectCooldownMs = 2200,
        retryBackoffMs = 1500,
        maxRetries = 3,
        connectAfterScanDelayMs = 650,
        requestHighConnectionPriority = true,
        requestMtuBytes = 185
    )
    BleTuningProfile.Conservative -> BleTuning(
        notifySetupDelayMs = 160,
        writeWithResponseDelayMs = 100,
        writeWithoutResponseDelayMs = 50,
        postWriteDelayMs = 30,
        reconnectCooldownMs = 2500,
        retryBackoffMs = 1800,
        maxRetries = 3,
        connectAfterScanDelayMs = 800,
        requestHighConnectionPriority = true,
        requestMtuBytes = 0
    )
    BleTuningProfile.Aggressive -> BleTuning(
        notifySetupDelayMs = 80,
        writeWithResponseDelayMs = 60,
        writeWithoutResponseDelayMs = 25,
        postWriteDelayMs = 15,
        reconnectCooldownMs = 1200,
        retryBackoffMs = 1200,
        maxRetries = 2,
        connectAfterScanDelayMs = 400,
        requestHighConnectionPriority = true,
        requestMtuBytes = 247
    )
}

class FacadeDriverSettings(
    private val facade: SettingsFacade,
    private val scope: CoroutineScope,
    deviceAddress: String,
    handlerNamespace: String
) : ScaleDeviceHandler.DriverSettings {

    private val prefix = "ble/$handlerNamespace/$deviceAddress/"

    private val mem = ConcurrentHashMap<String, String>()

    override fun getInt(key: String, default: Int): Int {
        val k = prefix + key
        mem[k]?.toIntOrNull()?.let { return it }

        val v: Int = runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeout(300) { facade.observeSetting(k, default).first() }
            }
        }.getOrElse { default }

        mem[k] = v.toString()
        return v
    }

    override fun putInt(key: String, value: Int) {
        val k = prefix + key
        mem[k] = value.toString()
        scope.launch { facade.saveSetting(k, value) }
    }

    override fun getString(key: String, default: String?): String? {
        val k = prefix + key
        mem[k]?.let { return it }

        val raw: String = runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeout(300) { facade.observeSetting(k, default ?: "").first() }
            }
        }.getOrElse { default ?: "" }

        val result = if (raw.isEmpty() && default == null) null else raw
        result?.let { mem[k] = it }
        return result
    }

    override fun putString(key: String, value: String) {
        val k = prefix + key
        mem[k] = value
        scope.launch { facade.saveSetting(k, value) }
    }

    override fun remove(key: String) {
        val k = prefix + key
        mem.remove(k)
        scope.launch { facade.saveSetting(k, "") }
    }
}

/**
 * ModernScaleAdapter
 * ------------------
 * Bridges a device-specific [ScaleDeviceHandler] to the Blessed BLE stack. It owns scan/connect
 * lifecycle, handles service discovery and IO pacing, and surfaces events to the app via
 * [BluetoothEvent].
 *
 * This version additionally supports **broadcast-only** scales (no GATT, advertisement parsing).
 * For those, the adapter scans, forwards advertisements to the handler, and emits
 * `BluetoothEvent.Listening` / `BluetoothEvent.BroadcastComplete` around the flow.
 */
class ModernScaleAdapter(
    private val context: Context,
    private val settingsFacade: SettingsFacade,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
    private val handler: ScaleDeviceHandler,
    private val bleTuning: BleTuning? = null
) : ScaleCommunicator {

    private val TAG = "ModernScaleAdapter"

    // Active timing profile (defaults to Balanced)
    private val tuning: BleTuning = bleTuning ?: BleTuningProfile.Balanced.asTuning()
    private var broadcastAttached = false

    // Coroutine + Android handlers
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Blessed central manager (lazy)
    private lateinit var central: BluetoothCentralManager

    // Session state
    private var targetAddress: String? = null
    private var currentPeripheral: BluetoothPeripheral? = null

    @Volatile private lateinit var selectedUserSnapshot: ScaleUser
    @Volatile private var usersSnapshot: List<ScaleUser> = emptyList()
    @Volatile private var lastSnapshot: Map<Int, ScaleMeasurement> = emptyMap()

    // Session tracking for log correlation
    private var sessionCounter = 0
    private var sessionId = 0
    private fun newSession() {
        sessionId = ++sessionCounter
        LogManager.d(TAG, "session#$sessionId start for $targetAddress")
    }

    // IO pacing (small gaps reduce GATT 133 and write failures)
    private val ioMutex = Mutex()
    private suspend fun ioGap(ms: Long) { if (ms > 0) delay(ms) }
    private val GAP_BEFORE_NOTIFY = tuning.notifySetupDelayMs
    private val GAP_BEFORE_WRITE_WITH_RESP = tuning.writeWithResponseDelayMs
    private val GAP_BEFORE_WRITE_NO_RESP = tuning.writeWithoutResponseDelayMs
    private val GAP_AFTER_WRITE = tuning.postWriteDelayMs

    // Reconnect smoothing / retry
    private var lastDisconnectAtMs: Long = 0L
    private var connectAttempts: Int = 0
    private val RECONNECT_COOLDOWN_MS = tuning.reconnectCooldownMs
    private val RETRY_BACKOFF_MS = tuning.retryBackoffMs
    private val MAX_RETRY = tuning.maxRetries

    private val connectAfterScanDelayMs = tuning.connectAfterScanDelayMs

    // Public streams exposed to the app
    private val _events = MutableSharedFlow<BluetoothEvent>(replay = 1, extraBufferCapacity = 8)
    override fun getEventsFlow(): SharedFlow<BluetoothEvent> = _events.asSharedFlow()

    private val _isConnecting = MutableStateFlow(false)
    override val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        scope.launch {
            userFacade.observeSelectedUser().collect { u ->
                if (u != null) {
                    selectedUserSnapshot = mapUser(u)
                }
            }
        }

        scope.launch {
            userFacade.observeAllUsers()
                .flatMapLatest { users ->
                    usersSnapshot = users.map(::mapUser)

                    if (users.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(users.map { u -> measurementFacade.getMeasurementsForUser(u.id) }) { lists ->
                            val out = HashMap<Int, ScaleMeasurement>(users.size)
                            users.forEachIndexed { idx, u ->
                                val newest = lists[idx].maxByOrNull { it.measurement.timestamp }
                                mapMeasurement(newest)?.let { out[u.id] = it }
                            }
                            out
                        }
                    }
                }
                .collect { latestMap -> lastSnapshot = latestMap }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Blessed callbacks: central / peripheral
    // --------------------------------------------------------------------------------------------

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(
            peripheral: BluetoothPeripheral,
            scanResult: android.bluetooth.le.ScanResult
        ) {
            // Only react to the target address
            if (peripheral.address != targetAddress) return

            // Decide link mode using handler.supportFor(...) + scan data; fallback to handler.linkMode
            val linkMode = resolveLinkModeFor(peripheral, scanResult)
            val isBroadcast = linkMode != LinkMode.CONNECT_GATT

            if (isBroadcast) {
                // Broadcast path: attach handler lazily on first matching advertisement
                if (!broadcastAttached) {
                    val driverSettings = FacadeDriverSettings(
                        facade = settingsFacade,
                        scope = scope,
                        deviceAddress = peripheral.address,
                        handlerNamespace = handler::class.simpleName ?: "Handler"
                    )
                    handler.attach(noopTransport, appCallbacks, driverSettings, dataProvider)
                    broadcastAttached = true
                    // Now we know it's a broadcast flow → inform UI
                    _events.tryEmit(BluetoothEvent.Listening(peripheral.address))
                }

                // Forward the advertisement to the handler for parsing/aggregation
                val user = selectedUserSnapshot
                when (handler.onAdvertisement(scanResult, user)) {
                    BroadcastAction.IGNORED -> Unit

                    BroadcastAction.CONSUMED_KEEP_SCANNING -> {
                        _events.tryEmit(
                            BluetoothEvent.DeviceMessage(
                                context.getString(R.string.bt_info_waiting_for_measurement),
                                peripheral.address
                            )
                        )
                    }

                    BroadcastAction.CONSUMED_STOP -> {
                        _events.tryEmit(BluetoothEvent.BroadcastComplete(peripheral.address))
                        stopScanInternal()
                        cleanup(peripheral.address)
                        broadcastAttached = false
                    }
                }
                return
            }

            // GATT path: stop scan and connect to the peripheral
            LogManager.i(TAG, "session#$sessionId Found $targetAddress → stop scan + connect")
            central.stopScan()
            scope.launch {
                if (connectAfterScanDelayMs > 0) delay(connectAfterScanDelayMs)
                central.connectPeripheral(peripheral, peripheralCallback)
            }
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            scope.launch {
                LogManager.i(TAG, "session#$sessionId Connected ${peripheral.name ?: "Unknown"} (${peripheral.address})")
                currentPeripheral = peripheral
                _isConnected.value = true
                _isConnecting.value = false
                _events.tryEmit(BluetoothEvent.Connected(peripheral.name ?: "Unknown", peripheral.address))
            }
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            scope.launch {
                LogManager.e(TAG, "session#$sessionId Connection failed ${peripheral.address}: $status")
                if (connectAttempts < MAX_RETRY) {
                    val nextTry = connectAttempts + 1
                    _events.tryEmit(
                        BluetoothEvent.DeviceMessage(
                            context.getString(R.string.bt_info_reconnecting_try, nextTry, MAX_RETRY),
                            peripheral.address
                        )
                    )
                    connectAttempts = nextTry
                    LogManager.w(TAG, "session#$sessionId Retry #$nextTry in ${RETRY_BACKOFF_MS}ms…")
                    delay(RETRY_BACKOFF_MS)

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
                LogManager.i(TAG, "session#$sessionId Disconnected ${peripheral.address}: $status")
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
            LogManager.d(TAG, "session#$sessionId Services discovered for ${peripheral.address}")
            currentPeripheral = peripheral // ensure available to the handler

            if (tuning.requestHighConnectionPriority) runCatching { peripheral.requestConnectionPriority(ConnectionPriority.HIGH) }
            if (tuning.requestMtuBytes > 23) {
                runCatching { peripheral.requestMtu(tuning.requestMtuBytes) }
            }
            LogManager.d(TAG, "session#$sessionId Link params: highPrio=${tuning.requestHighConnectionPriority}, mtu=${tuning.requestMtuBytes}")

            val user = if (::selectedUserSnapshot.isInitialized) {
                selectedUserSnapshot
            } else {
                LogManager.e(TAG, "no user selected before services discovered")
                central.cancelConnection(peripheral)
                return
            }

            val driverSettings = FacadeDriverSettings(
                facade = settingsFacade,
                scope = scope,
                deviceAddress = peripheral.address,
                handlerNamespace = handler::class.simpleName ?: "Handler"
            )

            // From here the handler drives protocol: enable NOTIFY, write commands, parse frames.
            handler.attach(transportImpl, appCallbacks, driverSettings, dataProvider)
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
                LogManager.w(TAG, "session#$sessionId Notify error ${characteristic.uuid}: $status")
            }
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            // Only warn on failure; success is noisy if logged for every packet
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
            if (status == GattStatus.SUCCESS) {
                LogManager.d(TAG, "session#$sessionId Notify ${characteristic.uuid} -> ${peripheral.isNotifying(characteristic)}")
            } else {
                appCallbacks.onWarn(
                    R.string.bt_warn_notify_state_failed_status,
                    characteristic.uuid.toString(),
                    status.toString()
                )
            }
        }

        override fun onMtuChanged(peripheral: BluetoothPeripheral, mtu: Int, status: GattStatus) {
            LogManager.d(TAG, "session#$sessionId MTU changed to $mtu (status=$status)")
        }
    }

    // --------------------------------------------------------------------------------------------
    // Transport for handlers
    // --------------------------------------------------------------------------------------------

    // Full transport used by GATT devices
    private val transportImpl = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            scope.launch {
                ioMutex.withLock {
                    val p = currentPeripheral
                    if (p == null) {
                        appCallbacks.onWarn(R.string.bt_warn_no_peripheral_for_setnotify, characteristic.toString())
                        return@withLock
                    }
                    val ch = p.getCharacteristic(service, characteristic)
                    if (ch == null) {
                        appCallbacks.onWarn(R.string.bt_warn_characteristic_not_found, characteristic.toString())
                        return@withLock
                    }
                    LogManager.d(TAG, "session#$sessionId Enable NOTIFY for $characteristic (props=${propsPretty(ch.properties)})")
                    ioGap(GAP_BEFORE_NOTIFY)
                    if (!p.setNotify(ch, true)) {
                        appCallbacks.onWarn(R.string.bt_warn_notify_failed, characteristic.toString())
                    }
                }
            }
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            scope.launch {
                ioMutex.withLock {
                    val p = currentPeripheral
                    if (p == null) {
                        appCallbacks.onWarn(R.string.bt_warn_no_peripheral_for_write, characteristic.toString())
                        return@withLock
                    }
                    val ch = p.getCharacteristic(service, characteristic)
                    val type = if (withResponse) WITH_RESPONSE else WITHOUT_RESPONSE
                    if (ch == null) {
                        appCallbacks.onWarn(R.string.bt_warn_characteristic_not_found, characteristic.toString())
                        return@withLock
                    }
                    LogManager.d(TAG, "session#$sessionId Write chr=$characteristic len=${payload.size} type=$type (props=${propsPretty(ch.properties)})")
                    ioGap(if (withResponse) GAP_BEFORE_WRITE_WITH_RESP else GAP_BEFORE_WRITE_NO_RESP)
                    p.writeCharacteristic(service, characteristic, payload, type)
                    ioGap(GAP_AFTER_WRITE)
                }
            }
        }

        override fun read(service: UUID, characteristic: UUID) {
            scope.launch {
                ioMutex.withLock {
                    val p = currentPeripheral
                    if (p == null) {
                        appCallbacks.onWarn(R.string.bt_warn_no_peripheral_for_read, characteristic.toString())
                        return@withLock
                    }
                    LogManager.d(TAG, "session#$sessionId Read chr=$characteristic")
                    p.readCharacteristic(service, characteristic)
                }
            }
        }

        override fun disconnect() {
            currentPeripheral?.let { central.cancelConnection(it) }
        }
    }

    private val dataProvider = object : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = selectedUserSnapshot
        override fun usersForDevice(): List<ScaleUser> = usersSnapshot
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = lastSnapshot[userId]
    }

    // No-op transport used for broadcast-only devices (no GATT operations)
    private val noopTransport = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) { /* no-op */ }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) { /* no-op */ }
        override fun read(service: UUID, characteristic: UUID) { /* no-op */ }
        override fun disconnect() { stopScanInternal() }
    }

    // --------------------------------------------------------------------------------------------
    // App callbacks
    // --------------------------------------------------------------------------------------------

    private val appCallbacks = object : ScaleDeviceHandler.Callbacks {
        override fun onPublish(measurement: ScaleMeasurement) {
            val addr = currentPeripheral?.address ?: targetAddress ?: "unknown"
            _events.tryEmit(BluetoothEvent.MeasurementReceived(measurement, addr))
        }

        override fun onInfo(@StringRes resId: Int, vararg args: Any) {
            val addr = currentPeripheral?.address ?: targetAddress ?: "unknown"
            val msg = context.getString(resId, *args)
            _events.tryEmit(BluetoothEvent.DeviceMessage(msg, addr))
        }

        override fun onWarn(@StringRes resId: Int, vararg args: Any) {
            val addr = currentPeripheral?.address ?: targetAddress ?: "unknown"
            val msg = context.getString(resId, *args)
            _events.tryEmit(BluetoothEvent.DeviceMessage(msg, addr))
        }

        override fun onError(@StringRes resId: Int, t: Throwable?, vararg args: Any) {
            val addr = currentPeripheral?.address ?: targetAddress ?: "unknown"
            val msg = context.getString(resId, *args)
            LogManager.e(TAG, msg, t)
            _events.tryEmit(BluetoothEvent.DeviceMessage(msg, addr))
        }

        override fun onUserInteractionRequired(
            interactionType: UserInteractionType,
            data: Any?
        ) {
            val addr = currentPeripheral?.address ?: targetAddress ?: "unknown"
            _events.tryEmit(BluetoothEvent.UserInteractionRequired(addr, data, interactionType))
        }

        override fun resolveString(@StringRes resId: Int, vararg args: Any): String =
            context.getString(resId, *args)
    }

    // --------------------------------------------------------------------------------------------
    // ScaleCommunicator API
    // --------------------------------------------------------------------------------------------

    /**
     * Start a BLE session to the given MAC and bind the session to [scaleUser].
     * The adapter starts scanning; for broadcast-only devices it will lazily attach the handler
     * on the first matching advertisement and emit a Listening event at that moment.
     */
    override fun connect(address: String, scaleUser: ScaleUser?) {
        scope.launch {
            if (!ensureSelectedUserSnapshot()) {
                _events.tryEmit(
                    BluetoothEvent.ConnectionFailed(
                        address,
                        context.getString(R.string.bt_error_no_user_selected)
                    )
                )
                return@launch
            }

            if (targetAddress == address && (_isConnecting.value || _isConnected.value)) {
                LogManager.d(TAG, "session#$sessionId connect($address) ignored: already connecting/connected")
                return@launch
            }
            ensureCentral()

            // Cooldown between reconnects to avoid Android stack churn
            val since = SystemClock.elapsedRealtime() - lastDisconnectAtMs
            if (since in 1 until RECONNECT_COOLDOWN_MS) {
                val wait = RECONNECT_COOLDOWN_MS - since
                LogManager.d(TAG, "Cooldown ${wait}ms before reconnect to $address")
                delay(wait)
            }

            // If currently busy with another device, disconnect first
            if ((_isConnected.value || _isConnecting.value) && targetAddress != address) {
                disconnect()
            }

            // Initialize session state
            targetAddress = address
            connectAttempts = 0
            _isConnecting.value = true
            _isConnected.value = false
            newSession()

            // Always start by scanning; final link mode decision happens in onDiscoveredPeripheral(...)
            runCatching { central.stopScan() }
            try {
                central.scanForPeripheralsWithAddresses(arrayOf(address))
            } catch (e: Exception) {
                LogManager.e(TAG, "session#$sessionId Failed to start scan/connect: ${e.message}", e)
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



    /** Gracefully terminate the current BLE session (if any). */
    override fun disconnect() {
        scope.launch {
            currentPeripheral?.let { central.cancelConnection(it) }
            stopScanInternal()
            cleanup(targetAddress)
        }
    }

    /** Some scales only ever push via NOTIFY; we surface a helpful user message. */
    override fun requestMeasurement() {
        val addr = targetAddress ?: "unknown"
        _events.tryEmit(
            BluetoothEvent.DeviceMessage(
                context.getString(R.string.bt_info_waiting_for_measurement),
                addr
            )
        )
    }

    /** Reserved for UI round-trips (rare for scales); currently unused. */
    override fun processUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any,
        uiHandler: Handler
    ) {
        scope.launch {
            runCatching {
                handler.onUserInteractionFeedback(interactionType, appUserId, feedbackData, uiHandler)
            }.onFailure { t ->
                val addr = currentPeripheral?.address ?: targetAddress ?: "unknown"
                LogManager.e(TAG, "Delivering user feedback failed: ${t.message}", t)
                _events.tryEmit(
                    BluetoothEvent.DeviceMessage(
                        context.getString(R.string.bt_error_delivery_user_feedback, t.message ?: "—"),
                        addr
                    )
                )
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------

    private fun ensureCentral() {
        if (!::central.isInitialized) {
            central = BluetoothCentralManager(context, centralCallback, mainHandler)
        }
    }

    private fun stopScanInternal() {
        runCatching { if (::central.isInitialized) central.stopScan() }
    }

    private fun cleanup(addr: String?) {
        _isConnected.value = false
        _isConnecting.value = false
        currentPeripheral = null
        broadcastAttached = false
        // Keep targetAddress/currentUser for optional retry
    }

    /** Free resources; should be called when the adapter is no longer needed. */
    fun release() {
        disconnect()
        scope.cancel()
        runCatching { if (::central.isInitialized) central.close() }
    }

    /** Turn a GATT property bitfield into a human-readable string for logs. */
    private fun propsPretty(props: Int): String {
        val names = mutableListOf<String>()
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ) != 0) names += "READ"
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) names += "WRITE"
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) names += "WRITE_NO_RESP"
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) names += "NOTIFY"
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) names += "INDICATE"
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) names += "SIGNED_WRITE"
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) names += "BROADCAST"
        if ((props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) names += "EXTENDED_PROPS"
        return if (names.isEmpty()) props.toString() else names.joinToString("|")
    }


    private suspend fun ensureSelectedUserSnapshot(): Boolean {
        if (::selectedUserSnapshot.isInitialized) return true

        val u0 = userFacade.observeSelectedUser().first()
        if (u0 != null) {
            selectedUserSnapshot = mapUser(u0)
            return true
        }

        runCatching { userFacade.restoreOrSelectDefaultUser() }.onFailure { return false }

        val u1 = userFacade.observeSelectedUser().first()
        return if (u1 != null) {
            selectedUserSnapshot = mapUser(u1)
            true
        } else {
            false
        }
    }

    private fun mapUser(u: User): ScaleUser =
        ScaleUser().apply {
            runCatching { setId(u.id) }
            runCatching { setUserName(u.name) }
            when (val b = runCatching { u.birthDate }.getOrNull()) {
                is Date -> setBirthday(b)
                is Long -> setBirthday(Date(b))
            }
            runCatching { setBodyHeight(u.heightCm) }
            runCatching { setGender(u.gender) }
            runCatching { setActivityLevel(u.activityLevel) }
        }

    private fun mapMeasurement(
        mwv: MeasurementWithValues?
    ): ScaleMeasurement? {
        if (mwv == null) return null

        val m = ScaleMeasurement()

        runCatching { m.setId(mwv.measurement.id) }
        runCatching { m.setUserId(mwv.measurement.userId) }
        runCatching { m.setDateTime(Date(mwv.measurement.timestamp)) }

        // Helper to pull a float by key from the value list
        fun valueOf(key: MeasurementTypeKey): MeasurementValue? {
            val v = mwv.values.firstOrNull { it.type.key == key } ?: return null
            return v.value
        }

        valueOf(MeasurementTypeKey.WEIGHT)?.let { m.setWeight(it.floatValue ?: 0f) }
        valueOf(MeasurementTypeKey.BODY_FAT)?.let { m.setFat(it.floatValue ?: 0f) }
        valueOf(MeasurementTypeKey.WATER)?.let { m.setWater(it.floatValue ?: 0f) }
        valueOf(MeasurementTypeKey.MUSCLE)?.let { m.setMuscle(it.floatValue ?: 0f) }
        valueOf(MeasurementTypeKey.VISCERAL_FAT)?.let { m.setVisceralFat(it.floatValue ?: 0f) }
        valueOf(MeasurementTypeKey.LBM)?.let { m.setLbm(it.floatValue ?: 0f) }
        valueOf(MeasurementTypeKey.BONE)?.let { m.setBone(it.floatValue ?: 0f) }

        return m
    }

    // --------------------------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------------------------

    /**
     * Decide link mode for a discovered peripheral. Uses handler.supportFor() if
     * the handler exposes it for this device, falling back to handler.linkMode.
     */
    private fun resolveLinkModeFor(
        peripheral: BluetoothPeripheral,
        scanResult: android.bluetooth.le.ScanResult?
    ): LinkMode {
        val record = scanResult?.scanRecord
        val info = ScannedDeviceInfo(
            name = peripheral.name,
            address = peripheral.address,
            rssi = scanResult?.rssi ?: 0,
            serviceUuids = record?.serviceUuids?.map { it.uuid } ?: emptyList(),
            manufacturerData = record?.manufacturerSpecificData,
            isSupported = false,
            determinedHandlerDisplayName = null
        )
        val support = runCatching { handler.supportFor(info) }.getOrNull()
        return support?.linkMode ?: handler.linkMode
    }
}
