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
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.TrendDirection
import com.health.openscale.testutil.Fixtures
import org.junit.Test

/**
 * Pure JVM tests for [MeasurementInsightsUseCase.compute] — the statistical analysis behind the
 * Insights screen. Covers the minimum-data gate and the core measurement analysis (delta, min/max,
 * long-term trend) for a clean monotonic series.
 */
class MeasurementInsightsUseCaseTest {

    private val useCase = MeasurementInsightsUseCase()
    private val weight = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT)

    private fun m(day: Int, value: Float): MeasurementWithValues =
        Fixtures.mwv(
            measurementId = day,
            timestamp = Fixtures.ts(2025, 1, day),
            values = listOf(Fixtures.valueWithType(weight, value, day)),
        )

    private fun risingSeries(): List<MeasurementWithValues> =
        listOf(m(1, 70f), m(2, 71f), m(3, 72f), m(4, 73f), m(5, 74f), m(6, 75f))

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
}
