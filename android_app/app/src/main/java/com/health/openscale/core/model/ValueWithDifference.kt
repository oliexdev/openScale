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

import com.health.openscale.core.data.Trend

/**
 * Holds a measurement value together with its delta to the previous value
 * and a simple trend indicator.
 *
 * @property currentValue The current value plus its type metadata.
 * @property difference Current minus previous value (null if not comparable).
 * @property trend Direction based on [difference], or NOT_APPLICABLE.
 */
data class ValueWithDifference(
    val currentValue: MeasurementValueWithType,
    val difference: Float? = null,
    val trend: Trend = Trend.NOT_APPLICABLE
)
