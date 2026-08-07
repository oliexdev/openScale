/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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

import com.health.openscale.core.data.GenderType

/**
 * Offline compatibility implementation of BestHealth's `BHKeep_2023-03-30` two-leg,
 * dual-frequency body-composition routine bundled in Keep 9.0.80.
 *
 * The APK does not call this library from its application code; captured Keep reports appear to
 * receive composition values from Keep's server and differ slightly from this routine. These
 * results are therefore local BIA estimates, not Keep cloud values or direct scale measurements.
 * The implementation was recovered for interoperability and does not contain or load vendor code.
 *
 * Inputs and fixed-point output units match the bundled SDK. Keeping the intermediate arithmetic
 * as [Float] and tenths is intentional: the ARM64 routine uses IEEE-754 single precision and
 * truncation toward zero.
 */
internal object KeepS3BodyComposition {
    data class Input(
        val gender: GenderType,
        val age: Int,
        val heightCm: Int,
        val weightKg: Float,
        val impedance50Ohm: Int,
        val impedance100Ohm: Int,
        val athlete: Boolean = false,
    )

    data class Result(
        val bodyFatKg: Float,
        val bodyFatPercent: Float,
        val fatFreeMassKg: Float,
        val waterPercent: Float,
        val boneKg: Float,
        val muscleKg: Float,
        val musclePercent: Float,
        val skeletalMuscleKg: Float,
        val skeletalMusclePercent: Float,
        val subcutaneousFatKg: Float,
        val subcutaneousFatPercent: Float,
        val proteinPercent: Float,
        val visceralFatLevel: Int,
        val basalMetabolicRateKcal: Int,
        val bodyAge: Int,
        val bmi22ReferenceWeightKg: Float,
    )

    fun calculate(input: Input): Result? {
        if (input.age !in 6..99 || input.heightCm !in 90..220) return null
        if (!input.weightKg.isFinite() || input.weightKg !in 10f..200f) return null
        if (input.impedance50Ohm !in 200..1200 || input.impedance100Ohm !in 200..1200) {
            return null
        }

        val male = input.gender == GenderType.MALE
        val weightRaw = (input.weightKg * 10f).toInt().coerceIn(100, 2000)
        val height = input.heightCm
        val age = input.age
        val averageImpedance = (input.impedance50Ohm + input.impedance100Ohm) ushr 1
        val heightSquared = height * height
        val bmiRaw = weightRaw * 10_000 / heightSquared
        val bmi22ReferenceWeightRaw = (heightSquared.toFloat() * 0.022f).toInt()

        val rawFatFreeMass = heightSquared.toFloat() * 9.058f / 10_000f +
            12.226f + weightRaw.toFloat() * 0.032f -
            averageImpedance.toFloat() * 0.0068f - age.toFloat() * 0.0542f

        var adjustedFatFreeMass = rawFatFreeMass - when {
            male -> 0.8f
            age < 50 -> 9.25f
            else -> 7.25f
        }

        if (male) {
            adjustedFatFreeMass *= 1.05f
            if (averageImpedance.toFloat() / height.toFloat() < 2.6f) {
                adjustedFatFreeMass *= 1.03f
            } else {
                adjustedFatFreeMass *= 0.96f
                if (weightRaw < 610) adjustedFatFreeMass *= 0.97f
                if (height > 170) adjustedFatFreeMass *= 0.98f
            }
        } else {
            adjustedFatFreeMass *= 1.02f
            if (weightRaw < 500) adjustedFatFreeMass *= 1.02f
            if (weightRaw > 600) adjustedFatFreeMass *= 0.96f
            if (height > 160) adjustedFatFreeMass *= 1.03f
        }

        var fatKg = weightRaw.toFloat() / 10f - adjustedFatFreeMass
        if (input.athlete) {
            fatKg = if (male) fatKg * 0.778f - 0.93f else fatKg * 0.992f - 1.5f
        }
        val fatRateRaw = (fatKg * 10_000f / weightRaw.toFloat()).toInt().coerceIn(50, 750)
        val fatKgRaw = fatRateRaw * weightRaw / 1000
        val fatFreeMassRaw = weightRaw - fatKgRaw

        var waterRateRaw = ((1000 - fatRateRaw) * 7 / 10).let { base ->
            base * if (base > 500) 98 else 102
        } / 100
        if (input.athlete) {
            waterRateRaw = (waterRateRaw.toFloat() * (if (male) 0.996f else 0.985f) +
                (if (male) 4f else 9f)).toInt()
        }
        waterRateRaw = waterRateRaw.coerceAtLeast(350)

        var boneRaw = (rawFatFreeMass * 0.5158f - if (male) 1.802f else 2.4569f).toInt()
        boneRaw += if (boneRaw > 22) 1 else -1
        if (input.athlete) {
            boneRaw += when {
                boneRaw < 20 -> 1
                boneRaw < 30 -> 2
                else -> 3
            }
        }

        val bodyAge = calculateBodyAge(age, bmiRaw)
        var bmrRaw = if (male) {
            weightRaw.toFloat() * 1.4916f + 877.8f - height.toFloat() * 0.726f -
                age.toFloat() * 8.976f
        } else {
            weightRaw.toFloat() * 1.0204f + 864.6f - height.toFloat() * 0.3934f -
                age.toFloat() * 6.204f
        }.toInt()
        if (input.athlete) bmrRaw = (bmrRaw.toFloat() * 1.16f - 149f).toInt()
        bmrRaw = bmrRaw.coerceAtLeast(500)

        val visceralFat = calculateVisceralFat(
            male = male,
            athlete = input.athlete,
            age = age,
            height = height,
            weightRaw = weightRaw,
            heightSquared = heightSquared,
        )

        val muscleKgRaw = (fatFreeMassRaw - boneRaw).coerceAtLeast(0)
        val muscleRateRaw = (muscleKgRaw.toFloat() * 1000f / weightRaw.toFloat()).toInt()
        val waterKgRaw = waterRateRaw * weightRaw / 1000
        val skeletalMuscleKgRaw = (waterKgRaw.toFloat() * 0.832f - 27.354f)
            .toInt().coerceAtLeast(0)
        val skeletalMuscleRate = if (weightRaw > 0) {
            skeletalMuscleKgRaw.toFloat() * 100f / weightRaw.toFloat()
        } else {
            0f
        }

        val boneRateRaw = boneRaw.toFloat() * 1000f / weightRaw.toFloat()
        val proteinRateRaw = ((1000 - fatRateRaw).toFloat() -
            waterRateRaw.toFloat() * 1.08f - boneRateRaw).toInt().coerceIn(20, 300)

        var subcutaneousFatKgRaw = averageImpedance.toFloat() * 0.031f +
            bmiRaw.toFloat() * 0.94f + age.toFloat() * 1.049f - 210.772f
        subcutaneousFatKgRaw = subcutaneousFatKgRaw.coerceIn(10f, 300f) * -9.4f / 34f +
            fatKgRaw.toFloat()
        if (input.athlete) subcutaneousFatKgRaw *= 0.85f
        val subcutaneousFatRateRaw = (subcutaneousFatKgRaw * 1000f / weightRaw.toFloat())
            .toInt().coerceIn(10, 600)
        val storedSubcutaneousFatKgRaw = subcutaneousFatRateRaw * weightRaw / 1000

        return Result(
            bodyFatKg = fatKgRaw / 10f,
            bodyFatPercent = fatRateRaw / 10f,
            fatFreeMassKg = fatFreeMassRaw / 10f,
            waterPercent = waterRateRaw / 10f,
            boneKg = boneRaw / 10f,
            muscleKg = muscleKgRaw / 10f,
            musclePercent = muscleRateRaw / 10f,
            skeletalMuscleKg = skeletalMuscleKgRaw / 10f,
            skeletalMusclePercent = skeletalMuscleRate,
            subcutaneousFatKg = storedSubcutaneousFatKgRaw / 10f,
            subcutaneousFatPercent = subcutaneousFatRateRaw / 10f,
            proteinPercent = proteinRateRaw / 10f,
            visceralFatLevel = visceralFat,
            basalMetabolicRateKcal = bmrRaw,
            bodyAge = bodyAge,
            bmi22ReferenceWeightKg = bmi22ReferenceWeightRaw / 10f,
        )
    }

