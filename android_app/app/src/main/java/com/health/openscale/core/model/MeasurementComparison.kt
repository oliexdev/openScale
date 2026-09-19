/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
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

import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Trend

/**
 * Detailed comparison for an individual [MeasurementType] across two measurements.
 *
 * @property type The measurement type metadata (name, unit, icon, color).
 * @property baseValue Numeric value in the baseline (older/reference) measurement, if available.
 * @property targetValue Numeric value in the target (newer/comparison) measurement, if available.
 * @property baseFormatted Human-readable formatted string with unit for base value (e.g. "80.5 kg").
 * @property targetFormatted Human-readable formatted string with unit for target value (e.g. "78.2 kg").
 * @property difference Target value minus base value (null if either value is absent or non-numeric).
 * @property differenceFormatted Formatted signed delta with unit (e.g. "-2.3 kg").
 * @property percentChange Relative change in percent: `((target - base) / abs(base)) * 100`.
 * @property percentChangeFormatted Formatted signed percentage string (e.g. "-2.9 %").
 * @property ratePerWeek Change extrapolated to a 7-day rate based on elapsed time.
 * @property ratePerWeekFormatted Formatted weekly rate string (e.g. "-0.5 kg/week").
 * @property trend Direction indicator based on the numeric difference.
 */
data class MeasurementComparisonItem(
    val type: MeasurementType,
    val baseValue: Float? = null,
    val targetValue: Float? = null,
    val baseFormatted: String,
    val targetFormatted: String,
    val difference: Float? = null,
    val differenceFormatted: String? = null,
    val percentChange: Float? = null,
    val percentChangeFormatted: String? = null,
    val ratePerWeek: Float? = null,
    val ratePerWeekFormatted: String? = null,
    val trend: Trend = Trend.NOT_APPLICABLE,
) {
    /** True if the metric is present in both measurements. */
    val isPresentInBoth: Boolean
        get() = baseFormatted != "-" && targetFormatted != "-"

    /** True if difference is non-null. */
    val hasDifference: Boolean
        get() = difference != null
}

/**
 * Result of comparing two [MeasurementWithValues] entries.
 *
 * Typically [baseMeasurement] is the chronologically earlier measurement and [targetMeasurement]
 * is the later measurement, though the user may invert the direction.
 *
 * @property baseMeasurement The baseline measurement.
 * @property targetMeasurement The target measurement being compared against baseline.
 * @property daysBetween Number of calendar days elapsed between the two measurements.
 * @property items Metric-by-metric comparison results.
 */
data class MeasurementComparison(
    val baseMeasurement: MeasurementWithValues,
    val targetMeasurement: MeasurementWithValues,
    val daysBetween: Long,
    val items: List<MeasurementComparisonItem>,
) {
    /** Quick access to the primary weight comparison item, if present. */
    val weightItem: MeasurementComparisonItem?
        get() = items.firstOrNull { it.type.key == MeasurementType.WEIGHT }
}
