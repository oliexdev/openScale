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
 * Unit tests for [MGBHandler]'s 8-byte streaming frame parsers (e.g. Dr Trust Smart 505).
 *
 * Vectors are taken from the decoded protocol notes; checksums were hand-verified against
 * `chk = (b2 + b3 + b4 + b5 + b6) & 0xFF`.
 */
class MGBHandlerStreamingTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `extracts 99_10 kg from the final weight frame`() {
        val raw = MGBHandler.parseFinalWeightRaw(hex("AC 02 26 B6 00 00 CA A6"))
        assertThat(raw).isEqualTo(9910)
        assertThat(raw!! / 100.0f).isWithin(1e-3f).of(99.10f)
    }

    @Test
    fun `live frames are not read as final weight`() {
        // Same payload as the final-weight frame, flag still 0xCE (settling).
        assertThat(MGBHandler.parseFinalWeightRaw(hex("AC 02 26 B6 00 00 CE AA")))
            .isNull()
    }

    @Test
    fun `live frames are readable via parseLiveWeightRaw`() {
        val raw = MGBHandler.parseLiveWeightRaw(hex("AC 02 26 B6 00 00 CE AA"))
        assertThat(raw).isEqualTo(9910)
    }

    @Test
    fun `extracts 600 ohm from the impedance frame`() {
        assertThat(MGBHandler.parseImpedanceOhm(hex("AC 02 FD 01 02 58 CB 23")))
            .isEqualTo(600)
    }

    @Test
    fun `FD 00 block-open frame is not an impedance value`() {
        assertThat(MGBHandler.parseImpedanceOhm(hex("AC 02 FD 00 00 00 CB C8")))
            .isNull()
    }

    @Test
    fun `FD FF impedance-unavailable frame is not an impedance value`() {
        assertThat(MGBHandler.parseImpedanceOhm(hex("AC 02 FD FF 00 00 CB C7")))
            .isNull()
    }

    @Test
    fun `date-write config echo is not confused with impedance`() {
        // Same FD-in-b2 shape as impedance frames, but flag is 0xCC (config), not 0xCB.
        assertThat(MGBHandler.parseImpedanceOhm(hex("AC 02 FD 1A 07 18 CC 02")))
            .isNull()
    }

    @Test
    fun `rejects a frame with a bad checksum`() {
        assertThat(MGBHandler.isValidStreamingFrame(hex("AC 02 26 B6 00 00 CA FF")))
            .isFalse()
        assertThat(MGBHandler.parseFinalWeightRaw(hex("AC 02 26 B6 00 00 CA FF")))
            .isNull()
    }

    @Test
    fun `does not accept a 20-byte composite frame as a streaming frame`() {
        assertThat(MGBHandler.isValidStreamingFrame(ByteArray(20))).isFalse()
        assertThat(MGBHandler.parseFinalWeightRaw(ByteArray(20))).isNull()
        assertThat(MGBHandler.parseImpedanceOhm(ByteArray(20))).isNull()
    }
}
