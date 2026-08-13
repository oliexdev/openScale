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

/**
 * Unit tests for [OneByoneNewLib].
 *
 * - Snapshot tests (FIXTURES) sichern, dass sich die Outputs nach Refactor/Port nicht verändern.
 * - Property-Tests prüfen Monotonie, Clamping, Finite-Werte etc.
 */
class OneByoneNewLibTest {

    private val EPS = 1e-3f

    // ---------- Snapshots (aus deiner Ausgabe eingefügt) ----------

    private data class Snap(
        val sex: Int,
        val age: Int,
        val h: Float,
        val w: Float,
        val imp: Int,
        val pt: Int,
        val bmi: Float,
        val lbm: Float,
        val bmmrCoeff: Float,
        val bmmr: Float,
        val bodyFatPct: Float,
        val boneMass: Float,
        val muscleMass: Float,
        val skelMusclePct: Float,
        val visceralFat: Float,
        val waterPct: Float,
        val proteinPct: Float
    )

    private val FIXTURES: Map<String, Snap> = mapOf(
        "male_mid" to Snap(
            sex = 1, age = 30, h = 180f, w = 80f, imp = 500, pt = 0,
            bmi = 24.691359f, lbm = 62.14792f, bmmrCoeff = 21.0f, bmmr = 1000.0f,
            bodyFatPct = 23.315102f, boneMass = 3.1254208f, muscleMass = 58.2225f,
            skelMusclePct = 40.566765f, visceralFat = 10.79977f, waterPct = 52.60584f,
            proteinPct = 15.963814f
        ),
        "female_mid" to Snap(
            sex = 0, age = 28, h = 165f, w = 60f, imp = 520, pt = 1,
            bmi = 22.038567f, lbm = 51.032806f, bmmrCoeff = 22.0f, bmmr = 1000.0f,
            bodyFatPct = 28.27285f, boneMass = 2.486581f, muscleMass = 40.549713f,
            skelMusclePct = 36.45647f, visceralFat = 4.704997f, waterPct = 49.204823f,
            proteinPct = 14.44164f
        ),
        "imp_low" to Snap(
            sex = 1, age = 25, h = 178f, w = 72f, imp = 80, pt = 0,
            bmi = 22.724403f, lbm = 62.06637f, bmmrCoeff = 23.0f, bmmr = 1000.0f,
            bodyFatPct = 14.907819f, boneMass = 3.1212144f, muscleMass = 58.145157f,
            skelMusclePct = 45.008743f, visceralFat = 9.316022f, waterPct = 58.373234f,
            proteinPct = 17.714064f
        ),
        "imp_mid" to Snap(
            sex = 0, age = 35, h = 170f, w = 68f, imp = 300, pt = 2,
            bmi = 23.529411f, lbm = 56.22662f, bmmrCoeff = 20.0f, bmmr = 1000.0f,
            bodyFatPct = 31.690466f, boneMass = 2.754478f, muscleMass = 43.696007f,
            skelMusclePct = 36.679123f, visceralFat = 6.603998f, waterPct = 48.773006f,
            proteinPct = 11.583979f
        ),
        "imp_high" to Snap(
            sex = 1, age = 45, h = 182f, w = 90f, imp = 1300, pt = 1,
            bmi = 27.170633f, lbm = 59.750725f, bmmrCoeff = 21.0f, bmmr = 1000.0f,
            bodyFatPct = 34.49919f, boneMass = 3.0017734f, muscleMass = 55.948956f,
            skelMusclePct = 36.065098f, visceralFat = 13.974511f, waterPct = 46.76758f,
            proteinPct = 11.656517f
        ),
    )

