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
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.mean
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.rmse
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.subjects
import org.junit.Test
import kotlin.math.abs

/**
 * [S400BodyComposition] at its defaults against the [S400ReferenceCohort]
 * weighings. The Mi app is the only per-subject reference available, so the
 * bounds below state agreement with it, not accuracy.
 *
 * Body fat and fat-free mass are compared directly. The app reports no ECW or
 * ICW, so the compartment split is only checked for plausibility: every subject
 * must stay inside the §3.3 window and within [0.40, 0.50], against 0.40-0.42 in
 * De Lorenzo's dilution cohort (p. 1547). The cohort sits at the top of that.
 */
class S400ReferenceCohortTest {

    private val results = subjects.map { it to S400BodyComposition.compute(it.inputs) }

    private fun deltas(select: (S400ReferenceCohort.Subject, S400Result) -> Double) =
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
    fun bodyFatAgreesWithTheApp() {
        val err = results.map { (s, r) -> (r.bfPct!! - s.appBfPct).toDouble() }
        val detail = deltas { s, r -> (r.bfPct!! - s.appBfPct).toDouble() }
        assertWithMessage("mean bias, pts: $detail").that(abs(mean(err))).isAtMost(2.0)
        assertWithMessage("RMSE, pts: $detail").that(rmse(err)).isAtMost(5.0)
    }

    @Test
    fun fatFreeMassAgreesWithTheApp() {
        val err = results.map { (s, r) -> (r.ffmKg!! - s.appFfmKg).toDouble() }
        val detail = deltas { s, r -> (r.ffmKg!! - s.appFfmKg).toDouble() }
        assertWithMessage("mean bias, kg: $detail").that(abs(mean(err))).isAtMost(2.0)
    }

    @Test
    fun ecwTbwStaysPlausible() {
        for ((s, r) in results) {
            assertWithMessage(s.label).that(r.ecwTbwRatio!!).isIn(Range.closed(0.40f, 0.50f))
        }
    }

    @Test
    fun bandRatioFlagCount() {
        // Real captures sit below the Table 2 R_0/R_INF means; see the §3.2b KDoc.
        val flagged = results.count { (_, r) -> r.reliability == Reliability.APPROXIMATE }
        assertWithMessage(deltas { _, r -> r.r0RinfRatio!!.toDouble() }).that(flagged).isEqualTo(6)
    }
}
