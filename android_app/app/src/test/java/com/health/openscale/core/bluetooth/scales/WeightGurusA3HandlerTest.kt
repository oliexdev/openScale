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
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the encoding helpers of [WeightGurusA3Handler]: the two IEEE-11073 medical
 * float types, the pairing XOR, and slot selection.
 */
class WeightGurusA3HandlerTest {

    // --- 32-bit FLOAT (weight, impedance) -------------------------------------

    @Test
    fun `decodes a 32-bit FLOAT with a negative exponent`() {
        // mantissa 7725 (LE 24-bit), exponent -2 -> 77.25
        val data = byteArrayOf(0x2D, 0x1E, 0x00, 0xFE.toByte())
        assertThat(WeightGurusA3Handler.floatFrom32(data, 0)).isWithin(1e-3f).of(77.25f)
    }

    @Test
    fun `decodes a 32-bit FLOAT with a zero exponent`() {
        // mantissa 500, exponent 0 -> 500.0 (impedance-style value)
        val data = byteArrayOf(0xF4.toByte(), 0x01, 0x00, 0x00)
        assertThat(WeightGurusA3Handler.floatFrom32(data, 0)).isWithin(1e-3f).of(500f)
    }

    @Test
    fun `honours the offset and rejects a truncated 32-bit FLOAT`() {
        val framed = byteArrayOf(0x1F, 0x2D, 0x1E, 0x00, 0xFE.toByte())
        assertThat(WeightGurusA3Handler.floatFrom32(framed, 1)).isWithin(1e-3f).of(77.25f)
        assertThat(WeightGurusA3Handler.floatFrom32(framed, 3)).isEqualTo(0f)
    }

    // --- 16-bit SFLOAT (body composition) --------------------------------------

    @Test
    fun `decodes an SFLOAT with a negative exponent`() {
        // exponent -1 (0xF), mantissa 213 -> 21.3 %
        val raw = (0xF shl 12) or 213
        val data = byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
        assertThat(WeightGurusA3Handler.sfloatFrom16(data, 0)).isWithin(1e-3f).of(21.3f)
    }

    @Test
    fun `decodes an SFLOAT with a zero exponent`() {
        // exponent 0, mantissa 12 -> 12.0 (visceral fat level)
        val data = byteArrayOf(0x0C, 0x00)
        assertThat(WeightGurusA3Handler.sfloatFrom16(data, 0)).isWithin(1e-3f).of(12f)
    }

    @Test
    fun `decodes an SFLOAT with a negative mantissa`() {
        // exponent 0, mantissa -1 (0xFFF two's complement in 12 bits)
        val data = byteArrayOf(0xFF.toByte(), 0x0F)
        assertThat(WeightGurusA3Handler.sfloatFrom16(data, 0)).isWithin(1e-3f).of(-1f)
    }

    @Test
    fun `decodes the most negative SFLOAT mantissa`() {
        // exponent 0, raw mantissa 0x800 -> -2048, not 0
        val data = byteArrayOf(0x00, 0x08)
        assertThat(WeightGurusA3Handler.sfloatFrom16(data, 0)).isWithin(1e-3f).of(-2048f)
    }

    @Test
    fun `decodes an SFLOAT with the most negative exponent`() {
        // exponent 0x8 -> -8, mantissa 1
        val raw = (0x8 shl 12) or 1
        val data = byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
        assertThat(WeightGurusA3Handler.sfloatFrom16(data, 0)).isWithin(1e-12f).of(1e-8f)
    }

    @Test
    fun `rejects a truncated SFLOAT`() {
        assertThat(WeightGurusA3Handler.sfloatFrom16(byteArrayOf(0x0C), 0)).isEqualTo(0f)
    }

    // --- Bone units ------------------------------------------------------------

    @Test
    fun `converts the bone percentage to a mass in kg`() {
        // 0xF023 decodes to 3.5 %, which at 70.0 kg is 2.45 kg.
        assertThat(WeightGurusA3Handler.bonePercentToKg(3.5f, 70.0f)).isWithin(1e-2f).of(2.45f)
    }

