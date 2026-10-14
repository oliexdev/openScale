/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.QardioBaseLib
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Qardio QardioBase (B100) and QardioBase 2 (B200).
 *
 * Qardio shut down in 2025; the scales keep working over BLE without the cloud. The protocol was
 * taken from the last official Android app (2.5.1). See [QardioBaseLib] for the wire format.
 *
 * Session flow:
 * 1. Enable STATE and ENGINEERING notifications, sync the clock.
 * 2. If the current app user has no up-to-date profile on the scale (first use, profile edited,
 *    scale reset by someone else, or a reset was requested in the device settings), enter
 *    configuration mode, read the scale's user list, add or rewrite the profile in place, then
 *    leave configuration mode.
 * 3. Optionally (device setting) select the current user's profile, as the official app did while
 *    its dashboard was open. This is a hint; the scale still identifies the person itself.
 * 4. On STATE 6 read the result JSON. Like the official app: a result with our profile's `userid`
 *    is saved, one with another `userid` is not, and one without `userid` is a guest weigh-in
 *    (not saved unless the device setting says so). Body composition is the scale's own, computed
 *    from the profile stored on it; see QardioBaseLib.Result for the field mapping.
 *
 * The scale identifies people by weight and impedance and learns from the first 1–3 weigh-ins,
 * where it shows "name > guest" and the user steps off and presses firmly in the middle of the scale
 * to pick. Profile names must be lowercase letters/digits (max 6), otherwise the firmware never
 * matches the profile. There is no per-user delete, only a full reset that removes every profile.
 *
 * Callbacks arrive on the main thread and timers on the adapter's background scope, so all state
 * changes happen under [lock].
 */
class QardioBaseHandler : ScaleDeviceHandler() {

    companion object {
        val SERVICE: UUID = UUID.fromString("c8219e89-93e0-4169-a3dc-ea7959e866af")
        val CHR_STATE: UUID = UUID.fromString("a78af805-8f3f-4e8f-a964-318b768bc38c")
        val CHR_ENGINEERING: UUID = UUID.fromString("9f3f4e1b-37d7-4f95-b374-cf585d808beb")
        val CHR_RESULT: UUID = UUID.fromString("b24f98be-9cd4-4f82-b935-01f18f104ede")
        val CHR_USER_CONFIG: UUID = UUID.fromString("9891780f-831e-4741-a2e8-c4a7956cc31e")
        val SVC_BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val CHR_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val SVC_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val CHR_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val CHR_FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val CHR_SOFTWARE: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")

        private const val STATE_CONFIG_MODE = 1
        private const val STATE_RESULT_READY = 6

        /** How long to wait for the user list in configuration mode before reading it again. */
        private const val USER_LIST_TIMEOUT_MS = 5_000L
        /** Extra user-list reads before giving up; never guess "add" (that duplicates profiles). */
        private const val USER_LIST_RETRIES = 2

        /**
         * How long a guest result (no `userid`) is held so the user can assign it on the scale
         * (step off, press firmly in the middle). The scale learns who is who from these choices.
         */
        private const val GUEST_HOLD_MS = 30_000L
        private const val GUEST_POLL_MS = 3_000L

        /**
         * How long entering configuration mode (STATE 1) or a reset ack may take. The scale may not
         * enter it at all, e.g. while someone stands on it; without a limit the phone would keep the
         * connection, which the scale gives to one phone at a time.
         */
        private const val CONFIG_MODE_TIMEOUT_MS = 10_000L

        /** A result read at connect that is at most this old counts as the weigh-in just made. */
        private const val RECENT_RESULT_MS = 120_000L

        /** The last legacy reset frame is not acked; time for the write queue to send it. */
        private const val LEGACY_LAST_FRAME_MS = 1_000L

        /** Each profile chunk is acknowledged within ~150 ms; give up well after that. */
        private const val CHUNK_ACK_TIMEOUT_MS = 3_000L

        /** A result read that fails right after STATE 6 is tried once more after this delay. */
        private const val RESULT_RETRY_MS = 1_000L

        /** Reset for the next connection: 0 none, [RESET_NORMAL] or [RESET_LEGACY]. */
        private const val KEY_PENDING_RESET = "pendingReset"
        private const val RESET_NORMAL = 1
        private const val RESET_LEGACY = 2
        private const val KEY_SELECT_ON_CONNECT = "selectOnConnect"
        private const val KEY_SAVE_GUEST = "saveGuest"
        /**
         * Scale-wide flags. "scale*" mirror what the scale last reported (-1 unknown) and drive the
         * switches; "pending*" (-1 none) is a one-off change applied on the next connection. Phones
         * never re-assert their own values, so two phones cannot keep flipping the scale back.
         */
        private const val KEY_SCALE_IMPEDANCE = "scaleImpedance"
        private const val KEY_SCALE_HAPTIC = "scaleHaptic"
        /** Device Information strings as last read, shown in the device settings. */
        private const val KEY_MODEL = "scaleModel"
        private const val KEY_FIRMWARE = "scaleFirmware"
        private const val KEY_SOFTWARE = "scaleSoftware"
        private const val KEY_BATTERY = "scaleBattery"
        /** Profile ids in the scale's user list as last read (comma separated). */
        private const val KEY_SCALE_USERS = "scaleUsers"
        /** Name of a profile this phone registered, by scale profile id (the list carries ids only). */
        private fun keyProfileName(scaleId: String) = "profileName.$scaleId"
        private const val KEY_PENDING_IMPEDANCE = "pendingImpedance"
        private const val KEY_PENDING_HAPTIC = "pendingHaptic"
        private const val KEY_GENERATION = "generation"
        private const val KEY_LAST_RESULT_ID = "lastResultId"
        private fun keyScaleUserId(appUserId: Int) = "scaleUserId/$appUserId"
        private fun keyToken(appUserId: Int) = "token/$appUserId"
        private fun keyProfile(appUserId: Int) = "profile/$appUserId"
    }

