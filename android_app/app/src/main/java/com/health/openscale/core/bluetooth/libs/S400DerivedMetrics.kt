package com.health.openscale.core.bluetooth.libs

import kotlin.math.floor

/**
 * Version-235 output arithmetic observed in Xiaomi's calculation responses.
 *
 * This is NOT the BIA model. Body fat, bone, water percentage and skeletal
 * muscle must be supplied by a separately validated upstream calculator.
 * Missing components remain missing; lean mass is never substituted for
 * skeletal muscle.
 *
 * Displayed water mass is derived from the displayed water percentage, not the
 * reverse: for 40 kg at 83.3 % water the server reports 33.3 kg, and inverting
 * from a single-decimal mass reintroduces representation error.
 */
object S400DerivedMetrics {
    data class Components(
        val bodyFatPct: Float,
        val boneKg: Float? = null,
        val waterPct: Float? = null,
        val skeletalMuscleKg: Float? = null,
    )

    data class Result(
        val bmi: Float,
        val bfPct: Float,
        val bfKg: Float,
        val ffmKg: Float,
        val ffmPct: Float,
        val bmrKcal: Float,
        val boneKg: Float?,
        val slmKg: Float?,
        val slmPct: Float?,
        val waterKg: Float?,
        val waterPct: Float?,
        val skeletalMuscleKg: Float?,
        val skeletalMusclePct: Float?,
        val idealWeightKg: Float,
    )

    fun compute(heightCm: Float, weightKg: Float, sexMale: Boolean, components: Components): Result {
        require(heightCm.isFinite() && heightCm > 0f)
        require(weightKg.isFinite() && weightKg > 0f)
        require(components.bodyFatPct.isFinite() && components.bodyFatPct in 0f..100f)
        val w = weightKg.toDouble()
        val h = heightCm.toDouble()
        val fatPct = tenth(components.bodyFatPct.toDouble())
        val fat = tenth(w * fatPct / 100.0)
        val ffm = tenth(w - fat)
        val bone = components.boneKg?.let { tenth(it.toDouble()) }
        val waterPct = components.waterPct?.let { tenth(it.toDouble()) }
        val water = waterPct?.let { tenth(w * it / 100.0) }
        val lean = bone?.let { tenth(ffm - it) }
        // Preserve the upstream SMM value: Xiaomi can return more than one decimal
        // when its 0.45/0.70 * SLM clamp is active.
        val skeletal = components.skeletalMuscleKg
        return Result(
            bmi = tenth(w / ((h / 100.0) * (h / 100.0))).toFloat(),
            bfPct = fatPct.toFloat(),
            bfKg = fat.toFloat(),
            ffmKg = ffm.toFloat(),
            ffmPct = tenth(100.0 * ffm / w).toFloat(),
            bmrKcal = floor(370.0 + 21.6 * ffm + 0.5).toFloat(),
            boneKg = bone?.toFloat(),
            slmKg = lean?.toFloat(),
            slmPct = lean?.let { tenth(100.0 * it / w).toFloat() },
            waterKg = water?.toFloat(),
            waterPct = waterPct?.toFloat(),
            skeletalMuscleKg = skeletal,
            skeletalMusclePct = skeletal?.let { tenth(100.0 * it.toDouble() / w).toFloat() },
            idealWeightKg = tenth(if (sexMale) (h - 80.0) * 0.7 else (h - 70.0) * 0.6).toFloat(),
        )
    }

    // Inputs originate as displayed decimal values. Float representation error
    // must not turn a positive halfway value into an accidental down-round.
    private fun tenth(value: Double): Double = floor(value * 10.0 + 0.5 + 0.00001) / 10.0
}
