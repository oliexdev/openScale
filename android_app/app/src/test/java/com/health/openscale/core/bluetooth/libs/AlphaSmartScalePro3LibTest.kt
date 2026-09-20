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
import com.health.openscale.core.bluetooth.libs.AlphaSmartScalePro3Lib.ScaleFrame
import org.junit.Test

/**
 * Unit tests for [AlphaSmartScalePro3Lib] — the wire protocol of the ALPHA Smart Scale PRO 3.
 *
 * All payloads were captured with the vendor's FitDays app (btsnoop HCI log, 2026-09-20). The
 * final result of the last weigh-in reads 88 970 g and 469 Ohm; FitDays showed it as 88.95 kg,
 * i.e. rounded to the scale's 0.05 kg display resolution.
 */
class AlphaSmartScalePro3LibTest {

    // Live weight (0xD5): settling, then the same weight locked ~1 s later.
    private val liveSettling = hex("AC 27 00 69 5B 8A 02 00 30 01 00 00 00 00 00 00 00 24 D5 1A")
    private val liveLocked = hex("AC 27 80 69 5B 8A 02 00 30 01 00 00 00 00 00 00 00 24 D5 1A")

    // Final result (0xD6): 88 970 g with 469 Ohm, and 89 240 g without impedance (socks).
    private val finalWithImpedance = hex("AC 27 01 00 01 D5 01 80 69 5B 8A 00 00 00 00 00 00 24 D6 00")
    private val finalWithoutImpedance = hex("AC 27 01 00 00 00 01 80 69 5C 98 00 00 00 00 00 00 24 D6 19")

    // Stored record (0xD8): 6 390 g at Unix time 0x6AB04EE1 (2026-09-20 21:23:45 UTC).
    private val storedRecord = hex("AC 27 00 6A B0 4E E1 80 68 18 F6 00 00 00 01 00 00 24 D8 1C")

    // --- Validation ------------------------------------------------------------

    @Test
    fun `every captured scale frame carries the 5-bit checksum`() {
        val captured = listOf(
            liveSettling, liveLocked, finalWithImpedance, finalWithoutImpedance, storedRecord,
            hex("AC 27 00 68 00 00 02 00 30 01 00 00 00 00 00 00 00 24 D5 14"), // nobody on the scale
            hex("AC 27 00 69 5A AE 02 00 30 01 00 00 00 00 00 00 00 24 D5 1D"),
            hex("AC 27 01 00 01 D5 01 80 69 5A AE 00 00 00 00 00 00 24 D6 03"),
            hex("AC 27 00 6A B0 39 38 80 68 47 36 00 00 00 01 00 00 24 D8 0D"),
        )
        captured.forEach { frame ->
            assertThat(AlphaSmartScalePro3Lib.isValidScaleFrame(frame)).isTrue()
            assertThat(AlphaSmartScalePro3Lib.parse(frame)).isNotNull()
        }
    }

    @Test
    fun `rejects frames with a bad checksum, a wrong header or a wrong length`() {
        val badChecksum = liveSettling.copyOf().also { it[19] = 0x1B }
        val badHeader = liveSettling.copyOf().also { it[1] = 0x02 }
        val truncated = liveSettling.copyOf(19)
        val eightByteMgbFrame = hex("AC 02 26 B6 00 00 CA A6")

        listOf(badChecksum, badHeader, truncated, eightByteMgbFrame, ByteArray(0)).forEach { frame ->
            assertThat(AlphaSmartScalePro3Lib.isValidScaleFrame(frame)).isFalse()
            assertThat(AlphaSmartScalePro3Lib.parse(frame)).isNull()
        }
    }

    @Test
    fun `a valid frame of an unknown wire type is reported as such`() {
        val frame = liveSettling.copyOf().also { it[18] = 0xD9.toByte() }
        frame[19] = AlphaSmartScalePro3Lib.scaleChecksum(frame).toByte()

        assertThat(AlphaSmartScalePro3Lib.parse(frame)).isEqualTo(ScaleFrame.Unknown(0xD9))
    }

    // --- Live weight -----------------------------------------------------------

    @Test
    fun `decodes the live weight and the locked state`() {
        assertThat(AlphaSmartScalePro3Lib.parse(liveSettling))
            .isEqualTo(ScaleFrame.LiveWeight(weightGrams = 88_970, locked = false))
        assertThat(AlphaSmartScalePro3Lib.parse(liveLocked))
            .isEqualTo(ScaleFrame.LiveWeight(weightGrams = 88_970, locked = true))
    }