    @Test
    fun `bone conversion is safe without a weight`() {
        assertThat(WeightGurusA3Handler.bonePercentToKg(3.5f, 0f)).isEqualTo(0f)
    }

    // --- Pairing ---------------------------------------------------------------

    @Test
    fun `verification code is the byte-wise xor of password and random`() {
        val password = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val random = byteArrayOf(0x11, 0x11, 0x11, 0x11)
        assertThat(WeightGurusA3Handler.xor(password, random).toList())
            .containsExactly(0x03.toByte(), 0x25.toByte(), 0x47.toByte(), 0x69.toByte())
            .inOrder()
    }

    @Test
    fun `password survives a hex round trip`() {
        val password = byteArrayOf(0x00, 0xAB.toByte(), 0x7F, 0xFF.toByte())
        val hex = WeightGurusA3Handler.bytesToHex(password)
        assertThat(hex).isEqualTo("00ab7fff")
        assertThat(WeightGurusA3Handler.hexToBytes(hex)).isEqualTo(password)
    }

    @Test
    fun `rejects malformed stored passwords`() {
        assertThat(WeightGurusA3Handler.hexToBytes("abc")).isNull()
        assertThat(WeightGurusA3Handler.hexToBytes("zzzz")).isNull()
    }

    // --- Profile height --------------------------------------------------------
    // Height is an SFLOAT in metres, (height_cm * 10) or 0xD000, not raw centimetres.
    // sfloatFrom16 is pinned above, so decoding what encodeHeightCm produces is the check.

    @Test
    fun `encodes the profile height as an SFLOAT in metres`() {
        val encoded = WeightGurusA3Handler.encodeHeightCm(170)
        assertThat(encoded).isEqualTo(0xD6A4)

        val bytes = byteArrayOf((encoded and 0xFF).toByte(), ((encoded shr 8) and 0xFF).toByte())
        assertThat(WeightGurusA3Handler.sfloatFrom16(bytes, 0)).isWithin(1e-4f).of(1.70f)
    }

    @Test
    fun `profile height round trips across the representable range`() {
        for (cm in 50..204) {
            val encoded = WeightGurusA3Handler.encodeHeightCm(cm)
            val bytes = byteArrayOf((encoded and 0xFF).toByte(), ((encoded shr 8) and 0xFF).toByte())
            assertThat(WeightGurusA3Handler.sfloatFrom16(bytes, 0))
                .isWithin(1e-4f).of(cm / 100f)
        }
    }

    @Test
    fun `clamps heights that the signed SFLOAT mantissa cannot carry`() {
        // 205 cm would set the mantissa's sign bit, so anything taller is pinned to the ceiling.
        for (cm in listOf(205, 250, 9999)) {
            assertThat(WeightGurusA3Handler.encodeHeightCm(cm))
                .isEqualTo(WeightGurusA3Handler.encodeHeightCm(204))
        }
        // An unset height (-1) must not wrap either.
        val unset = WeightGurusA3Handler.encodeHeightCm(-1)
        val bytes = byteArrayOf((unset and 0xFF).toByte(), ((unset shr 8) and 0xFF).toByte())
        assertThat(WeightGurusA3Handler.sfloatFrom16(bytes, 0)).isGreaterThan(0f)
    }

    // --- Slot selection --------------------------------------------------------
    // Choice falls back through: the stored slot, a slot already named for this user, then the
    // lowest free one.

    @Test
    fun `reuses the stored slot when there is one`() {
        assertThat(
            WeightGurusA3Handler.chooseSlot(
                storedSlot = 5, userName = "Sam",
                slotNames = mapOf(1 to "Sam"), freeSlots = setOf(2, 3)
            )
        ).isEqualTo(5)
    }

