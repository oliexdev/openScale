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

import android.bluetooth.le.ScanResult
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothPeripheral
import java.util.UUID
import kotlin.math.min

/**
 * What a handler declares about a device it supports.
 *
 * @property displayName Human-friendly name shown in the UI (e.g., "Yunmai Mini").
 * @property capabilities Features the device *can* support in theory.
 * @property implemented  Features this handler actually implements today (may be a subset).
 * @property tuningProfile Optional link timing/retry preferences (see [TuningProfile]).
 * @property linkMode     Whether the device uses GATT or broadcast-only advertisements.
 */
data class DeviceSupport(
    val displayName: String,
    val capabilities: Set<DeviceCapability>,
    val implemented: Set<DeviceCapability>,
    val tuningProfile: TuningProfile = TuningProfile.Balanced,
    val linkMode: LinkMode = LinkMode.CONNECT_GATT
)

/** High-level capabilities a scale might offer. */
enum class DeviceCapability(
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    BODY_COMPOSITION( R.string.cap_body_composition, Icons.Filled.FitnessCenter ),
    TIME_SYNC(        R.string.cap_time_sync,        Icons.Filled.Schedule ),
    USER_SYNC(        R.string.cap_user_sync,        Icons.Filled.Group ),
    HISTORY_READ(     R.string.cap_history_read,     Icons.Filled.History ),
    LIVE_WEIGHT_STREAM(R.string.cap_live_weight,     Icons.Filled.AutoGraph ),
    UNIT_CONFIG(      R.string.cap_unit_config,      Icons.Filled.Tune ),
    BATTERY_LEVEL(    R.string.cap_battery,          Icons.Outlined.BatteryStd )
}

/**
 * Defines whether a device communicates via a GATT connection
 * or only via broadcast advertisements.
 */
enum class LinkMode { CONNECT_GATT, BROADCAST_ONLY, CLASSIC_SPP }

/**
 * Signals how the handler consumed an advertisement.
 * - IGNORED: payload not relevant; adapter keeps scanning silently.
 * - CONSUMED_KEEP_SCANNING: payload processed, but we want to continue scanning (e.g., waiting for stability).
 * - CONSUMED_STOP: final payload processed; adapter should stop scanning and finish the session.
 */
enum class BroadcastAction { IGNORED, CONSUMED_KEEP_SCANNING, CONSUMED_STOP }

/**
 * # ScaleDeviceHandler
 *
 * Minimal base class for a **device-specific** BLE protocol handler.
 *
 * For GATT devices, the app (via `ModernScaleAdapter`) injects a BLE [Transport] and [Callbacks],
 * then calls [onConnected] and forwards notifications to [onNotification].
 *
 * For broadcast-only devices, the adapter attaches a **no-op** transport and forwards
 * advertisement frames to [onAdvertisement]. The handler can call [publish] to emit results.
 *
 * Threading: the adapter serializes and paces BLE I/O. Avoid sleeps or blocking work inside your
 * handler; just call the helpers in the order your protocol requires.
 */
abstract class ScaleDeviceHandler {
    val TAG = this::class.simpleName ?: "ScaleDeviceHandler"

    companion object {
        // Pseudo UUIDs for Classic/SPP
        val CLASSIC_FAKE_SERVICE: UUID =
            UUID.fromString("00000000-0000-0000-0000-00000000C1A0")
        val CLASSIC_DATA_UUID: UUID =
            UUID.fromString("00000000-0000-0000-0000-00000000C1A5")
    }
    /**
     * Identify whether this handler supports the given scanned device.
     * Return a [DeviceSupport] description if yes, or `null` if not.
     */
    abstract fun supportFor(device: ScannedDeviceInfo): DeviceSupport?

