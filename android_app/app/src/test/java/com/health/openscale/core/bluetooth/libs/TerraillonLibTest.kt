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

/**
 * The frames below keep the exact layout of a BTSnoop capture from a Terraillon Smart Connect
 * (flags, field order, encodings), but carry an invented reading and an invented MAC address.
 */
class TerraillonLibTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `password is the reversed low four bytes of the MAC`() {
        val pw = TerraillonLib.passwordFromMac("C0:FF:EE:12:34:56")!!
        assertThat(pw.toList()).containsExactlyElementsIn(hex("56 34 12 ee").toList()).inOrder()
    }

    @Test
    fun `verification code xors the password with the random`() {
        val code = TerraillonLib.verificationCode(hex("56 34 12 ee"), hex("11 11 11 11"))
        assertThat(code.toList()).containsExactlyElementsIn(hex("47 25 03 ff").toList()).inOrder()
    }

    @Test
    fun `rejects a malformed MAC`() {
        assertThat(TerraillonLib.passwordFromMac("C0FFEE123456")).isNull()
    }

    @Test
    fun `decodes a weight frame`() {
        // flags 1f | weight 7245e-2 | ts | delta | impedance 5123e-1 | slot 01 | status 19 | pad
        val f = TerraillonLib.parseWeightFrame(
            hex("1f 4d 1c 00 fe 00 00 00 1f 00 00 00 00 03 14 00 ff 01 19 00")
        )!!
        assertThat(f.weightKg).isWithin(1e-3f).of(72.45f)
        assertThat(f.timestamp).isEqualTo(0x1f000000)
        assertThat(f.impedanceOhm!!).isWithin(1e-2f).of(512.3f)
        assertThat(f.bodyFollows).isTrue()
    }

    @Test
    fun `decodes a body composition frame`() {
        // flags 7f | ts | slot 01 | bmr 1650 | fat 22.4 | water 55.1 | visceral 8.0
        // | muscle 38.6 | bone 3.2 kg | trailing byte pair not covered by the flags
        val b = TerraillonLib.parseBodyFrame(
            hex("7f 00 00 00 1f 01 72 06 e0 f0 27 f2 50 f0 82 f1 20 f0 19 00")
        )!!
        assertThat(b.timestamp).isEqualTo(0x1f000000)
        assertThat(b.bmrKcal).isEqualTo(1650f)
        assertThat(b.fatPercent!!).isWithin(1e-3f).of(22.4f)
        assertThat(b.waterPercent!!).isWithin(1e-3f).of(55.1f)
        assertThat(b.visceralFat!!).isWithin(1e-3f).of(8.0f)
        assertThat(b.musclePercent!!).isWithin(1e-3f).of(38.6f)
        assertThat(b.boneKg!!).isWithin(1e-3f).of(3.2f)
    }

    @Test
    fun `weight without the body-follows status bit`() {
        val f = TerraillonLib.parseWeightFrame(
            hex("1f 4d 1c 00 fe 00 00 00 1f 00 00 00 00 03 14 00 ff 01 01 00")
        )!!
        assertThat(f.bodyFollows).isFalse()
    }

    @Test
    fun `rejects truncated frames`() {
        assertThat(TerraillonLib.parseWeightFrame(hex("1f 4d 1c"))).isNull()
        assertThat(TerraillonLib.parseWeightFrame(hex("1f 4d 1c 00 fe 00 00"))).isNull()
        assertThat(TerraillonLib.parseBodyFrame(hex("7f 00"))).isNull()
    }
}
