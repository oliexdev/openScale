/*
 * openScale
 * Copyright (C) 2025 ...
 * GPLv3+
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
import com.health.openscale.core.facade.SettingsFacade
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * ## ModernScaleAdapter
 *
 * A small “bridge” that wires a device-specific [ScaleDeviceHandler] to the BLE stack
 * via the Blessed library. The adapter owns the Bluetooth session (scan → connect →
 * service discovery → notifications/writes) and exposes a clean, event-driven API to
 * the rest of the app through [ScaleCommunicator].
 *
 * ### What handler authors need to know
 *
 * - **You do NOT manage Bluetooth directly.** Implement a subclass of [ScaleDeviceHandler]
 *   and use its protected helpers:
 *   - `setNotifyOn(service, characteristic)` to subscribe.
 *   - `writeTo(service, characteristic, payload, withResponse)` to send commands.
 *   - `readFrom(service, characteristic)` if the device requires reads.
 *   - `publish(measurement)` when you have a complete result.
 *   - `userInfo("…")` to show human-readable messages (e.g., “Step on the scale…”).
 *
 * - **Lifecycle in your handler:**
 *   - `onConnected(user)` is called after services are discovered and the link is ready.
 *     Enable notifications here and send your init sequence (user/time/unit/etc.).
 *   - `onNotification(uuid, data, user)` is called for each incoming notify packet.
 *     Parse, update state, and call `publish()` for final results.
 *   - `onDisconnected()` is called for cleanup.
 *
 * - **Threading/timing is handled for you.** The adapter serializes BLE I/O with a `Mutex`
 *   and adds small delays between operations to avoid “GATT 133” and write errors.
 *   Don’t add your own blocking sleeps inside the handler; just call the helpers.
 *
 * - **Events to the app:** The adapter surfaces connection state and measurements through
 *   a `SharedFlow<BluetoothEvent>`. You don’t emit those directly—use the handler helpers.
 *
 * ### Responsibilities split
 * - Adapter: scans, connects, discovers services, sets MTU/priority, enforces pacing,
 *   funnels notifications/writes, and reports connection events.
 * - Handler: pure protocol logic for one vendor/model (frame formats, checksums, etc.).
 */
