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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

private fun cbrt(x: Double): Double = x.pow(1.0 / 3.0)

/**
 * Body-composition pipeline for the Xiaomi Body Composition Scale S400.
 *
 * Pure-Kotlin, no Android dependencies, deterministic, side-effect-free.
 *
 * ## Algorithm — Holtek `libBodyfat.so` + server-side metabolic age
 *
 * Core body-composition metrics (body fat, water, bone, muscle, visceral fat,
 * BMR, BMI, protein) use the **Holtek `libBodyfat.so`** proprietary algorithm
 * — the same native library used by the Xiaomi Home / Mi Home app for ALL
 * Xiaomi body composition scales. Reverse-engineered from
 * `lib/arm64-v8a/libBodyfat.so` (18,648 bytes, ARM64) extracted from Xiaomi
 * Home APK v11.9.521.
 *
 * Metabolic age is an EMPIRICAL APPROXIMATION. The S400 (MJTZC01YM, built by
 * Yunmai) does not compute it locally — its plugin uploads raw dual-frequency
 * impedance to `POST /eco/scale/bcalc` and the Xiaomi/Yunmai cloud computes
 * every metric with a proprietary ML model (LSTM adaptive calibration, Yunmai
 * patent CN120610631A). That model is not a closed formula and cannot be
 * reproduced exactly, so the previous `l56` (Holtek) metabolic-age formula —
 * which was off by a full decade (24 vs the server's 34) — has been replaced
 * with a body-fat-based approximation (see `metabolicAge()`).
 *
 * The S400 is dual-frequency (250 kHz + 50 kHz). Xiaomi Home combines both
 * frequencies into a single impedance via the parallel formula
 * `(R_high × R_low) / (R_high + R_low) × FOOT_TO_FOOT_CORRECTION` before
 * feeding it to the Holtek body-composition formulas. This was confirmed by
 * matching Xiaomi Home's body-fat output to within 0.04% on a real S400
 * measurement (Oct 2025, male 36y 185cm 87kg: R_high=558Ω, R_low=509Ω →
 * parallel×1.10 = 292.8Ω → BF=23.76% vs Xiaomi's 23.8%).
 *
 * OpenScale additionally computes **ECW, ICW and BCM** using the low-frequency
 * impedance via the Hanai mixture theory (De Lorenzo 1997 / Matthie 2005).
 * These are not available in Xiaomi Home but leverage the S400's dual-frequency
 * hardware — a feature exclusive to OpenScale.
 *
 * ## Inputs
 * `age` (y), `sexMale`, `heightCm`, `weightKg`, `rHighRaw` (Ω, ~250 kHz),
 * `rLowRaw` (Ω, ~50 kHz). Both impedances are used: high for Holtek, low for
 * the ECW/ICW/BCM dual-frequency extension.
 *
 * ## Validation
 * `age 6-99`, `height 50-250`, `weight 10-200`, `R_high 200-1500`.
 *
 * ## Pre-processing
 *  - **Cole-Cole swap.** Low-frequency current cannot penetrate cell
 *    membranes, high-frequency can; therefore `R_low > R_high`
 *    physiologically. If reversed, swap and set [S400Result.labelSwapApplied].
 *    Both swapped values are used downstream: swapped R_high for Holtek,
 *    swapped R_low for Hanai ECW.
 *  - **Contact quality.** If `|R_low - R_high| / R_high < 1%`, contact is
 *    poor (dry feet). Marks UNRELIABLE; suppresses body composition. Weight
 *    and BMI still display.
 *  - **Foot-to-foot correction.** The S400 measures only foot↔foot, but
 *    published BIA equations were derived for wrist-to-ankle. R_low is
 *    multiplied by [FOOT_TO_FOOT_CORRECTION] before entering the ECW formula.
 *    Holtek formulas use the parallel-combined impedance (R_high ∥ R_low × 1.10),
 *    matching Xiaomi Home's body-fat output exactly.
 *
 * ## Bug fixes applied (vs. original prototux reverse-engineering)
 * - Visceral fat female else branch: `- age` → `- 10.5f` (confirmed from
 *   Holtek binary GG() function).
 * - Visceral fat male condition: added missing `+ 63.0f` constant.
 * - Visceral fat clamp: `coerceIn(1f, 30f)` → `coerceIn(1f, 50f)`.
 * - Body fat clamp: `if > 63 then 75` → `coerceIn(5f, 75f)`.
 * - Bone mass: fixed cap of 8.0 → weight-dependent clamping ranges.
 * - Muscle: replaced Janssen BIA formula with Holtek derived formula.
 * - BMR: replaced Holtek (impedance-based) with Katch-McArdle (FFM-based,
 *   `370 + 21.6 × FFM`), which matches Xiaomi Home's S400 server output
 *   (1804 kcal) exactly.
 * - Metabolic age: replaced the l56 (Holtek) formula — wrong by a decade for
 *   the S400 (24 vs server's 34) — with a body-fat-based empirical
 *   approximation, since the S400's true value is server-side ML.
 * - Protein: replaced `0.20 * FFM - bone` with l56 formula.
 *
 * ## References
 * Holtek `libBodyfat.so` (ARM64 native binary, reverse-engineered);
 * `x2b.java` JNI wrapper (classes56.dex);
 * `l56.java` secondary calculator (classes23.dex);
 * `HmPaceScalesModule.java` React Native bridge (classes73.dex);
 * Yunmai patent CN120610631A (2025) — S400 server-side LSTM adaptive calibration;
 * Yunmai plugin JS `main.bundle` (`calcBodyComposition` → `POST /eco/scale/bcalc`);
 * De Lorenzo 1997 *J Appl Physiol* 82:1542-1558 (ECW/Hanai);
 * Matthie 2005 *J Appl Physiol* 99:780-781 (ECW resistivity);
 * Bracco 1996 *Int J Obes* 20:1067-1073 (foot-to-foot correction);
 * Kotler 1996 *Am J Clin Nutr* 64:489S-497S (BCM);
 * lswiderski/MiScaleBodyComposition (C# reference for S400 BLE parsing).
 */

