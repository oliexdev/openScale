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
 * Decode logic for the Xiaomi Mijia Scale S800 (MiBeacon v5, service data 0xFE95).
 *
 * The S800 is broadcast-only: it advertises AES-128-CCM encrypted MiBeacon frames in the
 * 0xFE95 service data. The weigh-in object 0x4e16 carries the weight (uint16 LE / 100). The
 * frames are encrypted under a per-device 16-byte bind key from the Mi cloud. Segmental body
 * composition is only on the encrypted Mi-auth GATT path (out of scope); weight plus the user
 * profile drives openScale's existing body-composition pipeline.
 *
 * Uses the same BouncyCastle AES-CCM primitive as [S400Decryptor] (AAD `0x11`, 32-bit tag).
 */
object XiaomiS800Lib {

    /** Product id of the Mijia Scale S800 (xiaomi.scales.ms116, pdid 20962). */
    const val S800_PID = 0x51e2

    private const val FC_ENCRYPTED = 0x08
    private const val FC_MAC_INCLUDED = 0x10
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
     * Layout: `FC(2 LE) | PID(2) | cnt(1) | [MAC(6) if FC&0x10] | cipher | extCnt(3) | MIC(4)`.
     * nonce = `macFrameOrder(6) || data[2..5) || extCnt(3)`; AAD = `0x11`; tag = 4 bytes.
     */
    fun decryptMiBeaconV5(data: ByteArray, bindKey: ByteArray, macFrameOrder: ByteArray): ByteArray? {
        if (data.size < 12 || bindKey.size != 16 || macFrameOrder.size != 6) return null
        val fc = leU16(data, 0)
        if ((fc and FC_ENCRYPTED) == 0) return null
        val cipherStart = if ((fc and FC_MAC_INCLUDED) != 0) 11 else 5
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

    /**
     * Parse a decrypted MiBeacon object TLV. Returns the weight in kg when it is the 0x4e16
     * measurement object whose trailing uint16 LE / 100 is a plausible weight, else null
     * (idle 0x5201, wrong object, or out-of-range weight).
     */
    fun parseWeightKg(decrypted: ByteArray): Float? {
        if (decrypted.size < 3) return null
        val type = leU16(decrypted, 0)
        val len = decrypted[2].toInt() and 0xFF
        if (type != OBJ_MEASUREMENT || len < 9 || decrypted.size < 3 + len) return null
        val value = decrypted.copyOfRange(3, 3 + len)
        val weight = leU16(value, 7) / 100.0f
        return if (weight in WEIGHT_MIN..WEIGHT_MAX) weight else null
    }

    private fun leU16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
}
