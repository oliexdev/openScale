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
     * Muscle mass (kg) derived from body composition per Holtek libBodyfat.so FF().
     * Formula: muscle_kg = weight - fatMass - boneMass, clamped to [10, 120] kg.
     * The caller converts to percentage for the Percent schema.
     */
    fun getMuscle(weight: Float, impedance: Float): Float {
        if (weight <= 0f) return 0f

        // Holtek libBodyfat.so FF(): muscle_kg = weight - fatMass - boneMass
        // Derived from body composition, NOT a direct BIA formula.
        val fatMass = (getBodyFat(weight, impedance) * 0.01f) * weight
        val boneMass = getBoneMass(weight, impedance)
        val muscleKg = weight - fatMass - boneMass

        return muscleKg.coerceIn(10f, 120f)
    }

    fun getWater(weight: Float, impedance: Float): Float {
        val water = (100.0f - getBodyFat(weight, impedance)) * 0.7f
        val coeff = if (water < 50f) 1.02f else 0.98f
        return (coeff * water).coerceIn(35.0f, 75.0f)
    }

    fun getBoneMass(weight: Float, impedance: Float): Float {
        val base = if (sex == 0) 0.245691014f else 0.18016894f
        var boneMass = (base - (getLBMCoefficient(weight, impedance) * 0.05158f)) * -1.0f

        boneMass = if (boneMass > 2.2f) boneMass + 0.1f else boneMass - 0.1f

        // Holtek libBodyfat.so DD(): weight-dependent clamping ranges.
        val bounds = if (sex == 0) {
            when {
                weight < 45.0f -> 0.8f to 2.8f
                weight < 60.0f -> 1.2f to 3.2f
                weight < 75.0f -> 1.5f to 3.5f
                else -> 0.5f to 4.2f
            }
        } else {
            if (weight < 60.0f) 1.5f to 3.5f else 1.9f to 3.9f
        }

        return boneMass.coerceIn(bounds.first, bounds.second)
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
                visceralFat = (((height * 0.027f) - (subcalc * weight)) * -1.0f) + (age * 0.07f) - 10.5f
            }
        } else {
            if (height < weight * 1.6f + 63.0f) {
                val subcalc = ((height * 0.4f) - (height * (height * 0.0826f))) * -1.0f
                visceralFat = ((weight * 305.0f) / (subcalc + 48.0f)) - 2.9f + (age * 0.15f)
            } else {
                val subcalc = 0.765f + height * -0.0015f
                visceralFat = (((height * 0.143f) - (weight * subcalc)) * -1.0f) + (age * 0.15f) - 5.0f
            }
        }
        return visceralFat.coerceIn(1.0f, 50.0f)
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
        bodyFat = bodyFat.coerceIn(5.0f, 75.0f)
        return bodyFat
    }

    /**
     * Basal Metabolic Rate (kcal/day) using the Holtek libBodyfat.so BB() formula.
     * Unlike the Schofield equation used by [BodyMiScaleLib], this includes impedance.
     */
    fun getBMR(weight: Float, impedance: Float): Float {
        val bmr = if (sex == 1) {
            weight * 14.916f + 877.8f - impedance * 0.726f - age * 8.976f
        } else {
            weight * 10.2036f + 864.6f - impedance * 0.39336f - age * 6.204f
        }
        return bmr.coerceIn(500.0f, 10000.0f)
    }
}
