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
import com.health.openscale.core.data.ActivityLevel
import org.junit.Test

class YunmaiLibTest {

    private val EPS = 1e-3f

    // --- Behavior (kept from earlier) ---------------------------------------

    @Test
    fun toYunmaiActivityLevel_mapsCorrectly() {
        assertThat(YunmaiLib.toYunmaiActivityLevel(ActivityLevel.HEAVY)).isEqualTo(1)
        assertThat(YunmaiLib.toYunmaiActivityLevel(ActivityLevel.EXTREME)).isEqualTo(1)
        assertThat(YunmaiLib.toYunmaiActivityLevel(ActivityLevel.SEDENTARY)).isEqualTo(0)
        assertThat(YunmaiLib.toYunmaiActivityLevel(ActivityLevel.MILD)).isEqualTo(0)
        assertThat(YunmaiLib.toYunmaiActivityLevel(ActivityLevel.MODERATE)).isEqualTo(0)
    }

    @Test
    fun constructor_setsFitnessFlag_indirectlyVisibleInMuscleValues() {
        val fit = YunmaiLib(1, 180f, ActivityLevel.EXTREME)
        val normal = YunmaiLib(1, 180f, ActivityLevel.MILD)
        val bf = 20f
        assertThat(fit.getMuscle(bf)).isGreaterThan(normal.getMuscle(bf))
        assertThat(fit.getSkeletalMuscle(bf)).isGreaterThan(normal.getSkeletalMuscle(bf))
    }

    // --- Regression fixtures -------------------------------------------------

    @Test
    fun regression_male_mod_30y_180cm_80kg_res500_bf23() {
        val lib = YunmaiLib(1, 180f, ActivityLevel.MODERATE)
        val age = 30; val w = 80f; val res = 500; val bf = 23f
        val r = Fx(55.907001f, 23.237043f, 51.595001f, 40.814999f, 3.263390f, 61.599998f, 11.318182f)
        checkAll(lib, age, w, res, bf, r)
    }

    @Test
    fun regression_female_mild_28y_165cm_60kg_res520_bf28() {
        val lib = YunmaiLib(0, 165f, ActivityLevel.MILD)
        val age = 28; val w = 60f; val res = 520; val bf = 28f
        val r = Fx(52.276997f, 29.947247f, 48.244999f, 38.164993f, 2.530795f, 43.200001f, 6.166667f)
        checkAll(lib, age, w, res, bf, r)
    }

    @Test
    fun regression_male_sedentary_55y_175cm_95kg_res430_bf32() {
        val lib = YunmaiLib(1, 175f, ActivityLevel.SEDENTARY)
        val age = 55; val w = 95f; val res = 430; val bf = 32f
        val r = Fx(49.372997f, 34.547203f, 45.564999f, 36.044998f, 3.365057f, 64.599998f, 18.590908f)
        checkAll(lib, age, w, res, bf, r)
    }

    @Test
    fun regression_female_sedentary_55y_160cm_50kg_res600_bf27() {
        val lib = YunmaiLib(0, 160f, ActivityLevel.SEDENTARY)
        val age = 55; val w = 50f; val res = 600; val bf = 27f
        val r = Fx(53.003002f, 28.532946f, 48.915001f, 38.694996f, 2.088284f, 36.500000f, 5.055555f)
        checkAll(lib, age, w, res, bf, r)
    }

    @Test
    fun regression_male_heavy_20y_190cm_72kg_res480_bf14() {
        val lib = YunmaiLib(1, 190f, ActivityLevel.HEAVY)
        val age = 20; val w = 72f; val res = 480; val bf = 14f
        val r = Fx(62.441002f, 15.266259f, 60.205002f, 51.605000f, 3.519648f, 61.919998f, 9.000000f)
        checkAll(lib, age, w, res, bf, r)
    }

    @Test
    fun regression_female_mod_22y_155cm_55kg_res510_bf29() {
        val lib = YunmaiLib(0, 155f, ActivityLevel.MODERATE)
        val age = 22; val w = 55f; val res = 510; val bf = 29f
        val r = Fx(51.551003f, 30.724077f, 47.575001f, 37.634998f, 2.187678f, 39.049999f, 6.722222f)
        checkAll(lib, age, w, res, bf, r)
    }

