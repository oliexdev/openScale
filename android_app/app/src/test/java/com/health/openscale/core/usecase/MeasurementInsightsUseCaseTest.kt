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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.TrendDirection
import com.health.openscale.core.model.Volatility
import com.health.openscale.testutil.Fixtures
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * Pure JVM tests for [MeasurementInsightsUseCase.compute] — the statistical analysis behind the
 * Insights screen. Covers the minimum-data gate and the core measurement analysis (delta, min/max,
 * long-term trend) for a clean monotonic series.
 */
class MeasurementInsightsUseCaseTest {

    private val useCase = MeasurementInsightsUseCase()
    private val weight = Fixtures.type(id = 1, identity = MeasurementType.WEIGHT.identity)

    private fun m(day: Int, value: Float): MeasurementWithValues =
        Fixtures.mwv(
            measurementId = day,
            timestamp = Fixtures.ts(2025, 1, day),
            values = listOf(Fixtures.valueWithType(weight, value, day)),
        )

    private fun risingSeries(): List<MeasurementWithValues> =
        listOf(m(1, 70f), m(2, 71f), m(3, 72f), m(4, 73f), m(5, 74f), m(6, 75f))

    private fun m(date: LocalDate, measurementId: Int, value: Float): MeasurementWithValues =
        Fixtures.mwv(
            measurementId = measurementId,
            timestamp = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            values = listOf(Fixtures.valueWithType(weight, value, measurementId)),
        )

    private fun dailySeries(
        start: LocalDate,
        count: Int,
        valueForDay: (Int) -> Float,
    ): List<MeasurementWithValues> =
        (0 until count).map { day ->
            m(start.plusDays(day.toLong()), day + 1, valueForDay(day))
        }

    private fun analysisFor(measurements: List<MeasurementWithValues>) =
        useCase.compute(measurements, primaryTypeId = weight.id).measurementAnalysis!!

    @Test
    fun compute_emptyInput_returnsEmptyInsight() {
        val insight = useCase.compute(emptyList(), primaryTypeId = weight.id)
        assertThat(insight.measurementAnalysis).isNull()
        assertThat(insight.anomalies).isEmpty()
        assertThat(insight.basedOnCount).isEqualTo(0)
    }

    @Test
    fun compute_belowMinimumMeasurements_returnsEmptyAnalysis() {
        val insight = useCase.compute(risingSeries().take(4), primaryTypeId = weight.id)
        assertThat(insight.measurementAnalysis).isNull()
        assertThat(insight.basedOnCount).isEqualTo(4)
    }

    @Test
    fun compute_nullPrimaryType_returnsEmptyAnalysis() {
        val insight = useCase.compute(risingSeries(), primaryTypeId = null)
        assertThat(insight.measurementAnalysis).isNull()
    }

    @Test
    fun compute_risingSeries_producesAnalysisWithUpwardTrend() {
        val insight = useCase.compute(risingSeries(), primaryTypeId = weight.id)

        val analysis = insight.measurementAnalysis
        assertThat(analysis).isNotNull()
        assertThat(analysis!!.firstValue).isWithin(1e-3f).of(70f)
        assertThat(analysis.lastValue).isWithin(1e-3f).of(75f)
        assertThat(analysis.deltaAbsolute).isWithin(1e-3f).of(5f)
        assertThat(analysis.minValue).isWithin(1e-3f).of(70f)
        assertThat(analysis.maxValue).isWithin(1e-3f).of(75f)
        assertThat(analysis.longTermTrend).isEqualTo(TrendDirection.UP)
        assertThat(insight.basedOnCount).isEqualTo(6)
    }

    @Test
    fun compute_survivesNumericValuesOfTypesWithoutAPredefinedKey() {
        // Regression: the body-composition pattern used to force-unwrap type.key, which is
        // null for every ble.*/user.* row — crashing exactly for the users the identity
        // refactor was built for.
        val leftArm = Fixtures.type(id = 99, identity = "ble.segmental.fat.left_arm")
        val series = risingSeries().mapIndexed { i, mwv ->
            mwv.copy(values = mwv.values + Fixtures.valueWithType(leftArm, 12f + i, 50 + i))
        }

        val insight = useCase.compute(series, primaryTypeId = weight.id)

        assertThat(insight.basedOnCount).isEqualTo(series.size)
        assertThat(insight.measurementAnalysis).isNotNull()
    }

