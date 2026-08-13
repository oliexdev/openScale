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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Round-trip tests for [XiaomiS800Lib], mirroring ble-scale-sync
 * tests/scales/xiaomi-s800.test.ts. SYNTHETIC data only (dummy key + arbitrary MAC): the test
 * encrypts a 0x4e16 weigh-in object with AES-128-CCM exactly as the scale would, then asserts
 * the lib decrypts and parses it back.
 */
class XiaomiS800LibTest {

    private val dummyKey = hex("000102030405060708090a0b0c0d0e0f")
    private val macFrame = hex("aba18f47ae04") // arbitrary reversed MAC, frame order

    /** Build the 12-byte decrypted 0x4e16 object for a weight (kg). */
    private fun weightObject(kg: Double): ByteArray {
        val raw = (kg * 100).roundToInt()
        val value = byteArrayOf(
            0x90.toByte(), 0, 0, 0x05, 0x2b, 0, 0,
            (raw and 0xff).toByte(), ((raw shr 8) and 0xff).toByte()
        )
        return byteArrayOf(0x16, 0x4e, 0x09) + value
    }

    /** Encrypt an object into a full FE95 frame (MAC-included variant, FC 0x5958). */
    private fun buildFrame(obj: ByteArray, key: ByteArray, mac: ByteArray, cnt: Int = 0x5b): ByteArray {
        val pid = byteArrayOf(0xe2.toByte(), 0x51)
        val ext = byteArrayOf(0x01, 0, 0)
        val nonce = mac + byteArrayOf(pid[0], pid[1], cnt.toByte()) + ext
        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        ccm.init(true, AEADParameters(KeyParameter(key), 32, nonce, byteArrayOf(0x11)))
        val out = ByteArray(ccm.getOutputSize(obj.size))
        val n = ccm.processBytes(obj, 0, obj.size, out, 0)
        ccm.doFinal(out, n)
        val cipher = out.copyOfRange(0, out.size - 4)
        val mic = out.copyOfRange(out.size - 4, out.size)
        val fc = byteArrayOf(0x58, 0x59)
        return fc + pid + byteArrayOf(cnt.toByte()) + mac + cipher + ext + mic
    }

    @Test
    fun `parses weight from a plaintext 0x4e16 object`() {
        assertThat(XiaomiS800Lib.parseWeightKg(weightObject(75.0))!!).isWithin(1e-3f).of(75.0f)
    }

    @Test
    fun `rejects out-of-range and idle objects`() {
        // trailing uint16 = 0x0048 = 72 -> 0.72 kg, out of [10,250]
        assertThat(XiaomiS800Lib.parseWeightKg(
            byteArrayOf(0x16, 0x4e, 0x09, 0x90.toByte(), 0, 0, 0x05, 0x2b, 0, 0, 0x48, 0x00))).isNull()
        // idle 0x5201 object
        assertThat(XiaomiS800Lib.parseWeightKg(byteArrayOf(0x01, 0x52, 0x01, 0x00))).isNull()
    }

    @Test
    fun `round-trips a synthetic encrypted frame`() {
        val frame = buildFrame(weightObject(82.4), dummyKey, macFrame)
        val mac = XiaomiS800Lib.macFrameOrderFromFrame(frame)
        assertThat(mac).isNotNull()
        assertThat(toHex(mac!!)).isEqualTo("aba18f47ae04")
        val dec = XiaomiS800Lib.decryptMiBeaconV5(frame, dummyKey, mac)
        assertThat(dec).isNotNull()
        assertThat(XiaomiS800Lib.parseWeightKg(dec!!)!!).isWithin(1e-2f).of(82.4f)
    }

    @Test
    fun `returns null on a wrong key (tag mismatch)`() {
        val frame = buildFrame(weightObject(75.0), dummyKey, macFrame)
        val wrong = ByteArray(16) { 0xff.toByte() }
        assertThat(XiaomiS800Lib.decryptMiBeaconV5(frame, wrong, macFrame)).isNull()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
