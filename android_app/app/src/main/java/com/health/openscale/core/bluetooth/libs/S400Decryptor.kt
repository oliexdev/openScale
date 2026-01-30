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
/**
 * Decryption logic for Xiaomi Body Composition Scale S400.
 * Based on https://github.com/lswiderski/mi-scale-exporter and
 * https://github.com/lswiderski/MiScaleBodyComposition
 */
package com.health.openscale.core.bluetooth.libs

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw measurement data from S400 scale after decryption.
 */
data class S400Measurement(
    val weightKg: Float,
    val impedance: Float?,
    val heartRate: Int?
)

/**
 * Decrypts and parses advertisement data from Xiaomi Body Composition Scale S400.
 *
 * The S400 sends AES-CCM encrypted BLE advertisement data that requires:
 * - The scale's MAC address (used in nonce construction)
 * - A 16-byte BLE bind key from Xiaomi Cloud
 */
object S400Decryptor {

    private const val EXPECTED_DATA_LENGTH = 24
    private const val EXPECTED_DATA_LENGTH_WITH_HEADER = 26
    private const val BIND_KEY_HEX_LENGTH = 32
    private const val MAC_TAG_BITS = 32

    /**
     * Decrypt S400 advertisement data and extract measurements.
     *
     * @param advertisementData Raw service data from BLE advertisement (24 or 26 bytes)
     * @param macAddress Scale's Bluetooth MAC address (format: "XX:XX:XX:XX:XX:XX")
     * @param bindKey 32-character hex string (16 bytes) from Xiaomi Cloud
     * @return Decrypted measurement or null if decryption fails or data is invalid
     */
    fun decrypt(
        advertisementData: ByteArray,
        macAddress: String,
        bindKey: String
    ): S400Measurement? {
        // Validate bind key
        if (bindKey.length != BIND_KEY_HEX_LENGTH) {
            return null
        }

        // Normalize data length (strip 2-byte service UUID header if present)
        val data = when (advertisementData.size) {
            EXPECTED_DATA_LENGTH_WITH_HEADER -> advertisementData.copyOfRange(2, EXPECTED_DATA_LENGTH_WITH_HEADER)
            EXPECTED_DATA_LENGTH -> advertisementData
            else -> return null
        }

        return try {
            val macBytes = hexStringToByteArray(macAddress.replace(":", ""))
            val keyBytes = hexStringToByteArray(bindKey)

            if (macBytes.size != 6 || keyBytes.size != 16) {
                return null
            }

            // Build nonce: MAC_reversed[6] + data[2:5] + data[17:20]
            val nonce = macBytes.reversedArray() +
                data.copyOfRange(2, 5) +
                data.copyOfRange(data.size - 7, data.size - 4)

            // Extract MIC (authentication tag) - last 4 bytes
            val mic = data.copyOfRange(data.size - 4, data.size)

            // Extract encrypted payload - bytes 5 to (length - 7)
            val encryptedPayload = data.copyOfRange(5, data.size - 7)

            // Combine encrypted payload and MIC for decryption
            val cipherText = encryptedPayload + mic

            // AES-CCM decryption
            val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
            val associatedData = byteArrayOf(0x11)
            val params = AEADParameters(KeyParameter(keyBytes), MAC_TAG_BITS, nonce, associatedData)
            ccm.init(false, params)

            val decrypted = ByteArray(ccm.getOutputSize(cipherText.size))
            val len = ccm.processBytes(cipherText, 0, cipherText.size, decrypted, 0)
            ccm.doFinal(decrypted, len)

            parseDecryptedData(decrypted)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse decrypted payload to extract weight, impedance, and heart rate.
     */
    private fun parseDecryptedData(decrypted: ByteArray): S400Measurement? {
        if (decrypted.size < 12) return null

        // Extract bytes 3-12 (9 bytes), then take 4 bytes at offset 1
        val obj = decrypted.copyOfRange(3, 12)
        val slice = obj.copyOfRange(1, 5)

        // Convert to little-endian Int32
        val value = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN).int

        // Extract measurements using bit masks
        val weightRaw = value and 0x7FF           // bits 0-10
        val heartRateRaw = (value shr 11) and 0x7F // bits 11-17
        val impedanceRaw = value shr 18            // bits 18+

        val weightKg = weightRaw / 10.0f

        // Heart rate: valid range is 1-126, then add 50
        val heartRate = if (heartRateRaw in 1..126) heartRateRaw + 50 else null

        // Impedance: only valid if both impedance and weight are non-zero
        val impedance = if (impedanceRaw != 0 && weightRaw != 0) {
            impedanceRaw / 10.0f
        } else null

        return if (weightKg > 0) {
            S400Measurement(weightKg, impedance, heartRate)
        } else null
    }

    /**
     * Convert hex string to byte array.
     */
    private fun hexStringToByteArray(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace(":", "")
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Validate that a bind key is properly formatted.
     */
    fun isValidBindKey(bindKey: String): Boolean {
        if (bindKey.length != BIND_KEY_HEX_LENGTH) return false
        return bindKey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /**
     * Validate that a MAC address is properly formatted.
     */
    fun isValidMacAddress(mac: String): Boolean {
        val pattern = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        return pattern.matches(mac)
    }
}
