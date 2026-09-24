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

import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.model.MeasurementWithValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/** Derived from the sign of `goalValue` minus the value at the start — never stored. */
private enum class ProgressDirection { DOWN, UP, HOLD }

/** How far one tracked measurement type has come since its start point. */
data class GoalProgress(
    val type: MeasurementType,
    /** Value and date of the measurement the goal's start date resolved to. */
    val startValue: Float,
    val startDate: LocalDate,
    /** The day the figures were computed for, so callers render durations against the same one. */
    val today: LocalDate,
    val currentValue: Float,
    val delta: Float,
    val goalValue: Float,
    val goalTargetDate: LocalDate?,
    /** 0f..1f of the way from start to target; null when start and target coincide. */
    val goalFraction: Float?,
    /** Days from today to [goalTargetDate]; negative once it has passed. */
    val daysToTarget: Int?,
    /**
     * Date of the earliest reading of the unbroken run of readings that meet the goal, or null
     * when the latest one does not. Answers "how long have I been holding it".
     */
    val inGoalSince: LocalDate?,
    /** Signed remainder to the target. */
    val remainingToGoal: Float,
)

@Singleton
class UserGoalsUseCases @Inject constructor(
    private val databaseRepository: DatabaseRepository
) {
    suspend fun insertUserGoal(goal: UserGoals): Long {
        return databaseRepository.insertUserGoal(goal)
    }

    suspend fun updateUserGoal(goal: UserGoals) {
        databaseRepository.updateUserGoal(goal)
    }

    suspend fun deleteUserGoal(userId: Int, measurementTypeId: Int) {
        databaseRepository.deleteUserGoal(userId, measurementTypeId)
    }

    fun getAllGoalsForUser(userId: Int): Flow<List<UserGoals>> {
        return databaseRepository.getAllGoalsForUser(userId)
    }

    /**
     * Emits how far [userId] has come since the start point of each of their goals.
     *
     * Takes no time range on purpose: the window is `[start, now]` by definition.
     *
     * @param types Measurement types, in the order the result should report them.
     */
    fun observeProgress(
        userId: Int,
        types: List<MeasurementType>,
    ): Flow<List<GoalProgress>> =
        combine(
            databaseRepository.getMeasurementsWithValuesForUser(userId),
            getAllGoalsForUser(userId),
        ) { measurements, goals ->
            computeProgress(measurements, goals, types)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    companion object {
        /** A change smaller than this share of the start value reads as holding, not moving. */
        private const val HOLD_THRESHOLD = 0.005f

        private const val MIN_RELATIVE_DENOMINATOR = 1e-6f

        /**
         * Computes how far the user has come since the start point of each of their goals.
         *
         * One entry per goal whose type has at least one reading, in the order [types] lists them.
         * Each window is `[start, now]` and is independent of any screen's time filter.
         *
         * @param measurements Full measurement history for a single user, unsorted.
         * @param types        Measurement types, in the order the result should report them.
         */
        fun computeProgress(
            measurements: List<MeasurementWithValues>,
            goals: List<UserGoals>,
            types: List<MeasurementType>,
            zone: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zone),
        ): List<GoalProgress> {
            if (goals.isEmpty()) return emptyList()

            val sorted = measurements.sortedBy { it.measurement.timestamp }

            return types.mapNotNull { type ->
                val goal = goals.firstOrNull { it.measurementTypeId == type.id }
                    ?: return@mapNotNull null
                computeGoalProgress(goal, type, sorted, zone, today)
            }
        }

        private fun computeGoalProgress(
            goal: UserGoals,
            type: MeasurementType,
            sortedMeasurements: List<MeasurementWithValues>,
            zone: ZoneId,
            today: LocalDate,
        ): GoalProgress? {
            if (type.inputType != InputFieldType.FLOAT && type.inputType != InputFieldType.INT) return null
            val anchorMillis = goal.startDate

            val series = sortedMeasurements.mapNotNull { mwv ->
                numericValueFor(mwv, type)?.let { mwv.measurement.timestamp to it }
            }

            // The anchor the user picked snaps to the nearest measurement carrying this type, so
            // the reported start is always a reading that exists.
            val (startMillis, startValue) = series.minByOrNull { abs(it.first - anchorMillis) }
                ?: return null
            val startDate = toLocalDate(startMillis, zone)

            // An older reading than the start would report progress that has not happened yet.
            val currentValue = series.lastOrNull { it.first >= startMillis }?.second ?: return null

            val targetDate = goal.goalTargetDate?.let { toLocalDate(it, zone) }
            val daysToTarget = targetDate?.let { ChronoUnit.DAYS.between(today, it).toInt() }

            val goalValue = goal.goalValue
            val span = goalValue - startValue
            val direction = classifyDirection(span, startValue)

            // Walk back from the newest reading while the goal still holds; the first one that
            // breaks it ends the run. A hold goal has no side to be on, so it has no run either.
            val inGoalSince = when (direction) {
                ProgressDirection.DOWN, ProgressDirection.UP -> {
                    val meetsGoal = { v: Float ->
                        if (direction == ProgressDirection.DOWN) v <= goalValue else v >= goalValue
                    }
                    series.asReversed()
                        .takeWhile { meetsGoal(it.second) }
                        .lastOrNull()
                        ?.let { toLocalDate(it.first, zone) }
                }
                else -> null
            }
            val goalFraction = if (abs(span) >= MIN_RELATIVE_DENOMINATOR) {
                ((currentValue - startValue) / span).coerceIn(0f, 1f)
            } else {
                null
            }

            return GoalProgress(
                type            = type,
                startValue      = startValue,
                startDate       = startDate,
                today           = today,
                currentValue    = currentValue,
                delta           = currentValue - startValue,
                goalValue       = goalValue,
                goalTargetDate  = targetDate,
                goalFraction    = goalFraction,
                daysToTarget    = daysToTarget,
                inGoalSince     = inGoalSince,
                remainingToGoal = goalValue - currentValue,
            )
        }

        /** Relative threshold, so 0.2 kg on 85 kg holds while 0.2 % on 24 % body fat moves. */
        private fun classifyDirection(change: Float, reference: Float): ProgressDirection {
            val threshold = max(abs(reference) * HOLD_THRESHOLD, MIN_RELATIVE_DENOMINATOR)
            return when {
                change > threshold  -> ProgressDirection.UP
                change < -threshold -> ProgressDirection.DOWN
                else                -> ProgressDirection.HOLD
            }
        }

        private fun numericValueFor(mwv: MeasurementWithValues, type: MeasurementType): Float? =
            mwv.values.firstOrNull { it.type.id == type.id }?.let { vt ->
                when (type.inputType) {
                    InputFieldType.FLOAT -> vt.value.floatValue
                    InputFieldType.INT   -> vt.value.intValue?.toFloat()
                    else                 -> null
                }
            }

        private fun toLocalDate(timestampMillis: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
    }
}