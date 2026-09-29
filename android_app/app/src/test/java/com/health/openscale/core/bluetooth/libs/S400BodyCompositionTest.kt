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

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tests for [S400BodyComposition]. Section markers (§7.x) match the KDoc on
 * [S400BodyComposition], which is the specification. Expected values are
 * worked from the published equations documented there, by a reference
 * implementation written independently of this one, and not read back from it.
 * `R_0` is listed per subject so each chain can be re-derived by hand.
 *
 * Edge cases come first (§7.4 label swap, §7.5 unreliable contact) — cheapest
 * defensive checks to ship; they cover the two failure modes most likely to
 * break the model in production.
 *
 * Reference subjects (computed with `c = 1.00`, MI_LEGACY bone, CUN91 BMR):
 *  - §7.1 young athletic male: age 27, M, 172 cm, 76.5 kg, R 365/402, R_0 470.0
 *  - §7.2 middle-aged female: age 55, F, 162 cm, 68 kg, R 600/690, R_0 834.7
 *  - §7.3 older male:         age 70, M, 170 cm, 80 kg, R 520/610, R_0 771.0
 *
 * The real S400 weighings have no reference measurement: their body fat is
 * pinned to this implementation's output, to catch unintended changes, and only
 * the cohort mean is checked against an independent equation (Wu 2015).
 */
class S400BodyCompositionTest {

    private val tolKg = 0.05f
    private val tolPct = 0.1f
    private val tolKcal = 5f

    // ---------- §7.4 — label swap ----------

