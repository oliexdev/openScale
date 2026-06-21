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
 * Unit tests for [RobiS9Handler.parseFinalWeightKg] and the handshake replay.
 *
 * Vectors are taken verbatim from ble-scale-sync tests/scales/robi-s9.test.ts (the
 * reporter's HCI/DEBUG captures, #228/#248): weight is a 3-byte big-endian gram count at
 * offset 5 in the A3 final-result frame.
 */
class RobiS9HandlerTest {

    @Test
    fun `extracts 77_25 kg from the real A3 final frame`() {
        // @vanboxel capture, v1.18.0: 01 2d c2 = 77250 g = 77.25 kg.
        val a3 = RobiS9Handler.hexToBytes("030800a300012dc2000000000000000000000013")
        val kg = RobiS9Handler.parseFinalWeightKg(a3)
        assertThat(kg).isNotNull()
        assertThat(kg!!).isWithin(1e-2f).of(77.25f)
    }

    @Test
    fun `ignores A2 live frames`() {
        val a2 = RobiS9Handler.hexToBytes("1d0700a20400012c000000000000000000000013")
        assertThat(RobiS9Handler.parseFinalWeightKg(a2)).isNull()
    }

    @Test
    fun `rejects short or malformed frames`() {
        assertThat(RobiS9Handler.parseFinalWeightKg(ByteArray(10))).isNull()
        // [2] != 0x00
        assertThat(RobiS9Handler.parseFinalWeightKg(
            RobiS9Handler.hexToBytes("0308ffa300012dc2000000000000000000000013"))).isNull()
    }

    @Test
    fun `handshake replays 11 frames in order starting with the B0 hello`() {
        assertThat(RobiS9Handler.HANDSHAKE).hasSize(11)
        assertThat(RobiS9Handler.HANDSHAKE[0])
            .isEqualTo("000300b000000000000000000000000000000010")
        // seq byte increments 00..0a
        RobiS9Handler.HANDSHAKE.forEachIndexed { i, hex ->
            assertThat(RobiS9Handler.hexToBytes(hex)[0].toInt() and 0xFF).isEqualTo(i)
        }
    }
}
