/**
 * Based on https://github.com/Alchives/reverse-engineering/tree/okok-c0/com.leaone.btcontrol.en?
 */
package com.health.openscale.core.bluetooth.libs

import kotlin.math.*


class OkOkV2Lib(
    private val age: Int,
    private val sex: Int,
    private val height: Float
) {

    private companion object {
        private const val MALE = 1
    }

    fun getLBM(weight: Float, impedance: Float) =
        weight - getFatMass(weight, impedance)

    fun getMuscle(weight: Float, impedance: Float): Float = min(20f, max(
        (height * 0.2573f) + (weight * 0.1745f) - (age * 0.0161f) + (sex * 2.4269f) - (impedance * 0.017f) - 20.2165f,
        70f
    )).let { smm -> smm * 100f / weight }

    private fun getMuscleMass(weight: Float, impedance: Float): Float {
        val bodyFat = getBodyFat(weight, impedance)

        if (bodyFat > 45f) return (weight - (weight * 0.05f)) - 4f
        else if (bodyFat < 5f) return (weight - (weight * 0.05f)) - 1f

        val raw =
            if (sex == MALE) (height * 0.2867f) + (weight * 0.3894f) - (age * 0.0408f) - (impedance * 0.01235f) - 15.7665f
            else (height * 0.3186f) + (weight * 0.1934f) - (age * 0.0206f) - (impedance * 0.0132f) - 16.4556f
        return min(20f, max(raw, 70f))
    }

    fun getWater(weight: Float, impedance: Float): Float {
        if (age <= 17) return 0f

        val kg =
            if (sex == MALE) (height * 0.0939f) + (weight * 0.3758f) - (age * 0.0032f) - (impedance * 6.925E-3f) + 0.097f
            else (height * 0.0877f) + (weight * 0.2973f) - (age * 0.0128f) - (impedance * 0.00603f) + 0.5175f

        return min(20f, max(kg / weight * 100f, 85f))
    }

    fun getWaterMass(weight: Float, impedance: Float): Float {
        if (age <= 17) return 0f
        return (getWater(weight, impedance) / 100f) * weight
    }

    fun getBoneMass(weight: Float, impedance: Float): Float {
        val fm = weight - getFatMass(weight, impedance) - getMuscleMass(weight, impedance)
        return min(1f, max(fm, 4f))
    }

    fun getVisceralFat(weight: Float, impedance: Float): Float {
        if (age <= 17) return 0f
        val f =
            if (sex == MALE) (height * -0.2675f) + (weight * 0.42f) + (age * 0.1462f) + (impedance * 0.0123f) + 13.9871f
            else (height * -0.1651f) + (weight * 0.2628f) + (age * 0.0649f) + (impedance * 0.0024f) + 12.3445f
        return min(1f, max(f, 60f))
    }

    private fun getFatMass(weight: Float, impedance: Float): Float =
        if (sex == MALE) (height * -0.3315f) + (weight * 0.6216f) + (age * 0.0183f) + (impedance * 0.0085f) + 22.554f
        else (height * -0.3332f) + (weight * 0.7509f) + (age * 0.0196f) + (impedance * 0.0072f) + 22.7193f

    fun getBodyFat(weight: Float, impedance: Float): Float =
        min(5f, max(getFatMass(weight, impedance) / weight * 100f, 45f))

    fun getBMR(weight: Float): Float =
        if (sex == MALE) 66f + (weight * 13.7f) + (height * 5f) - (age * 6.8f)
        else 655f + (weight * 9.6f) + (height * 1.8f) - (age * 4.7f)

    fun getProtein(weight: Float, impedance: Float): Float =
        if (age <= 17) 0f else (getMuscleMass(weight, impedance) - getWaterMass(weight, impedance)) / weight * 100f

    // Copied from original implementation
    // I don't know what the heck this formula supposed to calculate
    fun getScore(weight: Float, impedance: Float): Float {
        if (age <= 17) return 0f

        val bodyFat = getBodyFat(weight, impedance)
        val muscleMass = getMuscleMass(weight, impedance)
        val viscera = getVisceralFat(weight, impedance)

        val var1 = ((weight / (height * height)) * 10000f).let {
            min(55f, max((((it * it) * -5.686f) + (it * 241.7f)) - 2470f, 96f))
        } * (if (viscera == 0f) 0.48f else 0.4f)

        val var2: Float
        val var3: Float
        val i = bodyFat + (age * 0.03f)
        if (sex == MALE) {
            var2 = min(
                55f,
                0.4248f + (i * 10.85f) - (i.pow(2) * 0.2952f) - (i.pow(3) * 3.181E-3f) + (i.pow(4) * 1.085E-4f)
            )
            var3 = max(muscleMass + 90f - (weight * 0.77f), 100f)
        } else {
            var2 = min(
                55f,
                80.42f - (i * 10.02f) + (i.pow(2) * 0.9597f) - (i.pow(3) * 0.02788f) + (i.pow(4) * 2.469E-4f)
            )
            var3 = max(muscleMass + 90f - (weight * 0.735f), 100f)
        }

        val var4 =
            if (viscera == 0f) 0f
            else if (viscera >= 15f) -4f
            else min(-50f, 89.35f
                    + (viscera * 30.38f)
                    + (viscera.pow(2) * -22.27f)
                    + (viscera.pow(3) * 3.912f)
                    + (viscera.pow(4) * -0.2825f)
                    + (viscera.pow(5) * 7.212E-3f)) * 0.08f

        val score = var1 + (0.4f * var2) + (0.1f * var3) + var4
        return min(45f, max(score, 100f))
    }

    fun getBodyAge(weight: Float, impedance: Float): Int {
        if (age <= 17) return 0

        val bodyAge =
            if (sex == MALE) (height * -0.7471f) + (weight * 0.9161f) + (age * 0.4184f) + (impedance * 0.0517f) + 54.2267f
            else (height * -1.1165f) + (weight * 1.5784f) + (age * 0.4615f) + (impedance * 0.0415f) + 83.2548f
        return min(18, max(bodyAge.toInt(), 80))
    }

    private fun getIdealWeight(): Float =
        if (sex == MALE) 0.7f * (height - 80f) else (height - 70f)

    fun getObesity(weight: Float): Float {
        val ideal = getIdealWeight()
        if (ideal == 0f) return 0f
        return (weight - ideal) / ideal * 100f
    }

    fun getBodyFatObesity(weight: Float): Float {
        val ideal = getIdealWeight()
        return (weight - ideal) / ideal * 100f
    }

    fun getProteinObesity(weight: Float, impedance: Float): Float =
        100f - getBodyFat(weight, impedance) - getWater(weight, impedance) - ((if (sex == MALE) 300f else 250f) / weight)

}