    @Test
    fun compute_smoothStrongDownwardTrend_doesNotReportLongPlateauOrHighVolatility() {
        val start = LocalDate.of(2025, 1, 1)
        val measurements = dailySeries(start, count = 181) { day ->
            100f - 22f * day / 180f
        }

        val analysis = analysisFor(measurements)

        assertThat(analysis.longTermTrend).isEqualTo(TrendDirection.DOWN)
        assertThat(analysis.plateauDays ?: 0).isLessThan(14)
        assertThat(analysis.volatility).isEqualTo(Volatility.STABLE)
    }

    @Test
    fun compute_smoothStrongUpwardTrend_doesNotReportHighVolatilityFromTrendSpan() {
        val start = LocalDate.of(2025, 1, 1)
        val measurements = dailySeries(start, count = 181) { day ->
            78f + 22f * day / 180f
        }

        val analysis = analysisFor(measurements)

        assertThat(analysis.longTermTrend).isEqualTo(TrendDirection.UP)
        assertThat(analysis.plateauDays ?: 0).isLessThan(14)
        assertThat(analysis.volatility).isEqualTo(Volatility.STABLE)
    }

    @Test
    fun compute_stableSeries_stillDetectsPlateau() {
        val start = LocalDate.of(2025, 2, 1)
        val measurements = dailySeries(start, count = 30) { day ->
            if (day % 2 == 0) 80f else 80.05f
        }

        val analysis = analysisFor(measurements)

        assertThat(analysis.longTermTrend).isEqualTo(TrendDirection.STABLE)
        assertThat(analysis.plateauDays).isAtLeast(28)
        assertThat(analysis.plateauStartDate).isEqualTo(start)
    }

    @Test
    fun compute_flatNoisySeries_reportsHighVolatility() {
        val start = LocalDate.of(2025, 3, 1)
        val measurements = dailySeries(start, count = 40) { day ->
            if (day % 2 == 0) 77f else 83f
        }

        val analysis = analysisFor(measurements)

        assertThat(analysis.longTermTrend).isEqualTo(TrendDirection.STABLE)
        assertThat(analysis.volatility).isEqualTo(Volatility.HIGH)
    }

    @Test
    fun compute_trendingNoisySeries_reportsResidualVolatilityFromScatter() {
        val start = LocalDate.of(2025, 3, 1)
        val measurements = dailySeries(start, count = 60) { day ->
            100f - 0.1f * day + if (day % 2 == 0) -1.5f else 1.5f
        }

        val analysis = analysisFor(measurements)

        assertThat(analysis.longTermTrend).isEqualTo(TrendDirection.DOWN)
        assertThat(analysis.volatility).isEqualTo(Volatility.MODERATE)
    }

    @Test
    fun compute_slowPersistentDrift_doesNotBecomeLongPlateau() {
        val measurements = dailySeries(LocalDate.of(2025, 4, 1), count = 60) { day ->
            80f - 0.02f * day
        }

        val analysis = analysisFor(measurements)

        assertThat(analysis.longTermTrend).isEqualTo(TrendDirection.DOWN)
        assertThat(analysis.plateauDays ?: 0).isLessThan(14)
    }

    @Test
    fun compute_plateauWindowUsesTotalChangeThresholdBoundary() {
        val start = LocalDate.of(2025, 5, 1)
        val withinThreshold = dailySeries(start, count = 20) { day ->
            if (day == 0) 100f else 100.24f
        }
        val outsideThreshold = dailySeries(start, count = 20) { day ->
            if (day == 0) 99.7f else 100f
        }

        val inside = analysisFor(withinThreshold)
        val outside = analysisFor(outsideThreshold)

        assertThat(inside.plateauStartDate).isEqualTo(start)
        assertThat(outside.plateauStartDate).isEqualTo(start.plusDays(1))
    }

    @Test
    fun compute_shortDatasetReturnsEmptyAnalysis() {
        val measurements = dailySeries(LocalDate.of(2025, 6, 1), count = 2) { day ->
            80f + day
        }

        val insight = useCase.compute(measurements, primaryTypeId = weight.id)

        assertThat(insight.measurementAnalysis).isNull()
        assertThat(insight.basedOnCount).isEqualTo(2)
    }
}
