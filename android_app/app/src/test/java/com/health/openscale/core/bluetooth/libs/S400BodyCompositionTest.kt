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
 * [S400BodyComposition]; vectors below are the spec's reference subjects.
 *
 * Edge cases come first (§7.4 label swap, §7.5 unreliable contact) — cheapest
 * defensive checks to ship; they cover the two failure modes most likely to
 * break the model in production.
 *
 * Reference subjects (computed with `c = 1.10`, MI_LEGACY bone, CUN91 BMR):
 *  - §7.1 young athletic male: age 27, M, 172 cm, 76.5 kg, R 365/402
 *  - §7.2 middle-aged female: age 55, F, 162 cm, 68 kg, R 600/690
 *  - §7.3 older male:         age 70, M, 170 cm, 80 kg, R 520/610
 */
class S400BodyCompositionTest {

    private val tolKg = 0.05f
    private val tolPct = 0.1f
    private val tolKcal = 5f

    // ---------- §7.4 — label swap ----------

    @Test
    fun labelSwap_appliedWhenRLowBelowRHigh() {
        // Subject A inputs but with bodyRes/bodyRes2 swapped (real-world
        // failure mode observed in captured advertisements).
        val swapped = S400Inputs(
            age = 27, sexMale = true, heightCm = 172f, weightKg = 76.5f,
            rHighRaw = 402f, rLowRaw = 365f,
        )
        val r = S400BodyComposition.compute(swapped)
        assertThat(r.labelSwapApplied).isTrue()
        // After swap, result must equal §7.1 Subject A.
        assertThat(r.tbwKg!!).isWithin(tolKg).of(48.13f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(65.75f)
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
        // Weight and BMI must still be reported.
        assertThat(r.weightKg).isEqualTo(85f)
        assertThat(r.bmi).isWithin(0.1f).of(26.23f)
    }

    // ---------- §7.1 — Subject A (young athletic male) ----------

    @Test
    fun subjectA_defaultOptions_matchesSpec() {
        val r = subjectA()
        assertThat(r.reliability).isAnyOf(Reliability.OK, Reliability.APPROXIMATE)
        assertThat(r.tbwKg!!).isWithin(tolKg).of(48.13f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(62.9f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(21.47f)
        assertThat(r.ecwPct!!).isWithin(tolPct).of(28.1f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(26.66f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.446f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(65.75f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(10.75f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(14.1f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(36.56f)
        assertThat(r.smmPct!!).isWithin(tolPct).of(47.8f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.99f)  // Option A default
        assertThat(r.vfi!!).isWithin(0.1f).of(13.2f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1790f)  // Cun91 default
        assertThat(r.bcmKg!!).isWithin(tolKg).of(38.09f)
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
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1946f)
    }

    // ---------- §7.2 — Subject B (middle-aged female) ----------

    @Test
    fun subjectB_defaultOptions_matchesSpec() {
        val r = S400BodyComposition.compute(S400Inputs(
            age = 55, sexMale = false, heightCm = 162f, weightKg = 68f,
            rHighRaw = 600f, rLowRaw = 690f,
        ))
        assertThat(r.tbwKg!!).isWithin(tolKg).of(29.12f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(42.8f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(12.96f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(16.16f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.445f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(39.79f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(28.21f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(41.5f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(17.14f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.47f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1229f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(23.09f)
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
        assertThat(r.tbwKg!!).isWithin(tolKg).of(38.34f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(47.9f)
        assertThat(r.ecwKg!!).isWithin(tolKg).of(16.24f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(22.09f)
        assertThat(r.ecwTbwRatio!!).isWithin(0.005f).of(0.424f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(52.37f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(27.63f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(34.5f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(24.22f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.84f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1501f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(31.56f)
    }

    @Test
    fun subjectC_boneOptionB_matchesSpec() {
        val r = S400BodyComposition.compute(
            S400Inputs(70, true, 170f, 80f, 520f, 610f),
            boneFormula = BoneFormula.HEYMSFIELD,
        )
        assertThat(r.boneKg!!).isWithin(tolKg).of(3.28f)
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
