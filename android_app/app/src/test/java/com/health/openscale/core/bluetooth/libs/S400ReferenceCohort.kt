/*
 * openScale
 * Copyright (C) 2026 Dany Mestas
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

import kotlin.math.sqrt

/**
 * Real S400 weighings contributed anonymously to
 * https://github.com/dckiller51/bodymiscale/issues/349, each with the body fat
 * and fat-free mass the Mi app reported for the same weighing. The values are
 * factual measurements reproduced from that public thread and credited to its
 * contributors; the copyright notice above covers this file's code, not them.
 * Contributors are identified only by sex and age, as in the thread.
 *
 * The app values are Xiaomi's estimate, not a measurement standard: they serve
 * as an external comparison, and nothing in [S400BodyComposition] is fitted to
 * them. In that thread the Home Assistant entity `impedance_low` carries the
 * numerically smaller magnitude, which is physically the 250 kHz band, so the
 * columns below are mapped by magnitude: [r50] is the larger value.
 */
object S400ReferenceCohort {

    data class Subject(
        val label: String,
        val sexMale: Boolean,
        val age: Int,
        val heightCm: Float,
        val weightKg: Float,
        val r50: Float,
        val r250: Float,
        val appBfPct: Float,
        val appFfmKg: Float,
    ) {
        val inputs get() = S400Inputs(age, sexMale, heightCm, weightKg, rHighRaw = r250, rLowRaw = r50)
    }

    val subjects = listOf(
        Subject("M62", true, 62, 170f, 76.9f, 437.0f, 387.0f, 24.6f, 58.0f),
        Subject("M56", true, 56, 170f, 67.9f, 509.1f, 457.8f, 20.3f, 54.1f),
        Subject("M28", true, 28, 187f, 132.5f, 454.6f, 414.9f, 35.7f, 85.2f),
        Subject("M48", true, 48, 183f, 79.1f, 458.6f, 408.5f, 20.0f, 63.3f),
        Subject("M26", true, 26, 173f, 91.1f, 505.7f, 457.5f, 29.2f, 64.5f),
        Subject("M50", true, 50, 180f, 83.3f, 451.6f, 407.9f, 23.3f, 63.9f),
        Subject("M38", true, 38, 172f, 71.9f, 460.3f, 414.7f, 19.7f, 57.7f),
        Subject("M30", true, 30, 171f, 56.0f, 568.2f, 485.8f, 10.8f, 50.0f),
        Subject("F27", false, 27, 164f, 63.1f, 513.8f, 454.9f, 30.8f, 43.7f),
        Subject("M27", true, 27, 178f, 88.0f, 570.0f, 510.6f, 26.8f, 64.4f),
        Subject("F26", false, 26, 163f, 63.6f, 630.0f, 571.5f, 33.3f, 42.4f),
    )

    /**
     * Four weighings of the M56 subject on different days, from the same
     * thread. The app reported 20.1, 20.2, 20.1 and 20.3 % body fat.
     */
    val repeats = listOf(
        Subject("M56-a", true, 56, 170f, 67.6f, 514.2f, 461.5f, 20.1f, Float.NaN),
        Subject("M56-b", true, 56, 170f, 67.7f, 512.6f, 459.7f, 20.2f, Float.NaN),
        Subject("M56-c", true, 56, 170f, 67.7f, 506.3f, 455.8f, 20.1f, Float.NaN),
        subjects[1],
    )

    fun mean(xs: List<Double>) = xs.sum() / xs.size

    fun sd(xs: List<Double>): Double {
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    fun rmse(xs: List<Double>) = sqrt(xs.sumOf { it * it } / xs.size)

    fun pearson(xs: List<Double>, ys: List<Double>): Double {
        val mx = mean(xs)
        val my = mean(ys)
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        val syy = ys.sumOf { (it - my) * (it - my) }
        return sxy / sqrt(sxx * syy)
    }
}