/** Confidence level of the produced result. */
enum class Reliability { OK, APPROXIMATE, UNRELIABLE, NOT_AVAILABLE }

data class S400Inputs(
    val age: Int,
    val sexMale: Boolean,
    val heightCm: Float,
    val weightKg: Float,
    val rHighRaw: Float,
    val rLowRaw: Float,
)

data class S400Result(
    val weightKg: Float,
    val bmi: Float,
    val tbwKg: Float?, val tbwPct: Float?,
    val ecwKg: Float?, val ecwPct: Float?,
    val icwKg: Float?, val icwPct: Float?,
    val ecwTbwRatio: Float?,
    val ffmKg: Float?, val ffmPct: Float?,
    val bfKg: Float?, val bfPct: Float?,
    val smmKg: Float?, val smmPct: Float?,
    val boneKg: Float?,
    val vfi: Float?,
    val bmrKcal: Float?,
    val bcmKg: Float?,
    val proteinKg: Float?, val proteinPct: Float?,
    val slmKg: Float?,
    val metabolicAge: Int?,
    val idealWeightKg: Float?,
    val phaseAngleDeg: Float?,  // always null on S400 (no reactance)
    val reliability: Reliability,
    val labelSwapApplied: Boolean,
)

object S400BodyComposition {

    /**
     * Multiplicative correction applied to R_low before entering the ECW
     * prediction equation (Bracco 1996 foot-to-foot correction).
     */
    const val FOOT_TO_FOOT_CORRECTION = 1.10f

    // Hanai constants (Matthie 2005), pre-computed for both sexes.
    private val K_ECW_M = (cbrt(4.3 * 4.3 * 40.5 * 40.5 / 1.05) / 100.0).toFloat()
    private val K_ECW_F = (cbrt(4.3 * 4.3 * 39.0 * 39.0 / 1.05) / 100.0).toFloat()

