/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.libs

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Kotlin implementation of PICOOC 4.3.0's reverse-engineered Caucasian/White BIA calculations.
 *
 * The vendor names the two pieces of persistent profile state `anchor_weight` and
 * `anchor_bata` (sic). They are not scale calibration constants: the app derives them from a
 * group of nearby historical weigh-ins. Keeping them explicit makes the calculation testable
 * and lets a handler persist them without shipping PICOOC's proprietary native library.
 */
internal object PicoocWhiteBodyComposition {
    data class Input(
        val male: Boolean,
        val heightCm: Float,
        val age: Int,
        val weightKg: Float,
        val correctedImpedanceOhm: Int,
        val anchorWeightKg: Int,
        val anchorBeta: Int,
        val hour: Int = 0,
        val previousMeasurementAnchor: Int = 0,
    )

    data class Result(
        val bodyFatPercent: Float,
        /** PICOOC's broad muscle metric: lean body mass minus bone mass, as % of weight. */
        val totalMusclePercent: Float,
        val boneMassKg: Float,
        val waterPercent: Float,
        val basalMetabolicRateKcal: Int,
        val proteinPercent: Float,
        val bmi: Float,
        val visceralFatLevel: Int,
        val leanBodyMassKg: Float,
        val metabolicAge: Int,
        val skeletalMusclePercent: Float,
        val fatReferencePercent: Float,
        val anchorBeta: Int,
        val measurementAnchor: Int,
    )

    fun calculate(input: Input): Result? {
        val h = if (input.heightCm < 3f) input.heightCm * 100f else input.heightCm
        val w = input.weightKg
        val r = input.correctedImpedanceOhm
        if (h <= 0f || w <= 0f || r < 50 || input.age < 16 || input.anchorWeightKg <= 0) {
            return null
        }

        val sex = if (input.male) 1 else 0
        val reference = fatReference(h, w, sex, input.age, r, input.anchorWeightKg)
        val beta = if (input.anchorBeta >= 19) {
            input.anchorBeta
        } else {
            initialAnchorBeta(
                sex = sex,
                age = input.age,
                bmiTimesTen = (w / square(h / 100f) * 10f).roundToInt(),
                referenceFatTimesTen = (reference * 10f).roundToInt(),
                impedance = r,
                hour = input.hour,
            )
        }

        val initialCoefficient = if (input.male) 0.495f else 0.515f
        var fat = fatFormula(h, w, initialCoefficient, sex, input.age, r, input.anchorWeightKg, beta)
        var anchorPart = 0

        // PICOOC has a small continuity correction only at exceptionally low fat values. The
        // previous measurement stores beta*10 + this one-digit band number.
        val previousPart = if (input.previousMeasurementAnchor > 100) {
            input.previousMeasurementAnchor % 10
        } else {
            0
        }
        val thresholds = if (input.male) {
            shiftedThresholds(floatArrayOf(5f, 6f, 7f, 8f, 9f), previousPart)
        } else {
            shiftedThresholds(floatArrayOf(6f, 7f, 8f, 9f, 10f, 11f), previousPart)
        }
        val coefficients = if (input.male) {
            floatArrayOf(0.435f, 0.445f, 0.455f, 0.465f, 0.475f)
        } else {
            floatArrayOf(0.475f, 0.4825f, 0.49f, 0.495f, 0.5f, 0.505f)
        }
        for (index in thresholds.indices) {
            if (fat <= thresholds[index]) {
                anchorPart = index + 1
                fat = fatFormula(h, w, coefficients[index], sex, input.age, r, input.anchorWeightKg, beta)
                break
            }
        }

        val lean = w * (1f - fat / 100f)
        val water = water(h, w, fat, sex, input.age, r)
        val bone = if (input.male) lean * 0.05f + w * 0.00237f else lean * 0.0868f - w * 0.02085f
        val totalMuscle = 100f - fat - bone / w * 100f
        val protein = 100f - fat - water - bone / w * 100f
        val bmr = bmr(h, w, lean, fat, sex, input.age)
        val bmi = w / square(h / 100f)
        val visceral = visceralFat(h, w, input.age, r)
        val rawBodyAge = rawBodyAge(w, h, fat, sex, input.age)
        val metabolicAge = adjustedBodyAge(input.age, sex, bmr, w, rawBodyAge)
        val skeletal = skeletalMuscle(h, w, sex, input.age, r)

        return Result(
            bodyFatPercent = fat,
            totalMusclePercent = totalMuscle,
            boneMassKg = bone,
            waterPercent = water,
            basalMetabolicRateKcal = bmr,
            proteinPercent = protein,
            bmi = bmi,
            visceralFatLevel = visceral,
            leanBodyMassKg = lean,
            metabolicAge = metabolicAge,
            skeletalMusclePercent = skeletal,
            fatReferencePercent = reference,
            anchorBeta = beta,
            measurementAnchor = beta * 10 + anchorPart,
        )
    }

