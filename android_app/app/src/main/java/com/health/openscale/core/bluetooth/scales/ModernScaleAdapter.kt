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

import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.outlined.SignalCellularAlt2Bar
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleCommunicator
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

// -------------------------------------------------------------------------------------------------
// Shared tuning for BLE pacing & retry (used by GATT adapter).
// -------------------------------------------------------------------------------------------------

// Common knobs every link can use
data class CommonTuning(
    val reconnectCooldownMs: Long = 2000,
    val retryBackoffMs: Long = 1500,
    val maxRetries: Int = 3
)

// GATT-specific
data class BleGattTuning(
    val common: CommonTuning = CommonTuning(),
    val notifySetupDelayMs: Long = 120,
    val writeWithResponseDelayMs: Long = 80,
    val writeWithoutResponseDelayMs: Long = 35,
    val postWriteDelayMs: Long = 20,
    val postReadDelayMs: Long = 20,
    val connectAfterScanDelayMs: Long = 650,
    val requestHighConnectionPriority: Boolean = true,
    val requestMtuBytes: Int = 185,
    val operationTimeoutMs: Long = 1000
)

// Broadcast scanner tuning
data class BleBroadcastTuning(
    val common: CommonTuning = CommonTuning(),
    val scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
    val maxScanMs: Long = 20_000,
    val restartBackoffMs: Long = 1500,
    val packetDedupWindowMs: Long = 750,
    val stabilizeWindowMs: Long = 1200,
    val minRssiDbm: Int? = null // e.g. -90
)

// Classic SPP tuning
data class BtSppTuning(
    val common: CommonTuning = CommonTuning(),
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 3000,
    val writeChunkBytes: Int = 256,
    val interChunkDelayMs: Long = 10,
    val soKeepAlive: Boolean = true
)

enum class TuningProfile(
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Conservative(
        labelRes = R.string.tuning_conservative,
        icon = Icons.Filled.SignalCellularAlt1Bar
    ),
    Balanced(
        labelRes = R.string.tuning_balanced,
        icon = Icons.Outlined.SignalCellularAlt2Bar
    ),
    Aggressive(
        labelRes = R.string.tuning_aggressive,
        icon = Icons.Filled.SignalCellularAlt
    )
}
fun TuningProfile.forGatt(): BleGattTuning = when (this) {
    TuningProfile.Balanced -> BleGattTuning(
        common = CommonTuning(2200, 1500, 3),
        notifySetupDelayMs = 120,
        writeWithResponseDelayMs = 80,
        writeWithoutResponseDelayMs = 35,
        postWriteDelayMs = 20,
        postReadDelayMs = 20,
        connectAfterScanDelayMs = 650,
        requestHighConnectionPriority = true,
        requestMtuBytes = 185,
        operationTimeoutMs = 1000
    )
    TuningProfile.Conservative -> BleGattTuning(
        common = CommonTuning(2500, 1800, 3),
        notifySetupDelayMs = 160,
        writeWithResponseDelayMs = 100,
        writeWithoutResponseDelayMs = 50,
        postWriteDelayMs = 30,
        postReadDelayMs = 30,
        connectAfterScanDelayMs = 800,
        requestHighConnectionPriority = true,
        requestMtuBytes = 0,
        operationTimeoutMs = 2000
    )
    TuningProfile.Aggressive -> BleGattTuning(
        common = CommonTuning(1200, 1200, 2),
        notifySetupDelayMs = 80,
        writeWithResponseDelayMs = 60,
        writeWithoutResponseDelayMs = 25,
        postWriteDelayMs = 15,
        postReadDelayMs = 15,
        connectAfterScanDelayMs = 400,
        requestHighConnectionPriority = true,
        requestMtuBytes = 247,
        operationTimeoutMs = 500
    )
}

