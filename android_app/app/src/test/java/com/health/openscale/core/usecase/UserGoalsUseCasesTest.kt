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
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.testutil.Fixtures
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * Pure JVM tests for [UserGoalsUseCases.computeProgress] — how far the user has come since the
 * start point recorded on each goal. No database: the computation is a pure function of
 * measurements, goals and types.
 */
class UserGoalsUseCasesTest {

    private val weight = Fixtures.type(id = 1, identity = MeasurementType.WEIGHT.identity)
    private val bodyFat = Fixtures.type(id = 2, identity = MeasurementType.BODY_FAT.identity)

    private val start = LocalDate.of(2025, 3, 1)
    private val today = start.plusDays(42)

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun goal(
        type: MeasurementType,
        startDate: LocalDate = start,
        goalValue: Float = 78f,
        goalTargetDate: LocalDate? = null,
    ) = UserGoals(
        userId = 1,
        measurementTypeId = type.id,
        goalValue = goalValue,
        goalTargetDate = goalTargetDate?.let(::millis),
        startDate = millis(startDate),
    )

    /** One measurement per listed (day offset, value) pair, for the given type. */
    private fun series(
        type: MeasurementType,
        vararg points: Pair<Long, Float>,
    ): List<MeasurementWithValues> = points.mapIndexed { index, (dayOffset, value) ->
        Fixtures.mwv(
            measurementId = index + 1,
            timestamp = start.plusDays(dayOffset).atTime(8, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            values = listOf(Fixtures.valueWithType(type, value, index + 1)),
        )
    }

    private fun progressFor(
        measurements: List<MeasurementWithValues>,
        goals: List<UserGoals>,
        types: List<MeasurementType> = listOf(weight, bodyFat),
        on: LocalDate = today,
    ) = UserGoalsUseCases.computeProgress(
        measurements = measurements,
        goals = goals,
        types = types,
        zone = ZoneId.systemDefault(),
        today = on,
    )

    @Test
    fun withoutAnyGoal_isEmpty() {
        assertThat(progressFor(series(weight, 0L to 85.4f), goals = emptyList())).isEmpty()
    }

    @Test
    fun withoutAnyMeasurement_isEmpty() {
        // A goal but nothing weighed yet — there is no progress to report.
        assertThat(progressFor(emptyList(), goals = listOf(goal(weight)))).isEmpty()
    }

    @Test
    fun reportsTheStartPointAndTheChangeSinceIt() {
        val progress = progressFor(
            series(weight, 0L to 85.4f, 42L to 82.0f),
            goals = listOf(goal(weight)),
        ).single()

        assertThat(progress.startDate).isEqualTo(start)
        assertThat(progress.today).isEqualTo(start.plusDays(42))
        assertThat(progress.currentValue).isWithin(0.001f).of(82.0f)
        assertThat(progress.delta).isWithin(0.001f).of(-3.4f)
    }

    @Test
    fun snapsToTheMeasurementNearestTheChosenDate_andReportsItsDate() {
        val progress = progressFor(
            // Nothing was measured on the chosen day; the reading two days earlier is nearest.
            series(weight, -2L to 86.0f, 10L to 84.0f, 42L to 82.0f),
            goals = listOf(goal(weight)),
        ).single()

        // Measured against the snapped reading, not the 85.4 of the chosen day.
        assertThat(progress.startValue).isWithin(0.001f).of(86.0f)
        assertThat(progress.delta).isWithin(0.001f).of(-4.0f)
        // The date reported is the measurement's, not the one the user typed.
        assertThat(progress.startDate).isEqualTo(start.minusDays(2))
    }

    @Test
    fun omitsTypesWithoutAnyReading() {
        val progress = progressFor(
            series(weight, 0L to 85.4f, 42L to 82.0f),
            goals = listOf(goal(weight), goal(bodyFat, goalValue = 18f)),
        )

        assertThat(progress.map { it.type.id }).containsExactly(weight.id)
    }

    @Test
    fun eachGoalKeepsItsOwnStartAndIsReportedInTypeOrder() {
        val measurements = series(weight, 0L to 85.4f, 42L to 82.0f) +
            series(bodyFat, 20L to 24.1f, 42L to 22.3f)

        val progress = progressFor(
            measurements,
            // Body fat starts later, and nothing averages the two together.
            goals = listOf(
                goal(weight),
                goal(bodyFat, startDate = start.plusDays(20), goalValue = 18f),
            ),
        )

        assertThat(progress.map { it.type.id }).containsExactly(weight.id, bodyFat.id).inOrder()
        assertThat(progress.first().startDate).isEqualTo(start)
        assertThat(progress.last().startDate).isEqualTo(start.plusDays(20))
        assertThat(progress.last().delta).isWithin(0.001f).of(-1.8f)
    }

    @Test
    fun ignoresMeasurementsTakenBeforeTheStart() {
        val progress = progressFor(
            // The 90.0 reading predates the start and must not become the current value.
            series(weight, -10L to 90.0f, 0L to 85.4f, 5L to 84.0f),
            goals = listOf(goal(weight)),
        ).single()

        assertThat(progress.currentValue).isWithin(0.001f).of(84.0f)
    }

    @Test
    fun goalFractionRunsFromZeroAtStartToOneAtTarget() {
        fun fractionAt(vararg points: Pair<Long, Float>) =
            progressFor(series(weight, *points), goals = listOf(goal(weight, goalValue = 78f)))
                .single()

        assertThat(fractionAt(0L to 85.0f).goalFraction!!).isWithin(0.001f).of(0f)
        assertThat(fractionAt(0L to 85.0f, 42L to 81.5f).goalFraction!!).isWithin(0.001f).of(0.5f)

        val reached = fractionAt(0L to 85.0f, 42L to 78.0f)
        assertThat(reached.goalFraction!!).isWithin(0.001f).of(1f)
        assertThat(reached.remainingToGoal).isWithin(0.001f).of(0f)
    }

    @Test
    fun goalFractionIsClampedOnOvershootAndBacksliding() {
        val overshot = progressFor(
            series(weight, 0L to 85.0f, 42L to 75.0f),
            goals = listOf(goal(weight, goalValue = 78f)),
        ).single()
        val wrongWay = progressFor(
            series(weight, 0L to 85.0f, 42L to 88.0f),
            goals = listOf(goal(weight, goalValue = 78f)),
        ).single()

        assertThat(overshot.goalFraction!!).isWithin(0.001f).of(1f)
        assertThat(wrongWay.goalFraction!!).isWithin(0.001f).of(0f)
    }

    @Test
    fun goalFractionIsNullWhenTheTargetEqualsTheStart() {
        val progress = progressFor(
            series(weight, 0L to 85.0f, 42L to 84.0f),
            goals = listOf(goal(weight, goalValue = 85f)),
        ).single()

        assertThat(progress.goalFraction).isNull()
        // A goal that asks for no change has no side to be on, so nothing counts as holding it.
        assertThat(progress.inGoalSince).isNull()
    }

    @Test
    fun goalFractionFollowsTheTargetForGainGoalsToo() {
        val gaining = progressFor(
            series(weight, 0L to 70.0f, 42L to 72.0f),
            goals = listOf(goal(weight, goalValue = 75f)),
        ).single()

        assertThat(gaining.goalFraction!!).isWithin(0.001f).of(0.4f)
    }

    @Test
    fun theGoalSideComesFromTheTarget_notTheDailyReading() {
        // Two days in and a kilo up, the intent is still to lose: were the side taken from the
        // latest reading instead, 86.0 would count as meeting a 78.0 goal from above.
        val progress = progressFor(
            series(weight, 0L to 85.0f, 2L to 86.0f),
            goals = listOf(goal(weight, goalValue = 78f)),
            on = start.plusDays(2),
        ).single()

        assertThat(progress.inGoalSince).isNull()
    }

    @Test
    fun futureStartDate_stillReportsTheGoal() {
        val progress = progressFor(
            series(weight, 0L to 85.0f),
            goals = listOf(goal(weight)),
            on = start.minusDays(5),
        ).single()

        assertThat(progress.today).isEqualTo(start.minusDays(5))
        assertThat(progress.delta).isWithin(0.001f).of(0f)
    }

    @Test
    fun daysToTargetCountsDownFromToday() {
        val progress = progressFor(
            series(weight, 0L to 85.0f, 42L to 82.0f),
            goals = listOf(goal(weight, goalTargetDate = start.plusDays(84))),
        ).single()

        // 84 days planned, 42 of them gone.
        assertThat(progress.daysToTarget).isEqualTo(42)
    }

    @Test
    fun daysToTargetGoesNegativeOnceTheDateHasPassed() {
        val progress = progressFor(
            series(weight, 0L to 85.0f, 42L to 82.0f),
            goals = listOf(goal(weight, goalTargetDate = start.plusDays(20))),
        ).single()

        assertThat(progress.daysToTarget).isEqualTo(-22)
    }

    @Test
    fun daysToTargetIsNullWithoutATargetDate() {
        val progress = progressFor(
            series(weight, 0L to 85.0f, 42L to 82.0f),
            goals = listOf(goal(weight)),
        ).single()

        assertThat(progress.daysToTarget).isNull()
    }

    @Test
    fun inGoalSince_marksTheStartOfTheUnbrokenRunThatMeetsTheGoal() {
        val progress = progressFor(
            // Dipped under 78 on day 20, back over on day 25, under again from day 30 on.
            series(weight, 0L to 85.0f, 20L to 77.5f, 25L to 79.0f, 30L to 77.8f, 42L to 77.0f),
            goals = listOf(goal(weight, goalValue = 78f)),
        ).single()

        assertThat(progress.inGoalSince).isEqualTo(start.plusDays(30))
    }

    @Test
    fun inGoalSince_isNullWhileTheLatestReadingMissesTheGoal() {
        val progress = progressFor(
            series(weight, 0L to 85.0f, 30L to 77.5f, 42L to 79.0f),
            goals = listOf(goal(weight, goalValue = 78f)),
        ).single()

        assertThat(progress.inGoalSince).isNull()
    }

    @Test
    fun inGoalSince_worksForGainGoalsToo() {
        val progress = progressFor(
            series(weight, 0L to 70.0f, 30L to 75.5f, 42L to 76.0f),
            goals = listOf(goal(weight, goalValue = 75f)),
        ).single()

        assertThat(progress.inGoalSince).isEqualTo(start.plusDays(30))
    }
}
