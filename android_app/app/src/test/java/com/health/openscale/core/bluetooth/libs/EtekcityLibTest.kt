/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
 * Unit tests for [EtekcityLib].
 */
class EtekcityLibTest {
    internal val EPS = 1e-3 // general float tolerance

    val lib = EtekcityLib(gender = GenderType.MALE, age = 30, weightKg = 80.0, heightM = 1.8, impedance = 527.0)

    @Test
    fun bmi_isComputedCorrectly_forTypicalMale() {
        assertThat(lib.bmi).isWithin(EPS).of(24.69136)
        assertThat(lib.bodyFatPercentage).isWithin(EPS).of(17.7)
        assertThat(lib.fatFreeWeight).isWithin(EPS).of(65.84)
        assertThat(lib.visceralFat).isWithin(EPS).of(7.64163)
        assertThat(lib.water).isWithin(EPS).of(59.4206)
        assertThat(lib.basalMetabolicRate).isWithin(EPS).of(1792.144)
        assertThat(lib.skeletalMusclePercentage).isWithin(EPS).of(53.1658)
        assertThat(lib.boneMass).isWithin(EPS).of(3.292)
        assertThat(lib.subcutaneousFat).isWithin(EPS).of(15.3993)
        assertThat(lib.muscleMass).isWithin(EPS).of(62.548)
        assertThat(lib.proteinPercentage).isWithin(EPS).of(18.7644)
        assertThat(lib.weightScore).isEqualTo(76)
        assertThat(lib.fatScore).isEqualTo(97)
        assertThat(lib.bmiScore).isEqualTo(89)
        assertThat(lib.healthScore).isEqualTo(87)
        assertThat(lib.metabolicAge).isEqualTo(29)
    }

    @Test
    fun bmi_monotonicity_weightUp_heightSame_increases() {
        assertThat(lib.run { copy(weightKg = weightKg + 5.0) }.bmi).isGreaterThan(lib.bmi)
    }

    @Test
    fun bmi_monotonicity_heightUp_weightSame_decreases() {
        assertThat(lib.run { copy(heightM = heightM + 0.05) }.bmi).isLessThan(lib.bmi)
    }
}