    @Test
    fun `decodes the empty scale and the step-on ramp`() {
        // Consecutive live frames while stepping on: 0 g, 1 620 g, 61 560 g, 92 040 g overshoot.
        val ramp = listOf(
            hex("AC 27 00 68 00 00 02 00 30 01 00 00 00 00 00 00 00 24 D5 14") to 0,
            hex("AC 27 00 68 06 54 02 00 30 01 00 00 00 00 00 00 00 24 D5 0E") to 1_620,
            hex("AC 27 00 68 F0 78 02 00 30 01 00 00 00 00 00 00 00 24 D5 1C") to 61_560,
            hex("AC 27 00 69 67 88 02 00 30 01 00 00 00 00 00 00 00 24 D5 04") to 92_040,
        )
        ramp.forEach { (frame, grams) ->
            assertThat((AlphaSmartScalePro3Lib.parse(frame) as ScaleFrame.LiveWeight).weightGrams).isEqualTo(grams)
        }
    }

    @Test
    fun `weight is the low 18 bits of the weight word`() {
        // 150 000 g = 0x249F0 sits above bit 16, so the word becomes 0x6A49F0.
        val frame = liveSettling.copyOf().also { it[3] = 0x6A; it[4] = 0x49; it[5] = 0xF0.toByte() }
        frame[19] = AlphaSmartScalePro3Lib.scaleChecksum(frame).toByte()

        assertThat(AlphaSmartScalePro3Lib.weightGrams(frame, 3)).isEqualTo(150_000)
        assertThat((AlphaSmartScalePro3Lib.parse(frame) as ScaleFrame.LiveWeight).weightGrams).isEqualTo(150_000)
    }

    // --- Final result ----------------------------------------------------------

    @Test
    fun `decodes weight and impedance from the final result`() {
        assertThat(AlphaSmartScalePro3Lib.parse(finalWithImpedance))
            .isEqualTo(ScaleFrame.FinalResult(weightGrams = 88_970, impedanceOhms = 469, locked = true))
    }

    @Test
    fun `final result without impedance reports zero ohm`() {
        assertThat(AlphaSmartScalePro3Lib.parse(finalWithoutImpedance))
            .isEqualTo(ScaleFrame.FinalResult(weightGrams = 89_240, impedanceOhms = 0, locked = true))
    }

    // --- Stored record ---------------------------------------------------------

    @Test
    fun `decodes timestamp and weight from a stored record`() {
        assertThat(AlphaSmartScalePro3Lib.parse(storedRecord))
            .isEqualTo(ScaleFrame.StoredRecord(timestampEpochSeconds = 1_789_939_425L, weightGrams = 6_390, locked = true))
    }

    // --- Frame builders (phone -> scale) ---------------------------------------

    @Test
    fun `builds the profile frame FitDays sent for a 165 cm, 31 year old woman`() {
        // Captured at Unix time 0x6AB05077 with the account's profile: 165 cm, born 1995, female.
        assertThat(AlphaSmartScalePro3Lib.buildProfile(0x6AB05077L, 165, 31, AlphaSmartScalePro3Lib.SEX_FEMALE))
            .isEqualTo(hex("AC 27 6A B0 50 77 08 00 00 A5 00 00 1F 02 00 00 03 00 D0 82"))
    }

    @Test
    fun `profile frame encodes a male user and clamps out-of-range values`() {
        val frame = AlphaSmartScalePro3Lib.buildProfile(0L, 300, -5, AlphaSmartScalePro3Lib.SEX_MALE)

        assertThat(frame[9].toInt() and 0xFF).isEqualTo(255)
        assertThat(frame[12].toInt() and 0xFF).isEqualTo(0)
        assertThat(frame[13].toInt() and 0xFF).isEqualTo(1)
        assertThat(frame[19].toInt() and 0xFF).isEqualTo(AlphaSmartScalePro3Lib.phoneChecksum(frame))
    }

    @Test
    fun `builds the acknowledgements FitDays sent`() {
        assertThat(AlphaSmartScalePro3Lib.buildAck(AlphaSmartScalePro3Lib.WIRE_FINAL_RESULT))
            .isEqualTo(hex("AC 27 04 D6 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF B9"))
        assertThat(AlphaSmartScalePro3Lib.buildAck(AlphaSmartScalePro3Lib.WIRE_STORED_RECORD))
            .isEqualTo(hex("AC 27 04 D8 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF BB"))
    }

    @Test
    fun `builds the session start frame FitDays sent`() {
        assertThat(AlphaSmartScalePro3Lib.buildSessionStart())
            .isEqualTo(hex("AC 27 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF E0"))
    }
}