    private enum class Phase { IDLE, ENTERING_CONFIG, READING_USERS, WRITING_PROFILE, RESETTING, RESETTING_LEGACY }

    private val lock = Any()

    @Volatile private var phase = Phase.IDLE
    @Volatile private var lastState = -1
    private var user: ScaleUser? = null
    private var profile: QardioBaseLib.Profile? = null
    private var pendingFrames = ArrayDeque<ByteArray>()
    private var userListTimeout: Job? = null
    /** The result read at connect may be an old weigh-in; publish it if new, but never warn about it. */
    private var resultFromConnect = false
    /** The first state seen on this connection was 6: the weigh-in finished just before it. */
    private var connectedOnResult = false
    /** Values of the last B100 result (no id) on this connection; see [isRepeatedResult]. */
    private var lastIdlessResult: String? = null
    private var heldGuest: QardioBaseLib.Result? = null
    private var guestTimeout: Job? = null
    private var chunkAckTimeout: Job? = null
    private var configModeTimeout: Job? = null
    private var userListAttempts = 0
    private var resultRetried = false
    /** Scale-wide flags from the user config (impedance, vibration); kept when writing profiles. */
    private var scaleFlags = QardioBaseLib.ConfigFlags()
    /** Flags sent with the profile being written, recorded as the scale's once it is acked. */
    private var writtenFlags: QardioBaseLib.ConfigFlags? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val byName = device.name.equals("QardioBase", ignoreCase = true)
        val byService = device.serviceUuids.contains(SERVICE)
        if (!byName && !byService) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            // The display unit (kg/lb/st) is part of the profile written to the scale.
            DeviceCapability.UNIT_CONFIG,
            DeviceCapability.BATTERY_LEVEL,
        )
        return DeviceSupport(
            displayName = "Qardio QardioBase / QardioBase 2",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) = synchronized(lock) {
        this.user = user
        this.profile = buildProfile(user)
        phase = Phase.IDLE
        lastState = -1
        connectedOnResult = false
        lastIdlessResult = null
        pendingFrames.clear()
        scaleFlags = QardioBaseLib.ConfigFlags()

        setNotifyOn(SERVICE, CHR_STATE)
        setNotifyOn(SERVICE, CHR_ENGINEERING)
        writeEngineering(QardioBaseLib.setTimestamp(System.currentTimeMillis() / 1000))

        if (settingsGetInt(KEY_PENDING_RESET, 0) != 0 || profileOutdated(user) || pendingFlags() != null) {
            enterConfigMode()
        } else {
            // Cheap check that our profile is still on the scale (someone may have reset it).
            readFrom(SERVICE, CHR_USER_CONFIG)
            selectOwnProfileIfEnabled()
        }
        readFrom(SERVICE, CHR_STATE)
        readFrom(SVC_BATTERY, CHR_BATTERY_LEVEL)
        readFrom(SVC_DEVICE_INFO, CHR_MODEL)
        readFrom(SVC_DEVICE_INFO, CHR_FIRMWARE)
        readFrom(SVC_DEVICE_INFO, CHR_SOFTWARE)
        // Picks up a weigh-in that finished while we were not connected (deduplicated by id).
        resultFromConnect = true
        readFrom(SERVICE, CHR_RESULT)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        synchronized(lock) {
            when (characteristic) {
                CHR_STATE -> if (data.isNotEmpty()) onState(data[0].toInt() and 0xFF)
                CHR_ENGINEERING -> QardioBaseLib.parseEngineeringReply(data)?.let(::onEngineeringReply)
                CHR_USER_CONFIG -> onUserList(String(data, Charsets.UTF_8))
                CHR_RESULT -> onResult(String(data, Charsets.UTF_8))
                CHR_BATTERY_LEVEL -> if (data.isNotEmpty()) {
                    val level = data[0].toInt() and 0xFF
                    logD("Battery level: $level%")
                    settingsPutInt(KEY_BATTERY, level)
                    if (level in 0..10) userWarn(R.string.bt_warn_low_battery, level)
                }
                CHR_MODEL -> rememberDeviceInfo(KEY_MODEL, data)
                CHR_FIRMWARE -> rememberDeviceInfo(KEY_FIRMWARE, data)
                CHR_SOFTWARE -> rememberDeviceInfo(KEY_SOFTWARE, data)
                else -> Unit
            }
        }
    }

    private fun rememberDeviceInfo(key: String, data: ByteArray) {
        val text = String(data, Charsets.US_ASCII).trim { it <= ' ' }
        // A failed read is delivered too; keep only plain printable strings.
        if (text.isEmpty() || text.any { it !in ' '..'~' }) return
        logD("$key: $text")
        settingsPutString(key, text)
    }

    override fun onDisconnected() = synchronized(lock) {
        userListTimeout?.cancel()
        chunkAckTimeout?.cancel()
        configModeTimeout?.cancel()
        guestTimeout?.cancel()
        // Still attached here, so a held guest weigh-in is resolved rather than dropped silently.
        finishHeldGuest()
        phase = Phase.IDLE
        pendingFrames.clear()
    }

    // --- State machine ------------------------------------------------------------------------

    private fun onState(state: Int) {
        logD("state=$state phase=$phase")
        // The state is both read and notified at connect; act on transitions only.
        if (state == lastState) return
        val firstState = lastState == -1
        lastState = state
        if (firstState && state == STATE_RESULT_READY) connectedOnResult = true
        when (state) {
            STATE_CONFIG_MODE -> if (phase == Phase.ENTERING_CONFIG) onConfigModeEntered()
            // A 6 seen first is a result from before connecting: the result read at connect
            // already covers it (see [RECENT_RESULT_MS]), and a second read would fail.
            STATE_RESULT_READY -> if (!firstState) {
                resultFromConnect = false
                resultRetried = false
                readFrom(SERVICE, CHR_RESULT)
            }
        }
    }

    private fun enterConfigMode() {
        phase = Phase.ENTERING_CONFIG
        writeEngineering(QardioBaseLib.configMode(true))
        armConfigModeTimeout()
    }

    /** Leaves [Phase.ENTERING_CONFIG] / [Phase.RESETTING] if the scale does not answer. */
    private fun armConfigModeTimeout() {
        configModeTimeout?.cancel()
        configModeTimeout = scope.launch {
            delay(CONFIG_MODE_TIMEOUT_MS)
            synchronized(lock) {
                val stuck = phase
                if (stuck != Phase.ENTERING_CONFIG && stuck != Phase.RESETTING) return@launch
                logW("No answer from the scale in phase $stuck; leaving configuration mode")
                // A pending reset or profile change stays pending for the next connection.
                abortProfileWrite()
                userWarn(
                    if (stuck == Phase.RESETTING) R.string.bt_warn_qardio_reset_timeout
                    else R.string.bt_warn_qardio_config_timeout
                )
            }
        }
    }

    private fun onConfigModeEntered() {
        configModeTimeout?.cancel()
        when (settingsGetInt(KEY_PENDING_RESET, 0)) {
            RESET_NORMAL -> {
                phase = Phase.RESETTING
                userInfo(R.string.bt_info_qardio_resetting)
                writeEngineering(QardioBaseLib.uniqueId(true, randomToken(16)))
                writeEngineering(QardioBaseLib.reset())
                armConfigModeTimeout()
                return
            }
            RESET_LEGACY -> {
                phase = Phase.RESETTING_LEGACY
                userInfo(R.string.bt_info_qardio_resetting)
                pendingFrames = ArrayDeque(listOf(
                    QardioBaseLib.clearConfigTarget(QardioBaseLib.TARGET_USER_CONFIG),
                    QardioBaseLib.clearConfigTarget(QardioBaseLib.TARGET_WIFI_CONFIG),
                    QardioBaseLib.uniqueId(true, randomToken(16)),
                ))
                sendLegacyResetStep()
                return
            }
        }
        phase = Phase.READING_USERS
        userListAttempts = 0
        requestUserList()
    }

    private fun requestUserList() {
        readFrom(SERVICE, CHR_USER_CONFIG)
        userListTimeout?.cancel()
        userListTimeout = scope.launch {
            delay(USER_LIST_TIMEOUT_MS)
            synchronized(lock) { if (phase == Phase.READING_USERS) retryUserListOrAbort() }
        }
    }

    /**
     * Without the list we cannot tell "add" from "rewrite slot n", and guessing "add" for a profile
     * that is already stored duplicates it (the official app only ever acted on a list it read).
     */
    private fun retryUserListOrAbort() {
        if (userListAttempts < USER_LIST_RETRIES) {
            userListAttempts++
            logW("No usable user list from the scale; reading it again ($userListAttempts)")
            requestUserList()
            return
        }
        logW("No user list from the scale; leaving configuration mode without registering")
        userListTimeout?.cancel()
        abortProfileWrite()
        userWarn(R.string.bt_warn_qardio_profile_timeout)
    }

    private fun onUserList(json: String) {
        logD("user config: $json")
        val ids = QardioBaseLib.parseUserIds(json)
        val own = profile?.id?.toLong()
        logD("user list ids=$ids own=$own phase=$phase")
        ids?.let { settingsPutString(KEY_SCALE_USERS, it.filterNotNull().joinToString(",")) }

        when (phase) {
            Phase.READING_USERS -> {
                // A failed read is delivered too (as a garbage value); it is not an empty list.
                if (ids == null) return retryUserListOrAbort()
                userListTimeout?.cancel()
                scaleFlags = QardioBaseLib.parseConfigFlags(json)
                rememberScaleFlags(scaleFlags)
                // Rewriting our own slot is also how a pending scale-wide change is applied: the
                // flags travel at the top level of every profile write, as in the official app.
                writeProfile(ids.indexOf(own).takeIf { it >= 0 }?.let { it to ids.size })
            }
            Phase.IDLE -> if (ids != null) {
                rememberScaleFlags(QardioBaseLib.parseConfigFlags(json))
                if (own != null && own !in ids) {
                    logI("Profile $own is no longer on the scale; registering again")
                    enterConfigMode()
                }
            }
            else -> Unit
        }
    }

    /** [slot] = (index, slotCount) to rewrite an existing profile, null to append one. */
    private fun writeProfile(slot: Pair<Int, Int>?) {
        val p = profile ?: return
        phase = Phase.WRITING_PROFILE
        userInfo(if (slot == null) R.string.bt_info_register_new_user_started else R.string.bt_info_qardio_updating)
        val flags = pendingFlags()?.let {
            QardioBaseLib.ConfigFlags(impedance = it.first ?: scaleFlags.impedance, haptic = it.second ?: scaleFlags.haptic)
        } ?: scaleFlags
        writtenFlags = flags
        val json = QardioBaseLib.profileJson(
            p, slot?.first, slot?.second ?: 0,
            impedance = flags.impedance, haptic = flags.haptic,
        )
        pendingFrames = ArrayDeque(QardioBaseLib.longWriteFrames(QardioBaseLib.TARGET_USER_CONFIG, json.toByteArray()))
        sendNextChunk()
    }

    private fun sendNextChunk() {
        writeEngineering(pendingFrames.removeFirst())
        chunkAckTimeout?.cancel()
        chunkAckTimeout = scope.launch {
            delay(CHUNK_ACK_TIMEOUT_MS)
            synchronized(lock) {
                if (phase != Phase.WRITING_PROFILE) return@launch
                // Without an ack the scale is not taking the write (e.g. it left configuration mode).
                logW("No ack for a profile chunk; aborting registration")
                abortProfileWrite()
                userWarn(R.string.bt_warn_qardio_profile_timeout)
            }
        }
    }

    private fun abortProfileWrite() {
        chunkAckTimeout?.cancel()
        pendingFrames.clear()
        phase = Phase.IDLE
        writeEngineering(QardioBaseLib.configMode(false))
    }

    private fun onEngineeringReply(reply: QardioBaseLib.EngineeringReply) {
        when (reply.cmd) {
            QardioBaseLib.CMD_LONG_WRITE -> when (phase) {
                Phase.WRITING_PROFILE -> onProfileChunkAck(reply.value)
                Phase.RESETTING_LEGACY -> {
                    chunkAckTimeout?.cancel()
                    logI("Legacy reset frame acked (${reply.value})")
                    sendLegacyResetStep()
                }
                else -> Unit
            }
            QardioBaseLib.CMD_RESET -> if (phase == Phase.RESETTING) onResetDone()
            // Mirrors STATE on the B200; the official app read 18/19 as step on/off.
            QardioBaseLib.CMD_STEP_EVENT -> logD("engineering 0x05 value=${reply.value}")
        }
    }

    private fun onProfileChunkAck(value: Int) {
        chunkAckTimeout?.cancel()
        if (value != 0) {
            abortProfileWrite()
            if (value == QardioBaseLib.ACK_USERS_FULL) userWarn(R.string.bt_warn_qardio_users_full)
            else userWarn(R.string.bt_warn_register_failed_with_code, value)
            return
        }
        if (pendingFrames.isNotEmpty()) {
            sendNextChunk()
            return
        }

        // Profile complete: same epilogue as the official app, then leave configuration mode.
        writeEngineering(QardioBaseLib.uniqueId(false, randomToken(16)))
        writeEngineering(QardioBaseLib.setTimestamp(System.currentTimeMillis() / 1000))
        writeEngineering(QardioBaseLib.configMode(false))
        phase = Phase.IDLE
        selectOwnProfileIfEnabled()

        writtenFlags?.let {
            rememberScaleFlags(it)
            settingsPutInt(KEY_PENDING_IMPEDANCE, -1)
            settingsPutInt(KEY_PENDING_HAPTIC, -1)
        }

        val u = user ?: return
        val p = profile ?: return
        settingsPutString(keyProfile(u.id), profileSignature(p))
        settingsPutString(keyProfileName(p.id.toString()), QardioBaseLib.scaleName(p.name))
        // Keeps the shown list current without another read.
        val listed = settingsGetString(KEY_SCALE_USERS).orEmpty().split(',').filter { it.isNotEmpty() }
        if (p.id.toString() !in listed) settingsPutString(KEY_SCALE_USERS, (listed + p.id.toString()).joinToString(","))
        userInfo(R.string.bt_info_qardio_updated)
    }

    /** A one-off scale-wide change requested in the device settings: (impedance, haptic), null = keep. */
    private fun pendingFlags(): Pair<Boolean?, Boolean?>? {
        val z = settingsGetInt(KEY_PENDING_IMPEDANCE, -1).takeIf { it >= 0 }?.let { it == 1 }
        val haptic = settingsGetInt(KEY_PENDING_HAPTIC, -1).takeIf { it >= 0 }?.let { it == 1 }
        return if (z == null && haptic == null) null else z to haptic
    }

    private fun rememberScaleFlags(flags: QardioBaseLib.ConfigFlags) {
        settingsPutInt(KEY_SCALE_IMPEDANCE, if (flags.impedance) 1 else 0)
        settingsPutInt(KEY_SCALE_HAPTIC, if (flags.haptic) 1 else 0)
    }

    private fun onResetDone() {
        chunkAckTimeout?.cancel()
        configModeTimeout?.cancel()
        pendingFrames.clear()
        settingsPutInt(KEY_PENDING_RESET, 0)
        settingsPutString(KEY_SCALE_USERS, "")
        // Invalidates every stored profile signature, so each app user registers again on their
        // next connection. Not now: a reset scale can be given away as it is.
        settingsPutInt(KEY_GENERATION, settingsGetInt(KEY_GENERATION, 0) + 1)
        // The reset also restores the scale-wide defaults (impedance off, vibration on): keep the
        // settings it had as pending, so that registration puts them back.
        if (settingsGetInt(KEY_PENDING_IMPEDANCE, -1) < 0) settingsGetInt(KEY_SCALE_IMPEDANCE, -1)
            .takeIf { it >= 0 }?.let { settingsPutInt(KEY_PENDING_IMPEDANCE, it) }
        if (settingsGetInt(KEY_PENDING_HAPTIC, -1) < 0) settingsGetInt(KEY_SCALE_HAPTIC, -1)
            .takeIf { it >= 0 }?.let { settingsPutInt(KEY_PENDING_HAPTIC, it) }
        logI("Scale reset")
        phase = Phase.IDLE
        userInfo(R.string.bt_info_qardio_reset_done)
        requestDisconnect()
    }

    /**
     * Experimental: the official app's reset for firmware before 1.8 (LEGACY_RESET_BASE): clear the
     * user config, clear the Wi-Fi config, then the unique id frame. A step without an ack does not
     * stop the next one; ack values are logged to learn what old firmware does.
     */
    private fun sendLegacyResetStep() {
        val frame = pendingFrames.removeFirstOrNull() ?: return onResetDone()
        writeEngineering(frame)
        chunkAckTimeout?.cancel()
        if (pendingFrames.isEmpty()) {
            // The unique id frame is not acked. Disconnecting cancels the link at once, dropping
            // writes still queued, so give the queue time to send it first.
            chunkAckTimeout = scope.launch {
                delay(LEGACY_LAST_FRAME_MS)
                synchronized(lock) { if (phase == Phase.RESETTING_LEGACY) onResetDone() }
            }
            return
        }
        chunkAckTimeout = scope.launch {
            delay(CHUNK_ACK_TIMEOUT_MS)
            synchronized(lock) {
                if (phase != Phase.RESETTING_LEGACY) return@launch
                logW("No ack for a legacy reset frame; going on")
                sendLegacyResetStep()
            }
        }
    }

    private fun onResult(json: String) {
        logD("result: $json")
        var fromConnect = resultFromConnect
        resultFromConnect = false
        val result = QardioBaseLib.parseResult(json) ?: run {
            // Failed reads arrive as garbage too. At connect, or once a result has been read, the
            // characteristic is simply empty; right after STATE 6 try once more.
            if (fromConnect || heldGuest != null) return
            if (!resultRetried) {
                resultRetried = true
                logW("Result read failed; retrying")
                scope.launch {
                    delay(RESULT_RETRY_MS)
                    readFrom(SERVICE, CHR_RESULT)
                }
            } else {
                logW("Unparseable result: ${json.take(120)}")
            }
            return
        }
        // The official app read the result once per state change; publish each weigh-in once.
        // A B100 result (no id, no time) read at connect is from this wake-up only if the first
        // state was 6; otherwise it is an earlier weigh-in, and the new one arrives as a STATE 6.
        // The state read is queued before the result read, so the first state is known here.
        if (fromConnect && result.id == null && !connectedOnResult) return
        if (isRepeatedResult(result)) return

        val u = user ?: return
        val ownId = profile?.id?.toLong()

        // A weigh-in that finished just before the connection is handled as a live one (guest hold,
        // warnings); only older ones are collected quietly.
        if (fromConnect && result.time?.let { abs(System.currentTimeMillis() - it.time) <= RECENT_RESULT_MS } == true) {
            fromConnect = false
        }

        heldGuest?.let { held ->
            guestTimeout?.cancel()
            // Whether a reassignment keeps the result id is not verified, so a result for our own
            // profile also counts as one: saving both would duplicate the weigh-in. Deliberately, a
            // held guest is then dropped even if the own result (different id) was a separate
            // weigh-in; that needs two weigh-ins within the hold. Tighten to `result.id == held.id`
            // once a reassignment is seen to keep the id.
            if (result.scaleUserId != null && (result.id == held.id || result.scaleUserId == ownId)) {
                // The user assigned the held guest weigh-in on the scale: this result replaces it.
                heldGuest = null
            } else {
                // A different weigh-in: resolve the held one first so it is not lost silently.
                finishHeldGuest()
            }
        }
        if (result.scaleUserId == null && result.id != null) {
            // QardioBase 2 guest weigh-in (the B100 sends neither id nor userid and is always saved).
            if (fromConnect) {
                // An old weigh-in nobody assigned; the official app filed these under "guest".
                if (settingsGetInt(KEY_SAVE_GUEST, 0) == 1) publishResult(result)
                return
            }
            heldGuest = result
            userInfo(R.string.bt_info_qardio_guest)
            guestTimeout?.cancel()
            guestTimeout = scope.launch {
                // The scale may not signal a reassignment, so look at the result again meanwhile.
                repeat((GUEST_HOLD_MS / GUEST_POLL_MS).toInt()) {
                    delay(GUEST_POLL_MS)
                    synchronized(lock) { if (heldGuest != null) readFrom(SERVICE, CHR_RESULT) }
                }
                synchronized(lock) {
                    if (heldGuest != null) {
                        finishHeldGuest()
                        releaseScale()
                    }
                }
            }
            return
        }

        if (result.scaleUserId != null && result.scaleUserId != ownId) {
            if (fromConnect) return
            logI("Result for scale user ${result.scaleUserId} (${result.scaleUserName}), not ours ($ownId)")
            userWarn(
                R.string.bt_warn_qardio_other_user,
                formatWeight(result.weightKg, u.scaleUnit),
                result.scaleUserName ?: result.scaleUserId.toString(),
            )
            releaseScale()
            return
        }

        publishResult(result)
        // An old result read at connect: stay connected for the weigh-in that is about to happen.
        if (!fromConnect) releaseScale()
    }

    /**
     * Whether [result] was handled before; records it otherwise. A QardioBase 2 result is keyed by
     * its id (plus the userid: reassigning a guest weigh-in on the scale re-sends it with one). A
     * B100 result has no id; it is keyed by its values for this connection only, against a repeated
     * read in one wake-up. Across connections the first state decides (see [onResult]): persisted,
     * the same weight on another day (likely with impedance off) would be dropped.
     */
    private fun isRepeatedResult(result: QardioBaseLib.Result): Boolean {
        if (result.id == null) {
            val key = "${result.weightKg}|${result.impedanceOhm ?: ""}|${result.fatPercent ?: ""}"
            if (key == lastIdlessResult) return true
            lastIdlessResult = key
            return false
        }
        val key = "${result.id}|${result.scaleUserId ?: ""}"
        if (key == settingsGetString(KEY_LAST_RESULT_ID)) return true
        settingsPutString(KEY_LAST_RESULT_ID, key)
        return false
    }

    /**
     * Resolves a guest weigh-in nobody assigned on the scale. The official app filed these under
     * "guest" rather than the phone's owner; saving them is opt-in (device setting).
     */
    private fun finishHeldGuest() {
        val guest = heldGuest ?: return
        heldGuest = null
        if (settingsGetInt(KEY_SAVE_GUEST, 0) == 1) {
            publishResult(guest)
        } else {
            val unit = user?.scaleUnit ?: WeightUnit.KG
            userWarn(R.string.bt_warn_qardio_guest_not_saved, formatWeight(guest.weightKg, unit))
        }
    }

    private fun publishResult(result: QardioBaseLib.Result) {
        val u = user ?: return
        // A weigh-in collected later (read at connect) keeps the time it was taken.
        val m = ScaleMeasurement(userId = u.id, dateTime = result.time ?: Date())
        // Only what the scale reported (see QardioBaseLib.Result); openScale derives BMI, BMR and
        // lean body mass. A guest weigh-in carries weight and impedance only.
        m[MeasurementType.WEIGHT] = Kg(result.weightKg)
        result.impedanceOhm?.let { m[MeasurementType.IMPEDANCE] = Ohm(it.toFloat()) }
        result.fatPercent?.let { m[MeasurementType.BODY_FAT] = Percent(it) }
        result.waterPercent?.let { m[MeasurementType.WATER] = Percent(it) }
        result.skeletalMusclePercent?.let { m[MeasurementType.MUSCLE] = Percent(it) }
        result.boneKg?.let { m[MeasurementType.BONE] = Kg(it) }
        publish(m)
    }

    /** The scale takes one connection at a time; free it for other phones once we are done. */
    private fun releaseScale() {
        if (phase == Phase.IDLE) requestDisconnect()
    }

    // --- Profile bookkeeping ------------------------------------------------------------------

    private fun buildProfile(user: ScaleUser): QardioBaseLib.Profile {
        var scaleId = settingsGetInt(keyScaleUserId(user.id), -1)
        if (scaleId <= 0) {
            scaleId = Random.nextInt(1, Int.MAX_VALUE)
            settingsPutInt(keyScaleUserId(user.id), scaleId)
        }
        val token = settingsGetString(keyToken(user.id)) ?: randomHex(32).also {
            settingsPutString(keyToken(user.id), it)
        }
        return QardioBaseLib.Profile(
            id = scaleId,
            name = user.userName,
            male = user.gender.isMale(),
            heightCm = user.bodyHeight.roundToInt(), // the official app rounded too
            birthday = user.birthday,
            units = when (user.scaleUnit) {
                WeightUnit.KG -> 0
                WeightUnit.LB -> 1
                WeightUnit.ST -> 2
            },
            token = token,
        )
    }

    private fun profileSignature(p: QardioBaseLib.Profile) = "${settingsGetInt(KEY_GENERATION, 0)}|${p.signature}"

    private fun profileOutdated(user: ScaleUser): Boolean {
        val p = profile ?: return false
        return settingsGetString(keyProfile(user.id)) != profileSignature(p)
    }

    private fun selectOwnProfileIfEnabled() {
        val p = profile ?: return
        if (settingsGetInt(KEY_SELECT_ON_CONNECT, 0) == 1) writeEngineering(QardioBaseLib.selectUser(p.id))
    }

    private fun formatWeight(kg: Float, unit: WeightUnit): String {
        val suffix = when (unit) {
            WeightUnit.KG -> "kg"
            WeightUnit.LB -> "lb"
            WeightUnit.ST -> "st"
        }
        return String.format(Locale.getDefault(), "%.1f %s", ConverterUtils.fromKilogram(kg, unit), suffix)
    }

    private fun writeEngineering(payload: ByteArray) = writeTo(SERVICE, CHR_ENGINEERING, payload)

    private fun randomToken(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return String(CharArray(length) { alphabet[Random.nextInt(alphabet.length)] })
    }

    private fun randomHex(length: Int): String =
        String(CharArray(length) { "0123456789abcdef"[Random.nextInt(16)] })

    // --- Device settings UI ---------------------------------------------------------------------

    @Composable
    override fun DeviceConfigurationUi() {
        var pendingReset by remember { mutableIntStateOf(settingsGetInt(KEY_PENDING_RESET, 0)) }
        var resendRequested by remember { mutableStateOf(false) }
        var selectOnConnect by remember { mutableIntStateOf(settingsGetInt(KEY_SELECT_ON_CONNECT, 0)) }
        var saveGuest by remember { mutableIntStateOf(settingsGetInt(KEY_SAVE_GUEST, 0)) }
        // Shown value: the pending change if any, else what the scale last reported (default on).
        fun shown(pendingKey: String, scaleKey: String): Int =
            settingsGetInt(pendingKey, -1).takeIf { it >= 0 } ?: settingsGetInt(scaleKey, 1).takeIf { it >= 0 } ?: 1
        var impedance by remember { mutableIntStateOf(shown(KEY_PENDING_IMPEDANCE, KEY_SCALE_IMPEDANCE)) }
        var haptic by remember { mutableIntStateOf(shown(KEY_PENDING_HAPTIC, KEY_SCALE_HAPTIC)) }
        var flagsPending by remember {
            mutableStateOf(settingsGetInt(KEY_PENDING_IMPEDANCE, -1) >= 0 || settingsGetInt(KEY_PENDING_HAPTIC, -1) >= 0)
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Note(stringResource(R.string.qardio_config_description))

            // --- Read-only: what the scale reported at the last connection ---
            SectionHeader(stringResource(R.string.qardio_section_info))
            val model = settingsGetString(KEY_MODEL)
            if (model == null) {
                Note(stringResource(R.string.qardio_info_none))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(stringResource(R.string.qardio_info_model), model)
                    settingsGetString(KEY_FIRMWARE)?.let { InfoRow(stringResource(R.string.qardio_info_firmware), it) }
                    settingsGetString(KEY_SOFTWARE)?.let { InfoRow(stringResource(R.string.qardio_info_software), it) }
                    settingsGetInt(KEY_BATTERY, -1).takeIf { it >= 0 }?.let {
                        InfoRow(stringResource(R.string.cap_battery), stringResource(R.string.qardio_info_battery_value, it))
                    }
                    // Empty (nothing listed, or just reset) reads back as null; show "None" for it.
                    settingsGetString(KEY_SCALE_USERS).orEmpty().let { stored ->
                        val other = stringResource(R.string.qardio_info_profile_other)
                        val names = stored.split(',').filter { it.isNotEmpty() }
                            .map { settingsGetString(keyProfileName(it)) ?: other }
                        InfoRow(
                            stringResource(R.string.qardio_info_profiles),
                            names.joinToString(", ").ifEmpty { stringResource(R.string.text_none) },
                        )
                    }
                }
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.qardio_section_scale))
            SettingSwitch(
                title = stringResource(R.string.qardio_config_impedance),
                description = stringResource(R.string.qardio_config_impedance_desc),
                checked = impedance == 1,
            ) {
                impedance = if (it) 1 else 0
                settingsPutInt(KEY_PENDING_IMPEDANCE, impedance)
                flagsPending = true
            }
            SettingSwitch(
                title = stringResource(R.string.qardio_config_haptic),
                description = stringResource(R.string.qardio_config_haptic_desc),
                checked = haptic == 1,
            ) {
                haptic = if (it) 1 else 0
                settingsPutInt(KEY_PENDING_HAPTIC, haptic)
                flagsPending = true
            }
            if (flagsPending) Note(stringResource(R.string.qardio_config_scale_wide_pending), MaterialTheme.colorScheme.primary)

            HorizontalDivider()
            SectionHeader(stringResource(R.string.qardio_section_phone))
            SettingSwitch(
                title = stringResource(R.string.qardio_config_select_on_connect),
                description = stringResource(R.string.qardio_config_select_on_connect_desc),
                checked = selectOnConnect == 1,
            ) {
                selectOnConnect = if (it) 1 else 0
                settingsPutInt(KEY_SELECT_ON_CONNECT, selectOnConnect)
            }
            SettingSwitch(
                title = stringResource(R.string.qardio_config_save_guest),
                description = stringResource(R.string.qardio_config_save_guest_desc),
                checked = saveGuest == 1,
            ) {
                saveGuest = if (it) 1 else 0
                settingsPutInt(KEY_SAVE_GUEST, saveGuest)
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.qardio_section_maintenance))
            SettingAction(
                title = stringResource(R.string.qardio_config_resend_profiles),
                description = stringResource(R.string.qardio_config_resend_profiles_desc),
                action = stringResource(R.string.qardio_action_resend),
                enabled = !resendRequested,
            ) {
                // Bumping the generation makes every app user re-send their profile.
                settingsPutInt(KEY_GENERATION, settingsGetInt(KEY_GENERATION, 0) + 1)
                resendRequested = true
            }
            if (resendRequested) Note(stringResource(R.string.qardio_config_resend_pending), MaterialTheme.colorScheme.primary)
            if (pendingReset == 0) {
                SettingAction(
                    title = stringResource(R.string.qardio_config_reset),
                    description = stringResource(R.string.qardio_config_reset_desc),
                    action = stringResource(R.string.qardio_action_reset),
                ) {
                    pendingReset = RESET_NORMAL
                    settingsPutInt(KEY_PENDING_RESET, RESET_NORMAL)
                }
                SettingAction(
                    title = stringResource(R.string.qardio_config_reset_legacy),
                    description = stringResource(R.string.qardio_config_reset_legacy_desc),
                    action = stringResource(R.string.qardio_action_reset),
                ) {
                    pendingReset = RESET_LEGACY
                    settingsPutInt(KEY_PENDING_RESET, RESET_LEGACY)
                }
            } else {
                SettingAction(
                    title = stringResource(
                        if (pendingReset == RESET_LEGACY) R.string.qardio_config_reset_legacy else R.string.qardio_config_reset
                    ),
                    description = stringResource(R.string.qardio_config_reset_pending),
                    descriptionColor = MaterialTheme.colorScheme.error,
                    action = stringResource(R.string.cancel_button),
                ) {
                    pendingReset = 0
                    settingsPutInt(KEY_PENDING_RESET, 0)
                }
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }

    @Composable
    private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }

    @Composable
    private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Note(description)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun SettingAction(
        title: String,
        description: String,
        action: String,
        enabled: Boolean = true,
        descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick: () -> Unit,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Note(description, descriptionColor)
            }
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}