    @Test
    fun snapshots_match_expected_outputs() {
        FIXTURES.forEach { (name, s) ->
            val lib = OneByoneNewLib(s.sex, s.age, s.h, s.pt)

            val bmi   = lib.getBMI(s.w)
            val lbm   = lib.getLBM(s.w, s.imp)
            val coeff = lib.getBMMRCoeff(s.w)
            val bmmr  = lib.getBMMR(s.w)
            val bf    = lib.getBodyFatPercentage(s.w, s.imp)
            val bone  = lib.getBoneMass(s.w, s.imp)
            val mm    = lib.getMuscleMass(s.w, s.imp)
            val skm   = lib.getSkeletonMusclePercentage(s.w, s.imp)
            val vf    = lib.getVisceralFat(s.w)
            val water = lib.getWaterPercentage(s.w, s.imp)
            val prot  = lib.getProteinPercentage(s.w, s.imp)

            assertWithMessage("$name:bmi").that(bmi).isWithin(EPS).of(s.bmi)
            assertWithMessage("$name:lbm").that(lbm).isWithin(EPS).of(s.lbm)
            assertWithMessage("$name:bmmrCoeff").that(coeff).isWithin(EPS).of(s.bmmrCoeff)
            assertWithMessage("$name:bmmr").that(bmmr).isWithin(EPS).of(s.bmmr)
            assertWithMessage("$name:bf%").that(bf).isWithin(EPS).of(s.bodyFatPct)
            assertWithMessage("$name:bone").that(bone).isWithin(EPS).of(s.boneMass)
            assertWithMessage("$name:muscleMass").that(mm).isWithin(EPS).of(s.muscleMass)
            assertWithMessage("$name:skelMuscle%").that(skm).isWithin(EPS).of(s.skelMusclePct)
            assertWithMessage("$name:visceralFat").that(vf).isWithin(EPS).of(s.visceralFat)
            assertWithMessage("$name:water%").that(water).isWithin(EPS).of(s.waterPct)
            assertWithMessage("$name:protein%").that(prot).isWithin(EPS).of(s.proteinPct)
        }
    }

    // ---------- Behavior / Property tests ----------

    @Test
    fun bmi_is_bounded_and_monotonic_with_weight() {
        val lib = OneByoneNewLib(1, 30, 180f, 0)
        val b1 = lib.getBMI(60f)
        val b2 = lib.getBMI(80f)
        val b3 = lib.getBMI(100f)

        listOf(b1, b2, b3).forEach {
            assertThat(it).isAtLeast(10f - EPS)
            assertThat(it).isAtMost(90f + EPS)
        }
        assertThat(b2).isGreaterThan(b1)
        assertThat(b3).isGreaterThan(b2)
    }

    @Test
    fun lbm_varies_with_impedance_and_weight() {
        val lib = OneByoneNewLib(0, 28, 165f, 1)
        val w   = 60f

        val lbmHighImp = lib.getLBM(w, 1200)
        val lbmLowImp  = lib.getLBM(w, 200)
        assertThat(lbmLowImp).isGreaterThan(lbmHighImp)

        val lbmHeavier = lib.getLBM(75f, 300)
        val lbmLighter = lib.getLBM(55f, 300)
        assertThat(lbmHeavier).isGreaterThan(lbmLighter)
    }

    @Test
    fun bmmrCoeff_follows_age_bands_and_sex() {
        fun coeff(sex:Int, age:Int): Float =
            OneByoneNewLib(sex, age, 170f, 0).getBMMRCoeff(70f)

        // male bands
        assertThat(coeff(1, 10)).isWithin(EPS).of(36f)
        assertThat(coeff(1, 14)).isWithin(EPS).of(30f)
        assertThat(coeff(1, 17)).isWithin(EPS).of(26f)
        assertThat(coeff(1, 25)).isWithin(EPS).of(23f)
        assertThat(coeff(1, 50)).isWithin(EPS).of(20f)

        // female bands
        assertThat(coeff(0, 10)).isWithin(EPS).of(34f)
        assertThat(coeff(0, 15)).isWithin(EPS).of(29f)
        assertThat(coeff(0, 17)).isWithin(EPS).of(24f)
        assertThat(coeff(0, 25)).isWithin(EPS).of(22f)
        assertThat(coeff(0, 50)).isWithin(EPS).of(19f)
    }

    @Test
    fun bmmr_is_bounded_and_differs_by_sex() {
        val h = 180f
        val age = 30

        val male = OneByoneNewLib(1, age, h, 0).getBMMR(22f)   // ~806 (ungeclamped)
        val female = OneByoneNewLib(0, age, h, 0).getBMMR(19f) // ~802 (ungeclamped)

        assertThat(male).isAtLeast(500f - 1e-3f)
        assertThat(male).isAtMost(1000f + 1e-3f)
        assertThat(female).isAtLeast(500f - 1e-3f)
        assertThat(female).isAtMost(1000f + 1e-3f)

        assertThat(abs(male - female)).isGreaterThan(0.1f)
    }

