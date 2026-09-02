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
import com.health.openscale.core.data.MeasurementType
import org.junit.Test
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Percent

/** The envelope-plus-map behaviour every multi-packet protocol leans on. */
class ScaleMeasurementTest {

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
