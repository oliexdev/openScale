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
 * Pure-Kotlin, no Android dependencies, deterministic, side-effect-free. Every
 * coefficient comes from a published equation or from public reference data;
 * nothing is fitted to the scale's companion app.
 *
 * ## Inputs (six numbers per weighing)
 * `age` (y), `sexMale`, `heightCm`, `weightKg`, `rHighRaw` (Ω, ~250 kHz),
 * `rLowRaw` (Ω, ~50 kHz), both impedance magnitudes. Heart rate, if present, is
 * not an input to any equation here.
 *
 * ## What each band is used for
 * Fat-free mass, body fat, TBW and SMM read the 50 kHz band only. On NHANES
 * 1999-2004 (Xitron BIS against Hologic DXA, n = 5,939 adults 18-49; see
 * `S400NhanesValidationTest`) a fat-free-mass regression refitted on 1999-2002
 * and scored on 2003-04 reaches the same body-fat error with or without the
 * ~250 kHz magnitude (RMSE 3.38 against 3.39 points), so the second band carries
 * no fat-free-mass information. What it does carry is the ECW/ICW split: §2.3
 * turns the band ratio into `R_0`, and ECW from Eq. B2 on that `R_0` lands within
 * +0.7 % (SD 2.6 %) of the same equation on the full-spectrum `R_E`, against
 * +14 % when Eq. B2 is fed the 50 kHz magnitude itself.
 *
 * ## Validation (§1.1): reject entire computation
 * `age 18-120` (Janssen/Cunningham not validated <18), `height 100-230`,
 * `weight 20-250`, `R_high/R_low 200-1500`, `BMI 12-60`. These are the limits
 * beyond which the equations have not been validated, not physical limits.
 *
 * ## Pre-processing
 *  - **§2.1 Band ordering and contact.** Low-frequency current cannot cross cell
 *    membranes, so `|Z_50| > |Z_250|`. If reversed, swap and set
 *    [S400Result.labelSwapApplied]. If `|R_low - R_high| / R_high < 1 %`, contact
 *    is poor (dry feet, stepping off); mark UNRELIABLE and suppress every
 *    impedance-derived field.
 *  - **§2.2 Foot-to-foot correction.** The S400 measures foot to foot, while
 *    Deurenberg 1991, Janssen 2000 and De Lorenzo Eq. B2 (through `K_B`,
 *    Appendix C) assume a wrist-to-ankle path, and no source gives the
 *    conversion. Both bands are multiplied by [FOOT_TO_FOOT_CORRECTION], which
 *    leaves the §2.3 band ratio untouched. The raw high band is kept for the
 *    §3.7 empirical bone formula, which was fit on raw foot-to-foot data.
 *  - **§2.3 Two-band Cole inversion.** With `α` and `f_c` pinned,
 *    `|Z_50| / |Z_250|` is strictly increasing in `R_0 / R_INF`, so bisection
 *    recovers `R_0` for §3.2. The pinned values are the NHANES 1999-2002 Xitron
 *    fits by sex (median `f_c`, mean `α`); De Lorenzo's Table 2 values (57/80 kHz)
 *    leave `R_0` 5.9 % low and ECW 4.2 % high on 2003-04. This is not a Cole fit:
 *    per-subject `f_c` spreads ±20 kHz, which is what the 2.6 % ECW SD reflects.
 *
 * ## Computation order (§3): sources
 *  - §3.1 FFM: Deurenberg 1991 BIA equation on corrected `|Z_50|`
 *  - §3.1b FFM/BF gate: agreement with the Deurenberg 1991 BMI equation
 *  - §3.2 ECW: De Lorenzo 1997 Eq. B2 on the §2.3 `R_0`
 *  - §3.2b Matthie 2005 Eqs. 5 and 14 TBW as a diagnostic; band-ratio flag
 *  - §3.3 TBW = 0.732·FFM (Pace & Rathbun 1945); ICW = TBW − ECW
 *  - §3.5 BF = W − FFM
 *  - §3.6 SMM: Janssen 2000 (MRI-validated, 50 kHz), on corrected `|Z_50|`
 *  - §3.7 Bone: see [BoneFormula]
 *  - §3.8 VFI: empirical anthropometric regression (no impedance input)
 *  - §3.9 BMR: see [BmrFormula]; Mifflin-St Jeor fallback when FFM is suppressed
 *  - §3.10 BCM = ICW / 0.70 (Wang 2004 p. E125: ICW is 0.70 of BCM, 0.69-0.71
 *    in healthy adults)
 *  - §3.11 Phase angle: not derivable from magnitudes alone; always null
 *
 * ## Accuracy against DXA
 * On the NHANES adults the §3.1 equation reads body fat at +0.2 points bias,
 * RMSE 3.3, which is the error a regression refitted on NHANES itself reaches.
 * It is a 50 kHz resistance equation (Wang 2014 *Med Sci Monit* 20:2298,
 * Table 1, which lists it among 50 kHz equations), and the NHANES spectrum
 * agrees: fed the resistance at each measured frequency, its bias crosses zero
 * at 50 kHz (+2.0 at 20 kHz, -1.6 at 100 kHz). Feeding `|Z_50|` instead of `R_50`
 * costs 0.2 points of bias.
 * Sun 2003 TBW reads -1.6 / 4.1, the Hanai/Matthie two-band TBW -0.7 / 5.2, and
 * Xitron's own full-spectrum FFM -0.7 / 5.6. NHANES is hand-to-foot, so it
 * validates the equation, not [FOOT_TO_FOOT_CORRECTION]. For the foot-to-foot
 * path the comparison is Wu 2015, the 50 kHz foot-to-foot equation calibrated
 * on DXA: on the real S400 weighings in `S400ReferenceCohortTest` the two agree
 * to within 1 point on average at a factor of 1.00. Both read about 4 points
 * above the Mi app, which therefore appears to read low against DXA.
 *
 * ## Compartment cross-checks (§3.2b)
 * `R_0/R_INF` is compared against the robust NHANES 1999-2002 distribution of
 * the same two-band estimate (median ± 1.4826·MAD by sex). Beyond 2 SD the result
 * is APPROXIMATE, which flags 3.9 % of men and 7.5 % of women on 2003-04. It does
 * not suppress: foot-to-foot band ratios run lower than hand-to-foot ones, and 6
 * of the 11 S400 weighings sit beyond 2 SD, so the flag says the path differs as
 * much as that the reading is unusual.
 *
 * A second TBW from Matthie 2005 Eqs. 5 and 14 on the same `R_0` is reported as
 * [S400Result.tbwCrossCheckDelta] and never gates or displays: it rests on the
 * same pinned `α` and `f_c`, and as a TBW it scores worse against DXA than §3.1.
 *
 * `k_ECW` itself is ambiguous by about 15 % in the source: p. 1544 prints 0.306,
 * while Table 3's BIS-ECW for the same 14 men implies 0.35; the two correspond to
 * the `ρ_ECW` 174.32 and 214 sets respectively. The code uses 0.306. TBW and ECW
 * come from equations calibrated on different tracers, so `ECW/TBW` has no
 * published validation as a pair.
 *
 * ## Suppression policy
 *  - TBW out of `[0.38, 0.68]·W` (M) / `[0.35, 0.63]·W` (F): suppress TBW and
 *    everything downstream
 *  - §3.1b BF % more than [DEURENBERG_MARGIN] from the BMI equation in either
 *    direction: suppress TBW, FFM, BF and everything downstream, since they are
 *    one number in three forms
 *  - `ECW/TBW` outside `[0.30, 0.55]`: suppress ECW, ICW, BCM
 *  - VFI outside [1, 30]: suppress rather than clamp
 *  - `R_0/R_INF` beyond 2 SD: APPROXIMATE, no suppression
 *  - UNRELIABLE contact: suppress every impedance-derived field. Weight, BMI,
 *    VFI, anthropometric bone and the §6 Mifflin BMR still display
 *
 * ## Bone + VFI caveats
 * BIA does not measure bone: the output is a regression on weight, height, sex
 * and age, to be labelled "estimated". VFI cannot be derived from impedance
 * without a waist measurement; label it "approximate, no waist measured".
 *
 * ## Fallback policy (§6, partially implemented in caller)
 * When BIA computation is suppressed: BMI unconditionally; the Deurenberg 1991
 * BMI equation `BF% = 1.20·BMI + 0.23·age − 10.8·sexM − 5.4` for BF%; Heymsfield
 * for bone; Mifflin-St Jeor for BMR; the anthropometric VFI.
 *
 * ## Test vectors
 * §7.1-7.3 reference subjects and §7.4-7.5 edge cases live in
 * `S400BodyCompositionTest.kt`; DXA validation in `S400NhanesValidationTest.kt`;
 * real S400 weighings in `S400ReferenceCohortTest.kt` and
 * `S400MethodComparisonTest.kt`.
 *
 * ## Primary references
 * Deurenberg 1991 *Int J Obes* 15:17-25 (BIA FFM equation, densitometry,
 * n = 661 adults, 50 kHz resistance per Wang 2014 *Med Sci Monit* 20:2298);
 * Deurenberg 1991 *Br J Nutr* 65:105-114 (BMI BF% equation);
 * De Lorenzo 1997 *J Appl Physiol* 82:1542-1558 (ECW Eq. B2, `k_ECW` p. 1544,
 * `K_B` Appendix C); Matthie 2005 *J Appl Physiol* 99:780-781,
 * doi:10.1152/japplphysiol.00145.2005 (second-generation ICW); Pace & Rathbun
 * 1945 *J Biol Chem* 158:685-691 (FFM hydration); Janssen 2000 *J Appl Physiol*
 * 89:465-471 (SMM); Cunningham 1991 *Am J Clin Nutr* 54:963-969 (BMR);
 * Mifflin-St Jeor 1990 *Am J Clin Nutr* 51:241-247 (BMR fallback); Heymsfield
 * 2007 *Am J Clin Nutr* 86:82-91 (bone, see [BoneFormula]); Wang 2004
 * *Am J Physiol Endocrinol Metab* 286:E123-E128, doi:10.1152/ajpendo.00227.2003
 * (BCM); Wu 2015 *Nutr J* 14:52, doi:10.1186/s12937-015-0041-0
 * (foot-to-foot comparison); CDC NHANES 1999-2004 BIX/DXX/DEMO/BMX files
 * (public domain; Cole constants, band-ratio distribution, DXA validation).
 */

