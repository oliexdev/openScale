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
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * Unit tests for [ChipseaBleLib] — the Chipsea-BLE GATT protocol.
 *
 * Frames are synthetic, built byte by byte from the layout documented in [ChipseaBleLib].
 * The reference measurement used throughout: 75.5 kg, 23.4 % body fat, 55.1 % water,
 * 32.8 % muscle, 1650 kcal BMR, visceral fat 9.0, 2.9 kg bone mass.
 */
class ChipseaBleLibTest {

    private val lib = ChipseaBleLib()

    @Test
    fun `user sync v10 frame layout and checksum`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 21, 23, 30, 15)
            set(Calendar.MILLISECOND, 0)
        }.time

        val frame = ChipseaBleLib.buildUserSyncV10(
            userId = 1, isMale = true, age = 40, heightCm = 170, now = now,
        )

        // CA 10 0E 01 | 26 10 21 23 30 15 (yy MM dd HH mm ss) | user id 1 (BE) |
        // sex=1 age=40 height=170 | XOR of bytes 1..16 = 0x9E | 00 00
        assertThat(frame).isEqualTo(
            hex("CA 10 0E 01 1A 0A 15 17 1E 0F 00 00 00 01 01 28 AA 9E 00 00"),
        )
    }

    @Test
    fun `single frame unlocked is a live weight in kg`() {
        // property 0x00: kg, 1 decimal digit, unlocked; weight raw 755 -> 75.5 kg
        val m = lib.feed(hex("CA 10 12 00 00 02 F3"))

        assertThat(m).isNotNull()
        assertThat(m!!.locked).isFalse()
        assertThat(m.weightKg).isWithin(0.001f).of(75.5f)
        assertThat(m.bodyFatPercent).isNull()
    }

    @Test
    fun `single frame locked carries body composition`() {
        // property 0x01: kg, 1 decimal digit, locked
        // weight 02F3, fat 00EA (23.4), water 0227 (55.1), muscle 0148 (32.8),
        // BMR 0672 (1650), visceral 005A (9.0), bone 1D (2.9)
        val m = lib.feed(hex("CA 10 12 00 01 02 F3 00 EA 02 27 01 48 06 72 00 5A 1D"))

        assertThat(m).isNotNull()
        assertThat(m!!.locked).isTrue()
        assertThat(m.weightKg).isWithin(0.001f).of(75.5f)
        assertThat(m.bodyFatPercent!!).isWithin(0.001f).of(23.4f)
        assertThat(m.waterPercent!!).isWithin(0.001f).of(55.1f)
        assertThat(m.musclePercent!!).isWithin(0.001f).of(32.8f)
        assertThat(m.bmrKcal).isEqualTo(1650)
        assertThat(m.visceralFat!!).isWithin(0.001f).of(9.0f)
        assertThat(m.boneKg!!).isWithin(0.001f).of(2.9f)
        assertThat(m.timestamp).isNull()
    }

    @Test
    fun `weight in pounds is converted to kg`() {
        // property 0x10: lb (bits 3..4 = 0b10), 1 decimal digit, unlocked; raw 1653 -> 165.3 lb
        val m = lib.feed(hex("CA 10 12 00 10 06 75"))

        assertThat(m).isNotNull()
        assertThat(m!!.weightKg).isWithin(0.01f).of(74.98f)
    }

    @Test
    fun `v11 simple weight frame uses subtype as lock flag`() {
        // subtype 1 (locked), weight at [5..6], property 0x01 at [11]
        val m = lib.feed(hex("CA 11 0A 01 00 02 F3 00 00 00 00 01"))

        assertThat(m).isNotNull()
        assertThat(m!!.locked).isTrue()
        assertThat(m.weightKg).isWithin(0.001f).of(75.5f)
        assertThat(m.bodyFatPercent).isNull()
    }

    @Test
    fun `v11 multi packet live measurement reassembles`() {
        // Assembled payload (23 bytes): ts=0, property 0x01, role id 1, weight 02F3,
        // fat 00EA, water 0227, muscle 0148, BMR 0672, visceral 005A, bone 1D.
        // Chunk 1: 14 payload bytes, split byte (index 1 << 4) | total 2 = 0x12, len 14+2.
        val first = lib.feed(
            hex("CA 11 10 12 12 00 00 00 00 01 00 00 00 00 01 02 F3 00 EA"),
        )
        assertThat(first).isNull()

        // Chunk 2: remaining 9 payload bytes, split (2 << 4) | 2 = 0x22, len 9+2.
        val m = lib.feed(hex("CA 11 0B 12 22 02 27 01 48 06 72 00 5A 1D"))

        assertThat(m).isNotNull()
        assertThat(m!!.locked).isTrue()
        assertThat(m.weightKg).isWithin(0.001f).of(75.5f)
        assertThat(m.bodyFatPercent!!).isWithin(0.001f).of(23.4f)
        assertThat(m.bmrKcal).isEqualTo(1650)
        assertThat(m.timestamp).isNull()
    }

    @Test
    fun `v11 multi packet live measurement unlocked is a weight only preview`() {
        // Same payload as the locked live test but property 0x00: the lock flag is
        // clear, so the composition fields must not be reported as final values.
        val first = lib.feed(
            hex("CA 11 10 12 12 00 00 00 00 00 00 00 00 00 01 02 F3 00 EA"),
        )
        assertThat(first).isNull()

        val m = lib.feed(hex("CA 11 0B 12 22 02 27 01 48 06 72 00 5A 1D"))

        assertThat(m).isNotNull()
        assertThat(m!!.locked).isFalse()
        assertThat(m.weightKg).isWithin(0.001f).of(75.5f)
        assertThat(m.bodyFatPercent).isNull()
        assertThat(m.bmrKcal).isNull()
    }

    @Test
    fun `v11 multi packet with inconsistent chunk totals is dropped`() {
        // Chunks 1 and 4 of a 5-chunk sequence, then chunk 3 claiming a total of 3:
        // the stored count matches the new total but index 2 was never received,
        // so the sequence must be dropped instead of throwing on the missing chunk.
        assertThat(lib.feed(hex("CA 11 06 12 15 01 02 03 04"))).isNull()
        assertThat(lib.feed(hex("CA 11 06 12 45 05 06 07 08"))).isNull()
        assertThat(lib.feed(hex("CA 11 06 12 33 09 0A 0B 0C"))).isNull()

        // A following well-formed sequence still completes normally.
        assertThat(
            lib.feed(hex("CA 11 10 12 12 00 00 00 00 01 00 00 00 00 01 02 F3 00 EA")),
        ).isNull()
        val m = lib.feed(hex("CA 11 0B 12 22 02 27 01 48 06 72 00 5A 1D"))
        assertThat(m).isNotNull()
        assertThat(m!!.locked).isTrue()
    }

    @Test
    fun `v11 multi packet history record carries its timestamp`() {
        // Same payload as the live test but timestamp 0x6553F100 = 1700000000 s,
        // subtype 19, single chunk: split (1 << 4) | 1 = 0x11, len 23+2 = 0x19.
        val m = lib.feed(
            hex("CA 11 19 13 11 65 53 F1 00 01 00 00 00 00 01 02 F3 00 EA 02 27 01 48 06 72 00 5A 1D"),
        )

        assertThat(m).isNotNull()
        assertThat(m!!.timestamp).isEqualTo(Date(1_700_000_000_000L))
        assertThat(m.weightKg).isWithin(0.001f).of(75.5f)
    }

    @Test
    fun `foreign and truncated frames are ignored`() {
        assertThat(lib.feed(ByteArray(0))).isNull()
        assertThat(lib.feed(hex("FA 04 01 02"))).isNull() // WeChat-scale protocol, not ours
        assertThat(lib.feed(hex("CA 10 12"))).isNull()
        assertThat(lib.feed(hex("CA 11 02 07 00"))).isNull() // unknown v1.1 subtype
    }
}