    /** Vendor impedance stabilisation: reuse a recent correction, otherwise round to 10 ohms. */
    fun correctedImpedance(
        rawOhm: Int,
        weightKg: Float,
        timestampMs: Long,
        previousRawOhm: Int?,
        previousWeightKg: Float?,
        previousTimestampMs: Long?,
        previousCorrectedOhm: Int?,
    ): Int {
        val canReuse = previousRawOhm != null && previousWeightKg != null &&
            previousTimestampMs != null && timestampMs - previousTimestampMs in 0L..600_000L &&
            abs(weightKg - previousWeightKg) <= 1f && abs(rawOhm - previousRawOhm) < 10
        if (canReuse && previousCorrectedOhm != null && previousCorrectedOhm > 0) {
            return previousCorrectedOhm
        }
        val source = if (canReuse) previousRawOhm else rawOhm
        return ((source / 10.0) + 0.5).toInt() * 10
    }

    /** Vendor anchor bucket: keep it over [-1,+2) kg, otherwise reset to truncated weight. */
    fun anchorWeight(weightKg: Float, previousAnchorWeightKg: Int?): Int {
        val previous = previousAnchorWeightKg?.takeIf { it > 0 } ?: return weightKg.toInt()
        val tenths = (weightKg * 10f).roundToInt()
        return if (tenths >= (previous + 2) * 10 || tenths < (previous - 1) * 10) {
            weightKg.toInt()
        } else {
            previous
        }
    }

    private fun fatReference(h: Float, w: Float, sex: Int, age: Int, r: Int, anchorWeight: Int): Float {
        fun youngerLean(): Double {
            val intercept = if (sex == 1) 9.28 else 10.985
            val offset = if (sex == 1) 0.7335 else 0.7785
            val coefficient = if (sex == 1) 0.495 else 0.515
            val fatRatio = (h * -0.0767 + intercept + age * 0.0635) / anchorWeight + offset -
                coefficient * h * h / (r * w) + r * 0.00001732 - r * 0.01 / w
            return w * (1.0 - fatRatio)
        }

        val leanKg = when {
            age < 51 -> youngerLean()
            else -> {
                val olderLean = if (sex == 1) {
                    h * 0.499 * h / r + 15.229 + w * 0.134
                } else {
                    h * 0.45954 * h / r - 2.66775 + w * 0.204 + h * 0.05113 +
                        r * 0.00667 - age * 0.04233
                }
                if (age < 61) (60 - age) * youngerLean() / 10.0 + (age - 50) * olderLean / 10.0 else olderLean
            }
        }
        return boundedFat(((w - leanKg) / w * 100.0).toFloat(), sex)
    }

    private fun fatFormula(
        h: Float,
        w: Float,
        coefficient: Float,
        sex: Int,
        age: Int,
        r: Int,
        anchorWeight: Int,
        beta: Int,
    ): Float {
        val intercept = if (sex == 1) 9.28 else 10.985
        val offset = if (sex == 1) 0.7335 else 0.7785
        val ratio = (h * -0.0767 + intercept + age * 0.0635) / anchorWeight + offset -
            coefficient * h * h / (r * w) + r * 0.00001732 - beta / 100_000_000.0 * r * r
        return boundedFat((ratio * 100.0).toFloat(), sex)
    }

    private fun boundedFat(value: Float, sex: Int): Float = if (sex == 1) {
        value.coerceIn(5f, 62.2f)
    } else {
        value.coerceIn(5f, 66.7f)
    }

