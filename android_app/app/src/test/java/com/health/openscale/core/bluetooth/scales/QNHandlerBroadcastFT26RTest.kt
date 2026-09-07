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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Payload regression tests for the FITINDEX FT-26R-W, captured from a physical unit.
 *
 * The FT-26R is a weight-only scale (no BIA electrodes) that advertises the AABB
 * manufacturer payload under company ID 0xFFFF and is not connectable, so the
 * broadcast handler is the only way openScale can read it.
 *
 * These frames are the reason [QNHandlerBroadcast.FLAG_STABLE_BIT_ALT] exists: this
 * model never sets bit 5 (0x20), so the original stable check silently discarded
 * every reading. Captured 2026-09-07; the scale's own display read 155.0 lb
 * (70.31 kg) at the end of session 1.
 *
 * The device address in bytes [2-7] has been replaced with the documentation
 * address 00:00:5E:00:53:01 (RFC 7042). Nothing parses those bytes, so the
 * substitution does not affect what these tests pin down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QNHandlerBroadcastFT26RTest {

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Mirrors the handler's own field extraction so the test pins the wire format. */
    private fun weightKgOf(frame: ByteArray): Float =
        (((frame[17].toInt() and 0xFF) or ((frame[18].toInt() and 0xFF) shl 8))) / 100.0f

    private fun statusOf(frame: ByteArray): Int = frame[15].toInt() and 0xFF

    private fun isStable(frame: ByteArray): Boolean =
        (statusOf(frame) and 0x21) != 0   // FLAG_STABLE_BIT or FLAG_STABLE_BIT_ALT

    // ---- Session 1: settled at 70.30 kg, display read 155.0 lb ----------------

    private val settling = listOf(
        hex("aabb00005e0053016d000000ffffff1400671b510e032100"), // 70.15, moving
        hex("aabb00005e0053016e000000ffffff1400711b510e032200"), // 70.25, moving
        hex("aabb00005e0053016e000000ffffff14007b1b510e032300"), // 70.35, moving
        hex("aabb00005e00530171000000ffffff1400761b510e032800")  // 70.30, moving
    )

    private val settled = listOf(
        hex("aabb00005e00530172000000ffffff1500761b510e032300"),
        hex("aabb00005e00530178000000ffffff1500761b510e032300"),
        hex("aabb00005e0053017b000000ffffff1500761b510e032300")
    )

    /** Idle advertisement: bit 0 is set but the weight field is zero. */
    private val idle = hex("aabb00005e00530100f09f6affffff15a00000510e03500f")

    @Test
    fun `payload carries the AABB magic and the device's own address`() {
        val frame = settled.first()
        assertThat(frame[0]).isEqualTo(0xAA.toByte())
        assertThat(frame[1]).isEqualTo(0xBB.toByte())
        assertThat(frame.copyOfRange(2, 8))
            .isEqualTo(hex("00005e005301"))
    }

    @Test
    fun `weight decodes to the value shown on the scale display`() {
        // 155.0 lb == 70.307 kg; the scale reports in 0.05 kg steps.
        settled.forEach { assertThat(weightKgOf(it)).isWithin(0.01f).of(70.30f) }
    }

    @Test
    fun `FT-26R never sets the bit 5 stable flag`() {
        (settling + settled + listOf(idle)).forEach { frame ->
            assertThat(statusOf(frame) and 0x20).isEqualTo(0)
        }
    }

    @Test
    fun `status latches from 0x14 to 0x15 exactly when the weight settles`() {
        settling.forEach { assertThat(statusOf(it)).isEqualTo(0x14) }
        settled.forEach { assertThat(statusOf(it)).isEqualTo(0x15) }
    }

    @Test
    fun `only the settled frames are treated as stable`() {
        settling.forEach { assertThat(isStable(it)).isFalse() }
        settled.forEach { assertThat(isStable(it)).isTrue() }
    }

    @Test
    fun `idle advertisement sets bit 0 but is rejected by the weight range check`() {
        // This is why testing both stable bits together is safe: the idle frame would
        // otherwise publish a bogus reading.
        assertThat(isStable(idle)).isTrue()
        assertThat(weightKgOf(idle)).isEqualTo(0f)
        assertThat(weightKgOf(idle)).isLessThan(0.5f)   // WEIGHT_MIN_KG
    }

    // ---- Session 2: independent capture, settled at 70.35 kg ------------------

    @Test
    fun `second capture shows the same 0x14 to 0x15 transition`() {
        val moving = hex("aabb00005e005301ef000000ffffff14007b1b510e034900")
        val locked = hex("aabb00005e005301f0000000ffffff15007b1b510e034400")

        assertThat(weightKgOf(moving)).isWithin(0.01f).of(70.35f)
        assertThat(weightKgOf(locked)).isWithin(0.01f).of(70.35f)
        assertThat(isStable(moving)).isFalse()
        assertThat(isStable(locked)).isTrue()
    }

    @Test
    fun `byte 8 is a free running counter and not measurement data`() {
        // Differs between sessions (0x6d..0x7b vs 0xea..0xfa) while the weight is
        // identical, so it must not be parsed as part of the reading.
        val s1 = hex("aabb00005e00530172000000ffffff1500761b510e032300")
        val s2 = hex("aabb00005e005301f0000000ffffff15007b1b510e034400")
        assertThat(s1[8]).isNotEqualTo(s2[8])
    }
}
