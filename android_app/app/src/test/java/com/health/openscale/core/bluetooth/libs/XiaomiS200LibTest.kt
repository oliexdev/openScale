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
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Tests for [XiaomiS200Lib].
 *
 * The primary fixture is the real MiBeacon frame from `xiaomi-ble`'s own
 * `test_Xiaomi_Scale_S200_MJTZC02YM` test (https://github.com/Bluetooth-Devices/xiaomi-ble),
 * independently re-verified against pycryptodome (not this driver's own BouncyCastle code)
 * before being committed, so it cannot hide a shared implementation bug. The idle-beacon
 * fixture is a real advertisement publicly captured in
 * https://github.com/Bluetooth-Devices/xiaomi-ble/issues/263. Capability-byte and
 * MAC-embedded coverage has no public real-world sample, so those cases are built
 * synthetically with the same AES-CCM construction the scale would use.
 */
class XiaomiS200LibTest {

    // Upstream xiaomi-ble `test_Xiaomi_Scale_S200_MJTZC02YM` fixture: decrypts to profile 1,
    // 62.25 kg, timestamp 1762728842 (2025-11-09 22:54:02 UTC).
    private val realServiceData = hex("4859044c019a80a275939010f0abc4fadc0600003d29c044")
    private val realBindKey = hex("653b1b10e1cb35e4ac5e60fa45f3bf29")
    private val realMacDisplay = "D0:7B:6F:27:D7:29"

    // Real idle-beacon advertisement (mac_include=1, encrypted=0, no object) from
    // https://github.com/Bluetooth-Devices/xiaomi-ble/issues/263; product id 0x4DCB, MAC
    // D0:7B:6F:5A:AB:C6.
    private val idleServiceData = hex("105bcb4d57c6ab5a6f7bd0")

    @Test
    fun `decrypts and parses the real S200 measurement frame`() {
        val decrypted = XiaomiS200Lib.decryptMiBeaconV5(realServiceData, realBindKey, realMacDisplay)
        assertThat(decrypted).isNotNull()
        val measurement = XiaomiS200Lib.parseMeasurement(decrypted!!)
        assertThat(measurement).isNotNull()
        assertThat(measurement!!.profileId).isEqualTo(1)
        assertThat(measurement.weightKg).isWithin(1e-3f).of(62.25f)
        assertThat(measurement.timestampSec).isEqualTo(1762728842L)
    }

    @Test
    fun `reads the product id from real advertisements regardless of encryption`() {
        assertThat(XiaomiS200Lib.parseProductId(realServiceData)).isEqualTo(0x4C04)
        assertThat(XiaomiS200Lib.parseProductId(idleServiceData)).isEqualTo(0x4DCB)
        assertThat(XiaomiS200Lib.PRODUCT_IDS).contains(0x4DCB)
    }

    @Test
    fun `parseProductId returns null for data shorter than 4 bytes`() {
        assertThat(XiaomiS200Lib.parseProductId(byteArrayOf(0x01, 0x02))).isNull()
    }

    @Test
    fun `returns null for the unencrypted idle beacon`() {
        assertThat(XiaomiS200Lib.decryptMiBeaconV5(idleServiceData, realBindKey, "D0:7B:6F:5A:AB:C6")).isNull()
    }

    @Test
    fun `returns null on a wrong bind key`() {
        val wrongKey = ByteArray(16) { 0xff.toByte() }
        assertThat(XiaomiS200Lib.decryptMiBeaconV5(realServiceData, wrongKey, realMacDisplay)).isNull()
    }

    @Test
    fun `returns null when the fallback address does not match the encrypted frame`() {
        // The real vector has mac_include=0, so an unrelated address must fail AEAD verification.
        assertThat(XiaomiS200Lib.decryptMiBeaconV5(realServiceData, realBindKey, "AA:BB:CC:DD:EE:FF")).isNull()
    }

    @Test
    fun `returns null on a truncated frame`() {
        val truncated = realServiceData.copyOfRange(0, 10)
        assertThat(XiaomiS200Lib.decryptMiBeaconV5(truncated, realBindKey, realMacDisplay)).isNull()
    }

    @Test
    fun `returns null when the authentication tag is corrupted`() {
        val corrupted = realServiceData.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()
        assertThat(XiaomiS200Lib.decryptMiBeaconV5(corrupted, realBindKey, realMacDisplay)).isNull()
    }

    @Test
    fun `rejects an object that is not the 0x4e16 measurement`() {
        assertThat(XiaomiS200Lib.parseMeasurement(byteArrayOf(0x01, 0x00, 0x04, 0, 0, 0, 0))).isNull()
    }

    @Test
    fun `rejects a measurement object with the wrong length`() {
        // type=0x4e16, but len=8 instead of the required 9.
        assertThat(
            XiaomiS200Lib.parseMeasurement(byteArrayOf(0x16, 0x4e, 0x08, 0, 0, 0, 0, 0, 0, 0, 0))
        ).isNull()
    }

    @Test
    fun `rejects a still-zero weight`() {
        // The scale reports raw weight 0 for a non-final packet.
        val obj = byteArrayOf(0x16, 0x4e, 0x09) + byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0)
        assertThat(XiaomiS200Lib.parseMeasurement(obj)).isNull()
    }

    @Test
    fun `rejects an implausible weight`() {
        val value = byteArrayOf(1) + leBytes(99_999_999) + leBytes(0)
        val obj = byteArrayOf(0x16, 0x4e, 0x09) + value
        assertThat(XiaomiS200Lib.parseMeasurement(obj)).isNull()
    }

    @Test
    fun `an embedded frame MAC takes priority over the passed address`() {
        val macFrameOrder = hex("aba18f47ae04")
        val obj = weightObject(profileId = 2, kg = 74.30, ts = 1_700_000_000)
        val frame = buildFrame(obj, key = testKey, macFrameOrder = macFrameOrder, macIncluded = true)

        // Pass a deliberately wrong address: since the frame embeds the real MAC, it must win.
        val decrypted = XiaomiS200Lib.decryptMiBeaconV5(frame, testKey, "00:00:00:00:00:00")
        assertThat(decrypted).isNotNull()
        val measurement = XiaomiS200Lib.parseMeasurement(decrypted!!)
        assertThat(measurement!!.profileId).isEqualTo(2)
        assertThat(measurement.weightKg).isWithin(1e-2f).of(74.30f)
    }

    @Test
    fun `handles a one-byte capability field`() {
        val macFrameOrder = hex("aba18f47ae04")
        val obj = weightObject(profileId = 1, kg = 55.15, ts = 1_700_000_000)
        val frame = buildFrame(obj, key = testKey, macFrameOrder = macFrameOrder, capabilityByte = 0x00)

        val decrypted = XiaomiS200Lib.decryptMiBeaconV5(frame, testKey, macDisplayFromFrameOrder(macFrameOrder))
        assertThat(decrypted).isNotNull()
        assertThat(XiaomiS200Lib.parseMeasurement(decrypted!!)!!.weightKg).isWithin(1e-2f).of(55.15f)
    }

    @Test
    fun `handles a capability field with its IO bit set (one extra byte)`() {
        val macFrameOrder = hex("aba18f47ae04")
        val obj = weightObject(profileId = 1, kg = 88.80, ts = 1_700_000_000)
        val frame = buildFrame(obj, key = testKey, macFrameOrder = macFrameOrder, capabilityByte = 0x20)

        val decrypted = XiaomiS200Lib.decryptMiBeaconV5(frame, testKey, macDisplayFromFrameOrder(macFrameOrder))
        assertThat(decrypted).isNotNull()
        assertThat(XiaomiS200Lib.parseMeasurement(decrypted!!)!!.weightKg).isWithin(1e-2f).of(88.80f)
    }

    // ---- synthetic frame builder (mirrors XiaomiS800LibTest's approach) ----

    private val testKey = hex("000102030405060708090a0b0c0d0e0f")

    private fun weightObject(profileId: Int, kg: Double, ts: Long): ByteArray {
        val raw = (kg * 100).roundToInt()
        val value = byteArrayOf(profileId.toByte()) + leBytes(raw) + leBytes(ts.toInt())
        return byteArrayOf(0x16, 0x4e, 0x09) + value
    }

    /** Encrypts [obj] into a full FE95 frame, matching the FC bits to the requested shape. */
    private fun buildFrame(
        obj: ByteArray,
        key: ByteArray,
        macFrameOrder: ByteArray,
        macIncluded: Boolean = false,
        capabilityByte: Int? = null,
        cnt: Int = 0x01
    ): ByteArray {
        var fc = 0x0048 // encrypted (0x08) | obj_include (0x40)
        if (macIncluded) fc = fc or 0x0010
        if (capabilityByte != null) fc = fc or 0x0020
        val pid = byteArrayOf(0x04, 0x4c) // 0x4C04, one of the real S200 product ids
        val ext = byteArrayOf(0x01, 0, 0)
        val nonce = macFrameOrder + byteArrayOf(pid[0], pid[1], cnt.toByte()) + ext

        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        ccm.init(true, AEADParameters(KeyParameter(key), 32, nonce, byteArrayOf(0x11)))
        val out = ByteArray(ccm.getOutputSize(obj.size))
        val n = ccm.processBytes(obj, 0, obj.size, out, 0)
        ccm.doFinal(out, n)
        val cipher = out.copyOfRange(0, out.size - 4)
        val mic = out.copyOfRange(out.size - 4, out.size)

        var frame = byteArrayOf((fc and 0xFF).toByte(), ((fc shr 8) and 0xFF).toByte()) + pid + byteArrayOf(cnt.toByte())
        if (macIncluded) frame += macFrameOrder
        if (capabilityByte != null) {
            frame += byteArrayOf(capabilityByte.toByte())
            if ((capabilityByte and 0x20) != 0) frame += byteArrayOf(0x00) // IO byte, value irrelevant here
        }
        return frame + cipher + ext + mic
    }

    private fun macDisplayFromFrameOrder(frameOrderMac: ByteArray): String =
        frameOrderMac.reversedArray().joinToString(":") { "%02X".format(it) }

    private fun leBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
}