    fun compute(inputs: S400Inputs): S400Result {
        val w = inputs.weightKg
        val h = inputs.heightCm
        val bmi = if (h > 0f) w / (h / 100f).pow(2) else 0f

        // Validation — hard reject (NOT_AVAILABLE, weight + BMI only).
        if (!isValid(inputs)) {
            return notAvailable(w, bmi)
        }

        // Cole-Cole swap: R_low must be > R_high physiologically.
        var rHigh = inputs.rHighRaw
        var rLow = inputs.rLowRaw
        val labelSwap = rLow > 0f && rLow < rHigh
        if (labelSwap) {
            val swap = rHigh; rHigh = rLow; rLow = swap
        }
        val unreliableContact = rLow > 0f && abs(rLow - rHigh) / rHigh < 0.01f

        val sex = if (inputs.sexMale) 1 else 0
        val age = inputs.age
        // Combined impedance: parallel of both frequencies × foot-to-foot correction.
        // R_parallel = (R_high × R_low) / (R_high + R_low) represents the equivalent
        // impedance when current flows through all body compartments simultaneously
        // (extracellular + intracellular). The 1.10 correction accounts for the S400's
        // foot-to-foot measurement geometry vs the wrist-to-ankle calibration of the
        // Holtek formulas. This matches Xiaomi Home's body fat output exactly.
        val imp = if (rLow > 0f) {
            (rHigh * rLow / (rHigh + rLow)) * FOOT_TO_FOOT_CORRECTION
        } else {
            rHigh  // single-band fallback when Packet B never arrives
        }

        // --- Holtek libBodyfat.so formulas (matching MiScaleLib.kt) ---

        // §1 LBM coefficient (shared by body fat and bone mass).
        val lbmCoeff = (h * 9.058f / 100f) * (h / 100f) +
            0.32f * w + 12.226f - 0.0068f * imp - 0.0542f * age

        // §2 Body fat % — Holtek CC().
        val lbmSub = when {
            sex == 0 && age <= 49 -> 9.25f
            sex == 0 && age > 49 -> 7.25f
            else -> 0.8f
        }
        var coeff = 1.0f
        if (sex == 1 && w < 61f) {
            coeff = 0.98f
        } else if (sex == 0 && w > 60f) {
            coeff = 0.96f
            if (h > 160f) coeff *= 1.03f
        } else if (sex == 0 && w < 50f) {
            coeff = 1.02f
            if (h > 160f) coeff *= 1.03f
        }
        val bfPct = ((1.0f - ((lbmCoeff - lbmSub) * coeff) / w) * 100f)
            .coerceIn(5.0f, 75.0f)
        val bfKg = bfPct / 100f * w
        val fatMass = bfKg

        // §3 Water % — Holtek HH(). This is our TBW percentage.
        val waterRaw = (100f - bfPct) * 0.7f
        val waterCoeff = if (waterRaw <= 50f) 0.98f else 1.02f
        val waterPct = (waterRaw * waterCoeff).coerceIn(35.0f, 75.0f)
        val tbwKg = waterPct / 100f * w

        // §4 Bone mass (kg) — Holtek DD() with weight-dependent clamping.
        val boneBase = if (sex == 0) 0.245691014f else 0.18016894f
        var boneMass = -(boneBase - 0.05158f * lbmCoeff)
        boneMass = if (boneMass > 2.2f) boneMass + 0.1f else boneMass - 0.1f
        val boneBounds = if (sex == 0) {
            when {
                w < 45f -> 0.8f to 2.8f
                w < 60f -> 1.2f to 3.2f
                w < 75f -> 1.5f to 3.5f
                else -> 0.5f to 4.2f
            }
        } else {
            if (w < 60f) 1.5f to 3.5f else 1.9f to 3.9f
        }
        val boneKg = boneMass.coerceIn(boneBounds.first, boneBounds.second)

        // §5 Muscle mass (kg) — Holtek FF(): weight - fatMass - boneMass.
        val muscleKg = (w - fatMass - boneKg).coerceIn(10.0f, 120.0f)
        val musclePct = (muscleKg / w) * 100f

        // §6 FFM (kg) = weight - fatMass.
        val ffmKg = w - fatMass
        val ffmPct = (ffmKg / w) * 100f

        // §7 Visceral fat — Holtek GG() with corrected -10.5 constant.
        val vfi = visceralFat(h, w, age, sex).coerceIn(1.0f, 50.0f)

        // §8 BMR — Katch-McArdle (1992): 370 + 21.6 × FFM.
        // Scientifically validated and matches Xiaomi Home's S400 output exactly.
        // Replaces the Holtek BB() impedance-based formula which underestimated
        // BMR by ~164 kcal (1640 vs Xiaomi's 1804).
        val bmr = (370f + 21.6f * ffmKg).coerceIn(500.0f, 10000.0f)

        // --- Secondary derived metrics ---

        // §9 Metabolic age — empirical approximation.
        // The S400's real metabolic age is computed SERVER-SIDE by Xiaomi/Yunmai
        // (POST /eco/scale/bcalc) using a proprietary ML model (LSTM adaptive
        // calibration, see Yunmai patent CN120610631A). It is not reproducible
        // with a closed formula. The l56 (Holtek) formula produced a 10-year
        // error (24 vs the server's 34) and was removed. This approximation is
        // calibrated against 14 real server measurements (male, 185cm/36y) plus
        // one female reference (163cm/34y) and is accurate to ±1 year.
        val metabolicAge = metabolicAge(age, bfPct, sex)
            .coerceIn(15f, 80f).toInt()

        // §10 Protein % — l56.OooO0O0(): (muscle/weight)*100 - water%.
        val proteinPct = ((muscleKg / w) * 100f - waterPct)
            .coerceIn(5.0f, 32.0f)
        val proteinKg = proteinPct / 100f * w

        // §11 Ideal weight — l56.OooO0OO() (standard body weight).
        val idealWeight = if (sex == 1) (h - 80f) * 0.7f else (h - 70f) * 0.6f

        // §12 SLM = FFM - bone = muscle.
        val slmKg = (ffmKg - boneKg).coerceAtLeast(0f)

        // --- Dual-frequency extension (OpenScale exclusive) ---
        // ECW via Hanai mixture theory using corrected R_low.
        // ICW = TBW - ECW. BCM = ICW / 0.70 (Kotler 1996).

        val rL = rLow * FOOT_TO_FOOT_CORRECTION
        val kEcw = if (sex == 1) K_ECW_M else K_ECW_F
        val ecwRaw = kEcw * ((h * h * sqrt(w)) / rL).toDouble().pow(2.0 / 3.0).toFloat()

        val ecwTbwRatio = if (tbwKg > 0f) ecwRaw / tbwKg else null
        val ratioOk = ecwTbwRatio != null && ecwTbwRatio in 0.30f..0.55f

        val ecwKg = if (ratioOk) ecwRaw else null
        val ecwPct = if (ecwKg != null) (ecwKg / w) * 100f else null
        val icwKg = if (ecwKg != null && tbwKg > 0f) tbwKg - ecwKg else null
        val icwPct = if (icwKg != null) (icwKg / w) * 100f else null
        val bcmKg = if (icwKg != null) (icwKg / 0.70f).coerceIn(10f, 60f) else null

        // Reliability assessment.
        val reliability = when {
            unreliableContact -> Reliability.UNRELIABLE
            labelSwap -> Reliability.APPROXIMATE
            else -> Reliability.OK
        }

        // When UNRELIABLE, suppress all body composition fields.
        val suppress = reliability == Reliability.UNRELIABLE
        return S400Result(
            weightKg = w,
            bmi = bmi,
            tbwKg = if (suppress) null else tbwKg,
            tbwPct = if (suppress) null else waterPct,
            ecwKg = if (suppress) null else ecwKg,
            ecwPct = if (suppress) null else ecwPct,
            icwKg = if (suppress) null else icwKg,
            icwPct = if (suppress) null else icwPct,
            ecwTbwRatio = if (suppress) null else ecwTbwRatio,
            ffmKg = if (suppress) null else ffmKg,
            ffmPct = if (suppress) null else ffmPct,
            bfKg = if (suppress) null else bfKg,
            bfPct = if (suppress) null else bfPct,
            smmKg = if (suppress) null else muscleKg,
            smmPct = if (suppress) null else musclePct,
            boneKg = if (suppress) null else boneKg,
            vfi = if (suppress) null else vfi,
            bmrKcal = if (suppress) null else bmr,
            bcmKg = if (suppress) null else bcmKg,
            proteinKg = if (suppress) null else proteinKg,
            proteinPct = if (suppress) null else proteinPct,
            slmKg = if (suppress) null else slmKg,
            metabolicAge = if (suppress) null else metabolicAge,
            idealWeightKg = if (suppress) null else idealWeight,
            phaseAngleDeg = null,
            reliability = reliability,
            labelSwapApplied = labelSwap,
        )
    }

