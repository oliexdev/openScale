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
package com.health.openscale.core.bluetooth.data

import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.scales.MiScaleS400Handler
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import org.junit.Test

/** The envelope-plus-map behaviour every multi-packet protocol leans on. */
class ScaleMeasurementTest {

    @Test
    fun `S400 hydration shift preserves decreases and exact zero`() {
        val measurement = ScaleMeasurement()
        for (value in listOf(-4f, 0f, 4f)) {
            measurement[MiScaleS400Handler.HYDRATION_SHIFT] = Percent(value)
            assertThat(measurement[MiScaleS400Handler.HYDRATION_SHIFT]?.value).isEqualTo(value)
        }
    }

    @Test
    fun `device fields enforce their declared numeric policy`() {
        val accepted = mapOf(
            MeasurementType.NumericPolicy.POSITIVE to setOf(4),
            MeasurementType.NumericPolicy.NON_NEGATIVE to setOf(0, 4),
            MeasurementType.NumericPolicy.SIGNED to setOf(-4, 0, 4)
        )
        for ((policy, validValues) in accepted) {
            val percent = MeasurementType.devicePercent(
                "test.percent", R.string.measurement_type_weight, numericPolicy = policy
            )
            val number = MeasurementType.deviceFloat(
                "test.float", R.string.measurement_type_weight, numericPolicy = policy
            )
            val score = MeasurementType.deviceInt(
                "test.int", R.string.measurement_type_weight, numericPolicy = policy
            )
            for (value in listOf(-4, 0, 4)) {
                val measurement = ScaleMeasurement()
                measurement[percent] = Percent(value.toFloat())
                measurement[number] = value.toFloat()
                measurement[score] = value
                val expected = value.takeIf { it in validValues }
                assertThat(measurement[percent]?.value).isEqualTo(expected?.toFloat())
                assertThat(measurement[number]).isEqualTo(expected?.toFloat())
                assertThat(measurement[score]).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `device fields still reject zero and negative values by default`() {
        val number = MeasurementType.deviceFloat("test.positive_float", R.string.measurement_type_weight)
        val score = MeasurementType.deviceInt("test.positive_int", R.string.measurement_type_weight)
        for (value in listOf(-4, 0)) {
            val measurement = ScaleMeasurement()
            measurement[deviceKey] = Percent(value.toFloat())
            measurement[number] = value.toFloat()
            measurement[score] = value
            measurement[MeasurementType.WEIGHT] = Kg(value.toFloat())
            measurement[MeasurementType.HEART_RATE] = Bpm(value)
            assertThat(measurement.values).isEmpty()
        }
    }

    @Test
    fun `every numeric policy rejects nonfinite values`() {
        for (policy in MeasurementType.NumericPolicy.entries) {
            val percent = MeasurementType.devicePercent(
                "test.percent", R.string.measurement_type_weight, numericPolicy = policy
            )
            val number = MeasurementType.deviceFloat(
                "test.float", R.string.measurement_type_weight, numericPolicy = policy
            )
            for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
                val measurement = ScaleMeasurement()
                measurement[percent] = Percent(value)
                measurement[number] = value
                assertThat(measurement.values).isEmpty()
            }
        }
    }

    @Test
    fun `merge skips placeholder zero but preserves zero when it is a real value`() {
        val rating = MeasurementType.deviceInt(
            "test.rating", R.string.measurement_type_weight,
            numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE
        )
        val first = ScaleMeasurement().apply {
            this[deviceKey] = Percent(0f)
            this[rating] = 0
        }
        val later = ScaleMeasurement().apply {
            this[deviceKey] = Percent(22.4f)
            this[rating] = 3
        }
        first.mergeWith(later)
        assertThat(first[deviceKey]?.value).isEqualTo(22.4f)
        assertThat(first[rating]).isEqualTo(0)
    }

    @Test
    fun `signed impedance keeps zero and negative values but rejects nonfinite values`() {
        val reactance = MeasurementType.deviceOhm(
            "test.reactance", R.string.measurement_type_impedance,
            numericPolicy = MeasurementType.NumericPolicy.SIGNED
        )
        for (value in listOf(-20f, 0f, 20f)) {
            val measurement = ScaleMeasurement()
            measurement[reactance] = Ohm(value)
            assertThat(measurement[reactance]?.value).isEqualTo(value)
        }
        for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            val measurement = ScaleMeasurement()
            measurement[reactance] = Ohm(value)
            assertThat(measurement.values).isEmpty()
        }
    }

    private val deviceKey = MeasurementType.devicePercent(
        "segmental.fat.left_arm", R.string.measurement_type_weight
    )

    @Test
    fun `absence is absence - values that never describe a measurement are dropped`() {
        val m = ScaleMeasurement()
        m[MeasurementType.WEIGHT] = Kg(0f)            // the old sentinel for "not reported"
        m[MeasurementType.BODY_FAT] = Percent(Float.NaN)
        m[MeasurementType.HEART_RATE] = Bpm(0)
        m[MeasurementType.COMMENT] = "  "

        assertThat(m.values).isEmpty()
        assertThat(m.hasWeight()).isFalse()

        m[MeasurementType.WEIGHT] = Kg(72.5f)
        assertThat(m.hasWeight()).isTrue()
        assertThat(m[MeasurementType.WEIGHT]?.value).isEqualTo(72.5f)
    }

    @Test
    fun `predefined and device keys ride the same map`() {
        val m = ScaleMeasurement()
        m[MeasurementType.WEIGHT] = Kg(72.5f)
        m[deviceKey] = Percent(12.4f)
        m[MeasurementType.HEART_RATE] = Bpm(62)

        assertThat(m.values).hasSize(3)
        assertThat(m[deviceKey]?.value).isEqualTo(12.4f)
        assertThat(m[MeasurementType.HEART_RATE]?.value).isEqualTo(62)
    }

    @Test
    fun `merge fills gaps without overwriting what was collected first`() {
        val target = ScaleMeasurement(userId = 0xFF).apply { this[MeasurementType.WEIGHT] = Kg(72.5f) }
        val incoming = ScaleMeasurement(userId = 3).apply {
            this[MeasurementType.WEIGHT] = Kg(99f)
            this[MeasurementType.BODY_FAT] = Percent(21.3f)
        }

        target.mergeWith(incoming)

        assertThat(target[MeasurementType.WEIGHT]?.value).isEqualTo(72.5f)
        assertThat(target[MeasurementType.BODY_FAT]?.value).isEqualTo(21.3f)
        assertThat(target.userId).isEqualTo(3)
    }

    @Test
    fun `snapshot detaches the map so later mutations do not leak into published data`() {
        val acc = ScaleMeasurement().apply { this[MeasurementType.WEIGHT] = Kg(72.5f) }

        val published = acc.snapshot()
        acc[MeasurementType.BODY_FAT] = Percent(21.3f)
        acc.values.clear()

        assertThat(published[MeasurementType.WEIGHT]?.value).isEqualTo(72.5f)
        assertThat(published.values).hasSize(1)
    }
}
