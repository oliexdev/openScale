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

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs

class SoehnleLibTest {

    private val EPS = 1e-3f

    // -------- Snapshot-Struktur --------
    private data class Snap(
        val isMale: Boolean,
        val age: Int,
        val h: Float,       // height (cm)
        val w: Float,       // weight (kg)
        val imp50: Float,   // 50 kHz impedance
        val imp5: Float,    // 5 kHz impedance
        val activity: Int,  // activityLevel (1..5)
        val bmi: Float,
        val fat: Float,
        val water: Float,
        val muscle: Float
    )

    // -------- Fixe Snapshots (deine Werte) --------
    private val FIXTURES: Map<String, Snap> = mapOf(
        "male_mid" to Snap(
            isMale = true, age = 30, h = 180f, w = 80f,
            imp50 = 500f, imp5 = 200f, activity = 3,
            bmi = 24.691359f, fat = 18.604935f, water = 54.8644f, muscle = 9.49897f
        ),
        "female_mid" to Snap(
            isMale = false, age = 28, h = 165f, w = 60f,
            imp50 = 520f, imp5 = 210f, activity = 4,
            bmi = 22.038567f, fat = 26.137234f, water = 56.005848f, muscle = 5.708269f
        ),
        "male_active5" to Snap(
            isMale = true, age = 35, h = 178f, w = 85f,
            imp50 = 480f, imp5 = 190f, activity = 5,
            bmi = 26.827421f, fat = 26.860249f, water = 55.484665f, muscle = 10.4964695f
        ),
        "female_low" to Snap(
            isMale = false, age = 45, h = 160f, w = 70f,
            imp50 = 700f, imp5 = 250f, activity = 1,
            bmi = 27.34375f, fat = 48.433907f, water = 38.981922f, muscle = 2.8784876f
        ),
    )

    // -------- Snapshot-Check --------
    @Test
    fun snapshots_match_expected_outputs() {
        require(FIXTURES.isNotEmpty()) { "No snapshots defined." }

        FIXTURES.forEach { (name, s) ->
            val lib = SoehnleLib(s.isMale, s.age, s.h, s.activity)

            assertWithMessage("$name:bmi")
                .that(lib.computeBodyMassIndex(s.w))
                .isWithin(EPS).of(s.bmi)

            assertWithMessage("$name:fat%")
                .that(lib.getFat(s.w, s.imp50))
                .isWithin(EPS).of(s.fat)

            assertWithMessage("$name:water%")
                .that(lib.getWater(s.w, s.imp50))
                .isWithin(EPS).of(s.water)

            assertWithMessage("$name:muscle%")
                .that(lib.getMuscle(s.w, s.imp50, s.imp5))
                .isWithin(EPS).of(s.muscle)
        }
    }

    // -------- Property-Tests --------

    @Test
    fun bmi_monotonic_with_weight() {
        val lib = SoehnleLib(true, 30, 180f, 3)
        val bmi1 = lib.computeBodyMassIndex(70f)
        val bmi2 = lib.computeBodyMassIndex(85f)
        assertWithMessage("BMI should increase when weight increases")
            .that(bmi2).isGreaterThan(bmi1)
    }

    @Test
    fun fat_increases_with_impedance50() {
        val lib = SoehnleLib(true,  35,  178f,  3)
        val w = 82f
        val low = lib.getFat(w, 300f)
        val high = lib.getFat(w, 600f)
        assertWithMessage("Fat% should increase with imp50 for same person/weight")
            .that(high).isGreaterThan(low)
    }

    @Test
    fun water_in_reasonable_range() {
        val lib = SoehnleLib( false,  29,  165f,  4)
        val water = lib.getWater(60f, 520f)
        assertInRange("Water%", water, 30f, 75f)
    }

    @Test
    fun muscle_in_reasonable_range() {
        val lib = SoehnleLib( true,  40,  182f,  5)
        val muscle = lib.getMuscle(90f, 500f, 220f)
        assertInRange("Muscle%", muscle, 0f, 70f)
    }

    @Test
    fun male_vs_female_fat_differs_same_inputs() {
        val age = 30; val h = 178f; val act = 3; val w = 75f; val imp50 = 500f
        val m = SoehnleLib(true, age, h, act).getFat(w, imp50)
        val f = SoehnleLib(false, age, h, act).getFat(w, imp50)
        assertWithMessage("Male vs Female fat% should differ")
            .that(abs(m - f)).isGreaterThan(0.1f)
    }

    // -------- Helper --------
    private fun assertInRange(label: String, value: Float, lo: Float, hi: Float) {
        assertWithMessage("$label lower bound ($lo)")
            .that(value).isAtLeast(lo)
        assertWithMessage("$label upper bound ($hi)")
            .that(value).isAtMost(hi)
    }
}
