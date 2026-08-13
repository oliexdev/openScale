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
import android.os.ParcelUuid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.health.openscale.core.bluetooth.libs.BmrFormula
import com.health.openscale.core.bluetooth.libs.BoneFormula
import com.health.openscale.core.bluetooth.libs.S400Aggregator
import com.health.openscale.core.bluetooth.libs.S400BodyComposition
import com.health.openscale.core.bluetooth.libs.S400Decryptor
import com.health.openscale.core.bluetooth.libs.S400Inputs
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import kotlin.text.isNotEmpty
import kotlin.text.lowercase

/**
 * Xiaomi Body Composition Scale S400 handler.
 *
 * Based on https://github.com/lswiderski/mi-scale-exporter and
 * https://github.com/lswiderski/MiScaleBodyComposition
 *
 * The S400 broadcasts AES-CCM encrypted advertisement data containing:
 * - Weight
 * - Impedance (for body composition calculation)
 * - Heart rate
 *
 * Unlike older Mi Scales, the S400 requires:
 * - A BLE bind key extracted from Xiaomi Cloud
 * - The scale's MAC address (used in nonce construction)
 *
 * This scale uses broadcast-only mode (no GATT connection) and sends
 * AES-CCM encrypted service data in BLE advertisements.
 */
class MiScaleS400Handler : ScaleDeviceHandler() {

    companion object {
        // Settings keys for S400 configuration
        private const val SETTINGS_KEY_BIND_KEY = "s400_bind_key"
        private const val SETTINGS_KEY_MAC_ADDRESS = "s400_mac_address"
        private const val SETTINGS_KEY_BONE_FORMULA = "s400_bone_formula"
        private const val SETTINGS_KEY_BMR_FORMULA = "s400_bmr_formula"

        // Known S400 device name patterns
        // The S400 may advertise with various names depending on firmware/region
        // Format seen in practice: "Xiaomi Scale S400 XXXX" where XXXX is last 4 of MAC
        private val KNOWN_NAME_PATTERNS = listOf(
            "SCALE S400",                  // Core pattern - matches "Xiaomi Scale S400 8E8B"
            "XMTZC14HM",                   // S400 model identifier (raw BLE name)
            "XMTZC",                       // Xiaomi scale prefix
        )

        // S400 Service UUID (Xiaomi Body Composition service)
        private val SERVICE_UUID_S400 = ParcelUuid.fromString("0000181b-0000-1000-8000-00805f9b34fb")
    }

    // Track if we've warned about missing configuration
    private var warnedMissingConfig = false

    // Holds per-device session state until both impedance packets land.
    private val aggregator = S400Aggregator()


    @Composable
    override fun DeviceConfigurationUi() {
        // 1. Read the initial value from settings
        val persistedValue = settingsGetString(SETTINGS_KEY_BIND_KEY) ?: ""

        // 2. Track the local input and a "isSaved" flag for immediate feedback
        var inputValue by remember(persistedValue) { mutableStateOf(persistedValue) }
        var lastSavedValue by remember { mutableStateOf(persistedValue) }

        // Validation logic
        val isValid = inputValue.length == 32
        val showError = inputValue.isNotEmpty() && !isValid
        // Logic: It's saved if it's valid and matches the last value we successfully wrote
        val isSuccessfullySaved = isValid && inputValue == lastSavedValue && inputValue.isNotEmpty()

        // Auto-save effect
        LaunchedEffect(inputValue) {
            if (isValid && inputValue != lastSavedValue) {
                logD("Auto-saving valid bind key: $inputValue")
                settingsPutString(SETTINGS_KEY_BIND_KEY, inputValue)
                // Update the local "last saved" state immediately to trigger UI feedback
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
                    val filtered = newValue
                        .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                        .lowercase()
                    if (filtered.length <= 32) {
                        inputValue = filtered
                    }
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
                        if (showError) {
                            Text(stringResource(R.string.s400_bind_key_error))
                        } else if (isSuccessfullySaved) {
                            Text(
                                text = stringResource(R.string.saved),
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Text("${inputValue.length}/32")
                    }
                }
            )

            FormulaPicker(
                titleRes = R.string.s400_bone_formula_label,
                key = SETTINGS_KEY_BONE_FORMULA,
                options = listOf(
                    BoneFormula.MI_LEGACY.name to R.string.s400_bone_formula_mi_legacy,
                    BoneFormula.HEYMSFIELD.name to R.string.s400_bone_formula_heymsfield,
                ),
                defaultValue = BoneFormula.MI_LEGACY.name,
            )

            FormulaPicker(
                titleRes = R.string.s400_bmr_formula_label,
                key = SETTINGS_KEY_BMR_FORMULA,
                options = listOf(
                    BmrFormula.CUNNINGHAM_1991.name to R.string.s400_bmr_formula_cun91,
                    BmrFormula.CUNNINGHAM_1980.name to R.string.s400_bmr_formula_cun80,
                ),
                defaultValue = BmrFormula.CUNNINGHAM_1991.name,
            )
        }
    }