    private fun calculateBodyAge(age: Int, bmiRaw: Int): Int {
        val first = (age.toFloat() + 28.428f - bmiRaw.toFloat() * 0.1428f)
            .toInt().coerceIn(age - 5, age + 5)
        val second = (bmiRaw.toFloat() * 0.1724f + age.toFloat() - 34.931f)
            .toInt().coerceIn(age - 8, age + 8)
        val result = if (bmiRaw < 30) {
            first.toFloat() * 0.6f + second.toFloat() * 0.4f
        } else {
            first.toFloat() * 0.4f + second.toFloat() * 0.6f
        }
        return result.toInt().coerceIn(6, 99)
    }

    private fun calculateVisceralFat(
        male: Boolean,
        athlete: Boolean,
        age: Int,
        height: Int,
        weightRaw: Int,
        heightSquared: Int,
    ): Int {
        val weight = weightRaw.toFloat()
        val stature = height.toFloat()
        var visceral = if (male) {
            if (weight * 0.16f + 63f > stature) {
                weight * 30.5f /
                    (heightSquared.toFloat() * 0.0826f - stature * 0.4f + 48f) -
                    2.9f + age.toFloat() * 0.15f
            } else {
                (stature * -0.0015f + 0.765f) * weight / 10f -
                    stature * 0.143f + age.toFloat() * 0.15f - 5f
            }
        } else {
            if (stature * 5f - 130f < weight) {
                weight * 50f /
                    (heightSquared.toFloat() * 0.1158f + stature * 1.45f - 144f) -
                    6f + age.toFloat() * 0.07f
            } else {
                (stature * -0.0024f + 0.691f) * weight / 10f -
                    stature * 0.027f + age.toFloat() * 0.07f - 10.5f
            }
        }

        if (athlete) {
            visceral = when {
                visceral < 2f -> 1f
                visceral < 10f -> visceral - 2f
                visceral < 20f -> visceral * 0.8f
                else -> visceral * 0.85f
            }
        }
        return visceral.toInt().coerceIn(1, 50)
    }
}
