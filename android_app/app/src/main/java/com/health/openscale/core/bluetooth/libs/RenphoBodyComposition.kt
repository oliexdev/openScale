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

import kotlin.math.roundToInt

/**
 * Renpho composition approximations shared by the QN and 0x55aa handlers.
 * Body fat reported by a scale takes precedence over the off-scale approximation.
 * Coefficients follow renpho-escs20m/body_metrics.py (MIT, Copyright (c) 2026 Ron):
 * https://github.com/ronnnnnnnnnnnnn/renpho-escs20m/tree/24d78369bed9dc3d50a12d2a548a80f9f3b27020
 * These are estimates of the vendor algorithm, not its proprietary implementation.
 */
object RenphoBodyComposition {
    /** Body fat % — linear regression on BMI, age and impedance. */
    fun bodyFatPercent(weightKg: Float, heightM: Float, age: Int, isMale: Boolean, resistance: Int): Float {
        val bmi = weightKg / (heightM * heightM)
        return if (isMale)
            (1.524f * bmi + 0.103f * age - 21.992f - 500f / resistance).coerceIn(1f, 60f)
        else
            (1.545f * bmi + 0.097f * age - 12.689f - 500f / resistance).coerceIn(1f, 60f)
    }

    /** Water % — derived from body fat via sex-specific linear coefficients. */
    fun waterPercent(bf: Float, isMale: Boolean) =
        (if (isMale) 72.202f - 0.72223f * bf else 68.651f - 0.68725f * bf).coerceIn(20f, 80f)

    /** Skeletal muscle % — derived from body fat. */
    fun skeletalMusclePercent(bf: Float, isMale: Boolean) =
        (if (isMale) 64.713f - 0.65508f * bf else 58.390f - 0.58654f * bf).coerceIn(17.5f, 70f)

    /** Bone mass (kg) — weight minus soft-lean mass minus fat mass. */
    fun boneMass(weightKg: Float, bf: Float, isMale: Boolean): Float {
        val softLeanPct =
            (if (isMale) 94.992f - 0.94969f * bf else 93.988f - 0.93960f * bf).coerceIn(0f, 100f)
        val softLeanKg = (weightKg * softLeanPct / 100f).coerceIn(3.75f, 110f)
        val bfKg = bf * weightKg / 100f
        return (weightKg - softLeanKg - bfKg).coerceIn(1f, 7f)
    }

    /** Protein % — derived from body fat. */
    fun proteinPercent(bf: Float, isMale: Boolean) =
        (if (isMale) 22.787f - 0.22735f * bf else 25.340f - 0.30245f * bf).coerceIn(5f, 24f)

    /** Vendor-style BMR estimate from bone mass; coefficients are approximate. */
    fun basalMetabolicRate(boneKg: Float, isMale: Boolean): Int =
        (if (isMale) 372.7023f + 430.9015f * boneKg else 370.5818f + 359.6167f * boneKg)
            .roundToInt().coerceIn(900, 2500)

}
