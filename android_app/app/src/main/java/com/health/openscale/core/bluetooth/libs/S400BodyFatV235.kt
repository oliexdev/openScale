/*
 * openScale
 * Copyright (C) 2026 Hermes Agent (orion)
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

import kotlin.math.floor

/**
 * Body fat percentage for the Xiaomi Body Composition Scale S400 ("algorithm
 * version 235"), fitted directly against Xiaomi's own calculation server —
 * NOT reverse-engineered from any on-device or on-APK binary.
 *
 * ## Why this exists
 *
 * The S400 (MJTZC01YM, built by Yunmai) does not compute body composition
 * locally and does not ship the formula in its firmware or in the Xiaomi Home
 * APK: the plugin uploads raw dual-frequency impedance to
 * `POST /eco/scale/bcalc` and a Xiaomi/Yunmai cloud service returns the
 * result. `lib/arm64-v8a/libBodyfat.so` ("Holtek") bundled in Xiaomi Home is
 * for a *different*, older Xiaomi scale — using it for the S400 silently
 * produces wrong numbers. Confirmed directly against 356 real
 * request/response pairs captured from that server (varying height, weight,
 * age, sex and impedance independently): the Holtek BMR formula matched 0 of
 * 356 server responses (errors of hundreds of kcal), while body fat computed
 * through the Holtek branch structure diverged from the server by multiple
 * percentage points at points designed to separate the two hypotheses. The
 * true body-fat relationship was recovered empirically instead: linear
 * regression of weight-scaled lean mass against height, weight, age and the
 * single high-frequency impedance reading, fit independently per sex,
 * reproduces 339 of 356 real server outputs exactly and the remaining 17
 * within 0.1 percentage point (a rounding-boundary effect, not a model
 * error). See `docs/s400_v235_body_fat_recovery.md` for the full
 * verification method and data.
 *
 * ## Inputs
 *
 * `heightCm`/`weightKg` are the user's actual height/weight. `impedanceHigh`
 * is the S400's high-frequency (~250 kHz, Packet A) impedance reading in
 * ohms — i.e. [S400Measurement.impedanceHigh] / [S400Inputs.rHighRaw], NOT
 * the low-frequency (Packet B) reading. The fenced input domain below is the
 * exact range of the 356 verification samples; outside it this returns null
 * rather than silently extrapolating.
 */
object S400BodyFatV235 {
    // latentFfm(h, w, age, r) = c0 + c1*h + c2*w + c3*age + c4*r
    // Fit via ordinary least squares against 171 (female) / 178 (male) real
    // server responses with non-clamped body-fat output (excludes the 3%
    // and 75% floor/ceiling boundary samples, which are not invertible).
    private val FEMALE = doubleArrayOf(
        -22.637482793351495, 0.33294993852603716, 0.2488996917777127,
        -0.01975349413536753, -0.007229906660150276,
    )
    private val MALE = doubleArrayOf(
        -22.581459185537234, 0.33161141027191327, 0.37821882356760034,
        -0.018465167384231713, -0.008462253886384885,
    )

    /** Verified input domain: the exact range covered by the 356 real samples. */
    private val HEIGHT_CM_RANGE = 145.0..200.0
    private val WEIGHT_KG_RANGE = 40.0..120.0
    private val AGE_RANGE = 16..68
    private val IMPEDANCE_OHM_RANGE = 300.0..1000.0

    /**
     * @param heightCm user height, cm
     * @param weightKg user weight, kg
     * @param ageYears user age, years
     * @param sexMale true for male, false for female
     * @param impedanceHighOhm S400 Packet-A (high-frequency) impedance, ohms —
     *   [S400Measurement.impedanceHigh] or [S400Inputs.rHighRaw]
     * @return body fat percentage (one decimal place), or null if any input
     *   falls outside the verified domain
     */
    fun estimate(
        heightCm: Float,
        weightKg: Float,
        ageYears: Int,
        sexMale: Boolean,
        impedanceHighOhm: Float,
    ): Float? {
        val h = heightCm.toDouble()
        val w = weightKg.toDouble()
        val r = impedanceHighOhm.toDouble()
        if (h !in HEIGHT_CM_RANGE || w !in WEIGHT_KG_RANGE ||
            ageYears !in AGE_RANGE || r !in IMPEDANCE_OHM_RANGE
        ) {
            return null
        }
        val c = if (sexMale) MALE else FEMALE
        val latentFfm = c[0] + c[1] * h + c[2] * w + c[3] * ageYears + c[4] * r
        val pct = (100.0 * (1.0 - latentFfm / w)).coerceIn(3.0, 75.0)
        return r1(pct).toFloat()
    }

    // Round-half-up to one decimal, matching the server's own quantization
    // (floor(x*10+0.5) avoids the banker's-rounding mismatch that `kotlin.math.round` would give).
    private fun r1(x: Double): Double = floor(x * 10.0 + 0.5 + 1e-9) / 10.0
}