    @Composable
    private fun FormulaPicker(
        @androidx.annotation.StringRes titleRes: Int,
        key: String,
        options: List<Pair<String, Int>>,
        defaultValue: String,
    ) {
        val persisted = settingsGetString(key) ?: defaultValue
        var selected by remember(persisted) { mutableStateOf(persisted) }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
            )
            options.forEach { (value, labelRes) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected == value,
                            onClick = {
                                selected = value
                                settingsPutString(key, value)
                            },
                        ),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == value,
                        onClick = {
                            selected = value
                            settingsPutString(key, value)
                        },
                    )
                    Text(stringResource(labelRes))
                }
            }
        }
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.ROOT)

        // Check if device name matches known S400 patterns
        // Use contains() for flexibility - some names may have prefixes/suffixes
        val isS400 = KNOWN_NAME_PATTERNS.any { pattern ->
            val upperPattern = pattern.uppercase(Locale.ROOT)
            name.contains(upperPattern) || name.startsWith(upperPattern)
        }

        if (!isS400) return null

        return DeviceSupport(
            displayName = "Xiaomi Body Composition Scale S400",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            ),
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.BROADCAST_ONLY
        )
    }

    /**
     * Process BLE advertisements from the S400 scale.
     *
     * The S400 sends encrypted service data that we need to decrypt
     * using the user's bind key and the scale's MAC address.
     */
    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        // Get configured bind key and MAC address
        val bindKey = settingsGetString(SETTINGS_KEY_BIND_KEY)
        val configuredMac = settingsGetString(SETTINGS_KEY_MAC_ADDRESS)

        // Get MAC from scan result (format: XX:XX:XX:XX:XX:XX)
        val deviceMac = result.device.address

        // Use configured MAC if available, otherwise use detected MAC
        val macAddress = if (!configuredMac.isNullOrEmpty() && S400Decryptor.isValidMacAddress(configuredMac)) {
            configuredMac
        } else {
            deviceMac
        }

        // Validate configuration
        if (bindKey.isNullOrEmpty() || !S400Decryptor.isValidBindKey(bindKey)) {
            if (!warnedMissingConfig) {
                logW("S400: Missing or invalid bind key. Configure in Settings.")
                userWarn(R.string.bt_s400_missing_bind_key)
                warnedMissingConfig = true
            }
            return BroadcastAction.IGNORED
        }

        // Extract service data from advertisement
        val serviceData = extractServiceData(result) ?: return BroadcastAction.IGNORED

        logD("S400 advert: ${serviceData.size} bytes from $deviceMac")

        // Attempt decryption
        val measurement = try {
            S400Decryptor.decrypt(serviceData, macAddress, bindKey)
        } catch (e: Exception) {
            logW("S400 decryption failed: ${e.message}")
            return BroadcastAction.IGNORED
        }

        if (measurement == null) {
            logD("S400: No valid measurement in advertisement")
            return BroadcastAction.IGNORED
        }

        logD(
            "S400 packet: weight=${measurement.weightKg}kg, " +
                "impedanceHigh=${measurement.impedanceHigh}, " +
                "impedanceLow=${measurement.impedanceLow}, " +
                "hr=${measurement.heartRate}"
        )

        val finalized = when (val outcome = aggregator.ingest(deviceMac, measurement, System.currentTimeMillis())) {
            is S400Aggregator.Outcome.Pending -> return BroadcastAction.CONSUMED_KEEP_SCANNING
            is S400Aggregator.Outcome.Duplicate -> return BroadcastAction.CONSUMED_KEEP_SCANNING
            is S400Aggregator.Outcome.Finalized -> outcome
        }

        logI(
            "S400 finalized: weight=${finalized.weightKg}kg, " +
                "impedanceHigh=${finalized.impedanceHigh}, " +
                "impedanceLow=${finalized.impedanceLow}, " +
                "hr=${finalized.heartRate}, timedOut=${finalized.timedOut}"
        )

        val boneFormula = readBoneFormula()
        val bmrFormula = readBmrFormula()

        val composition = S400BodyComposition.compute(
            S400Inputs(
                age = user.age,
                sexMale = user.gender == GenderType.MALE,
                heightCm = user.bodyHeight,
                weightKg = finalized.weightKg,
                rHighRaw = finalized.impedanceHigh,
                // Single-band fallback when Packet B never arrives (older firmware path).
                rLowRaw = finalized.impedanceLow ?: finalized.impedanceHigh,
            ),
            boneFormula = boneFormula,
            bmrFormula = bmrFormula,
        )

        val scaleMeasurement = ScaleMeasurement().apply {
            dateTime = Date()
            weight = finalized.weightKg
            userId = user.id
            heartRate = finalized.heartRate ?: 0
            impedance = finalized.impedanceHigh.toDouble()
            finalized.impedanceLow?.let { impedanceLow = it.toDouble() }

            fat = composition.bfPct ?: 0f
            water = composition.tbwPct ?: 0f
            muscle = composition.smmPct ?: 0f
            bone = composition.boneKg ?: 0f
            lbm = composition.ffmKg ?: 0f
            visceralFat = composition.vfi ?: 0f
            bmr = composition.bmrKcal ?: 0f
            ecw = composition.ecwPct ?: 0f
            icw = composition.icwPct ?: 0f
            protein = composition.proteinPct ?: 0f
            bcm = composition.bcmKg ?: 0f
        }

        publish(scaleMeasurement)

        return BroadcastAction.CONSUMED_STOP
    }

    private fun readBoneFormula(): BoneFormula =
        when (settingsGetString(SETTINGS_KEY_BONE_FORMULA)) {
            BoneFormula.HEYMSFIELD.name -> BoneFormula.HEYMSFIELD
            else -> BoneFormula.MI_LEGACY
        }

    private fun readBmrFormula(): BmrFormula =
        when (settingsGetString(SETTINGS_KEY_BMR_FORMULA)) {
            BmrFormula.CUNNINGHAM_1980.name -> BmrFormula.CUNNINGHAM_1980
            else -> BmrFormula.CUNNINGHAM_1991
        }

    /**
     * Extract service data from the BLE scan result.
     *
     * The S400 sends data via Service Data (AD type 0x16) for the
     * Body Composition Service UUID (0x181B).
     */
    private fun extractServiceData(result: ScanResult): ByteArray? {
        val scanRecord = result.scanRecord ?: return null

        // Try to get service data for the Body Composition Service
        val serviceData = scanRecord.serviceData

        // Check for 0x181B service data
        serviceData?.get(SERVICE_UUID_S400)?.let { data ->
            if (data.size >= 24) {
                return data
            }
        }

        // Some devices may include the service UUID in the data
        // Try all service data entries
        serviceData?.values?.forEach { data ->
            if (data.size >= 24 && data.size <= 26) {
                return data
            }
        }

        // Fallback: check raw advertisement bytes for service data
        val rawBytes = scanRecord.bytes
        if (rawBytes != null && rawBytes.size >= 26) {
            // Look for service data AD type (0x16) followed by 0x1B 0x18 (little-endian 0x181B)
            for (i in 0 until rawBytes.size - 26) {
                if (rawBytes[i] == 0x16.toByte() &&
                    rawBytes[i + 1] == 0x1B.toByte() &&
                    rawBytes[i + 2] == 0x18.toByte()) {
                    // Found service data header, extract the data
                    val dataStart = i + 3
                    val remaining = rawBytes.size - dataStart
                    if (remaining >= 24) {
                        return rawBytes.copyOfRange(dataStart, dataStart + minOf(remaining, 26))
                    }
                }
            }
        }

        return null
    }
}
