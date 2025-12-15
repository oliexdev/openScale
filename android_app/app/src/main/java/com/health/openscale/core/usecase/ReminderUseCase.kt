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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import android.content.Context
import android.text.format.DateUtils
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.worker.ReminderWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}

/**
 * ReminderUseCase – calculates the next reminder occurrence based on user settings
 * and schedules/cancels the worker accordingly.
 *
 * Keeps domain logic (when) separate from infra details (how via WorkManager).
 */
@Singleton
class ReminderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsFacade,
    private val measurementQuery: MeasurementQueryUseCases,
    private val clock: Clock
) {

    /**
     * Checks if a reminder should be triggered for the current user.
     * It returns false if reminders are disabled OR if a weight measurement already exists for today.
     */
    suspend fun isReminderNeeded(): Boolean {
        if (!settings.reminderEnabled.first()) return false

        val currentUserId = settings.currentUserId.first() ?: return false

        val allMeasurements = measurementQuery.getMeasurementsForUser(currentUserId).first()
        if (allMeasurements.isEmpty()) return true

        val today = System.currentTimeMillis()
        val closestMatchResult = measurementQuery.findClosestMeasurement(today, allMeasurements)
            ?: return true

        val measurementTimestamp = closestMatchResult.second.measurement.timestamp
        val isSameDay = DateUtils.isToday(measurementTimestamp)

        if (!isSameDay) return true

        val hasWeightValue = closestMatchResult.second.values.any { it.type.key == MeasurementTypeKey.WEIGHT }

        return !hasWeightValue
    }

    /** Recompute and (re)schedule the next reminder. Cancels if disabled or no days selected. */
    suspend fun rescheduleNext() {
        val enabled = runCatching { settings.reminderEnabled.first() }.getOrElse { false }
        val days = runCatching { settings.reminderDays.first() }.getOrElse { emptySet() }
        if (!enabled || days.isEmpty()) {
            cancel()
            return
        }

        val hour = runCatching { settings.reminderHour.first() }.getOrElse { 9 }.coerceIn(0, 23)
        val minute = runCatching { settings.reminderMinute.first() }.getOrElse { 0 }.coerceIn(0, 59)

        val now = ZonedDateTime.now(clock)
        val next = computeNext(now, days, hour, minute)
        scheduleAt(next)
    }

    /** Cancel any scheduled reminder. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun scheduleAt(next: ZonedDateTime) {
        val delayMs = Duration.between(ZonedDateTime.now(clock), next).toMillis().coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    /**
     * Compute the next ZonedDateTime at which the reminder should trigger.
     * @param selectedDayNames Set of DayOfWeek.name strings (e.g., "MONDAY").
     */
    private fun computeNext(
        from: ZonedDateTime,
        selectedDayNames: Set<String>,
        hour: Int,
        minute: Int
    ): ZonedDateTime {
        val days: Set<DayOfWeek> = selectedDayNames.mapNotNull { name ->
            runCatching { DayOfWeek.valueOf(name) }.getOrNull()
        }.toSet()

        var candidate = from.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

        // If today not allowed or time already passed, move forward day-by-day until allowed
        if (candidate.isBefore(from) || candidate.dayOfWeek !in days) {
            repeat(7) {
                candidate = candidate.plusDays(1)
                if (candidate.dayOfWeek in days) return candidate
            }
        }
        return candidate
    }

    companion object {
        const val WORK_NAME = "daily_reminder_work"
    }
}
