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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.mean
import org.junit.Test
import kotlin.math.abs

/**
 * [S400HydrationShift]. The noise check uses the four repeat weighings of one S400
 * user in [S400ReferenceCohort]. The physiology checks use NHANES
 * ([S400NhanesFixture]), which has one weighing per person: they establish how
 * the band ratio relates to water distribution and to body-fat error across
 * people, the relation the warning assumes holds within one person over time.
 */
class S400HydrationShiftTest {

    @Test
    fun bandRatioIgnoresLabelOrderAndRejectsBadContact() {
        assertThat(S400HydrationShift.bandRatio(460f, 510f)).isWithin(1e-6f).of(510f / 460f)
        assertThat(S400HydrationShift.bandRatio(510f, 460f)).isWithin(1e-6f).of(510f / 460f)
        assertThat(S400HydrationShift.bandRatio(null, 460f)).isNull()
        assertThat(S400HydrationShift.bandRatio(460f, 463f)).isNull()
    }

    @Test
    fun shiftIsSignedAgainstThePreviousWeighing() {
        val base = 510f / 460f
        val lower = S400HydrationShift.shiftPct(500f, 460f, 510f, 460f)!!
        assertThat(lower).isWithin(1e-4f).of(((500f / 460f) / base - 1f) * 100f)
        assertThat(lower).isLessThan(0f)
        assertThat(S400HydrationShift.shiftPct(510f, 460f, null, null)).isNull()
    }

    @Test
    fun warningThreshold() {
        assertThat(S400HydrationShift.isWarning(2.99f)).isFalse()
        assertThat(S400HydrationShift.isWarning(3.0f)).isTrue()
        assertThat(S400HydrationShift.isWarning(-3.5f)).isTrue()
    }

    @Test
    fun repeatWeighingsStayFarBelowTheThreshold() {
        val shifts = S400ReferenceCohort.repeats.zipWithNext { prev, cur ->
            S400HydrationShift.shiftPct(cur.r50, cur.r250, prev.r50, prev.r250)!!
        }
        println("Repeat-weighing shifts, %: " + shifts.joinToString { "%+.2f".format(it) })
        for (s in shifts) {
            assertThat(abs(s)).isLessThan(0.5f)
            assertThat(S400HydrationShift.isWarning(s)).isFalse()
        }
    }

    private fun slope(x: List<Double>, y: List<Double>): Double {
        val mx = mean(x)
        val my = mean(y)
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / x.sumOf { (it - mx) * (it - mx) }
    }

    @Test
    fun thresholdMatchesAboutOnePointOfBodyFatAndAWaterShift() {
        for (male in listOf(true, false)) {
            val rows = S400NhanesFixture.rows
                .filter { it.male == male && it.ecf != null && it.tbw != null }
                .mapNotNull { row -> S400BodyComposition.compute(row.inputs).bfPct?.let { row to it } }
            val ratio = rows.map { (it.first.z50 / it.first.z250).toDouble() }
            val ecwTbw = rows.map { it.first.ecf!!.toDouble() / it.first.tbw!! }
            val bfError = rows.map { it.second - it.first.bfDxa }
            val atThreshold = mean(ratio) * S400HydrationShift.WARNING_THRESHOLD_PCT / 100.0
            val r = S400ReferenceCohort.pearson(ratio, ecwTbw)
            val ecwShift = slope(ratio, ecwTbw) * atThreshold
            val bfShift = slope(ratio, bfError) * atThreshold
            println("%s n %d: r(ratio, ECW/TBW) %.2f; a 3 %% shift = ECW/TBW %+.3f, body fat %+.2f pts"
                .format(if (male) "men" else "women", rows.size, r, ecwShift, bfShift))
            assertWithMessage("correlation").that(r).isAtMost(-0.8)
            assertWithMessage("ECW/TBW change at the threshold").that(ecwShift).isAtMost(-0.04)
            assertWithMessage("body-fat bias at the threshold").that(bfShift).isAtLeast(0.8)
        }
    }
}
