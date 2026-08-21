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
 * Unit tests for [OneByoneHandler] frame framing decisions.
 *
 * Vectors are taken verbatim from openScale session logs of a 1byone "Health Scale". The scale
 * sends its final measurement twice: sometimes as two separate 11-byte notifications, and sometimes
 * coalesced into a single 20-byte one, because the ATT payload caps at 20 bytes. The coalesced form
 * used to be misread as a history frame and silently discarded.
 */
class OneByoneHandlerTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Separate 11-byte frames — these were accepted even before the fix. */
    private val single1016 = hex("CF 50 0F B0 27 10 13 51 00 00 55")
    private val single1014 = hex("CF 60 0E 9C 27 04 02 F0 00 00 EC")

    /** Coalesced frame + truncated duplicate — each of these was silently dropped. */
    private val coalesced1016 = hex("CF 32 0F B0 27 FD F7 0D 00 00 62 CF 32 0F B0 27 FD F7 0D 00")
    private val coalesced1014a = hex("CF 6A 0E 9C 27 60 64 68 00 00 7C CF 6A 0E 9C 27 60 64 68 00")
    private val coalesced1014b = hex("CF 92 0E 9C 27 1D 13 5F 00 00 B9 CF 92 0E 9C 27 1D 13 5F 00")

    /** A coalesced frame captured after the fix, which parsed and published correctly. */
    private val coalesced1027 = hex("CF 76 0C 1E 28 DF D6 07 01 00 8C CF 76 0C 1E 28 DF D6 07 01")

    private val allCoalesced = listOf(coalesced1016, coalesced1014a, coalesced1014b, coalesced1027)

    private fun weightKg(b: ByteArray): Float =
        ((b[3].toInt() and 0xFF) or ((b[4].toInt() and 0xFF) shl 8)) / 100.0f

    private fun impedanceOhm(b: ByteArray): Float =
        (((b[2].toInt() and 0xFF) shl 8) + (b[1].toInt() and 0xFF)) * 0.1f

    @Test
    fun `all captured frames carry a valid XOR checksum`() {
        for (frame in listOf(single1016, single1014) + allCoalesced) {
            assertThat(OneByoneHandler.isLiveFrame(frame)).isTrue()
        }
    }

    @Test
    fun `coalesced duplicate frames are not mistaken for history`() {
        // Each is >= 18 bytes, so the old `size >= 18` rule read bytes 11..17 as a timestamp,
        // produced an impossible date, and the non-lenient Calendar threw the reading away.
        for (frame in allCoalesced) {
            assertThat(frame.size).isAtLeast(18)
            assertThat(OneByoneHandler.hasHistoryTimestamp(frame)).isFalse()
        }
    }

    @Test
    fun `coalesced frames decode to the same weight as the retry that succeeded`() {
        // The user reweighed after each drop; these are the weights openScale did record.
        assertThat(weightKg(coalesced1016)).isWithin(1e-3f).of(101.60f)
        assertThat(weightKg(coalesced1014a)).isWithin(1e-3f).of(101.40f)
        assertThat(weightKg(coalesced1014b)).isWithin(1e-3f).of(101.40f)

        assertThat(weightKg(coalesced1016)).isEqualTo(weightKg(single1016))
        assertThat(weightKg(coalesced1014a)).isEqualTo(weightKg(single1014))
    }

    @Test
    fun `a coalesced frame still yields its impedance`() {
        // Captured on hardware after the fix: this frame published full body composition.
        assertThat(weightKg(coalesced1027)).isWithin(1e-3f).of(102.70f)
        assertThat(impedanceOhm(coalesced1027)).isWithin(1e-3f).of(319.0f)
    }

    @Test
    fun `single frames are below the history length threshold`() {
        assertThat(OneByoneHandler.hasHistoryTimestamp(single1016)).isFalse()
        assertThat(OneByoneHandler.hasHistoryTimestamp(single1014)).isFalse()
    }

    @Test
    fun `a genuine history frame is still treated as history`() {
        // 18-byte historic entry: 11-byte body then year 2026 (07 EA), 08-12, 09:11:43.
        // Byte 10 is measurement data here, not a checksum over bytes 0..9.
        val historic = hex("CF 60 0E 9C 27 04 02 F0 00 00 11 07 EA 08 0C 09 0B 2B")
        assertThat(historic.size).isAtLeast(18)
        assertThat(OneByoneHandler.isLiveFrame(historic)).isFalse()
        assertThat(OneByoneHandler.hasHistoryTimestamp(historic)).isTrue()
    }

    @Test
    fun `history detection needs a frame marker at byte 11 not just a valid checksum`() {
        // Valid live checksum at byte 10, but byte 11 is a plausible year high byte rather than
        // another 0xCF - that is a history frame, so the timestamp must be read.
        val checksumCollision = hex("CF 60 0E 9C 27 04 02 F0 00 00 EC 07 EA 08 0C 09 0B 2B")
        assertThat(OneByoneHandler.isLiveFrame(checksumCollision)).isTrue()
        assertThat(OneByoneHandler.hasHistoryTimestamp(checksumCollision)).isTrue()
    }

    @Test
    fun `rejects truncated and corrupted frames`() {
        assertThat(OneByoneHandler.isLiveFrame(hex("CF 50 0F B0 27"))).isFalse()
        assertThat(OneByoneHandler.isLiveFrame(ByteArray(0))).isFalse()
        // Same frame with a corrupted checksum byte.
        assertThat(OneByoneHandler.isLiveFrame(hex("CF 50 0F B0 27 10 13 51 00 00 FF"))).isFalse()
    }
}
