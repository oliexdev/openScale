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
import kotlin.math.pow
import kotlin.math.sqrt

private fun cbrt(x: Double): Double = x.pow(1.0 / 3.0)

/**
 * Body-composition pipeline for the Xiaomi Body Composition Scale S400.
 *
 * Pure-Kotlin, no Android dependencies, deterministic, side-effect-free.
 *
 * ## Purpose
 * Derive body-composition outputs from the raw values the scale transmits over
 * BLE, without any network call. Pipeline is literature-grounded; no
 * proprietary calibration is used.
 *
 * ## Inputs (six numbers per weighing)
 * `age` (y), `sexMale`, `heightCm`, `weightKg`, `rHighRaw` (Ω, ~250 kHz),
 * `rLowRaw` (Ω, ~50 kHz). Heart rate, if present, is **not** an input to any
 * body-composition equation — pass through to the UI unmodified.
 *
 * ## Validation (§1.1) — reject entire computation
 * `age 18-120` (Janssen/Cunningham not validated <18), `height 100-230`,
 * `weight 20-250`, `R_high/R_low 200-1500`, `BMI 12-60`. These are not hard
 * physical limits; they are the limits beyond which published equations have
 * not been validated. Returning numbers outside them is worse than no number.
 *
 * ## Pre-processing
 *  - **§2.1 Cole-Cole sanity check.** Low-frequency current cannot penetrate
 *    cell membranes, high-frequency can; therefore `R_low > R_high`
 *    physiologically. If reversed, swap and set [S400Result.labelSwapApplied].
 *    If `|R_low - R_high| / R_high < 1 %`, contact is poor (dry feet, user
 *    stepped off mid-measurement); mark UNRELIABLE and suppress per-compartment
 *    fields. Weight and BMI still display.
 *  - **§2.2 Foot-to-foot correction.** The S400 measures only the lower body
 *    (foot↔foot), but every published BIA equation was derived for
 *    wrist-to-ankle BIA. Foot-to-foot R is ~10 % lower because the path omits
 *    the arm segment (Organ 1994, Bracco 1996, Demura 2004). Both R values are
 *    multiplied by [FOOT_TO_FOOT_CORRECTION] before entering any prediction
 *    equation. Raw (un-corrected) values are kept for the §3.7 empirical bone
 *    formula and §3.8 VFI, which were fit against raw foot-to-foot data and
 *    would double-correct.
 *
 * ## Computation order (§3) — sources
 *  - §3.1 TBW — Sun 2003 race-combined, sex-specific
 *  - §3.2 ECW — De Lorenzo 1997 / Matthie 2005 Hanai mixture theory
 *  - §3.3 ICW = TBW − ECW
 *  - §3.4 FFM = TBW / 0.732 — Pace & Rathbun 1945 hydration constant
 *  - §3.5 BF = W − FFM
 *  - §3.6 SMM — Janssen 2000 (MRI-validated, single-frequency 50 kHz; uses
 *    corrected R_H even though nominally a dual-freq model)
 *  - §3.7 Bone — see [BoneFormula]
 *  - §3.8 VFI — empirical anthropometric regression (no impedance input)
 *  - §3.9 BMR — see [BmrFormula]; Mifflin-St Jeor fallback when FFM suppressed
 *  - §3.10 BCM = ICW / 0.70 — Kotler 1996
 *  - §3.11 Phase angle — **not derivable** on S400 (no reactance from
 *    magnitude-only impedance); always null. Do not invent a default like 5°.
 *
 * ## Suppression policy
 *  - TBW out of `[0.30·W, 0.75·W]` → suppress TBW + everything downstream
 *  - `ECW/TBW` outside `[0.30, 0.55]` → suppress ECW, ICW, BCM; TBW/FFM/BF/SMM
 *    still display (they depend only on TBW). Healthy reference: 0.36-0.40
 *    young adult, 0.38-0.42 older.
 *  - FFM/W outside `[0.30, 0.97]` → suppress FFM, BF, SMM
 *  - BF % outside [3, 60] (M) / [8, 70] (F) → suppress, flag (underlying TBW
 *    likely wrong)
 *  - UNRELIABLE contact → suppress all per-compartment fields; weight + BMI
 *    still display
 *
 * ## Bone + VFI caveats
 * **BIA does not measure bone.** Bone has high resistivity and contributes
 * negligibly to whole-body impedance; output is a regression on
 * weight/height/sex/age, not a measurement. **Label as "estimated" in UI** —
 * not DXA bone densitometry.
 *
 * **VFI cannot be derived from impedance.** Without a waist measurement, any
 * VFI is an anthropometric convention. **Label "approximate, no waist
 * measured" in UI.**
 *
 * ## Fallback policy (§6, partially implemented in caller)
 * When BIA computation is suppressed, still display something useful: BMI
 * unconditionally; Deurenberg 1991 `BF% = 1.20·BMI + 0.23·age − 10.8·sexM − 5.4`
 * for BF%; Heymsfield anthropometric for bone (needs no R); Mifflin-St Jeor
 * for BMR (already wired in this file); empirical anthropometric for VFI
 * (needs no R, already unconditional).
 *
 * ## Test vectors
 * §7.1-7.3 reference subjects and §7.4-7.5 edge cases (label swap, unreliable
 * contact) live in `S400BodyCompositionTest.kt`.
 *
 * ## Primary references
 * Sun 2003 *Am J Clin Nutr* 77:331-340 (TBW); De Lorenzo 1997
 * *J Appl Physiol* 82:1542-1558 (ECW); Matthie 2005 *J Appl Physiol*
 * 99:780-781 (ECW resistivity); Pace & Rathbun 1945 *J Biol Chem* 158:685-691
 * (FFM hydration); Janssen 2000 *J Appl Physiol* 89:465-471 (SMM); Bracco 1996
 * *Int J Obes* 20:1067-1073 (foot-to-foot correction); Cunningham 1991
 * *Am J Clin Nutr* 54:963-969 (BMR); Mifflin-St Jeor 1990 *Am J Clin Nutr*
 * 51:241-247 (BMR fallback); Kotler 1996 *Am J Clin Nutr* 64:489S-497S (BCM);
 * Heymsfield 2007 *Am J Clin Nutr* 86:82-91 (anthropometric bone);
 * Deurenberg 1991 *Br J Nutr* 65:105-114 (BMI-based BF% fallback);
 * Kyle 2004 *Clin Nutr* 23:1226-1243 / 1430-1453 (ESPEN BIA consensus).
 */

