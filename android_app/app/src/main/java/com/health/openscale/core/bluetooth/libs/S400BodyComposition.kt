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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
 * The two impedance-driven regressions, Sun 2003 and Janssen 2000, were derived
 * on 50 kHz single-frequency BIA and take the corrected low band. De Lorenzo
 * Eq. B2 wants `R_0`, which §2.3 recovers from both bands. Everything else here
 * (Cunningham, Mifflin-St Jeor, Deurenberg, Heymsfield, Pace & Rathbun, Kotler)
 * takes no impedance input at all.
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
 *    the arm segment (Organ 1994, Bracco 1996, Demura 2004). Both bands are
 *    multiplied by [FOOT_TO_FOOT_CORRECTION], which scales `R_0` and `R_INF`
 *    together and so leaves the §2.3 band ratio untouched. The raw
 *    (un-corrected) high band is kept for the §3.7 empirical bone formula, which
 *    was fit against raw foot-to-foot data and would double-correct.
 *  - **§2.3 Cole inversion.** `|Z_50| / |Z_250|` is strictly increasing in
 *    `R_0 / R_INF` once `α` and `f_c` are pinned, so two magnitudes at two known
 *    frequencies determine both. Bisection recovers `R_0` for §3.2 and
 *    `R_0 / R_INF` as a plausibility check. See the §2.3 note below for what
 *    this does and does not buy.
 *
 * ## Computation order (§3) — sources
 *  - §3.1 TBW: Sun 2003 race-combined, sex-specific, on corrected R_low
 *  - §3.2 ECW: De Lorenzo 1997 Eq. B2, on the §2.3 `R_0`
 *  - §3.3 ICW = TBW − ECW
 *  - §3.4 FFM = TBW / 0.732 — Pace & Rathbun 1945 hydration constant
 *  - §3.5 BF = W − FFM
 *  - §3.6 SMM: Janssen 2000 (MRI-validated, 50 kHz), on corrected R_low
 *  - §3.7 Bone — see [BoneFormula]
 *  - §3.8 VFI — empirical anthropometric regression (no impedance input)
 *  - §3.9 BMR — see [BmrFormula]; Mifflin-St Jeor fallback when FFM suppressed
 *  - §3.10 BCM = ICW / 0.70 — Kotler 1996
 *  - §3.11 Phase angle — **not derivable** on S400 (no reactance from
 *    magnitude-only impedance); always null. Do not invent a default like 5°.
 *
 * ## What §2.3 buys, and what it does not
 * Eq. B2 is calibrated against `R_E`, which is `R_0`: Appendix B p. 1556 defines
 * `k_ECW` as the mean of `V_ECW / ((L²√Wt)/R_E)^(2/3)` over the calibration
 * cohort. Substituting the smaller 50 kHz magnitude therefore inflates ECW as
 * `R^(-2/3)`, by 12 % on that cohort's own numbers. Note what this argument does
 * **not** license: evaluating Eq. B2 at the calibration cohort's mean returns
 * that cohort's mean measured ECW by construction, so agreement there is the
 * calibration and not a test.
 *
 * The test that does mean something is a round trip. Generate `|Z_50|` and
 * `|Z_250|` from the Table 2 dilution-cohort Cole terms (`R_E` 577.71,
 * `R_I` 1020.42, `α` 0.68, `f_c` 61.96 kHz, giving 487.40 Ω and 417.62 Ω), then
 * invert them with the **population** constants below rather than that
 * subgroup's own. `R_0` comes back 579.2 Ω against a true 577.7, an error of
 * +0.3 %, worth -0.2 % on ECW.
 *
 * `α` and `f_c` are **assumed**, not fitted, so §2.3 is not a Cole fit and does
 * not make this a spectroscopy device. Pinning `α` is well supported: Table 2
 * puts it at 0.70 ± 0.02 (men) and 0.68 ± 0.03 (women). Pinning `f_c` is not.
 * De Lorenzo p. 1554 reports 43-110 kHz across his 73 healthy subjects and says
 * flatly that "use of `f_c` cannot be supported, because it is affected by all
 * the variables in the model", and Table 2's printed `f_c` column is itself
 * ~1.4× the value its own `C_m`, `R_E` and `R_I` imply under Eq. A1. Sweeping
 * `α` over 0.60-0.80 and `f_c` over 30-80 kHz moves `ECW/TBW` by under 0.06 on
 * the §7.1-7.3 subjects, but that is the flattering statistic: over the same box
 * `R_0` moves ±7-15 % and **ECW itself moves 1.4-1.9 kg**, which is what the UI
 * displays. Within the healthy 43-110 kHz `f_c` range the `ECW/TBW` spread is
 * nearer ±0.01.
 *
 * A second, larger uncertainty sits underneath all of it. The source's own
 * `k_ECW` is ambiguous by 15.4 %: p. 1544 prints 0.306 while Table 3's BIS-ECW
 * of 21.03 L for the same 14 men implies 0.353. The two reconcile exactly as
 * `(214 / 174.32)^(2/3) = 1.1465` against `21.03 / 18.34 = 1.1467`, so 0.306
 * belongs to the `ρ_ECW` 174.32 set that De Lorenzo recomputed from those men
 * and 0.353 to the earlier `ρ_ECW` 214 set. The code uses 0.306. That choice is
 * a larger lever on the displayed ECW than the 12 % this pipeline corrects.
 *
 * ## Compartment cross-check (§3.2b)
 * §2.3 supplies `R_E / R_INF`, which is what Matthie 2005 Eqs. 5 and 14 consume,
 * so an ICW and hence a second, independent TBW can be computed from the same
 * `R_0` using the `ρ_ECW` / `ρ_ICW` pair that belongs to the same recalibration
 * as `k_ECW`. Sun 2003 is a D₂O-calibrated NHANES regression and Eq. B2 is
 * NaBr-calibrated Hanai mixture theory, so the two routes share no calibration
 * and their agreement is real evidence that the inversion landed. On De
 * Lorenzo's dilution cohort they agree to 0.14 %.
 *
 * That agreement, not a window on `R_0/R_INF`, gates §3.2. Given pinned `α` and
 * `f_c`, `R_0/R_INF` is a strictly monotone relabelling of the raw band ratio and
 * carries nothing the ratio does not; a plausibility window on it would be a
 * band-ratio window wearing a physiological name. The bounds come from the two
 * routes' published standard errors of the estimate (De Lorenzo p. 1545: TBW
 * 1.33 L on 45.48, ECW 0.90 L on 18.34), added in quadrature to
 * [CROSS_CHECK_SIGMA]. Beyond 2σ the result is APPROXIMATE; beyond 3σ, ECW, ICW
 * and BCM are suppressed.
 *
 * The Matthie ICW itself is **not** displayed. It rests on the same assumed `α`
 * and `f_c` as `R_0`, through a second nonlinear step, so using it as a check is
 * sound while using it as a measurement would compound the assumption. ICW stays
 * the remainder of TBW after §3.2 ECW.
 *
 * Note what that remainder is, and what it does to BCM. `TBW` comes from Sun
 * 2003 and `ECW` from Eq. B2, calibrated on different tracers; De Lorenzo
 * p. 1547 puts NaBr and ³⁵SO₄ spaces 20 % apart, so the displayed `ECW/TBW` is
 * tracer-dependent before any impedance error and has no published validation as
 * a pair. And because §2.3 lowers ECW it raises ICW, so `BCM/FFM` moves from
 * 0.55/0.51/0.56 to 0.59/0.56/0.62 on the §7.1-7.3 subjects, away from Kotler's
 * 0.50-0.55 rather than toward it. §2.3 improves one displayed ratio and
 * degrades another; only the first is gated.
 *
 * Note what that remainder is: `TBW` from Sun 2003, a D₂O-calibrated NHANES
 * regression, minus `ECW` from Eq. B2, calibrated against NaBr. De Lorenzo
 * p. 1547 puts NaBr and ³⁵SO₄ spaces 20 % apart, so the displayed `ECW/TBW`
 * is tracer-dependent before any impedance error and has no published
 * validation as a pair.
 *
 * ## Suppression policy
 *  - TBW out of `[0.30·W, 0.75·W]` → suppress TBW + everything downstream
 *  - `ECW/TBW` outside `[0.30, 0.55]` → suppress ECW, ICW, BCM; TBW/FFM/BF/SMM
 *    still display (they depend only on TBW). Healthy reference: 0.36-0.40
 *    young adult, 0.38-0.42 older; De Lorenzo p. 1547 puts his own dilution
 *    cohort at 0.40-0.42. This pipeline still reads above that band.
 *  - §3.2b cross-check beyond 3·[CROSS_CHECK_SIGMA] → suppress ECW, ICW, BCM.
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
 * *J Appl Physiol* 82:1542-1558 (ECW Eq. B2, `k_ECW` p. 1544, `K_B` Appendix
 * C); Matthie 2005 *J Appl Physiol* 99:780-781,
 * doi:10.1152/japplphysiol.00145.2005 (second-generation ICW; restates Eq. B2
 * unchanged); Pace & Rathbun 1945 *J Biol Chem* 158:685-691
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
    /** §2.3 `R_0 / R_INF`; null when the two bands admit no Cole solution. */
    val r0RinfRatio: Float?,
    /** §3.2b fractional gap between the Matthie TBW and the Sun 2003 TBW. */
    val tbwCrossCheckDelta: Float?,
    val reliability: Reliability,
    val labelSwapApplied: Boolean,
)