    /**
     * Optional UI component for device-specific settings.
     * Override this in concrete handlers to show custom input fields.
     */
    @Composable
    open fun DeviceConfigurationUi() {
        // Default message when no specific configuration is required
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_special_configuration_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // --- Lifecycle entry points called by the adapter -------------------------

    internal fun attachSettings(settings: DriverSettings) {
        this.settings = settings
    }

    internal fun attach(transport: Transport, callbacks: Callbacks, settings: DriverSettings, data: DataProvider) {
        this.transport = transport
        this.callbacks = callbacks
        this.settings = settings
        this.data = data
        logD("attach()")
    }

    internal fun handleConnected(user: ScaleUser) {
        logD("handleConnected(userId=${user.id}, height=${user.bodyHeight}, age=${user.age})")
        try {
            onConnected(user)
        } catch (t: Throwable) {
            logE("onConnected failed: ${t.message}", t)
            callbacks?.onError(
                R.string.bt_error_handler_connect_failed,
                t,
                t.message ?: "—"
            )
        }
    }

    internal fun handleNotification(characteristic: UUID, data: ByteArray) {
        val u = currentAppUser() ?: return
        try {
            onNotification(characteristic, data, u)
        } catch (t: Throwable) {
            logE("onNotification failed for $characteristic: ${t.message}", t)
            callbacks?.onError(
                R.string.bt_error_handler_parse_error,
                t,
                characteristic.toString(),
                t.message ?: "—"
            )
        }
    }

    internal fun handleDisconnected() {
        logD("handleDisconnected()")
        try {
            onDisconnected()
        } catch (t: Throwable) {
            logW("onDisconnected threw: ${t.message}")
        } finally {
        }
    }

    internal fun detach() {
        logD("detach()")
        transport = null
        callbacks = null
    }

    // --- To be implemented by concrete handlers --------------------------------

    /** Called after services are discovered and the link is ready for I/O (GATT devices only). */
    protected open fun onConnected(user: ScaleUser) = Unit

    /** Called for each incoming notification (GATT devices only). */
    protected open fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) = Unit

    /** Optional cleanup hook. */
    protected open fun onDisconnected() = Unit

    /**
     * Called for each advertisement seen for the target device (broadcast-only devices).
     * Default implementation ignores the advertisement.
     */
    open fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction = BroadcastAction.IGNORED

    // --- Protected helper methods (use these from your handler) ----------------

    /** Enable notifications for a characteristic. */
    protected fun setNotifyOn(service: UUID, characteristic: UUID) {
        transport?.setNotifyOn(service, characteristic)
            ?: logW("setNotifyOn called without transport")
    }

    /**
     * Write a command to a characteristic.
     * @param withResponse true for `Write With Response` (default), false for `Write Without Response`.
     */
    protected fun writeTo(
        service: UUID,
        characteristic: UUID,
        payload: ByteArray,
        withResponse: Boolean = true
    ) {
        transport?.write(service, characteristic, payload, withResponse)
            ?: logW("writeTo called without transport")
    }

    /** Read a characteristic (rare for scales; most data comes via NOTIFY). */
    protected fun readFrom(service: UUID, characteristic: UUID) {
        transport?.read(service, characteristic)
            ?: logW("readFrom called without transport")
    }

    /** Publish a fully parsed measurement to the app. */
    protected fun publish(measurement: ScaleMeasurement) {
        logI("\u2190 publish measurement to app")
        callbacks?.onPublish(measurement)
            ?: logW("publish called without callbacks")
    }