    @Test
    fun `reclaims a slot carrying this user's name when the stored slot is gone`() {
        // The app's data was cleared, so only the name on the scale identifies our slot.
        assertThat(
            WeightGurusA3Handler.chooseSlot(
                storedSlot = -1, userName = "Sam",
                slotNames = mapOf(1 to "Alex", 4 to "sam"), freeSlots = setOf(6, 7, 8)
            )
        ).isEqualTo(4)
    }

    @Test
    fun `takes the lowest free slot when the user is not registered yet`() {
        assertThat(
            WeightGurusA3Handler.chooseSlot(
                storedSlot = -1, userName = "Sam",
                slotNames = mapOf(1 to "Alex"), freeSlots = setOf(7, 3, 5)
            )
        ).isEqualTo(3)
    }

    @Test
    fun `falls back to the first slot when the scale is full`() {
        assertThat(
            WeightGurusA3Handler.chooseSlot(
                storedSlot = -1, userName = "Sam",
                slotNames = mapOf(1 to "Alex"), freeSlots = emptySet()
            )
        ).isEqualTo(1)
    }

    @Test
    fun `suggests the first slot on a brand new scale where every slot is empty`() {
        // Nothing to match on, so the suggestion is simply the lowest slot; the user still picks.
        assertThat(
            WeightGurusA3Handler.chooseSlot(
                storedSlot = -1, userName = "Sam",
                slotNames = (1..8).associateWith { "" }, freeSlots = (1..8).toSet()
            )
        ).isEqualTo(1)
    }

    @Test
    fun `suggests a free slot on a second-hand scale full of another owner's names`() {
        // The previous owner's profiles are left alone unless the user deliberately picks one.
        assertThat(
            WeightGurusA3Handler.chooseSlot(
                storedSlot = -1, userName = "Sam",
                slotNames = mapOf(1 to "Alex", 2 to "Jo", 3 to ""), freeSlots = setOf(3)
            )
        ).isEqualTo(3)
    }

    @Test
    fun `an empty user name never matches an empty slot`() {
        // Unnamed slots decode to "", which must not be mistaken for a nameless user's slot.
        assertThat(
            WeightGurusA3Handler.chooseSlot(
                storedSlot = -1, userName = "",
                slotNames = mapOf(1 to "", 2 to ""), freeSlots = setOf(2)
            )
        ).isEqualTo(2)
    }

    @Test
    fun `decodes the space-padded slot name`() {
        // [0x83][slot][18-byte name, space padded]
        val frame = byteArrayOf(0x83.toByte(), 0x02) +
            "Sam".toByteArray() + ByteArray(15) { 0x20 }
        assertThat(WeightGurusA3Handler.decodeSlotName(frame)).isEqualTo("Sam")

        val empty = byteArrayOf(0x83.toByte(), 0x03) + ByteArray(18) { 0x20 }
        assertThat(WeightGurusA3Handler.decodeSlotName(empty)).isEmpty()
    }

    // --- Device matching -------------------------------------------------------

    @Test
    fun `claims both the pairing and the paired advertised names`() {
        val handler = WeightGurusA3Handler()
        assertThat(handler.supportFor(scanned("10376B"))).isNotNull()
        assertThat(handler.supportFor(scanned("00376B251016AA"))).isNotNull()
    }

    @Test
    fun `ignores other Transtek scales`() {
        val handler = WeightGurusA3Handler()
        // Trisa Body Analyze and 1BODY CONNECT have their own handlers.
        assertThat(handler.supportFor(scanned("01257B"))).isNull()
        assertThat(handler.supportFor(scanned("11257B"))).isNull()
        assertThat(handler.supportFor(scanned("1BODY CONNECT"))).isNull()
        assertThat(handler.supportFor(scanned(""))).isNull()
    }

    private fun scanned(name: String) =
        com.health.openscale.core.service.ScannedDeviceInfo(name, "CB:A0:80:F1:53:B2", 0, emptyList(), null)
}
