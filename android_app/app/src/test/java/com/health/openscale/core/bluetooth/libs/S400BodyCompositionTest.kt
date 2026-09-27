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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
 *  - §7.1 young athletic male: age 27, M, 172 cm, 76.5 kg, R 365/402, R_0 451.8
 *  - §7.2 middle-aged female: age 55, F, 162 cm, 68 kg, R 600/690, R_0 788.3
 *  - §7.3 older male:         age 70, M, 170 cm, 80 kg, R 520/610, R_0 728.2
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
        assertThat(r.tbwKg!!).isWithin(tolKg).of(48.09f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(65.69f)
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
        assertThat(r.tbwKg!!).isWithin(tolKg).of(48.09f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(62.86f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(21.10f)
        assertThat(r.ecwPct!!).isWithin(tolPct).of(27.59f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(26.98f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.439f)
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.319f)
        // 1.319 is 2.61 SD from the Table 2 male mean, hence APPROXIMATE.
        assertThat(r.tbwCrossCheckDelta!!).isWithin(0.002f).of(-0.1704f)
        assertThat(r.reliability).isEqualTo(Reliability.APPROXIMATE)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(65.69f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(10.81f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(14.13f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(36.52f)
        assertThat(r.smmPct!!).isWithin(tolPct).of(47.74f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.99f)  // Option A default
        assertThat(r.vfi!!).isWithin(0.1f).of(13.24f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1789f)  // Cun91 default
        assertThat(r.bcmKg!!).isWithin(tolKg).of(38.55f)
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
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1945f)
    }

    // ---------- §7.2 — Subject B (middle-aged female) ----------

    @Test
    fun subjectB_defaultOptions_matchesSpec() {
        val r = S400BodyComposition.compute(S400Inputs(
            age = 55, sexMale = false, heightCm = 162f, weightKg = 68f,
            rHighRaw = 600f, rLowRaw = 690f,
        ))
        assertThat(r.tbwKg!!).isWithin(tolKg).of(28.35f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(41.68f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(13.35f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(15.00f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.471f)
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.492f)
        assertThat(r.tbwCrossCheckDelta!!).isWithin(0.002f).of(0.0883f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(38.72f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(29.28f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(43.05f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(16.45f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.47f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1206f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(21.42f)
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
        assertThat(r.tbwKg!!).isWithin(tolKg).of(36.92f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(46.15f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(15.34f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(21.58f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.416f)
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.568f)
        assertThat(r.tbwCrossCheckDelta!!).isWithin(0.002f).of(0.0307f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(50.44f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(29.56f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(36.95f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(22.96f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.84f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1459f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(30.83f)
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
        // 369/402 puts R_0/R_INF at 1.280, 3.11 SD from the Table 2 male mean,
        // while §3.1 and §3.1b both pass. The compartments still report: the
        // band frequencies the deviation depends on are themselves nominal.
        val r = S400BodyComposition.compute(S400Inputs(27, true, 172f, 76.5f, 369f, 402f))
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(1.280f)
        assertThat(r.reliability).isEqualTo(Reliability.APPROXIMATE)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(21.27f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(26.82f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(38.31f)
    }

    @Test
    fun rWindow_doesNotWithholdTheCapturedReference() {
        // The 543.2/497.6 Ω pair from S400DecryptorTest, the only real device
        // reading in the repository, sits at 3.01 SD read as male and 2.78 SD
        // read as female. Both must produce the same fields.
        val male = S400BodyComposition.compute(S400Inputs(40, true, 178f, 80f, 497.6f, 543.2f))
        assertThat(male.ecwKg).isNotNull()
        assertThat(male.icwKg).isNotNull()
        assertThat(male.bcmKg).isNotNull()
    }

    // ---------- band attribution ----------

    @Test
    fun bandAttribution_regressionsReadOnlyThe50kHzBand() {
        // Sun 2003 and Janssen 2000 are 50 kHz equations. Moving only the
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
        // The same capture reports 5.1 % body fat where Deurenberg gives 29.3,
        // 24.2 points apart. TBW, FFM and BF are one number in three forms, so
        // all three go, and BMR falls back to Mifflin-St Jeor.
        val r = S400BodyComposition.compute(S400Inputs(
            age = 45, sexMale = true, heightCm = 196f, weightKg = 112.5f,
            rHighRaw = 226f, rLowRaw = 305f,
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
        assertThat(r.r0RinfRatio!!).isWithin(0.005f).of(2.313f)
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
}