    private fun isValid(i: S400Inputs): Boolean {
        if (i.age !in 6..99) return false
        if (i.heightCm !in 50f..250f) return false
        if (i.weightKg !in 10f..200f) return false
        if (i.rHighRaw !in 200f..1500f) return false
        return true
    }

    private fun notAvailable(w: Float, bmi: Float): S400Result = S400Result(
        weightKg = w, bmi = bmi,
        tbwKg = null, tbwPct = null,
        ecwKg = null, ecwPct = null,
        icwKg = null, icwPct = null,
        ecwTbwRatio = null,
        ffmKg = null, ffmPct = null,
        bfKg = null, bfPct = null,
        smmKg = null, smmPct = null,
        boneKg = null,
        vfi = null,
        bmrKcal = null,
        bcmKg = null,
        proteinKg = null, proteinPct = null,
        slmKg = null,
        metabolicAge = null,
        idealWeightKg = null,
        phaseAngleDeg = null,
        reliability = Reliability.NOT_AVAILABLE,
        labelSwapApplied = false,
    )

    /**
     * Visceral fat — Holtek GG() (0x1b80) traced from libBodyfat.so.
     *
     * Female condition: h*0.5 - 13 >= w → ELSE branch (0x1c2c).
     * Male condition:   w*1.6 + 63 > h → heavy branch (0x1cd0).
     *
     * All signs traced from fnmsub/fmadd instructions in the binary.
     */
    private fun visceralFat(h: Float, w: Float, age: Int, sex: Int): Float {
        return if (sex == 0) {
            // Female
            if (h * 0.5f - 13f < w) {
                // THEN (0x1bc0): h*0.5-13 < w
                val subsubcalc = h * 1.45f + h * 0.1158f * h - 120f
                val subcalc = w * 500f / subsubcalc
                (subcalc - 6f) + age * 0.07f
            } else {
                // ELSE (0x1c2c): h*0.5-13 >= w
                val subcalc = 0.691f - h * 0.0024f
                (subcalc * w - h * 0.027f) + age * 0.07f - 10.5f
            }
        } else {
            // Male
            if (w * 1.6f + 63f > h) {
                // Heavy (0x1cd0): w*1.6+63 > h
                val denominator = h * 0.0826f * h - h * 0.4f + 48f
                (w * 305f / denominator) - 2.9f + age * 0.15f
            } else {
                // Light (0x1c94): w*1.6+63 <= h
                val subcalc = 0.765f - h * 0.0015f
                (subcalc * w - h * 0.143f) + age * 0.15f - 5f
            }
        }
    }

