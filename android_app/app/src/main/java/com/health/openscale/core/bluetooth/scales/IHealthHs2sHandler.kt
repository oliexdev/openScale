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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.IHealthHs2sLib
import com.health.openscale.core.bluetooth.libs.IHealthHs2sLib.Variant
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

/**
 * iHealth HS2S family: iHealth HS2S, iHealth HS2S Pro and the Wyze Scale (WHSCL1).
 *
 * Protocol and authentication are documented in [IHealthHs2sLib]. The iHealth models derive
 * their session key from the scale MAC and a model key shipped in iHealth's app. The Wyze Scale
 * uses a per-scale key that Wyze serves from its cloud, so the user enters it once in the device
 * settings (fetched with the helper script linked from the issue).
 *
 * Session, following the vendor apps: FA/FB/FC/FD handshake → time (0x10) → update user (0x21)
 * → select user (0x23, retried after 0x24 if the scale is busy) → offline records (0x31, paged,
 * then deleted with 0x32) → live weight (0x40/0x41) and result (0x42).
 */
class IHealthHs2sHandler : ScaleDeviceHandler() {

    companion object {
        private const val SETTINGS_KEY_DEVICE_KEY = "hs2s_device_key"
        private const val DISCONNECT_DELAY_MS = 1_000L
    }

