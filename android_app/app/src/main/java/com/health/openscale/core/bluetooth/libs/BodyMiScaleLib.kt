/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * Portions derived from bodymiscale (C) dckiller51 and contributors, GPL-3.0
 * (https://github.com/dckiller51/bodymiscale).
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
 * Scientific mono-frequency (standard impedance) body-composition estimator, ported from the
 * Home Assistant integration **bodymiscale** by dckiller51 (GPL-3.0):
 *   https://github.com/dckiller51/bodymiscale
 *   custom_components/bodymiscale/metrics/{impedance,weight}.py + util.py
 *
 * A hardware-calibrated LBM (the same baseline Xiaomi uses) is combined with peer-reviewed
 * downstream formulas — body fat via the Siri (1956) 2-compartment model, water via the
 * Pace & Rathbun (1945) constant, protein via Wang (1999), and BMR via the Schofield (WHO)
 * equation.
 *
 * All metrics are chained and internally consistent: fat is derived from LBM, water and
 * protein from fat/LBM, and muscle from fat and bone. Compute [getLbm] first and feed its
 * result into the other methods (as the callers in bodymiscale do) to reproduce its output
 * exactly.
 *
 * The reverse-engineered Zepp Life / Mi Fit algorithm is not reproduced here — openScale
 * selects [MiScaleLib] for that path. The S400 dual-frequency mode of bodymiscale is likewise
 * out of scope; openScale has its own dual-frequency path in [S400BodyComposition].
 */
class BodyMiScaleLib(
    private val gender: GenderType,
    private val age: Int,
    private val heightCm: Float,
) {
    private val isMale = gender == GenderType.MALE

    /**
     * Lean / fat-free body mass in kg — the Xiaomi hardware-calibrated formula, shared by
     * both modes and capped at 98% of body weight. Everything downstream depends on this.
     */
    fun getLbm(weightKg: Float, impedance: Float): Float {
        val lbm = (heightCm * 9.058f / 100f) * (heightCm / 100f) +
            weightKg * 0.32f + 12.226f - impedance * 0.0068f - age * 0.0542f
        return minOf(lbm, weightKg * 0.98f)
    }

    /** Body fat percentage via the Siri (1956) 2-compartment model. Pass the [getLbm] result as [lbm]. */
    fun getFat(weightKg: Float, lbm: Float): Float {
        val fat = (weightKg - lbm) / weightKg * 100f
        return fat.coerceIn(5f, 75f)
    }

    /** Water percentage of body weight, via the Pace & Rathbun (1945) 0.73 constant. */
    fun getWater(fatPercent: Float): Float =
        ((100f - fatPercent) * 0.73f).coerceIn(35f, 73f)

    /** Protein percentage via Wang (1999): protein ≈ 19.5% of LBM. */
    fun getProtein(weightKg: Float, lbm: Float): Float =
        (lbm * 0.195f / weightKg * 100f).coerceIn(5f, 32f)

    /** Bone mass in kg — empirical formula shared by all modes, driven by [getLbm]. */
    fun getBoneMass(lbm: Float): Float {
        val base = if (isMale) 0.18016894f else 0.245691014f
        var bone = (base - lbm * 0.05158f) * -1f
        bone = if (bone > 2.2f) bone + 0.1f else bone - 0.1f
        if ((isMale && bone > 5.2f) || (!isMale && bone > 5.1f)) bone = 8.0f
        return bone.coerceIn(0.5f, 8f)
    }

    /**
     * Total muscle mass in kg: weight − fat mass − bone mass. Matches bodymiscale's
     * "muscle_mass" sensor (the "Mięśnie" / Masa mięśniowa value), not skeletal muscle.
     */
    fun getMuscleMass(weightKg: Float, fatPercent: Float, boneMassKg: Float): Float {
        val muscle = weightKg - (fatPercent * 0.01f * weightKg) - boneMassKg
        return muscle.coerceIn(10f, 120f)
    }

    /** Basal metabolic rate in kcal/day via the Schofield (WHO) equation. */
    fun getBmr(weightKg: Float): Float =
        schofieldBmr(weightKg).coerceIn(500f, 5000f)

    /** Schofield BMR by age bracket (WHO standard). */
    private fun schofieldBmr(weightKg: Float): Float {
        val coeffs = if (isMale) MALE_SCHOFIELD else FEMALE_SCHOFIELD
        val (slope, constant) = when {
            age < 3 -> coeffs[0]
            age < 10 -> coeffs[1]
            age < 18 -> coeffs[2]
            age < 30 -> coeffs[3]
            age < 60 -> coeffs[4]
            else -> coeffs[5]
        }
        return slope * weightKg + constant
    }

    /** Visceral fat rating (Zepp Life formula, shared by all modes). */
    fun getVisceralFat(weightKg: Float): Float {
        val h = heightCm
        val w = weightKg
        val vfal = if (isMale) {
            if (h < w * 1.6f + 63.0f)
                age * 0.15f + ((w * 305.0f) / ((h * 0.0826f * h - h * 0.4f) + 48.0f) - 2.9f)
            else
                age * 0.15f + (w * (h * -0.0015f + 0.765f) - h * 0.143f) - 5.0f
        } else {
            if (w <= h * 0.5f - 13.0f)
                age * 0.07f + (w * (h * -0.0024f + 0.691f) - h * 0.027f) - 10.5f
            else
                age * 0.07f + ((w * 500.0f) / ((h * 1.45f + h * 0.1158f * h) - 120.0f) - 6.0f)
        }
        return vfal.coerceIn(1f, 50f)
    }

    private companion object {
        // Schofield (slope, constant) by bracket: 0-3, 3-10, 10-18, 18-30, 30-60, 60+
        val MALE_SCHOFIELD = arrayOf(
            59.512f to -30.4f, 22.706f to 504.3f, 17.686f to 658.2f,
            15.057f to 692.2f, 11.472f to 873.1f, 11.711f to 587.7f,
        )
        val FEMALE_SCHOFIELD = arrayOf(
            58.317f to -31.1f, 20.315f to 485.9f, 13.384f to 692.6f,
            14.818f to 486.6f, 8.126f to 845.6f, 9.082f to 658.5f,
        )
    }
}
