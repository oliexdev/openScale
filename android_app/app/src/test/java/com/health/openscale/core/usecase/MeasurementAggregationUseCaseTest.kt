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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.testutil.Fixtures
import org.junit.Test
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

class MeasurementAggregationUseCaseTest {

    private val useCase = MeasurementAggregationUseCase()
    private val weight = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT)

    private fun em(measurementId: Int, timestamp: Long, weightValue: Float): EnrichedMeasurement =
        Fixtures.enriched(
            Fixtures.mwv(
                measurementId = measurementId,
                timestamp = timestamp,
                values = listOf(Fixtures.valueWithType(weight, weightValue, measurementId)),
            )
        )

    @Test
    fun aggregate_empty_returnsEmpty() {
        assertThat(useCase.aggregate(emptyList(), AggregationLevel.DAY)).isEmpty()
    }

    @Test
    fun aggregate_none_yieldsOneEntryPerInput() {
        val input = listOf(
            em(1, Fixtures.ts(2025, 4, 7), 80f),
            em(2, Fixtures.ts(2025, 4, 8), 82f),
        )
        val out = useCase.aggregate(input, AggregationLevel.NONE)
        assertThat(out).hasSize(2)
        assertThat(out.map { it.aggregatedFromCount }).containsExactly(1, 1)
    }

    @Test
    fun aggregate_day_groupsSameDayAndAveragesValue() {
        val input = listOf(
            em(1, Fixtures.ts(2025, 4, 7, 8), 80f),
            em(2, Fixtures.ts(2025, 4, 7, 20), 82f),
        )
        val out = useCase.aggregate(input, AggregationLevel.DAY)

        assertThat(out).hasSize(1)
        assertThat(out[0].aggregatedFromCount).isEqualTo(2)
        val avg = out[0].enriched.measurementWithValues.values
            .first { it.type.id == weight.id }.value.floatValue
        assertThat(avg).isNotNull()
        assertThat(avg!!).isWithin(1e-3f).of(81f)
    }

    @Test
    fun aggregate_day_separatesDifferentDays() {
        val input = listOf(
            em(1, Fixtures.ts(2025, 4, 7), 80f),
            em(2, Fixtures.ts(2025, 4, 8), 82f),
        )
        val out = useCase.aggregate(input, AggregationLevel.DAY)
        assertThat(out).hasSize(2)
        assertThat(out.map { it.aggregatedFromCount }).containsExactly(1, 1)
    }

    // --- INT-typed measurements ------------------------------------------------
    // The aggregated value keeps the original type, so every consumer switches on
    // InputFieldType.INT and reads intValue. If only floatValue is populated, an
    // INT-typed measurement reads as absent in the chart, the statistics screen and
    // smoothing alike.

    private val heartRate = Fixtures.type(
        id = 2,
        key = MeasurementTypeKey.HEART_RATE,
        inputType = InputFieldType.INT,
    )

    private fun emInt(measurementId: Int, timestamp: Long, value: Int): EnrichedMeasurement =
        Fixtures.enriched(
            Fixtures.mwv(
                measurementId = measurementId,
                timestamp = timestamp,
                values = listOf(Fixtures.intValueWithType(heartRate, value, measurementId)),
            )
        )

    @Test
    fun aggregate_day_populatesIntValueForIntTypedMeasurements() {
        val input = listOf(
            emInt(1, Fixtures.ts(2025, 4, 7, 8), 60),
            emInt(2, Fixtures.ts(2025, 4, 7, 20), 70),
        )
        val out = useCase.aggregate(input, AggregationLevel.DAY)

        assertThat(out).hasSize(1)
        val aggregated = out[0].enriched.measurementWithValues.values
            .first { it.type.id == heartRate.id }.value

        assertThat(aggregated.intValue).isEqualTo(65)
        // floatValue stays populated too, so consumers reading either field agree.
        assertThat(aggregated.floatValue).isNotNull()
        assertThat(aggregated.floatValue!!).isWithin(1e-3f).of(65f)
    }

    @Test
    fun aggregate_day_roundsIntValueRatherThanTruncating() {
        // 60 and 65 average to 62.5 — truncation would report 62.
        val input = listOf(
            emInt(1, Fixtures.ts(2025, 4, 7, 8), 60),
            emInt(2, Fixtures.ts(2025, 4, 7, 20), 65),
        )
        val out = useCase.aggregate(input, AggregationLevel.DAY)

        val aggregated = out[0].enriched.measurementWithValues.values
            .first { it.type.id == heartRate.id }.value
        assertThat(aggregated.intValue).isEqualTo(63)
    }

    @Test
    fun aggregate_leavesIntValueNullForFloatTypedMeasurements() {
        val input = listOf(
            em(1, Fixtures.ts(2025, 4, 7, 8), 80f),
            em(2, Fixtures.ts(2025, 4, 7, 20), 82f),
        )
        val out = useCase.aggregate(input, AggregationLevel.DAY)

        val aggregated = out[0].enriched.measurementWithValues.values
            .first { it.type.id == weight.id }.value
        assertThat(aggregated.intValue).isNull()
    }

    // ── Calendar weeks ────────────────────────────────────────────────────────

    private val zone: ZoneId = ZoneId.systemDefault()
    private val isoWeek: WeekFields = WeekFields.ISO
    private val usWeek: WeekFields = WeekFields.of(Locale.US)

    @Test
    fun aggregate_week_groupsBySundayOrMondayDependingOnTheRule() {
        val input = listOf(
            em(1, Fixtures.ts(2025, 4, 6), 80f),   // Sunday
            em(2, Fixtures.ts(2025, 4, 7), 82f),   // Monday
        )

        assertThat(useCase.aggregate(input, AggregationLevel.WEEK, zone, usWeek)).hasSize(1)
        assertThat(useCase.aggregate(input, AggregationLevel.WEEK, zone, isoWeek)).hasSize(2)
    }

    /**
     * Regression for issue #1454: the entry's period bounds must cover every measurement that
     * was folded into it, otherwise the drill-down filtered by those bounds shows other rows
     * than the aggregated row summarised.
     */
    @Test
    fun aggregate_periodBoundsContainEveryMeasurementOfTheirOwnEntry() {
        val input = (0 until 60).map { day ->
            em(day + 1, Fixtures.ts(2025, 3, 1) + day * 86_400_000L, 80f + day)
        }

        for (weekFields in listOf(isoWeek, usWeek)) {
            for (level in AggregationLevel.entries) {
                val out = useCase.aggregate(input, level, zone, weekFields)

                assertThat(out.sumOf { it.aggregatedFromCount }).isEqualTo(input.size)

                out.forEach { entry ->
                    val members = input.filter {
                        level.periodKey(
                            it.measurementWithValues.measurement.timestamp, zone, weekFields
                        ) == entry.periodKey
                    }
                    assertThat(members).isNotEmpty()
                    assertThat(members).hasSize(entry.aggregatedFromCount)
                    members.forEach { m ->
                        val t = m.measurementWithValues.measurement.timestamp
                        assertThat(t).isAtLeast(entry.periodStartMillis)
                        assertThat(t).isLessThan(entry.periodEndMillis)
                    }
                }
            }
        }
    }
}
