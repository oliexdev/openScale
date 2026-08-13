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

class SoehnleLib(// male = 1; female = 0
    private val isMale: Boolean,
    private val age: Int,
    private val height: Float,
    private val activityLevel: Int
) {
    fun getFat(weight: Float, imp50: Float): Float { // in %
        var activityCorrFac = 0.0f

        when (activityLevel) {
            4 -> {
                if (isMale) {
                    activityCorrFac = 2.5f
                } else {
                    activityCorrFac = 2.3f
                }
            }

            5 -> {
                if (isMale) {
                    activityCorrFac = 4.3f
                } else {
                    activityCorrFac = 4.1f
                }
            }
        }

        val sexCorrFac: Float
        val activitySexDiv: Float

        if (isMale) {
            sexCorrFac = 0.250f
            activitySexDiv = 65.5f
        } else {
            sexCorrFac = 0.214f
            activitySexDiv = 55.1f
        }

        return 1.847f * weight * 10000.0f / (height * height) + sexCorrFac * age + 0.062f * imp50 - (activitySexDiv - activityCorrFac)
    }

    fun computeBodyMassIndex(weight: Float): Float {
        return 10000.0f * weight / (height * height)
    }

    fun getWater(weight: Float, imp50: Float): Float { // in %
        var activityCorrFac = 0.0f

        when (activityLevel) {
            1, 2, 3 -> {
                if (isMale) {
                    activityCorrFac = 2.83f
                } else {
                    activityCorrFac = 0.0f
                }
            }

            4 -> {
                if (isMale) {
                    activityCorrFac = 3.93f
                } else {
                    activityCorrFac = 0.4f
                }
            }

            5 -> {
                if (isMale) {
                    activityCorrFac = 5.33f
                } else {
                    activityCorrFac = 1.4f
                }
            }
        }
        return (0.3674f * height * height / imp50 + 0.17530f * weight - 0.11f * age + (6.53f + activityCorrFac)) / weight * 100.0f
    }

    fun getMuscle(weight: Float, imp50: Float, imp5: Float): Float { // in %
        var activityCorrFac = 0.0f

        when (activityLevel) {
            1, 2, 3 -> {
                if (isMale) {
                    activityCorrFac = 3.6224f
                } else {
                    activityCorrFac = 0.0f
                }
            }

            4 -> {
                if (isMale) {
                    activityCorrFac = 4.3904f
                } else {
                    activityCorrFac = 0.0f
                }
            }

            5 -> {
                if (isMale) {
                    activityCorrFac = 5.4144f
                } else {
                    activityCorrFac = 1.664f
                }
            }
        }
        return ((0.47027f / imp50 - 0.24196f / imp5) * height * height + 0.13796f * weight - 0.1152f * age + (5.12f + activityCorrFac)) / weight * 100.0f
    }
}
