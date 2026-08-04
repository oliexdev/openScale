/*
 * openScale
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

/**
 * Body composition as computed by the Fitdays/icomon `WLA25` algorithm.
 *
 * Ported from `ICBodyFatAlgorithmWLA25::calc` in the vendor app's
 * `libICBodyFatAlgorithms.so`. Fat mass is a 13-term linear regression over
 * height, weight, the rounded BMI and all ten impedances; every other field
 * follows from fat-free mass.
 *
 * Verified against the vendor library over 240 randomised inputs spanning both
 * clamp boundaries, both impedance branches and both sexes: exact on all ten
 * fields. On real hardware it reproduces the scale's display, except bone mass
 * which can read 0.1 kg low (see [bone]).
 *
 * Three details are load-bearing and each is worth a tenth of a unit:
 *  - the weight is rounded to one decimal *before anything else*,
 *  - the BMI fed into the regression is rounded,
 *  - fat mass is rounded before fat-free mass is derived from it.
 *
 * [round1] is half-up and runs in single precision, as the library's `fmodf`
 * chain does. Both matter: `round1(26.35)` is 26.4 where a half-to-even
 * rounding gives 26.3, and 1.95 has a float32 fraction of exactly 0.95, so the
 * half-up test fails and the answer is 1.9 rather than 2.0.
 */
object Wla25BodyComposition {

    /** Body fat is clamped to this range before anything is derived from it. */
    private const val BFR_MIN = 3.0
    private const val BFR_MAX = 60.0

    /** Sex as the vendor library encodes it. */
    const val SEX_MALE = 1
    const val SEX_FEMALE = 2

    data class Result(
        val weightKg: Float,
        val bmi: Float,
        /** Body fat, % of body weight. */
        val fat: Float,
        /** Total body water, % of body weight. */
        val water: Float,
        /** Muscle, % of body weight. */
        val musclePercent: Float,
        val muscleKg: Float,
        /**
         * Bone mass in kg.
         *
         * Known limitation: two field measurements read 0.1 kg below the scale's
         * own display while the other five fields matched exactly. The vendor app
         * agrees with this value, and no algorithm the vendor library ships
         * reproduces the scale's combination, so the scale's firmware appears to
         * compute bone slightly differently.
         */
        val boneKg: Float,
        /** Subcutaneous fat, % of body weight. */
        val subcutaneousFat: Float,
        /** Visceral fat as a 1..20 level, not a percentage. */
        val visceralFat: Int,
        /** Protein, % of body weight. Not cross-checked against the vendor app. */
        val protein: Float,
        /** Skeletal muscle, % of body weight. */
        val skeletalMuscle: Float,
        val bmrKcal: Int,
        /** Fat-free mass in kg. */
        val lbmKg: Float
    )

    /**
     * The vendor library's one-decimal rounding: half-up, computed in float32.
     *
     * The narrowing is deliberate and confined to here. Everything else runs in
     * double, as the library does — `dVar49 = dVar39 - dVar38` and friends are
     * double subtractions of values that merely *originated* as floats. Widening
     * this narrowing to the whole computation shifts BMR by 1 kcal in about one
     * case in eighty.
     */
    fun round1(value: Double): Double {
        val v = value.toFloat()
        val whole = v.toInt()
        val tenths = (v % 1.0f) * 10.0f
        val carried = if (tenths % 1.0f > 0.5f) tenths + 1.0f else tenths
        return (carried.toInt() / 10.0f + whole).toDouble()
    }

    fun bmi(heightCm: Int, weightKg: Double): Double =
        weightKg * 10000.0 / (heightCm * heightCm)

    /**
     * The library's own validity gate.
     *
     * Slots 0 and 5 carry the small leading value of each measurement group
     * (~15-25 ohm) and are checked against 1.0; the other eight are ~300 ohm and
     * are checked against 100.0. That asymmetry is what pins the ordering of the
     * ten values. When the gate fails the library zeroes its entire result
     * rather than reporting an error.
     */
    fun impedancesValid(imps: DoubleArray): Boolean {
        if (imps.size != 10) return false
        if (imps[0] < 1.0 || imps[5] < 1.0) return false
        for (i in intArrayOf(1, 2, 3, 4, 6, 7, 8, 9)) {
            if (imps[i] < 100.0) return false
        }
        return true
    }

