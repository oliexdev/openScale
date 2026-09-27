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
import org.junit.Test
import kotlin.math.abs

/**
 * What bounds [S400BodyComposition.FOOT_TO_FOOT_CORRECTION]. Both fixtures are
 * CC BY 4.0 extracts; `src/test/resources/s400/make_foot_to_foot_fixtures.py`
 * gives the sources and the changes made.
 *
 *  - `seca_segments.csv` (204 Colombian adults, eight-electrode, supine): the
 *    hand-to-foot path against the sum of the two legs on the same people, the
 *    physical conversion a foot-to-foot scale would need. The leg sum leaves out
 *    the pelvis, so the ratio is an upper bound.
 *  - `tanita_uww.csv` (69 adolescent sprinters, Tanita TBF-410 foot to foot,
 *    underwater weighing): the factor at which the §3.1 equation reads real
 *    foot-to-foot impedance without bias. Age is not given per subject; 16 is
 *    used, the youngest age the equation covers, and each year moves FFM by
 *    0.127 kg.
 */
class S400FootToFootTest {

    private fun csv(name: String): List<List<String>> =
        javaClass.getResourceAsStream("/s400/$name")!!.bufferedReader().useLines { lines ->
            lines.drop(1).map { it.split(',') }.toList()
        }

    @Test
    fun handToFootIsAboutOneFourteenthAboveTheLegSum() {
        val rows = csv("seca_segments.csv").map { r -> r.drop(3).map(String::toDouble) }
        // Columns: ll50, rl50, lb50, rb50, ll200, rl200, lb200, rb200.
        val pathRatio = mean(rows.map { (it[2] + it[3]) / 2 / (it[0] + it[1]) })
        val legBandRatio = mean(rows.map { (it[0] + it[1]) / (it[4] + it[5]) })
        val sideBandRatio = mean(rows.map { (it[2] + it[3]) / (it[6] + it[7]) })
        println("seca n %d: hand-to-foot / leg sum at 50 kHz %.3f; R50/R200 legs %.4f, sides %.4f"
            .format(rows.size, pathRatio, legBandRatio, sideBandRatio))
        assertWithMessage("path ratio").that(pathRatio).isIn(Range.closed(1.10, 1.18))
        assertWithMessage("band ratio is path-independent")
            .that(abs(legBandRatio / sideBandRatio - 1.0)).isLessThan(0.01)
    }

    private class Weighing(val male: Boolean, val h: Double, val w: Double, val z: Double, val bfUww: Double)

    private val tanita by lazy {
        csv("tanita_uww.csv").map { r ->
            Weighing(r[2] == "1", r[3].toDouble(), r[4].toDouble(), r[5].toDouble(), r[7].toDouble())
        }
    }

    /** The §3.1 Deurenberg 1991 equation at [factor], body-fat error against underwater weighing. */
    private fun errors(factor: Double, age: Int = 16) = tanita.map {
        val ffm = 0.340 * it.h * it.h / (it.z * factor) + 15.34 * it.h / 100 + 0.273 * it.w -
            0.127 * age + 4.56 * (if (it.male) 1 else 0) - 12.44
        (it.w - ffm) / it.w * 100 - it.bfUww
    }

    @Test
    fun realFootToFootImpedanceNeedsNoUpwardCorrection() {
        var lo = 0.7
        var hi = 1.5
        repeat(60) {
            val mid = (lo + hi) / 2
            if (mean(errors(mid)) < 0) lo = mid else hi = mid
        }
        val zeroBias = (lo + hi) / 2
        for (f in listOf(0.95, 1.0, 1.1)) {
            val e = errors(f)
            println("Tanita n %d, factor %.2f: bias %+.2f, RMSE %.2f".format(e.size, f, mean(e), rmse(e)))
        }
        println("zero-bias factor %.3f".format(zeroBias))
        assertWithMessage("zero-bias factor").that(zeroBias).isIn(Range.closed(0.90, 1.05))
        val atDefault = mean(errors(S400BodyComposition.FOOT_TO_FOOT_CORRECTION.toDouble()))
        assertWithMessage("bias at the default factor").that(abs(atDefault)).isAtMost(2.5)
    }
}
