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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.service

import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Trend
import com.health.openscale.core.model.EnrichedMeasurement

import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.ValueWithDifference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enriches a chronological list of [MeasurementWithValues] by calculating
 * per-type differences and trend directions against the previous measurement.
 */
@Singleton
class MeasurementEnricher @Inject constructor(
    private val trendCalculator: TrendCalculator
) {

    /**
     * Enriches [measurements] with differences/trends and sorts values by [MeasurementType.displayOrder].
     *
     * @param measurements List ordered from newest → oldest (the previous item is at index+1).
     * @param allTypes Global type catalog used to resolve canonical ordering and enabled state.
     */
    fun enrich(
        measurements: List<MeasurementWithValues>,
        allTypes: List<MeasurementType>
    ): List<EnrichedMeasurement> {
        if (measurements.isEmpty()) return emptyList()

        // Quick lookup of type metadata by id
        val typesById = allTypes.associateBy { it.id }

        return measurements.mapIndexed { index, current ->
            val previous: MeasurementWithValues? = measurements.getOrNull(index + 1)

            // Build enriched values for this measurement
            val enriched = current.values.mapNotNull { currV ->
                val type = typesById[currV.type.id] ?: return@mapNotNull null
                if (!type.isEnabled) return@mapNotNull null

                val prevV: MeasurementValueWithType? =
                    previous?.values?.firstOrNull { it.type.id == type.id }

                toValueWithDifference(currV.copy(type = type), prevV)
            }.sortedBy { it.currentValue.type.displayOrder }

            EnrichedMeasurement(current, enriched)
        }
    }

    /**
     * Produces a [ValueWithDifference] for one value versus its previous counterpart (same type).
     */
    private fun toValueWithDifference(
        current: MeasurementValueWithType,
        previous: MeasurementValueWithType?
    ): ValueWithDifference {
        val currNum = extractNumeric(current)
        val prevNum = previous?.let { extractNumeric(it) }

        val diff: Float?
        val trend: Trend

        if (currNum != null && prevNum != null) {
            diff = currNum - prevNum
            trend = trendCalculator.calculate(currNum, prevNum)
        } else {
            diff = null
            trend = Trend.NOT_APPLICABLE
        }

        return ValueWithDifference(
            currentValue = current,
            difference = diff,
            trend = trend
        )
    }

    /**
     * Extracts a numeric representation as Float when the input type is numeric.
     * Returns null for non-numeric types.
     */
    private fun extractNumeric(v: MeasurementValueWithType): Float? = when (v.type.inputType) {
        InputFieldType.FLOAT -> v.value.floatValue
        InputFieldType.INT   -> v.value.intValue?.toFloat()
        else -> null
    }
}