/**
 * §3.7. Pick one and **do not switch silently** — output stability across
 * weighings matters more than absolute accuracy.
 *  - [MI_LEGACY]: empirical impedance-based regression (uses RAW, uncorrected
 *    R_high). Matches the output range produced by the scale's companion
 *    apps; kept under this name for backward compatibility with persisted
 *    user preferences.
 *  - [HEYMSFIELD]: anthropometric (`0.041·W` M, `0.036·W` F), no impedance
 *    input; works as the §6 fallback when impedance is unusable. Heymsfield
 *    2007 does not print these fractions: its Table 2 DXA means give bone
 *    mineral / weight 0.040 (men) and 0.035 (women).
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
    /** §3.2b fractional gap between the Matthie TBW and the §3.3 TBW. */
    val tbwCrossCheckDelta: Float?,
    val reliability: Reliability,
    val labelSwapApplied: Boolean,
)

object S400BodyComposition {

    /**
     * §2.2 multiplicative correction applied to both bands, so it scales `R_0`
     * and `R_INF` together and cancels out of the §2.3 ratio. The file KDoc §2.2
     * explains why no source fixes it. At 1.00 the §3.1 equation agrees on
     * average with Wu 2015, the foot-to-foot equation calibrated on DXA, over the
     * real S400 weighings in `S400ReferenceCohortTest`. Exposed as a parameter to
     * [compute] so a caller can override it per user profile.
     */
    const val FOOT_TO_FOOT_CORRECTION = 1.00f

