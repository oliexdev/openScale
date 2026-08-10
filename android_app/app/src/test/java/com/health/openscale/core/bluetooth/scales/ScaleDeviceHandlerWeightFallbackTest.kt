/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [ScaleDeviceHandler.fallbackWeightKg].
 *
 * The property that matters is the last one: handlers push this value into a
 * user record on the wire, and a real Huawei CH100 capture showed the scale
 * answering a weight-0 record with 127 USER_CHANGED re-requests in 33 seconds.
 */
class ScaleDeviceHandlerWeightFallbackTest {

    @Test
    fun `prefers the last measured weight`() {
        assertThat(ScaleDeviceHandler.fallbackWeightKg(126.6f, 80f, 185f)).isEqualTo(126.6f)
    }

    @Test
    fun `falls back to the profile's initial weight`() {
        assertThat(ScaleDeviceHandler.fallbackWeightKg(null, 80f, 185f)).isEqualTo(80f)
    }

    @Test
    fun `estimates from height when nothing is known`() {
        // BMI 22 at 1.85 m
        assertThat(ScaleDeviceHandler.fallbackWeightKg(null, 0f, 185f)).isWithin(0.1f).of(75.3f)
    }

    @Test
    fun `uses a last resort when height is missing too`() {
        assertThat(ScaleDeviceHandler.fallbackWeightKg(null, 0f, -1f)).isEqualTo(70f)
    }

    @Test
    fun `never returns zero or a non-finite value`() {
        val inputs = listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)
        for (last in inputs + null) {
            for (initial in inputs) {
                for (height in inputs) {
                    val w = ScaleDeviceHandler.fallbackWeightKg(last, initial, height)
                    assertThat(w).isGreaterThan(0f)
                    assertThat(w.isFinite()).isTrue()
                }
            }
        }
    }
}