    /** Ask the adapter to terminate the link. */
    protected fun requestDisconnect() {
        logD("\u2192 request BLE disconnect")
        transport?.disconnect()
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean {
        val hasUUID = transport?.hasCharacteristic(service, characteristic) ?: false
        if (!hasUUID)
            logD("hasCharacteristic: $service/$characteristic → false")
        return hasUUID
    }

    protected fun getPeripheral(): BluetoothPeripheral? {
        return transport?.getPeripheral()
    }

    /** Helper to build a 16-bit Bluetooth Base UUID (e.g., `uuid16(0xFFE4)`). */
    protected fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    protected fun resolveString(@StringRes resId: Int, vararg args: Any): String =
        callbacks?.resolveString(resId, *args) ?: "res:$resId"

    protected fun settingsGetInt(key: String, default: Int = -1): Int = settings.getInt(key, default)
    protected fun settingsPutInt(key: String, value: Int) { settings.putInt(key, value) }

    protected fun settingsRemove(key: String) { settings.remove(key) }

    protected fun settingsGetString(key: String, default: String? = null): String? = settings.getString(key, default)
    protected fun settingsPutString(key: String, value: String) { settings.putString(key, value) }

    protected fun currentAppUser(): ScaleUser = data.currentUser()
    protected fun usersForDevice(): List<ScaleUser> = data.usersForDevice()
    protected fun lastMeasurementFor(userId: Int): ScaleMeasurement? = data.lastMeasurementFor(userId)

    // --- Logging shortcuts (route to LogManager under a single TAG) ------------

    protected fun logD(msg: String) = LogManager.d(TAG, msg)
    protected fun logI(msg: String) = LogManager.i(TAG, msg)
    protected fun logW(msg: String) = LogManager.w(TAG, msg, null)
    protected fun logE(msg: String, t: Throwable? = null) = LogManager.e(TAG, msg, t)

    // Human-readable messages for users (e.g., snackbars/toasts)
    protected fun userInfo(@StringRes resId: Int, vararg args: Any) {
        callbacks?.onInfo(resId, *args) ?: logD("userInfo dropped: res=$resId")
    }
    protected fun userWarn(@StringRes resId: Int, vararg args: Any) {
        callbacks?.onWarn(resId, *args) ?: logW("userWarn dropped: res=$resId")
    }
    protected fun userError(@StringRes resId: Int, vararg args: Any, t: Throwable? = null) {
        callbacks?.onError(resId, t, *args) ?: logE("userError dropped: res=$resId", t)
    }

    protected fun requestUserInteraction(
        interactionType: BluetoothEvent.UserInteractionType,
        data: Any?
    ) {
        callbacks?.onUserInteractionRequired(interactionType, data)
            ?: logW("requestUserInteraction dropped: $interactionType")
    }

    open suspend fun onUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any) { /* no-op */ }

    // --- Wiring provided by the adapter ---------------------------------------

    private var transport: Transport? = null
    private var callbacks: Callbacks? = null
    private lateinit var settings: DriverSettings
    private lateinit var data: DataProvider
    /**
     * BLE transport the adapter provides. No threading/queueing implied here—
     * the adapter already serializes and paces I/O.
     */
    interface Transport {
        fun setNotifyOn(service: UUID, characteristic: UUID)
        fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean = true)
        fun read(service: UUID, characteristic: UUID)
        fun disconnect()
        fun getPeripheral(): BluetoothPeripheral? = null
        fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean
    }

    // ----- DataProvider: live app data the handler can query -----
    interface DataProvider {
        /** Currently selected app user (may be null if none). */
        fun currentUser(): ScaleUser

        /** Fresh snapshot of app users that are relevant for this device. */
        fun usersForDevice(): List<ScaleUser>

        /** Latest saved measurement for the given user (or null if none). */
        fun lastMeasurementFor(userId: Int): ScaleMeasurement?
    }

    interface DriverSettings {
        fun getInt(key: String, default: Int = -1): Int
        fun putInt(key: String, value: Int)

        fun getString(key: String, default: String? = null): String?
        fun putString(key: String, value: String)

        fun remove(key: String)
    }

    /** App callbacks to emit parsed results and user-visible messages. */
    interface Callbacks {
        fun onPublish(measurement: ScaleMeasurement)
        fun onInfo(@StringRes resId: Int, vararg args: Any) { /* optional */ }
        fun onWarn(@StringRes resId: Int, vararg args: Any) { /* optional */ }
        fun onError(@StringRes resId: Int, t: Throwable? = null, vararg args: Any) { /* optional */ }

        fun onUserInteractionRequired(interactionType: BluetoothEvent.UserInteractionType, data: Any?) { /* optional */ }
        fun resolveString(@StringRes resId: Int, vararg args: Any): String
    }

    // --- Small utils -----------------------------------------------------------

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

    /**
     * ASCII preview of the first `max` bytes; non-printable bytes are rendered as '?'.
     */
    fun ByteArray.toAsciiPreview(max: Int = 64): String {
        if (isEmpty()) return ""
        val n = min(size, max)
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val ch = (this[i].toInt() and 0xFF).toChar()
            sb.append(if (ch.isISOControl()) '?' else ch)
        }
        if (size > max) sb.append("…(+").append(size - max).append("b)")
        return sb.toString()
    }
}
