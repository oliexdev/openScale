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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.service

import com.health.openscale.core.data.Trend
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Provides utility to calculate a [Trend] based on two values.
 */
@Singleton
class TrendCalculator @Inject constructor() {

    /**
     * Compares the given values and returns a [Trend].
     *
     * @param current The current value.
     * @param previous The previous value to compare with.
     * @param epsilon Optional threshold below which the difference counts as stable.
     * @return [Trend.UP], [Trend.DOWN], or [Trend.NONE] if difference ≤ epsilon.
     */
    fun calculate(current: Float, previous: Float, epsilon: Float = 0.0f): Trend {
        val delta = current - previous
        return when {
            abs(delta) <= epsilon -> Trend.NONE
            delta > 0f -> Trend.UP
            else -> Trend.DOWN
        }
    }
}