    @Test
    fun labelSwap_appliedWhenRLowBelowRHigh() {
        // Subject A with the two bands transposed. §2.1 must restore the
        // physiological ordering before anything downstream reads them.
        val swapped = S400Inputs(
            age = 27, sexMale = true, heightCm = 172f, weightKg = 76.5f,
            rHighRaw = 402f, rLowRaw = 365f,
        )
        val r = S400BodyComposition.compute(swapped)
        assertThat(r.labelSwapApplied).isTrue()
        // After swap, result must equal §7.1 Subject A.
        assertThat(r.tbwKg!!).isWithin(tolKg).of(44.64f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(60.98f)
    }

    @Test
    fun labelSwap_notAppliedWhenAlreadyCorrect() {
        val correct = S400Inputs(
            age = 27, sexMale = true, heightCm = 172f, weightKg = 76.5f,
            rHighRaw = 365f, rLowRaw = 402f,
        )
        val r = S400BodyComposition.compute(correct)
        assertThat(r.labelSwapApplied).isFalse()
    }

    // ---------- §7.5 — implausible electrode contact ----------

    @Test
    fun unreliable_whenRLowAndRHighWithinOnePercent() {
        // |ΔR| / R_high = (398 − 395) / 395 = 0.0076 < 0.01
        val edge = S400Inputs(
            age = 30, sexMale = true, heightCm = 180f, weightKg = 85f,
            rHighRaw = 395f, rLowRaw = 398f,
        )
        val r = S400BodyComposition.compute(edge)
        assertThat(r.reliability).isEqualTo(Reliability.UNRELIABLE)
        assertThat(r.tbwKg).isNull()
        assertThat(r.ecwKg).isNull()
        assertThat(r.icwKg).isNull()
        assertThat(r.bcmKg).isNull()
        assertThat(r.ffmKg).isNull()
        assertThat(r.bfPct).isNull()
        // Weight, BMI and the §6 Mifflin BMR carry no impedance and still report.
        assertThat(r.weightKg).isEqualTo(85f)
        assertThat(r.bmi).isWithin(0.1f).of(26.23f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1830f)
    }

    // ---------- §7.1 — Subject A (young athletic male) ----------

    @Test
    fun subjectA_defaultOptions_matchesSpec() {
        val r = subjectA()
        assertThat(r.tbwKg!!).isWithin(tolKg).of(44.64f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(58.35f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(20.55f)
        assertThat(r.ecwPct!!).isWithin(tolPct).of(26.87f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(24.08f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.461f)
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.369f)
        // 1.369 is 3.02 SD from the NHANES male median, hence APPROXIMATE.
        assertThat(r.tbwCrossCheckDelta!!).isWithin(0.002f).of(-0.0729f)
        assertThat(r.reliability).isEqualTo(Reliability.APPROXIMATE)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(60.98f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(15.52f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(20.29f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(36.52f)
        assertThat(r.smmPct!!).isWithin(tolPct).of(47.74f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.99f)  // Option A default
        assertThat(r.vfi!!).isWithin(0.1f).of(13.24f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1687f)  // Cun91 default
        assertThat(r.bcmKg!!).isWithin(tolKg).of(34.41f)
        assertThat(r.phaseAngleDeg).isNull()
    }

    @Test
    fun subjectA_boneOptionB_matchesSpec() {
        val r = subjectA(boneFormula = BoneFormula.HEYMSFIELD)
        assertThat(r.boneKg!!).isWithin(tolKg).of(3.14f)  // 0.041 × 76.5
    }

    @Test
    fun subjectA_bmrCunningham1980_matchesSpec() {
        val r = subjectA(bmrFormula = BmrFormula.CUNNINGHAM_1980)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1842f)
    }

    // ---------- §7.2 — Subject B (middle-aged female) ----------

    @Test
    fun subjectB_defaultOptions_matchesSpec() {
        val r = S400BodyComposition.compute(S400Inputs(
            age = 55, sexMale = false, heightCm = 162f, weightKg = 68f,
            rHighRaw = 600f, rLowRaw = 690f,
        ))
        assertThat(r.tbwKg!!).isWithin(tolKg).of(27.03f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(39.75f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(12.85f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(14.18f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.475f)
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.545f)
        assertThat(r.tbwCrossCheckDelta!!).isWithin(0.002f).of(0.1574f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(36.92f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(31.08f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(45.70f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(16.45f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.47f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1168f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(20.26f)
    }

    @Test
    fun subjectB_boneOptionB_matchesSpec() {
        val r = S400BodyComposition.compute(
            S400Inputs(55, false, 162f, 68f, 600f, 690f),
            boneFormula = BoneFormula.HEYMSFIELD,
        )
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.45f)  // 0.036 × 68
    }

    // ---------- §7.3 — Subject C (older male) ----------

    @Test
    fun subjectC_defaultOptions_matchesSpec() {
        val r = S400BodyComposition.compute(S400Inputs(
            age = 70, sexMale = true, heightCm = 170f, weightKg = 80f,
            rHighRaw = 520f, rLowRaw = 610f,
        ))
        assertThat(r.tbwKg!!).isWithin(tolKg).of(34.59f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(43.24f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(14.77f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(19.82f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.427f)
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.651f)
        assertThat(r.tbwCrossCheckDelta!!).isWithin(0.002f).of(0.1384f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(47.26f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(32.74f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(40.93f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(22.96f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.84f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1391f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(28.32f)
    }

    @Test
    fun subjectC_boneOptionB_matchesSpec() {
        val r = S400BodyComposition.compute(
            S400Inputs(70, true, 170f, 80f, 520f, 610f),
            boneFormula = BoneFormula.HEYMSFIELD,
        )
        assertThat(r.boneKg!!).isWithin(tolKg).of(3.28f)
    }

    // ---------- §2.3 Cole inversion ----------

    @Test
    fun coleInversion_ratioIsIndependentOfFootToFootCorrection() {
        // §2.2 scales both bands, and §2.3 reads only their ratio.
        assertThat(subjectA(footToFoot = 1.00f).r0RinfRatio!!)
            .isWithin(1e-5f).of(subjectA(footToFoot = 1.15f).r0RinfRatio!!)
    }

    @Test
    fun coleInversion_widerBandGapImpliesHigherR0RinfRatio() {
        val narrow = S400BodyComposition.compute(S400Inputs(27, true, 172f, 76.5f, 365f, 402f))
        val wide = S400BodyComposition.compute(S400Inputs(27, true, 172f, 76.5f, 365f, 420f))
        assertThat(wide.r0RinfRatio!!).isGreaterThan(narrow.r0RinfRatio!!)
    }

    @Test
    fun rWindow_flagsBeyondTwoSdWithoutSuppressing() {
        // 369/402 puts R_0/R_INF at 1.324, 3.81 SD from the NHANES male median,
        // while §3.1 and §3.1b both pass. The compartments still report: the
        // flag cannot tell an unusual reading from the foot-to-foot path.
        val r = S400BodyComposition.compute(S400Inputs(27, true, 172f, 76.5f, 369f, 402f))
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.324f)
        assertThat(r.reliability).isEqualTo(Reliability.APPROXIMATE)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(20.76f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(23.88f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(34.11f)
    }

    @Test
    fun rWindow_doesNotWithholdTheCapturedReference() {
        // The 543.2/497.6 Ω pair from S400DecryptorTest sits at 3.67 SD read as
        // male and 2.90 SD read as female. The flag must not withhold fields.
        val male = S400BodyComposition.compute(S400Inputs(40, true, 178f, 80f, 497.6f, 543.2f))
        assertThat(male.ecwKg).isNotNull()
        assertThat(male.icwKg).isNotNull()
        assertThat(male.bcmKg).isNotNull()
    }

    // ---------- band attribution ----------

    @Test
    fun bandAttribution_regressionsReadOnlyThe50kHzBand() {
        // Deurenberg 1991 and Janssen 2000 are 50 kHz equations. Moving only the
        // 250 kHz band must leave them untouched and move only ECW, through R_0.
        // Both label orders are checked so the §2.1 swap is covered too.
        for (transposed in listOf(false, true)) {
            fun run(r50: Float, r250: Float) = S400BodyComposition.compute(
                if (transposed) S400Inputs(27, true, 172f, 76.5f, rHighRaw = r50, rLowRaw = r250)
                else S400Inputs(27, true, 172f, 76.5f, rHighRaw = r250, rLowRaw = r50),
            )
            val base = run(402f, 365f)
            val moved250 = run(402f, 355f)
            assertThat(moved250.tbwKg).isEqualTo(base.tbwKg)
            assertThat(moved250.ffmKg).isEqualTo(base.ffmKg)
            assertThat(moved250.bfPct).isEqualTo(base.bfPct)
            assertThat(moved250.smmKg).isEqualTo(base.smmKg)
            assertThat(moved250.ecwKg).isNotEqualTo(base.ecwKg)

            val moved50 = run(412f, 365f)
            assertThat(moved50.tbwKg!!).isLessThan(base.tbwKg!!)
            assertThat(moved50.smmKg!!).isLessThan(base.smmKg!!)
        }
    }

    // ---------- §3.1b anthropometric cross-check ----------

    @Test
    fun deurenbergGuard_suppressesTheWholeWaterChain() {
        // 257/200 Ω reads 12.9 % body fat where the BMI equation gives 29.3,
        // 16.4 points apart, while TBW/W stays inside its window. TBW, FFM and
        // BF are one number in three forms, so all three go, and BMR falls back
        // to Mifflin-St Jeor.
        val r = S400BodyComposition.compute(S400Inputs(
            age = 45, sexMale = true, heightCm = 196f, weightKg = 112.5f,
            rHighRaw = 200f, rLowRaw = 257f,
        ))
        assertThat(r.tbwKg).isNull()
        assertThat(r.tbwPct).isNull()
        assertThat(r.ffmKg).isNull()
        assertThat(r.bfPct).isNull()
        assertThat(r.smmKg).isNull()
        assertThat(r.smmPct).isNull()
        assertThat(r.ecwKg).isNull()
        assertThat(r.icwKg).isNull()
        assertThat(r.bcmKg).isNull()
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(2.147f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(2130f)
        // Weight, BMI and the anthropometric outputs survive.
        assertThat(r.bmi).isWithin(0.01f).of(29.28f)
        assertThat(r.vfi!!).isWithin(0.1f).of(26.71f)
        // §3.7 drops to Heymsfield for the same reason §2.1 does.
        assertThat(r.boneKg!!).isWithin(tolKg).of(4.61f)
    }

    @Test
    fun deurenbergGuard_leavesTheReferenceSubjectsAlone() {
        assertThat(subjectA().tbwKg).isNotNull()
        assertThat(subjectA().smmKg).isNotNull()
        val c = S400BodyComposition.compute(S400Inputs(70, true, 170f, 80f, 520f, 610f))
        assertThat(c.tbwKg).isNotNull()
    }

    @Test
    fun vfi_reportedForNormalWeightWomen() {
        // §7.2 sits exactly on the `w = 0.5·h - 13` weight where the female
        // regression used to switch to a branch that cannot return an in-range
        // value at any adult height.
        val r = S400BodyComposition.compute(S400Inputs(55, false, 162f, 68f, 600f, 690f))
        assertThat(r.vfi!!).isWithin(0.1f).of(8.63f)
    }

    // ---------- §1.1 input validation ----------

    @Test
    fun rejectsUnderageSubject() {
        val r = S400BodyComposition.compute(S400Inputs(10, true, 140f, 35f, 500f, 600f))
        assertThat(r.reliability).isEqualTo(Reliability.NOT_AVAILABLE)
        assertThat(r.tbwKg).isNull()
        assertThat(r.weightKg).isEqualTo(35f)
    }

    @Test
    fun rejectsImplausibleBmi() {
        // weight = 250, height = 150 → BMI = 111
        val r = S400BodyComposition.compute(S400Inputs(40, true, 150f, 250f, 500f, 600f))
        assertThat(r.reliability).isEqualTo(Reliability.NOT_AVAILABLE)
    }

    @Test
    fun rejectsOutOfRangeImpedance() {
        val r = S400BodyComposition.compute(S400Inputs(30, true, 175f, 75f, 50f, 600f))
        assertThat(r.reliability).isEqualTo(Reliability.NOT_AVAILABLE)
    }

    // ---------- foot-to-foot correction injection ----------

    @Test
    fun footToFootCorrection_lowerC_yieldsLowerTbw() {
        val baseline = subjectA(footToFoot = 1.10f)
        val tight = subjectA(footToFoot = 1.00f)
        // c=1.0 means smaller corrected R → larger H²/R → larger TBW. Sanity check:
        assertThat(tight.tbwKg!!).isGreaterThan(baseline.tbwKg!!)
    }

    @Test
    fun footToFootCorrection_higherC_yieldsLowerTbw() {
        val baseline = subjectA(footToFoot = 1.10f)
        val loose = subjectA(footToFoot = 1.15f)
        assertThat(loose.tbwKg!!).isLessThan(baseline.tbwKg!!)
    }

    // ---------- hydration shift ----------

    @Test
    fun hydrationShift_bandRatioIgnoresLabelOrderAndRejectsBadContact() {
        assertThat(S400HydrationShift.bandRatio(460f, 510f)).isWithin(1e-6f).of(510f / 460f)
        assertThat(S400HydrationShift.bandRatio(510f, 460f)).isWithin(1e-6f).of(510f / 460f)
        assertThat(S400HydrationShift.bandRatio(null, 460f)).isNull()
        assertThat(S400HydrationShift.bandRatio(460f, 463f)).isNull()
    }

    @Test
    fun hydrationShift_isSignedAgainstThePreviousWeighing() {
        val base = 510f / 460f
        val lower = S400HydrationShift.shiftPct(500f, 460f, 510f, 460f)!!
        assertThat(lower).isWithin(1e-4f).of(((500f / 460f) / base - 1f) * 100f)
        assertThat(lower).isLessThan(0f)
        assertThat(S400HydrationShift.shiftPct(510f, 460f, null, null)).isNull()
    }

    @Test
    fun hydrationShift_warningThreshold() {
        assertThat(S400HydrationShift.isWarning(2.99f)).isFalse()
        assertThat(S400HydrationShift.isWarning(3.0f)).isTrue()
        assertThat(S400HydrationShift.isWarning(-3.5f)).isTrue()
    }

    @Test
    fun hydrationShift_repeatWeighingsStayFarBelowTheThreshold() {
        val shifts = repeats.zipWithNext { prev, cur ->
            S400HydrationShift.shiftPct(cur.r50, cur.r250, prev.r50, prev.r250)!!
        }
        for (s in shifts) {
            assertThat(abs(s)).isLessThan(0.5f)
            assertThat(S400HydrationShift.isWarning(s)).isFalse()
        }
    }

    // ---------- real S400 weighings ----------

    @Test
    fun realWeighings_noneIsRejectedOrSuppressed() {
        for (w in weighings) {
            val r = S400BodyComposition.compute(w.inputs)
            assertWithMessage(w.label).that(r.reliability).isNotEqualTo(Reliability.NOT_AVAILABLE)
            assertWithMessage(w.label).that(r.reliability).isNotEqualTo(Reliability.UNRELIABLE)
            assertWithMessage(w.label).that(r.tbwKg).isNotNull()
            assertWithMessage(w.label).that(r.ecwKg).isNotNull()
            assertWithMessage(w.label).that(r.icwKg).isNotNull()
            assertWithMessage(w.label).that(r.bcmKg).isNotNull()
            assertWithMessage(w.label).that(r.ecwTbwRatio!!).isIn(Range.closed(0.38f, 0.50f))
        }
    }

    @Test
    fun realWeighings_bodyFatMatchesPinnedValues() {
        val expected = listOf(30.04f, 27.95f, 39.94f, 23.49f, 33.76f, 27.35f, 23.28f, 15.49f, 29.77f, 33.05f, 35.59f)
        for ((w, bf) in weighings.zip(expected)) {
            assertWithMessage(w.label).that(S400BodyComposition.compute(w.inputs).bfPct!!).isWithin(tolPct).of(bf)
        }
    }

    @Test
    fun realWeighings_bandRatioFlagsSevenOfEleven() {
        // Foot-to-foot band ratios run below the NHANES hand-to-foot distribution;
        // see the §3.2b KDoc.
        val flagged = weighings.filter { S400BodyComposition.compute(it.inputs).reliability == Reliability.APPROXIMATE }
        assertThat(flagged.map { it.label }).containsExactly("M56", "M28", "M26", "M50", "M38", "M27", "F26")
    }

    @Test
    fun realWeighings_bodyFatAgreesWithTheFootToFootDxaEquation() {
        // Wu 2015 (Nutr J 14:52): 50 kHz foot-to-foot FFM calibrated on DXA,
        // n = 554, SEE 3.17 kg. Its individual error is several points, so only
        // the mean gap is bounded: -0.7 at a foot-to-foot factor of 1.00, +1.7 at 1.10.
        val gaps = weighings.map { w ->
            val h = w.heightCm.toDouble()
            val kg = w.weightKg.toDouble()
            val ffm = 13.055 + 0.204 * kg + 0.394 * h * h / w.r50 - 0.136 * w.age + (if (w.sexMale) 8.125 else 0.0)
            S400BodyComposition.compute(w.inputs).bfPct!! - (kg - ffm) / kg * 100.0
        }
        assertThat(abs(gaps.average())).isAtMost(1.5)
    }

    @Test
    fun realWeighings_repeatBodyFatIsStable() {
        // The Mi app's own SD over the same four weighings is 0.10 points.
        val bf = repeats.map { S400BodyComposition.compute(it.inputs).bfPct!!.toDouble() }
        val mean = bf.average()
        val sd = sqrt(bf.sumOf { (it - mean) * (it - mean) } / (bf.size - 1))
        assertThat(sd).isAtMost(0.5)
    }

    // ---------- helpers ----------

    private fun subjectA(
        boneFormula: BoneFormula = BoneFormula.MI_LEGACY,
        bmrFormula: BmrFormula = BmrFormula.CUNNINGHAM_1991,
        footToFoot: Float = S400BodyComposition.FOOT_TO_FOOT_CORRECTION,
    ) = S400BodyComposition.compute(
        S400Inputs(
            age = 27, sexMale = true, heightCm = 172f, weightKg = 76.5f,
            rHighRaw = 365f, rLowRaw = 402f,
        ),
        boneFormula = boneFormula,
        bmrFormula = bmrFormula,
        footToFootCorrection = footToFoot,
    )

    private class Weighing(
        val label: String,
        val sexMale: Boolean,
        val age: Int,
        val heightCm: Float,
        val weightKg: Float,
        val r50: Float,
        val r250: Float,
    ) {
        val inputs get() = S400Inputs(age, sexMale, heightCm, weightKg, rHighRaw = r250, rLowRaw = r50)
    }

    /**
     * Real S400 weighings shared publicly in
     * https://github.com/dckiller51/bodymiscale/issues/349, contributors
     * identified only by sex and age. There the entity `impedance_low` carries
     * the smaller magnitude, which is physically the 250 kHz band, so the bands
     * are mapped by magnitude: [Weighing.r50] is the larger value.
     */
    private val weighings = listOf(
        Weighing("M62", true, 62, 170f, 76.9f, 437.0f, 387.0f),
        Weighing("M56", true, 56, 170f, 67.9f, 509.1f, 457.8f),
        Weighing("M28", true, 28, 187f, 132.5f, 454.6f, 414.9f),
        Weighing("M48", true, 48, 183f, 79.1f, 458.6f, 408.5f),
        Weighing("M26", true, 26, 173f, 91.1f, 505.7f, 457.5f),
        Weighing("M50", true, 50, 180f, 83.3f, 451.6f, 407.9f),
        Weighing("M38", true, 38, 172f, 71.9f, 460.3f, 414.7f),
        Weighing("M30", true, 30, 171f, 56.0f, 568.2f, 485.8f),
        Weighing("F27", false, 27, 164f, 63.1f, 513.8f, 454.9f),
        Weighing("M27", true, 27, 178f, 88.0f, 570.0f, 510.6f),
        Weighing("F26", false, 26, 163f, 63.6f, 630.0f, 571.5f),
    )

    /** Four weighings of the M56 contributor on different days, from the same thread. */
    private val repeats = listOf(
        Weighing("M56-a", true, 56, 170f, 67.6f, 514.2f, 461.5f),
        Weighing("M56-b", true, 56, 170f, 67.7f, 512.6f, 459.7f),
        Weighing("M56-c", true, 56, 170f, 67.7f, 506.3f, 455.8f),
        weighings[1],
    )
}
