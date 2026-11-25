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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import java.util.Calendar
import org.junit.Test

/**
 * Live stream and time-sync frames of the Soehnle scales.
 *
 * The frame layout comes from HCI captures of the vendor app on a Shape Sense Connect 50
 * and a Shape Sense Connect 200 (capture attached to issue #487): after the app writes
 * `30 FF 01`, 352e3001 delivers `30 <user> 00 <weight>` with zero impedances about three
 * times a second while the weight settles, then one final frame with a non-zero status
 * (0x11 on the Shape50, 0x21 on the Shape200) carrying both impedances.
 *
 * The Shape50 payloads below use the captured layout with neutral values (80.0 kg,
 * 400 / 380 Ohm); the Shape200 frames are the ones from issue #487.
 */
class SoehnleLiveFrameTest {

    @Test
    fun `decodes a settling frame`() {
        val f = SoehnleLiveFrame.parse(hex("30 01 00 03 20 00 00 00 00"))!!
        assertThat(f.userIndex).isEqualTo(1)
        assertThat(f.isFinal).isFalse()
        assertThat(f.weightKg).isWithin(0.001f).of(80.0f)
        assertThat(f.imp5).isEqualTo(0)
        assertThat(f.imp50).isEqualTo(0)
    }

    @Test
    fun `decodes the final frame of the Shape50`() {
        val f = SoehnleLiveFrame.parse(hex("30 02 11 03 20 01 90 01 7C"))!!
        assertThat(f.userIndex).isEqualTo(2)
        assertThat(f.status).isEqualTo(0x11)
        assertThat(f.isFinal).isTrue()
        assertThat(f.weightKg).isWithin(0.001f).of(80.0f)
        assertThat(f.imp5).isEqualTo(400)
        assertThat(f.imp50).isEqualTo(380)
    }

    @Test
    fun `treats the Shape200 final status as final`() {
        assertThat(SoehnleLiveFrame.parse(hex("30 01 00 05 69 00 00 00 00"))!!.isFinal).isFalse()
        val f = SoehnleLiveFrame.parse(hex("30 01 21 05 69 11 EF 01 9D"))!!
        assertThat(f.isFinal).isTrue()
        assertThat(f.weightKg).isWithin(0.001f).of(138.5f)
    }

    @Test
    fun `ignores history and user list frames`() {
        // 0x09 history frame (15 bytes) and 0x0C user list frame share the characteristic
        assertThat(SoehnleLiveFrame.parse(hex("09 01 07 E3 08 0D 09 04 33 05 AF 11 FD 15 DC"))).isNull()
        assertThat(SoehnleLiveFrame.parse(hex("0C 01 00 28 00 00 AA 00 00 03"))).isNull()
    }

    @Test
    fun `rejects flagged or missing impedances`() {
        assertThat(SoehnleLib.isValidImpedance(0x0172)).isTrue()
        assertThat(SoehnleLib.isValidImpedance(0x019D)).isTrue()
        assertThat(SoehnleLib.isValidImpedance(0x0000)).isFalse()
        assertThat(SoehnleLib.isValidImpedance(0x10A4)).isFalse()
        assertThat(SoehnleLib.isValidImpedance(0x15DC)).isFalse()
    }

    @Test
    fun `decodes user list entries`() {
        // Shape200 user list from issue #487: slot 1 registered, slot 2 free
        assertThat(SoehnleUserSlot.parse(hex("0C 01 01 29 00 00 C5 05 51 02")))
            .isEqualTo(SoehnleUserSlot(index = 1, registered = true))
        assertThat(SoehnleUserSlot.parse(hex("0C 02 00 28 00 00 AA 00 00 03")))
            .isEqualTo(SoehnleUserSlot(index = 2, registered = false))
        assertThat(SoehnleUserSlot.parse(hex("30 01 00 03 20 00 00 00 00"))).isNull()
    }

    @Test
    fun `current time uses the 10-byte layout the vendor app writes`() {
        // year LE, month, day, hours, minutes, seconds, day of week (Mon = 1),
        // fractions256 and adjust reason (left at zero)
        val payload = soehnleCurrentTime(2026, 3, 18, 7, 5, 9, Calendar.WEDNESDAY)
        assertThat(payload).isEqualTo(hex("EA 07 03 12 07 05 09 03 00 00"))
    }

    @Test
    fun `maps Sunday and Monday to the Bluetooth day of week`() {
        assertThat(soehnleCurrentTime(2026, 3, 22, 0, 0, 0, Calendar.SUNDAY)[7]).isEqualTo(7.toByte())
        assertThat(soehnleCurrentTime(2026, 3, 23, 0, 0, 0, Calendar.MONDAY)[7]).isEqualTo(1.toByte())
    }
}
