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
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Tests for [EufyP2Lib] / [EufyAuthHandler], mirroring ble-scale-sync
 * tests/scales/eufy-p2.test.ts. Crypto helpers + frame parsers are pure and verifiable;
 * a scale-side C1 emulator drives the full C0/C1/C2/C3 handshake.
 */
class EufyP2LibTest {

    private val testMac = "CF:E6:03:1D:09:F7"
    private val testMacFlat = "CFE6031D09F7"
    private val iv = "0000000000000000".toByteArray(Charsets.US_ASCII)

    private fun makeNotification(kg: Double, impedance: Int, isFinal: Boolean = true): ByteArray {
        val raw = (kg * 100).roundToInt()
        return ByteArray(16).also {
            it[0] = 0xCF.toByte(); it[2] = 0x00
            it[6] = (raw and 0xff).toByte(); it[7] = ((raw shr 8) and 0xff).toByte()
            it[8] = (impedance and 0xff).toByte()
            it[9] = ((impedance shr 8) and 0xff).toByte()
            it[10] = ((impedance shr 16) and 0xff).toByte()
            it[12] = if (isFinal) 0x00 else 0x01
        }
    }

    private fun makeVendor(kg: Double, finalFlag: Int = 0x00): ByteArray {
        val raw = (kg * 100).roundToInt()
        return ByteArray(19).also {
            EufyP2Lib.hexToBytes(testMacFlat).copyInto(it, 0)
            it[6] = 0xCF.toByte()
            it[9] = (raw and 0xff).toByte(); it[10] = ((raw shr 8) and 0xff).toByte()
            it[15] = finalFlag.toByte()
        }
    }

    /** Emulate the scale: respond to C0 with C1 carrying an AES-encrypted device UUID. */
    private fun makeC1Frames(deviceUuid: String): List<ByteArray> {
        val key = MessageDigest.getInstance("MD5").digest(testMacFlat.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val enc = cipher.doFinal(deviceUuid.toByteArray(Charsets.UTF_8))
        val base64Hex = EufyP2Lib.toHex(Base64.getEncoder().encodeToString(enc).toByteArray(Charsets.US_ASCII))
        return EufyP2Lib.buildSubContract(base64Hex, 0xc1)
    }

    @Test
    fun `derives AES key from MAC via MD5`() {
        val expected = MessageDigest.getInstance("MD5").digest(testMacFlat.toByteArray(Charsets.UTF_8))
        assertThat(EufyAuthHandler(testMac).key).isEqualTo(expected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid MAC`() {
        EufyAuthHandler("not-a-mac")
    }

    @Test
    fun `builds C0 frames with correct header and XOR checksum`() {
        val frames = EufyAuthHandler(testMac, "abcdef123456789").buildC0()
        assertThat(frames).hasSize(2)
        assertThat(frames[0][0].toInt() and 0xFF).isEqualTo(0xc0)
        assertThat(frames[0][1].toInt() and 0xFF).isEqualTo(0x02)
        assertThat(frames[0][2].toInt() and 0xFF).isEqualTo(0x00)
        assertThat(frames[0][3].toInt() and 0xFF).isEqualTo(0x18) // 24 base64 bytes
        assertThat(frames[1][2].toInt() and 0xFF).isEqualTo(0x01)
        for (f in frames) {
            val body = f.copyOfRange(0, f.size - 1)
            assertThat(f[f.size - 1].toInt() and 0xFF).isEqualTo(EufyP2Lib.xorChecksum(body))
        }
    }

    @Test
    fun `completes full C0-C1-C2-C3 handshake`() {
        val h = EufyAuthHandler(testMac, "abcdef123456789")
        assertThat(h.buildC0()).isNotEmpty()

        var c1Done = false
        for (f in makeC1Frames("DEVICEUUID12345")) c1Done = h.handleC1(f) || c1Done
        assertThat(c1Done).isTrue()
        assertThat(h.deviceUuid).isEqualTo("DEVICEUUID12345")

        val c2 = h.buildC2()
        assertThat(c2[0][0].toInt() and 0xFF).isEqualTo(0xc2)

        assertThat(h.handleC3(byteArrayOf(0xc3.toByte(), 0x01, 0x00, 0x01, 0x00, 0xc3.toByte()))).isTrue()
        assertThat(h.isAuthenticated).isTrue()

        val fail = EufyAuthHandler(testMac)
        fail.handleC3(byteArrayOf(0xc3.toByte(), 0x01, 0x00, 0x01, 0x01, 0xc2.toByte()))
        assertThat(fail.isAuthenticated).isFalse()
    }

    @Test(expected = IllegalStateException::class)
    fun `buildC2 before C1 throws`() {
        EufyAuthHandler(testMac).buildC2()
    }

    @Test
    fun `buildSubContract fragments at 15 base64 bytes per segment`() {
        // 44-char base64 -> 88 hex chars -> 3 segments (30+30+28)
        val dataHex = EufyP2Lib.toHex("A".repeat(44).toByteArray(Charsets.US_ASCII))
        val frames = EufyP2Lib.buildSubContract(dataHex, 0xc2)
        assertThat(frames).hasSize(3)
        assertThat(frames[0][1].toInt() and 0xFF).isEqualTo(3)
        assertThat(frames[0][3].toInt() and 0xFF).isEqualTo(44)
        assertThat(frames.map { it[2].toInt() and 0xFF }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `parses final weight and impedance`() {
        assertThat(EufyP2Lib.parseWeightNotification(makeNotification(83.45, 543)))
            .isEqualTo(EufyReading(83.45f, 543))
        assertThat(EufyP2Lib.parseWeightNotification(makeNotification(83.45, 543, isFinal = false))).isNull()
        assertThat(EufyP2Lib.parseWeightNotification(makeNotification(83.45, 543).also { it[0] = 0xEE.toByte() })).isNull()
        assertThat(EufyP2Lib.parseWeightNotification(ByteArray(10))).isNull()
        assertThat(EufyP2Lib.parseWeightNotification(makeNotification(0.5, 400))).isNull()
    }

    @Test
    fun `parses advertisement weight`() {
        assertThat(EufyP2Lib.parseAdvertisement(makeVendor(75.2))).isEqualTo(EufyReading(75.2f, 0))
        assertThat(EufyP2Lib.parseAdvertisement(makeVendor(75.2, 0x02))).isNull()
        assertThat(EufyP2Lib.parseAdvertisement(makeVendor(75.2).also { it[6] = 0x00 })).isNull()
    }

    @Test
    fun `segment reassembler rejects a tampered XOR`() {
        val h = EufyAuthHandler(testMac, "abcdef123456789")
        val c1 = makeC1Frames("DEVICEUUID12345")
        val bad = c1[0].copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xff).toByte()
        assertThat(h.handleC1(bad)).isFalse()
    }
}
