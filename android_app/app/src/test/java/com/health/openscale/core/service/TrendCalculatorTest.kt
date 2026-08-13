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
package com.health.openscale.core.service

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.Trend
import org.junit.Test

class TrendCalculatorTest {

    private val calc = TrendCalculator()

    @Test
    fun equalValues_areNone() {
        assertThat(calc.calculate(100f, 100f)).isEqualTo(Trend.NONE)
    }

    @Test
    fun increase_isUp() {
        assertThat(calc.calculate(101f, 100f)).isEqualTo(Trend.UP)
    }

    @Test
    fun decrease_isDown() {
        assertThat(calc.calculate(99f, 100f)).isEqualTo(Trend.DOWN)
    }

    @Test
    fun deltaWithinEpsilon_isNone() {
        assertThat(calc.calculate(100.05f, 100f, epsilon = 0.1f)).isEqualTo(Trend.NONE)
    }

    @Test
    fun deltaAboveEpsilon_isUp() {
        assertThat(calc.calculate(100.2f, 100f, epsilon = 0.1f)).isEqualTo(Trend.UP)
    }

    @Test
    fun deltaBelowNegativeEpsilon_isDown() {
        assertThat(calc.calculate(99.8f, 100f, epsilon = 0.1f)).isEqualTo(Trend.DOWN)
    }
}
