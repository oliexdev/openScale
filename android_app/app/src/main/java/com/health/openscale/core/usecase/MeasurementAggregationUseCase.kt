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

import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.Trend
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.ValueWithDifference
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case that aggregates a list of [EnrichedMeasurement] into a smaller list
 * where each entry represents a time period (day, week, month, or year).
 *
 * Aggregation rules:
 * - NONE: returns the original list unchanged.
 * - DAY/WEEK/MONTH/YEAR: groups measurements by the period and averages numeric values.
 * - The representative timestamp of an aggregated entry is the midpoint of the period.
 * - Non-numeric types (TEXT, DATE, TIME, USER) are excluded from aggregated entries.
 * - Differences and trends are calculated between consecutive aggregated periods,
 *   so consumers (Overview, Table, Statistics, Graph) get diff/trend for free.
 * - The resulting list is sorted newest → oldest (same order as the input).
 */
@Singleton
class MeasurementAggregationUseCase @Inject constructor() {

    /**
     * Aggregates [measurements] according to [level].
     *
     * @param measurements Chronologically sorted list (newest → oldest).
     * @param level The desired aggregation granularity.
     * @return Aggregated list (newest → oldest) with diff/trend populated,
     *         or the original list when level is [AggregationLevel.NONE].
     */
    fun aggregate(
        measurements: List<EnrichedMeasurement>,
        level: AggregationLevel
    ): List<EnrichedMeasurement> {
        if (level == AggregationLevel.NONE || measurements.isEmpty()) return measurements

        val zone = ZoneId.systemDefault()

        // Hoist WeekFields outside the groupBy lambda (locale lookup is not free)
        val weekFields = if (level == AggregationLevel.WEEK) WeekFields.of(Locale.getDefault()) else null

        // Group by period key
        val grouped: Map<String, List<EnrichedMeasurement>> = measurements
            .groupBy { em ->
                val date = Instant.ofEpochMilli(em.measurementWithValues.measurement.timestamp)
                    .atZone(zone)
                    .toLocalDate()
                when (level) {
                    AggregationLevel.NONE  -> date.toString()
                    AggregationLevel.DAY   -> date.toString()
                    AggregationLevel.WEEK  -> {
                        val wf   = weekFields!!
                        val week = date.get(wf.weekOfWeekBasedYear())
                        val year = date.get(wf.weekBasedYear())
                        "$year-W$week"
                    }
                    AggregationLevel.MONTH -> "${date.year}-${date.monthValue}"
                    AggregationLevel.YEAR  -> "${date.year}"
                }
            }

        // Build one aggregated entry per group, sorted oldest → newest for diff calculation
        val sortedOldestFirst: List<EnrichedMeasurement> = grouped.values
            .mapNotNull { group -> buildAggregatedEntry(group, zone) }
            .sortedBy { it.measurementWithValues.measurement.timestamp }

        // Calculate diff + trend between consecutive periods (oldest → newest)
        // then reverse back to newest → oldest for the UI
        return calculateDiffsAndTrends(sortedOldestFirst)
            .sortedByDescending { it.measurementWithValues.measurement.timestamp }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a single aggregated [EnrichedMeasurement] from a group of measurements
     * belonging to the same time period.
     * Diff/trend are left empty here — they are filled in by [calculateDiffsAndTrends].
     */
    private fun buildAggregatedEntry(
        group: List<EnrichedMeasurement>,
        zone: ZoneId
    ): EnrichedMeasurement? {
        if (group.isEmpty()) return null

        val timestamps   = group.map { it.measurementWithValues.measurement.timestamp }
        val minTs        = timestamps.min()
        val maxTs        = timestamps.max()
        val midTimestamp = minTs + (maxTs - minTs) / 2L

        val userId = group.first().measurementWithValues.measurement.userId

        // Collect all numeric values per typeId and average them
        // flatMap + groupBy avoids repeated mutableList mutations
        val valuesByType: Map<Int, List<Pair<MeasurementValueWithType, Float>>> = group
            .flatMap { em -> em.measurementWithValues.values }
            .mapNotNull { vwt ->
                val numeric = when (vwt.type.inputType) {
                    InputFieldType.FLOAT -> vwt.value.floatValue
                    InputFieldType.INT   -> vwt.value.intValue?.toFloat()
                    else                 -> null
                } ?: return@mapNotNull null
                vwt to numeric
            }
            .groupBy { (vwt, _) -> vwt.type.id }

        val aggregatedValues: List<MeasurementValueWithType> = valuesByType.map { (_, pairs) ->
            val avg         = pairs.map { it.second }.average().toFloat()
            val templateVwt = pairs.first().first
            val syntheticVal = templateVwt.value.copy(
                id            = -1,
                measurementId = -1,
                floatValue    = avg,
                intValue      = null
            )
            templateVwt.copy(value = syntheticVal)
        }

        val syntheticMeasurement = Measurement(
            id        = -1,
            userId    = userId,
            timestamp = midTimestamp
        )

        val syntheticMwv = MeasurementWithValues(
            measurement = syntheticMeasurement,
            values      = aggregatedValues
        )

        // Diff/trend are NOT_APPLICABLE for now; filled in by calculateDiffsAndTrends()
        val valuesWithTrend = aggregatedValues.map { vwt ->
            ValueWithDifference(
                currentValue = vwt,
                difference   = null,
                trend        = Trend.NOT_APPLICABLE
            )
        }

        return EnrichedMeasurement(
            measurementWithValues          = syntheticMwv,
            valuesWithTrend                = valuesWithTrend,
            measurementWithValuesProjected = emptyList()
        )
    }

    /**
     * Given a list of aggregated entries sorted **oldest → newest**, calculates
     * the difference and trend for each type between consecutive periods.
     *
     * For the oldest period (no predecessor) diff stays null and trend stays NONE.
     *
     * @return The same list with [ValueWithDifference.difference] and
     *         [ValueWithDifference.trend] populated.
     */
    private fun calculateDiffsAndTrends(
        sortedOldestFirst: List<EnrichedMeasurement>
    ): List<EnrichedMeasurement> {
        if (sortedOldestFirst.size <= 1) return sortedOldestFirst

        return sortedOldestFirst.mapIndexed { index, current ->
            val previous = if (index > 0) sortedOldestFirst[index - 1] else null

            // Build a quick lookup: typeId → averaged float value for the previous period
            val prevValueByTypeId: Map<Int, Float> = previous
                ?.measurementWithValues
                ?.values
                ?.mapNotNull { vwt ->
                    val v = vwt.value.floatValue ?: return@mapNotNull null
                    vwt.type.id to v
                }
                ?.toMap()
                ?: emptyMap()

            val updatedValuesWithTrend = current.valuesWithTrend.map { vwd ->
                val currentFloat = vwd.currentValue.value.floatValue
                val prevFloat    = prevValueByTypeId[vwd.currentValue.type.id]

                if (currentFloat != null && prevFloat != null) {
                    val diff  = currentFloat - prevFloat
                    val trend = when {
                        diff > 0f  -> Trend.UP
                        diff < 0f  -> Trend.DOWN
                        else       -> Trend.NONE
                    }
                    vwd.copy(difference = diff, trend = trend)
                } else {
                    // No previous value for this type → no diff, neutral trend
                    vwd.copy(difference = null, trend = Trend.NONE)
                }
            }

            current.copy(valuesWithTrend = updatedValuesWithTrend)
        }
    }
}