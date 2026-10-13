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
 * Tests for [S400BodyComposition] — Holtek libBodyfat.so parity + dual-freq.
 *
 * Core metrics (fat, water, bone, muscle, VFI, BMR, protein, metabolic age,
 * ideal weight) verified against Holtek formulas. ECW/ICW/BCM verified against
 * Hanai mixture theory using R_low.
 *
 * Reference subjects:
 *  - Subject A: age 27, M, 172 cm, 76.5 kg, R 365/402
 *  - Subject B: age 55, F, 162 cm, 68 kg, R 600/690
 *  - Subject C: age 70, M, 170 cm, 80 kg, R 520/610
 */
class S400BodyCompositionTest {

    private val tolKg = 0.05f
    private val tolPct = 0.1f
    private val tolKcal = 5f
    private val tolVfi = 0.1f
    private val tolRatio = 0.005f

    // ---------- Label swap ----------

    @Test
    fun labelSwap_appliedWhenRLowBelowRHigh() {
        val swapped = S400Inputs(
            age = 27, sexMale = true, heightCm = 172f, weightKg = 76.5f,
            rHighRaw = 402f, rLowRaw = 365f,
        )
        val r = S400BodyComposition.compute(swapped)
        assertThat(r.labelSwapApplied).isTrue()
        assertThat(r.reliability).isEqualTo(Reliability.APPROXIMATE)
        // After swap, R_high=365 — same as Subject A.
        assertThat(r.bfPct!!).isWithin(tolPct).of(23.2f)
        assertThat(r.tbwKg!!).isWithin(tolKg).of(40.31f)
    }

    @Test
    fun labelSwap_notAppliedWhenAlreadyCorrect() {
        val correct = S400Inputs(
            age = 27, sexMale = true, heightCm = 172f, weightKg = 76.5f,
            rHighRaw = 365f, rLowRaw = 402f,
        )
        val r = S400BodyComposition.compute(correct)
        assertThat(r.labelSwapApplied).isFalse()
        assertThat(r.reliability).isEqualTo(Reliability.OK)
    }

    // ---------- Unreliable contact ----------

    @Test
    fun unreliable_whenRLowAndRHighWithinOnePercent() {
        val edge = S400Inputs(
            age = 30, sexMale = true, heightCm = 180f, weightKg = 85f,
            rHighRaw = 395f, rLowRaw = 398f,
        )
        val r = S400BodyComposition.compute(edge)
        assertThat(r.reliability).isEqualTo(Reliability.UNRELIABLE)
        assertThat(r.bfPct).isNull()
        assertThat(r.tbwKg).isNull()
        assertThat(r.ecwKg).isNull()
        assertThat(r.icwKg).isNull()
        assertThat(r.bcmKg).isNull()
        assertThat(r.boneKg).isNull()
        assertThat(r.smmKg).isNull()
        // Weight and BMI must still be reported.
        assertThat(r.weightKg).isEqualTo(85f)
        assertThat(r.bmi).isWithin(0.1f).of(26.23f)
    }

    // ---------- Subject A (young athletic male) ----------