/**
 * §3.7. Pick one and **do not switch silently** — output stability across
 * weighings matters more than absolute accuracy.
 *  - [MI_LEGACY]: empirical impedance-based regression (uses RAW, uncorrected
 *    R_high). Matches the output range produced by the scale's companion
 *    apps; kept under this name for backward compatibility with persisted
 *    user preferences.
 *  - [HEYMSFIELD]: anthropometric (`0.041·W` M, `0.036·W` F), no impedance
 *    input. Best for clinical defensibility and works as the §6 fallback
 *    when impedance is unusable.
 */
enum class BoneFormula { MI_LEGACY, HEYMSFIELD }

/**
 * §3.9. Cun80 (`500 + 22·FFM`) runs ~5-8 % higher than Cun91
 * (`370 + 21.6·FFM`). Cun80 reproduces the BMR range users see in the
 * scale's companion apps; Cun91 is the literature-correct citation.
 * **Pick one and do not switch silently.**
 */
enum class BmrFormula { CUNNINGHAM_1991, CUNNINGHAM_1980 }

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
    val phaseAngleDeg: Float?,  // always null on S400 (no reactance)
    val reliability: Reliability,
    val labelSwapApplied: Boolean,
)

object S400BodyComposition {

    /**
     * §2.2 multiplicative correction applied to both R values before they enter
     * any prediction equation. Bracco 1996 default for mid-range adults.
     * Defensible literature range 1.00-1.18: athletic/lean closer to 1.05,
     * overweight closer to 1.15. Exposed as a parameter to [compute] so a
     * caller can override per user profile without recompiling.
     */
    const val FOOT_TO_FOOT_CORRECTION = 1.10f

    // Hanai constants (Matthie 2005), pre-computed for both sexes.
    private val K_ECW_M = (cbrt(4.3 * 4.3 * 40.5 * 40.5 / 1.05) / 100.0).toFloat()
    private val K_ECW_F = (cbrt(4.3 * 4.3 * 39.0 * 39.0 / 1.05) / 100.0).toFloat()

