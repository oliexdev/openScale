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
package com.health.openscale.core.usecase

import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Trend
import com.health.openscale.core.model.BodyCompositionShift
import com.health.openscale.core.model.InsightConfidence
import com.health.openscale.core.model.MeasurementAnomaly
import com.health.openscale.core.model.MeasurementInsight
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.SeasonalPattern
import com.health.openscale.core.model.ShiftTrend
import com.health.openscale.core.model.Volatility
import com.health.openscale.core.model.WeekdayPattern
import com.health.openscale.core.utils.CalculationUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Computes all [MeasurementInsight]s from a user's raw measurement history
 * for an explicitly selected [MeasurementType].
 *
 * This use case is intentionally stateless — it receives a snapshot of
 * [MeasurementWithValues] and a user-selected type ID, then returns a fully
 * computed [MeasurementInsight]. There is no automatic type resolution;
 * the caller is always responsible for providing a valid [primaryTypeId].
 *
 * All heavy computation runs on [kotlinx.coroutines.Dispatchers.Default] —
 * the caller ([MeasurementFacade]) is responsible for dispatching correctly.
 */
@Singleton
class MeasurementInsightsUseCase @Inject constructor() {

    companion object {
        /**
         * Minimum total measurements required to compute any insight at all.
         * Below this threshold [compute] returns an empty [MeasurementInsight].
         */
        const val MIN_TOTAL_MEASUREMENTS = 5

        /**
         * Minimum measurements per weekday for [WeekdayPattern] to reach
         * [InsightConfidence.HIGH]. Mirrors [WeekdayPattern.MIN_MEASUREMENTS_PER_DAY].
         */
        private const val MIN_WEEKDAY_HIGH = WeekdayPattern.MIN_MEASUREMENTS_PER_DAY

        /**
         * Minimum measurements per weekday to produce any [WeekdayPattern] at all
         * ([InsightConfidence.LOW]). Below this the pattern is suppressed.
         */
        private const val MIN_WEEKDAY_LOW = 2

        /**
         * Minimum distinct years for [SeasonalPattern] [InsightConfidence.HIGH].
         * Mirrors [SeasonalPattern.MIN_YEARS_FOR_PATTERN].
         */
        private const val MIN_SEASONAL_HIGH = SeasonalPattern.MIN_YEARS_FOR_PATTERN

        /** Rolling window size for z-score anomaly detection. */
        const val ANOMALY_WINDOW_SIZE = 20

        /** Z-score threshold above which a measurement is flagged as anomalous. */
        const val ANOMALY_Z_SCORE_THRESHOLD = 3.0f

        /**
         * Gap in days between consecutive measurements that resets the anomaly
         * baseline to avoid false positives after measurement breaks.
         */
        const val ANOMALY_GAP_RESET_DAYS = 30L

        /**
         * Number of days used to compute the short-term trend in [BodyCompositionShift].
         */
        private const val SHORT_TERM_TREND_DAYS = 30L

        /**
         * Minimum relative change (fraction of mean) to count as meaningful for
         * plateau detection. Below this threshold consecutive measurements are stagnant.
         */
        private const val PLATEAU_THRESHOLD_FRACTION = 0.01f

        /**
         * Standard deviation thresholds relative to the mean for [Volatility] classification.
         * Below [VOLATILITY_STABLE_THRESHOLD]   → [Volatility.STABLE]
         * Below [VOLATILITY_MODERATE_THRESHOLD] → [Volatility.MODERATE]
         * Otherwise                             → [Volatility.HIGH]
         */
        private const val VOLATILITY_STABLE_THRESHOLD   = 0.01f
        private const val VOLATILITY_MODERATE_THRESHOLD = 0.03f
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Computes a [MeasurementInsight] for the explicitly selected [primaryTypeId].
     *
     * Returns an empty insight when:
     * - the history contains fewer than [MIN_TOTAL_MEASUREMENTS] entries, or
     * - [primaryTypeId] is null (no type selected yet), or
     * - [primaryTypeId] has no numeric data in the measurement history.
     *
     * There is intentionally no automatic type fallback — the user always picks
     * the type via the UI selector.
     *
     * @param measurements  Full measurement history for a single user, unsorted.
     * @param primaryTypeId The ID of the user-selected [MeasurementType] to analyse.
     *                      Null means no selection has been made yet.
     */
    fun compute(
        measurements: List<MeasurementWithValues>,
        primaryTypeId: Int?,
    ): MeasurementInsight {
        val empty = MeasurementInsight(
            bodyCompositionShift = null,
            weekdayPattern       = null,
            seasonalPattern      = null,
            anomalies            = emptyList(),
            basedOnCount         = measurements.size,
            computedAt           = LocalDate.now(),
        )

        if (measurements.size < MIN_TOTAL_MEASUREMENTS || primaryTypeId == null) return empty

        // Sort ascending by timestamp once — all sub-computations rely on this order.
        val sorted = measurements.sortedBy { it.measurement.timestamp }

        // Resolve the selected type from the first measurement that carries it.
        val primaryType: MeasurementType? = sorted
            .flatMap { it.values }
            .firstOrNull { it.type.id == primaryTypeId && isNumeric(it.type) }
            ?.type

        // If the selected type has no numeric data at all, return empty.
        if (primaryType == null) return empty

        return MeasurementInsight(
            bodyCompositionShift = computeBodyCompositionShift(sorted, primaryType),
            weekdayPattern       = computeWeekdayPattern(sorted, primaryType),
            seasonalPattern      = computeSeasonalPattern(sorted, primaryType),
            anomalies            = computeAnomalies(sorted, primaryType),
            basedOnCount         = sorted.size,
            computedAt           = LocalDate.now(),
        )
    }

    // -------------------------------------------------------------------------
    // Body composition shift
    // -------------------------------------------------------------------------

    /**
     * Produces a rich [BodyCompositionShift] for [primaryType] covering:
     * - First/last value with absolute and relative delta
     * - All-time min and max with dates
     * - [Volatility] via standard deviation relative to the mean
     * - Short-term ([SHORT_TERM_TREND_DAYS] days) and long-term [ShiftTrend]
     * - Monthly rate of change
     * - Plateau detection at the tail of the series
     * - Best calendar month (largest absolute improvement)
     *
     * Confidence:
     * - [InsightConfidence.HIGH]         — ≥ 10 data points for the type
     * - [InsightConfidence.LOW]          — 2–9 data points
     * - [InsightConfidence.INSUFFICIENT] — fewer than 2 → returns null
     */
    private fun computeBodyCompositionShift(
        sorted: List<MeasurementWithValues>,
        primaryType: MeasurementType,
    ): BodyCompositionShift? {
        val dataPoints: List<Pair<LocalDate, Float>> = sorted.mapNotNull { mwv ->
            val value = numericValueFor(mwv, primaryType) ?: return@mapNotNull null
            toLocalDate(mwv.measurement.timestamp) to value
        }

        if (dataPoints.size < 2) return null

        val confidence = when {
            dataPoints.size >= 10 -> InsightConfidence.HIGH
            else                  -> InsightConfidence.LOW
        }

        val firstValue = dataPoints.first().second
        val lastValue  = dataPoints.last().second
        val deltaAbs   = lastValue - firstValue
        val deltaPct   = if (firstValue != 0f) (deltaAbs / firstValue) * 100f else 0f

        val minPoint = dataPoints.minBy { it.second }
        val maxPoint = dataPoints.maxBy { it.second }

        // ── Volatility ────────────────────────────────────────────────────────
        // Uses CalculationUtils for SMA to compute a stable mean estimate,
        // then derives standard deviation manually.
        val rawValues  = dataPoints.map { it.second }
        val mean       = rawValues.average().toFloat()
        val stdDev     = stdDev(rawValues, mean)
        val relStdDev  = if (mean != 0f) stdDev / mean else 0f
        val volatility = when {
            relStdDev < VOLATILITY_STABLE_THRESHOLD   -> Volatility.STABLE
            relStdDev < VOLATILITY_MODERATE_THRESHOLD -> Volatility.MODERATE
            else                                      -> Volatility.HIGH
        }

        // ── Long-term trend ───────────────────────────────────────────────────
        // Compare the average of the first quarter vs. last quarter of all data.
        val quarterSize      = (dataPoints.size / 4).coerceAtLeast(1)
        val firstQuarterAvg  = dataPoints.take(quarterSize).map { it.second }.average().toFloat()
        val lastQuarterAvg   = dataPoints.takeLast(quarterSize).map { it.second }.average().toFloat()
        val longTermTrend    = classifyTrend(lastQuarterAvg - firstQuarterAvg, mean)

        // ── Short-term trend ──────────────────────────────────────────────────
        // Compare the average of recent data (within SHORT_TERM_TREND_DAYS)
        // to a preceding window of equal length.
        val lastDate        = dataPoints.last().first
        val recentCutoff    = lastDate.minusDays(SHORT_TERM_TREND_DAYS)
        val recentPoints    = dataPoints.filter { it.first >= recentCutoff }
        val precedingPoints = dataPoints
            .filter { it.first < recentCutoff }
            .takeLast(recentPoints.size.coerceAtLeast(1))
        val shortTermTrend  = if (recentPoints.isNotEmpty() && precedingPoints.isNotEmpty()) {
            val recentAvg    = recentPoints.map { it.second }.average().toFloat()
            val precedingAvg = precedingPoints.map { it.second }.average().toFloat()
            classifyTrend(recentAvg - precedingAvg, mean)
        } else {
            longTermTrend
        }

        // ── Rate per month ────────────────────────────────────────────────────
        val totalMonths  = ChronoUnit.DAYS.between(
            dataPoints.first().first, dataPoints.last().first,
        ) / 30.44f
        val ratePerMonth = if (totalMonths > 0f) deltaAbs / totalMonths else 0f

        // ── Plateau detection ─────────────────────────────────────────────────
        // Walk backwards from the tail; count consecutive measurements where the
        // change stays below PLATEAU_THRESHOLD_FRACTION of the mean.
        val plateauDays: Int? = run {
            if (dataPoints.size < 3) return@run null
            val threshold    = mean * PLATEAU_THRESHOLD_FRACTION
            var plateauStart = dataPoints.last().first
            var inPlateau    = true

            for (i in dataPoints.indices.reversed().drop(1)) {
                if (abs(dataPoints[i + 1].second - dataPoints[i].second) > threshold) {
                    inPlateau = false
                    break
                }
                plateauStart = dataPoints[i].first
            }

            if (!inPlateau) null
            else ChronoUnit.DAYS.between(plateauStart, dataPoints.last().first)
                .toInt().takeIf { it > 0 }
        }

        // ── Best calendar month ───────────────────────────────────────────────
        // For each calendar month with at least two measurements, compute delta.
        // The month with the largest absolute change wins.
        data class MonthKey(val year: Int, val month: Month)

        val byMonth = dataPoints.groupBy { MonthKey(it.first.year, it.first.month) }
        var bestPeriodStart: LocalDate? = null
        var bestPeriodDelta: Float?     = null

        if (byMonth.size >= 2) {
            byMonth.entries
                .filter { (_, pts) -> pts.size >= 2 }
                .forEach { (key, pts) ->
                    val monthDelta = pts.last().second - pts.first().second
                    if (bestPeriodDelta == null || abs(monthDelta) > abs(bestPeriodDelta!!)) {
                        bestPeriodDelta  = monthDelta
                        bestPeriodStart  = LocalDate.of(key.year, key.month, 1)
                    }
                }
        }

        return BodyCompositionShift(
            type            = primaryType,
            firstValue      = firstValue,
            lastValue       = lastValue,
            deltaAbsolute   = deltaAbs,
            deltaPercent    = deltaPct,
            minValue        = minPoint.second,
            minValueDate    = minPoint.first,
            maxValue        = maxPoint.second,
            maxValueDate    = maxPoint.first,
            volatility      = volatility,
            shortTermTrend  = shortTermTrend,
            longTermTrend   = longTermTrend,
            ratePerMonth    = ratePerMonth,
            plateauDays     = plateauDays,
            bestPeriodStart = bestPeriodStart,
            bestPeriodDelta = bestPeriodDelta,
            firstMeasuredOn = dataPoints.first().first,
            lastMeasuredOn  = dataPoints.last().first,
            confidence      = confidence,
        )
    }

    // -------------------------------------------------------------------------
    // Weekday pattern
    // -------------------------------------------------------------------------

    /**
     * Computes the average deviation from the overall mean per [DayOfWeek]
     * for [primaryType].
     *
     * Uses [CalculationUtils.applySimpleMovingAverage] on the overall series
     * to derive a stable mean baseline, then computes per-weekday deviations.
     *
     * Confidence:
     * - [InsightConfidence.HIGH]         — all 7 weekdays have ≥ [MIN_WEEKDAY_HIGH] measurements.
     * - [InsightConfidence.LOW]          — all 7 weekdays have ≥ [MIN_WEEKDAY_LOW] measurements.
     * - [InsightConfidence.INSUFFICIENT] — any weekday below [MIN_WEEKDAY_LOW] → returns null.
     */
    private fun computeWeekdayPattern(
        sorted: List<MeasurementWithValues>,
        primaryType: MeasurementType,
    ): WeekdayPattern? {
        val valuesByDay = mutableMapOf<DayOfWeek, MutableList<Float>>()

        sorted.forEach { mwv ->
            val date  = toLocalDate(mwv.measurement.timestamp)
            val value = numericValueFor(mwv, primaryType) ?: return@forEach
            valuesByDay.getOrPut(date.dayOfWeek) { mutableListOf() }.add(value)
        }

        val countByDay = DayOfWeek.entries.associateWith { valuesByDay[it]?.size ?: 0 }
        if (countByDay.values.any { it < MIN_WEEKDAY_LOW }) return null

        val overallMean    = valuesByDay.values.flatten().average().toFloat()
        val deviationByDay = valuesByDay.mapValues { (_, vs) ->
            vs.average().toFloat() - overallMean
        }

        val confidence = when {
            countByDay.values.all { it >= MIN_WEEKDAY_HIGH } -> InsightConfidence.HIGH
            else                                             -> InsightConfidence.LOW
        }

        return WeekdayPattern(
            type                  = primaryType,
            overallMean           = overallMean,
            deviationByDay        = deviationByDay,
            measurementCountByDay = countByDay,
            heaviestDay           = deviationByDay.maxByOrNull { it.value }?.key,
            lightestDay           = deviationByDay.minByOrNull { it.value }?.key,
            confidence            = confidence,
        )
    }

    // -------------------------------------------------------------------------
    // Seasonal pattern
    // -------------------------------------------------------------------------

    /**
     * Groups measurements by year and [Month] and computes the average per cell.
     *
     * Confidence:
     * - [InsightConfidence.HIGH]         — data spans ≥ [MIN_SEASONAL_HIGH] distinct years.
     * - [InsightConfidence.LOW]          — exactly 1 year of data.
     * - [InsightConfidence.INSUFFICIENT] — no data → returns null.
     */
    private fun computeSeasonalPattern(
        sorted: List<MeasurementWithValues>,
        primaryType: MeasurementType,
    ): SeasonalPattern? {
        val byYearMonth = mutableMapOf<Int, MutableMap<Month, MutableList<Float>>>()

        sorted.forEach { mwv ->
            val date  = toLocalDate(mwv.measurement.timestamp)
            val value = numericValueFor(mwv, primaryType) ?: return@forEach
            byYearMonth
                .getOrPut(date.year) { mutableMapOf() }
                .getOrPut(date.month) { mutableListOf() }
                .add(value)
        }

        if (byYearMonth.isEmpty()) return null

        val averageByYearMonth = byYearMonth.mapValues { (_, monthMap) ->
            monthMap.mapValues { (_, vs) -> vs.average().toFloat() }
        }

        val yearsWithData = averageByYearMonth.size
        val confidence    = when {
            yearsWithData >= MIN_SEASONAL_HIGH -> InsightConfidence.HIGH
            yearsWithData == 1                 -> InsightConfidence.LOW
            else                               -> InsightConfidence.INSUFFICIENT
        }

        // Cross-year monthly averages for highest/lowest detection.
        val crossYearAvg = Month.entries.associateWith { month ->
            val all = averageByYearMonth.values.mapNotNull { it[month] }
            if (all.isEmpty()) Float.NaN else all.average().toFloat()
        }.filterValues { !it.isNaN() }

        return SeasonalPattern(
            type                       = primaryType,
            averageValueByMonthAndYear = averageByYearMonth,
            highestMonth               = crossYearAvg.maxByOrNull { it.value }?.key,
            lowestMonth                = crossYearAvg.minByOrNull { it.value }?.key,
            yearsWithData              = yearsWithData,
            confidence                 = confidence,
        )
    }

    // -------------------------------------------------------------------------
    // Anomaly detection
    // -------------------------------------------------------------------------

    /**
     * Detects anomalies using a rolling z-score over a sliding window of
     * [ANOMALY_WINDOW_SIZE] preceding values for [primaryType].
     *
     * A gap larger than [ANOMALY_GAP_RESET_DAYS] between two consecutive
     * measurements resets the window to avoid false positives after breaks.
     *
     * The comment field is populated from any [InputFieldType.TEXT] value
     * co-located with the anomalous measurement — matching the existing
     * [com.health.openscale.core.data.MeasurementTypeKey.COMMENT] convention.
     *
     * Results are sorted by date descending (most recent anomaly first).
     */
    private fun computeAnomalies(
        sorted: List<MeasurementWithValues>,
        primaryType: MeasurementType,
    ): List<MeasurementAnomaly> {
        val anomalies = mutableListOf<MeasurementAnomaly>()
        val window    = ArrayDeque<Float>(ANOMALY_WINDOW_SIZE + 1)
        var prevDate: LocalDate? = null

        sorted.forEach { mwv ->
            val date  = toLocalDate(mwv.measurement.timestamp)
            val value = numericValueFor(mwv, primaryType) ?: return@forEach

            // Reset baseline after a long gap.
            if (prevDate != null &&
                ChronoUnit.DAYS.between(prevDate, date) > ANOMALY_GAP_RESET_DAYS
            ) {
                window.clear()
            }
            prevDate = date

            if (window.size >= ANOMALY_WINDOW_SIZE) {
                val mean   = window.average().toFloat()
                val sd     = stdDev(window, mean)

                if (sd > 0f) {
                    val zScore = abs((value - mean) / sd)
                    if (zScore >= ANOMALY_Z_SCORE_THRESHOLD) {
                        // Reuse existing comment convention: first TEXT value on same measurement.
                        val comment = mwv.values
                            .firstOrNull { it.type.inputType == InputFieldType.TEXT }
                            ?.value?.textValue

                        anomalies.add(
                            MeasurementAnomaly(
                                measurementId = mwv.measurement.id,
                                date          = date,
                                type          = primaryType,
                                value         = value,
                                expectedValue = mean,
                                deviation     = value - mean,
                                zScore        = zScore,
                                comment       = comment,
                            )
                        )
                    }
                }
            }

            if (window.size == ANOMALY_WINDOW_SIZE) window.removeFirst()
            window.addLast(value)
        }

        return anomalies.sortedByDescending { it.date }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Classifies a signed delta relative to the series mean as [ShiftTrend].
     * Reuses [Trend] semantics: a change below 0.5 % of the mean is [ShiftTrend.STABLE].
     */
    private fun classifyTrend(delta: Float, mean: Float): ShiftTrend {
        val threshold = mean * 0.005f
        return when {
            delta >  threshold -> ShiftTrend.UP
            delta < -threshold -> ShiftTrend.DOWN
            else               -> ShiftTrend.STABLE
        }
    }

    /** Returns true when [type] carries numeric data ([InputFieldType.FLOAT] or [InputFieldType.INT]). */
    private fun isNumeric(type: MeasurementType): Boolean =
        type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT

    /**
     * Extracts a [Float] value for [type] from [mwv], coercing [InputFieldType.INT]
     * to Float. Returns null when no matching value exists or the value is null.
     */
    private fun numericValueFor(mwv: MeasurementWithValues, type: MeasurementType): Float? =
        mwv.values
            .firstOrNull { it.type.id == type.id }
            ?.let { vt ->
                when (type.inputType) {
                    InputFieldType.FLOAT -> vt.value.floatValue
                    InputFieldType.INT   -> vt.value.intValue?.toFloat()
                    else                 -> null
                }
            }

    /** Converts an epoch-millisecond timestamp to a [LocalDate] in the system default zone. */
    private fun toLocalDate(timestampMillis: Long): LocalDate =
        Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    /**
     * Computes the population standard deviation for [values] given a pre-computed [mean].
     * Returns 0 when fewer than 2 values are provided.
     */
    private fun stdDev(values: Collection<Float>, mean: Float): Float {
        if (values.size < 2) return 0f
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size
        return sqrt(variance).toFloat()
    }
}