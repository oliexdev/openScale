/*
 * openScale
 * Copyright (C) 2026 nadelacruz
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
import java.util.Calendar
import java.util.Date
import org.junit.Test

class OmronHn300t2LibTest {

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun localDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Date =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.time

    @Test
    fun `session token frame is opcode 0x11 plus token padded to 20 bytes`() {
        val f = OmronHn300t2Lib.sessionToken(hex("da c7 4a 42"))
        assertThat(f).hasLength(20)
        assertThat(f.copyOfRange(0, 5)).isEqualTo(hex("11 da c7 4a 42"))
        assertThat(f.copyOfRange(5, 20).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `echo matches only the same token`() {
        val tok = hex("da c7 4a 42")
        assertThat(OmronHn300t2Lib.echoMatches(hex("91 00 da c7 4a 42 00 00"), tok)).isTrue()
        assertThat(OmronHn300t2Lib.echoMatches(hex("91 00 5d 22 47 21 00 00"), tok)).isFalse()
        assertThat(OmronHn300t2Lib.echoMatches(hex("91 00"), tok)).isFalse()
    }

    @Test
    fun `fixture handshake pair matches`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val tok = t.handshakeWrite.copyOfRange(1, 5)
        assertThat(OmronHn300t2Lib.sessionToken(tok)).isEqualTo(t.handshakeWrite)
        assertThat(OmronHn300t2Lib.echoMatches(t.handshakeNotify, tok)).isTrue()
    }

    @Test
    fun `timestamp decodes the documented examples and rejects garbage`() {
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("1a 0b 04 03 11 27"), 0))
            .isEqualTo(localDate(2026, 11, 4, 3, 17, 39))
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("ff ff ff ff ff ff"), 0)).isNull()
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("10 01 01 00 00 00"), 0)).isNull() // 2016
    }

    @Test
    fun `timestamp honours offset and rejects short input`() {
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("00 00 1a 0b 04 03 11 27"), 2))
            .isEqualTo(localDate(2026, 11, 4, 3, 17, 39))
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("1a 0b 04 03 11"), 0)).isNull()
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("1a 0b 04 03 11 27"), 1)).isNull()
    }

    @Test
    fun `timestamp rejects impossible dates and the far future`() {
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("18 02 1f 00 00 00"), 0)).isNull() // 31 Feb
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("1a 0d 01 00 00 00"), 0)).isNull() // month 13
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("1a 01 01 18 00 00"), 0)).isNull() // hour 24
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("1a 01 01 00 3c 00"), 0)).isNull() // minute 60
        assertThat(OmronHn300t2Lib.decodeTimestamp(hex("63 01 01 00 00 00"), 0)).isNull() // year 2099
    }

    @Test
    fun `weigh-in index block points at record 18 with one unsent in slot 0`() {
        val ib = OmronHn300t2Lib.decodeIndexBlock(hex("13 00 00 00 01 00 00 00 00 13 00 00"))!!
        assertThat(ib.newestRecord[0]).isEqualTo(18)
        assertThat(ib.unsent[0]).isEqualTo(1)
        assertThat(OmronHn300t2Lib.recordAddress(0, 18)).isEqualTo(0x03E0)
    }

    @Test
    fun `pairing index block masks the status bits and has nothing unsent`() {
        val ib = OmronHn300t2Lib.decodeIndexBlock(hex("92 00 00 00 80 00 00 00 00 12 00 80"))!!
        assertThat(ib.newestRecord[0]).isEqualTo(17)
        assertThat(ib.unsent.all { it == 0 }).isTrue()
    }

    @Test
    fun `a zero pointer has no newest record`() {
        assertThat(OmronHn300t2Lib.decodeIndexBlock(hex("00 00 00 00 00 00 00 00"))!!.newestRecord[0]).isEqualTo(-1)
    }

    @Test
    fun `a short index block decodes to nothing`() {
        assertThat(OmronHn300t2Lib.decodeIndexBlock(hex("13 00 00 00 01 00 00"))).isNull()
    }

    @Test
    fun `slots 1 to 3 have no known address yet`() {
        assertThat(OmronHn300t2Lib.recordAddress(1, 0)).isNull()
    }

    @Test
    fun `fixture record decodes timestamp and the synthetic weight in 50 g units`() {
        val r = OmronHn300t2Lib.decodeRecord(hex("06 40 1a 0b 04 03 11 22 00 00 00 00 00 00 00 00"))!!
        assertThat(r.timestamp).isEqualTo(localDate(2026, 11, 4, 3, 17, 34))
        assertThat(r.weightRaw).isEqualTo(1600)
        assertThat(r.weightKg).isWithin(0.001f).of(80.0f)
    }

    @Test
    fun `bytes 11 and 12 do not affect the weight`() {
        val r = OmronHn300t2Lib.decodeRecord(hex("06 40 1a 0b 04 03 11 22 00 00 00 03 20 00 00 00"))!!
        assertThat(r.weightRaw).isEqualTo(1600)
    }

    @Test
    fun `short or erased records decode to nothing`() {
        assertThat(OmronHn300t2Lib.decodeRecord(hex("00 00 1a 0b"))).isNull()
        assertThat(OmronHn300t2Lib.decodeRecord(ByteArray(16) { 0xFF.toByte() })).isNull()
    }
}