    @Test
    fun water_switches_coeff_around_50_and_is_bounded() {
        val lib = OneByoneNewLib(1, 35, 175f, 1)
        val w = 80f
        val waterLow  = lib.getWaterPercentage(w, 1200) // typ. <50
        val waterHigh = lib.getWaterPercentage(w, 200)  // typ. >50

        listOf(waterLow, waterHigh).forEach {
            assertThat(it).isAtLeast(35f - EPS)
            assertThat(it).isAtMost(75f + EPS)
        }
        // lose heuristics; just ensure they are not identical and near the “switch” region across scenarios
        assertThat(abs(waterHigh - waterLow)).isGreaterThan(0.5f)
    }

    @Test
    fun boneMass_is_bounded_and_varies_with_impedance() {
        val lib = OneByoneNewLib(0, 50, 168f, 0)
        val w = 70f

        val boneHighImp = lib.getBoneMass(w, 1200)
        val boneLowImp  = lib.getBoneMass(w, 200)

        listOf(boneHighImp, boneLowImp).forEach {
            assertThat(it).isAtLeast(0.5f - 1e-3f)
            assertThat(it).isAtMost(8.0f + 1e-3f)
        }

        assertThat(abs(boneLowImp - boneHighImp)).isGreaterThan(0.0f)
        assertThat(boneLowImp).isGreaterThan(boneHighImp)
    }

    @Test
    fun muscleMass_is_bounded_and_correlates_with_impedance() {
        val lib = OneByoneNewLib(1, 28, 180f, 0)
        val w = 82f

        val mmHighImp = lib.getMuscleMass(w, 1200)
        val mmLowImp  = lib.getMuscleMass(w, 200)

        listOf(mmHighImp, mmLowImp).forEach {
            assertThat(it).isAtLeast(10f - EPS)
            assertThat(it).isAtMost(120f + EPS)
        }

        assertThat(mmLowImp).isGreaterThan(mmHighImp)
    }

    @Test
    fun skeletonMuscle_is_finite_and_reasonable_range() {
        val lib = OneByoneNewLib(0, 33, 165f, 2)
        val sm = lib.getSkeletonMusclePercentage(58f, 400)

        assertThat(sm.isNaN()).isFalse()
        assertThat(sm.isInfinite()).isFalse()
        assertThat(sm).isGreaterThan(-20f)
        assertThat(sm).isLessThan(120f)
    }

    @Test
    fun bodyFat_and_protein_are_finite() {
        val lib = OneByoneNewLib(1, 40, 182f, 1)
        val w = 90f
        val imp = 500
        val bf = lib.getBodyFatPercentage(w, imp)
        val prot = lib.getProteinPercentage(w, imp)

        assertThat(bf.isNaN()).isFalse()
        assertThat(prot.isNaN()).isFalse()
        assertThat(bf.isInfinite()).isFalse()
        assertThat(prot.isInfinite()).isFalse()
    }

    // ---------- Helper: re-print current values if du neue Fixtures brauchst ----------

    @Test
    fun print_current_outputs_for_fixtures() {
        fun dump(tag:String, sex:Int, age:Int, h:Float, w:Float, imp:Int, pt:Int) {
            val lib = OneByoneNewLib(sex, age, h, pt)
            val bmi   = lib.getBMI(w)
            val lbm   = lib.getLBM(w, imp)
            val coeff = lib.getBMMRCoeff(w)
            val bmmr  = lib.getBMMR(w)
            val bf    = lib.getBodyFatPercentage(w, imp)
            val bone  = lib.getBoneMass(w, imp)
            val mm    = lib.getMuscleMass(w, imp)
            val skm   = lib.getSkeletonMusclePercentage(w, imp)
            val vf    = lib.getVisceralFat(w)
            val water = lib.getWaterPercentage(w, imp)
            val prot  = lib.getProteinPercentage(w, imp)

            println(
                "SNAP $tag -> sex=$sex age=$age h=$h w=$w imp=$imp pt=$pt | " +
                        "bmi=$bmi; lbm=$lbm; bmmrCoeff=$coeff; bmmr=$bmmr; " +
                        "bf%=$bf; bone=$bone; muscleMass=$mm; skelMuscle%=$skm; " +
                        "visceralFat=$vf; water%=$water; protein%=$prot"
            )
        }

        dump("male_mid",   1, 30, 180f, 80f, 500, 0)
        dump("female_mid", 0, 28, 165f, 60f, 520, 1)
        dump("imp_low",    1, 25, 178f, 72f,  80, 0)
        dump("imp_mid",    0, 35, 170f, 68f, 300, 2)
        dump("imp_high",   1, 45, 182f, 90f,1300, 1)
    }
}
