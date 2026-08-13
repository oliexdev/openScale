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
 * Unit tests for [TaylorBIAHandler.decodeWeightKg] — the weight decode reverse-engineered
 * from the Taylor 5331891 BIA Scale's NOTIFY frames in btsnoop_hci.log.
 *
 * Frame layout (20 bytes): AC 27 <flag> <chan> <hi> <lo> ... 24 D5 <cksum>
 *   weight_kg = (((chan & 0x01) << 16) | (hi << 8) | lo) / 1000.0
 *
 * Ground truth: the user's recorded 12:49 pm weigh-in read 171.2 lb, and the matching stable
 * frame in the capture was AC 27 80 8D 2F 52 … → 0x12F52 = 77 650 g = 77.650 kg = 171.2 lb.
 */
class TaylorBIAHandlerTest {

    private fun b(v: Int): Byte = v.toByte()

    @Test
    fun `idle frame decodes to zero`() {
        // AC 27 00 8C 00 00 … — nobody on the scale.
        assertThat(TaylorBIAHandler.decodeWeightKg(b(0x8C), b(0x00), b(0x00)))
            .isWithin(1e-6f).of(0.0f)
    }

    @Test
    fun `stable frame decodes to recorded 171_2 lb`() {
        // AC 27 80 8D 2F 52 … — the locked reading; matches the user's 171.2 lb weigh-in.
        val kg = TaylorBIAHandler.decodeWeightKg(b(0x8D), b(0x2F), b(0x52))
        assertThat(kg).isWithin(1e-4f).of(77.650f)
        assertThat(kg * 2.20462f).isWithin(0.1f).of(171.2f)
    }

    @Test
    fun `stable frame decodes to recorded 173_0 lb`() {
        // AC 27 80 8D 32 40 … — second capture; app showed 173.0 lb.
        val kg = TaylorBIAHandler.decodeWeightKg(b(0x8D), b(0x32), b(0x40))
        assertThat(kg).isWithin(1e-4f).of(78.400f)
        assertThat(kg * 2.20462f).isWithin(0.2f).of(173.0f)
    }

    @Test
    fun `overflow bit adds 65_536 grams`() {
        // 0x8D sets bit 16: weight = 65536 + (hi<<8 | lo) grams.
        assertThat(TaylorBIAHandler.decodeWeightKg(b(0x8D), b(0x00), b(0x00)))
            .isWithin(1e-6f).of(65.536f)
        // A sub-65.5 kg reading on the 0x8C channel uses no overflow bit.
        assertThat(TaylorBIAHandler.decodeWeightKg(b(0x8C), b(0x9C), b(0x40)))
            .isWithin(1e-4f).of(40.0f) // 0x9C40 = 40000 g
    }
}
