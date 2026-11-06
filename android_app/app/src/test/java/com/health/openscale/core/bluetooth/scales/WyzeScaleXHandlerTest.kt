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
import com.health.openscale.core.data.GenderType
import org.junit.Test

/**
 * Muscle % for [WyzeScaleXHandler].
 *
 * The inputs are from a hardware weigh-in: 88.40 kg at 462 Ohm for a 195.6 cm, 39-year-old man.
 * The scale reported 67.2 kg of "muscle" for it, exactly LBM 71.6 kg minus bone 4.4 kg, which
 * would have shown as 76 % muscle.
 */
class WyzeScaleXHandlerTest {

    @Test
    fun `muscle is skeletal muscle from impedance, not the scale's soft lean mass`() {
        val pct = WyzeScaleXHandler.skeletalMusclePercent(GenderType.MALE, 39, 195.58f, 88.40f, 462f)

        // Janssen: 0.401 * 195.58² / 462 + 3.825 - 0.071 * 39 + 5.102 = 39.36 kg of 88.40 kg.
        assertThat(pct).isWithin(0.05f).of(44.52f)
    }

    @Test
    fun `sex changes the result`() {
        val male = WyzeScaleXHandler.skeletalMusclePercent(GenderType.MALE, 39, 195.58f, 88.40f, 462f)!!
        val female = WyzeScaleXHandler.skeletalMusclePercent(GenderType.FEMALE, 39, 195.58f, 88.40f, 462f)!!

        assertThat(male - female).isWithin(0.05f).of(3.825f / 88.40f * 100f)
    }

    @Test
    fun `no muscle without impedance, height or weight`() {
        assertThat(WyzeScaleXHandler.skeletalMusclePercent(GenderType.MALE, 39, 195.58f, 88.40f, 0f)).isNull()
        assertThat(WyzeScaleXHandler.skeletalMusclePercent(GenderType.MALE, 39, -1f, 88.40f, 462f)).isNull()
        assertThat(WyzeScaleXHandler.skeletalMusclePercent(GenderType.MALE, 39, 195.58f, 0f, 462f)).isNull()
    }
}
