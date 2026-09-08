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
 * Clean-room reimplementation of the frame handling documented by Home Assistant's
 * `xiaomi-ble` library (MIT licensed, https://github.com/Bluetooth-Devices/xiaomi-ble):
 *   - `_parse_xiaomi` (src/xiaomi_ble/parser.py) for the MiBeacon frame-control layout and
 *     AES-CCM framing, verified against its own `test_Xiaomi_Scale_S200_MJTZC02YM` fixture.
 *   - `obj4e16` (same file) for the decrypted measurement object layout.
 *
 * The AES-CCM primitive mirrors [S400Decryptor] and [XiaomiS800Lib] (AAD `0x11`, 32-bit tag);
 * it is intentionally a separate implementation rather than a shared one — same as those two —
 * so this driver does not depend on, or risk perturbing, either of their unverified edge cases.
 */
package com.health.openscale.core.bluetooth.libs

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Decode logic for the Xiaomi Smart Scale S200 (MJTZC02YM, MiBeacon v5, service data 0xFE95).
 *
 * The S200 is broadcast-only and reports **weight only** — its measurement object carries no
 * impedance or heart rate, unlike the S400's `0x6e16` object. Frames are AES-128-CCM encrypted
 * under a per-device 16-byte bind key from the Mi cloud; nothing decodes without one.
 *
 * The scale also emits an unencrypted *idle* frame with no object at all
 * (`frctrl` has `obj_include = 0`) — [decryptMiBeaconV5] returns null for it, same as for any
 * other non-measurement frame, so the handler simply keeps scanning until a real weigh-in frame
 * arrives (which requires someone standing on the scale).
 */
object XiaomiS200Lib {

    /** Known 16-bit product ids for the S200 (MJTZC02YM); Xiaomi has shipped it under three. */
    val PRODUCT_IDS = setOf(0x45C9, 0x4C04, 0x4DCB)

    private const val FC_ENCRYPTED = 0x08
    private const val FC_MAC_INCLUDED = 0x10
    private const val FC_CAPABILITY_INCLUDED = 0x20
    private const val FC_CAPABILITY_IO = 0x20
    private const val OBJ_MEASUREMENT = 0x4e16
    private const val OBJ_MEASUREMENT_LEN = 9
    private const val WEIGHT_MIN_KG = 1.0f
    private const val WEIGHT_MAX_KG = 300.0f
    private const val MAC_TAG_BITS = 32

    /** Parsed `0x4e16` measurement object. */
    data class Measurement(
        /** Mi Home user slot (1-based) the scale attributed this weigh-in to. */
        val profileId: Int,
        val weightKg: Float,
        /** Unix timestamp (seconds) the scale itself recorded for this weigh-in. */
        val timestampSec: Long
    )

    /**
     * Product id at bytes `[2..3]` LE of the 0xFE95 service data, or null if too short.
     * Present regardless of encryption, so it can be used to claim the device even before a
     * bind key is configured.
     */
    fun parseProductId(serviceData: ByteArray): Int? {
        if (serviceData.size < 4) return null
        return leU16(serviceData, 2)
    }

    /**
     * Decrypt a MiBeacon v5 FE95 advertisement. Returns the decrypted object TLV
     * (`type(2 LE) | len | value`), or null when the frame is unencrypted (e.g. the idle
     * beacon), malformed, or fails the AES-CCM tag (wrong key, or a MAC-omitted frame paired
     * with the wrong device address).
     *
     * Layout: `FC(2 LE) | PID(2) | cnt(1) | [MAC(6) if FC&0x10] | [capability(1-2) if FC&0x20]
     * | cipher | extCnt(3) | MIC(4)`. The S200's own measurement frames omit the MAC
     * (`mac_include = 0`), so [macAddressDisplayOrder] — the device's normal
     * `"XX:XX:XX:XX:XX:XX"` address — is required as a fallback; an embedded MAC, when present,
     * always takes priority over it.
     *
     * nonce = `macFrameOrder(6) || data[2..5) || extCnt(3)`; AAD = `0x11`; tag = 4 bytes.
     */
    fun decryptMiBeaconV5(data: ByteArray, bindKey: ByteArray, macAddressDisplayOrder: String): ByteArray? {
        if (data.size < 5 || bindKey.size != 16) return null
        val fc = leU16(data, 0)
        if ((fc and FC_ENCRYPTED) == 0) return null

        var i = 5
        var embeddedMac: ByteArray? = null
        if ((fc and FC_MAC_INCLUDED) != 0) {
            if (data.size < i + 6) return null
            embeddedMac = data.copyOfRange(i, i + 6)
            i += 6
        }
        if ((fc and FC_CAPABILITY_INCLUDED) != 0) {
            if (data.size <= i) return null
            val capabilityByte = data[i].toInt() and 0xFF
            i += 1
            // A capability byte with its I/O bit set is followed by one extra I/O byte.
            if ((capabilityByte and FC_CAPABILITY_IO) != 0) {
                if (data.size <= i) return null
                i += 1
            }
        }
        val cipherStart = i
        if (data.size < cipherStart + 7) return null

        val macFrameOrder = embeddedMac ?: macFrameOrderFromAddress(macAddressDisplayOrder) ?: return null
        val cipher = data.copyOfRange(cipherStart, data.size - 7)
        val extCnt = data.copyOfRange(data.size - 7, data.size - 4)
        val mic = data.copyOfRange(data.size - 4, data.size)
        val nonce = macFrameOrder + data.copyOfRange(2, 5) + extCnt
        val cipherText = cipher + mic

        return try {
            val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
            val params = AEADParameters(KeyParameter(bindKey), MAC_TAG_BITS, nonce, byteArrayOf(0x11))
            ccm.init(false, params)
            val out = ByteArray(ccm.getOutputSize(cipherText.size))
            val n = ccm.processBytes(cipherText, 0, cipherText.size, out, 0)
            ccm.doFinal(out, n)
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a decrypted MiBeacon object TLV as the S200's `0x4e16` measurement:
     * `profileId(u8) | weightRaw(u32 LE, ×0.01 kg) | timestamp(u32 LE, Unix seconds)`.
     * Returns null for any other object, a malformed/truncated one, a still-zero weight
     * (the scale reports this for a non-final packet), or an implausible weight.
     */
    fun parseMeasurement(decrypted: ByteArray): Measurement? {
        if (decrypted.size < 3) return null
        val type = leU16(decrypted, 0)
        val len = decrypted[2].toInt() and 0xFF
        if (type != OBJ_MEASUREMENT || len != OBJ_MEASUREMENT_LEN || decrypted.size < 3 + len) return null

        val value = decrypted.copyOfRange(3, 3 + len)
        val profileId = value[0].toInt() and 0xFF
        val weightRaw = u32LE(value, 1)
        if (weightRaw == 0L) return null
        val weightKg = weightRaw / 100.0f
        if (weightKg !in WEIGHT_MIN_KG..WEIGHT_MAX_KG) return null
        val timestampSec = u32LE(value, 5)

        return Measurement(profileId, weightKg, timestampSec)
    }

    /**
     * The nonce's MAC bytes are the device address reversed to "frame order"
     * (verified against the upstream fixture: address `D0:7B:6F:27:D7:29` → nonce prefix
     * `29 D7 27 6F 7B D0`). Returns null for a malformed address.
     */
    private fun macFrameOrderFromAddress(address: String): ByteArray? {
        val clean = address.replace(":", "")
        if (clean.length != 12) return null
        return try {
            ByteArray(6) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.reversedArray()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun leU16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

    private fun u32LE(d: ByteArray, off: Int): Long =
        (d[off].toLong() and 0xFF) or
            ((d[off + 1].toLong() and 0xFF) shl 8) or
            ((d[off + 2].toLong() and 0xFF) shl 16) or
            ((d[off + 3].toLong() and 0xFF) shl 24)
}
