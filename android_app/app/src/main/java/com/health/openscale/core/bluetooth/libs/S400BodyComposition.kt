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
 * **not** license: `k_ECW` is the mean of the per-subject ratios, so evaluating
 * Eq. B2 at the cohort's mean anthropometry returns that cohort's mean measured
 * ECW to 0.6 % (18.23 L against 18.34). Agreement there is nearly the
 * calibration restated, not a test of it.
 *
 * The test that does mean something is a round trip. Generate `|Z_50|` and
 * `|Z_250|` from the Table 2 dilution-cohort Cole terms (`R_E` 577.71,
 * `R_I` 1020.42, `α` 0.68, `f_c` 61.96 kHz, giving 487.40 Ω and 417.62 Ω), then
 * invert them with the **population** constants below rather than that
 * subgroup's own. `R_0` comes back 579.2 Ω against a true 577.7, an error of
 * +0.3 %, worth -0.2 % on ECW.
 *
 * That round trip cannot see one error, because it generates and inverts under
 * the same convention. Eq. A2 p. 1555 defines `f_c` as the reactance peak of the
 * **`T_d`-rotated** spectrum, and [coleMagnitude] implements a plain Cole model
 * with no `T_d` term, which is the likely source of the 1.4× discrepancy below.
 * Generating from the `C_m`-implied 44.46 kHz and inverting at the printed 57.02
 * recovers `R_0` 556.4, -3.7 %, worth -2.5 % on ECW, biased toward lower `r`.
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
 * `k_ECW` is ambiguous by about 15 %: p. 1544 prints 0.306 while Table 3's
 * BIS-ECW of 21.03 L for the same 14 men implies 0.35. The two reconcile as
 * `(214 / 174.32)^(2/3) = 1.1465` against `21.03 / 18.34 = 1.1467`, so 0.306
 * belongs to the `ρ_ECW` 174.32 set that De Lorenzo recomputed from those men
 * and 0.353 to the earlier `ρ_ECW` 214 set. The code uses 0.306. That choice is
 * a larger lever on the displayed ECW than the 12 % this pipeline corrects.
 *
 * ## Compartment cross-check (§3.2b)
 * `R_0/R_INF` is compared against the healthy distribution De Lorenzo Table 2
 * reports, 1.525 ± 0.079 for men and 1.494 ± 0.075 for women. Beyond 2 SD the
 * result is APPROXIMATE. It does **not** suppress, and the reason is worth
 * recording: [BAND_HIGH_KHZ] is a nominal figure with no source, and the
 * comparison is more sensitive to it than to anything else in §2.3. The one real
 * device capture in the tests (543.2 / 497.6 Ω) lands at 3.01 SD read as a male
 * and 2.78 SD read as a female, so a 3 SD cut would have withheld the
 * compartment split from a man and granted it to a woman on the same reading;
 * assume 150 kHz instead of 250 and the same capture sits at 1.44 SD. A ±20 %
 * error in the assumed band moves the figure by 0.6 to 1.1 SD. What suppresses
 * §3.2 is the §3.3 `ECW/TBW` window and §3.1b, both of which read quantities the
 * band frequencies do not enter.
 *
 * The comparison is a band-ratio test either way: with `α` and `f_c` pinned,
 * `R_0/R_INF` is a strictly monotone relabelling of `|Z_50|/|Z_250|` and carries
 * nothing the raw ratio does not.
 *
 * §2.3 also supplies what Matthie 2005 Eqs. 5 and 14 consume, so a second TBW
 * can be computed from the same `R_0` with the `ρ_ECW` / `ρ_ICW` pair below. Its
 * gap against Sun 2003 is reported as [S400Result.tbwCrossCheckDelta] and is a
 * useful diagnostic, but it does **not** gate, for two reasons found by
 * measurement rather than argument.
 *
 * First, it is not an independent second opinion. Both routes read the same
 * 50 kHz magnitude. Scaling both bands by a common factor leaves the band ratio
 * untouched; Sun's TBW then moves as `λ^-0.668`, its impedance term being 66.8 %
 * of the total, while the Matthie route moves as `λ^-2/3`. Sweeping `λ` from
 * 0.85 to 1.30 on the §7.1 subject, a 53 % swing in absolute impedance, moves the
 * gap by 0.6 percentage points. Sweeping [FOOT_TO_FOOT_CORRECTION] over its
 * stated 1.00-1.18 range moves the gap by 0.09 points while moving the displayed
 * ECW by 10.4 %. **Nothing here guards common-mode impedance error**: contact
 * resistance, the scale's absolute calibration and the foot-to-foot factor all
 * pass unexamined, and §3.1b is the only check anywhere that can see them.
 *
 * Second, inverting the gap back into `r` gives an accept region that tracks the
 * Table 2 window closely: averaged over the validated anthropometric range and a
 * 2.3× swing in absolute impedance it spans `r` 1.343-1.773 against the Table 2
 * 3 SD span of 1.288-1.762, with the boundaries themselves moving ±0.06 to ±0.11
 * across that sweep. Gating on the gap would therefore have been a band-ratio
 * comparison in disguise, with its bounds resting on a `σ` built from two nested
 * SEEs that both describe De Lorenzo's own BIS route rather than Sun's.
 *
 * On De Lorenzo's dilution cohort the full pipeline puts the gap at +2.84 %, of
 * which Sun alone contributes -5.41 % against the measured D₂O TBW. The two
 * equations do agree to 0.14 % when each is fed measured ECW and the true Cole
 * `r`, but that comparison runs neither Sun nor §2.3 and is close to its own
 * calibration.
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
 * degrades another, and neither is gated.
 *
 * ## Suppression policy
 *  - TBW out of `[0.38, 0.68]·W` (M) / `[0.35, 0.63]·W` (F) → suppress TBW +
 *    everything downstream
 *  - `ECW/TBW` outside `[0.30, 0.55]` → suppress ECW, ICW, BCM; TBW/FFM/BF/SMM
 *    still display (they depend only on TBW). Healthy reference: 0.36-0.40
 *    young adult, 0.38-0.42 older; De Lorenzo p. 1547 puts his own dilution
 *    cohort at 0.40-0.42. This pipeline still reads above that band.
 *  - §3.1b BF % more than [DEURENBERG_MARGIN] from Deurenberg 1991 in either
 *    direction → suppress TBW and everything downstream. TBW, FFM and BF are one
 *    number in three forms, so all three go together.
 *  - VFI outside [1, 30] → suppress rather than clamp
 *  - `R_0/R_INF` beyond 2 SD of the Table 2 healthy mean → APPROXIMATE, no
 *    suppression
 *  - UNRELIABLE contact → suppress every impedance-derived field. Weight, BMI,
 *    VFI, anthropometric bone and the §6 Mifflin BMR carry no impedance and
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
     * men; the women's pair is scaled from the men's rather than fitted (p. 1544).
     * p. 1544 instead pairs `k_ECW` 0.306 with an apparent ρ_ECW of 214, but only
     * this set reproduces that cohort: Matthie Eq. 5 depends on the two
     * resistivities solely through their ratio, and 1177.94/174.32 returns
     * ICW/ECW 1.476 against a measured 1.479, where 824/214 returns 0.949.
     */
    private const val RHO_ECW_M = 174.32f
    private const val RHO_ICW_M = 1177.94f
    private const val RHO_ECW_F = 167.80f
    private const val RHO_ICW_F = 1139.34f

    /**
     * §3.2b `R_0 / R_INF` in healthy adults, De Lorenzo Table 2 p. 1545, as
     * `1 + R_E/R_I` with the SDs propagated assuming `R_E` and `R_I` independent
     * (which overstates the spread, since both scale with body size, so these are
     * upper bounds). 2 SD flags the result APPROXIMATE, 3 SD suppresses §3.2.
     */
    private const val R0_RINF_MEAN_M = 1.525f
    private const val R0_RINF_SD_M = 0.079f
    private const val R0_RINF_MEAN_F = 1.494f
    private const val R0_RINF_SD_F = 0.075f

    /**
     * §3.1b percentage points away from the Deurenberg 1991 anthropometric body
     * fat at which a BIA body fat stops being a difference of opinion and starts
     * being a report on the electrode contact. Symmetric: a foot-to-foot scale
     * fails high (dry or cold feet) at least as often as it fails low.
     */
    private const val DEURENBERG_MARGIN = 12.0f

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
        val tbwRange = if (inputs.sexMale) (0.38f * w)..(0.68f * w) else (0.35f * w)..(0.63f * w)

        // §3.1b Anthropometric cross-check. TBW, FFM and BF are one number in
        // three forms: `FFM = TBW / 0.732` and `BF% = 100 - TBW%/0.732`. Whatever
        // rejects one has to reject all three, or the display contradicts itself.
        // Deurenberg 1991, already the §6 fallback, gives a body fat from height,
        // weight, age and sex alone, with no impedance in it.
        val ffmRaw = tbwRaw / 0.732f
        val bfPctRaw = ((w - ffmRaw) / w) * 100f
        val deurenbergBf = 1.20f * bmi + 0.23f * inputs.age - 10.8f * sexM - 5.4f
        val bfPlausible = abs(bfPctRaw - deurenbergBf) <= DEURENBERG_MARGIN

        val tbwOk = tbwRaw in tbwRange && bfPlausible
        val tbw = if (tbwOk) tbwRaw else null

        // §3.2 ECW (De Lorenzo 1997 Eq. B2), on the §2.3 R_0.
        val kEcw = if (inputs.sexMale) K_ECW_M else K_ECW_F
        val ecwRaw = coleFit?.let {
            kEcw * ((h * h * sqrt(w)) / it.r0).toDouble().pow(2.0 / 3.0).toFloat()
        }

        // §3.2b Matthie 2005 Eqs. 5 and 14 give a second TBW from the same R_0.
        // Reported as a diagnostic; see the §3.2b note for why it does not gate.
        val crossCheckDelta = if (ecwRaw != null && coleFit != null) {
            val matthieTbw = ecwRaw + matthieIcw(ecwRaw, coleFit.r0RinfRatio, inputs.sexMale)
            (matthieTbw - tbwRaw) / tbwRaw
        } else {
            null
        }

        // §3.2b gate: R_0/R_INF against the Table 2 healthy distribution.
        val rMean = if (inputs.sexMale) R0_RINF_MEAN_M else R0_RINF_MEAN_F
        val rSd = if (inputs.sexMale) R0_RINF_SD_M else R0_RINF_SD_F
        val rDeviation = coleFit?.let { abs(it.r0RinfRatio - rMean) / rSd }
        val rTight = rDeviation != null && rDeviation <= 2f

        // §3.3 ICW = TBW − ECW; suppress per-compartment outputs on bad ratio.
        val ecwTbwRatio = if (tbw != null && tbw > 0f && ecwRaw != null) ecwRaw / tbw else null
        val ratioOk = ecwTbwRatio != null && ecwTbwRatio in 0.30f..0.55f
        val ecw = if (tbw != null && ratioOk) ecwRaw else null
        val icw = if (tbw != null && ecw != null) tbw - ecw else null

        // §3.4 FFM = TBW / 0.732 (Pace & Rathbun 1945). §3.1b decides for all
        // three: the §3.1 window on TBW/W maps to FFM/W [0.52, 0.93] (M) and
        // [0.48, 0.86] (F), inside every range a separate check could impose.
        val ffm = if (tbw != null) ffmRaw else null

        // §3.5 Body fat.
        val bf = if (ffm != null) w - ffm else null
        val bfPct = if (bf != null) bfPctRaw else null
        val bfKg = bf

        // §3.6 SMM (Janssen 2000). Rides on the §3.4 suppression: Janssen's
        // regression shares the resistance index that drove FFM out of range.
        val smmRaw = 0.401f * (h * h / zLow) + 3.825f * sexM - 0.071f * inputs.age + 5.102f
        val smm = if (ffm != null) smmRaw.coerceIn(8f, 75f) else null

        // §3.7 Bone mineral mass — two options. MI_LEGACY reads impedance, so a
        // failed §2.1 contact check drops it to the anthropometric formula rather
        // than reporting a bone mass derived from a reading just called unusable.
        val bone = when {
            unreliableContact || boneFormula == BoneFormula.HEYMSFIELD ->
                heymsfieldBone(w, inputs.sexMale)
            else -> empiricalBone(h, w, inputs.age, rHighRawAfterSwap, inputs.sexMale)
        }.coerceIn(1.0f, 6.0f)

        // §3.8 VFI (empirical anthropometric regression, uses RAW height + weight only).
        // Out-of-range values are suppressed rather than clamped. The male branch
        // still steps down by 14-16 points where `h < 1.6·w` flips, a test that
        // compares centimetres against kilograms and so switches at BMI 42 for a
        // 150 cm man and BMI 31 for a 200 cm one; above the step it can exceed 30.
        val vfiRaw = empiricalVfi(h, w, inputs.age, inputs.sexMale)
        val vfi = vfiRaw.takeIf { it in 1f..30f }

        // §3.9 BMR.
        val bmrFromFfm = if (ffm != null && !unreliableContact) {
            when (bmrFormula) {
                BmrFormula.CUNNINGHAM_1991 -> 370f + 21.6f * ffm
                BmrFormula.CUNNINGHAM_1980 -> 500f + 22.0f * ffm
            }
        } else {
            // §6 Mifflin-St Jeor fallback. No impedance in it, so it survives a
            // failed §2.1 contact check where the FFM-based route cannot.
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
            !tbwOk || !rTight -> Reliability.APPROXIMATE
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
            smmPct = if (suppress || smm == null) null else (smm / w) * 100f,
            boneKg = bone,
            vfi = vfi,
            bmrKcal = bmr,
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
            500f * w / (1.45f * h + 0.1158f * h * h - 120f) - 6f + 0.07f * age
        }
    }
}

