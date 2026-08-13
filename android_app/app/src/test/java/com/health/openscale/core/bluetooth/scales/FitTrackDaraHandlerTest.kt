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
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.utils.ConverterUtils
import org.junit.Test

/**
 * Unit tests for the FitTrack Dara 2.0 frame decode ([FitTrackDaraHandler.checksum8] /
 * [FitTrackDaraHandler.weightDeciUnits]), reverse-engineered from a btsnoop_hci.log capture.
 *
 * Frame (8 bytes): AC 02 <b2> <b3> <b4> <b5> <chan> <cksum>
 *   cksum = (b2 + b3 + b4 + b5 + chan) & 0xFF
 *   weight frames (chan 0xCE/0xCA): weight_display = ((b2<<8)|b3) / 10.0
 *
 * Ground truth from the capture: the stable weight frame AC 02 05 5F 00 00 CA ..
 *   decodes 0x055F = 1375 → 137.5 lb → 62.37 kg.
 */
class FitTrackDaraHandlerTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `checksum matches captured command frame`() {
        // AC 02 FE 00 00 00 CC CA  (FE+00+00+00+CC = 0x1CA → 0xCA)
        val frame = bytes(0xAC, 0x02, 0xFE, 0x00, 0x00, 0x00, 0xCC, 0xCA)
        assertThat(FitTrackDaraHandler.checksum8(frame)).isEqualTo(0xCA)
    }

    @Test
    fun `checksum matches captured live-weight frame`() {
        // AC 02 05 51 00 00 CE 24  (05+51+00+00+CE = 0x124 → 0x24)
        val frame = bytes(0xAC, 0x02, 0x05, 0x51, 0x00, 0x00, 0xCE, 0x24)
        assertThat(FitTrackDaraHandler.checksum8(frame)).isEqualTo(0x24)
    }

    @Test
    fun `weight decodes to recorded 137_5 lb`() {
        // Stable frame value bytes 05 5F.
        val deci = FitTrackDaraHandler.weightDeciUnits(0x05.toByte(), 0x5F.toByte())
        assertThat(deci).isEqualTo(1375)

        val displayLb = deci / 10.0f
        assertThat(displayLb).isWithin(1e-4f).of(137.5f)

        // openScale stores kg: 137.5 lb ≈ 62.37 kg.
        val kg = ConverterUtils.toKilogram(displayLb, WeightUnit.LB)
        assertThat(kg).isWithin(0.05f).of(62.37f)
    }

    @Test
    fun `weight in kg passes through unchanged`() {
        // If the user's unit is kg, the same raw would be 62.4 kg (0x0270 = 624 → 62.4).
        val deci = FitTrackDaraHandler.weightDeciUnits(0x02.toByte(), 0x70.toByte())
        assertThat(deci).isEqualTo(624)
        assertThat(ConverterUtils.toKilogram(deci / 10.0f, WeightUnit.KG))
            .isWithin(1e-4f).of(62.4f)
    }
}