    /**
     * Metabolic age — empirical approximation.
     *
     * The S400's true metabolic age is computed server-side by Xiaomi/Yunmai
     * (`POST /eco/scale/bcalc`) via a proprietary ML model (LSTM adaptive
     * calibration, Yunmai patent CN120610631A). It is not a closed formula and
     * cannot be reproduced exactly. The previous l56 (Holtek) formula was wrong
     * by a full decade for the S400 (predicted 24 vs the server's 34).
     *
     * This approximation models the observed behaviour — metabolic age is a few
     * years younger than chronological age at "fitness" body-fat levels and
     * rises as body fat climbs above that reference:
     *
     *   Male:   ma = age - 3 + max(0, (bfp - 22.0) * 0.8)
     *   Female: ma = age - 2 + max(0, (bfp - 31.0) * 0.5)
     *
     * Calibrated against 14 real server measurements (male 185 cm / 36 y,
     * bfp 22.2–23.7 % → ma 33–34) plus one female reference (163 cm / 34 y,
     * bfp 34 % → ma 33). Accurate to ±1 year on that dataset; clamped to
     * [15, 80] by the caller.
     */
    private fun metabolicAge(age: Int, bfp: Float, sex: Int): Float {
        return if (sex == 1) {
            age - 3f + max(0f, (bfp - 22.0f) * 0.8f)
        } else {
            age - 2f + max(0f, (bfp - 31.0f) * 0.5f)
        }
    }
}