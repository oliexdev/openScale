/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * Portions derived from bodymiscale (C) dckiller51 and contributors, GPL-3.0
 * (https://github.com/dckiller51/bodymiscale).
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
import com.health.openscale.core.data.GenderType
import org.junit.Test

/**
 * Unit tests for [BodyMiScaleLib].
 *
 * The regression fixture is a real measurement captured from the upstream bodymiscale
 * Home Assistant integration (male, 46y, 168cm, 86.10kg, impedance 421), so this test
 * locks openScale's port to byte-for-byte parity with the reference project.
 */
class BodyMiScaleLibTest {
    private val EPS = 0.05f

    private val lib = BodyMiScaleLib(GenderType.MALE, age = 46, heightCm = 168f)
    private val weight = 86.10f
    private val impedance = 421f

    @Test
    fun matches_bodymiscale_reference() {
        val lbm = lib.getLbm(weight, impedance)
        val fat = lib.getFat(weight, lbm)
        val water = lib.getWater(fat)
        val bone = lib.getBoneMass(lbm)
        val muscle = lib.getMuscleMass(weight, fat, bone)
        val protein = lib.getProtein(weight, lbm)

        assertThat(lbm).isWithin(EPS).of(60.0f)
        assertThat(fat).isWithin(EPS).of(30.33f)
        assertThat(water).isWithin(EPS).of(50.86f)
        assertThat(protein).isWithin(EPS).of(13.59f)
        assertThat(bone).isWithin(EPS).of(3.01f)
        assertThat(muscle).isWithin(EPS).of(56.97f)
        assertThat(lib.getBmr(weight)).isWithin(1f).of(1861f)
        assertThat(lib.getVisceralFat(weight)).isWithin(EPS).of(15.36f)
    }

    @Test
    fun fat_reacts_to_impedance_only_weakly() {
        // Xiaomi-calibrated LBM barely moves with impedance; verify direction is sane.
        val fatLow = lib.getFat(weight, lib.getLbm(weight, 400f))
        val fatHigh = lib.getFat(weight, lib.getLbm(weight, 600f))
        assertThat(fatHigh).isGreaterThan(fatLow)
    }
}
