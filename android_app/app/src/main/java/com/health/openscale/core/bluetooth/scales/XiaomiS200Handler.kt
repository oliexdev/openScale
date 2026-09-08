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
 * Protocol reimplemented from Home Assistant's `xiaomi-ble` library (MIT licensed,
 * https://github.com/Bluetooth-Devices/xiaomi-ble): device identification from
 * `src/xiaomi_ble/devices.py`, frame/object parsing from `obj4e16` in `src/xiaomi_ble/parser.py`.
 * See [com.health.openscale.core.bluetooth.libs.XiaomiS200Lib] for the decode logic and its
 * round-trip test against that library's own upstream test vector.
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
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.XiaomiS200Lib
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Xiaomi Smart Scale S200 (MJTZC02YM).
 *
 * Broadcast-only: the scale advertises AES-128-CCM encrypted MiBeacon v5 frames in service
 * data 0xFE95. Unlike the S400/S800, its measurement object (`0x4e16`) carries **only** weight
 * plus a Mi Home profile id and the scale's own timestamp — no impedance, so no body
 * composition. The per-device bind key from the Mi cloud is required to decrypt it (configure
 * it in the device settings, same as the S400/S800).
 *
 * The scale also emits an unencrypted idle beacon with no object at all; [onAdvertisement]
 * simply keeps scanning until a real, encrypted measurement frame arrives, which requires
 * someone standing on the scale.
 */
class XiaomiS200Handler : ScaleDeviceHandler() {

    companion object {
        private const val SETTINGS_KEY_BIND_KEY = "s200_bind_key"

        private val KNOWN_NAME_PATTERNS = listOf("SCALE S200", "MJTZC02YM")

        private val SERVICE_UUID_FE95 =
            ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        private val SERVICE_UUID_FE95_JAVA: UUID = SERVICE_UUID_FE95.uuid
    }

    private var warnedMissingConfig = false

    /** Publish at most once per scan session; reset when the session ends. */
    private var armed = true

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.ROOT)
        val serviceData = device.serviceData[SERVICE_UUID_FE95_JAVA]
        val productId = serviceData?.let { XiaomiS200Lib.parseProductId(it) }
        val isS200 = KNOWN_NAME_PATTERNS.any { name.contains(it) } ||
            (productId != null && productId in XiaomiS200Lib.PRODUCT_IDS)

        if (!isS200) return null

        // No capability is declared: the S200 has no impedance electrodes (no body composition),
        // and — unlike GATT scales — it cannot stream a live/in-progress reading over broadcast.
        // Its 0x4e16 object only ever appears with a finalized, non-zero weight; an in-progress
        // weigh-in is either the objectless idle beacon or a zero-weight packet that
        // XiaomiS200Lib.parseMeasurement discards. There is no live value to surface.
        return DeviceSupport(
            displayName = "Xiaomi Smart Scale S200",
            capabilities = emptySet(),
            implemented = emptySet(),
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.BROADCAST_ONLY
        )
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val bindKeyHex = settingsGetString(SETTINGS_KEY_BIND_KEY)
        if (bindKeyHex.isNullOrEmpty() || !bindKeyHex.matches(Regex("^[0-9a-fA-F]{32}$"))) {
            if (!warnedMissingConfig) {
                logW("S200: missing or invalid bind key. Configure it in Settings.")
                userWarn(R.string.bt_s200_missing_bind_key)
                warnedMissingConfig = true
            }
            return BroadcastAction.IGNORED
        }
        val bindKey = hexToBytes(bindKeyHex)

        val serviceData = result.scanRecord?.serviceData?.get(SERVICE_UUID_FE95)
            ?: return BroadcastAction.IGNORED

        if (!armed) return BroadcastAction.CONSUMED_STOP

        // The S200's measurement frames omit the in-frame MAC; the scan result's own device
        // address is the fallback the decrypt routine needs to build the AES-CCM nonce.
        val decrypted = XiaomiS200Lib.decryptMiBeaconV5(serviceData, bindKey, result.device.address)
            ?: return BroadcastAction.CONSUMED_KEEP_SCANNING // unencrypted idle beacon, or wrong key/MAC
        val measurement = XiaomiS200Lib.parseMeasurement(decrypted)
            ?: return BroadcastAction.CONSUMED_KEEP_SCANNING // non-final packet (zero weight) or wrong object

        // Use the phone's receipt time, not the scale's embedded timestampSec: the S200 keeps
        // re-broadcasting its last reading (each re-encrypted under a fresh nonce, so the
        // adapter's raw-byte dedup can't catch it as a repeat) for a while after a weigh-in ends.
        // A new session's first decrypted frame can be that stale object; publishing it under the
        // *scale's* timestamp would collide with the already-stored row on the measurements
        // table's UNIQUE(userId, timestamp) index and be silently dropped by Room, even though
        // the app reports success. The phone's clock is always fresh per session, so a genuinely
        // new weigh-in can never collide with a previous one. Matches every other broadcast
        // handler in this codebase (Picooc, S400, S800, ...).
        val m = ScaleMeasurement().apply {
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(measurement.weightKg)
            userId = user.id
        }
        armed = false
        publish(m)
        return BroadcastAction.CONSUMED_STOP
    }

    override fun onDisconnected() {
        armed = true
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Composable
    override fun DeviceConfigurationUi() {
        // Reuses the S400/S800 bind-key strings — the concept (32-hex Mi-cloud key) is identical.
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
                text = stringResource(R.string.s200_bind_key_description),
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