object S400BodyComposition {

    /**
     * §2.2 multiplicative correction applied to the low-frequency band before
     * it enters a prediction equation. Bracco 1996 default for mid-range adults.
     * Defensible literature range 1.00-1.18: athletic/lean closer to 1.05,
     * overweight closer to 1.15. Exposed as a parameter to [compute] so a
     * caller can override per user profile without recompiling.
     */
    const val FOOT_TO_FOOT_CORRECTION = 1.10f

    /**
     * §3.2 `k_ECW`, De Lorenzo 1997 p. 1544. These are the values Xitron's
     * software uses: scaled against D₂O and NaBr dilution data, not evaluated
     * from Eq. B3.
     */
    private const val K_ECW_M = 0.306f
    private const val K_ECW_F = 0.316f

    /**
     * §2.3 Cole parameters, De Lorenzo Table 2 p. 1545 (men n=63, women n=10).
     * These are population means standing in for a per-subject fit; the file
     * KDoc quantifies how far the §3.2 output moves when they are wrong.
     */
    private const val COLE_ALPHA_M = 0.70f
    private const val COLE_ALPHA_F = 0.68f
    private const val COLE_FC_KHZ_M = 57.02f
    private const val COLE_FC_KHZ_F = 80.14f

    /** Nominal frequencies of the two bands the S400 broadcasts. */
    private const val BAND_LOW_KHZ = 50.0
    private const val BAND_HIGH_KHZ = 250.0

