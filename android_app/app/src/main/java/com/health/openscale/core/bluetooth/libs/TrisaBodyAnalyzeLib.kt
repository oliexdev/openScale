/*
 * openScale
 * Copyright (C) 2018  Maks Verver <maks@verver.ch>
 *               2025  olie.xdev <olie.xdeveloper@googlemail.com>
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

/**
 * Class with static helper methods. This is a separate class for testing purposes.
 */
class TrisaBodyAnalyzeLib(sex: Int, private val ageYears: Int, private val heightCm: Float) {
    private val isMale: Boolean

    init {
        isMale = if (sex == 1) true else false // male = 1; female = 0
    }

    fun getBMI(weightKg: Float): Float {
        return weightKg * 1e4f / (heightCm * heightCm)
    }

    fun getWater(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)

        val water = if (isMale)
            87.51f + (-1.162f * bmi - 0.00813f * impedance + 0.07594f * ageYears)
        else
            77.721f + (-1.148f * bmi - 0.00573f * impedance + 0.06448f * ageYears)

        return water
    }

    fun getFat(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)

        val fat = if (isMale)
            bmi * (1.479f + 4.4e-4f * impedance) + 0.1f * ageYears - 21.764f
        else
            bmi * (1.506f + 3.908e-4f * impedance) + 0.1f * ageYears - 12.834f

        return fat
    }

    fun getMuscle(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)

        val muscle = if (isMale)
            74.627f + (-0.811f * bmi - 0.00565f * impedance - 0.367f * ageYears)
        else
            57.0f + (-0.694f * bmi - 0.00344f * impedance - 0.255f * ageYears)

        return muscle
    }

    fun getBone(weightKg: Float, impedance: Float): Float {
        val bmi = getBMI(weightKg)

        val bone = if (isMale)
            7.829f + (-0.0855f * bmi - 5.92e-4f * impedance - 0.0389f * ageYears)
        else
            7.98f + (-0.0973f * bmi - 4.84e-4f * impedance - 0.036f * ageYears)

        return bone
    }
}
