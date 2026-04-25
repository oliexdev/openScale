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
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.BodyCompositionPattern
import com.health.openscale.core.model.CompositionPatternType
import com.health.openscale.core.model.InsightConfidence
import com.health.openscale.core.model.MeasurementAnalysis
import com.health.openscale.core.model.MeasurementAnomaly
import com.health.openscale.core.model.MeasurementInsight
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.SeasonalPattern
import com.health.openscale.core.model.TrendDirection
import com.health.openscale.core.model.Volatility
import com.health.openscale.core.model.WeekdayPattern
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

@Singleton
class MeasurementInsightsUseCase @Inject constructor() {

    companion object {
        const val MIN_TOTAL_MEASUREMENTS = 5
        private const val MIN_WEEKDAY_HIGH = WeekdayPattern.MIN_MEASUREMENTS_PER_DAY
        private const val MIN_WEEKDAY_LOW = 2
        const val ANOMALY_WINDOW_SIZE = 20
        const val ANOMALY_Z_SCORE_THRESHOLD = 3.0f
        const val ANOMALY_GAP_RESET_DAYS = 30L
        private const val SHORT_TERM_TREND_DAYS = 30L
        private const val VOLATILITY_STABLE_THRESHOLD   = 0.01f
        private const val VOLATILITY_MODERATE_THRESHOLD = 0.03f
        const val CORRELATION_WINDOW_DAYS = 90L
        const val CORRELATION_MIN_MEASUREMENTS = 4
        const val PATTERN_HISTORY_WINDOWS = 5
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Computes a [MeasurementInsight] for the explicitly selected [primaryTypeId].
     * Returns an empty insight when there is insufficient data or no type is selected.
     *
     * @param measurements  Full measurement history for a single user, unsorted.
     * @param primaryTypeId ID of the user-selected [MeasurementType] to analyse.
     */
    fun compute(
        measurements: List<MeasurementWithValues>,
        primaryTypeId: Int?,
    ): MeasurementInsight {
        val empty = MeasurementInsight(
            measurementAnalysis    = null,
            bodyCompositionPattern = null,
            weekdayPattern         = null,
            seasonalPattern        = null,
            anomalies              = emptyList(),
            basedOnCount           = measurements.size,
            computedAt             = LocalDate.now(),
        )

        if (measurements.size < MIN_TOTAL_MEASUREMENTS || primaryTypeId == null) return empty

        // Sort ascending by timestamp once — all sub-computations rely on this order
        val sorted = measurements.sortedBy { it.measurement.timestamp }

        val primaryType: MeasurementType? = sorted
            .flatMap { it.values }
            .firstOrNull { it.type.id == primaryTypeId && isNumeric(it.type) }
            ?.type

        if (primaryType == null) return empty

        return MeasurementInsight(
            measurementAnalysis    = computeMeasurementAnalysis(sorted, primaryType),
            bodyCompositionPattern = computeBodyCompositionPattern(sorted),
            weekdayPattern         = computeWeekdayPattern(sorted, primaryType),
            seasonalPattern        = computeSeasonalPattern(sorted, primaryType),
            anomalies              = computeAnomalies(sorted, primaryType),
            basedOnCount           = sorted.size,
            computedAt             = LocalDate.now(),
        )
    }

    // -------------------------------------------------------------------------
    // Measurement analysis
    // -------------------------------------------------------------------------

    /**
     * Computes a [MeasurementAnalysis] covering first/last delta, min/max, volatility,
     * short- and long-term trend, rate per month, plateau detection, and best period.
     *
     * Returns null when fewer than 2 data points exist for [primaryType].
     */
    private fun computeMeasurementAnalysis(
        sorted: List<MeasurementWithValues>,
        primaryType: MeasurementType,
    ): MeasurementAnalysis? {
        val dataPoints: List<Pair<LocalDate, Float>> = sorted.mapNotNull { mwv ->
            val value = numericValueFor(mwv, primaryType) ?: return@mapNotNull null
            toLocalDate(mwv.measurement.timestamp) to value
        }

        if (dataPoints.size < 2) return null

        val confidence = if (dataPoints.size >= 10) InsightConfidence.HIGH else InsightConfidence.LOW

        val firstValue = dataPoints.first().second
        val lastValue  = dataPoints.last().second
        val deltaAbs   = lastValue - firstValue
        val deltaPct   = if (firstValue != 0f) (deltaAbs / firstValue) * 100f else 0f

        val minPoint = dataPoints.minBy { it.second }
        val maxPoint = dataPoints.maxBy { it.second }

        // Volatility via standard deviation relative to the mean
        val rawValues  = dataPoints.map { it.second }
        val mean       = rawValues.average().toFloat()
        val stdDev     = stdDev(rawValues, mean)
        val relStdDev  = if (mean != 0f) stdDev / mean else 0f
        val volatility = when {
            relStdDev < VOLATILITY_STABLE_THRESHOLD   -> Volatility.STABLE
            relStdDev < VOLATILITY_MODERATE_THRESHOLD -> Volatility.MODERATE
            else                                      -> Volatility.HIGH
        }

        // Long-term trend: first quarter avg vs last quarter avg
        val quarterSize     = (dataPoints.size / 4).coerceAtLeast(1)
        val firstQuarterAvg = dataPoints.take(quarterSize).map { it.second }.average().toFloat()
        val lastQuarterAvg  = dataPoints.takeLast(quarterSize).map { it.second }.average().toFloat()
        val longTermTrend   = classifyTrend(lastQuarterAvg - firstQuarterAvg, mean)

        // Short-term trend: recent window vs preceding window of equal length
        val lastDate        = dataPoints.last().first
        val recentCutoff    = lastDate.minusDays(SHORT_TERM_TREND_DAYS)
        val recentPoints    = dataPoints.filter { it.first >= recentCutoff }
        val precedingPoints = dataPoints.filter { it.first < recentCutoff }
            .takeLast(recentPoints.size.coerceAtLeast(1))
        val shortTermTrend  = if (recentPoints.isNotEmpty() && precedingPoints.isNotEmpty()) {
            classifyTrend(
                recentPoints.map { it.second }.average().toFloat() -
                        precedingPoints.map { it.second }.average().toFloat(),
                mean,
            )
        } else longTermTrend

        val totalMonths  = ChronoUnit.DAYS.between(
            dataPoints.first().first, dataPoints.last().first,
        ) / 30.44f
        val ratePerMonth = if (totalMonths > 0f) deltaAbs / totalMonths else 0f

        // Plateau detection at the tail of the series using half the stdDev as threshold
        val (plateauDays, plateauStartDate) = run {
            if (dataPoints.size < 3) return@run null to null
            val threshold = stdDev * 0.5f
            if (threshold < 1e-6f) return@run null to null
            var plateauStartIndex = dataPoints.size - 1
            for (i in dataPoints.indices.reversed().drop(1)) {
                if (abs(dataPoints[i + 1].second - dataPoints[i].second) > threshold) break
                plateauStartIndex = i
            }
            if (plateauStartIndex == dataPoints.size - 1) return@run null to null
            val days = ChronoUnit.DAYS.between(
                dataPoints[plateauStartIndex].first, dataPoints.last().first,
            ).toInt().takeIf { it > 0 }
            days to dataPoints[plateauStartIndex].first
        }

        // Best calendar month: largest absolute delta among months with ≥ 2 measurements
        data class MonthKey(val year: Int, val month: Month)
        val byMonth = dataPoints.groupBy { MonthKey(it.first.year, it.first.month) }
        var bestPeriodStart: LocalDate? = null
        var bestPeriodDelta: Float?     = null
        if (byMonth.size >= 2) {
            byMonth.entries.filter { (_, pts) -> pts.size >= 2 }.forEach { (key, pts) ->
                val monthDelta = pts.last().second - pts.first().second
                if (bestPeriodDelta == null || abs(monthDelta) > abs(bestPeriodDelta!!)) {
                    bestPeriodDelta = monthDelta
                    bestPeriodStart = LocalDate.of(key.year, key.month, 1)
                }
            }
        }

        // Preserve original timestamps for sparkline rendering
        val valueHistory = sorted.mapNotNull { mwv ->
            val value = numericValueFor(mwv, primaryType) ?: return@mapNotNull null
            mwv.measurement.timestamp to value
        }

        return MeasurementAnalysis(
            type             = primaryType,
            firstValue       = firstValue,
            lastValue        = lastValue,
            deltaAbsolute    = deltaAbs,
            deltaPercent     = deltaPct,
            minValue         = minPoint.second,
            minValueDate     = minPoint.first,
            maxValue         = maxPoint.second,
            maxValueDate     = maxPoint.first,
            volatility       = volatility,
            shortTermTrend   = shortTermTrend,
            longTermTrend    = longTermTrend,
            ratePerMonth     = ratePerMonth,
            plateauDays      = plateauDays,
            plateauStartDate = plateauStartDate,
            bestPeriodStart  = bestPeriodStart,
            bestPeriodDelta  = bestPeriodDelta,
            firstMeasuredOn  = dataPoints.first().first,
            lastMeasuredOn   = dataPoints.last().first,
            confidence       = confidence,
            valueHistory     = valueHistory,
        )
    }

    // -------------------------------------------------------------------------
    // Body composition pattern
    // -------------------------------------------------------------------------

    /**
     * Analyses weight, fat, muscle, and water together over the current
     * [CORRELATION_WINDOW_DAYS]-day window and attaches up to [PATTERN_HISTORY_WINDOWS]
     * preceding non-overlapping windows as history.
     *
     * Returns null when the current window contains no quad-complete measurements.
     */
    private fun computeBodyCompositionPattern(
        sorted: List<MeasurementWithValues>,
    ): BodyCompositionPattern? {
        val typesByKey: Map<MeasurementTypeKey, MeasurementType> = sorted
            .flatMap { it.values }
            .filter { isNumeric(it.type) }
            .associateBy { it.type.key }
            .mapValues { it.value.type }

        val weightType = typesByKey[MeasurementTypeKey.WEIGHT]    ?: return null
        val fatType    = typesByKey[MeasurementTypeKey.BODY_FAT]  ?: return null
        val muscleType = typesByKey[MeasurementTypeKey.MUSCLE]    ?: return null
        val waterType  = typesByKey[MeasurementTypeKey.WATER]     ?: return null

        data class QuadPoint(
            val date: LocalDate,
            val weight: Float,
            val fat: Float,
            val muscle: Float,
            val water: Float,
        )

        val datedSorted = sorted.map { it to toLocalDate(it.measurement.timestamp) }

        fun quadPointsInWindow(start: LocalDate, end: LocalDate) =
            datedSorted
                .filter { (_, date) -> date in start..<end }
                .mapNotNull { (mwv, date) ->
                    QuadPoint(
                        date   = date,
                        weight = numericValueFor(mwv, weightType) ?: return@mapNotNull null,
                        fat    = numericValueFor(mwv, fatType)    ?: return@mapNotNull null,
                        muscle = numericValueFor(mwv, muscleType) ?: return@mapNotNull null,
                        water  = numericValueFor(mwv, waterType)  ?: return@mapNotNull null,
                    )
                }

        fun List<Float>.trend(): TrendDirection {
            val mean        = average().toFloat()
            val quarterSize = (size / 4).coerceAtLeast(1)
            return classifyTrend(
                takeLast(quarterSize).average().toFloat() - take(quarterSize).average().toFloat(),
                mean,
            )
        }

        fun buildPattern(
            points: List<QuadPoint>,
            windowStart: LocalDate,
            windowEnd: LocalDate,
            history: List<BodyCompositionPattern> = emptyList(),
        ): BodyCompositionPattern {
            val wTrend   = points.map { it.weight }.trend()
            val fTrend   = points.map { it.fat }.trend()
            val mTrend   = points.map { it.muscle }.trend()
            val wtrTrend = points.map { it.water }.trend()
            return BodyCompositionPattern(
                weightTrend     = wTrend,
                fatTrend        = fTrend,
                muscleTrend     = mTrend,
                waterTrend      = wtrTrend,
                fatDelta        = points.last().fat    - points.first().fat,
                muscleDelta     = points.last().muscle - points.first().muscle,
                pattern         = classifyCompositionPattern(wTrend, fTrend, mTrend),
                basedOnCount    = points.size,
                windowStartDate = windowStart,
                windowEndDate   = windowEnd,
                confidence      = if (points.size >= CORRELATION_MIN_MEASUREMENTS * 2)
                    InsightConfidence.HIGH else InsightConfidence.LOW,
                history         = history,
            )
        }

        val today        = LocalDate.now()
        val currentStart = today.minusDays(CORRELATION_WINDOW_DAYS)

        val historyPatterns: List<BodyCompositionPattern> = (1..PATTERN_HISTORY_WINDOWS)
            .mapNotNull { windowIndex ->
                val windowEnd   = today.minusDays(CORRELATION_WINDOW_DAYS * windowIndex)
                val windowStart = windowEnd.minusDays(CORRELATION_WINDOW_DAYS)
                val points      = quadPointsInWindow(windowStart, windowEnd)
                if (points.size < CORRELATION_MIN_MEASUREMENTS) return@mapNotNull null
                buildPattern(points, windowStart, windowEnd)
            }
            .reversed()

        val currentPoints = quadPointsInWindow(currentStart, today)

        if (currentPoints.isEmpty()) return null

        if (currentPoints.size < CORRELATION_MIN_MEASUREMENTS) {
            return BodyCompositionPattern(
                weightTrend     = TrendDirection.STABLE,
                fatTrend        = TrendDirection.STABLE,
                muscleTrend     = TrendDirection.STABLE,
                waterTrend      = TrendDirection.STABLE,
                pattern         = CompositionPatternType.UNDEFINED,
                basedOnCount    = currentPoints.size,
                windowStartDate = currentPoints.first().date,
                windowEndDate   = currentPoints.last().date,
                confidence      = InsightConfidence.INSUFFICIENT,
                history         = emptyList(),
            )
        }

        return buildPattern(currentPoints, currentStart, today, historyPatterns)
    }

    private fun classifyCompositionPattern(
        wTrend: TrendDirection,
        fTrend: TrendDirection,
        mTrend: TrendDirection,
    ): CompositionPatternType = when {
        fTrend == TrendDirection.DOWN && mTrend == TrendDirection.UP   -> CompositionPatternType.RECOMPOSITION
        fTrend == TrendDirection.DOWN && mTrend != TrendDirection.DOWN -> CompositionPatternType.FAT_LOSS
        fTrend == TrendDirection.DOWN && mTrend == TrendDirection.DOWN -> CompositionPatternType.WEIGHT_LOSS_MIXED
        mTrend == TrendDirection.UP   && fTrend == TrendDirection.UP   -> CompositionPatternType.MUSCLE_AND_FAT_GAIN
        mTrend == TrendDirection.UP                                    -> CompositionPatternType.MUSCLE_GAIN
        fTrend == TrendDirection.UP                                    -> CompositionPatternType.FAT_GAIN
        wTrend == TrendDirection.DOWN && mTrend == TrendDirection.DOWN -> CompositionPatternType.WEIGHT_LOSS_MIXED
        mTrend == TrendDirection.DOWN                                  -> CompositionPatternType.WEIGHT_LOSS_MIXED
        else                                                           -> CompositionPatternType.STABLE
    }

    // -------------------------------------------------------------------------
    // Weekday pattern
    // -------------------------------------------------------------------------

    /**
     * Computes the average deviation from the overall mean per [DayOfWeek].
     * Returns null when any weekday has fewer than [MIN_WEEKDAY_LOW] measurements.
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
        val deviationByDay = valuesByDay.mapValues { (_, vs) -> vs.average().toFloat() - overallMean }
        val confidence     = if (countByDay.values.all { it >= MIN_WEEKDAY_HIGH })
            InsightConfidence.HIGH else InsightConfidence.LOW

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
     * Returns null when no data is available.
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
            yearsWithData >= SeasonalPattern.MIN_YEARS_FOR_PATTERN -> InsightConfidence.HIGH
            yearsWithData == 1                                     -> InsightConfidence.LOW
            else                                                   -> InsightConfidence.INSUFFICIENT
        }

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
     * Detects anomalies via rolling z-score over [ANOMALY_WINDOW_SIZE] preceding values.
     * A gap larger than [ANOMALY_GAP_RESET_DAYS] resets the baseline.
     * Results are sorted by date descending.
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

            if (prevDate != null &&
                ChronoUnit.DAYS.between(prevDate, date) > ANOMALY_GAP_RESET_DAYS
            ) window.clear()
            prevDate = date

            if (window.size >= ANOMALY_WINDOW_SIZE) {
                val mean   = window.average().toFloat()
                val sd     = stdDev(window, mean)
                if (sd > 0f) {
                    val zScore = abs((value - mean) / sd)
                    if (zScore >= ANOMALY_Z_SCORE_THRESHOLD) {
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

    /** Classifies a signed delta relative to the series mean as [TrendDirection]. */
    private fun classifyTrend(delta: Float, mean: Float): TrendDirection {
        val threshold = mean * 0.005f
        return when {
            delta >  threshold -> TrendDirection.UP
            delta < -threshold -> TrendDirection.DOWN
            else               -> TrendDirection.STABLE
        }
    }

    private fun isNumeric(type: MeasurementType): Boolean =
        type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT

    private fun numericValueFor(mwv: MeasurementWithValues, type: MeasurementType): Float? =
        mwv.values.firstOrNull { it.type.id == type.id }?.let { vt ->
            when (type.inputType) {
                InputFieldType.FLOAT -> vt.value.floatValue
                InputFieldType.INT   -> vt.value.intValue?.toFloat()
                else                 -> null
            }
        }

    private fun toLocalDate(timestampMillis: Long): LocalDate =
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun stdDev(values: Collection<Float>, mean: Float): Float {
        if (values.size < 2) return 0f
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size
        return sqrt(variance).toFloat()
    }
}