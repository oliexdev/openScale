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

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.Subject
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.mean
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.subjects
import org.junit.Test
import kotlin.math.abs

/**
 * [S400BodyComposition] at its defaults on the [S400ReferenceCohort] weighings,
 * which are real foot-to-foot readings.
 *
 * No reference measurement exists for these subjects, so body fat is compared
 * with Wu 2015 (Nutr J 14:52), the 50 kHz foot-to-foot equation calibrated on
 * DXA (n = 554, SEE 3.17 kg). Its individual error is several points, so only
 * the cohort mean is bounded: ±1.5 points holds at a foot-to-foot factor of 1.00
 * (-0.7) and fails at 1.10 (+1.7).
 *
 * The app reports no ECW or ICW, so the compartment split is only checked for
 * plausibility against De Lorenzo's dilution cohort (0.40-0.42, p. 1547).
 */
class S400ReferenceCohortTest {

    private val results = subjects.map { it to S400BodyComposition.compute(it.inputs) }

    private fun wuBfPct(s: Subject): Double {
        val h = s.heightCm.toDouble()
        val w = s.weightKg.toDouble()
        val ffm = 13.055 + 0.204 * w + 0.394 * h * h / s.r50 - 0.136 * s.age + 8.125 * (if (s.sexMale) 1.0 else 0.0)
        return (w - ffm) / w * 100.0
    }

    private fun deltas(select: (Subject, S400Result) -> Double) =
        results.joinToString { (s, r) -> "%s %+.1f".format(s.label, select(s, r)) }

    @Test
    fun noSubjectIsRejectedOrSuppressed() {
        for ((s, r) in results) {
            assertWithMessage(s.label).that(r.reliability).isNotEqualTo(Reliability.NOT_AVAILABLE)
            assertWithMessage(s.label).that(r.reliability).isNotEqualTo(Reliability.UNRELIABLE)
            assertWithMessage(s.label).that(r.tbwKg).isNotNull()
            assertWithMessage(s.label).that(r.ecwKg).isNotNull()
            assertWithMessage(s.label).that(r.icwKg).isNotNull()
            assertWithMessage(s.label).that(r.bcmKg).isNotNull()
        }
    }

    @Test
    fun bodyFatAgreesWithTheFootToFootDxaEquation() {
        val gap = results.map { (s, r) -> r.bfPct!! - wuBfPct(s) }
        val detail = deltas { s, r -> r.bfPct!! - wuBfPct(s) }
        assertWithMessage("mean gap to Wu 2015, pts: $detail").that(abs(mean(gap))).isAtMost(1.5)
    }

    @Test
    fun ecwTbwStaysPlausible() {
        for ((s, r) in results) {
            assertWithMessage(s.label).that(r.ecwTbwRatio!!).isIn(Range.closed(0.38f, 0.50f))
        }
    }

    @Test
    fun bandRatioFlagCount() {
        // Foot-to-foot band ratios run below the NHANES hand-to-foot distribution;
        // see the §3.2b KDoc.
        val flagged = results.count { (_, r) -> r.reliability == Reliability.APPROXIMATE }
        assertWithMessage(deltas { _, r -> r.r0RinfRatio!!.toDouble() }).that(flagged).isEqualTo(7)
    }
}
