/*
 * openScale
 * Copyright (C) 2026 Hermes Agent (orion)
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies [S400BodyFatV235] against 275 real `POST /eco/scale/bcalc` request/response
 * pairs captured from Xiaomi's own calculation server (same fixture used by the prior
 * experimental candidate — see `docs/s400_v235_body_fat_recovery.md`).
 *
 * This recorded fixture allows residuals of 0.1 percentage point, not exact parity.
 * Twelve rows differ by 0.1. The cause is not established as a floating-point
 * artifact; the coefficients were fitted to quantized outputs. This fixture is
 * regression coverage over existing data, not a fresh independent holdout.
 */
class S400BodyFatV235Test {
    @Test
    fun recordedServerResponses() {
        val input = checkNotNull(javaClass.getResourceAsStream("/s400/body-fat-v235.tsv"))
        var checked = 0
        var withinRoundingTolerance = 0
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val t = line.split('\t')
                check(t.size == 10) { "malformed fixture row: $line" }
                val heightCm = t[1].toInt() / 10f
                val weightKg = t[2].toInt() / 10f
                val age = t[3].toInt()
                val sexMale = t[4] == "1"
                val impedanceHigh = t[5].toInt() / 10f
                val expected = t[7].toFloat()

                val actual = S400BodyFatV235.estimate(heightCm, weightKg, age, sexMale, impedanceHigh)
                assertNotNull(t[0], actual)
                val diff = abs(actual!! - expected)
                check(diff < 0.15f) { "${t[0]}: predicted=$actual observed=$expected diff=$diff" }
                if (diff > 0.0001f) withinRoundingTolerance++
                checked++
            }
        }
        assertEquals(275, checked)
        // Documented rounding-boundary count; update deliberately if the fixture changes,
        // never silently widen the per-row tolerance above to make this pass.
        assertEquals(12, withinRoundingTolerance)
    }

    @Test
    fun originalMaleReference() {
        val fat = S400BodyFatV235.estimate(185.0f, 87.0f, 36, true, 543.6f)
        assertEquals(23.7f, fat!!, 0.0001f)
    }

    @Test
    fun physicalWeighInProfileHeightMismatch() {
        // Observed in installed OpenScale: 186 cm profile -> 23.1%.
        // User reported Xiaomi: 185 cm profile -> 23.5% for this weigh-in.
        // Recalculation is a regression case, not a second on-device weigh-in.
        assertEquals(23.1f,
            S400BodyFatV235.estimate(186f, 86.2f, 36, true, 563.4f)!!, 0.0001f)
        assertEquals(23.5f,
            S400BodyFatV235.estimate(185f, 86.2f, 36, true, 563.4f)!!, 0.0001f)
    }

    @Test
    fun originalFemaleReference() {
        val fat = S400BodyFatV235.estimate(163.0f, 66.6f, 34, false, 572.5f)
        assertEquals(34.8f, fat!!, 0.0001f)
    }

    @Test
    fun rejectsOutOfDomainAge() {
        assertNull(S400BodyFatV235.estimate(185.0f, 87.0f, 15, true, 543.6f))
        assertNull(S400BodyFatV235.estimate(185.0f, 87.0f, 69, true, 543.6f))
    }

    @Test
    fun rejectsOutOfDomainAnthropometry() {
        assertNull(S400BodyFatV235.estimate(220.0f, 87.0f, 36, true, 543.6f))
        assertNull(S400BodyFatV235.estimate(185.0f, 150.0f, 36, true, 543.6f))
    }

    @Test
    fun rejectsOutOfDomainImpedance() {
        assertNull(S400BodyFatV235.estimate(185.0f, 87.0f, 36, true, 50.0f))
        assertNull(S400BodyFatV235.estimate(185.0f, 87.0f, 36, true, 1200.0f))
    }
}