    /** Upper bracket for the §2.3 bisection. */
    private const val R0_RINF_MAX_BRACKET = 5.0

    /**
     * §3.2b resistivities, De Lorenzo p. 1545, recomputed from the n=14 dilution
     * men. This is the set [K_ECW_M] belongs to. The women's pair is scaled from
     * the men's rather than fitted (p. 1544), which is also why `k_ECW` for women
     * disagrees with it: 167.80 implies 0.298, and p. 1544 prints 0.316.
     */
    private const val RHO_ECW_M = 174.32f
    private const val RHO_ICW_M = 1177.94f
    private const val RHO_ECW_F = 167.80f
    private const val RHO_ICW_F = 1139.34f

    /**
     * §3.2b combined standard error of the two TBW routes, from the SEEs De
     * Lorenzo reports on p. 1545 for the same cohort: 1.33 L on a TBW of 45.48
     * and 0.90 L on an ECW of 18.34, added in quadrature. 2σ flags, 3σ suppresses.
     */
    private const val CROSS_CHECK_SIGMA = 0.0571f

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

        // §2.2 foot-to-foot correction, applied to both bands so §2.3 sees the
        // same ratio either way.
        val zLow = rLow * footToFootCorrection
        val zHigh = rHigh * footToFootCorrection

        // §2.3 R_0 from the two bands; gated on a plausible R_0/R_INF.
        val coleFit = invertCole(
            zLow = zLow,
            zHigh = zHigh,
            fcKHz = if (inputs.sexMale) COLE_FC_KHZ_M else COLE_FC_KHZ_F,
            alpha = if (inputs.sexMale) COLE_ALPHA_M else COLE_ALPHA_F,
        )

        // §3.1 TBW (Sun 2003, race-combined, sex-specific).
        val sexM = if (inputs.sexMale) 1f else 0f
        val tbwRaw = if (inputs.sexMale) {
            1.20f + 0.45f * (h * h / zLow) + 0.18f * w
        } else {
            3.75f + 0.45f * (h * h / zLow) + 0.11f * w
        }
        val tbwOk = tbwRaw in (0.30f * w)..(0.75f * w)
        val tbw = if (tbwOk) tbwRaw else null

        // §3.2 ECW (De Lorenzo 1997 Eq. B2), on the §2.3 R_0.
        val kEcw = if (inputs.sexMale) K_ECW_M else K_ECW_F
        val ecwRaw = coleFit?.let {
            kEcw * ((h * h * sqrt(w)) / it.r0).toDouble().pow(2.0 / 3.0).toFloat()
        }

        // §3.2b independent TBW from the same R_0 (Matthie 2005 Eqs. 5 and 14).
        val crossCheckDelta = if (ecwRaw != null && coleFit != null && tbw != null && tbw > 0f) {
            val matthieTbw = ecwRaw + matthieIcw(ecwRaw, coleFit.r0RinfRatio, inputs.sexMale)
            (matthieTbw - tbw) / tbw
        } else {
            null
        }
        val crossCheckTight = crossCheckDelta != null && abs(crossCheckDelta) <= 2f * CROSS_CHECK_SIGMA
        val crossCheckOk = crossCheckDelta != null && abs(crossCheckDelta) <= 3f * CROSS_CHECK_SIGMA

