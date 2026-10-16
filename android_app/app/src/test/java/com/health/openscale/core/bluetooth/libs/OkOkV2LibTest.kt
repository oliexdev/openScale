/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
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

class OkOkV2LibTest {

    private val EPS = 0.5f

    @Test
    fun calculations_withinRealisticRange_doNotCollapseToClampMinimums() {
        // Test male: 50 years, 168 cm, 95.65 kg, impedance 470 ohms
        val lib = OkOkV2Lib(age = 50, sex = 1, height = 168f)
        val weight = 95.65f
        val impedance = 470f

        val bodyFat = lib.getBodyFat(weight, impedance)
        val water = lib.getWater(weight, impedance)
        val visceralFat = lib.getVisceralFat(weight, impedance)
        val boneMass = lib.getBoneMass(weight, impedance)

        // Ensure metrics do not collapse to hard-coded clamp floors (5% fat, 20% water, 1 visceral, 1kg bone)
        assertThat(bodyFat).isWithin(1.0f).of(32.6f)
        assertThat(water).isWithin(1.0f).of(50.6f)
        assertThat(visceralFat).isWithin(2.0f).of(21.0f)
        assertThat(boneMass).isWithin(1.0f).of(2.4f)
    }

    @Test
    fun boundaryLimits_clampProperly() {
        val lib = OkOkV2Lib(age = 30, sex = 1, height = 180f)

        // Extreme low fat should clamp at 5f
        val lowFat = lib.getBodyFat(weight = 40f, impedance = 100f)
        assertThat(lowFat).isAtLeast(5f)
        assertThat(lowFat).isAtMost(45f)

        // Extreme high water should clamp at 85f
        val water = lib.getWater(weight = 60f, impedance = 200f)
        assertThat(water).isAtLeast(20f)
        assertThat(water).isAtMost(85f)
    }
}
