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
package com.health.openscale.ui.screen.insights

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.model.InsightConfidence
import com.health.openscale.core.model.MeasurementAnalysis
import com.health.openscale.core.model.TrendDirection
import com.health.openscale.core.model.Volatility
import com.health.openscale.testutil.Fixtures
import java.time.LocalDate
import org.junit.Test

class InsightsScreenTest {

    private val weight = Fixtures.type(id = 1, identity = MeasurementType.WEIGHT.identity)

    private fun analysis(
        longTermTrend: TrendDirection,
        plateauDays: Int? = 21,
        lastMeasuredOn: LocalDate = LocalDate.of(2025, 6, 30),
    ) = MeasurementAnalysis(
        type = weight,
        firstValue = 80f,
        lastValue = 80f,
        deltaAbsolute = 0f,
        deltaPercent = 0f,
        minValue = 80f,
        minValueDate = lastMeasuredOn.minusDays(plateauDays?.toLong() ?: 0L),
        maxValue = 80f,
        maxValueDate = lastMeasuredOn,
        volatility = Volatility.STABLE,
        shortTermTrend = TrendDirection.STABLE,
        longTermTrend = longTermTrend,
        ratePerMonth = 0f,
        plateauDays = plateauDays,
        plateauStartDate = plateauDays?.let { lastMeasuredOn.minusDays(it.toLong()) },
        bestPeriodStart = null,
        bestPeriodDelta = null,
        firstMeasuredOn = lastMeasuredOn.minusDays(plateauDays?.toLong() ?: 0L),
        lastMeasuredOn = lastMeasuredOn,
        confidence = InsightConfidence.HIGH,
    )

    @Test
    fun shouldShowPlateauSummary_suppressesWhenLongTermTrendIsNotStable() {
        val today = LocalDate.of(2025, 7, 1)

        assertThat(analysis(TrendDirection.DOWN).shouldShowPlateauSummary(today)).isFalse()
        assertThat(analysis(TrendDirection.UP).shouldShowPlateauSummary(today)).isFalse()
    }

    @Test
    fun shouldShowPlateauSummary_allowsRecentStablePlateau() {
        val today = LocalDate.of(2025, 7, 1)

        assertThat(analysis(TrendDirection.STABLE).shouldShowPlateauSummary(today)).isTrue()
    }
}
