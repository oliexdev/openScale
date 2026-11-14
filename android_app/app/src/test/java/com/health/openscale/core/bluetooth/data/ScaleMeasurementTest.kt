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
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import kotlinx.coroutines.runBlocking
import java.util.Date

/** The envelope-plus-map behaviour every multi-packet protocol leans on. */
class ScaleMeasurementTest {

    private fun stored(vararg values: Pair<MeasurementType, MeasurementValue>) = MeasurementWithValues(
        measurement = Measurement(id = 1, userId = 3, timestamp = 1_700_000_000_000L),
        values = values.map { (type, value) -> MeasurementValueWithType(value, type) },
    )

    private fun type(key: MeasurementType.Key<*>, unit: UnitType, id: Int) =
        MeasurementType(id = id, identity = key.identity, unit = unit, inputType = key.inputType)

    @Test
    fun `fromStored converts stored values back into the wire unit`() {
        val m = ScaleMeasurement.fromStored(
            stored(
                type(MeasurementType.WEIGHT, UnitType.LB, 1) to MeasurementValue(measurementId = 1, typeId = 1, floatValue = 176.37f),
                type(MeasurementType.BODY_FAT, UnitType.PERCENT, 2) to MeasurementValue(measurementId = 1, typeId = 2, floatValue = 22.5f),
                type(MeasurementType.IMPEDANCE, UnitType.OHM, 3) to MeasurementValue(measurementId = 1, typeId = 3, floatValue = 512f),
                type(MeasurementType.IMPEDANCE_LOW, UnitType.OHM, 4) to MeasurementValue(measurementId = 1, typeId = 4, floatValue = 548f),
                type(MeasurementType.HEART_RATE, UnitType.BPM, 5) to MeasurementValue(measurementId = 1, typeId = 5, intValue = 64),
            )
        )

        assertThat(m.userId).isEqualTo(3)
        assertThat(m.dateTime).isEqualTo(Date(1_700_000_000_000L))
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(0.01f).of(80f)
        assertThat(m[MeasurementType.BODY_FAT]).isEqualTo(Percent(22.5f))
        assertThat(m[MeasurementType.IMPEDANCE]).isEqualTo(Ohm(512f))
        assertThat(m[MeasurementType.IMPEDANCE_LOW]).isEqualTo(Ohm(548f))
        assertThat(m[MeasurementType.HEART_RATE]).isEqualTo(Bpm(64))
    }

    @Test
    fun `toStored and fromStored are inverse for a user with lb and a device key`() = runBlocking {
        val weightLb = type(MeasurementType.WEIGHT, UnitType.LB, 1)
        val impedance = type(MeasurementType.IMPEDANCE, UnitType.OHM, 2)
        val heartRate = type(MeasurementType.HEART_RATE, UnitType.BPM, 3)
        val original = ScaleMeasurement(userId = 3, dateTime = Date(1_700_000_000_000L)).apply {
            this[MeasurementType.WEIGHT] = Kg(80f)
            this[MeasurementType.IMPEDANCE] = Ohm(512f)
            this[MeasurementType.HEART_RATE] = Bpm(64)
            this[deviceKey] = Percent(12f)
        }
        val byKey = mapOf<MeasurementType.Key<*>, MeasurementType>(
            MeasurementType.WEIGHT to weightLb, MeasurementType.IMPEDANCE to impedance, MeasurementType.HEART_RATE to heartRate,
        )

        val rows = original.toStored { byKey[it] }
        assertThat(rows.single { it.typeId == 1 }.floatValue!!).isWithin(0.01f).of(176.37f)
        assertThat(rows).hasSize(3)

        val back = ScaleMeasurement.fromStored(
            stored(*rows.map { row -> byKey.values.single { it.id == row.typeId } to row }.toTypedArray())
        )
        assertThat(back[MeasurementType.WEIGHT]!!.value).isWithin(0.01f).of(80f)
        assertThat(back[MeasurementType.IMPEDANCE]).isEqualTo(Ohm(512f))
        assertThat(back[MeasurementType.HEART_RATE]).isEqualTo(Bpm(64))
    }

    @Test
    fun `fromStored skips types without a predefined key and empty values`() {
        val custom = MeasurementType(id = 7, identity = "user.waist_custom", unit = UnitType.CM, inputType = InputFieldType.FLOAT)
        val m = ScaleMeasurement.fromStored(
            stored(
                custom to MeasurementValue(measurementId = 1, typeId = 7, floatValue = 80f),
                type(MeasurementType.WEIGHT, UnitType.KG, 1) to MeasurementValue(measurementId = 1, typeId = 1),
            )
        )

        assertThat(m.values).isEmpty()
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
