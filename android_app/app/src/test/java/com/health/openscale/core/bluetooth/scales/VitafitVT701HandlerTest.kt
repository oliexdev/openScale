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
 * Unit tests for [VitafitVT701Handler] frame parsing and command building.
 *
 * Vectors are taken verbatim from the HCI snoop of a Vitafit-app weigh-in; the stable weight
 * frame was confirmed against the scale's displayed 85.00 kg reading.
 */
class VitafitVT701HandlerTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `extracts 85_00 kg from the real stable frame`() {
        val raw = VitafitVT701Handler.parseStableWeightRaw(hex("5a 0a 26 10 02 00 00 21 21 34 0a aa"))
        assertThat(raw).isEqualTo(8500)
        assertThat(raw!! / 100.0f).isWithin(1e-3f).of(85.00f)
    }

    @Test
    fun `ignores settling frames whose stability flag is not 0x02`() {
        // flag 0x01, still measuring (85.15 kg preview).
        assertThat(VitafitVT701Handler.parseStableWeightRaw(hex("5a 0a 26 10 01 00 00 21 21 43 7e aa")))
            .isNull()
    }

    @Test
    fun `extracts impedance from the type 0x11 frames of both weigh-ins`() {
        // 85.00 kg weigh-in: 01 89 = 393 Ohm.
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 89 b4 aa")))
            .isEqualTo(393)
        // 84.95 kg weigh-in: 01 85 = 389 Ohm.
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 85 b8 aa")))
            .isEqualTo(389)
    }

    @Test
    fun `does not confuse weight and impedance frame types`() {
        // A weight frame is not an impedance frame and vice versa.
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0a 26 10 02 00 00 21 21 34 0a aa")))
            .isNull()
        assertThat(VitafitVT701Handler.parseStableWeightRaw(hex("5a 0b 26 11 00 00 00 00 00 01 89 b4 aa")))
            .isNull()
    }

    @Test
    fun `rejects malformed and bad-checksum frames`() {
        // corrupted checksum byte.
        assertThat(VitafitVT701Handler.parseStableWeightRaw(hex("5a 0a 26 10 02 00 00 21 21 34 ff aa")))
            .isNull()
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 89 ff aa")))
            .isNull()
        // truncated.
        assertThat(VitafitVT701Handler.parseStableWeightRaw(ByteArray(5))).isNull()
    }

    @Test
    fun `builds the observed hello commands with correct length and checksum`() {
        assertThat(VitafitVT701Handler.buildCommand(0x33, byteArrayOf(0x00)))
            .isEqualTo(hex("a5 05 26 33 00 10 aa"))
        assertThat(VitafitVT701Handler.buildCommand(0x44, byteArrayOf()))
            .isEqualTo(hex("a5 04 26 44 66 aa"))
        assertThat(VitafitVT701Handler.buildCommand(0x17, byteArrayOf(0x01)))
            .isEqualTo(hex("a5 05 26 17 01 35 aa"))
    }
}
