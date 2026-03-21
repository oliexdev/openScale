package com.health.openscale.core.bluetooth.libs

import com.health.openscale.core.data.GenderType
import kotlin.math.floor

// Based on https://github.com/ronnnnnnnnnnnnn/etekcity_esf551_ble

// TODO: Why are there multiple different ways to calculate these values?
//  Some values might depend on device-specific factors, but others are independent or could be added even for devices
//  which don't otherwise provide these values.
//  Introduce either one true calculation method or maybe configurable alternatives (why?), so we have standard
//  calculations across all devices instead of re-implementing this in different ways.
//  Values that are measured by the hardware itself can still be taken from the hardware via the handler, but all
//  undefined values could fall back to the standard calculation.
data class EtekcityLib(
    val gender: GenderType,
    val age: Int,
    val weightKg: Double,
    val heightM: Double,
    val impedance: Double,
) {
    val bmi: Double = weightKg / (heightM * heightM)

    val isMale = gender == GenderType.MALE

    val bodyFatPercentage: Double by lazy {
        val ageFactor = if (isMale) 0.103 else 0.097
        val bmiFactor = if (isMale) 1.524 else 1.545
        val constant = if (isMale) 22.0 else 12.7
        val raw = floor((ageFactor * age + bmiFactor * bmi - 500.0 / impedance - constant) * 10) / 10.0
        raw.coerceIn(5.0, 75.0)
    }

    val fatFreeWeight: Double = weightKg * (1 - bodyFatPercentage / 100)

    val visceralFat: Double by lazy {
        val bmiFactor = if (isMale) 0.8666 else 0.8895
        val bfpFactor = if (isMale) 0.0082 else 0.0943
        val fatFactor = if (isMale) 0.026 else -0.0534
        val constant = if (isMale) 14.2692 else 16.215
        (bmiFactor * bmi + bfpFactor * bodyFatPercentage + fatFactor * (weightKg - fatFreeWeight) - constant)
            .coerceIn(1.0, 30.0)
    }

    val water: Double by lazy {
        val ff1Factor = if (isMale) 0.05 else 0.06
        val ff2Factor = if (isMale) 0.76 else 0.73
        val ff1 = maxOf(1.0, ff1Factor * fatFreeWeight)
        ((ff2Factor * (fatFreeWeight - ff1) / weightKg * 100.0)).coerceIn(10.0, 80.0)
    }

    val basalMetabolicRate: Double = (fatFreeWeight * 21.6 + 370).coerceIn(900.0, 2500.0)

    val skeletalMusclePercentage: Double by lazy {
        val ff1Factor = if (isMale) 0.05 else 0.06
        val ff2Factor = if (isMale) 0.68 else 0.62
        val ff1 = maxOf(1.0, ff1Factor * fatFreeWeight)
        ff2Factor * (fatFreeWeight - ff1) / weightKg * 100.0
    }

    val boneMass: Double by lazy {
        val ff1Factor = if (isMale) 0.05 else 0.06
        maxOf(1.0, ff1Factor * fatFreeWeight)
    }

    val subcutaneousFat: Double by lazy {
        val bfpFactor = if (isMale) 0.965 else 0.983
        val vfvFactor = if (isMale) 0.22 else 0.303
        bfpFactor * bodyFatPercentage - vfvFactor * visceralFat
    }

    val muscleMass: Double by lazy {
        weightKg - boneMass - 0.01 * bodyFatPercentage * weightKg
    }

    val proteinPercentage: Double by lazy {
        val bfpFactor = if (isMale) 1.0 else 1.05
        maxOf(5.0, 100 - bfpFactor * bodyFatPercentage - boneMass / weightKg * 100 - water)
    }

    val weightScore: Int by lazy {
        val heightFactor = if (isMale) 100 else 137
        val constant = if (isMale) 80 else 110
        val factor = if (isMale) 0.7 else 0.45
        val res = factor * (heightFactor * heightM - constant)

        if (res <= weightKg) {
            if (1.3 * res < weightKg) {
                return@lazy 50
            }
            return@lazy (100 - 50 * (weightKg - res) / (0.3 * res)).toInt()
        }
        if (res * 0.7 < weightKg) {
            return@lazy (100 - 50 * (res - weightKg) / (0.3 * res)).toInt()
        }
        for (x in 0..<6) {
            if (res * x / 10 > weightKg) {
                return@lazy x * 10
            }
        }
        0
    }

    val fatScore: Int by lazy {
        val constant = if (isMale) 16 else 26
        if (constant < bodyFatPercentage) {
            if (bodyFatPercentage >= 45) {
                50
            } else {
                (100 - 50 * (bodyFatPercentage - constant) / (45 - constant)).toInt()
            }
        } else {
            (100 - 50 * (constant - bodyFatPercentage) / (constant - 5)).toInt()
        }
    }

    val bmiScore: Int = when {
        bmi >= 35 -> 50
        bmi >= 22 -> (100 - 3.85 * (bmi - 22)).toInt()
        bmi >= 15 -> (100 - 3.85 * (22 - bmi)).toInt()
        bmi >= 10 -> 40
        bmi >= 5 -> 30
        else -> 20
    }

    val healthScore: Int = (weightScore + fatScore + bmiScore) / 3

    val metabolicAge: Int by lazy {
        val ageAdjustmentFactor = when {
            healthScore < 50 -> 0
            healthScore < 60 -> 1
            healthScore < 65 -> 2
            healthScore < 68 -> 3
            healthScore < 70 -> 4
            healthScore < 73 -> 5
            healthScore < 75 -> 6
            healthScore < 80 -> 7
            healthScore < 85 -> 8
            healthScore < 88 -> 9
            healthScore < 90 -> 10
            healthScore < 93 -> 11
            healthScore < 95 -> 12
            healthScore < 97 -> 13
            healthScore < 98 -> 14
            healthScore < 99 -> 15
            else -> 16
        }
        maxOf(18, age + 8 - ageAdjustmentFactor)
    }
}
