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
 * Unit tests for the Hume Health Dara 2.0 frame decode ([HumeDara2Handler.weightKgFromFrame] /
 * [HumeDara2Handler.impedanceOhmsFromFrame] / [HumeDara2Handler.checksum] /
 * [HumeDara2Handler.isLockedFrame]), reverse-engineered from five real weigh-ins on the same
 * person/device/profile — see [HumeDara2Handler]'s class doc for the full ground truth table
 * and how each was cross-checked against Hume's own on-screen reading.
 */
class HumeDara2HandlerTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // --- Real captures --------------------------------------------------------------------

    private val lockedFrame1 = bytes(0xCF, 0x0A, 0x14, 0xDE, 0x21, 0x5A, 0x55, 0x6F, 0x01, 0x00, 0x4F)
    private val lockedFrame2 = bytes(0xCF, 0xC4, 0x13, 0xE8, 0x21, 0xE5, 0xE7, 0x96, 0x00, 0x00, 0x45)
    private val lockedFrame3 = bytes(0xCF, 0xC4, 0x13, 0xED, 0x21, 0xAC, 0xA6, 0xAE, 0x00, 0x00, 0x70)
    private val lockedFrame4 = bytes(0xCF, 0xC4, 0x13, 0xF7, 0x21, 0x5F, 0x55, 0x75, 0x00, 0x00, 0xB1)
    private val lockedFrame5 = bytes(0xCF, 0xB0, 0x13, 0xF2, 0x21, 0x70, 0x75, 0xB7, 0x00, 0x00, 0x0D)

    // A live (not yet locked) frame from mid-settle on the same weigh-in as lockedFrame5.
    private val liveFrame = bytes(0xCF, 0x00, 0x00, 0xDE, 0x21, 0x00, 0x00, 0x00, 0x00, 0x01, 0x31)

    @Test
    fun `weight decodes to the recorded kg for every real capture`() {
        assertThat(HumeDara2Handler.weightKgFromFrame(lockedFrame1)).isWithin(1e-3f).of(86.70f)
        assertThat(HumeDara2Handler.weightKgFromFrame(lockedFrame2)).isWithin(1e-3f).of(86.80f)
        assertThat(HumeDara2Handler.weightKgFromFrame(lockedFrame3)).isWithin(1e-3f).of(86.85f)
        assertThat(HumeDara2Handler.weightKgFromFrame(lockedFrame4)).isWithin(1e-3f).of(86.95f)
        assertThat(HumeDara2Handler.weightKgFromFrame(lockedFrame5)).isWithin(1e-3f).of(86.90f)
    }

    @Test
    fun `impedance decodes to plausible ohms and stays stable within a session`() {
        // First-ever capture: bad/rushed foot contact, implausibly low — this is exactly what
        // the plausible-range guard in HumeDara2Handler.publishFrame exists to reject.
        assertThat(HumeDara2Handler.impedanceOhmsFromFrame(lockedFrame1)).isWithin(0.01).of(25.80)

        // Three consecutive readings in one session: same foot contact, same impedance.
        assertThat(HumeDara2Handler.impedanceOhmsFromFrame(lockedFrame2)).isWithin(0.01).of(501.95)
        assertThat(HumeDara2Handler.impedanceOhmsFromFrame(lockedFrame3)).isWithin(0.01).of(501.95)
        assertThat(HumeDara2Handler.impedanceOhmsFromFrame(lockedFrame4)).isWithin(0.01).of(501.95)

        // A fresh BLE session later the same day: different (still plausible) contact.
        assertThat(HumeDara2Handler.impedanceOhmsFromFrame(lockedFrame5)).isWithin(0.01).of(450.75)
    }

    @Test
    fun `checksum matches every real capture`() {
        for (frame in listOf(lockedFrame1, lockedFrame2, lockedFrame3, lockedFrame4, lockedFrame5)) {
            assertThat(HumeDara2Handler.checksum(frame)).isEqualTo(frame[10].toInt() and 0xFF)
        }
    }

    @Test
    fun `a corrupted frame fails its checksum`() {
        val corrupted = lockedFrame3.copyOf().also { it[3] = 0x00 } // tamper with a weight byte
        assertThat(HumeDara2Handler.checksum(corrupted)).isNotEqualTo(corrupted[10].toInt() and 0xFF)
    }

    @Test
    fun `locked frames are distinguished from the live weight stream`() {
        for (frame in listOf(lockedFrame1, lockedFrame2, lockedFrame3, lockedFrame4, lockedFrame5)) {
            assertThat(HumeDara2Handler.isLockedFrame(frame)).isTrue()
        }
        assertThat(HumeDara2Handler.isLockedFrame(liveFrame)).isFalse()
    }
}
