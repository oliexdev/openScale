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
/**
 * based on https://github.com/prototux/MIBCS-reverse-engineering by prototux
 */
package com.health.openscale.core.bluetooth.libs

class MiScaleLib(
    // male = 1; female = 0
    private val sex: Int,
    private val age: Int,
    private val height: Float // cm
) {

    private fun getLBMCoefficient(weight: Float, impedance: Float): Float {
        var lbm = (height * 9.058f / 100.0f) * (height / 100.0f)
        lbm += weight * 0.32f + 12.226f
        lbm -= impedance * 0.0068f
        lbm -= age * 0.0542f
        return lbm
    }

    fun getBMI(weight: Float): Float {
        // weight [kg], height [cm]
        // BMI = kg / (m^2)
        return weight / (((height * height) / 100.0f) / 100.0f)
    }

    fun getLBM(weight: Float, impedance: Float): Float {
        var leanBodyMass =
            weight - ((getBodyFat(weight, impedance) * 0.01f) * weight) - getBoneMass(weight, impedance)

        if (sex == 0 && leanBodyMass >= 84.0f) {
            leanBodyMass = 120.0f
        } else if (sex == 1 && leanBodyMass >= 93.5f) {
            leanBodyMass = 120.0f
        }

        return leanBodyMass
    }

    /**
     * Skeletal Muscle Mass (%) derived from Janssen et al. BIA equation (kg) -> percent of body weight.
     * If impedance is non-positive, falls back to LBM * ratio.
     */
    fun getMuscle(weight: Float, impedance: Float): Float {
        if (weight <= 0f) return 0f

        val smmKg: Float = if (impedance > 0f) {
            // Janssen et al.: SMM(kg) = 0.401*(H^2/R) + 3.825*sex - 0.071*age + 5.102
            val h2OverR = (height * height) / impedance
            0.401f * h2OverR + 3.825f * sex - 0.071f * age + 5.102f
        } else {
            // Fallback: approximate as fraction of LBM
            val lbm = getLBM(weight, impedance)
            val ratio = if (sex == 1) 0.52f else 0.46f
            lbm * ratio
        }

        val percent = (smmKg / weight) * 100f
        return percent.coerceIn(10f, 60f)
    }

    fun getWater(weight: Float, impedance: Float): Float {
        val water = (100.0f - getBodyFat(weight, impedance)) * 0.7f
        val coeff = if (water < 50f) 1.02f else 0.98f
        return coeff * water
    }

    fun getBoneMass(weight: Float, impedance: Float): Float {
        val base = if (sex == 0) 0.245691014f else 0.18016894f
        var boneMass = (base - (getLBMCoefficient(weight, impedance) * 0.05158f)) * -1.0f

        boneMass = if (boneMass > 2.2f) boneMass + 0.1f else boneMass - 0.1f

        if (sex == 0 && boneMass > 5.1f) {
            boneMass = 8.0f
        } else if (sex == 1 && boneMass > 5.2f) {
            boneMass = 8.0f
        }

        return boneMass
    }

    fun getVisceralFat(weight: Float): Float {
        var visceralFat = 0.0f
        if (sex == 0) {
            if (weight > (13.0f - (height * 0.5f)) * -1.0f) {
                val subsubcalc = ((height * 1.45f) + (height * 0.1158f) * height) - 120.0f
                val subcalc = weight * 500.0f / subsubcalc
                visceralFat = (subcalc - 6.0f) + (age * 0.07f)
            } else {
                val subcalc = 0.691f + (height * -0.0024f) + (height * -0.0024f)
                visceralFat = (((height * 0.027f) - (subcalc * weight)) * -1.0f) + (age * 0.07f) - age
            }
        } else {
            if (height < weight * 1.6f) {
                val subcalc = ((height * 0.4f) - (height * (height * 0.0826f))) * -1.0f
                visceralFat = ((weight * 305.0f) / (subcalc + 48.0f)) - 2.9f + (age * 0.15f)
            } else {
                val subcalc = 0.765f + height * -0.0015f
                visceralFat = (((height * 0.143f) - (weight * subcalc)) * -1.0f) + (age * 0.15f) - 5.0f
            }
        }
        return visceralFat
    }

    fun getBodyFat(weight: Float, impedance: Float): Float {
        var lbmSub = 0.8f
        if (sex == 0 && age <= 49) {
            lbmSub = 9.25f
        } else if (sex == 0 && age > 49) {
            lbmSub = 7.25f
        }

        val lbmCoeff = getLBMCoefficient(weight, impedance)
        var coeff = 1.0f

        if (sex == 1 && weight < 61.0f) {
            coeff = 0.98f
        } else if (sex == 0 && weight > 60.0f) {
            coeff = 0.96f
            if (height > 160.0f) {
                coeff *= 1.03f
            }
        } else if (sex == 0 && weight < 50.0f) {
            coeff = 1.02f
            if (height > 160.0f) {
                coeff *= 1.03f
            }
        }

        var bodyFat = (1.0f - (((lbmCoeff - lbmSub) * coeff) / weight)) * 100.0f
        if (bodyFat > 63.0f) {
            bodyFat = 75.0f
        }
        return bodyFat
    }
}
