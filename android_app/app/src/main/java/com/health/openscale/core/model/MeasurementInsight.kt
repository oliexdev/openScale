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
import com.health.openscale.core.data.MeasurementType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * Represents the confidence level of a computed [MeasurementInsight].
 *
 * - [HIGH]: Sufficient, regular data — conclusions are reliable.
 * - [LOW]: Limited or irregular data — conclusions should be treated with caution.
 * - [INSUFFICIENT]: Not enough data to draw any meaningful conclusion — insight is hidden.
 */
enum class InsightConfidence { HIGH, LOW, INSUFFICIENT }

/** Direction of a long- or short-term value trend. */
enum class ShiftTrend { UP, DOWN, STABLE }

/**
 * How much a value fluctuates around its mean, derived from its standard deviation
 * relative to the overall value range.
 *
 * - [STABLE]:   Low fluctuation — the value changes gradually and predictably.
 * - [MODERATE]: Medium fluctuation — some variation but no extreme swings.
 * - [HIGH]:     High fluctuation — the value swings significantly day to day.
 */
enum class Volatility { STABLE, MODERATE, HIGH }

/**
 * A rich analysis of how a single measurement type has evolved over the user's
 * full measurement history.
 *
 * Beyond a simple first-to-last delta, this captures the rate of change,
 * plateau detection, best period, and short- vs. long-term trend direction —
 * giving the user actionable context rather than raw numbers.
 *
 * @property type             The measurement type being analysed.
 * @property firstValue       Value of the first recorded measurement.
 * @property lastValue        Value of the most recent measurement.
 * @property deltaAbsolute    Signed absolute change: [lastValue] − [firstValue].
 * @property deltaPercent     Relative change in percent: ([deltaAbsolute] / [firstValue]) × 100.
 * @property minValue         Lowest recorded value across the full history.
 * @property minValueDate     Date on which [minValue] was recorded.
 * @property maxValue         Highest recorded value across the full history.
 * @property maxValueDate     Date on which [maxValue] was recorded.
 * @property volatility       How much the value fluctuates around its mean.
 * @property shortTermTrend   Trend direction computed over the last 30 days of data.
 * @property longTermTrend    Trend direction computed over the full history.
 * @property ratePerMonth     Average absolute change per calendar month
 *                            (positive = increasing, negative = decreasing).
 * @property plateauDays      Number of consecutive days without a significant change
 *                            (> 0.5 % of mean) at the end of the history, or null if
 *                            no plateau is currently active.
 * @property bestPeriodStart  Start of the calendar month with the largest improvement,
 *                            null if fewer than two months of data are available.
 * @property bestPeriodDelta  The signed delta achieved in [bestPeriodStart]'s month,
 *                            null when [bestPeriodStart] is null.
 * @property firstMeasuredOn  Date of the first measurement included in this analysis.
 * @property lastMeasuredOn   Date of the most recent measurement included.
 * @property confidence       Overall reliability of this insight.
 */
@Immutable
data class BodyCompositionShift(
    val type: MeasurementType,
    val firstValue: Float,
    val lastValue: Float,
    val deltaAbsolute: Float,
    val deltaPercent: Float,
    val minValue: Float,
    val minValueDate: LocalDate,
    val maxValue: Float,
    val maxValueDate: LocalDate,
    val volatility: Volatility,
    val shortTermTrend: ShiftTrend,
    val longTermTrend: ShiftTrend,
    val ratePerMonth: Float,
    val plateauDays: Int?,
    val bestPeriodStart: LocalDate?,
    val bestPeriodDelta: Float?,
    val firstMeasuredOn: LocalDate,
    val lastMeasuredOn: LocalDate,
    val confidence: InsightConfidence,
)

/**
 * Represents the average value deviation per day of the week for a specific measurement type.
 *
 * The primary type is chosen by the caller rather than being hardcoded,
 * so custom types are fully supported.
 *
 * Requires at least [MIN_MEASUREMENTS_PER_DAY] measurements per weekday to produce
 * a [InsightConfidence.HIGH] confidence result.
 *
 * @property type                   The measurement type this pattern is based on.
 * @property overallMean            Average value across all days of the week.
 * @property deviationByDay         Map of [DayOfWeek] to average deviation from the overall mean
 *                                  (positive = above average, negative = below average).
 * @property measurementCountByDay  Number of measurements available per weekday.
 * @property heaviestDay            Weekday with the highest average value, null if insufficient data.
 * @property lightestDay            Weekday with the lowest average value, null if insufficient data.
 * @property confidence             Reliability of this insight based on data availability.
 */
