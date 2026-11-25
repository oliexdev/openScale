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
import org.junit.Test
import com.health.openscale.core.bluetooth.ScaleCatalog.hex

/**
 * Tests for the Beurer BF105 private half-body composition decoder.
 *
 * The layout asserted here is the one a BF105 sends on the Beurer-private FFFF/0008
 * characteristic: one half-body frame per stored weigh-in, right after the body-composition
 * packet (2A9C) of that same weigh-in. The percentages are synthetic; standard 2A9D and
 * 2A9C packets are handled by the existing StandardWeightProfileHandler.
 */
class BeurerBf105LibTest {

    @Test
    fun `decodes private half-body measurement`() {
        val decoded = BeurerBf105Lib.parseHalfBodyMeasurement(
            hex("1E 6D 00 A2 00 91 01 B9 01")
        )

        assertThat(decoded).isNotNull()
        with(decoded!!) {
            assertThat(upperBodyFat).isWithin(1e-4f).of(10.9f)
            assertThat(lowerBodyFat).isWithin(1e-4f).of(16.2f)
            assertThat(upperBodyMuscle).isWithin(1e-4f).of(40.1f)
            assertThat(lowerBodyMuscle).isWithin(1e-4f).of(44.1f)
        }
    }

    @Test
    fun `rejects short or unexpected private packets`() {
        assertThat(BeurerBf105Lib.parseHalfBodyMeasurement(ByteArray(8))).isNull()

        assertThat(BeurerBf105Lib.parseHalfBodyMeasurement(
            hex("00 6D 00 A2 00 91 01 B9 01")
        )).isNull()
    }

    @Test
    fun `decodes a frame without values instead of rejecting it`() {
        val decoded = BeurerBf105Lib.parseHalfBodyMeasurement(
            hex("1E 00 00 00 00 00 00 00 00")
        )

        assertThat(decoded).isNotNull()
        with(decoded!!) {
            assertThat(upperBodyFat).isWithin(1e-4f).of(0f)
            assertThat(lowerBodyFat).isWithin(1e-4f).of(0f)
            assertThat(upperBodyMuscle).isWithin(1e-4f).of(0f)
            assertThat(lowerBodyMuscle).isWithin(1e-4f).of(0f)
        }
    }
}
