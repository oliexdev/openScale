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
import com.health.openscale.core.bluetooth.libs.S400HydrationShift
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.testutil.Fixtures
import org.junit.Test

class PreviousMeasurementMappingTest {
    private fun previous(high: Float? = 460f, low: Float? = 510f): MeasurementWithValues {
        val keys = listOf(MeasurementType.WEIGHT, MeasurementType.IMPEDANCE, MeasurementType.IMPEDANCE_LOW)
        val raw = listOf(75f, high, low)
        val types = MeasurementType.seedRows().associateBy { it.identity }
        val values = keys.mapIndexedNotNull { index, key ->
            raw[index]?.let { value ->
                val type = types.getValue(key.identity).copy(id = index + 1)
                Fixtures.valueWithType(type, value, measurementId = 10)
            }
        }
        return Fixtures.mwv(measurementId = 10, timestamp = 1000L, userId = 7, values = values)
    }

    @Test
    fun `previous snapshot retains both impedance bands`() {
        val snapshot = mapPreviousMeasurement(previous())!!
        assertThat(snapshot[MeasurementType.IMPEDANCE_LOW]?.value).isEqualTo(510f)
        assertThat(snapshot[MeasurementType.IMPEDANCE]?.value).isEqualTo(460f)
        assertThat(snapshot[MeasurementType.WEIGHT]?.value).isEqualTo(75f)
        assertThat(snapshot.userId).isEqualTo(7)
        assertThat(snapshot.dateTime?.time).isEqualTo(1000L)
    }

    @Test
    fun `S400 can calculate decreases zero and increases from the previous snapshot`() {
        val snapshot = mapPreviousMeasurement(previous())!!
        for (currentLow in listOf(500f, 510f, 520f)) {
            val shift = S400HydrationShift.shiftPct(
                currentLow = currentLow, currentHigh = 460f,
                previousLow = snapshot[MeasurementType.IMPEDANCE_LOW]?.value,
                previousHigh = snapshot[MeasurementType.IMPEDANCE]?.value,
            )
            val expected = ((currentLow / 460f) / (510f / 460f) - 1f) * 100f
            assertThat(shift).isWithin(0.0001f).of(expected)
        }
    }

    @Test
    fun `an absent band stays absent and prevents an invented shift`() {
        val snapshot = mapPreviousMeasurement(previous(high = null))!!
        assertThat(snapshot[MeasurementType.IMPEDANCE]).isNull()
        assertThat(S400HydrationShift.shiftPct(
            currentLow = 500f, currentHigh = 460f,
            previousLow = snapshot[MeasurementType.IMPEDANCE_LOW]?.value,
            previousHigh = snapshot[MeasurementType.IMPEDANCE]?.value,
        )).isNull()
    }

    @Test
    fun `no previous measurement stays absent`() {
        assertThat(mapPreviousMeasurement(null)).isNull()
    }
}
