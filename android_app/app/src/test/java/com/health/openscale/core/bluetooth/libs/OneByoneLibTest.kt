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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Unit tests for [OneByoneLib].
 *
 * Strategy
 * 1) Snapshot tests with frozen numbers (from current Java impl) to guard a Kotlin port.
 * 2) Property tests (monotonicity, clamping, finite outputs, boundary behavior).
 *
 * NOTE: We do NOT re-implement formulas here. Snapshots are the source of truth.
 */
class OneByoneLibTest {

    private val EPS = 1e-3f

    // ---------- Snapshots (pre-recorded from current Java implementation) ----------

    private data class Snap(
        val sex: Int,
        val age: Int,
        val h: Float,
        val w: Float,
        val imp: Float,
        val pt: Int,
        val bmi: Float,
        val bf: Float,
        val lbm: Float,
        val muscle: Float,
        val water: Float,
        val bone: Float,
        val vf: Float
    )

    private val FIXTURES = mapOf(
        "male_mid" to Snap(
            sex = 1, age = 30, h = 180f, w = 80f, imp = 500f, pt = 0,
            bmi = 24.691359f, bf = 23.315102f, lbm = 61.34792f, muscle = 40.97725f,
            water = 52.60584f, bone = 3.030576f, vf = 10.79977f
        ),
        "female_mid" to Snap(
            sex = 0, age = 28, h = 165f, w = 60f, imp = 520f, pt = 1,
            bmi = 22.038567f, bf = 25.210106f, lbm = 44.873936f, muscle = 40.181107f,
            water = 51.305866f, bone = 2.3883991f, vf = 0.70499706f
        ),
        "male_high" to Snap(
            sex = 1, age = 52, h = 175f, w = 95f, imp = 430f, pt = 2,
            bmi = 31.020409f, bf = 26.381027f, lbm = 69.93803f, muscle = 35.573257f,
            water = 50.502613f, bone = 3.1443515f, vf = 13.163806f
        ),
        "imp_low" to Snap(
            sex = 1, age = 25, h = 178f, w = 72f, imp = 80f, pt = 0,
            bmi = 22.724403f, bf = 16.04116f, lbm = 60.450363f, muscle = 230.51118f,
            water = 57.595764f, bone = 3.0263696f, vf = 9.316022f
        ),
        "imp_mid" to Snap(
            sex = 0, age = 35, h = 170f, w = 68f, imp = 300f, pt = 2,
            bmi = 23.529411f, bf = 25.14642f, lbm = 50.900436f, muscle = 60.656864f,
            water = 51.349552f, bone = 2.650265f, vf = 2.6039982f
        ),
        "imp_high" to Snap(
            sex = 1, age = 45, h = 182f, w = 90f, imp = 1300f, pt = 1,
            bmi = 27.170633f, bf = 30.914497f, lbm = 62.176952f, muscle = 17.721643f,
            water = 49.32705f, bone = 2.901557f, vf = 11.179609f
        )
    )

    @Test
    fun snapshots_match_expected_outputs() {
        require(FIXTURES.isNotEmpty()) { "No snapshots defined." }

        FIXTURES.forEach { (name, s) ->
            val lib = OneByoneLib(s.sex, s.age, s.h, s.pt)
            val bf = lib.getBodyFat(s.w, s.imp)

            assertWithMessage("$name:bmi").that(lib.getBMI(s.w)).isWithin(EPS).of(s.bmi)
            assertWithMessage("$name:bf").that(bf).isWithin(EPS).of(s.bf)
            assertWithMessage("$name:lbm").that(lib.getLBM(s.w, bf)).isWithin(EPS).of(s.lbm)
            assertWithMessage("$name:muscle").that(lib.getMuscle(s.w, s.imp)).isWithin(EPS).of(s.muscle)
            assertWithMessage("$name:water").that(lib.getWater(bf)).isWithin(EPS).of(s.water)
            assertWithMessage("$name:bone").that(lib.getBoneMass(s.w, s.imp)).isWithin(EPS).of(s.bone)
            assertWithMessage("$name:vf").that(lib.getVisceralFat(s.w)).isWithin(EPS).of(s.vf)
        }
    }

    // ---------------- Generic / property-based tests ----------------

    @Test
    fun bmi_monotonicity_weightUp_increases_heightConstant() {
        val lib = OneByoneLib(1, 30, 180f, 0)
        val w1 = 70f
        val w2 = 85f
        assertThat(lib.getBMI(w2)).isGreaterThan(lib.getBMI(w1))
    }

    @Test
    fun bmi_monotonicity_heightUp_decreases_weightConstant() {
        val libShort = OneByoneLib(1, 30, 170f, 0)
        val libTall  = OneByoneLib(1, 30, 190f, 0)
        val w = 80f
        assertThat(libTall.getBMI(w)).isLessThan(libShort.getBMI(w))
    }

    @Test
    fun water_switch_coeff_below_and_above_50() {
        val lib = OneByoneLib(0, 40, 165f, 1)
        val bfHigh = 35f // → (100-35)*0.7 = 45.5 < 50 → *1.02
        val bfLow  = 20f // → (100-20)*0.7 = 56 > 50 → *0.98
        val wHigh = lib.getWater(bfHigh)
        val wLow  = lib.getWater(bfLow)
        assertThat(wHigh).isLessThan(50f)
        assertThat(wLow).isGreaterThan(50f)
    }

