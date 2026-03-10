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

import androidx.compose.runtime.Immutable

/**
 * Wraps an [EnrichedMeasurement] with aggregation metadata computed by
 * [com.health.openscale.core.usecase.MeasurementAggregationUseCase].
 *
 * Naming follows the existing hierarchy:
 * ```
 * MeasurementWithValues
 *     → EnrichedMeasurement        (+ trend, diff, projection)
 *         → AggregatedMeasurement  (+ count, period bounds, key)
 * ```
 *
 * ### When [aggregatedFromCount] == 1
 * The entry represents a single raw measurement. [periodStartMillis] and [periodEndMillis]
 * span exactly that measurement's calendar day, and [periodKey] is its ISO date string.
 *
 * ### When [aggregatedFromCount] > 1
 * The entry represents the average of multiple raw measurements within a time period.
 * The [enriched] measurement holds synthetic (averaged) values with id == -1.
 *
 * Screens can use [isAggregated] to decide whether to show the "⌀" prefix, a drill-down
 * arrow, or other aggregation-specific UI without querying the aggregation level themselves.
 *
 * @property enriched            The (possibly aggregated) enriched measurement.
 * @property aggregatedFromCount Number of raw measurements merged into this entry (≥ 1).
 * @property periodStartMillis   Inclusive start of the represented period (epoch ms).
 * @property periodEndMillis     Exclusive end of the represented period (epoch ms).
 * @property periodKey           Stable, locale-independent identifier for this period,
 *                               suitable as a [androidx.compose.foundation.lazy.LazyColumn]
 *                               item key or map key. Example: "2025-W15", "2025-4", "2025".
 */
@Immutable
data class AggregatedMeasurement(
    val enriched: EnrichedMeasurement,
    val aggregatedFromCount: Int,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val periodKey: String,
) {
    /** True when this entry was produced by averaging two or more raw measurements. */
    val isAggregated: Boolean get() = aggregatedFromCount > 1
}