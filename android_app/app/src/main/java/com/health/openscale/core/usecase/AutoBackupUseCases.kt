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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.health.openscale.core.data.BackupInterval
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.worker.BackupWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/**
 * Encapsulates all auto-backup related settings and scheduling logic.
 *
 * Responsibilities:
 * - Expose/modify auto-backup preferences via [SettingsFacade].
 * - (Re)Schedule or cancel the periodic [BackupWorker] based on the current preferences.
 * - Offer a single entry point to recompute scheduling after any preference change.
 *
 * This keeps ViewModels free from WorkManager wiring and business rules.
 */
@Singleton
class AutoBackupUseCases @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsFacade
) {
    private val TAG = "AutoBackupUseCase"

    // --- Observables (pass-through) ---
    val enabled: Flow<Boolean> get() = settings.autoBackupEnabledGlobally
    val locationUri: Flow<String?> get() = settings.autoBackupLocationUri
    val interval: Flow<BackupInterval> get() = settings.autoBackupInterval
    val createNewFile: Flow<Boolean> get() = settings.autoBackupCreateNewFile
    val lastSuccessfulTimestamp: Flow<Long> get() = settings.autoBackupLastSuccessfulTimestamp

    // --- Mutations ---

    /** Enable/disable global auto-backup and refresh scheduling. */
    suspend fun setEnabled(value: Boolean) {
        settings.setAutoBackupEnabledGlobally(value)
        refreshSchedule()
    }

    /** Update target folder URI. Enabling is auto-implied when a non-null uri is set. */
    suspend fun setLocationUri(uri: String?) {
        settings.setAutoBackupLocationUri(uri)
        if (uri != null && !enabled.first()) settings.setAutoBackupEnabledGlobally(true)
        if (uri == null && enabled.first()) settings.setAutoBackupEnabledGlobally(false)
        refreshSchedule()
    }

    /** Update interval and refresh scheduling. */
    suspend fun setInterval(value: BackupInterval) {
        settings.setAutoBackupInterval(value)
        refreshSchedule()
    }

    /** Update file creation mode (append vs new). Does not affect scheduling. */
    suspend fun setCreateNewFile(value: Boolean) {
        settings.setAutoBackupCreateNewFile(value)
    }

    /**
     * Recompute WorkManager scheduling according to current preferences.
     * Cancels the worker if disabled or no location set.
     */
    suspend fun refreshSchedule() {
        val isEnabled = enabled.first()
        val uri = locationUri.first()
        val chosen = interval.first()

        val wm = WorkManager.getInstance(appContext)
        if (!isEnabled || uri == null) {
            wm.cancelUniqueWork(BackupWorker.WORK_NAME)
            LogManager.i(TAG, "Auto-backup disabled or no URI; worker cancelled.")
            return
        }

        val (repeatMillis, flexMillis) = intervalMillis(chosen)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresStorageNotLow(true)
            .build()

        val req = PeriodicWorkRequestBuilder<BackupWorker>(
            repeatMillis, TimeUnit.MILLISECONDS,
            flexMillis, TimeUnit.MILLISECONDS
        ).setConstraints(constraints)
            .addTag(BackupWorker.TAG)
            .build()

        wm.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            req
        )
        LogManager.i(TAG, "Auto-backup scheduled: interval=$chosen repeat=${repeatMillis}ms flex=${flexMillis}ms")
    }

    private fun intervalMillis(interval: BackupInterval): Pair<Long, Long> = when (interval) {
        BackupInterval.DAILY -> 24L * 60 * 60 * 1000L to (24L * 60 * 60 * 1000L / 8)
        BackupInterval.WEEKLY -> 7L * 24 * 60 * 60 * 1000L to (7L * 24 * 60 * 60 * 1000L / 8)
        BackupInterval.MONTHLY -> 30L * 24 * 60 * 60 * 1000L to (30L * 24 * 60 * 60 * 1000L / 8)
    }
}