    @Test
    fun boneMass_isReasonablyClamped_between_0_5_and_8_0() {
        val lib = OneByoneLib(0, 55, 170f, 2)
        // Explore some extreme ranges
        val candidates = listOf(
            40f to 1400f,
            150f to 200f,
            55f to 600f,
            95f to 300f,
        )
        candidates.forEach { (w, imp) ->
            val bone = lib.getBoneMass(w, imp)
            assertThat(bone).isAtLeast(0.5f)
            assertThat(bone).isAtMost(8.0f)
        }
    }

    @Test
    fun muscle_reacts_to_impedance_reasonably() {
        val sex = 1; val age = 30; val h = 180f; val w = 80f
        val lib = OneByoneLib(sex, age, h, 0)

        val impHigh = 1300f
        val impMid  = 400f
        val impLow  = 80f

        val mHigh = lib.getMuscle(w, impHigh)
        val mMid  = lib.getMuscle(w, impMid)
        val mLow  = lib.getMuscle(w, impLow)

        // Lower impedance tends to increase SMM estimate (classic BIA behavior)
        assertThat(mLow).isGreaterThan(mMid)
        assertThat(mMid).isGreaterThan(mHigh)
    }

    @Test
    fun bodyFat_stays_within_reasonable_bounds() {
        val lib = OneByoneLib(1, 35, 180f, 1)
        val weights = listOf(50f, 70f, 90f, 110f)
        val imps    = listOf(80f, 300f, 600f, 1200f)
        for (w in weights) for (imp in imps) {
            val bf = lib.getBodyFat(w, imp)
            // Implementation clamps to [1, 45]; allow small epsilon
            assertThat(bf).isAtLeast(1f - 1e-3f)
            assertThat(bf).isAtMost(45f + 1e-3f)
        }
    }

    @Test
    fun peopleType_influences_outputs() {
        val base = OneByoneLib(1, 40, 175f, 0)
        val mid  = OneByoneLib(1, 40, 175f, 1)
        val high = OneByoneLib(1, 40, 175f, 2)
        val w = 85f; val imp = 450f

        val boneBase = base.getBoneMass(w, imp)
        val boneMid  = mid.getBoneMass(w, imp)
        val boneHigh = high.getBoneMass(w, imp)

        // Different activity types should yield distinct (but not crazy) values.
        assertThat(abs(boneBase - boneMid)).isGreaterThan(0.0f)
        assertThat(abs(boneMid - boneHigh)).isGreaterThan(0.0f)

        // Guard against wild divergence
        val minV = min(boneBase, min(boneMid, boneHigh))
        val maxV = max(boneBase, max(boneMid, boneHigh))
        assertThat(maxV - minV).isLessThan(2.0f) // heuristic guard
    }

    @Test
    fun sex_flag_affects_outputs() {
        val male   = OneByoneLib(1, 32, 178f, 1)
        val female = OneByoneLib(0, 32, 178f, 1)
        val w = 75f; val imp = 420f

        val bfM = male.getBodyFat(w, imp)
        val bfF = female.getBodyFat(w, imp)

        // Expect some difference between sexes
        assertThat(abs(bfM - bfF)).isGreaterThan(0.1f)
    }

    @Test
    fun outputs_are_finite_for_typical_ranges() {
        val lib = OneByoneLib(1, 30, 180f, 0)
        val w = 80f; val imp = 500f
        val bf = lib.getBodyFat(w, imp)
        val values = listOf(
            lib.getBMI(w),
            bf,
            lib.getLBM(w, bf),
            lib.getMuscle(w, imp),
            lib.getWater(bf),
            lib.getBoneMass(w, imp),
            lib.getVisceralFat(w)
        )
        values.forEach {
            assertThat(it.isNaN()).isFalse()
            assertThat(it.isInfinite()).isFalse()
        }
    }

    // ---------- Helper to (re)generate snapshot values if formulas change ----------

    @Test
    fun print_current_outputs_for_fixtures() {
        fun dump(sex: Int, age: Int, h: Float, w: Float, imp: Float, pt: Int) {
            val lib = OneByoneLib(sex, age, h, pt)
            val bf = lib.getBodyFat(w, imp)
            println(
                "SNAP -> sex=$sex age=$age h=$h w=$w imp=$imp pt=$pt | " +
                        "bmi=${lib.getBMI(w)}; bf=$bf; " +
                        "lbm=${lib.getLBM(w, bf)}; muscle=${lib.getMuscle(w, imp)}; " +
                        "water=${lib.getWater(bf)}; bone=${lib.getBoneMass(w, imp)}; vf=${lib.getVisceralFat(w)}"
            )
        }

        // Re-run if you intentionally modify formulas; then paste outputs into FIXTURES above.
        dump(1, 30, 180f, 80f, 500f, 0)
        dump(0, 28, 165f, 60f, 520f, 1)
        dump(1, 52, 175f, 95f, 430f, 2)
        dump(1, 25, 178f, 72f, 80f, 0)
        dump(0, 35, 170f, 68f, 300f, 2)
        dump(1, 45, 182f, 90f, 1300f, 1)
    }
}
