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
package com.health.openscale.core.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalculationUtilsTest {

    private companion object {
        const val EPS = 1e-4f
    }

    /** Builds an epoch-milli for a date at start-of-day in the system zone, matching ageOn()'s zone handling. */
    private fun millis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---- ageOn ----------------------------------------------------------------------------------

    @Test
    fun ageOn_fullYears_betweenBirthAndDate() {
        assertThat(CalculationUtils.ageOn(millis(2020, 1, 1), millis(2000, 1, 1))).isEqualTo(20)
    }

    @Test
    fun ageOn_notYetHadBirthdayThisYear() {
        // born 2000-06-15, measured 2020-06-14 -> still 19
        assertThat(CalculationUtils.ageOn(millis(2020, 6, 14), millis(2000, 6, 15))).isEqualTo(19)
    }

    @Test
    fun ageOn_sameDay_isZero() {
        assertThat(CalculationUtils.ageOn(millis(2000, 1, 1), millis(2000, 1, 1))).isEqualTo(0)
    }

    @Test
    fun ageOn_futureBirthDate_isClampedToZero() {
        // birth after the measurement date -> negative period -> coerced to 0
        assertThat(CalculationUtils.ageOn(millis(2000, 1, 1), millis(2010, 1, 1))).isEqualTo(0)
    }

    @Test
    fun ageOn_leapYearBoundary() {
        // born 2000-02-29, measured 2020-02-28 -> 19 (birthday is on the 29th)
        assertThat(CalculationUtils.ageOn(millis(2020, 2, 28), millis(2000, 2, 29))).isEqualTo(19)
    }

    // ---- roundTo (truncates toward zero to 2 decimals) ------------------------------------------

    @Test
    fun roundTo_truncatesToTwoDecimals() {
        assertThat(CalculationUtils.roundTo(5.567f)).isWithin(EPS).of(5.56f)
        assertThat(CalculationUtils.roundTo(5.561f)).isWithin(EPS).of(5.56f)
    }

    @Test
    fun roundTo_smallValueTruncatesToZero() {
        assertThat(CalculationUtils.roundTo(0.001f)).isWithin(EPS).of(0.0f)
    }

    @Test
    fun roundTo_negativeTruncatesTowardZero() {
        assertThat(CalculationUtils.roundTo(-5.567f)).isWithin(EPS).of(-5.56f)
    }

    // ---- exponential smoothing ------------------------------------------------------------------

    @Test
    fun exponentialSmoothing_emptyAndSingle() {
        assertThat(CalculationUtils.applyExponentialSmoothing(emptyList(), 0.5f)).isEmpty()
        assertThat(CalculationUtils.applyExponentialSmoothing(listOf(5f), 0.5f)).containsExactly(5f)
    }

    @Test
    fun exponentialSmoothing_alphaOne_returnsRawSeries() {
        val out = CalculationUtils.applyExponentialSmoothing(listOf(10f, 20f, 30f), 1.0f)
        assertThat(out).containsExactly(10f, 20f, 30f).inOrder()
    }

    @Test
    fun exponentialSmoothing_alphaZeroIsClampedNotFrozen() {
        // alpha=0 is clamped to 0.01, so the second value barely moves toward 20.
        val out = CalculationUtils.applyExponentialSmoothing(listOf(10f, 20f), 0f)
        assertThat(out[0]).isWithin(EPS).of(10f)
        assertThat(out[1]).isWithin(EPS).of(10.1f) // 0.01*20 + 0.99*10
    }

    // ---- simple moving average ------------------------------------------------------------------

    @Test
    fun sma_windowTwo_overFourPoints() {
        val out = CalculationUtils.applySimpleMovingAverage(listOf(1f, 2f, 3f, 4f), 2)
        assertThat(out).containsExactly(1.5f, 2.5f, 3.5f).inOrder()
    }

    @Test
    fun sma_edgeCases() {
        assertThat(CalculationUtils.applySimpleMovingAverage(emptyList(), 3)).isEmpty()
        assertThat(CalculationUtils.applySimpleMovingAverage(listOf(1f, 2f), 0)).isEmpty()
        assertThat(CalculationUtils.applySimpleMovingAverage(listOf(1f, 2f), 5)).isEmpty()
        assertThat(CalculationUtils.applySimpleMovingAverage(listOf(1f, 2f, 3f), 1))
            .containsExactly(1f, 2f, 3f).inOrder()
    }
}
