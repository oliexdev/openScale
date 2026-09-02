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
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.testutil.Fixtures.ts
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

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

    // ── Calendar weeks ────────────────────────────────────────────────────────
    //
    // The week rule is an explicit parameter here rather than the device default, so these
    // tests describe both a Monday-first and a Sunday-first region regardless of where they
    // run. Under a Monday-first host locale the Sunday-first cases used to pass by accident,
    // which is why issue #1454 went unnoticed.

    private val isoWeek: WeekFields = WeekFields.ISO
    private val usWeek: WeekFields = WeekFields.of(Locale.US)

    @Test
    fun periodBounds_week_startsOnTheRulesFirstDayOfWeek() {
        val wednesday = ts(2025, 4, 9)

        val isoStart = Instant.ofEpochMilli(
            AggregationLevel.WEEK.periodBounds(wednesday, zone, isoWeek).first
        ).atZone(zone).toLocalDate()
        val usStart = Instant.ofEpochMilli(
            AggregationLevel.WEEK.periodBounds(wednesday, zone, usWeek).first
        ).atZone(zone).toLocalDate()

        assertThat(isoStart.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
        assertThat(usStart.dayOfWeek).isEqualTo(DayOfWeek.SUNDAY)
    }

    @Test
    fun periodKey_week_sundayAndMondayShareAWeekOnlyUnderASundayFirstRule() {
        val sunday = ts(2025, 4, 6)
        val monday = ts(2025, 4, 7)

        assertThat(AggregationLevel.WEEK.periodKey(sunday, zone, usWeek))
            .isEqualTo(AggregationLevel.WEEK.periodKey(monday, zone, usWeek))
        assertThat(AggregationLevel.WEEK.periodKey(sunday, zone, isoWeek))
            .isNotEqualTo(AggregationLevel.WEEK.periodKey(monday, zone, isoWeek))
    }

    /**
     * Regression for issue #1454: grouping used the locale week while the bounds were hardcoded
     * to ISO, so under a Sunday-first rule a group's own member fell outside the range reported
     * for that group — and the drill-down filtered by that range showed the wrong rows.
     */
    @Test
    fun periodBounds_containEveryTimestampSharingThePeriodKey() {
        val timestamps = (0 until 400).map { ts(2025, 1, 1).plusDays(it) }

        for (weekFields in listOf(isoWeek, usWeek)) {
            for (level in AggregationLevel.entries) {
                timestamps
                    .groupBy { level.periodKey(it, zone, weekFields) }
                    .forEach { (key, group) ->
                        val (start, end) = level.periodBounds(group.first(), zone, weekFields)
                        group.forEach { t ->
                            assertThat(t).isAtLeast(start)
                            assertThat(t).isLessThan(end)
                        }
                        // and the bounds must not reach into a neighbouring period
                        assertThat(level.periodKey(start, zone, weekFields)).isEqualTo(key)
                        assertThat(level.periodKey(end - 1, zone, weekFields)).isEqualTo(key)
                    }
            }
        }
    }

    @Test
    fun periodLabel_week_showsTheSameNumberAsThePeriodKey() {
        for (weekFields in listOf(isoWeek, usWeek)) {
            for (offset in 0 until 400) {
                val t = ts(2025, 1, 1).plusDays(offset)
                val week = AggregationLevel.WEEK.periodKey(t, zone, weekFields)
                    .substringAfter("-W")
                val label = AggregationLevel.WEEK.periodLabel(
                    timestamp = t,
                    calendarWeekAbbrev = "CW",
                    weekFields = weekFields,
                )
                assertThat(label).endsWith("CW $week")
            }
        }
    }

    /** New Year: ISO uses the first-Thursday rule, the US rule the week containing Jan 1. */
    @Test
    fun periodKey_week_yearBoundaryFollowsTheRule() {
        val newYear = ts(2027, 1, 1)

        assertThat(AggregationLevel.WEEK.periodKey(newYear, zone, usWeek)).isEqualTo("2027-W1")
        assertThat(AggregationLevel.WEEK.periodKey(newYear, zone, isoWeek)).isEqualTo("2026-W53")
    }

    @Test
    fun periodLabel_month_honoursTheShortFlag() {
        val april = ts(2025, 4, 15)

        assertThat(
            AggregationLevel.MONTH.periodLabel(april, "CW", Locale.US, zone, isoWeek, short = false)
        ).isEqualTo("April 2025")
        assertThat(
            AggregationLevel.MONTH.periodLabel(april, "CW", Locale.US, zone, isoWeek, short = true)
        ).isEqualTo("Apr 2025")
    }

    /**
     * The device configuration is unavailable in a plain JVM test, which exercises the same
     * fallback that a region-less locale takes: ISO rather than an accidental Sunday week.
     */
    @Test
    fun systemWeekFields_fallsBackToIsoWithoutADeviceRegion() {
        assertThat(LocaleUtils.systemWeekFields()).isEqualTo(WeekFields.ISO)
    }

    private fun Long.plusDays(days: Int): Long =
        LocalDate.ofEpochDay(
            Instant.ofEpochMilli(this).atZone(zone).toLocalDate().toEpochDay() + days
        ).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}
