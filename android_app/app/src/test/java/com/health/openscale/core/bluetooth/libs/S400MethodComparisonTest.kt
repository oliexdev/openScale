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
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.Subject
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.mean
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.pearson
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.repeats
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.rmse
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.sd
import com.health.openscale.core.bluetooth.libs.S400ReferenceCohort.subjects
import com.health.openscale.core.data.GenderType
import org.junit.Test
import kotlin.math.abs

/**
 * Body fat from single-frequency, two-frequency and impedance-free methods,
 * scored against the Mi app on the [S400ReferenceCohort] weighings. `repSD` is
 * the within-subject SD over the four repeat weighings, where the app's own is
 * 0.10. The table is printed for review; the assertions hold only orderings
 * that do not depend on the app being right.
 */
class S400MethodComparisonTest {

    private class Method(val name: String, val bfPct: (Subject) -> Double)

    /** Sun 2003 race-combined TBW, Pace & Rathbun 1945 hydration. */
    private fun sunBf(s: Subject, r: Float): Double {
        val h = s.heightCm.toDouble()
        val w = s.weightKg.toDouble()
        val tbw = if (s.sexMale) 1.20 + 0.45 * h * h / r + 0.18 * w else 3.75 + 0.45 * h * h / r + 0.11 * w
        return (w - tbw / 0.732) / w * 100.0
    }

    private fun production(s: Subject, footToFoot: Float = S400BodyComposition.FOOT_TO_FOOT_CORRECTION) =
        S400BodyComposition.compute(s.inputs, footToFootCorrection = footToFoot)

    private val productionMethod = Method("1F Sun 2003 @50 kHz x1.00 (pipeline)") {
        production(it).bfPct!!.toDouble()
    }

    private val masterMethod = Method("1F Sun 2003 @250 kHz x1.10") { sunBf(it, it.r250 * 1.10f) }

    /** Matthie 2005 Eqs. 5 and 14 TBW, from the pipeline's own two-band R_0 and ECW. */
    private val hanaiMethod = Method("2F Hanai/Matthie TBW (Cole-inverted)") {
        val r = production(it)
        val tbw = r.tbwKg!! * (1.0 + r.tbwCrossCheckDelta!!)
        (it.weightKg - tbw / 0.732) / it.weightKg * 100.0
    }

    private val methods = listOf(
        masterMethod,
        // The equation alone: at 1.10 the pipeline's §3.1b gate withholds M27.
        Method("1F Sun 2003 @50 kHz x1.10") { sunBf(it, it.r50 * 1.10f) },
        productionMethod,
        // Deurenberg 1991 Br J Nutr 65:105, BIA equation for adults.
        Method("1F Deurenberg 1991 BIA @50 kHz") {
            val h = it.heightCm.toDouble()
            val w = it.weightKg.toDouble()
            val sex = if (it.sexMale) 1.0 else 0.0
            val ffm = 0.340 * h * h / it.r50 + 15.34 * h / 100.0 + 0.273 * w - 0.127 * it.age + 4.56 * sex - 12.44
            (w - ffm) / w * 100.0
        },
        hanaiMethod,
        // bodymiscale's S400 mode: its hardware LBM on the 50 kHz band. The Xiaomi
        // formula family, so its agreement with the app is partly by construction.
        Method("1F bodymiscale S400 (Xiaomi LBM @50 kHz)") {
            val lib = BodyMiScaleLib(if (it.sexMale) GenderType.MALE else GenderType.FEMALE, it.age, it.heightCm)
            lib.getFat(it.weightKg, lib.getLbm(it.weightKg, it.r50)).toDouble()
        },
        // Deurenberg 1991, the same paper's BMI equation; no impedance.
        Method("0F Deurenberg 1991 BMI") {
            val bmi = it.weightKg / (it.heightCm / 100.0) / (it.heightCm / 100.0)
            1.20 * bmi + 0.23 * it.age - (if (it.sexMale) 10.8 else 0.0) - 5.4
        },
    )

    private class Score(val bias: Double, val sd: Double, val rmse: Double, val max: Double, val r: Double, val repSd: Double)

    private fun score(m: Method): Score {
        val predicted = subjects.map(m.bfPct)
        val app = subjects.map { it.appBfPct.toDouble() }
        val err = predicted.indices.map { predicted[it] - app[it] }
        return Score(mean(err), sd(err), rmse(err), err.maxOf { abs(it) }, pearson(predicted, app), sd(repeats.map(m.bfPct)))
    }

    @Test
    fun printAndCompare() {
        val scores = methods.associateWith(::score)
        println("%-42s %6s %6s %6s %6s %6s %6s".format("Body fat vs Mi app (pts)", "bias", "SD", "RMSE", "max", "r", "repSD"))
        for ((m, s) in scores) {
            println("%-42s %+6.1f %6.1f %6.1f %6.1f %6.2f %6.2f".format(m.name, s.bias, s.sd, s.rmse, s.max, s.r, s.repSd))
        }

        val prod = scores.getValue(productionMethod)
        assertWithMessage("pipeline RMSE vs master").that(prod.rmse).isAtMost(scores.getValue(masterMethod).rmse + 0.5)
        assertWithMessage("Hanai TBW RMSE vs pipeline").that(scores.getValue(hanaiMethod).rmse).isGreaterThan(prod.rmse)
        assertWithMessage("pipeline repSD").that(prod.repSd).isAtMost(0.5)
    }
}
