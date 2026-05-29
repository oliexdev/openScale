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
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests the pure reminder-scheduling algorithm [ReminderUseCase.computeNext] — the "when does the
 * next reminder fire" logic — independent of WorkManager/Clock. Calendar-agnostic (derives the
 * weekday from the reference time) so it never breaks on a specific date.
 */
class ReminderSchedulingTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("UTC"))

    @Test
    fun computeNext_todayAllowedAndTimeNotPassed_firesToday() {
        val from = at(2025, 4, 9, 8, 0)
        val next = ReminderUseCase.computeNext(from, setOf(from.dayOfWeek.name), 9, 0)
        assertThat(next.toLocalDate()).isEqualTo(from.toLocalDate())
        assertThat(next.hour).isEqualTo(9)
        assertThat(next.minute).isEqualTo(0)
    }

    @Test
    fun computeNext_timeAlreadyPassedToday_firesNextWeek() {
        val from = at(2025, 4, 9, 10, 0)
        val next = ReminderUseCase.computeNext(from, setOf(from.dayOfWeek.name), 9, 0)
        assertThat(next.dayOfWeek).isEqualTo(from.dayOfWeek)
        assertThat(next.toLocalDate()).isEqualTo(from.toLocalDate().plusDays(7))
    }

    @Test
    fun computeNext_picksNextSelectedDay() {
        val from = at(2025, 4, 9, 8, 0)
        val target = from.dayOfWeek.plus(2)
        val next = ReminderUseCase.computeNext(from, setOf(target.name), 9, 0)
        assertThat(next.dayOfWeek).isEqualTo(target)
        assertThat(next.toLocalDate()).isEqualTo(from.toLocalDate().plusDays(2))
    }

    @Test
    fun computeNext_invalidDayNamesAreIgnored() {
        val from = at(2025, 4, 9, 8, 0)
        val target = from.dayOfWeek.plus(2)
        val next = ReminderUseCase.computeNext(from, setOf("NOT_A_DAY", target.name), 9, 0)
        assertThat(next.dayOfWeek).isEqualTo(target)
    }
}