class ModernScaleAdapter(
    private val context: Context,
    private val settingsFacade: SettingsFacade,
    private val handler: ScaleDeviceHandler,
    private val bleTuning: BleTuning? = null
) : ScaleCommunicator {

    private val TAG = "ModernScaleAdapter"

    private val tuning: BleTuning = bleTuning ?: BleTuningProfile.Balanced.asTuning()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var central: BluetoothCentralManager

    private var targetAddress: String? = null
    private var currentPeripheral: BluetoothPeripheral? = null
    private var currentUser: ScaleUser? = null

    // Session tracking to correlate logs across reconnects.
    private var sessionCounter = 0
    private var sessionId = 0
    private fun newSession() {
        sessionId = ++sessionCounter
        LogManager.d(TAG, "session#$sessionId start for $targetAddress")
    }

    // --- I/O pacing (currently internal defaults; see BleTuning above) --------
    private val ioMutex = Mutex()
    private suspend fun ioGap(ms: Long) { if (ms > 0) delay(ms) }
    private val GAP_BEFORE_NOTIFY = tuning.notifySetupDelayMs
    private val GAP_BEFORE_WRITE_WITH_RESP = tuning.writeWithResponseDelayMs
    private val GAP_BEFORE_WRITE_NO_RESP = tuning.writeWithoutResponseDelayMs
    private val GAP_AFTER_WRITE = tuning.postWriteDelayMs

    // --- Reconnect smoothing / retry (internal defaults) ----------------------
    private var lastDisconnectAtMs: Long = 0L
    private var connectAttempts: Int = 0
    private val RECONNECT_COOLDOWN_MS = tuning.reconnectCooldownMs
    private val RETRY_BACKOFF_MS = tuning.retryBackoffMs
    private val MAX_RETRY = tuning.maxRetries

    private val connectAfterScanDelayMs = tuning.connectAfterScanDelayMs

    // --- Public streams exposed to the app -----------------------------------
    private val _events = MutableSharedFlow<BluetoothEvent>(replay = 1, extraBufferCapacity = 8)
    override fun getEventsFlow(): SharedFlow<BluetoothEvent> = _events.asSharedFlow()

    private val _isConnecting = MutableStateFlow(false)
    override val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // ---- Blessed callbacks: discovery / link state ---------------------------

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: android.bluetooth.le.ScanResult) {
            if (peripheral.address == targetAddress) {
                LogManager.i(TAG, "session#$sessionId Found $targetAddress → stop scan + connect")
                central.stopScan()
                scope.launch {
                    if (connectAfterScanDelayMs > 0) delay(connectAfterScanDelayMs)
                    central.connectPeripheral(peripheral, peripheralCallback)
                }
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

            val user = currentUser ?: run {
                LogManager.e(TAG, "session#$sessionId No user set before services discovered")
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
            handler.attach(transportImpl, appCallbacks, driverSettings)
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
            if (status == GattStatus.SUCCESS) {
                LogManager.d(TAG, "session#$sessionId Write OK ${characteristic.uuid}")
            } else {
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

    // ---- Transport given to ScaleDeviceHandler (serialized + paced) ----------

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
                    if (ch == null) {
                        appCallbacks.onWarn(R.string.bt_warn_characteristic_not_found, characteristic.toString())
                        return@withLock
                    }
                    val type = if (withResponse) WITH_RESPONSE else WITHOUT_RESPONSE
                    LogManager.d(TAG, "session#$sessionId Write chr=$characteristic len=${payload.size} type=$type (props=${propsPretty(ch?.properties ?: 0)})")
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

    // ---- App callbacks handed to ScaleDeviceHandler --------------------------

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

    // ---- ScaleCommunicator API (used by the app layer) -----------------------

    /**
     * Start a BLE session to the given MAC and bind the session to [scaleUser].
     * The adapter scans for the exact address, connects when found, and then
     * delegates protocol work to the bound [ScaleDeviceHandler].
     */
    override fun connect(address: String, scaleUser: ScaleUser?) {
        scope.launch {
            if (scaleUser == null) {
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

            // Cool down between reconnects to avoid Android stack churn.
            val since = SystemClock.elapsedRealtime() - lastDisconnectAtMs
            if (since in 1 until RECONNECT_COOLDOWN_MS) {
                val wait = RECONNECT_COOLDOWN_MS - since
                LogManager.d(TAG, "Cooldown ${wait}ms before reconnect to $address")
                delay(wait)
            }

            if ((_isConnected.value || _isConnecting.value) && targetAddress != address) {
                disconnect()
            }

            targetAddress = address
            currentUser = scaleUser
            connectAttempts = 0
            _isConnecting.value = true
            _isConnected.value = false
            newSession()

            try { central.stopScan() } catch (_: Exception) { /* ignore */ }
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

    // ---- Internals -----------------------------------------------------------

    private fun ensureCentral() {
        if (!::central.isInitialized) {
            central = BluetoothCentralManager(context, centralCallback, mainHandler)
        }
    }

    private fun cleanup(addr: String?) {
        _isConnected.value = false
        _isConnecting.value = false
        currentPeripheral = null
        // keep targetAddress/currentUser for optional retry
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
}

/* ---------------------------------------------------------------------------
   Handler Quick-Start (for implementers)
   --------------------------------------
   1) Create a new file: `FooDeviceHandler.kt` extending ScaleDeviceHandler.
   2) Implement `supportFor(device)` to detect your device by name/advertising.
   3) In `onConnected(user)`, enable notifications and send your init sequence.
   4) In `onNotification(uuid, data, user)`, parse frames and call `publish()`.
   5) Optionally call `userInfo("Step on the scale…")` for UI messages.

   Example skeleton:

   class FooDeviceHandler : ScaleDeviceHandler() {
       override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
           val name = device.name ?: return null
           return if (name.startsWith("FOO-SCALE")) {
               DeviceSupport(
                   displayName = "Foo Scale",
                   capabilities = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC),
                   implemented  = setOf(DeviceCapability.BODY_COMPOSITION)
               )
           } else null
       }

       override fun onConnected(user: ScaleUser) {
           // Enable NOTIFY on your measurement characteristic
           val svc = uuid16(0xFFE0)
           val chr = uuid16(0xFFE4)
           setNotifyOn(svc, chr)

           // Send any init / user / time commands the device expects
           val ctrlSvc = uuid16(0xFFE5)
           val ctrlChr = uuid16(0xFFE9)
           val cmd = byteArrayOf(/* vendor-specific payload */)
           writeTo(ctrlSvc, ctrlChr, cmd, withResponse = true)

           userInfo("Step on the scale…")
       }

       override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
           // Parse vendor frame(s). When you have a complete result:
           // val measurement = ScaleMeasurement(...populate...)
           // publish(measurement)
       }

       override fun onDisconnected() {
           // Optional cleanup
       }
   }

   Tips:
   - Do not sleep/block inside the handler; the adapter already sequences and paces I/O.
   - Prefer small, deterministic parsers per packet type; log unexpected frames.
   - If your device needs faster/slower pacing, talk to the adapter owner to wire in BleTuning.
--------------------------------------------------------------------------- */
