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
import com.health.openscale.core.data.Trend
import com.health.openscale.core.model.AggregatedMeasurement
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.ValueWithDifference
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case that aggregates a list of [EnrichedMeasurement] into a smaller list of
 * [AggregatedMeasurement] where each entry represents a time period
 * (day, week, month, or year).
 *
 * Aggregation rules:
 * - NONE: wraps each raw measurement in an [AggregatedMeasurement] with [AggregatedMeasurement.aggregatedFromCount] == 1.
 * - DAY/WEEK/MONTH/YEAR: groups measurements by period, averages numeric values,
 *   and sets [AggregatedMeasurement.aggregatedFromCount] to the group size.
 * - Non-numeric types (TEXT, DATE, TIME, USER) are excluded from aggregated entries.
 * - Period bounds ([AggregatedMeasurement.periodStartMillis] / [AggregatedMeasurement.periodEndMillis])
 *   and a stable [AggregatedMeasurement.periodKey] are pre-computed so screens
 *   never need to recalculate them.
 * - Differences and trends are calculated between consecutive aggregated periods.
 * - The resulting list is sorted newest → oldest (same order as the input).
 */
@Singleton
class MeasurementAggregationUseCase @Inject constructor() {

    /**
     * Aggregates [measurements] according to [level].
     *
     * @param measurements Chronologically sorted list (newest → oldest).
     * @param level        The desired aggregation granularity.
     * @return List of [AggregatedMeasurement] sorted newest → oldest.
     *         When [level] is [AggregationLevel.NONE] each raw measurement is wrapped
     *         individually with [AggregatedMeasurement.aggregatedFromCount] == 1.
     */
    fun aggregate(
        measurements: List<EnrichedMeasurement>,
        level: AggregationLevel,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AggregatedMeasurement> {
        if (measurements.isEmpty()) return emptyList()

        if (level == AggregationLevel.NONE) {
            return measurements.map { em ->
                val ts = em.measurementWithValues.measurement.timestamp
                val (periodStart, periodEnd) = level.periodBounds(ts, zone)
                AggregatedMeasurement(
                    enriched             = em,
                    aggregatedFromCount  = 1,
                    periodStartMillis    = periodStart,
                    periodEndMillis      = periodEnd,
                    periodKey            = level.periodKey(ts, zone),
                )
            }
        }

        // Hoist WeekFields outside the groupBy lambda (locale lookup is not free)
        val weekFields = if (level == AggregationLevel.WEEK) WeekFields.of(Locale.getDefault()) else null

        // Group by period key — reuses the same logic as periodKey() extension
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
        val sortedOldestFirst: List<AggregatedMeasurement> = grouped.values
            .mapNotNull { group -> buildAggregatedEntry(group, level, zone) }
            .sortedBy { it.enriched.measurementWithValues.measurement.timestamp }

        // Calculate diff + trend between consecutive periods then reverse to newest → oldest
        return calculateDiffsAndTrends(sortedOldestFirst)
            .sortedByDescending { it.enriched.measurementWithValues.measurement.timestamp }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a single [AggregatedMeasurement] from a group of measurements
     * belonging to the same time period.
     *
     * - The representative timestamp is the midpoint of [min, max] timestamps in the group.
     * - Period bounds and key are derived from that midpoint timestamp.
     * - Diff/trend are left as NOT_APPLICABLE here — filled in by [calculateDiffsAndTrends].
     */
    private fun buildAggregatedEntry(
        group: List<EnrichedMeasurement>,
        level: AggregationLevel,
        zone: ZoneId,
    ): AggregatedMeasurement? {
        if (group.isEmpty()) return null

        val timestamps   = group.map { it.measurementWithValues.measurement.timestamp }
        val minTs        = timestamps.min()
        val maxTs        = timestamps.max()
        val midTimestamp = minTs + (maxTs - minTs) / 2L

        val userId = group.first().measurementWithValues.measurement.userId

        // Accumulate sums directly — avoids Pair allocations of the previous implementation
        val sumByTypeId = mutableMapOf<Int, Pair<MeasurementValueWithType, MutableList<Float>>>()
        for (em in group) {
            for (vwt in em.measurementWithValues.values) {
                val numeric = when (vwt.type.inputType) {
                    InputFieldType.FLOAT -> vwt.value.floatValue
                    InputFieldType.INT   -> vwt.value.intValue?.toFloat()
                    else                 -> null
                } ?: continue
                sumByTypeId.getOrPut(vwt.type.id) { vwt to mutableListOf() }.second.add(numeric)
            }
        }

        val aggregatedValues: List<MeasurementValueWithType> = sumByTypeId.values.map { (vwt, nums) ->
            val avg          = nums.average().toFloat()
            val syntheticVal = vwt.value.copy(
                id            = -1,
                measurementId = -1,
                floatValue    = avg,
                intValue      = null,
            )
            vwt.copy(value = syntheticVal)
        }

        val syntheticMeasurement = Measurement(
            id        = -1,
            userId    = userId,
            timestamp = midTimestamp,
        )

        val syntheticMwv = MeasurementWithValues(
            measurement = syntheticMeasurement,
            values      = aggregatedValues,
        )

        val valuesWithTrend = aggregatedValues.map { vwt ->
            ValueWithDifference(
                currentValue = vwt,
                difference   = null,
                trend        = Trend.NOT_APPLICABLE,
            )
        }

        val enriched = EnrichedMeasurement(
            measurementWithValues          = syntheticMwv,
            valuesWithTrend                = valuesWithTrend,
            measurementWithValuesProjected = emptyList(),
        )

        val (periodStart, periodEnd) = level.periodBounds(minTs, zone)

        return AggregatedMeasurement(
            enriched            = enriched,
            aggregatedFromCount = group.size,
            periodStartMillis   = periodStart,
            periodEndMillis     = periodEnd,
            periodKey           = level.periodKey(minTs, zone),
        )
    }

    /**
     * Given [AggregatedMeasurement]s sorted **oldest → newest**, calculates the difference
     * and trend for each type between consecutive periods.
     *
     * For the oldest period (no predecessor) diff stays null and trend stays NONE.
     *
     * @return The same list with [ValueWithDifference.difference] and
     *         [ValueWithDifference.trend] populated inside each [AggregatedMeasurement.enriched].
     */
    private fun calculateDiffsAndTrends(
        sortedOldestFirst: List<AggregatedMeasurement>,
    ): List<AggregatedMeasurement> {
        if (sortedOldestFirst.size <= 1) return sortedOldestFirst

        return sortedOldestFirst.mapIndexed { index, current ->
            val previous = if (index > 0) sortedOldestFirst[index - 1] else null

            val prevValueByTypeId: Map<Int, Float> = previous
                ?.enriched
                ?.measurementWithValues
                ?.values
                ?.mapNotNull { vwt ->
                    val v = vwt.value.floatValue ?: return@mapNotNull null
                    vwt.type.id to v
                }
                ?.toMap()
                ?: emptyMap()

            val updatedValuesWithTrend = current.enriched.valuesWithTrend.map { vwd ->
                val currentFloat = vwd.currentValue.value.floatValue
                val prevFloat    = prevValueByTypeId[vwd.currentValue.type.id]

                if (currentFloat != null && prevFloat != null) {
                    val diff  = currentFloat - prevFloat
                    val trend = when {
                        diff > 0f -> Trend.UP
                        diff < 0f -> Trend.DOWN
                        else      -> Trend.NONE
                    }
                    vwd.copy(difference = diff, trend = trend)
                } else {
                    vwd.copy(difference = null, trend = Trend.NONE)
                }
            }

            current.copy(
                enriched = current.enriched.copy(valuesWithTrend = updatedValuesWithTrend)
            )
        }
    }
}