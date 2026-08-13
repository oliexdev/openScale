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
 * Vectors are taken verbatim from an openScale session log of a 1byone "Health Scale"
 * (94:E3:6D:5A:3F:DF, 2026-08-08 and 2026-08-12). The scale sends its final measurement twice.
 * Sometimes the two copies arrive as separate 11-byte notifications, and sometimes coalesced into
 * a single 20-byte one; the coalesced form used to be misread as a history frame and discarded.
 */
class OneByoneHandlerTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Single 11-byte frame, 101.60 kg — this one was accepted even before the fix. */
    private val single1016 = hex("CF 50 0F B0 27 10 13 51 00 00 55")

    /** Single 11-byte frame, 101.40 kg. */
    private val single1014 = hex("CF 60 0E 9C 27 04 02 F0 00 00 EC")

    /** Coalesced frame + truncated duplicate, 101.60 kg — silently dropped before the fix. */
    private val coalesced1016 = hex("CF 32 0F B0 27 FD F7 0D 00 00 62 CF 32 0F B0 27 FD F7 0D 00")

    /** Coalesced frame + truncated duplicate, 101.40 kg (two separate weigh-ins). */
    private val coalesced1014a = hex("CF 6A 0E 9C 27 60 64 68 00 00 7C CF 6A 0E 9C 27 60 64 68 00")
    private val coalesced1014b = hex("CF 92 0E 9C 27 1D 13 5F 00 00 B9 CF 92 0E 9C 27 1D 13 5F 00")

    private fun weightKg(b: ByteArray): Float =
        ((b[3].toInt() and 0xFF) or ((b[4].toInt() and 0xFF) shl 8)) / 100.0f

    @Test
    fun `all captured frames carry a valid XOR checksum`() {
        for (frame in listOf(single1016, single1014, coalesced1016, coalesced1014a, coalesced1014b)) {
            assertThat(OneByoneHandler.isLiveFrame(frame)).isTrue()
        }
    }

    @Test
    fun `coalesced duplicate frames are not mistaken for history`() {
        // Each is >= 18 bytes, so the old `size >= 18` rule read bytes 11..17 as a timestamp.
        for (frame in listOf(coalesced1016, coalesced1014a, coalesced1014b)) {
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

    /**
     * Captured 2026-08-12 11:00:05 after the coalescing fix shipped. The scale settled (status
     * 0x00) but reported **zero impedance** — no bioimpedance run. The weight is valid and must
     * survive; only body composition should be skipped.
     */
    private val zeroImpedance = hex("CF 00 00 28 28 00 00 00 01 00 CE")

    @Test
    fun `a settled zero-impedance frame is a valid weight`() {
        assertThat(OneByoneHandler.isLiveFrame(zeroImpedance)).isTrue()
        assertThat(OneByoneHandler.isFinalReading(zeroImpedance)).isTrue()
        assertThat(weightKg(zeroImpedance)).isWithin(1e-3f).of(102.80f)

        // Impedance really is absent, so body composition cannot be derived from this frame.
        val impedanceOhm =
            (((zeroImpedance[2].toInt() and 0xFF) shl 8) + (zeroImpedance[1].toInt() and 0xFF)) * 0.1f
        assertThat(impedanceOhm).isEqualTo(0f)
    }

    @Test
    fun `settled readings are distinguished from in-progress ones`() {
        // 0x00 and 0x36 are the vendor app's "locked" values.
        for (frame in listOf(single1016, single1014, coalesced1016, coalesced1014a, coalesced1014b)) {
            assertThat(OneByoneHandler.isFinalReading(frame)).isTrue()
        }
        assertThat(OneByoneHandler.isFinalReading(hex("CF 50 0F B0 27 10 13 51 00 36 63"))).isTrue()

        // Anything else is still settling and must not be recorded.
        assertThat(OneByoneHandler.isFinalReading(hex("CF 50 0F B0 27 10 13 51 00 01 54"))).isFalse()
        assertThat(OneByoneHandler.isFinalReading(hex("CF 50 0F B0 27 10 13 51 00 02 57"))).isFalse()
        assertThat(OneByoneHandler.isFinalReading(ByteArray(4))).isFalse()
    }

    /**
     * The 2026-08-12 18:25/18:26 back-to-back pair that validated both fixes on real hardware:
     * the same 103.00 kg weigh-in taken in socks (no bioimpedance) and barefoot (coalesced frame).
     */
    private val socks = hex("CF 00 00 3C 28 00 00 00 01 00 DA")
    private val barefootCoalesced = hex("CF B6 0D 3C 28 B4 B5 99 01 00 F9 CF B6 0D 3C 28 B4 B5 99 01")

    private fun impedanceOhm(b: ByteArray): Float =
        (((b[2].toInt() and 0xFF) shl 8) + (b[1].toInt() and 0xFF)) * 0.1f

    @Test
    fun `socks and barefoot frames agree on weight and differ only in impedance`() {
        for (frame in listOf(socks, barefootCoalesced)) {
            assertThat(OneByoneHandler.isLiveFrame(frame)).isTrue()
            assertThat(OneByoneHandler.isFinalReading(frame)).isTrue()
            assertThat(weightKg(frame)).isWithin(1e-3f).of(103.00f)
        }

        // Socks block the bioimpedance measurement; barefoot produces a usable reading.
        assertThat(impedanceOhm(socks)).isEqualTo(0f)
        assertThat(impedanceOhm(barefootCoalesced)).isWithin(1e-3f).of(351.0f)
    }

    @Test
    fun `the barefoot frame is the coalesced shape that used to be dropped`() {
        assertThat(barefootCoalesced.size).isEqualTo(20)
        assertThat(OneByoneHandler.hasHistoryTimestamp(barefootCoalesced)).isFalse()
    }

    @Test
    fun `rejects truncated and corrupted frames`() {
        assertThat(OneByoneHandler.isLiveFrame(hex("CF 50 0F B0 27"))).isFalse()
        assertThat(OneByoneHandler.isLiveFrame(ByteArray(0))).isFalse()
        // Same frame with a corrupted checksum byte.
        assertThat(OneByoneHandler.isLiveFrame(hex("CF 50 0F B0 27 10 13 51 00 00 FF"))).isFalse()
    }
}