    /**
     * §3.2 `k_ECW`, De Lorenzo 1997 p. 1544. These are the values Xitron's
     * software uses: scaled against D₂O and NaBr dilution data, not evaluated
     * from Eq. B3.
     */
    private const val K_ECW_M = 0.306f
    private const val K_ECW_F = 0.316f

    /**
     * §2.3 Cole parameters: mean `α` and median `f_c` of the Xitron fits in NHANES
     * 1999-2002, adults 18-49 (2,053 men, 1,903 women), standing in for a
     * per-subject fit.
     */
    private const val COLE_ALPHA_M = 0.676f
    private const val COLE_ALPHA_F = 0.664f
    private const val COLE_FC_KHZ_M = 40.0f
    private const val COLE_FC_KHZ_F = 48.0f

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
     * §3.2b two-band `R_0 / R_INF` in NHANES 1999-2002 adults under the constants
     * above: median and 1.4826·MAD by sex. Beyond 2 SD the result is APPROXIMATE;
     * nothing is suppressed.
     */
    private const val R0_RINF_MEAN_M = 1.541f
    private const val R0_RINF_SD_M = 0.057f
    private const val R0_RINF_MEAN_F = 1.458f
    private const val R0_RINF_SD_F = 0.047f

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

        // §3.1 FFM (Deurenberg 1991 BIA equation): height in cm inside H²/Z and in
        // metres in the linear term.
        val sexM = if (inputs.sexMale) 1f else 0f
        val ffmRaw = 0.340f * (h * h / zLow) + 15.34f * (h / 100f) + 0.273f * w -
            0.127f * inputs.age + 4.56f * sexM - 12.44f
        val tbwRaw = 0.732f * ffmRaw
        val tbwRange = if (inputs.sexMale) (0.38f * w)..(0.68f * w) else (0.35f * w)..(0.63f * w)