    @Test
    fun subjectA_matchesHoltekAndDualFreqValues() {
        val r = subjectA()
        assertThat(r.reliability).isEqualTo(Reliability.OK)
        // Holtek core
        assertThat(r.bmi).isWithin(0.01f).of(25.86f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(23.2f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(17.74f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(52.7f)
        assertThat(r.tbwKg!!).isWithin(tolKg).of(40.31f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.99f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(55.77f)
        assertThat(r.smmPct!!).isWithin(tolPct).of(72.9f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(58.76f)
        assertThat(r.ffmPct!!).isWithin(tolPct).of(76.8f)
        assertThat(r.vfi!!).isWithin(tolVfi).of(10.8f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1512f)
        assertThat(r.metabolicAge!!).isEqualTo(25)
        assertThat(r.proteinPct!!).isWithin(tolPct).of(20.2f)
        assertThat(r.idealWeightKg!!).isWithin(0.01f).of(64.4f)
        // Dual-frequency extension
        assertThat(r.ecwKg!!).isWithin(tolKg).of(21.47f)
        assertThat(r.ecwPct!!).isWithin(tolPct).of(28.1f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(18.84f)
        assertThat(r.icwPct!!).isWithin(tolPct).of(24.6f)
        assertThat(r.ecwTbwRatio!!).isWithin(tolRatio).of(0.533f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(26.92f)
        // Phase angle not derivable
        assertThat(r.phaseAngleDeg).isNull()
    }

    // ---------- Subject B (middle-aged female) ----------

    @Test
    fun subjectB_matchesHoltekAndDualFreqValues() {
        val r = S400BodyComposition.compute(S400Inputs(
            age = 55, sexMale = false, heightCm = 162f, weightKg = 68f,
            rHighRaw = 600f, rLowRaw = 690f,
        ))
        assertThat(r.reliability).isEqualTo(Reliability.OK)
        // Holtek core
        assertThat(r.bmi).isWithin(0.01f).of(25.91f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(36.8f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(25.04f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(45.1f)
        assertThat(r.tbwKg!!).isWithin(tolKg).of(30.67f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.47f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(40.49f)
        assertThat(r.smmPct!!).isWithin(tolPct).of(59.5f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(42.96f)
        assertThat(r.vfi!!).isWithin(tolVfi).of(1.0f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(981f)
        assertThat(r.metabolicAge!!).isEqualTo(59)
        assertThat(r.proteinPct!!).isWithin(tolPct).of(14.4f)
        assertThat(r.idealWeightKg!!).isWithin(0.01f).of(55.2f)
        // Dual-frequency extension
        assertThat(r.ecwKg!!).isWithin(tolKg).of(12.96f)
        assertThat(r.ecwPct!!).isWithin(tolPct).of(19.1f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(17.71f)
        assertThat(r.icwPct!!).isWithin(tolPct).of(26.0f)
        assertThat(r.ecwTbwRatio!!).isWithin(tolRatio).of(0.423f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(25.30f)
    }

    // ---------- Subject C (older male) ----------

    @Test
    fun subjectC_matchesHoltekAndDualFreqValues() {
        val r = S400BodyComposition.compute(S400Inputs(
            age = 70, sexMale = true, heightCm = 170f, weightKg = 80f,
            rHighRaw = 520f, rLowRaw = 610f,
        ))
        assertThat(r.reliability).isEqualTo(Reliability.OK)
        // Holtek core
        assertThat(r.bmi).isWithin(0.01f).of(27.68f)
        assertThat(r.bfPct!!).isWithin(tolPct).of(30.2f)
        assertThat(r.bfKg!!).isWithin(tolKg).of(24.13f)
        assertThat(r.tbwPct!!).isWithin(tolPct).of(49.9f)
        assertThat(r.tbwKg!!).isWithin(tolKg).of(39.89f)
        assertThat(r.boneKg!!).isWithin(tolKg).of(2.84f)
        assertThat(r.smmKg!!).isWithin(tolKg).of(53.03f)
        assertThat(r.smmPct!!).isWithin(tolPct).of(66.3f)
        assertThat(r.ffmKg!!).isWithin(tolKg).of(55.87f)
        assertThat(r.vfi!!).isWithin(tolVfi).of(17.9f)
        assertThat(r.bmrKcal!!).isWithin(tolKcal).of(1065f)
        assertThat(r.metabolicAge!!).isEqualTo(56)
        assertThat(r.proteinPct!!).isWithin(tolPct).of(16.4f)
        assertThat(r.idealWeightKg!!).isWithin(0.01f).of(63.0f)
        // Dual-frequency extension
        assertThat(r.ecwKg!!).isWithin(tolKg).of(16.24f)
        assertThat(r.ecwPct!!).isWithin(tolPct).of(20.3f)
        assertThat(r.icwKg!!).isWithin(tolKg).of(23.65f)
        assertThat(r.icwPct!!).isWithin(tolPct).of(29.6f)
        assertThat(r.ecwTbwRatio!!).isWithin(tolRatio).of(0.407f)
        assertThat(r.bcmKg!!).isWithin(tolKg).of(33.79f)
    }

    // ---------- Input validation ----------

    @Test
    fun rejectsUnderageSubject() {
        val r = S400BodyComposition.compute(S400Inputs(5, true, 140f, 35f, 500f, 600f))
        assertThat(r.reliability).isEqualTo(Reliability.NOT_AVAILABLE)
        assertThat(r.bfPct).isNull()
        assertThat(r.weightKg).isEqualTo(35f)
    }

    @Test
    fun rejectsOverweightSubject() {
        val r = S400BodyComposition.compute(S400Inputs(40, true, 175f, 201f, 500f, 600f))
        assertThat(r.reliability).isEqualTo(Reliability.NOT_AVAILABLE)
    }

    @Test
    fun rejectsOutOfRangeImpedance() {
        val r = S400BodyComposition.compute(S400Inputs(30, true, 175f, 75f, 50f, 600f))
        assertThat(r.reliability).isEqualTo(Reliability.NOT_AVAILABLE)
    }

    // ---------- Visceral fat bug fix verification ----------

    @Test
    fun vfi_femaleElseBranch_uses10_5_notAge() {
        // Construct a case that hits the female else branch.
        // h=180, w=50: threshold = -(13-90) = 77, w=50 < 77 → else branch.
        val r = S400BodyComposition.compute(S400Inputs(
            age = 30, sexMale = false, heightCm = 180f, weightKg = 50f,
            rHighRaw = 500f, rLowRaw = 550f,
        ))
        assertThat(r.vfi!!).isAtLeast(1.0f)
        assertThat(r.vfi!!).isAtMost(50.0f)
    }

    // ---------- helpers ----------

    private fun subjectA() = S400BodyComposition.compute(
        S400Inputs(
            age = 27, sexMale = true, heightCm = 172f, weightKg = 76.5f,
            rHighRaw = 365f, rLowRaw = 402f,
        )
    )
}