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
 * Unit tests for [StandardImpedanceLib].
 */
class StandardImpedanceLibTest {
    internal val EPS = 1e-3 // general float tolerance

    val lib = StandardImpedanceLib(gender = GenderType.MALE, age = 30, weightKg = 80.0, heightM = 1.8, impedance = 527.0)

    @Test
    fun bmi_isComputedCorrectly_forTypicalMale() {
        assertThat(lib.bmi).isWithin(EPS).of(24.69136)
        assertThat(lib.fatFreeMassKg).isWithin(EPS).of(60.622)
        assertThat(lib.totalFatPercentage).isWithin(EPS).of(24.222)
        assertThat(lib.totalBodyWaterPercentage).isWithin(EPS).of(53.819)
        assertThat(lib.basalMetabolicRate).isWithin(EPS).of(1679.436)
        assertThat(lib.skeletalMusclePercentage).isWithin(EPS).of(39.313)
        assertThat(lib.boneMassKg).isWithin(EPS).of(3.455)

        // We're within ±3% of TBW / FFM = 0.732 (https://pmc.ncbi.nlm.nih.gov/articles/PMC11625996/)
        val tbwFFM = 0.732
        assertThat(lib.totalBodyWaterKg / lib.fatFreeMassKg).isWithin(tbwFFM * 0.03).of(tbwFFM)
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
