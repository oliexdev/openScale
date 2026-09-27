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

import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.mean
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.pearson
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.rmse
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.sd
import com.health.openscale.core.bluetooth.libs.S400NhanesFixture.Row
import com.health.openscale.core.data.GenderType
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * [S400BodyComposition] against DXA on NHANES 1999-2004 adults 18-49 (public
 * domain; see [S400NhanesFixture]). NHANES measures right hand to right foot with a Xitron spectroscope,
 * so the pipeline runs with a foot-to-foot factor of 1.00 on native wrist-ankle
 * magnitudes: this validates the equations, not the S400's electrode path.
 * Foot-to-foot equations (Wu 2015, bodymiscale) are scored off their path and
 * marked `*`.
 */
class S400NhanesValidationTest {

    private val rows = S400NhanesFixture.rows

    private val results by lazy { rows.map { S400BodyComposition.compute(it.inputs) } }

    private fun sunBf(r: Row, z: Float): Double {
        val h = r.h.toDouble()
        val w = r.w.toDouble()
        val tbw = if (r.male) 1.20 + 0.45 * h * h / z + 0.18 * w else 3.75 + 0.45 * h * h / z + 0.11 * w
        return (w - tbw / 0.732) / w * 100.0
    }

    private fun ffmBf(r: Row, ffm: Double) = (r.w - ffm) / r.w * 100.0

    private val pipeline = "1F Deurenberg 1991 BIA @50 kHz (pipeline)"
    private val master = "1F Sun 2003 @250 kHz x1.10"
    private val sun = "1F Sun 2003 @50 kHz x1.00"
    private val hanai = "2F Hanai/Matthie TBW (Cole-inverted)"

    private val methods: Map<String, (Int) -> Double?> by lazy {
        linkedMapOf(
            master to { i -> sunBf(rows[i], rows[i].z250 * 1.10f) },
            "1F Sun 2003 @50 kHz x1.10" to { i -> sunBf(rows[i], rows[i].z50 * 1.10f) },
            sun to { i -> sunBf(rows[i], rows[i].z50) },
            pipeline to { i -> results[i].bfPct?.toDouble() },
            // Wu 2015 Nutr J 14:52, foot-to-foot 50 kHz against DXA.
            "1F Wu 2015 foot-to-foot vs DXA @50 kHz *" to { i ->
                val r = rows[i]
                ffmBf(r, 13.055 + 0.204 * r.w + 0.394 * r.h * r.h / r.z50 - 0.136 * r.age + 8.125 * (if (r.male) 1 else 0))
            },
            hanai to { i ->
                val res = results[i]
                val tbw = res.tbwKg
                val delta = res.tbwCrossCheckDelta
                if (tbw == null || delta == null) null else ffmBf(rows[i], tbw * (1.0 + delta) / 0.732)
            },
            "1F bodymiscale S400 (Xiaomi LBM @50 kHz) *" to { i ->
                val r = rows[i]
                val lib = BodyMiScaleLib(if (r.male) GenderType.MALE else GenderType.FEMALE, r.age, r.h)
                lib.getFat(r.w, lib.getLbm(r.w, r.z50)).toDouble()
            },
            // Deurenberg 1991 Br J Nutr 65:105, BMI equation; no impedance.
            "0F Deurenberg 1991 BMI" to { i ->
                val r = rows[i]
                1.20 * r.w / (r.h / 100.0).pow(2) + 0.23 * r.age - (if (r.male) 10.8 else 0.0) - 5.4
            },
        )
    }

    private class Score(val bias: Double, val sd: Double, val rmse: Double, val r: Double, val n: Int)

    private fun score(method: (Int) -> Double?): Score {
        val pairs = rows.indices.mapNotNull { i -> method(i)?.let { it to rows[i].bfDxa } }
        val err = pairs.map { it.first - it.second }
        return Score(mean(err), sd(err), rmse(err), pearson(pairs.map { it.first }, pairs.map { it.second }), pairs.size)
    }

    @Test
    fun bodyFatAgainstDxa() {
        val scores = methods.mapValues { score(it.value) }
        println("Body fat vs DXA (pts), NHANES n = ${rows.size}")
        println("%-44s %6s %6s %6s %6s %6s".format("", "bias", "SD", "RMSE", "r", "n"))
        for ((name, s) in scores) {
            println("%-44s %+6.1f %6.1f %6.1f %6.2f %6d".format(name, s.bias, s.sd, s.rmse, s.r, s.n))
        }

        val p = scores.getValue(pipeline)
        assertWithMessage("pipeline rows withheld").that(p.n.toDouble() / rows.size).isAtLeast(0.99)
        assertWithMessage("pipeline bias").that(abs(p.bias)).isAtMost(1.0)
        assertWithMessage("pipeline RMSE").that(p.rmse).isAtMost(3.6)
        for (other in listOf(master, sun, hanai)) {
            assertWithMessage("pipeline RMSE vs $other").that(p.rmse).isLessThan(scores.getValue(other).rmse)
        }
    }

    @Test
    fun twoBandEcwTracksTheFullSpectrumFit() {
        // Eq. B2 on the two-band R_0 against Eq. B2 on Xitron's fitted R_E, on the
        // 2003-04 cycle that the Cole constants were not taken from.
        val rel = rows.indices.filter { rows[it].cycle == "C" }.mapNotNull { i ->
            val r = rows[i]
            val ecw = results[i].ecwKg ?: return@mapNotNull null
            val kEcw = if (r.male) 0.306 else 0.316
            val reference = kEcw * (r.h * r.h * sqrt(r.w.toDouble()) / r.re).pow(2.0 / 3.0)
            (ecw / reference - 1.0) * 100.0
        }
        println("ECW vs full-spectrum R_E, 2003-04: bias %+.1f %%, SD %.1f %%, n %d".format(mean(rel), sd(rel), rel.size))
        assertWithMessage("ECW bias, %").that(abs(mean(rel))).isAtMost(2.0)
        assertWithMessage("ECW SD, %").that(sd(rel)).isAtMost(4.0)
    }

    @Test
    fun bandRatioFlagRateIsModestInHealthyAdults() {
        val c = rows.indices.filter { rows[it].cycle == "C" && results[it].r0RinfRatio != null }
        val flagged = c.count { results[it].reliability == Reliability.APPROXIMATE }.toDouble() / c.size
        println("Band-ratio flag rate, 2003-04: %.1f %%".format(flagged * 100))
        assertWithMessage("flag rate").that(flagged).isAtMost(0.10)
    }
}
