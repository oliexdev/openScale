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
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Silvergear Smart Scale 2023 (Tuya BLE, category tzc1, product id v4ju89si).
 * Protocol details and tests: TuyaBleLib / TuyaBleLibTest.
 */
package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.TuyaBleLib
import com.health.openscale.core.bluetooth.libs.TuyaBleProtocolException
import com.health.openscale.core.bluetooth.libs.TuyaDataPoint
import com.health.openscale.core.bluetooth.libs.TuyaSecurity
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Silvergear Smart Scale 2023 — a Tuya BLE scale (category tzc1).
 *
 * The device exposes Tuya service 0xFD50 with write characteristic `…-0001-…-07d0` and notify
 * characteristic `…-0002-…-07d0`. Before it streams datapoints the client must complete the
 * Tuya login/pair handshake; this firmware additionally needs the device-info quirk (payload
 * `00 f3` with protocol version 2) or it never answers. Measurements arrive as DP 1 (`weight`)
 * and DP 9 (`realtime_data`) in 0.01 kg steps, with foot impedance on DP 7 (`body_r`) in ohms.
 *
 * The scale requires device_id / uuid / local_key (from the Tuya cloud, e.g. via tinytuya);
 * they are entered in the driver settings and never leave the device. See [TuyaBleLib].
 */
class SilvergearTuyaHandler : ScaleDeviceHandler() {

    companion object {
        val SERVICE_FD50: UUID = UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE: UUID = UUID.fromString("00000001-0000-1001-8001-00805f9b07d0")
        val CHAR_NOTIFY: UUID = UUID.fromString("00000002-0000-1001-8001-00805f9b07d0")

        internal const val SETTINGS_KEY_DEVICE_ID = "ble/tuya_silvergear/device_id"
        internal const val SETTINGS_KEY_UUID = "ble/tuya_silvergear/uuid"
        internal const val SETTINGS_KEY_LOCAL_KEY = "ble/tuya_silvergear/local_key"
        internal const val SETTINGS_KEY_SEC_KEY = "ble/tuya_silvergear/sec_key"

        /** DP ids carrying weight in 0.01 kg steps on this scale family. */
        private val WEIGHT_DP_IDS = setOf(1, 9)
        private const val IMPEDANCE_DP_ID = 7

        private const val ARM_DELAY_MS = 5_000L
        private const val STABLE_AFTER_MS = 3_000L

        /** First weight sample at or above this counts as "someone is on the scale". */
        private const val MIN_WEIGHT_KG = 5f
        private const val STABILITY_TICK_MS = 250L
        private const val STATUS_POLL_MS = 2_000L
        private const val RESPONSE_TIMEOUT_MS = 15_000L
    }

    private var security: TuyaSecurity? = null
    private var sessionKey: ByteArray? = null
    private var authKey: ByteArray? = null
    private var protocolVersion = 2
    private var wasAlreadyPaired = false

    private val reassembler = TuyaBleLib.TuyaFrameReassembler()
    private val seq = java.util.concurrent.atomic.AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Int>>()
    private val writeMutex = Mutex()

    private var sessionJob: Job? = null
    private var published = false

    private val startTime = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastChange = java.util.concurrent.atomic.AtomicLong(0L)
    private val changeSeen = java.util.concurrent.atomic.AtomicBoolean(false)
    private val weights = ConcurrentHashMap<Int, Float>()
    private val weightTimes = ConcurrentHashMap<Int, Long>()
    private val impedances = ConcurrentHashMap<Int, Float>()
    private val impedanceTimes = ConcurrentHashMap<Int, Long>()
    private val earlyImpedances = ConcurrentHashMap<Int, Float>()
    private val earlyImpedanceTimes = ConcurrentHashMap<Int, Long>()

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Tuya scales keep the default name "TY" unless renamed in the vendor app.
        // No other registered handler claims the exact name "TY".
        // The FD50 service UUID is advertised but not required here: saved-device
        // snapshots can lose parts of the advertisement data.
        if (!device.name.equals("TY", ignoreCase = true)) return null

