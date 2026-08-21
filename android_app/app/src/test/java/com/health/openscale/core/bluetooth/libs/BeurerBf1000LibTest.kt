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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

/**
 * Tests for the Beurer BF1000 Super Precision decoder.
 *
 * The vectors are from an openScale file-log capture taken against a BF1000:
 * 2A9D standard weight, 2A9C standard body composition, then Beurer-private
 * FFFF/0009 segmental fat and FFFF/000A segmental muscle notifications.
 */
class BeurerBf1000LibTest {
    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `decodes standard weight measurement with timestamp user bmi and height`() {
        // flags, kg weight, timestamp, scale user index, BMI, height.
        val decoded = BeurerBf1000Lib.parseWeightMeasurement(
            hex("0E 48 44 EA 07 08 15 00 02 0E 01 04 01 26 07")
        )

        assertThat(decoded).isNotNull()
        with(decoded!!) {
            assertThat(isKg).isTrue()
            assertThat(weightKg).isWithin(1e-4f).of(87.4f)
            assertThat(scaleUserIndex).isEqualTo(1)
            assertThat(bmi!!).isWithin(1e-4f).of(26.0f)
            assertThat(heightMeters!!).isWithin(1e-4f).of(1.83f)
        }

        with(Calendar.getInstance().apply { time = decoded.dateTime!! }) {
            assertThat(get(Calendar.YEAR)).isEqualTo(2026)
            assertThat(get(Calendar.MONTH)).isEqualTo(Calendar.AUGUST)
            assertThat(get(Calendar.DAY_OF_MONTH)).isEqualTo(21)
            assertThat(get(Calendar.HOUR_OF_DAY)).isEqualTo(0)
            assertThat(get(Calendar.MINUTE)).isEqualTo(2)
            assertThat(get(Calendar.SECOND)).isEqualTo(14)
        }
    }

    @Test
    fun `decodes body composition and derives displayed bone mass from soft lean mass`() {
        // flags, body fat %, BMR kJ, muscle %, soft lean mass, water mass, impedance.
        val decoded = BeurerBf1000Lib.parseBodyCompositionMeasurement(
            hex("98 03 72 00 A2 1E EA 01 74 39 D6 2B 02 12"),
            fallbackWeightKg = 87.4f
        )

        assertThat(decoded).isNotNull()
        with(decoded!!) {
            assertThat(isKg).isTrue()
            assertThat(isMultiPacket).isFalse()
            assertThat(bodyFatPercent).isWithin(1e-4f).of(11.4f)
            assertThat(bmrKcal!!).isWithin(1e-4f).of(1873.0f)
            assertThat(musclePercent!!).isWithin(1e-4f).of(49.0f)
            assertThat(softLeanMassKg!!).isWithin(1e-4f).of(73.54f)
            assertThat(waterMassKg!!).isWithin(1e-4f).of(56.11f)
            assertThat(impedanceOhm!!).isWithin(1e-4).of(461.0)
            assertThat(weightKg!!).isWithin(1e-4f).of(87.4f)
            assertThat(leanBodyMassKg!!).isWithin(1e-4f).of(77.4364f)
            assertThat(boneMassKg!!).isWithin(1e-4f).of(3.8964f)
        }
    }

    @Test
    fun `decodes private segmental fat measurement`() {
        val decoded = BeurerBf1000Lib.parseSegmentalFatMeasurement(
            hex("7E 32 8E 00 8F 00 63 00 81 00 7C 00")
        )

        assertThat(decoded).isNotNull()
        with(decoded!!) {
            assertThat(visceralFat).isWithin(1e-4f).of(5.0f)
            assertThat(leftArm).isWithin(1e-4f).of(14.2f)
            assertThat(rightArm).isWithin(1e-4f).of(14.3f)
            assertThat(torso).isWithin(1e-4f).of(9.9f)
            assertThat(leftLeg).isWithin(1e-4f).of(12.9f)
            assertThat(rightLeg).isWithin(1e-4f).of(12.4f)
        }
    }

    @Test
    fun `decodes private segmental muscle measurement`() {
        val decoded = BeurerBf1000Lib.parseSegmentalMuscleMeasurement(
            hex("3E EA 01 E9 01 E8 01 F2 01 F5 01")
        )

        assertThat(decoded).isNotNull()
        with(decoded!!) {
            assertThat(leftArm).isWithin(1e-4f).of(49.0f)
            assertThat(rightArm).isWithin(1e-4f).of(48.9f)
            assertThat(torso).isWithin(1e-4f).of(48.8f)
            assertThat(leftLeg).isWithin(1e-4f).of(49.8f)
            assertThat(rightLeg).isWithin(1e-4f).of(50.1f)
        }
    }

    @Test
    fun `rejects short or unexpected private packets`() {
        assertThat(BeurerBf1000Lib.parseSegmentalFatMeasurement(ByteArray(11))).isNull()
        assertThat(BeurerBf1000Lib.parseSegmentalMuscleMeasurement(ByteArray(10))).isNull()

        assertThat(BeurerBf1000Lib.parseSegmentalFatMeasurement(
            hex("00 32 8E 00 8F 00 63 00 81 00 7C 00")
        )).isNull()
        assertThat(BeurerBf1000Lib.parseSegmentalMuscleMeasurement(
            hex("00 EA 01 E9 01 E8 01 F2 01 F5 01")
        )).isNull()
    }
}