    fun compute(
        inputs: S400Inputs,
        boneFormula: BoneFormula = BoneFormula.MI_LEGACY,
        bmrFormula: BmrFormula = BmrFormula.CUNNINGHAM_1991,
        footToFootCorrection: Float = FOOT_TO_FOOT_CORRECTION,
    ): S400Result {
        val w = inputs.weightKg
        val h = inputs.heightCm
        val bmi = if (h > 0f) w / (h / 100f).pow(2) else 0f

        // §1.1 input validation — hard reject (NOT_AVAILABLE, weight + BMI only).
        if (!isWithinValidationRange(inputs, bmi)) {
            return notAvailable(w, bmi)
        }

        // §2.1 Cole-Cole sanity check.
        var rHigh = inputs.rHighRaw
        var rLow = inputs.rLowRaw
        val labelSwap = rLow < rHigh
        if (labelSwap) {
            val swap = rHigh; rHigh = rLow; rLow = swap
        }
        val rHighRawAfterSwap = rHigh  // §3.7 Option A needs RAW (un-corrected) R_high.
        val unreliableContact = abs(rLow - rHigh) / rHigh < 0.01f

        // §2.2 foot-to-foot correction (applied to both R values for the main pipeline).
        val rH = rHigh * footToFootCorrection
        val rL = rLow * footToFootCorrection

        // §3.1 TBW (Sun 2003, race-combined, sex-specific).
        val sexM = if (inputs.sexMale) 1f else 0f
        val tbwRaw = if (inputs.sexMale) {
            1.20f + 0.45f * (h * h / rH) + 0.18f * w
        } else {
            3.75f + 0.45f * (h * h / rH) + 0.11f * w
        }
        val tbwOk = tbwRaw in (0.30f * w)..(0.75f * w)
        val tbw = if (tbwOk) tbwRaw else null

        // §3.2 ECW (Hanai mixture, sex-specific resistivity).
        val kEcw = if (inputs.sexMale) K_ECW_M else K_ECW_F
        val ecwRaw = kEcw * ((h * h * sqrt(w)) / rL).toDouble().pow(2.0 / 3.0).toFloat()

        // §3.3 ICW = TBW − ECW; suppress per-compartment outputs on bad ratio.
        val ecwTbwRatio = if (tbw != null && tbw > 0f) ecwRaw / tbw else null
        val ratioOk = ecwTbwRatio != null && ecwTbwRatio in 0.30f..0.55f
        val ecw = if (tbw != null && ratioOk) ecwRaw else null
        val icw = if (tbw != null && ecw != null) tbw - ecw else null

        // §3.4 FFM = TBW / 0.732 (Pace & Rathbun 1945).
        val ffmRaw = if (tbw != null) tbw / 0.732f else null
        val ffmOk = ffmRaw != null && ffmRaw / w in 0.30f..0.97f
        val ffm = if (ffmOk) ffmRaw else null

        // §3.5 Body fat.
        val bf = if (ffm != null) w - ffm else null
        val bfPctRaw = if (bf != null) (bf / w) * 100f else null
        val bfRange = if (inputs.sexMale) 3f..60f else 8f..70f
        val bfPctOk = bfPctRaw != null && bfPctRaw in bfRange
        val bfPct = if (bfPctOk) bfPctRaw else null
        val bfKg = if (bfPct != null) bf else null

        // §3.6 SMM (Janssen 2000), uses corrected R_H.
        val smmRaw = 0.401f * (h * h / rH) + 3.825f * sexM - 0.071f * inputs.age + 5.102f
        val smm = smmRaw.coerceIn(8f, 75f)

        // §3.7 Bone mineral mass — two options.
        val bone = when (boneFormula) {
            BoneFormula.MI_LEGACY -> empiricalBone(h, w, inputs.age, rHighRawAfterSwap, inputs.sexMale)
            BoneFormula.HEYMSFIELD -> heymsfieldBone(w, inputs.sexMale)
        }.coerceIn(1.0f, 6.0f)

        // §3.8 VFI (empirical anthropometric regression, uses RAW height + weight only).
        val vfiRaw = empiricalVfi(h, w, inputs.age, inputs.sexMale)
        val vfi = vfiRaw.coerceIn(1f, 30f)

        // §3.9 BMR.
        val bmrFromFfm = if (ffm != null) {
            when (bmrFormula) {
                BmrFormula.CUNNINGHAM_1991 -> 370f + 21.6f * ffm
                BmrFormula.CUNNINGHAM_1980 -> 500f + 22.0f * ffm
            }
        } else {
            // Mifflin-St Jeor fallback.
            10f * w + 6.25f * h - 5f * inputs.age + if (inputs.sexMale) 5f else -161f
        }
        val bmr = bmrFromFfm.coerceIn(800f, 4000f)

        // §3.10 BCM = ICW / 0.70 (Kotler 1996). Suppressed when ICW suppressed.
        val bcm = if (icw != null) (icw / 0.70f).coerceIn(10f, 60f) else null

        // Protein + SLM derivations (spec is silent; cheap approximations).
        val proteinKg = if (ffm != null) (0.20f * ffm - bone).coerceAtLeast(0f) else null
        val proteinPct = if (proteinKg != null) (proteinKg / w) * 100f else null
        val slmKg = if (ffm != null) (ffm - bone).coerceAtLeast(0f) else null

        // Overall reliability.
        val reliability = when {
            unreliableContact -> Reliability.UNRELIABLE
            !tbwOk || !ffmOk || !bfPctOk -> Reliability.APPROXIMATE
            else -> Reliability.OK
        }

        // When marked UNRELIABLE, suppress per-compartment fields per §2.1.
        val suppress = reliability == Reliability.UNRELIABLE
        return S400Result(
            weightKg = w,
            bmi = bmi,
            tbwKg = if (suppress) null else tbw,
            tbwPct = if (suppress || tbw == null) null else (tbw / w) * 100f,
            ecwKg = if (suppress) null else ecw,
            ecwPct = if (suppress || ecw == null) null else (ecw / w) * 100f,
            icwKg = if (suppress) null else icw,
            icwPct = if (suppress || icw == null) null else (icw / w) * 100f,
            ecwTbwRatio = if (suppress) null else ecwTbwRatio,
            ffmKg = if (suppress) null else ffm,
            ffmPct = if (suppress || ffm == null) null else (ffm / w) * 100f,
            bfKg = if (suppress) null else bfKg,
            bfPct = if (suppress) null else bfPct,
            smmKg = if (suppress) null else smm,
            smmPct = if (suppress) null else (smm / w) * 100f,
            boneKg = bone,
            vfi = vfi,
            bmrKcal = if (suppress) null else bmr,
            bcmKg = if (suppress) null else bcm,
            proteinKg = if (suppress) null else proteinKg,
            proteinPct = if (suppress) null else proteinPct,
            slmKg = if (suppress) null else slmKg,
            phaseAngleDeg = null,
            reliability = reliability,
            labelSwapApplied = labelSwap,
        )
    }

