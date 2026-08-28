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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Tests the date-bucketing that drives day/week/month/year aggregation grouping. */
class AggregationLevelTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun tsUtc(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun weekLabel(timestamp: Long, abbreviation: String, locale: Locale): String =
        AggregationLevel.WEEK.periodLabel(timestamp, abbreviation, locale, utc)

    private fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        return try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

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
    fun periodKey_week_isLocaleIndependent() {
        val sunday = tsUtc(2025, 4, 6)
        val monday = tsUtc(2025, 4, 7)

        val usKeys = withDefaultLocale(Locale.US) {
            listOf(
                AggregationLevel.WEEK.periodKey(sunday, utc),
                AggregationLevel.WEEK.periodKey(monday, utc),
            )
        }
        val germanyKeys = withDefaultLocale(Locale.GERMANY) {
            listOf(
                AggregationLevel.WEEK.periodKey(sunday, utc),
                AggregationLevel.WEEK.periodKey(monday, utc),
            )
        }

        assertThat(usKeys).containsExactly("2025-W14", "2025-W15").inOrder()
        assertThat(germanyKeys).isEqualTo(usKeys)
    }

    @Test
    fun periodKey_week_usesIsoWeekBasedYearAtYearBoundary() {
        withDefaultLocale(Locale.US) {
            assertThat(AggregationLevel.WEEK.periodKey(tsUtc(2021, 1, 1), utc))
                .isEqualTo("2020-W53")
        }
    }

    @Test
    fun periodLabel_week_underUsLocale_usesIsoWeekIdentity() {
        val sunday = tsUtc(2025, 4, 6)
        val label = weekLabel(sunday, "CW", Locale.US)

        assertThat(AggregationLevel.WEEK.periodKey(sunday, utc)).isEqualTo("2025-W14")
        assertThat(label).contains("2025")
        assertThat(label).contains("CW 14")
        assertThat(label).doesNotContain("CW 15")
    }

    @Test
    fun periodLabel_week_identityIsLocaleIndependent() {
        val sunday = tsUtc(2025, 4, 6)

        val usLabel = weekLabel(sunday, "CW", Locale.US)
        val germanyLabel = weekLabel(sunday, "KW", Locale.GERMANY)

        assertThat(usLabel).contains("2025")
        assertThat(usLabel).contains("CW 14")
        assertThat(germanyLabel).contains("2025")
        assertThat(germanyLabel).contains("KW 14")
    }

    @Test
    fun periodLabel_week_usesIsoWeekBasedYearAtYearBoundary() {
        val monday = tsUtc(2019, 12, 30)
        val wednesday = tsUtc(2020, 1, 1)

        assertThat(AggregationLevel.WEEK.periodKey(monday, utc)).isEqualTo("2020-W1")
        assertThat(AggregationLevel.WEEK.periodKey(wednesday, utc)).isEqualTo("2020-W1")
        assertThat(weekLabel(monday, "CW", Locale.US)).contains("2020")
        assertThat(weekLabel(monday, "CW", Locale.US)).contains("CW 1")
        assertThat(weekLabel(wednesday, "CW", Locale.US)).contains("2020")
        assertThat(weekLabel(wednesday, "CW", Locale.US)).contains("CW 1")
    }

    @Test
    fun periodLabel_week_matchesKeyAndBounds() {
        val cases = listOf(
            tsUtc(2025, 4, 6) to "2025-W14",
            tsUtc(2025, 4, 7) to "2025-W15",
            tsUtc(2020, 1, 1) to "2020-W1",
        )

        for ((timestamp, expectedKey) in cases) {
            val expectedWeek = expectedKey.substringAfter("-W")
            val label = weekLabel(timestamp, "CW", Locale.US)
            val (start, end) = AggregationLevel.WEEK.periodBounds(timestamp, utc)

            assertThat(AggregationLevel.WEEK.periodKey(timestamp, utc)).isEqualTo(expectedKey)
            assertThat(label).contains(expectedKey.substringBefore("-W"))
            assertThat(label).contains("CW $expectedWeek")
            assertThat(timestamp).isAtLeast(start)
            assertThat(timestamp).isLessThan(end)
        }
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

    @Test
    fun periodBounds_week_startsOnMonday() {
        val (start, end) = AggregationLevel.WEEK.periodBounds(tsUtc(2025, 4, 6), utc)

        assertThat(Instant.ofEpochMilli(start).atZone(utc).toLocalDate())
            .isEqualTo(LocalDate.of(2025, 3, 31))
        assertThat(Instant.ofEpochMilli(end).atZone(utc).toLocalDate())
            .isEqualTo(LocalDate.of(2025, 4, 7))
    }
}