    private fun initialAnchorBeta(
        sex: Int,
        age: Int,
        bmiTimesTen: Int,
        referenceFatTimesTen: Int,
        impedance: Int,
        hour: Int,
    ): Int {
        val lowBmi = bmiTimesTen < 250
        val lowReferenceFat = referenceFatTimesTen < 290
        val daytime = hour in 4..11
        val band = if (lowBmi) {
            when {
                impedance < 500 -> 0
                impedance < 550 -> 1
                impedance < 600 -> 2
                impedance < 650 -> 3
                impedance < 700 -> 4
                else -> 5
            }
        } else {
            when {
                impedance < 400 -> 0
                impedance < 500 -> 1
                impedance < 550 -> 2
                impedance < 600 -> 3
                impedance < 650 -> 4
                impedance < 700 -> 5
                else -> 6
            }
        }

        val values = when {
            age < 51 && sex == 1 && lowReferenceFat && lowBmi ->
                pair(intArrayOf(35, 33, 31, 30, 29, 28), intArrayOf(35, 35, 33, 31, 30, 29))
            age < 51 && sex == 1 && lowReferenceFat ->
                pair(intArrayOf(35, 34, 32, 30, 29, 28, 27), intArrayOf(35, 35, 34, 32, 30, 29, 28))
            age < 51 && sex == 1 && lowBmi ->
                pair(intArrayOf(25, 24, 23, 22, 21, 20), intArrayOf(25, 25, 24, 23, 22, 21))
            age < 51 && sex == 1 ->
                pair(intArrayOf(25, 24, 23, 22, 21, 20, 19), intArrayOf(25, 25, 24, 23, 22, 21, 20))
            age < 51 && lowReferenceFat && lowBmi ->
                pair(intArrayOf(38, 36, 35, 34, 33, 32), intArrayOf(38, 38, 36, 35, 34, 33))
            age < 51 && lowReferenceFat ->
                pair(intArrayOf(38, 37, 35, 34, 33, 32, 31), intArrayOf(38, 38, 37, 35, 34, 33, 32))
            age < 51 && lowBmi ->
                pair(intArrayOf(31, 30, 29, 28, 27, 26), intArrayOf(31, 31, 30, 29, 28, 27))
            age < 51 ->
                pair(intArrayOf(31, 30, 29, 28, 27, 26, 25), intArrayOf(31, 31, 30, 29, 28, 27, 26))
            sex == 1 && lowReferenceFat && lowBmi ->
                pair(intArrayOf(36, 35, 34, 33, 31, 29), intArrayOf(36, 36, 35, 34, 33, 31))
            sex == 1 && lowReferenceFat ->
                pair(intArrayOf(36, 35, 34, 33, 32, 30, 28), intArrayOf(36, 36, 35, 34, 33, 32, 30))
            sex == 1 && lowBmi ->
                pair(intArrayOf(25, 24, 23, 22, 21, 20), intArrayOf(25, 25, 24, 23, 22, 21))
            sex == 1 ->
                pair(intArrayOf(25, 24, 23, 22, 21, 20, 19), intArrayOf(25, 25, 24, 23, 22, 21, 20))
            lowReferenceFat && lowBmi ->
                pair(intArrayOf(41, 39, 37, 35, 34, 33), intArrayOf(41, 41, 39, 37, 35, 34))
            lowReferenceFat ->
                pair(intArrayOf(41, 38, 37, 36, 34, 33, 32), intArrayOf(41, 41, 38, 37, 36, 34, 33))
            lowBmi ->
                pair(intArrayOf(30, 29, 28, 27, 26, 25), intArrayOf(30, 30, 29, 28, 27, 26))
            else ->
                pair(intArrayOf(30, 29, 28, 27, 26, 25, 24), intArrayOf(30, 30, 29, 28, 27, 26, 25))
        }
        return values[if (daytime) 1 else 0][band]
    }

    private fun pair(normal: IntArray, daytime: IntArray): Array<IntArray> = arrayOf(normal, daytime)

    private fun shiftedThresholds(base: FloatArray, previousPart: Int): FloatArray =
        base.copyOf().also { values ->
            if (previousPart in 1..values.size) values[previousPart - 1] += 1f
        }

    private fun water(h: Float, w: Float, fat: Float, sex: Int, age: Int, r: Int): Float {
        val value = ((h * 0.3674 * h / r + 6.53 + w * 0.17531 - age * 0.11 + sex * 2.83) / w * 100.0).toFloat()
        return value.coerceAtMost(if (sex == 1) 73.8f else 72.8f)
    }