    private fun isWithinValidationRange(i: S400Inputs, bmi: Float): Boolean {
        if (i.age !in 18..120) return false
        if (i.heightCm !in 100f..230f) return false
        if (i.weightKg !in 20f..250f) return false
        if (i.rHighRaw !in 200f..1500f) return false
        if (i.rLowRaw !in 200f..1500f) return false
        if (bmi !in 12f..60f) return false
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
        phaseAngleDeg = null,
        reliability = Reliability.NOT_AVAILABLE,
        labelSwapApplied = false,
    )

    private fun empiricalBone(h: Float, w: Float, age: Int, rHighRaw: Float, sexMale: Boolean): Float {
        val lbmCoeff = (h * 9.058f / 100f) * (h / 100f) + 0.32f * w + 12.226f -
            0.0068f * rHighRaw - 0.0542f * age
        val base = if (sexMale) 0.18016894f else 0.245691014f
        val boneRaw = -(base - 0.05158f * lbmCoeff)
        return if (boneRaw > 2.2f) boneRaw + 0.1f else boneRaw - 0.1f
    }

    private fun heymsfieldBone(w: Float, sexMale: Boolean): Float =
        if (sexMale) 0.041f * w else 0.036f * w

    private fun empiricalVfi(h: Float, w: Float, age: Int, sexMale: Boolean): Float {
        return if (sexMale) {
            if (h < 1.6f * w) {
                305f * w / (-(0.4f * h - 0.0826f * h * h) + 48f) - 2.9f + 0.15f * age
            } else {
                -(0.143f * h - (0.765f - 0.0015f * h) * w) + 0.15f * age - 5f
            }
        } else {
            val threshold = -(13f - 0.5f * h)
            if (w > threshold) {
                500f * w / (1.45f * h + 0.1158f * h * h - 120f) - 6f + 0.07f * age
            } else {
                -(0.027f * h - (0.691f - 0.0048f * h) * w) + 0.07f * age - age
            }
        }
    }
}

