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
package com.health.openscale.core.data

import com.google.common.truth.Truth.assertThat
import com.health.openscale.testutil.Fixtures.ts
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Tests the date-bucketing that drives day/week/month/year aggregation grouping. */
class AggregationLevelTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    @Test
    fun periodKey_day_isIsoDate() {
        assertThat(AggregationLevel.DAY.periodKey(ts(2025, 4, 7, 10), zone)).isEqualTo("2025-04-07")
    }

    @Test
    fun periodKey_month_isYearDashMonth() {
        assertThat(AggregationLevel.MONTH.periodKey(ts(2025, 4, 7), zone)).isEqualTo("2025-4")
    }

    @Test
    fun periodKey_year_isYear() {
        assertThat(AggregationLevel.YEAR.periodKey(ts(2025, 4, 7), zone)).isEqualTo("2025")
    }

    @Test
    fun periodKey_day_isStableWithinSameDay() {
        assertThat(AggregationLevel.DAY.periodKey(ts(2025, 4, 7, 1), zone))
            .isEqualTo(AggregationLevel.DAY.periodKey(ts(2025, 4, 7, 23), zone))
    }

    @Test
    fun periodKey_day_differsAcrossDays() {
        assertThat(AggregationLevel.DAY.periodKey(ts(2025, 4, 7), zone))
            .isNotEqualTo(AggregationLevel.DAY.periodKey(ts(2025, 4, 8), zone))
    }

    @Test
    fun periodBounds_day_containsTimestampAndIsStableWithinDay() {
        val t1 = ts(2025, 4, 7, 1)
        val t2 = ts(2025, 4, 7, 23)
        val b1 = AggregationLevel.DAY.periodBounds(t1, zone)
        assertThat(AggregationLevel.DAY.periodBounds(t2, zone)).isEqualTo(b1)
        assertThat(t1).isAtLeast(b1.first)
        assertThat(t1).isLessThan(b1.second)
    }

    @Test
    fun periodBounds_month_startsAtFirstOfMonth() {
        val (start, end) = AggregationLevel.MONTH.periodBounds(ts(2025, 4, 15), zone)
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        assertThat(startDate.dayOfMonth).isEqualTo(1)
        assertThat(startDate.monthValue).isEqualTo(4)
        assertThat(start).isLessThan(end)
    }
}