    private fun bmr(h: Float, w: Float, lean: Float, fat: Float, sex: Int, age: Int): Int {
        val equationOne = if (sex == 1) {
            when {
                age < 4 -> w * 28.2 + h * 8.59 - 371.0
                age < 11 -> w * 15.1 + h * 3.13 + 306.0
                age < 18 -> w * 15.6 + h * 2.66 + 299.0
                age < 31 -> w * 14.4 + h * 3.13 + 113.0
                age < 61 -> w * 11.4 + h * 5.41 - 137.0
                else -> w * 11.4 + h * 5.41 - 256.0
            }
        } else {
            when {
                age < 4 -> w * 30.4 + h * 7.03 - 287.0
                age < 11 -> w * 15.9 + h * 2.1 + 349.0
                age < 18 -> w * 9.4 + h * 2.49 + 462.0
                age < 31 -> w * 10.4 + h * 6.15 - 282.0
                age < 61 -> w * 8.18 + h * 5.02 - 11.6
                else -> w * 8.52 + h * 4.21 + 10.7
            }
        }
        val fatKg = w * fat / 100f
        val bmi = w / square(h / 100f)
        val equationTwo = when {
            bmi <= 18.5f -> (lean * 0.08961 + fatKg * 0.05662 + 0.667) * 1000 / 4.184
            bmi <= 25f -> (lean * 0.0455 + fatKg * 0.0278 - age * 0.01291 +
                if (sex == 1) 4.513 else 3.634) * 1000 / 4.184
            bmi <= 30f -> (lean * 0.03776 + fatKg * 0.03013 - age * 0.01196 +
                if (sex == 1) 4.858 else 3.928) * 1000 / 4.814
            else -> (lean * 0.05685 + fatKg * 0.04022 - age * 0.01402 +
                if (sex == 1) 3.626 else 2.818) * 1000 / 4.814
        }
        val averaged = ((equationOne + equationTwo) / 2.0).roundToInt()
        val leanFloor = (lean * 21.6 + 370.0).toInt()
        return maxOf(averaged, leanFloor)
    }

    private fun visceralFat(h: Float, w: Float, age: Int, r: Int): Int {
        // The mangled signature is (int, float, float, int): age is in x0 and resistance in
        // x1. Ghidra lists the float registers first, which can misleadingly resemble sex/age.
        val raw = ((r * 31f + w * 1_000_000f * 10f / (h * 100f * h) * 940f +
            age * 1049f - 210_772f) / 1000f).toInt().coerceIn(0, 0xffff)
        return (raw / 10 + 1).coerceIn(1, 30)
    }

    private fun skeletalMuscle(h: Float, w: Float, sex: Int, age: Int, r: Int): Float {
        val value = if (sex == 1) {
            (h * 0.3315 * h / r + w * 0.119 - age * 0.0355 + 4.4509) / w * 100.0
        } else {
            (h * 0.3475 * h / r + w * 0.0778 - age * 0.0355 + 3.1369) / w * 100.0
        }
        return value.toFloat().coerceIn(if (sex == 1) 20.1f else 15.1f, if (sex == 1) 70.1f else 68.1f)
    }

    private fun rawBodyAge(w: Float, h: Float, fat: Float, sex: Int, age: Int): Int {
        if (age < 18) return 0
        val weightIndex = ((w * 10f).toInt() - 50) / 10
        val heightIndex = ((h * 10f).toInt() - 1000) / 5
        val weightFactor = WEIGHT_TABLE[weightIndex.coerceIn(0, WEIGHT_TABLE.lastIndex)]
        val heightFactor = if (heightIndex < HEIGHT_TABLE_SHORT.size) {
            HEIGHT_TABLE_SHORT[heightIndex.coerceAtLeast(0)]
        } else {
            HEIGHT_TABLE_TALL[(heightIndex - HEIGHT_TABLE_SHORT.size).coerceIn(0, HEIGHT_TABLE_TALL.lastIndex)]
        }
        val impedanceLike = ((1000f - fat * 10f) * w * 10f * 100_000f /
            (weightFactor * heightFactor) / 8883f)
        val signal = if (sex == 0) {
            fat * 10f * 10.3f + 6716f - impedanceLike * 313f
        } else {
            fat * 10f * 3.86f + 3052f - impedanceLike * 100f
        }
        val estimate = if (sex == 0) age * 9f + abs(signal) * 0.05f else age * 8f + abs(signal) * 0.1f
        return (estimate / 10f + 0.5f).toInt().coerceIn(18, 80)
    }

