/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
package com.health.openscale.testutil

import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.ValueWithDifference
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared, dependency-free builders for domain model fixtures used by JVM unit tests
 * (aggregation, filtering, enrichment). Keeps test bodies focused on the behaviour under test.
 */
object Fixtures {

    /** Epoch millis at a given local date/time in the system zone (matches the production zone handling). */
    fun ts(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDate.of(year, month, day).atTime(hour, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun type(
        id: Int,
        key: MeasurementTypeKey = MeasurementTypeKey.CUSTOM,
        inputType: InputFieldType = InputFieldType.FLOAT,
        displayOrder: Int = id,
        enabled: Boolean = true,
    ): MeasurementType = MeasurementType(
        id = id,
        key = key,
        unit = UnitType.NONE,
        inputType = inputType,
        displayOrder = displayOrder,
        isEnabled = enabled,
    )

    fun valueWithType(type: MeasurementType, value: Float, measurementId: Int = 0): MeasurementValueWithType =
        MeasurementValueWithType(
            value = MeasurementValue(measurementId = measurementId, typeId = type.id, floatValue = value),
            type = type,
        )

    fun mwv(
        measurementId: Int,
        timestamp: Long,
        values: List<MeasurementValueWithType>,
        userId: Int = 1,
    ): MeasurementWithValues = MeasurementWithValues(
        measurement = Measurement(id = measurementId, userId = userId, timestamp = timestamp),
        values = values,
    )

    /** Wraps a [MeasurementWithValues] as an [EnrichedMeasurement], deriving valuesWithTrend from its values. */
    fun enriched(mwv: MeasurementWithValues): EnrichedMeasurement = EnrichedMeasurement(
        measurementWithValues = mwv,
        measurementWithValuesProjected = emptyList(),
        valuesWithTrend = mwv.values.map { ValueWithDifference(currentValue = it) },
    )
}
