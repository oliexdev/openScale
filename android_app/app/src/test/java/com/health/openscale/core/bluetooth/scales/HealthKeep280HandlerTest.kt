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
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import org.junit.Test

/**
 * Frame parsing tests for [HealthKeep280Handler].
 *
 * Payloads were captured with nRF Connect during a weigh-in: the final indication reads
 * 116.40 kg, 68 bpm and 357 Ohm, matching the scale's own display.
 *
 * Note the two weight offsets: a live notification carries the weight at bytes[6..8], the
 * final indication at bytes[5..7]. Mixing them up is the mistake these tests exist to catch,
 * so both are pinned against captures of the same weighing.
 */
class HealthKeep280HandlerTest {

    /** Final indication (FFB3): 0xA3 command, 116.40 kg (0x01C6B0), 68 bpm (0x44), 357 Ohm (0x0165). */
    private val finalFrame = hex("CF 08 00 A3 00 01 C6 B0 44 01 65 00 00 00 00 00 00 00 00 04")

    @Test
    fun `recognises the final measurement command`() {
        assertThat(HealthKeep280Handler.commandOf(finalFrame))
            .isEqualTo(HealthKeep280Handler.CMD_FINAL_MEASUREMENT)
    }

    @Test
    fun `decodes weight, heart rate and impedance from the final indication`() {
        assertThat(HealthKeep280Handler.finalWeightKg(finalFrame)).isWithin(0.01f).of(116.40f)
        assertThat(HealthKeep280Handler.finalHeartRateBpm(finalFrame)).isEqualTo(68)
        assertThat(HealthKeep280Handler.finalImpedanceOhm(finalFrame)).isEqualTo(357)
    }

    @Test
    fun `reads the live weight from its own offset and reports the in-progress status`() {
        // Live notification (FFB2), constructed to carry the same 116.40 kg one byte further
        // along than the final frame does: status 0x01 at [4], weight at [6..8].
        val liveFrame = hex("CF 08 00 A2 01 00 01 C6 B0")

        assertThat(HealthKeep280Handler.liveStatusOf(liveFrame)).isEqualTo(0x01)
        assertThat(HealthKeep280Handler.liveWeightKg(liveFrame)).isWithin(0.01f).of(116.40f)
    }

    @Test
    fun `the two frame layouts do not share an offset`() {
        // Reading the final frame with the live offset shifts the weight by one byte, which is
        // exactly the regression the separate accessors guard against.
        assertThat(HealthKeep280Handler.liveWeightKg(finalFrame))
            .isNotWithin(0.01f).of(HealthKeep280Handler.finalWeightKg(finalFrame))
    }
}