    private fun adjustedBodyAge(age: Int, sex: Int, bmr: Int, weight: Float, rawAge: Int): Int {
        var adjusted = age
        if (age > 17) {
            val ideal = idealBmr(weight, sex, age)
            adjusted = if (age < 26) {
                val lowRatio = if (sex == 1) 0.9f else 0.95f
                when {
                    bmr >= ideal -> age
                    bmr < ideal * 0.5f -> age + if (sex == 1) 5 else 8
                    bmr < ideal * lowRatio -> age + interpolate(bmr, ideal * 0.5f, ideal * lowRatio, 7, 3)
                    else -> age + interpolate(bmr, ideal * lowRatio, ideal.toFloat(), 3, 1)
                }
            } else if (sex == 1) {
                when {
                    bmr >= ideal * 1.15f -> age - 3
                    bmr >= ideal * 1.10f -> age - interpolate(bmr, ideal * 1.10f, ideal * 1.15f, 1, 2)
                    bmr >= ideal -> age
                    bmr >= ideal * 0.9f -> age + interpolate(bmr, ideal * 0.9f, ideal.toFloat(), 4, 1)
                    bmr >= ideal * 0.5f -> age + interpolate(bmr, ideal * 0.5f, ideal * 0.9f, 8, 4)
                    else -> age + 9
                }
            } else {
                when {
                    bmr >= ideal * 1.10f -> age - 3
                    bmr >= ideal * 1.05f -> age - interpolate(bmr, ideal * 1.05f, ideal * 1.10f, 1, 2)
                    bmr >= ideal -> age
                    bmr >= ideal * 0.95f -> age + interpolate(bmr, ideal * 0.95f, ideal.toFloat(), 4, 1)
                    bmr >= ideal * 0.5f -> age + interpolate(bmr, ideal * 0.5f, ideal * 0.95f, 8, 4)
                    else -> age + 9
                }
            }
        }
        adjusted += ((rawAge - adjusted) / 2f).toInt()
        return adjusted
    }

    private fun idealBmr(weight: Float, sex: Int, age: Int): Int {
        val value = when {
            age < 10 -> weight * if (sex == 1) 42.48 else 40.176
            age < 13 -> weight * if (sex == 1) 35.136 else 33.264
            age < 16 -> weight * if (sex == 1) 29.52 else 27.936
            age < 20 -> if (sex == 1 && weight > 73.5f) weight * 17.5 + 651 else weight * if (sex == 1) 26.352 else 24.192
            age < 25 -> if (sex == 1 && weight > 77.6f) weight * 15.3 + 679 else weight * if (sex == 1) 24.048 else 23.328
            age < 30 -> if (sex == 1 && weight > 89.4f) weight * 15.3 + 679 else weight * if (sex == 1) 22.896 else 22.032
            age < 35 -> if (sex == 1 && weight > 77.8f) weight * 11.6 + 879 else weight * if (sex == 1) 22.896 else 22.032
            age < 55 -> if (sex == 1 && weight > 83.1f) weight * 11.6 + 879 else weight * if (sex == 1) 22.176 else 21.168
            age < 70 -> if (sex == 1 && weight > 86.7f) weight * 11.6 + 879 else weight * if (sex == 1) 21.744 else 20.736
            else -> weight * if (sex == 1) 20.88 else 20.736
        }
        return (value + 0.5).toInt()
    }

    private fun interpolate(value: Int, low: Float, high: Float, lowResult: Int, highResult: Int): Int {
        val clamped = value.toFloat().coerceIn(low, high)
        return (lowResult + (clamped - low) / (high - low) * (highResult - lowResult) + 0.5f).toInt()
    }

    private fun square(value: Float): Float = value * value

    private val WEIGHT_TABLE = intArrayOf(
        20,22,24,25,27,28,29,30,31,32,33,34,35,36,37,38,39,39,40,41,42,42,43,44,45,45,46,47,47,48,48,49,
        50,50,51,51,52,53,53,54,54,55,55,56,56,57,57,58,58,59,59,60,60,61,61,62,62,62,63,63,64,64,65,65,
        66,66,66,67,67,68,68,68,69,69,70,70,70,71,71,72,72,72,73,73,73,74,74,74,75,75,76,76,76,77,77,77,
        78,78,78,79,79,79,80,80,80,81,81,81,82,82,82,83,83,83,83,84,84,84,85,85,85,86,86,86,87,87,87,87,
        88,88,88,89,89,89,89,90,90,90,91,91,91,91,92,92,92,93,93,93,93,94,94,94,94,95,95,95,95,95,95,
    )

