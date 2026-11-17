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
import com.health.openscale.core.bluetooth.libs.EufyT9140Lib.Frame
import com.health.openscale.core.bluetooth.libs.EufyT9140Lib.Record
import org.junit.Test

/**
 * Frames are verbatim from the EufyLife-app HCI snoop logs attached to openScale issue #772
 * (a scale advertising "eufy T9140", app in kg mode, user 190.5 cm male). The app reported:
 *   capture 1  76.0 kg  2021-09-11 2:52 PM PDT (delivered as a stored record only)
 *   capture 2  79.9 kg  2021-09-11 3:53 PM PDT
 *   capture 3  84.0 kg  2021-09-11 4:23 PM PDT
 */
class EufyT9140LibTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun single(s: String): Frame = EufyT9140Lib.parseNotification(hex(s)).single()

    @Test
    fun `checksum is the sum of the bytes between header and checksum`() {
        for (f in listOf("ac02031f0000caec", "ac02fd01022bcbf6", "ac02fe1cff00cce5", "ac02f16b333d61ccf9")) {
            val b = hex(f)
            assertThat(EufyT9140Lib.checksum(b)).isEqualTo(b.last().toInt() and 0xFF)
        }
    }

    @Test
    fun `live and stable weight frames decode in 0_1 kg steps`() {
        // Capture 2: ac 02 | 03 1f | 00 00 | ce/ca | ck  ->  0x031F = 799 -> 79.9 kg
        assertThat(single("ac02031f0000cef0")).isEqualTo(Frame.Weight(799, stable = false))
        assertThat(single("ac02031f0000caec")).isEqualTo(Frame.Weight(799, stable = true))
        // Capture 3: 0x0348 = 840 -> 84.0 kg
        assertThat((single("ac0203480000ca15") as Frame.Weight).kg).isWithin(1e-4f).of(84.0f)
    }

    @Test
    fun `packed 16-byte notification carries impedance plus a repeated stable weight`() {
        // ac 02 | fd 01 | 02 2b | cb | ck   ->  impedance 0x022B = 555 ohm, then the stable frame again
        val frames = EufyT9140Lib.parseNotification(hex("ac02fd01022bcbf6ac02031f0000caec"))
        assertThat(frames).containsExactly(Frame.Impedance(555), Frame.Weight(799, stable = true)).inOrder()

        assertThat(EufyT9140Lib.parseNotification(hex("ac02fd000000cbc8ac02031f0000caec")).first())
            .isEqualTo(Frame.ImpedanceStarted)
        assertThat(EufyT9140Lib.parseNotification(hex("ac02fd010223cbeeac0203480000ca15")).first())
            .isEqualTo(Frame.Impedance(547))
    }

    @Test
    fun `packed 17-byte notification carries the measurement time`() {
        // ac 02 | f1 | 6b 33 3d 61 (LE) | cc | ck  ->  0x613D336B = 2021-09-11 22:53:31 UTC (3:53 PM PDT)
        val frames = EufyT9140Lib.parseNotification(hex("ac02f16b333d61ccf9ac02031f0000caec"))
        assertThat(frames).containsExactly(Frame.MeasurementTime(0x613D336BL), Frame.Weight(799, true)).inOrder()
    }

    @Test
    fun `final record holds time, weight and impedance`() {
        // ac 02 fe | 6b 33 3d 61 | 03 1f | 02 2b | 00 x8
        assertThat(single("ac02fe6b333d61031f022b0000000000000000"))
            .isEqualTo(Frame.FinalRecord(Record(0x613D336BL, 799, 555)))
        assertThat(single("ac02fe633a3d61034802230000000000000000"))
            .isEqualTo(Frame.FinalRecord(Record(0x613D3A63L, 840, 547)))
    }

    @Test
    fun `history frames hold up to two records`() {
        // Capture 1: the 76.0 kg weigh-in at 21:52:34 UTC (= the app's 2:52 PM PDT) and an earlier one.
        assertThat(single("ac02ff22253d6102f80236920e3d6102f80237")).isEqualTo(
            Frame.History(listOf(Record(0x613D2522L, 760, 566), Record(0x613D0E92L, 760, 567)))
        )
        // The second slot is empty (all zero).
        assertThat(single("ac02ffd8623c6102f8021a0000000000000000"))
            .isEqualTo(Frame.History(listOf(Record(0x613C62D8L, 760, 538))))
    }

    @Test
    fun `status frames`() {
        assertThat(single("ac02fe070000ccd1")).isEqualTo(Frame.Status(EufyT9140Lib.STATUS_TIME_SYNC_OK, 0))
        assertThat(single("ac02fe06fe00ccce")).isEqualTo(Frame.Status(EufyT9140Lib.STATUS_UNIT_SET, 0xFE))
        assertThat(single("ac02fe010000cfce")).isEqualTo(Frame.Status(EufyT9140Lib.STATUS_HISTORY_START, 0))
        assertThat(single("ac02fe020000cfcf")).isEqualTo(Frame.Status(EufyT9140Lib.STATUS_HISTORY_END, 0))
        assertThat(single("ac02fe100000ccda")).isEqualTo(Frame.Status(EufyT9140Lib.STATUS_MEASUREMENT_FINISHED, 0))
        assertThat(single("ac02fe1cff00cce5")).isEqualTo(Frame.Status(EufyT9140Lib.STATUS_READY_POWER_OFF, 0xFF))
        assertThat(single("ac02fe1a0000cce4")).isEqualTo(Frame.Status(EufyT9140Lib.STATUS_POWER_OFF, 0))
    }

    @Test
    fun `a corrupted frame is rejected`() {
        assertThat(single("ac02031f0000caed")).isInstanceOf(Frame.Unknown::class.java)
    }

    @Test
    fun `commands match what the vendor app wrote`() {
        assertThat(EufyT9140Lib.timeSync(0x613D335CL)).isEqualTo(hex("ac02f25c333d61cceb"))
        assertThat(EufyT9140Lib.timeSync(0x613D2543L)).isEqualTo(hex("ac02f243253d61ccc4"))
        assertThat(EufyT9140Lib.setUnit(EufyT9140Lib.UNIT_KG)).isEqualTo(hex("ac02fe060000ccd0"))
        assertThat(EufyT9140Lib.historyRequest()).isEqualTo(hex("ac02ff000000cfce"))
    }

    // --- Second unit (2026 firmware, service 4143f7b0), captured with capture_scale.py ----------

    @Test
    fun `2026 firmware preamble frames are recognised`() {
        // Sent before every result, identical each time; checksum valid, payload meaning unknown.
        assertThat(single("ac02f423100bccfe")).isEqualTo(Frame.Preamble(0xF4))
        assertThat(single("ac02f723152acc25")).isEqualTo(Frame.Preamble(0xF7))
    }

    @Test
    fun `2026 firmware result frames decode`() {
        // Display showed 50.2 lb; the wire still carries 0.1 kg: 0x00E4 = 22.8 kg (50.3 lb).
        assertThat(single("ac0200e40000caae")).isEqualTo(Frame.Weight(228, stable = true))
        assertThat(EufyT9140Lib.parseNotification(hex("ac02fd0102decba9ac0200e40000caae")))
            .containsExactly(Frame.Impedance(734), Frame.Weight(228, true)).inOrder()
        // 0x6AFA8DD3 = 2026-11-16 05:00:03 UTC
        assertThat(EufyT9140Lib.parseNotification(hex("ac02f1d38dfa6acc81ac0200e40000caae")).first())
            .isEqualTo(Frame.MeasurementTime(0x6AFA8DD3L))
    }

    @Test
    fun `2026 firmware history frames decode`() {
        // One stored record (synthetic values, real layout): 2026-11-10 12:00:00 UTC, 75.0 kg, 500 ohm.
        assertThat(single("ac02ff4007f36a02ee01f40000000000000000"))
            .isEqualTo(Frame.History(listOf(Record(0x6AF30740L, 750, 500))))
        // Two offline weigh-ins: 05:11:48Z 23.6 kg 552 ohm, 05:12:02Z 22.4 kg 581 ohm.
        assertThat(single("ac02ff9490fa6a00ec0228a290fa6a00e00245")).isEqualTo(
            Frame.History(listOf(Record(0x6AFA9094L, 236, 552), Record(0x6AFA90A2L, 224, 581)))
        )
    }

    @Test
    fun `unit codes`() {
        assertThat(EufyT9140Lib.setUnit(EufyT9140Lib.UNIT_KG)).isEqualTo(hex("ac02fe060000ccd0"))
    }
}