    private var variant: Variant? = null
    private val assembler = IHealthHs2sLib.FrameAssembler()
    private val random = SecureRandom()
    private var seq = 1
    private var r1 = ByteArray(0)
    private var scaleUserId = ByteArray(0)
    private var offlineRead = 0
    private var pendingOnlineUser = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val v = device.serviceUuids.firstNotNullOfOrNull { IHealthHs2sLib.variantForService(it) }
            ?: when {
                device.name.equals("WHSCL1", ignoreCase = true) -> Variant.WYZE_WHSCL1
                device.name.uppercase().startsWith("HS2S PRO") -> Variant.HS2S_PRO
                device.name.uppercase().startsWith("HS2S") -> Variant.HS2S
                else -> null
            }
            ?: return null
        variant = v
        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.LIVE_WEIGHT_STREAM,
        )
        return DeviceSupport(
            displayName = v.displayName,
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    @Composable
    override fun DeviceConfigurationUi() {
        val persistedValue = settingsGetString(SETTINGS_KEY_DEVICE_KEY) ?: ""
        var inputValue by remember(persistedValue) { mutableStateOf(persistedValue) }
        var lastSavedValue by remember { mutableStateOf(persistedValue) }

        val isValid = IHealthHs2sLib.parseHexKey(inputValue) != null
        val showError = inputValue.isNotEmpty() && !isValid
        val isSaved = isValid && inputValue == lastSavedValue

        LaunchedEffect(inputValue) {
            if (isValid && inputValue != lastSavedValue) {
                settingsPutString(SETTINGS_KEY_DEVICE_KEY, inputValue)
                lastSavedValue = inputValue
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.hs2s_device_key_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = inputValue,
                onValueChange = { newValue ->
                    val filtered = newValue.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.lowercase()
                    if (filtered.length <= 32) inputValue = filtered
                },
                label = { Text(stringResource(R.string.hs2s_device_key_label)) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                singleLine = true,
                isError = showError,
                supportingText = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        when {
                            showError -> Text(stringResource(R.string.hs2s_device_key_error))
                            isSaved -> Text(stringResource(R.string.saved), color = MaterialTheme.colorScheme.primary)
                            else -> Spacer(modifier = Modifier.weight(1f))
                        }
                        Text("${inputValue.length}/32")
                    }
                }
            )
        }
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        val v = variant ?: Variant.entries.firstOrNull { hasCharacteristic(it.service, it.notifyChar) }
        if (v == null) {
            logW("No HS2S-family service found")
            requestDisconnect()
            return
        }
        variant = v
        seq = 1
        offlineRead = 0
        pendingOnlineUser = false
        assembler.reset()
        scaleUserId = IHealthHs2sLib.userIdFor(user.id)

        setNotifyOn(v.service, v.notifyChar)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        r1 = ByteArray(16) { alphabet[random.nextInt(alphabet.length)].code.toByte() }
        send(IHealthHs2sLib.CMD_AUTH_R1, r1)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        val v = variant ?: return
        if (characteristic != v.notifyChar) return
        for (frame in assembler.feed(data)) handleFrame(v, frame, user)
    }

    override fun onDisconnected() {
        assembler.reset()
    }

    // --- Protocol ----------------------------------------------------------------------------

    private fun handleFrame(v: Variant, frame: IHealthHs2sLib.Frame, user: ScaleUser) {
        val p = frame.payload
        when (frame.cmd) {
            IHealthHs2sLib.CMD_AUTH_FB -> answerChallenge(v, p)
            IHealthHs2sLib.CMD_AUTH_OK -> {
                logD("Authenticated")
                send(IHealthHs2sLib.CMD_DEVICE_INFO, IHealthHs2sLib.be32(System.currentTimeMillis() / 1000))
            }
            IHealthHs2sLib.CMD_AUTH_FAIL -> {
                userWarn(R.string.bt_hs2s_auth_failed)
                requestDisconnect()
            }
            IHealthHs2sLib.CMD_DEVICE_INFO -> {
                if (p.isNotEmpty()) logD("Battery ${p.last().toInt() and 0xFF} %")
                send(IHealthHs2sLib.CMD_USER_UPDATE, userRecord(v, user))
            }
            IHealthHs2sLib.CMD_USER_UPDATE -> selectOnlineUser(v, user)
            IHealthHs2sLib.CMD_USER_ONLINE -> {
                if (p.isNotEmpty() && p[0].toInt() == 0) {
                    pendingOnlineUser = false
                    send(IHealthHs2sLib.CMD_OFFLINE_DATA, IHealthHs2sLib.offlineDataRequest(scaleUserId, offlineRead))
                } else {
                    // The scale is still finishing a measurement; it sends 0x24 when done.
                    pendingOnlineUser = true
                }
            }
            IHealthHs2sLib.CMD_MEASURE_FINISHED -> {
                send(IHealthHs2sLib.CMD_MEASURE_FINISHED)
                if (pendingOnlineUser) selectOnlineUser(v, user)
            }
            IHealthHs2sLib.CMD_OFFLINE_DATA -> {
                val batch = IHealthHs2sLib.parseOfflineData(v, p)
                batch.records.forEach { publishRecord(v, it, user) }
                offlineRead += batch.records.size
                when {
                    batch.moreAvailable ->
                        send(IHealthHs2sLib.CMD_OFFLINE_DATA, IHealthHs2sLib.offlineDataRequest(scaleUserId, offlineRead))
                    offlineRead > 0 -> send(IHealthHs2sLib.CMD_OFFLINE_DELETE, scaleUserId.copyOf(16))
                }
            }
            IHealthHs2sLib.CMD_OFFLINE_DELETE -> logD("Offline records deleted")
            IHealthHs2sLib.CMD_LIVE_WEIGHT -> Unit
            IHealthHs2sLib.CMD_STABLE_WEIGHT -> send(IHealthHs2sLib.CMD_STABLE_WEIGHT)
            IHealthHs2sLib.CMD_RESULT -> {
                send(IHealthHs2sLib.CMD_RESULT)
                IHealthHs2sLib.parseResult(v, p)?.let { publishRecord(v, it, user) }
                // Let the ack go out first; an unacked result comes back as an offline record.
                scope.launch {
                    delay(DISCONNECT_DELAY_MS)
                    requestDisconnect()
                }
            }
            else -> logD("Unhandled cmd 0x%02X %s".format(frame.cmd, p.toHexPreview(24)))
        }
    }

    private fun answerChallenge(v: Variant, payload: ByteArray) {
        val challenge = IHealthHs2sLib.parseAuthChallenge(payload) ?: run {
            logW("Short auth challenge ${payload.toHexPreview(24)}")
            requestDisconnect()
            return
        }
        val key = if (v.masterKey != null) {
            IHealthHs2sLib.deriveKey(challenge.seed, v.masterKey)
        } else {
            settingsGetString(SETTINGS_KEY_DEVICE_KEY)?.let { IHealthHs2sLib.parseHexKey(it) } ?: run {
                userWarn(R.string.bt_hs2s_missing_device_key)
                requestDisconnect()
                return
            }
        }
        val answer = IHealthHs2sLib.answerChallenge(challenge, r1, key) ?: run {
            userWarn(R.string.bt_hs2s_wrong_device_key)
            requestDisconnect()
            return
        }
        send(IHealthHs2sLib.CMD_AUTH_FC, answer)
    }

    private fun selectOnlineUser(v: Variant, user: ScaleUser) {
        send(IHealthHs2sLib.CMD_USER_ONLINE, byteArrayOf(1) + userRecord(v, user))
    }

    private fun userRecord(v: Variant, user: ScaleUser): ByteArray {
        val lastWeight = lastMeasurementFor(user.id)?.get(MeasurementType.WEIGHT)?.value
            ?: user.initialWeight
        return IHealthHs2sLib.buildUserRecord(
            variant = v,
            userId = scaleUserId,
            epochSeconds = System.currentTimeMillis() / 1000,
            weightKg = lastWeight,
            male = user.gender.isMale(),
            age = user.age,
            heightCm = user.bodyHeight.toInt(),
        )
    }

    private fun publishRecord(v: Variant, r: IHealthHs2sLib.Record, user: ScaleUser) {
        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = if (r.timeValid) Date(r.epochSeconds * 1000) else Date()
            this[MeasurementType.WEIGHT] = Kg(r.weightKg)
            r.impedances.firstOrNull()?.let { this[MeasurementType.IMPEDANCE] = Ohm(it.toFloat()) }
        }
        val fat = r.fatPercent
        if (r.hasBodyComposition && fat != null && r.weightKg > 0f) {
            m[MeasurementType.BODY_FAT] = Percent(fat)
            r.waterPercent?.let { m[MeasurementType.WATER] = Percent(it) }
            r.muscleKg?.let { m[MeasurementType.MUSCLE] = Percent(it / r.weightKg * 100f) }
            r.boneKg?.let { m[MeasurementType.BONE] = Kg(it) }
            r.proteinPercent?.let { m[MeasurementType.PROTEIN] = Percent(it) }
            m[MeasurementType.LBM] = Kg(r.weightKg * (100f - fat) / 100f)
            m[MeasurementType.BMR] = Kcal(IHealthHs2sLib.wyzeBmr(r.weightKg, fat))
            m[MeasurementType.VISCERAL_FAT] = r.visceralFat
                ?: IHealthHs2sLib.wyzeVisceralFat(r.weightKg, fat, r.heightCm.toFloat())
        }
        publish(m)
    }

    private fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        val v = variant ?: return
        val frame = IHealthHs2sLib.buildFrame(seq, cmd, payload)
        seq += 2
        IHealthHs2sLib.chunk(frame).forEach { writeTo(v.service, v.writeChar, it, withResponse = false) }
    }
}