        val caps = setOf(DeviceCapability.LIVE_WEIGHT_STREAM, DeviceCapability.BODY_COMPOSITION)
        return DeviceSupport(
            displayName = "Silvergear Smart Scale",
            capabilities = caps,
            implemented = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        resetState()

        val deviceId = settingsGetString(SETTINGS_KEY_DEVICE_ID).orEmpty().trim()
        val uuid = settingsGetString(SETTINGS_KEY_UUID).orEmpty().trim()
        val localKey = settingsGetString(SETTINGS_KEY_LOCAL_KEY).orEmpty().trim()
        val secKey = settingsGetString(SETTINGS_KEY_SEC_KEY).orEmpty().trim()
        if (deviceId.isEmpty() || uuid.isEmpty() || localKey.length < 6) {
            logW("Tuya scale: missing credentials (device_id/uuid/local_key) in driver settings")
            userError(R.string.tuya_ble_config_missing)
            return
        }
        val sec = try {
            TuyaSecurity(localKey, secKey.ifEmpty { null })
        } catch (e: IllegalArgumentException) {
            logE("Tuya scale: invalid credentials: ${e.message}")
            userError(R.string.tuya_ble_config_invalid, e.message ?: "")
            return
        }
        security = sec

        setNotifyOn(SERVICE_FD50, CHAR_NOTIFY)
        startTime.set(System.currentTimeMillis())

        sessionJob = scope.launch {
            try {
                performHandshake(deviceId, uuid)
                logI("Tuya scale: handshake complete, waiting for measurement")
                userInfo(R.string.bt_info_step_on_scale)
                launch { startStatusPoll() }
                watchStability()
            } catch (e: Exception) {
                ensureActive()
                logE("Tuya scale: handshake failed: ${e.message}")
                userError(R.string.tuya_ble_handshake_failed, e.message ?: "—")
            }
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return
        val frameBytes = try {
            reassembler.feed(data)
        } catch (e: TuyaBleProtocolException) {
            logW("Tuya scale: chunk reassembly failed: ${e.message}")
            reassembler.reset()
            return
        } ?: return

        val frame = try {
            TuyaBleLib.parseFrame(frameBytes, TuyaBleLib.keyLookup(security ?: return, sessionKey, authKey))
        } catch (e: TuyaBleProtocolException) {
            logW("Tuya scale: frame rejected: ${e.message}")
            return
        }
        dispatch(frame.seqNum, frame.responseTo, frame.code, frame.data)
    }

    override fun onDisconnected() {
        sessionJob?.cancel()
        sessionJob = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        reassembler.reset()
        security = null
        sessionKey = null
        authKey = null
    }

    // ── handshake ─────────────────────────────────────────────────────────────

    private suspend fun performHandshake(deviceId: String, uuid: String) {
        val sec = security ?: return

        // Device-info request with the FD50 quirk; the reply carries the session-key seed.
        protocolVersion = 2
        sendAndWait(TuyaBleLib.CODE_DEVICE_INFO, TuyaBleLib.buildDeviceInfoRequestPayload(), sec.loginKey, sec.loginFlag)
        val key = sessionKey ?: throw TuyaBleProtocolException("no device info received")

        val payload = TuyaBleLib.buildPairPayload(uuid, sec.pairingLoginKey, deviceId)
        val result = sendAndWait(TuyaBleLib.CODE_PAIR, payload, key, sec.sessionFlag)
        if (result == TuyaBleLib.PAIR_ALREADY_PAIRED) {
            wasAlreadyPaired = true
            logI("Tuya scale: already paired")
        } else if (result != 0) {
            throw TuyaBleProtocolException("pairing rejected (code $result)")
        }
    }

    private suspend fun sendAndWait(code: Int, data: ByteArray, key: ByteArray, flag: Int): Int {
        val mySeq = seq.getAndIncrement()
        val deferred = CompletableDeferred<Int>()
        pending[mySeq] = deferred
        try {
            sendPackets(mySeq, code, data, responseTo = 0, key, flag)
            val result = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { deferred.await() }
                ?: throw TuyaBleProtocolException("timeout waiting for response to 0x%04x".format(code))
            if (result != 0 && code != TuyaBleLib.CODE_PAIR && code != TuyaBleLib.CODE_DEVICE_STATUS) {
                throw TuyaBleProtocolException("device answered with error $result")
            }
            return result
        } finally {
            pending.remove(mySeq)
        }
    }

    private suspend fun sendPackets(
        seqNum: Int,
        code: Int,
        data: ByteArray,
        responseTo: Int,
        key: ByteArray,
        flag: Int,
    ) {
        val packets = TuyaBleLib.buildPackets(
            seqNum, code, data, responseTo, key, flag, protocolVersion, TuyaBleLib.randomIv()
        )
        writeMutex.withLock {
            for (packet in packets) {
                logD("TX seq=$seqNum code=0x%04x ${packet.toHexPreview(24)}".format(code))
                writeTo(SERVICE_FD50, CHAR_WRITE, packet, withResponse = false)
            }
        }
    }

    private fun respond(code: Int, data: ByteArray, responseTo: Int) {
        val key = sessionKey ?: return
        val sec = security ?: return
        val mySeq = seq.getAndIncrement()
        scope.launch {
            try {
                sendPackets(mySeq, code, data, responseTo, key, sec.sessionFlag)
            } catch (e: Exception) {
                logD("Tuya scale: ACK 0x%04x failed: %s".format(code, e.message))
            }
        }
    }

    // ── frame dispatch ────────────────────────────────────────────────────────

    private fun dispatch(seqNum: Int, responseTo: Int, code: Int, data: ByteArray) {
        logD("RX seq=$seqNum code=0x%04x responseTo=$responseTo len=${data.size}".format(code))
        var result = 0
        when (code) {
            TuyaBleLib.CODE_DEVICE_INFO -> {
                try {
                    val info = TuyaBleLib.parseDeviceInfo(data)
                    protocolVersion = info.protocolMajor
                    sessionKey = security?.sessionKey(info.deviceRandom)
                    authKey = info.authKey
                    logI(
                        "Tuya scale: version ${info.deviceVersion}, protocol ${info.protocolVersionString}, " +
                            "hw ${info.hardwareVersion}, bound=${info.bound}"
                    )
                } catch (e: TuyaBleProtocolException) {
                    logW("Tuya scale: bad device info: ${e.message}")
                }
            }
            TuyaBleLib.CODE_PAIR -> {
                if (data.size == 1) {
                    result = data[0].toInt() and 0xFF
                    if (result != 0 && result != TuyaBleLib.PAIR_ALREADY_PAIRED) {
                        logW("Tuya scale: pairing rejected, code $result")
                    }
                }
            }
            TuyaBleLib.CODE_DEVICE_STATUS -> {
                if (data.size == 1) result = data[0].toInt() and 0xFF
            }
            TuyaBleLib.CODE_TIME1_REQ -> respondTime1(seqNum)
            TuyaBleLib.CODE_TIME2_REQ -> respondTime2(seqNum)
            TuyaBleLib.CODE_RECEIVE_DP -> {
                handleDatapoints(data, 0, 1, 0, System.currentTimeMillis() / 1000)
                respond(code, ByteArray(0), seqNum)
            }
            TuyaBleLib.CODE_RECEIVE_SIGN_DP -> {
                if (data.size >= 3) {
                    val dpSeq = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    val flags = data[2].toInt() and 0xFF
                    handleDatapoints(data, 3, 1, flags, System.currentTimeMillis() / 1000)
                    respond(
                        code,
                        byteArrayOf(
                            ((dpSeq ushr 8) and 0xFF).toByte(),
                            (dpSeq and 0xFF).toByte(),
                            flags.toByte(),
                            0,
                        ),
                        seqNum,
                    )
                }
            }
            TuyaBleLib.CODE_RECEIVE_TIME_DP -> {
                try {
                    val (seconds, pos) = TuyaBleLib.parseTimestamp(data, 0)
                    handleDatapoints(data, pos, 1, 0, seconds)
                    respond(code, ByteArray(0), seqNum)
                } catch (e: TuyaBleProtocolException) {
                    logW("Tuya scale: bad time-DP frame: ${e.message}")
                }
            }
            TuyaBleLib.CODE_RECEIVE_SIGN_TIME_DP -> {
                if (data.size >= 3) {
                    try {
                        val dpSeq = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        val flags = data[2].toInt() and 0xFF
                        val (seconds, pos) = TuyaBleLib.parseTimestamp(data, 3)
                        handleDatapoints(data, pos, 1, flags, seconds)
                        respond(
                            code,
                            byteArrayOf(
                                ((dpSeq ushr 8) and 0xFF).toByte(),
                                (dpSeq and 0xFF).toByte(),
                                flags.toByte(),
                                0,
                            ),
                            seqNum,
                        )
                    } catch (e: TuyaBleProtocolException) {
                        logW("Tuya scale: bad sign-time-DP frame: ${e.message}")
                    }
                }
            }
            TuyaBleLib.CODE_RECEIVE_DP_V4 -> {
                if (data.size < 7 || data[0].toInt() != 0) {
                    logW("Tuya scale: bad v4 DP frame (len=${data.size})")
                } else {
                    val sendFlags = data[5].toInt() and 0xFF
                    val mode = data[6].toInt() and 0xFF
                    handleDatapoints(data, 7, 2, mode, System.currentTimeMillis() / 1000)
                    if (sendFlags and 0x80 == 0) {
                        respond(code, data.copyOfRange(0, 7) + byteArrayOf(0), seqNum)
                    }
                }
            }
            TuyaBleLib.CODE_RECEIVE_TIME_DP_V4 -> {
                if (data.size < 8 || data[0].toInt() != 0) {
                    logW("Tuya scale: bad v4 time-DP frame (len=${data.size})")
                } else {
                    val sendFlags = data[5].toInt() and 0xFF
                    val mode = data[6].toInt() and 0xFF
                    try {
                        val (seconds, pos) = TuyaBleLib.parseTimestamp(data, 7)
                        handleDatapoints(data, pos, 2, mode, seconds)
                        if (sendFlags and 0x80 == 0) {
                            respond(code, data.copyOfRange(0, 7) + byteArrayOf(0), seqNum)
                        }
                    } catch (e: TuyaBleProtocolException) {
                        logW("Tuya scale: bad v4 time-DP frame: ${e.message}")
                    }
                }
            }
            else -> logD("Tuya scale: ignoring unknown code 0x%04x".format(code))
        }

        if (responseTo != 0) {
            pending.remove(responseTo)?.complete(result)
        }
    }

    // ── datapoints / stability ────────────────────────────────────────────────

    private fun handleDatapoints(data: ByteArray, start: Int, lengthSize: Int, flags: Int, timestampSeconds: Long) {
        val dps = try {
            TuyaBleLib.parseDatapoints(data, start, lengthSize, flags, timestampSeconds)
        } catch (e: TuyaBleProtocolException) {
            logW("Tuya scale: bad datapoints: ${e.message}")
            return
        }
        for (dp in dps) {
            logD("dp id=${dp.id} type=${dp.type} value=${dp.value} ts=${dp.timestampSeconds} flags=${dp.flags}")
            onDatapoint(dp)
        }
    }

    private fun onDatapoint(dp: TuyaDataPoint) {
        val now = System.currentTimeMillis()
        when (dp.id) {
            in WEIGHT_DP_IDS -> {
                val raw = (dp.value as? Number)?.toInt() ?: return
                val kg = raw / 100f
                val previous = weights.put(dp.id, kg)
                weightTimes[dp.id] = now
                // A constant weight (user already standing when we connected) never changes,
                // so the first plausible sample must count as a change as well.
                val firstPlausible = previous == null && kg >= MIN_WEIGHT_KG
                if (firstPlausible || (previous != null && previous != kg)) {
                    lastChange.set(now)
                    changeSeen.set(true)
                }
                val armed = now - startTime.get() >= ARM_DELAY_MS
                logD("weight DP ${dp.id}: $kg kg" + if (armed) "" else " (not armed yet)")
            }
            IMPEDANCE_DP_ID -> {
                val raw = (dp.value as? Number)?.toInt() ?: return
                if (now - startTime.get() >= ARM_DELAY_MS) {
                    impedances[dp.id] = raw.toFloat()
                    impedanceTimes[dp.id] = now
                    logD("impedance DP ${dp.id}: $raw ohm")
                } else if (raw > 0) {
                    // Impedance measured before the arm delay (user already standing on the
                    // scale at connect time) — keep it as a publish-time fallback.
                    earlyImpedances[dp.id] = raw.toFloat()
                    earlyImpedanceTimes[dp.id] = now
                    logD("impedance DP ${dp.id}: $raw ohm (early, kept as fallback)")
                } else {
                    logD("impedance DP ${dp.id}: $raw ohm (stale, ignored)")
                }
            }
            else -> logD("DP ${dp.id} (type ${dp.type}): ${dp.value}")
        }
    }

    private suspend fun CoroutineScope.watchStability() {
        while (isActive && !published) {
            delay(STABILITY_TICK_MS)
            val now = System.currentTimeMillis()
            if (weights.isEmpty() || !changeSeen.get()) continue
            val armedAt = startTime.get() + ARM_DELAY_MS
            if (now < armedAt) continue
            val reference = maxOf(lastChange.get(), armedAt)
            if (now - reference >= STABLE_AFTER_MS) {
                publishStable()
            }
        }
    }

    private fun publishStable() {
        if (published) return
        published = true

        val newestWeightDp = weightTimes.entries.maxByOrNull { it.value }?.key
        val kg = newestWeightDp?.let { weights[it] } ?: return
        val impedance = impedanceTimes.entries.maxByOrNull { it.value }?.key?.let { impedances[it] }
            ?: earlyImpedanceTimes.entries.maxByOrNull { it.value }?.key?.let { earlyImpedances[it] }

        logI("Tuya scale: stable measurement ${kg} kg (impedance=${impedance ?: "n/a"} ohm)")
        val user = currentAppUser()
        publish(ScaleMeasurement().apply {
            dateTime = Date()
            userId = user.id
            this[MeasurementType.WEIGHT] = Kg(kg)
            if (impedance != null && impedance > 0f) {
                this[MeasurementType.IMPEDANCE] = Ohm(impedance)
            }
        })
        requestDisconnect()
    }

    private suspend fun CoroutineScope.startStatusPoll() {
        val sec = security ?: return
        val key = sessionKey ?: return
        while (isActive && !published) {
            delay(STATUS_POLL_MS)
            try {
                sendAndWait(TuyaBleLib.CODE_DEVICE_STATUS, ByteArray(0), key, sec.sessionFlag)
            } catch (e: Exception) {
                logD("Tuya scale: status poll failed: ${e.message}")
            }
        }
    }

    // ── time sync replies ─────────────────────────────────────────────────────

    private fun timezoneUnits(): Int = TimeZone.getDefault().rawOffset / 36_000 // 15-min units

    private fun respondTime1(responseTo: Int) {
        val millis = System.currentTimeMillis().toString().toByteArray(Charsets.US_ASCII)
        val tz = timezoneUnits()
        respond(
            TuyaBleLib.CODE_TIME1_REQ,
            millis + byteArrayOf(((tz ushr 8) and 0xFF).toByte(), (tz and 0xFF).toByte()),
            responseTo,
        )
    }

    private fun respondTime2(responseTo: Int) {
        val cal = Calendar.getInstance()
        val tz = timezoneUnits()
        // Tuya weekday: Monday = 0; Calendar: Sunday = 1 … Saturday = 7
        val wday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val payload = byteArrayOf(
            (cal.get(Calendar.YEAR) % 100).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
            wday.toByte(),
            ((tz ushr 8) and 0xFF).toByte(),
            (tz and 0xFF).toByte(),
        )
        respond(TuyaBleLib.CODE_TIME2_REQ, payload, responseTo)
    }

    // ── config UI ─────────────────────────────────────────────────────────────

    @Composable
    override fun DeviceConfigurationUi() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.tuya_ble_config_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CredentialField(
                label = stringResource(R.string.tuya_ble_device_id_label),
                key = SETTINGS_KEY_DEVICE_ID,
                minLength = 1,
            )
            CredentialField(
                label = stringResource(R.string.tuya_ble_uuid_label),
                key = SETTINGS_KEY_UUID,
                minLength = 1,
            )
            CredentialField(
                label = stringResource(R.string.tuya_ble_local_key_label),
                key = SETTINGS_KEY_LOCAL_KEY,
                minLength = 6,
                mask = true,
            )
            CredentialField(
                label = stringResource(R.string.tuya_ble_sec_key_label),
                key = SETTINGS_KEY_SEC_KEY,
                minLength = 0,
                mask = true,
            )
        }
    }

    @Composable
    private fun CredentialField(label: String, key: String, minLength: Int, mask: Boolean = false) {
        val persistedValue = settingsGetString(key) ?: ""
        var inputValue by remember(persistedValue) { mutableStateOf(persistedValue) }
        var lastSavedValue by remember(persistedValue) { mutableStateOf(persistedValue) }
        val isValid = inputValue.length >= minLength
        val showError = inputValue.isNotEmpty() && !isValid
        val isSaved = inputValue == lastSavedValue && (isValid || inputValue.isEmpty())

        LaunchedEffect(inputValue) {
            when {
                inputValue.isEmpty() && lastSavedValue.isNotEmpty() -> {
                    settingsPutString(key, "")
                    lastSavedValue = ""
                }
                isValid && inputValue != lastSavedValue -> {
                    settingsPutString(key, inputValue)
                    lastSavedValue = inputValue
                }
            }
        }

        OutlinedTextField(
            value = inputValue,
            onValueChange = { newValue ->
                val filtered = if (mask) newValue.filter { !it.isWhitespace() && it.code >= 0x20 } else newValue
                inputValue = filtered
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            singleLine = true,
            isError = showError,
            visualTransformation = if (mask) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            supportingText = {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = when {
                            showError -> stringResource(R.string.tuya_ble_field_too_short, minLength)
                            isSaved && inputValue.isNotEmpty() -> stringResource(R.string.saved)
                            else -> ""
                        },
                        color = if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }

    private fun resetState() {
        published = false
        wasAlreadyPaired = false
        protocolVersion = 2
        sessionKey = null
        authKey = null
        reassembler.reset()
        seq.set(1)
        pending.clear()
        weights.clear()
        weightTimes.clear()
        impedances.clear()
        impedanceTimes.clear()
        earlyImpedances.clear()
        earlyImpedanceTimes.clear()
        changeSeen.set(false)
        lastChange.set(0L)
        startTime.set(System.currentTimeMillis())
    }
}