        // §3.3 ICW = TBW − ECW; suppress per-compartment outputs on bad ratio.
        val ecwTbwRatio = if (tbw != null && tbw > 0f && ecwRaw != null) ecwRaw / tbw else null
        val ratioOk = ecwTbwRatio != null && ecwTbwRatio in 0.30f..0.55f
        val ecw = if (tbw != null && ratioOk && crossCheckOk) ecwRaw else null
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

        // §3.6 SMM (Janssen 2000).
        val smmRaw = 0.401f * (h * h / zLow) + 3.825f * sexM - 0.071f * inputs.age + 5.102f
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
            !tbwOk || !ffmOk || !bfPctOk || !crossCheckTight -> Reliability.APPROXIMATE
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
            r0RinfRatio = coleFit?.r0RinfRatio,
            tbwCrossCheckDelta = crossCheckDelta,
            reliability = reliability,
            labelSwapApplied = labelSwap,
        )
    }

    private data class ColeFit(val r0: Float, val r0RinfRatio: Float)

    /**
     * §3.2b ICW by Matthie 2005 Eq. 5, with `ρ_TBW` from its Eq. 14. Both take
     * `(R_E + R_I) / R_I`, which is `R_E / R_INF`, so [ColeFit.r0RinfRatio] is
     * the only impedance input. Used to cross-check §3.1, never displayed.
     */
    private fun matthieIcw(ecwKg: Float, r0RinfRatio: Float, sexMale: Boolean): Float {
        val rhoEcw = if (sexMale) RHO_ECW_M else RHO_ECW_F
        val rhoIcw = if (sexMale) RHO_ICW_M else RHO_ICW_F
        val r = r0RinfRatio.toDouble()
        val rhoTbw = rhoIcw - (rhoIcw - rhoEcw) * (1.0 / r).pow(2.0 / 3.0)
        return (ecwKg * (((rhoTbw * r) / rhoEcw).pow(2.0 / 3.0) - 1.0)).toFloat()
    }

    /**
     * `|Z(f)| / R_INF` for the Cole model at `R_0 / R_INF = r`, in real
     * arithmetic: `Z = R_INF + (R_0 − R_INF) / (1 + (j·f/f_c)^α)`, expanding
     * `(j·x)^α` as `x^α·(cos(απ/2) + j·sin(απ/2))`.
     */
    private fun coleMagnitude(r: Double, fKHz: Double, fcKHz: Double, alpha: Double): Double {
        val u = (fKHz / fcKHz).pow(alpha)
        val quarterTurn = alpha * PI / 2.0
        val denomRe = 1.0 + u * cos(quarterTurn)
        val denomIm = u * sin(quarterTurn)
        val denomSq = denomRe * denomRe + denomIm * denomIm
        val spread = r - 1.0
        return hypot(1.0 + spread * denomRe / denomSq, -spread * denomIm / denomSq)
    }

    /**
     * §2.3 recovers `R_0` from the two broadcast magnitudes.
     *
     * `|Z_50| / |Z_250|` is strictly increasing in `R_0 / R_INF` for fixed `α`
     * and `f_c`, and equals 1 at `R_0 = R_INF`, so bisection inverts it. Returns
     * null when the measured ratio is at or below 1, or above what any
     * `R_0 / R_INF` up to [R0_RINF_MAX_BRACKET] can produce, which leaves §3.2
     * suppressed rather than guessed.
     */
    private fun invertCole(zLow: Float, zHigh: Float, fcKHz: Float, alpha: Float): ColeFit? {
        val measured = (zLow / zHigh).toDouble()
        if (measured <= 1.0) return null
        val fc = fcKHz.toDouble()
        val a = alpha.toDouble()
        fun bandRatio(r: Double) =
            coleMagnitude(r, BAND_LOW_KHZ, fc, a) / coleMagnitude(r, BAND_HIGH_KHZ, fc, a)

        var lo = 1.0 + 1e-9
        var hi = R0_RINF_MAX_BRACKET
        if (bandRatio(hi) < measured) return null
        repeat(60) {
            val mid = (lo + hi) / 2.0
            if (bandRatio(mid) < measured) lo = mid else hi = mid
        }
        val ratio = (lo + hi) / 2.0
        val rInf = zLow / coleMagnitude(ratio, BAND_LOW_KHZ, fc, a)
        return ColeFit(r0 = (ratio * rInf).toFloat(), r0RinfRatio = ratio.toFloat())
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
        r0RinfRatio = null,
        tbwCrossCheckDelta = null,
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

