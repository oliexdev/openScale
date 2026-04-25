package com.health.openscale.core.model

import androidx.compose.runtime.Immutable
import com.health.openscale.core.data.MeasurementType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/** Reliability level of a computed insight section. */
enum class InsightConfidence { HIGH, LOW, INSUFFICIENT }

/** Direction of a value trend over a given time window. */
enum class TrendDirection { UP, DOWN, STABLE }

/**
 * Fluctuation level of a measurement series around its mean.
 *
 * Derived from the standard deviation relative to the overall mean:
 * - [STABLE]:   gradual, predictable changes
 * - [MODERATE]: some variation, no extreme swings
 * - [HIGH]:     significant day-to-day swings
 */
enum class Volatility { STABLE, MODERATE, HIGH }

/**
 * Body composition pattern classified from four-metric trend analysis
 * (weight, fat, muscle, water).
 */
enum class CompositionPatternType {
    FAT_GAIN,
    MUSCLE_GAIN,
    MUSCLE_AND_FAT_GAIN,
    WEIGHT_LOSS_MIXED,
    FAT_LOSS,
    RECOMPOSITION,
    STABLE,
    UNDEFINED,
}

/**
 * Detected body composition pattern over the last
 * [com.health.openscale.core.usecase.MeasurementInsightsUseCase.CORRELATION_WINDOW_DAYS] days.
 *
 * Only measurements where all four metrics are present are included.
 * History carries up to [com.health.openscale.core.usecase.MeasurementInsightsUseCase.PATTERN_HISTORY_WINDOWS]
 * preceding non-overlapping windows, oldest first.
 * History entries always carry an empty [history] list to avoid recursive nesting.
 */
@Immutable
data class BodyCompositionPattern(
    val weightTrend: TrendDirection,
    val fatTrend: TrendDirection,
    val muscleTrend: TrendDirection,
    val waterTrend: TrendDirection,
    val pattern: CompositionPatternType,
    val basedOnCount: Int,
    val windowStartDate: LocalDate,
    val windowEndDate: LocalDate,
    val fatDelta: Float = 0f,
    val muscleDelta: Float = 0f,
    val confidence: InsightConfidence,
    val history: List<BodyCompositionPattern> = emptyList(),
)

/**
 * Rich analysis of how a single measurement type evolved over the user's full history.
 *
 * Covers rate of change, plateau detection, best period, and short- vs. long-term
 * trend direction — giving actionable context beyond a simple first-to-last delta.
 *
 * @property valueHistory  Ordered (timestamp ms, value) pairs used to render the sparkline.
 */
@Immutable
data class MeasurementAnalysis(
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
    val shortTermTrend: TrendDirection,
    val longTermTrend: TrendDirection,
    val ratePerMonth: Float,
    val plateauDays: Int?,
    val plateauStartDate: LocalDate?,
    val bestPeriodStart: LocalDate?,
    val bestPeriodDelta: Float?,
    val firstMeasuredOn: LocalDate,
    val lastMeasuredOn: LocalDate,
    val confidence: InsightConfidence,
    val valueHistory: List<Pair<Long, Float>> = emptyList(),
)

/**
 * Average value deviation per weekday for a specific measurement type.
 *
 * Requires at least [MIN_MEASUREMENTS_PER_DAY] measurements per weekday for
 * [InsightConfidence.HIGH].
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
        const val MIN_MEASUREMENTS_PER_DAY = 5
    }
}

/**
 * Average value per calendar month grouped by year for a specific measurement type.
 * Used to detect recurring seasonal patterns across multiple years.
 *
 * Requires data spanning at least [MIN_YEARS_FOR_PATTERN] years for
 * [InsightConfidence.HIGH].
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
        const val MIN_YEARS_FOR_PATTERN = 2
    }
}

/** A single anomalous measurement detected via rolling z-score. */
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
 * Top-level container for all computed insights for a single user.
 * Null fields indicate [InsightConfidence.INSUFFICIENT] data for that section.
 */
@Immutable
data class MeasurementInsight(
    val measurementAnalysis: MeasurementAnalysis?,
    val bodyCompositionPattern: BodyCompositionPattern?,
    val weekdayPattern: WeekdayPattern?,
    val seasonalPattern: SeasonalPattern?,
    val anomalies: List<MeasurementAnomaly>,
    val basedOnCount: Int,
    val computedAt: LocalDate,
)