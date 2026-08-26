/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.scales

import kotlin.math.max

/**
 * Independent Huawei Scale 3 four-electrode body-composition model.
 *
 * The coefficients below were independently derived from observed input/output
 * behavior of the Huawei Scale 3 adult four-electrode calculation. No Huawei
 * native library or proprietary runtime component is required.
 *
 * This model targets the normal adult four-electrode branch. Huawei uses a
 * separate child/teenager path and additional post-processing for extreme input
 * combinations; those paths are intentionally not emulated here.
 */
internal object HuaweiScale3BodyComposition {
    enum class Sex {
        FEMALE,
        MALE,
    }

    data class Result(
        val impedanceOhm: Double,
        val bodyFatPercent: Float,
        val waterPercent: Float,
        val skeletalMuscleKg: Float,
        val skeletalMusclePercent: Float,
        val boneMineralKg: Float,
        val leanBodyMassKg: Float,
        val proteinPercent: Float,
        val bmrKcal: Float,
        val visceralFatLevel: Float,
    )

    private data class Coefficients(
        val waterKg: DoubleArray,
        val fatFreeMassKg: DoubleArray,
        val muscleMassKg: DoubleArray,
        val boneMineralKg: DoubleArray,
        val bmr: DoubleArray,
        val visceralFat: DoubleArray,
    )

    /*
     * Feature order:
     *   [heightCm^2 / impedanceOhm,
     *    impedanceOhm,
     *    weightKg,
     *    heightCm,
     *    age^2,
     *    age,
     *    constant]
     */
    private val female = Coefficients(
        waterKg = doubleArrayOf(
            0.445999704, 0.0139999486, 0.167000103, -0.102999643,
            -0.00186499864, 0.172699790, 2.47318013
        ),
        fatFreeMassKg = doubleArrayOf(
            -0.0000000591396, -0.0150000073, 0.198000056, 0.313999865,
            -0.00148397395, 0.124597440, -16.3761132
        ),
        muscleMassKg = doubleArrayOf(
            -0.0000000596289, -0.0130000008, 0.180999914, 0.286000079,
            -0.00136797854, 0.115597450, -14.1328394
        ),
        boneMineralKg = doubleArrayOf(
            -0.00400158350, -0.00200033477, 0.0160000363, 0.0260015858,
            -0.000119001796, 0.00930013136, -1.67940940
        ),
        bmr = doubleArrayOf(
            1.75784170, -0.181837584, 9.65817197, 5.89515491,
            0.00782921597, -2.89574283, -203.303636
        ),
        visceralFat = doubleArrayOf(
            -0.00158317035, 0.00544003197, 0.314994178, -0.103662636,
            0.000967108734, 0.00527136893, -1.33727631
        ),
    )

    private val male = Coefficients(
        waterKg = doubleArrayOf(
            0.446000315, 0.0140000556, 0.166999963, -0.103000253,
            -0.00104299708, 0.0356998392, 9.22059951
        ),
        fatFreeMassKg = doubleArrayOf(
            0.114000146, -0.0149999678, 0.322000075, 0.272999903,
            -0.000979001428, 0.0170001265, -13.0029150
        ),
        muscleMassKg = doubleArrayOf(
            0.107999947, -0.0140000275, 0.305999991, 0.258999945,
            -0.000929995268, 0.0160995340, -12.5056628
        ),
        boneMineralKg = doubleArrayOf(
            -0.00400070678, -0.00200017483, 0.0160001373, 0.0260008239,
            -0.0000579943116, 0.00269952368, -1.50661300
        ),
        bmr = doubleArrayOf(
            6.43629949, -0.0002299374, 10.9972143, 4.74191847,
            0.0238549107, -6.20381101, -233.995162
        ),
        visceralFat = doubleArrayOf(
            -0.168283428, -0.0119657591, 0.377569276, -0.138136532,
            0.000252403405, 0.134562343, 17.0432610
        ),
    )

    fun calculate(
        heightCm: Float,
        weightKg: Float,
        ageYears: Int,
        sex: Sex,
        impedanceOhm: Double,
    ): Result? {
        if (heightCm <= 0f || weightKg <= 0f || impedanceOhm <= 0.0) return null

        // Huawei uses a separate teenager/child recalculation path.
        if (ageYears < 18) return null

        val h = heightCm.toDouble()
        val w = weightKg.toDouble()
        val age = ageYears.toDouble()
        val z = impedanceOhm

        // Reject obviously malformed input. These limits are deliberately broad.
        if (h !in 120.0..230.0 || w !in 20.0..250.0 || z !in 100.0..1000.0) return null

        val x = doubleArrayOf(
            h * h / z,
            z,
            w,
            h,
            age * age,
            age,
            1.0,
        )

        val c = if (sex == Sex.MALE) male else female

        val waterKg = evaluate(c.waterKg, x)
        val ffmKg = evaluate(c.fatFreeMassKg, x)
        val muscleMassKg = evaluate(c.muscleMassKg, x)
        val boneKg = evaluate(c.boneMineralKg, x)

        // Huawei's skeletal-muscle output is derived from its muscle-mass model.
        val skeletalMuscleKg = 0.605 * muscleMassKg - 1.833

        val fatPercent = (1.0 - ffmKg / w) * 100.0
        val waterPercent = waterKg / w * 100.0
        val skeletalMusclePercent = skeletalMuscleKg / w * 100.0

        // Huawei Health's displayed protein percentage is consistent with
        // protein mass = FFM - total body water - bone mineral.
        val proteinKg = ffmKg - waterKg - boneKg
        val proteinPercent = proteinKg / w * 100.0

        // The native four-electrode path stores these as positive integers.
        val bmr = evaluate(c.bmr, x).toInt()
        val visceralFat = max(1, evaluate(c.visceralFat, x).toInt())

        if (
            !fatPercent.isFinite() || fatPercent !in 0.0..75.0 ||
            !waterPercent.isFinite() || waterPercent !in 10.0..85.0 ||
            !skeletalMuscleKg.isFinite() || skeletalMuscleKg !in 0.0..w ||
            !boneKg.isFinite() || boneKg !in 0.0..10.0 ||
            !proteinPercent.isFinite() || proteinPercent !in 0.0..40.0 ||
            bmr !in 500..5000 ||
            visceralFat !in 1..60
        ) {
            return null
        }

        return Result(
            impedanceOhm = z,
            bodyFatPercent = fatPercent.toFloat(),
            waterPercent = waterPercent.toFloat(),
            skeletalMuscleKg = skeletalMuscleKg.toFloat(),
            skeletalMusclePercent = skeletalMusclePercent.toFloat(),
            boneMineralKg = boneKg.toFloat(),
            leanBodyMassKg = ffmKg.toFloat(),
            proteinPercent = proteinPercent.toFloat(),
            bmrKcal = bmr.toFloat(),
            visceralFatLevel = visceralFat.toFloat(),
        )
    }

    private fun evaluate(coefficients: DoubleArray, features: DoubleArray): Double {
        var result = 0.0
        for (i in coefficients.indices) {
            result += coefficients[i] * features[i]
        }
        return result
    }
}
