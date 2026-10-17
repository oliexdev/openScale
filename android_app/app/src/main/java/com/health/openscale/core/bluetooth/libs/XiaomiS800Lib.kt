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
 *   src/scales/xiaomi-s800.ts — "Xiaomi Mijia 8-electrode Body Composition Scale S800
 *   (xiaomi.scales.ms116)", upstream commit 5ee2c2e (#232).
 */
package com.health.openscale.core.bluetooth.libs

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Decode logic for the Xiaomi Mijia Scale S800 and the Xiaomi Smart Scale S200 (MiBeacon v5,
 * service data 0xFE95).
 *
 * Both are broadcast-only: they advertise AES-128-CCM encrypted MiBeacon frames in the 0xFE95
 * service data, encrypted under a per-device 16-byte bind key from the Mi cloud. Both send the
 * weigh-in as object 0x4e16, but with different layouts:
 *  - S800: weight as uint16 LE / 100 at value byte 7.
 *  - S200: `profile(u8) | weight(u32 LE / 100) | timestamp(u32 LE)`, as in xiaomi-ble's
 *    `obj4e16`. Its measurement frames omit the MAC, so the nonce needs the device address.
 * Segmental body composition is only on the encrypted Mi-auth GATT path (out of scope).
 *
 * Uses the same BouncyCastle AES-CCM primitive as [S400Decryptor] (AAD `0x11`, 32-bit tag).
 */
object XiaomiS800Lib {

    /** Product id of the Mijia Scale S800 (xiaomi.scales.ms116, pdid 20962). */
    const val S800_PID = 0x51e2

    /** Product ids of the Xiaomi Smart Scale S200 (MJTZC02YM), one model under several SKUs. */
    val S200_PIDS = setOf(0x45c9, 0x4c04, 0x4dcb)

    private const val FC_ENCRYPTED = 0x08
    private const val FC_MAC_INCLUDED = 0x10
    private const val FC_CAPABILITY_INCLUDED = 0x20
    private const val CAPABILITY_IO = 0x20
    private const val OBJ_MEASUREMENT = 0x4e16
    private const val WEIGHT_MIN = 10f
    private const val WEIGHT_MAX = 250f
    private const val MAC_TAG_BITS = 32

    /** Return the 6-byte frame-order MAC if the FE95 frame includes it (FC bit 0x10), else null. */
    fun macFrameOrderFromFrame(data: ByteArray): ByteArray? {
        if (data.size < 11) return null
        val fc = leU16(data, 0)
        if ((fc and FC_MAC_INCLUDED) == 0) return null
        return data.copyOfRange(5, 11)
    }

    /**
     * Decrypt a MiBeacon v5 FE95 advertisement. Returns the decrypted object TLV
     * (`type(2 LE) | len | value`) or null when the frame is unencrypted, malformed, or fails
     * the AES-CCM tag (wrong key / wrong MAC).
     *
     * Layout: `FC(2 LE) | PID(2) | cnt(1) | [MAC(6) if FC&0x10] | [capability(1-2) if FC&0x20]
     * | cipher | extCnt(3) | MIC(4)`.
     * nonce = `macFrameOrder(6) || data[2..5) || extCnt(3)`; AAD = `0x11`; tag = 4 bytes.
     */
    fun decryptMiBeaconV5(data: ByteArray, bindKey: ByteArray, macFrameOrder: ByteArray): ByteArray? {
        if (data.size < 12 || bindKey.size != 16 || macFrameOrder.size != 6) return null
        val fc = leU16(data, 0)
        if ((fc and FC_ENCRYPTED) == 0) return null
        var cipherStart = if ((fc and FC_MAC_INCLUDED) != 0) 11 else 5
        if ((fc and FC_CAPABILITY_INCLUDED) != 0) {
            if (data.size <= cipherStart) return null
            // A capability byte with its I/O bit set is followed by one I/O byte.
            val capability = data[cipherStart].toInt() and 0xFF
            cipherStart += if ((capability and CAPABILITY_IO) != 0) 2 else 1
        }
        if (data.size < cipherStart + 7) return null

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

    /** Device address `"AA:BB:CC:DD:EE:FF"` in nonce (frame) byte order, or null if malformed. */
    fun macFrameOrderFromAddress(address: String): ByteArray? {
        val parts = address.split(":")
        if (parts.size != 6) return null
        return parts.map { it.toIntOrNull(16)?.toByte() ?: return null }.reversed().toByteArray()
    }

    /**
     * Parse a decrypted MiBeacon object TLV. Returns the weight in kg when it is the 0x4e16
     * measurement object in the layout of [productId] with a plausible weight, else null
     * (idle 0x5201, wrong object, zero or out-of-range weight).
     */
    fun parseWeightKg(decrypted: ByteArray, productId: Int = S800_PID): Float? {
        if (decrypted.size < 3) return null
        val type = leU16(decrypted, 0)
        val len = decrypted[2].toInt() and 0xFF
        if (type != OBJ_MEASUREMENT || len < 9 || decrypted.size < 3 + len) return null
        val value = decrypted.copyOfRange(3, 3 + len)
        val weight = if (productId in S200_PIDS) leU32(value, 1) / 100.0f else leU16(value, 7) / 100.0f
        return if (weight in WEIGHT_MIN..WEIGHT_MAX) weight else null
    }

    /** The scale's own Unix timestamp of an S200 0x4e16 object, or null for any other object. */
    fun parseS200TimestampSec(decrypted: ByteArray): Long? {
        if (decrypted.size < 12 || leU16(decrypted, 0) != OBJ_MEASUREMENT) return null
        return leU32(decrypted, 3 + 5)
    }

    private fun leU32(d: ByteArray, off: Int): Long =
        leU16(d, off).toLong() or (leU16(d, off + 2).toLong() shl 16)

    private fun leU16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
}