fun TuningProfile.forBroadcast(): BleBroadcastTuning = when (this) {
    TuningProfile.Balanced -> BleBroadcastTuning(common = CommonTuning(2200,1500,3))
    TuningProfile.Conservative -> BleBroadcastTuning(
        common = CommonTuning(2500,1800,3),
        scanMode = android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED,
        maxScanMs = 30_000
    )
    TuningProfile.Aggressive -> BleBroadcastTuning(
        common = CommonTuning(1200,1200,2),
        scanMode = android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY,
        maxScanMs = 15_000,
        stabilizeWindowMs = 900
    )
}

fun TuningProfile.forSpp(): BtSppTuning = when (this) {
    TuningProfile.Balanced -> BtSppTuning()
    TuningProfile.Conservative -> BtSppTuning(connectTimeoutMs = 12_000, interChunkDelayMs = 15)
    TuningProfile.Aggressive -> BtSppTuning(connectTimeoutMs = 8_000, interChunkDelayMs = 5)
}

// -------------------------------------------------------------------------------------------------
// Small persisted driver settings wrapper backed by SettingsFacade (shared by all adapters).
// -------------------------------------------------------------------------------------------------

class FacadeDriverSettings(
    private val facade: SettingsFacade,
    private val scope: CoroutineScope,
    handlerNamespace: String
) : ScaleDeviceHandler.DriverSettings {

    private val prefix = "ble/$handlerNamespace/"
    private val mem = ConcurrentHashMap<String, String>()

    override fun getInt(key: String, default: Int): Int {
        val k = prefix + key
        mem[k]?.toIntOrNull()?.let { return it }
        val v = runCatching {
            runBlocking(Dispatchers.IO) { withTimeout(300) { facade.observeSetting(k, default).first() } }
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
        val raw = runCatching {
            runBlocking(Dispatchers.IO) { withTimeout(300) { facade.observeSetting(k, default ?: "").first() } }
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

// -------------------------------------------------------------------------------------------------
// ModernScaleAdapter (abstract base)
// - Owns app integration, user/measurements snapshots, event streams, handler wiring.
// - Concrete subclasses implement link-specific connect/disconnect logic.
// -------------------------------------------------------------------------------------------------

abstract class ModernScaleAdapter(
    protected val context: android.content.Context,
    protected val settingsFacade: SettingsFacade,
    protected val measurementFacade: MeasurementFacade,
    protected val userFacade: UserFacade,
    protected val handler: ScaleDeviceHandler
) : ScaleCommunicator {

    protected val TAG = this::class.simpleName ?: "ModernScaleAdapter"

    // ---- coroutine & lifecycle -----------------------------------------------------------------
    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    protected val mainHandler = Handler(Looper.getMainLooper())

    // ---- session targeting ---------------------------------------------------------------------
    protected var targetAddress: String? = null
    protected var lastDisconnectAtMs: Long = 0L

    // ---- UI streams ----------------------------------------------------------------------------
    val _events = MutableSharedFlow<BluetoothEvent>(replay = 1, extraBufferCapacity = 8)
    override fun getEventsFlow(): SharedFlow<BluetoothEvent> = _events.asSharedFlow()

    protected val _isConnecting = MutableStateFlow(false)
    override val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    protected val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // ---- app snapshots for handler.DataProvider -------------------------------------------------
    @Volatile protected var selectedUserSnapshot: ScaleUser? = null
    @Volatile protected var usersSnapshot: List<ScaleUser> = emptyList()
    @Volatile protected var lastSnapshot: Map<Int, ScaleMeasurement> = emptyMap()

    init {
        val driverSettings = FacadeDriverSettings(
            facade = settingsFacade,
            scope = scope,
            handlerNamespace = handler.javaClass.simpleName
        )
        handler.attachSettings(driverSettings)

        // Keep a *live* non-blocking snapshot of the current user.
        scope.launch {
            userFacade.observeSelectedUser().collect { u ->
                selectedUserSnapshot = u?.let(::mapUser)
            }
        }
        // Keep a *fresh enough* snapshot of users & their latest measurement.
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

    // ---- ScaleCommunicator entry points ---------------------------------------------------------

    /**
     * Template method: base validates input & selected user, then calls [doConnect].
     * Many handlers expect a selected user from app state;
     */
    final override fun connect(address: String, scaleUser: ScaleUser?) {
        targetAddress = address
        _isConnecting.value = true

        scope.launch {
            val user: ScaleUser? =
                scaleUser
                    ?: selectedUserSnapshot
                    ?: withTimeoutOrNull(750) {
                        userFacade.observeSelectedUser().first()
                    }?.let(::mapUser)

            if (user == null) {
                _events.tryEmit(
                    BluetoothEvent.ConnectionFailed(
                        address,
                        context.getString(R.string.bt_error_no_user_selected)
                    )
                )
                _isConnecting.value = false
                return@launch
            }

            runCatching {
                doConnect(address, user)
            }.onFailure { t ->
                _events.tryEmit(
                    BluetoothEvent.ConnectionFailed(
                        address,
                        t.message ?: "—"
                    )
                )
                _isConnecting.value = false
            }
        }
    }

    /**
     * Template method: calls [doDisconnect] and resets shared state.
     */
    final override fun disconnect() {
        doDisconnect()
        cleanup(targetAddress)
    }

    /**
     * Default UX helper for devices that only push data via NOTIFY or broadcasts.
     * Subclasses can override if they can actively trigger measurement on device.
     */
    override fun requestMeasurement() {
        val addr = targetAddress ?: "unknown"
        _events.tryEmit(
            BluetoothEvent.DeviceMessage(
                context.getString(R.string.bt_info_waiting_for_measurement),
                addr
            )
        )
    }

    override suspend fun processUserInteractionFeedback(
        interactionType: BluetoothEvent.UserInteractionType,
        appUserId: Int,
        feedbackData: Any
    ) {
        scope.launch {
            runCatching {
                handler.onUserInteractionFeedback(interactionType, appUserId, feedbackData)
            }.onFailure { t ->
                val addr = targetAddress ?: "unknown"
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

    // ---- abstract link hooks -------------------------------------------------------------------

    protected abstract fun doConnect(address: String, selectedUser: ScaleUser)
    protected abstract fun doDisconnect()

    // ---- callbacks & data provider for handlers ------------------------------------------------

    protected val appCallbacks = object : ScaleDeviceHandler.Callbacks {
        override fun onPublish(measurement: ScaleMeasurement) {
            val addr = targetAddress ?: "unknown"
            _events.tryEmit(BluetoothEvent.MeasurementReceived(measurement, addr))
        }

        override fun onInfo(@StringRes resId: Int, vararg args: Any) {
            val addr = targetAddress ?: "unknown"
            _events.tryEmit(BluetoothEvent.DeviceMessage(context.getString(resId, *args), addr))
        }

        override fun onWarn(@StringRes resId: Int, vararg args: Any) {
            val addr = targetAddress ?: "unknown"
            _events.tryEmit(BluetoothEvent.DeviceMessage(context.getString(resId, *args), addr))
        }

        override fun onError(@StringRes resId: Int, t: Throwable?, vararg args: Any) {
            val addr = targetAddress ?: "unknown"
            val msg = context.getString(resId, *args)
            LogManager.e(TAG, msg, t)
            _events.tryEmit(BluetoothEvent.DeviceMessage(msg, addr))
        }

        override fun onUserInteractionRequired(interactionType: BluetoothEvent.UserInteractionType, data: Any?) {
            val addr = targetAddress ?: "unknown"
            _events.tryEmit(BluetoothEvent.UserInteractionRequired(addr, data, interactionType))
        }

        override fun resolveString(@StringRes resId: Int, vararg args: Any): String =
            context.getString(resId, *args)
    }

    protected val dataProvider = object : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = selectedUserSnapshot
            ?: error("No selected user snapshot available")
        override fun usersForDevice(): List<ScaleUser> = usersSnapshot
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = lastSnapshot[userId]
    }

    protected fun cleanup(addr: String?) {
        _isConnected.value = false
        _isConnecting.value = false
        // keep targetAddress to allow higher layer to retry if wanted
    }

    // ---- mapping helpers (core -> legacy DTOs used by handlers) --------------------------------

    protected fun mapUser(u: User): ScaleUser =
        ScaleUser().apply {
            runCatching { id = u.id }
            runCatching { userName = u.name }
            when (val b = runCatching { u.birthDate }.getOrNull()) {
                is Date -> birthday = b
                is Long -> birthday = Date(b)
            }
            runCatching { bodyHeight = u.heightCm }
            runCatching { gender = u.gender }
            runCatching { activityLevel = u.activityLevel }

            runCatching {
                runBlocking(scope.coroutineContext) {
                    val userGoals = userFacade.getAllGoalsForUser(u.id).first()

                    val goalWeightGoal = userGoals.find { it.measurementTypeId == MeasurementTypeKey.WEIGHT.id }
                    if (goalWeightGoal != null) {
                        val goalType = measurementFacade.getAllMeasurementTypes().first()
                            .find { it.id == goalWeightGoal.measurementTypeId }

                        if (goalType != null) {
                            goalWeight = ConverterUtils.convertFloatValueUnit(
                                value = goalWeightGoal.goalValue,
                                fromUnit = goalType.unit,
                                toUnit = UnitType.KG
                            )
                        }
                    }

                    val allMeasurements = measurementFacade.getMeasurementsForUser(u.id).first()

                    val oldestWeightMeasurementValue = allMeasurements
                        .sortedBy { it.measurement.timestamp }
                        .firstNotNullOfOrNull { measurementWithValues ->
                            measurementWithValues.values.find { it.type.key == MeasurementTypeKey.WEIGHT }
                        }

                    if (oldestWeightMeasurementValue != null) {
                        initialWeight = ConverterUtils.convertFloatValueUnit(
                            value = oldestWeightMeasurementValue.value.floatValue ?: 0f,
                            fromUnit = oldestWeightMeasurementValue.type.unit,
                            toUnit = UnitType.KG
                        )
                    }

                    val allTypes = measurementFacade.getAllMeasurementTypes().first()

                    val weightType = allTypes.find { it.key == MeasurementTypeKey.WEIGHT }
                    if (weightType != null) {
                        scaleUnit = weightType.unit.toWeightUnit()
                    }
                }
            }
        }

    protected fun mapMeasurement(mwv: MeasurementWithValues?): ScaleMeasurement? {
        if (mwv == null) return null
        val m = ScaleMeasurement()
        runCatching { m.userId = mwv.measurement.userId }
        runCatching { m.dateTime = Date(mwv.measurement.timestamp) }

        fun valueOf(key: MeasurementTypeKey): MeasurementValue? =
            mwv.values.firstOrNull { it.type.key == key }?.value

        valueOf(MeasurementTypeKey.WEIGHT)?.let { m.weight = it.floatValue ?: 0f }
        valueOf(MeasurementTypeKey.BODY_FAT)?.let { m.fat = it.floatValue ?: 0f }
        valueOf(MeasurementTypeKey.WATER)?.let { m.water = it.floatValue ?: 0f }
        valueOf(MeasurementTypeKey.MUSCLE)?.let { m.muscle = it.floatValue ?: 0f }
        valueOf(MeasurementTypeKey.VISCERAL_FAT)?.let { m.visceralFat = it.floatValue ?: 0f }
        valueOf(MeasurementTypeKey.LBM)?.let { m.lbm = it.floatValue ?: 0f }
        valueOf(MeasurementTypeKey.BONE)?.let { m.bone = it.floatValue ?: 0f }

        return m
    }

    /** Pretty print a few leading bytes of a payload for logs. */
    fun ByteArray.toHexPreview(limit: Int): String {
        if (limit <= 0 || isEmpty()) return "(payload ${size}b)"
        val show = min(size, limit)
        val sb = StringBuilder("payload=[")
        for (i in 0 until show) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", this[i]))
        }
        if (size > limit) sb.append(" …(+").append(size - limit).append("b)")
        sb.append(']')
        return sb.toString()
    }
}