@Immutable
data class WeekdayPattern(
    val type: MeasurementType,
    val overallMean: Float,
    val deviationByDay: Map<DayOfWeek, Float>,
    val measurementCountByDay: Map<DayOfWeek, Int>,
    val heaviestDay: DayOfWeek?,
    val lightestDay: DayOfWeek?,
    val confidence: InsightConfidence,
) {
    companion object {
        /** Minimum measurements per weekday for [InsightConfidence.HIGH]. */
        const val MIN_MEASUREMENTS_PER_DAY = 5
    }
}

/**
 * Represents the average value per calendar month grouped by year for a specific measurement type.
 * Used to detect recurring seasonal patterns across multiple years.
 *
 * Requires data spanning at least [MIN_YEARS_FOR_PATTERN] years to produce
 * a [InsightConfidence.HIGH] confidence result.
 *
 * @property type                        The measurement type this pattern is based on.
 * @property averageValueByMonthAndYear  Nested map of year → month → average value in the type's unit.
 * @property highestMonth                Month with the highest cross-year average, null if insufficient.
 * @property lowestMonth                 Month with the lowest cross-year average, null if insufficient.
 * @property yearsWithData               Number of distinct years covered by the data.
 * @property confidence                  Reliability of this insight based on data availability.
 */
@Immutable
data class SeasonalPattern(
    val type: MeasurementType,
    val averageValueByMonthAndYear: Map<Int, Map<Month, Float>>,
    val highestMonth: Month?,
    val lowestMonth: Month?,
    val yearsWithData: Int,
    val confidence: InsightConfidence,
) {
    companion object {
        /** Minimum distinct years required for [InsightConfidence.HIGH]. */
        const val MIN_YEARS_FOR_PATTERN = 2
    }
}

/**
 * A single detected anomaly in a measurement series.
 *
 * Detected via a rolling z-score over a sliding window of recent measurements.
 * A gap longer than [com.health.openscale.core.usecase.MeasurementInsightsUseCase.ANOMALY_GAP_RESET_DAYS]
 * resets the baseline to avoid false positives after measurement breaks.
 *
 * @property measurementId  ID of the measurement that triggered the anomaly.
 * @property date           Date of the anomalous measurement.
 * @property type           The [MeasurementType] in which the anomaly was detected.
 * @property value          The actual measured value.
 * @property expectedValue  Expected value based on the local rolling average.
 * @property deviation      Signed difference between [value] and [expectedValue].
 * @property zScore         Standardised deviation — values with |zScore| ≥ threshold are flagged.
 * @property comment        Optional user comment on that measurement date, if any.
 */
@Immutable
data class MeasurementAnomaly(
    val measurementId: Int,
    val date: LocalDate,
    val type: MeasurementType,
    val value: Float,
    val expectedValue: Float,
    val deviation: Float,
    val zScore: Float,
    val comment: String?,
)

/**
 * Top-level container for all computed insights derived from a user's measurement history.
 *
 * Each insight field is nullable — null means [InsightConfidence.INSUFFICIENT] data
 * for that specific analysis.
 *
 * Note: [MeasurementInsight] sits alongside the aggregation hierarchy rather than
 * extending it — it is derived from the full measurement history, not from a single entry.
 *
 * @property bodyCompositionShift  Rich analysis of how the primary type evolved over time, or null.
 * @property weekdayPattern        Average deviation per weekday for the primary type, or null.
 * @property seasonalPattern       Average value per month grouped by year, or null.
 * @property anomalies             Detected anomalies sorted by date descending, empty if none found.
 * @property basedOnCount          Total raw measurements used to compute these insights.
 * @property computedAt            Date on which these insights were last computed.
 */
@Immutable
data class MeasurementInsight(
    val bodyCompositionShift: BodyCompositionShift?,
    val weekdayPattern: WeekdayPattern?,
    val seasonalPattern: SeasonalPattern?,
    val anomalies: List<MeasurementAnomaly>,
    val basedOnCount: Int,
    val computedAt: LocalDate,
)