    private val HEIGHT_TABLE_SHORT = intArrayOf(
        2118,2125,2132,2139,2146,2153,2160,2167,2174,2181,2188,2195,2202,2209,2216,2222,2229,2236,2243,2250,
        2257,2263,2270,2277,2284,2290,2297,2304,2311,2317,2324,2331,2337,2344,2351,2357,2364,2371,2377,2384,
        2391,2397,2404,2410,2417,2423,2430,2437,2443,2450,2456,2463,2469,2476,2482,2489,2495,2502,2508,2514,
        2521,2527,2534,2540,2546,2553,2559,2566,2572,2578,2585,2591,2597,2604,2610,2616,2623,2629,2635,2642,
        2648,2654,2660,2667,2673,2679,2685,2691,2698,2704,2710,2716,2722,2729,2735,2741,2747,2753,2759,2766,
        2772,2778,2784,2790,2796,2802,2808,2814,2821,2827,2833,2839,2845,2851,2857,2863,2869,2875,2881,2887,2893,
    )

    private val HEIGHT_TABLE_TALL = intArrayOf(
        2899,2905,2911,2917,2923,2929,2935,2941,2947,2953,2958,2964,2970,2976,2982,2988,2994,3000,3006,3012,
        3017,3023,3029,3035,3041,3047,3052,3058,3064,3070,3076,3082,3087,3093,3099,3105,3111,3116,3122,3128,
        3134,3139,3145,3151,3157,3162,3168,3174,3179,3185,3191,3197,3202,3208,3214,3219,3225,3231,3236,3242,
        3248,3253,3259,3265,3270,3276,3281,3287,3293,3298,3304,3310,3315,3321,3326,3332,3337,3343,3349,3354,
        3360,3365,3371,3376,3382,3387,3393,3398,3404,3410,3415,3421,3426,3432,3437,3443,3448,3453,3459,3464,
        3470,3475,3481,3486,3492,3497,3503,3508,3513,3519,3524,3530,3535,3541,3546,3551,3557,3562,3568,3573,
    )
}

/**
 * Online equivalent of PICOOC 4.3.0's chronological history replay.
 *
 * The vendor creates weight clusters while a profile beta is unset and promotes a cluster's beta
 * after its fourth BIA record. Keeping only the aggregates below is equivalent to retaining the
 * full records for chronological live measurements: the original grouping code only reads count,
 * average/min/max weight, last weight and last raw resistance.
 */
internal object PicoocAnchorLearner {
    private const val MAX_CLUSTER_DISTANCE_GRAMS = 3_000
    private const val SECOND_RECORD_DISTANCE_GRAMS = 4_000
    private const val SECOND_RECORD_MAX_R_DIFF_OHM = 60
    const val REQUIRED_MEASUREMENTS = 4

    data class Cluster(
        val beta: Int,
        val count: Int,
        val sumWeightGrams: Long,
        val minWeightGrams: Int,
        val maxWeightGrams: Int,
        val lastWeightGrams: Int,
        val lastRawOhm: Int,
    ) {
        val averageWeightGrams: Double get() = sumWeightGrams.toDouble() / count
    }

    data class State(
        val processedCount: Int = 0,
        val clusters: List<Cluster> = emptyList(),
    ) {
        val progress: Int
            get() = clusters.maxOfOrNull { it.count }?.coerceAtMost(REQUIRED_MEASUREMENTS) ?: 0
    }

    /** A beta of zero asks the body-composition implementation to run its native cold-start tree. */
    data class Decision(
        val beta: Int,
        val clusterIndex: Int?,
    )

    data class Update(
        val state: State,
        val fixedBeta: Int?,
        val progress: Int,
    )

    fun decide(state: State, weightKg: Float, rawOhm: Int): Decision {
        val weightGrams = (weightKg * 1_000f).roundToInt()
        val groups = state.clusters
        if (groups.isEmpty()) return Decision(beta = 0, clusterIndex = null)

        val index = when {
            state.processedCount == 1 -> {
                val first = groups.first()
                val weightDiff = abs(weightGrams - first.lastWeightGrams)
                if (weightDiff <= MAX_CLUSTER_DISTANCE_GRAMS ||
                    (weightDiff <= SECOND_RECORD_DISTANCE_GRAMS &&
                        abs(rawOhm - first.lastRawOhm) <= SECOND_RECORD_MAX_R_DIFF_OHM)
                ) 0 else null
            }

            state.processedCount == 2 && groups.size == 1 -> {
                val first = groups.first()
                if (weightGrams >= first.minWeightGrams - MAX_CLUSTER_DISTANCE_GRAMS &&
                    weightGrams <= first.maxWeightGrams + MAX_CLUSTER_DISTANCE_GRAMS
                ) 0 else null
            }

            else -> closestCluster(groups, weightGrams)
        }
        return if (index == null) Decision(beta = 0, clusterIndex = null)
        else Decision(beta = groups[index].beta, clusterIndex = index)
    }