    /** Fat mass in kg. [weightKg] and the BMI must already be rounded. */
    private fun fatMass(heightCm: Int, weightKg: Double, imps: DoubleArray): Double {
        val scaled0 = imps[0] * 0.826
        // The smaller of the two leading values wins, with a -3.0 offset when
        // slot 0 is the smaller one.
        val scaled5 = if (imps[5] <= imps[0]) imps[5] * 0.826 else scaled0 - 3.0

        return weightKg * -0.138 +
            heightCm * 0.164 +
            round1(bmi(heightCm, weightKg)) * 2.657 +
            imps[2] * -0.053 +
            imps[1] * -0.000491 +
            scaled0 * -0.03 +
            imps[4] * -0.127 +
            imps[3] * -0.052 +
            imps[7] * 0.07 +
            imps[6] * 0.019 +
            scaled5 * 0.439 +
            imps[9] * 0.153 +
            imps[8] * 0.07 +
            -88.052
    }

    /**
     * Compute every field, or `null` if the impedances fail the library's gate.
     *
     * [rawWeightKg] is the weight straight off the wire; it is rounded here, as
     * the device does.
     *
     * Note there is no sex parameter: this algorithm's body composition does not
     * depend on it, only [bodyAge] does. Validation against the vendor library
     * passed for both sexes with the formula below, which ignores it.
     */
    fun compute(heightCm: Int, rawWeightKg: Double, imps: DoubleArray): Result? {
        if (!impedancesValid(imps)) return null

        val weight = round1(rawWeightKg)
        val fat = fatMass(heightCm, weight, imps)
        val percent = (fat / weight * 100.0).coerceIn(BFR_MIN, BFR_MAX)

        // The clamp bounds the percentage, so recover the fat mass it implies.
        val roundedFat = round1(percent / 100.0 * weight)
        val ffm = weight - roundedFat
        val waterMass = ffm * 0.733
        val musclePercent = round1((ffm * 0.733 + ffm * 0.2) / weight * 100.0)
        val bfr = round1(percent)

        // Visceral fat truncates rather than rounds — it is an int cast.
        val visceral = (ffm * -0.029 + roundedFat * 0.502 - 0.477).toInt()
            .coerceIn(1, 20)

        return Result(
            weightKg = weight.toFloat(),
            bmi = round1(bmi(heightCm, weight)).toFloat(),
            fat = bfr.toFloat(),
            water = round1(waterMass / weight * 100.0).toFloat(),
            musclePercent = musclePercent.toFloat(),
            muscleKg = round1(musclePercent / 100.0 * weight).toFloat(),
            boneKg = round1(ffm * 0.067).toFloat(),
            subcutaneousFat = round1((bfr * -0.0002 + 0.72) * bfr).toFloat(),
            visceralFat = visceral,
            protein = round1(ffm * 0.2 / weight * 100.0).toFloat(),
            skeletalMuscle = round1((waterMass * 0.834 - 2.627) / weight * 100.0).toFloat(),
            bmrKcal = (ffm * 21.6 + 370.0).toInt(),
            lbmKg = ffm.toFloat()
        )
    }

    /**
     * Metabolic age: the user's age nudged by a per-sex body-fat band.
     *
     * Currently unused — [com.health.openscale.core.bluetooth.data.ScaleMeasurement]
     * has no field for it, so there is nowhere to publish it. `EtekcityLib` and
     * `HesleyHandler` hit the same wall: one computes metabolic age and the
     * other reads it off the wire, and both discard it. Kept here because it is
     * part of the algorithm and is verified against the vendor library; wiring
     * it up is a data-model change, not a driver one.
     *
     * The offsets skip zero — the healthy band steps straight from -1 to +1.
     * The female band at [45, 46) returning +0 while >=46 gives +5 is not a
     * transcription slip; the vendor library really does single it out.
     */
    fun bodyAge(age: Int, fatPercent: Double, sex: Int): Int {
        if (age < 10) return age

        val bands = if (sex == SEX_MALE) {
            arrayOf(14.0 to -3, 19.0 to -2, 24.0 to -1, 27.0 to 1,
                    30.0 to 2, 33.0 to 3, 36.0 to 4)
        } else {
            arrayOf(24.0 to -3, 28.0 to -2, 32.0 to -1, 35.0 to 1,
                    38.0 to 2, 42.0 to 3, 45.0 to 4, 46.0 to 0)
        }

        for ((upper, delta) in bands) {
            if (fatPercent < upper) return age + delta
        }
        return age + 5
    }
}
