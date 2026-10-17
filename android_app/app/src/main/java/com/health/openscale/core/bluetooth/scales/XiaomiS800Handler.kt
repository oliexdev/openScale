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
 * Ported from ble-scale-sync (GPL-3.0, © Kristián Partl):
 *   src/scales/xiaomi-s800.ts, upstream commit 5ee2c2e (#232).
 * NOTE: the BLE-advertisement wiring below could not be verified on hardware; the
 * decrypt/parse core is covered by XiaomiS800LibTest. Needs an on-device check with a real
 * bind key before release.
 */
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.XiaomiS800Lib
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import com.health.openscale.core.data.Kg

/**
 * Xiaomi Mijia 8-electrode Body Composition Scale S800 (xiaomi.scales.ms116) and Xiaomi Smart
 * Scale S200 (MJTZC02YM).
 *
 * Broadcast-only: the scales advertise AES-128-CCM encrypted MiBeacon v5 frames in service
 * data 0xFE95. The weigh-in object 0x4e16 carries the weight; the per-device bind key from the
 * Mi cloud is required to decrypt it (configure it in the device settings, same as the S400).
 * Segmental body composition lives only behind the encrypted Mi-auth GATT path (out of scope),
 * so this handler publishes weight; openScale derives BMI from the user profile. The S200 has
 * no electrodes and reports weight only.
 *
 * Decode logic and the round-trip test live in
 * [com.health.openscale.core.bluetooth.libs.XiaomiS800Lib].
 */
class XiaomiS800Handler : ScaleDeviceHandler() {

    companion object {
        private const val SETTINGS_KEY_BIND_KEY = "s800_bind_key"
        private const val SETTINGS_KEY_S200_LAST_STAMP = "s200_last_stamp"

        private val KNOWN_NAME_PATTERNS = listOf("MIJIA SCALE S800", "MS116")
        private val S200_NAME_PATTERNS = listOf("SCALE S200", "MJTZC02YM")

        private val SERVICE_UUID_FE95 =
            ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    }

    private var warnedMissingConfig = false
    /** Real device MAC (frame byte order) cached from a MAC-included frame. */
    private var cachedMac: ByteArray? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.ROOT)
        // Some S200 units advertise no name at all; their product id is in the FE95 service data.
        val fe95 = device.serviceData[SERVICE_UUID_FE95.uuid]
        val productId = fe95?.takeIf { it.size >= 4 }
            ?.let { (it[2].toInt() and 0xFF) or ((it[3].toInt() and 0xFF) shl 8) }
        if (S200_NAME_PATTERNS.any { name.contains(it) } || productId in XiaomiS800Lib.S200_PIDS) {
            // Only the final weight is broadcast, so not even a live weight stream.
            return DeviceSupport(
                displayName = "Xiaomi Smart Scale S200",
                capabilities = emptySet(),
                implemented = emptySet(),
                tuningProfile = TuningProfile.Conservative,
                linkMode = LinkMode.BROADCAST_ONLY
            )
        }
        val isS800 = KNOWN_NAME_PATTERNS.any { name.contains(it) }
        if (!isS800) return null

        return DeviceSupport(
            displayName = "Xiaomi Body Composition Scale S800",
            // Body composition only travels over the encrypted Mi-auth GATT path (see class doc);
            // the broadcast carries the final weight only.
            capabilities = setOf(DeviceCapability.BODY_COMPOSITION),
            implemented = emptySet(),
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.BROADCAST_ONLY
        )
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val bindKeyHex = settingsGetString(SETTINGS_KEY_BIND_KEY)
        if (bindKeyHex.isNullOrEmpty() || !bindKeyHex.matches(Regex("^[0-9a-fA-F]{32}$"))) {
            if (!warnedMissingConfig) {
                logW("S800: missing or invalid bind key. Configure it in Settings.")
                userWarn(R.string.bt_s400_missing_bind_key)
                warnedMissingConfig = true
            }
            return BroadcastAction.IGNORED
        }
        val bindKey = hexToBytes(bindKeyHex)

        val frame = extractServiceData(result) ?: return BroadcastAction.IGNORED
        val productId = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)

        // Cache the real MAC from any MAC-included frame so MAC-omitted rich frames can build
        // the AES-CCM nonce; the S200 never includes it in measurement frames.
        val frameMac = XiaomiS800Lib.macFrameOrderFromFrame(frame)
        if (frameMac != null) cachedMac = frameMac
        val mac = frameMac ?: cachedMac
            ?: XiaomiS800Lib.macFrameOrderFromAddress(result.device.address)
            ?: return BroadcastAction.CONSUMED_KEEP_SCANNING

        val decrypted = XiaomiS800Lib.decryptMiBeaconV5(frame, bindKey, mac)
            ?: return BroadcastAction.CONSUMED_KEEP_SCANNING
        val weightKg = XiaomiS800Lib.parseWeightKg(decrypted, productId)
            ?: return BroadcastAction.CONSUMED_KEEP_SCANNING

        // The S200 keeps re-broadcasting its last reading for a while, so the next scan can
        // start with an old weigh-in; its own timestamp tells them apart.
        if (productId in XiaomiS800Lib.S200_PIDS) {
            val stamp = XiaomiS800Lib.parseS200TimestampSec(decrypted)?.toString()
            if (stamp != null && stamp == settingsGetString(SETTINGS_KEY_S200_LAST_STAMP)) {
                return BroadcastAction.CONSUMED_KEEP_SCANNING
            }
            stamp?.let { settingsPutString(SETTINGS_KEY_S200_LAST_STAMP, it) }
        }

        val m = ScaleMeasurement().apply {
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            userId = user.id
        }
        publish(m)
        return BroadcastAction.CONSUMED_STOP
    }

    /** FE95 service data value (Android already strips the 16-bit UUID prefix). */
    private fun extractServiceData(result: ScanResult): ByteArray? {
        val data = result.scanRecord?.serviceData?.get(SERVICE_UUID_FE95) ?: return null
        if (data.size < 4) return null
        val pid = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        return data.takeIf { pid == XiaomiS800Lib.S800_PID || pid in XiaomiS800Lib.S200_PIDS }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Composable
    override fun DeviceConfigurationUi() {
        // Reuses the S400 bind-key strings — the concept (32-hex Mi-cloud key) is identical.
        val persistedValue = settingsGetString(SETTINGS_KEY_BIND_KEY) ?: ""
        var inputValue by remember(persistedValue) { mutableStateOf(persistedValue) }
        var lastSavedValue by remember { mutableStateOf(persistedValue) }

        val isValid = inputValue.length == 32
        val showError = inputValue.isNotEmpty() && !isValid
        val isSuccessfullySaved = isValid && inputValue == lastSavedValue && inputValue.isNotEmpty()

        LaunchedEffect(inputValue) {
            if (isValid && inputValue != lastSavedValue) {
                settingsPutString(SETTINGS_KEY_BIND_KEY, inputValue)
                lastSavedValue = inputValue
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.s400_bind_key_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = inputValue,
                onValueChange = { newValue ->
                    val filtered = newValue.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.lowercase()
                    if (filtered.length <= 32) inputValue = filtered
                },
                label = { Text(stringResource(R.string.s400_bind_key_label)) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                singleLine = true,
                isError = showError,
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        when {
                            showError -> Text(stringResource(R.string.s400_bind_key_error))
                            isSuccessfullySaved -> Text(
                                text = stringResource(R.string.saved),
                                color = MaterialTheme.colorScheme.primary
                            )
                            else -> Spacer(modifier = Modifier.weight(1f))
                        }
                        Text("${inputValue.length}/32")
                    }
                }
            )
        }
    }
}