    fun accept(
        state: State,
        decision: Decision,
        weightKg: Float,
        rawOhm: Int,
        calculatedBeta: Int,
    ): Update {
        require(calculatedBeta >= 19) { "PICOOC beta must be initialized" }
        val weightGrams = (weightKg * 1_000f).roundToInt()
        val groups = state.clusters.toMutableList()
        val updated: Cluster
        if (decision.clusterIndex == null) {
            updated = Cluster(
                beta = calculatedBeta,
                count = 1,
                sumWeightGrams = weightGrams.toLong(),
                minWeightGrams = weightGrams,
                maxWeightGrams = weightGrams,
                lastWeightGrams = weightGrams,
                lastRawOhm = rawOhm,
            )
            groups += updated
        } else {
            val old = groups[decision.clusterIndex]
            updated = old.copy(
                count = old.count + 1,
                sumWeightGrams = old.sumWeightGrams + weightGrams,
                minWeightGrams = minOf(old.minWeightGrams, weightGrams),
                maxWeightGrams = maxOf(old.maxWeightGrams, weightGrams),
                lastWeightGrams = weightGrams,
                lastRawOhm = rawOhm,
            )
            groups[decision.clusterIndex] = updated
        }

        val next = State(processedCount = state.processedCount + 1, clusters = groups)
        return Update(
            state = next,
            fixedBeta = calculatedBeta.takeIf { updated.count >= REQUIRED_MEASUREMENTS },
            progress = next.progress,
        )
    }

    /** Low-resistance measurements are calculated but excluded from beta-cluster learning. */
    fun skip(state: State): State = state.copy(processedCount = state.processedCount + 1)

    fun encode(state: State): String = buildString {
        append(state.processedCount)
        append('|')
        state.clusters.forEachIndexed { index, group ->
            if (index > 0) append(';')
            append(group.beta).append(',')
            append(group.count).append(',')
            append(group.sumWeightGrams).append(',')
            append(group.minWeightGrams).append(',')
            append(group.maxWeightGrams).append(',')
            append(group.lastWeightGrams).append(',')
            append(group.lastRawOhm)
        }
    }

    fun decode(value: String?): State {
        if (value.isNullOrBlank()) return State()
        return runCatching {
            val pieces = value.split('|', limit = 2)
            val processed = pieces[0].toInt().coerceAtLeast(0)
            val groups = pieces.getOrNull(1).orEmpty()
                .split(';')
                .filter { it.isNotBlank() }
                .map { encoded ->
                    val fields = encoded.split(',')
                    require(fields.size == 7)
                    Cluster(
                        beta = fields[0].toInt(),
                        count = fields[1].toInt(),
                        sumWeightGrams = fields[2].toLong(),
                        minWeightGrams = fields[3].toInt(),
                        maxWeightGrams = fields[4].toInt(),
                        lastWeightGrams = fields[5].toInt(),
                        lastRawOhm = fields[6].toInt(),
                    ).also {
                        require(it.beta >= 19 && it.count > 0 && it.sumWeightGrams > 0)
                    }
                }
            State(processedCount = maxOf(processed, groups.sumOf { it.count }), clusters = groups)
        }.getOrDefault(State())
    }

    private fun closestCluster(groups: List<Cluster>, weightGrams: Int): Int? {
        var bestIndex: Int? = null
        var bestDistance = Double.POSITIVE_INFINITY
        var bestCount = -1
        groups.forEachIndexed { index, group ->
            val distance = abs(weightGrams - group.averageWeightGrams)
            if (distance > MAX_CLUSTER_DISTANCE_GRAMS) return@forEachIndexed
            if (distance < bestDistance || (distance == bestDistance && group.count > bestCount)) {
                bestIndex = index
                bestDistance = distance
                bestCount = group.count
            }
        }
        return bestIndex
    }
}
