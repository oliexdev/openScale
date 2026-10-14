package com.health.openscale.core.bluetooth.libs

import kotlin.math.floor

/**
 * EMPIRICAL candidate for v235, inferred from quantized oracle outputs.
 * NOT constants extracted from Xiaomi code, NOT a recovered complete BIA model.
 *
 * The selected affine candidate fits 309 unique exploratory inputs and matched
 * 20 independently reserved adult inputs. These counts do not prove global
 * parity. The existing Bluetooth calculator does not use this helper.
 *
 * API units are explicit to avoid Float input conversion: mm, tenths of kg,
 * and tenths of ohm. Outside the predeclared fence it returns null.
 */
object S400BodyFatV235Experimental {
    private val female = doubleArrayOf(
        -22.67718678495718, 0.33311762205634016, 0.24897609312775784,
        -0.019640451233465635, -0.007226968447909854,
    )
    private val male = doubleArrayOf(
        -22.580964955199917, 0.33157834224954785, 0.3784006288616245,
        -0.018418507506048017, -0.008473211081849586,
    )

    fun estimatePct(
        heightMm: Int,
        weightDeciKg: Int,
        ageYears: Int,
        sexMale: Boolean,
        r50DeciOhm: Int,
        r250DeciOhm: Int,
    ): Float? {
        if (heightMm !in 1480..1940 || weightDeciKg !in 480..1050 ||
            ageYears !in 19..68 || r50DeciOhm !in 4500..7800 || r250DeciOhm <= 0
        ) return null
        val ratio = r250DeciOhm.toDouble() / r50DeciOhm
        if (ratio !in 0.78..0.95) return null
        val h = heightMm / 10.0
        val w = weightDeciKg / 10.0
        val r = r50DeciOhm / 10.0
        val c = if (sexMale) male else female
        val latentFfm = c[0] + c[1] * h + c[2] * w + c[3] * ageYears + c[4] * r
        val pct = (100.0 * (1.0 - latentFfm / w)).coerceIn(3.0, 75.0)
        return (floor(pct * 10.0 + 0.5) / 10.0).toFloat()
    }
}
