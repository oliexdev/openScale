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
package com.health.openscale.core.model

/**
 * A measurement enriched for UI consumption. It holds the original data,
 * value-level trend/delta information, and optionally, future projection points.
 *
 * @property measurementWithValues The raw measurement and its values.
 * @property valuesWithTrend Per-value enrichment including difference and trend.
 * @property measurementWithValuesProjected A list of "virtual" future MeasurementWithValues points.
 */
data class EnrichedMeasurement(
    val measurementWithValues: MeasurementWithValues,
    val measurementWithValuesProjected: List<MeasurementWithValues>,
    val valuesWithTrend: List<ValueWithDifference>,
)