    @Test
    fun regression_male_mild_35y_175cm_85kg_res200_bf25() {
        val lib = YunmaiLib(1, 175f, ActivityLevel.MILD)
        val age = 35; val w = 85f; val res = 200; val bf = 25f
        val r = Fx(54.455002f, 27.232653f, 50.255001f, 39.754993f, 3.322063f, 63.750000f, 13.136364f)
        checkAll(lib, age, w, res, bf, r)
    }

    @Test
    fun regression_female_sedentary_40y_170cm_70kg_res800_bf36() {
        val lib = YunmaiLib(0, 170f, ActivityLevel.SEDENTARY)
        val age = 40; val w = 70f; val res = 800; val bf = 36f
        val r = Fx(46.468998f, 34.777931f, 42.884998f, 33.924999f, 2.674562f, 44.799999f, 10.409091f)
        checkAll(lib, age, w, res, bf, r)
    }

    // --- Fixture dump helper (run manually) ----------------------------------

    private data class DumpIn(
        val sex: Int,
        val heightCm: Float,
        val activity: ActivityLevel,
        val age: Int,
        val weightKg: Float,
        val resistance: Int,
        val bodyFatPct: Float
    )

    private fun dump(fi: DumpIn) {
        val lib = YunmaiLib(fi.sex, fi.heightCm, fi.activity)
        val water = lib.getWater(fi.bodyFatPct)
        val fat = lib.getFat(fi.age, fi.weightKg, fi.resistance)
        val muscle = lib.getMuscle(fi.bodyFatPct)
        val skeletal = lib.getSkeletalMuscle(fi.bodyFatPct)
        val bone = lib.getBoneMass(muscle, fi.weightKg)
        val lbm = lib.getLeanBodyMass(fi.weightKg, fi.bodyFatPct)
        val vfat = lib.getVisceralFat(fi.bodyFatPct, fi.age)

        println(
            """
            // sex=${fi.sex}, height=${fi.heightCm}cm, act=${fi.activity}, age=${fi.age}, weight=${fi.weightKg}kg, res=${fi.resistance}, bf=${fi.bodyFatPct}%
            Fixture(
                water = ${"%.6f".format(water)}f,
                fat = ${"%.6f".format(fat)}f,
                muscle = ${"%.6f".format(muscle)}f,
                skeletal = ${"%.6f".format(skeletal)}f,
                bone = ${"%.6f".format(bone)}f,
                lbm = ${"%.6f".format(lbm)}f,
                visceralFat = ${"%.6f".format(vfat)}f
            )
            """.trimIndent()
        )
    }

    @Test
    fun dump_allFixtures() {
        listOf(
            DumpIn(1, 180f, ActivityLevel.MODERATE, 30, 80f, 500, 23f),
            DumpIn(0, 165f, ActivityLevel.MILD, 28, 60f, 520, 28f),
            DumpIn(1, 175f, ActivityLevel.SEDENTARY, 55, 95f, 430, 32f),
            DumpIn(0, 160f, ActivityLevel.SEDENTARY, 55, 50f, 600, 27f),
            DumpIn(1, 190f, ActivityLevel.HEAVY, 20, 72f, 480, 14f),
            DumpIn(0, 155f, ActivityLevel.MODERATE, 22, 55f, 510, 29f),
            DumpIn(1, 175f, ActivityLevel.MILD, 35, 85f, 200, 25f),
            DumpIn(0, 170f, ActivityLevel.SEDENTARY, 40, 70f, 800, 36f)
        ).forEach(::dump)
    }

    // --- Helpers -------------------------------------------------------------

    private data class Fx(
        val water: Float,
        val fat: Float,
        val muscle: Float,
        val skeletal: Float,
        val bone: Float,
        val lbm: Float,
        val visceralFat: Float
    )

    private fun checkAll(
        lib: YunmaiLib,
        age: Int,
        w: Float,
        res: Int,
        bf: Float,
        r: Fx
    ) {
        assertThat(lib.getWater(bf)).isWithin(EPS).of(r.water)
        assertThat(lib.getFat(age, w, res)).isWithin(EPS).of(r.fat)
        assertThat(lib.getMuscle(bf)).isWithin(EPS).of(r.muscle)
        assertThat(lib.getSkeletalMuscle(bf)).isWithin(EPS).of(r.skeletal)
        assertThat(lib.getBoneMass(r.muscle, w)).isWithin(EPS).of(r.bone)
        assertThat(lib.getLeanBodyMass(w, bf)).isWithin(EPS).of(r.lbm)
        assertThat(lib.getVisceralFat(bf, age)).isWithin(EPS).of(r.visceralFat)
    }
}