        // §3.1b Anthropometric cross-check. TBW, FFM and BF are one number in
        // three forms, so whatever rejects one has to reject all three. The
        // Deurenberg 1991 BMI equation, already the §6 fallback, gives a body fat
        // from height, weight, age and sex alone, with no impedance in it.
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

        // §3.2b flag: R_0/R_INF against the NHANES two-band distribution.
        val rMean = if (inputs.sexMale) R0_RINF_MEAN_M else R0_RINF_MEAN_F
        val rSd = if (inputs.sexMale) R0_RINF_SD_M else R0_RINF_SD_F
        val rDeviation = coleFit?.let { abs(it.r0RinfRatio - rMean) / rSd }
        val rTight = rDeviation != null && rDeviation <= 2f

        // §3.3 ICW = TBW − ECW; suppress per-compartment outputs on bad ratio.
        val ecwTbwRatio = if (tbw != null && tbw > 0f && ecwRaw != null) ecwRaw / tbw else null
        val ratioOk = ecwTbwRatio != null && ecwTbwRatio in 0.30f..0.55f
        val ecw = if (tbw != null && ratioOk) ecwRaw else null
        val icw = if (tbw != null && ecw != null) tbw - ecw else null

        // §3.1 FFM, suppressed together with TBW. The TBW/W window maps to FFM/W
        // [0.52, 0.93] (M) and [0.48, 0.86] (F) through the 0.732 hydration.
        val ffm = if (tbw != null) ffmRaw else null

        // §3.5 Body fat.
        val bf = if (ffm != null) w - ffm else null
        val bfPct = if (bf != null) bfPctRaw else null
        val bfKg = bf

        // §3.6 SMM (Janssen 2000). Rides on the §3.1 suppression: Janssen's
        // regression shares the resistance index that drove FFM out of range.
        val smmRaw = 0.401f * (h * h / zLow) + 3.825f * sexM - 0.071f * inputs.age + 5.102f
        val smm = if (ffm != null) smmRaw.coerceIn(8f, 75f) else null

        // §3.7 Bone mineral mass, two options. MI_LEGACY reads impedance, so any
        // verdict that the reading is unusable, §2.1 contact or §3.1b disagreement,
        // drops it to the anthropometric formula rather than reporting a bone mass
        // derived from a reading the same call just rejected.
        val bone = when {
            unreliableContact || !bfPlausible || boneFormula == BoneFormula.HEYMSFIELD ->
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

        // §3.10 BCM = ICW / 0.70 (Wang 2004). Suppressed when ICW suppressed.
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

