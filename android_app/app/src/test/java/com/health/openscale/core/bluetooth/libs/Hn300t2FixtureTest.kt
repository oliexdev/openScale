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
import org.junit.Test

class Hn300t2FixtureTest {

    @Test
    fun `weigh-in trace has eight WLD writes and the handshake pair`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        assertThat(t.wldWrites).hasSize(8)
        assertThat(t.wldWrites[0]).isEqualTo(hex("08 00 00 00 00 10 00 18"))
        assertThat(t.wldWrites[7]).isEqualTo(hex("08 0f 00 00 00 00 00 07"))
        assertThat(t.handshakeWrite[0]).isEqualTo(0x11.toByte())
        assertThat(t.handshakeNotify.copyOfRange(0, 2)).isEqualTo(hex("91 00"))
    }

    @Test
    fun `pairing trace has seven WLD writes`() {
        assertThat(Hn300t2Fixture.load("frames-pairing").wldWrites).hasSize(7)
    }

    @Test
    fun `notifies are collected in order`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        assertThat(t.wldNotifies).hasSize(8)
        assertThat(t.wldNotifies[0]).isEqualTo(hex("08 80 00 00 00 10 00 98"))
    }

    private fun hex(s: String): ByteArray = s.filterNot { it.isWhitespace() }
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
