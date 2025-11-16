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

// This class is similar to OneByoneLib, but the way measures are computer are slightly different
class OneByoneNewLib(
    private val sex: Int,
    private val age: Int,
    private val height: Float, // low activity = 0; medium activity = 1; high activity = 2
    private val peopleType: Int
) {
    fun getBMI(weight: Float): Float {
        val bmi = weight / (((height * height) / 100.0f) / 100.0f)
        return getBounded(bmi, 10f, 90f)
    }

    fun getLBM(weight: Float, impedance: Int): Float {
        var lbmCoeff = height / 100 * height / 100 * 9.058f
        lbmCoeff += 12.226.toFloat()
        lbmCoeff += (weight * 0.32).toFloat()
        lbmCoeff -= (impedance * 0.0068).toFloat()
        lbmCoeff -= (age * 0.0542).toFloat()
        return lbmCoeff
    }


    fun getBMMRCoeff(weight: Float): Float {
        var bmmrCoeff = 20
        if (sex == 1) {
            bmmrCoeff = 21
            if (age < 0xd) {
                bmmrCoeff = 36
            } else if (age < 0x10) {
                bmmrCoeff = 30
            } else if (age < 0x12) {
                bmmrCoeff = 26
            } else if (age < 0x1e) {
                bmmrCoeff = 23
            } else if (age >= 0x32) {
                bmmrCoeff = 20
            }
        } else {
            if (age < 0xd) {
                bmmrCoeff = 34
            } else if (age < 0x10) {
                bmmrCoeff = 29
            } else if (age < 0x12) {
                bmmrCoeff = 24
            } else if (age < 0x1e) {
                bmmrCoeff = 22
            } else if (age >= 0x32) {
                bmmrCoeff = 19
            }
        }
        return bmmrCoeff.toFloat()
    }

    fun getBMMR(weight: Float): Float {
        var bmmr: Float
        if (sex == 1) {
            bmmr = (weight * 14.916f + 877.8f) - height * 0.726f
            bmmr -= (age * 8.976).toFloat()
        } else {
            bmmr = (weight * 10.2036f + 864.6f) - height * 0.39336f
            bmmr -= (age * 6.204).toFloat()
        }

        return getBounded(bmmr, 500f, 1000f)
    }

    fun getBodyFatPercentage(weight: Float, impedance: Int): Float {
        var bodyFat = getLBM(weight, impedance)

        val bodyFatConst: Float
        if (sex == 0) {
            if (age < 0x32) {
                bodyFatConst = 9.25f
            } else {
                bodyFatConst = 7.25f
            }
        } else {
            bodyFatConst = 0.8f
        }

        bodyFat -= bodyFatConst

        if (sex == 0) {
            if (weight < 50) {
                bodyFat *= 1.02.toFloat()
            } else if (weight > 60) {
                bodyFat *= 0.96.toFloat()
            }

            if (height > 160) {
                bodyFat *= 1.03.toFloat()
            }
        } else {
            if (weight < 61) {
                bodyFat *= 0.98.toFloat()
            }
        }

        return 100 * (1 - bodyFat / weight)
    }

    fun getBoneMass(weight: Float, impedance: Int): Float {
        val lbmCoeff = getLBM(weight, impedance)

        var boneMassConst: Float
        if (sex == 1) {
            boneMassConst = 0.18016894f
        } else {
            boneMassConst = 0.245691014f
        }

        boneMassConst = lbmCoeff * 0.05158f - boneMassConst
        val boneMass: Float
        if (boneMassConst <= 2.2) {
            boneMass = boneMassConst - 0.1f
        } else {
            boneMass = boneMassConst + 0.1f
        }

        return getBounded(boneMass, 0.5f, 8f)
    }

    fun getMuscleMass(weight: Float, impedance: Int): Float {
        var muscleMass = weight - getBodyFatPercentage(weight, impedance) * 0.01f * weight
        muscleMass -= getBoneMass(weight, impedance)
        return getBounded(muscleMass, 10f, 120f)
    }

    fun getSkeletonMusclePercentage(weight: Float, impedance: Int): Float {
        var skeletonMuscleMass = getWaterPercentage(weight, impedance)
        skeletonMuscleMass *= weight
        skeletonMuscleMass *= 0.8422f * 0.01f
        skeletonMuscleMass -= 2.9903.toFloat()
        skeletonMuscleMass /= weight
        return skeletonMuscleMass * 100
    }

    fun getVisceralFat(weight: Float): Float {
        val visceralFat: Float
        if (sex == 1) {
            if (height < weight * 1.6 + 63.0) {
                visceralFat =
                    age * 0.15f + ((weight * 305.0f) / ((height * 0.0826f * height - height * 0.4f) + 48.0f) - 2.9f)
            } else {
                visceralFat =
                    age * 0.15f + (weight * (height * -0.0015f + 0.765f) - height * 0.143f) - 5.0f
            }
        } else {
            if (weight <= height * 0.5 - 13.0) {
                visceralFat =
                    age * 0.07f + (weight * (height * -0.0024f + 0.691f) - height * 0.027f) - 10.5f
            } else {
                visceralFat =
                    age * 0.07f + ((weight * 500.0f) / ((height * 1.45f + height * 0.1158f * height) - 120.0f) - 6.0f)
            }
        }

        return getBounded(visceralFat, 1f, 50f)
    }

    fun getWaterPercentage(weight: Float, impedance: Int): Float {
        var waterPercentage = (100 - getBodyFatPercentage(weight, impedance)) * 0.7f
        if (waterPercentage > 50) {
            waterPercentage *= 0.98.toFloat()
        } else {
            waterPercentage *= 1.02.toFloat()
        }

        return getBounded(waterPercentage, 35f, 75f)
    }

    fun getProteinPercentage(weight: Float, impedance: Int): Float {
        return (((100.0f - getBodyFatPercentage(weight, impedance))
                - getWaterPercentage(weight, impedance) * 1.08f
                )
                - (getBoneMass(weight, impedance) / weight) * 100.0f)
    }


    private fun getBounded(value: Float, lowerBound: Float, upperBound: Float): Float {
        if (value < lowerBound) {
            return lowerBound
        } else if (value > upperBound) {
            return upperBound
        }
        return value
    }
}
