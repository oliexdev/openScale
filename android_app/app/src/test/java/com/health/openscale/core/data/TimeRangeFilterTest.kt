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
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Tests the conversion from a selected time range to the bounds the measurement pipeline filters on. */
class TimeRangeFilterTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2025, 4, 20)

    private fun dayStart(date: LocalDate, zone: ZoneId = berlin): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun resolve(
        filter: TimeRangeFilter,
        start: Long = 0L,
        end: Long = 0L,
        zone: ZoneId = berlin,
    ) = filter.resolveBounds(start, end, zone, today)

    @Test
    fun allDays_isUnbounded() {
        assertThat(resolve(TimeRangeFilter.ALL_DAYS)).isEqualTo(null to null)
    }

    @Test
    fun rollingRange_startsAtLocalMidnight() {
        val (start, _) = resolve(TimeRangeFilter.LAST_30_DAYS)
        assertThat(start).isEqualTo(dayStart(LocalDate.of(2025, 3, 21)))
    }

    @Test
    fun rollingRange_leavesEndOpenSoFutureDatedEntriesStayVisible() {
        listOf(
            TimeRangeFilter.LAST_7_DAYS,
            TimeRangeFilter.LAST_30_DAYS,
            TimeRangeFilter.LAST_365_DAYS,
        ).forEach { filter ->
            assertThat(resolve(filter).second).isNull()
        }
    }

    @Test
    fun rollingRanges_spanTheirNamedNumberOfDays() {
        assertThat(resolve(TimeRangeFilter.LAST_7_DAYS).first)
            .isEqualTo(dayStart(LocalDate.of(2025, 4, 13)))
        assertThat(resolve(TimeRangeFilter.LAST_365_DAYS).first)
            .isEqualTo(dayStart(LocalDate.of(2024, 4, 20)))
    }

    @Test
    fun custom_endIsInclusiveToTheLastMillisecondOfThatDay() {
        val (start, end) = resolve(
            TimeRangeFilter.CUSTOM,
            start = dayStart(LocalDate.of(2025, 3, 12)),
            end = dayStart(LocalDate.of(2025, 4, 10)),
        )
        assertThat(start).isEqualTo(dayStart(LocalDate.of(2025, 3, 12)))
        // 2025-04-10 23:59:59.999 local, not the start of that day - a measurement taken that
        // evening belongs to the range the user picked.
        assertThat(end).isEqualTo(dayStart(LocalDate.of(2025, 4, 11)) - 1)
    }

    @Test
    fun custom_startWithoutEnd_staysOpenEnded() {
        val diaryStart = dayStart(LocalDate.of(2025, 3, 12))
        assertThat(resolve(TimeRangeFilter.CUSTOM, start = diaryStart, end = 0L))
            .isEqualTo(diaryStart to null)
    }

    @Test
    fun custom_withoutStart_isUnbounded() {
        assertThat(resolve(TimeRangeFilter.CUSTOM, start = 0L, end = 0L))
            .isEqualTo(null to null)
    }

    @Test
    fun custom_endWithoutStart_stillBoundsTheEnd() {
        val (start, end) = resolve(
            TimeRangeFilter.CUSTOM,
            start = 0L,
            end = dayStart(LocalDate.of(2025, 4, 10)),
        )
        assertThat(start).isNull()
        assertThat(end).isEqualTo(dayStart(LocalDate.of(2025, 4, 11)) - 1)
    }

    @Test
    fun custom_endIsInclusive_atNegativeUtcOffsetsToo() {
        val newYork = ZoneId.of("America/New_York")
        val (start, end) = resolve(
            TimeRangeFilter.CUSTOM,
            start = dayStart(LocalDate.of(2025, 3, 12), newYork),
            end = dayStart(LocalDate.of(2025, 4, 10), newYork),
            zone = newYork,
        )
        assertThat(start).isEqualTo(dayStart(LocalDate.of(2025, 3, 12), newYork))
        assertThat(end).isEqualTo(dayStart(LocalDate.of(2025, 4, 11), newYork) - 1)
    }

    @Test
    fun custom_endOfDay_survivesADayThatIsOnly23HoursLong() {
        // US DST starts 2025-03-09 at 02:00, so that calendar day has 23 hours. Anchoring the end
        // on the next day's midnight keeps the bound on the right day either way.
        val newYork = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2025, 3, 9)
        val (_, end) = resolve(
            TimeRangeFilter.CUSTOM,
            start = dayStart(dstDay, newYork),
            end = dayStart(dstDay, newYork),
            zone = newYork,
        )
        assertThat(end).isEqualTo(dayStart(dstDay.plusDays(1), newYork) - 1)
        assertThat(end!! - dayStart(dstDay, newYork) + 1).isEqualTo(23 * 60 * 60 * 1000L)
    }

    @Test
    fun rollingRange_countsCalendarDaysNotFixed24HourBlocks() {
        // 2025-03-30 is the European DST switch, so the seven days before 03-31 span 167 hours.
        // Subtracting 7 * 86_400_000 would land an hour into the wrong day.
        val end = LocalDate.of(2025, 3, 31)
        val start = TimeRangeFilter.LAST_7_DAYS.resolveBounds(zone = berlin, today = end).first
        assertThat(start).isEqualTo(dayStart(LocalDate.of(2025, 3, 24)))
        assertThat(dayStart(end) - start!!).isEqualTo(7 * 24 * 60 * 60 * 1000L - 60 * 60 * 1000L)
    }

    @Test
    fun bounds_holdOnSubHourUtcOffsets() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // UTC+05:45
        val day = LocalDate.of(2025, 4, 10)
        val (start, end) = resolve(
            TimeRangeFilter.CUSTOM,
            start = dayStart(day, kathmandu),
            end = dayStart(day, kathmandu),
            zone = kathmandu,
        )
        assertThat(start).isEqualTo(dayStart(day, kathmandu))
        assertThat(end).isEqualTo(dayStart(day.plusDays(1), kathmandu) - 1)
    }

    @Test
    fun bounds_followTheGivenZoneRatherThanUtc() {
        val utc = ZoneId.of("UTC")
        val berlinStart = resolve(TimeRangeFilter.LAST_7_DAYS, zone = berlin).first
        val utcStart = resolve(TimeRangeFilter.LAST_7_DAYS, zone = utc).first

        assertThat(berlinStart).isEqualTo(dayStart(LocalDate.of(2025, 4, 13), berlin))
        assertThat(utcStart).isEqualTo(dayStart(LocalDate.of(2025, 4, 13), utc))
        // Same calendar day, different instant - CEST is two hours ahead of UTC in April.
        assertThat(utcStart!! - berlinStart!!).isEqualTo(2 * 60 * 60 * 1000L)
    }
}
