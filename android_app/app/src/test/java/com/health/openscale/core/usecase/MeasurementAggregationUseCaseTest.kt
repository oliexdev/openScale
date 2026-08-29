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
}
