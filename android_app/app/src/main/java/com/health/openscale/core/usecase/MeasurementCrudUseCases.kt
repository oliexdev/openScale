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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.ui.widget.MeasurementWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles CRUD-related use cases for measurements.
 *
 * Contains:
 * - [saveMeasurement]: insert or update a measurement and reconcile its values.
 * - [deleteMeasurement]: delete a measurement (and rely on DB constraints to cascade values if configured).
 */
@Singleton
class MeasurementCrudUseCases @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val databaseRepository: DatabaseRepository,
    private val sync: SyncUseCases,
    private val settingsFacade: SettingsFacade
) {
    private var lastVibrateTime = 0L

    /**
     * Inserts or updates a [measurement] and reconciles its [values].
     *
     * Rules:
     * - If `measurement.id == 0`: insert measurement, then insert all values for the new id.
     * - If `measurement.id != 0`: update measurement, then
     *   - delete values that no longer exist in [values],
     *   - update values that exist (by id),
     *   - insert values that are new (id == 0).
     *
     * @return [Result.success] with the final measurement id (new or existing) on success,
     * or [Result.failure] on error.
     */
    suspend fun saveMeasurement(
        measurement: Measurement,
        values: List<MeasurementValue>
    ): Result<Int> = runCatching {
        if (measurement.id == 0) {
            // Insert path
            val newId = databaseRepository.insertMeasurement(measurement).toInt()

            values.forEach { v ->
                databaseRepository.insertMeasurementValue(v.copy(measurementId = newId))
            }

            sync.triggerSyncInsert(measurement, values,"com.health.openscale.sync")
            sync.triggerSyncInsert(measurement, values,"com.health.openscale.sync.oss")

            MeasurementWidget.refreshAll(appContext)

            maybeVibrateOnMeasurement()

            newId
        } else {
            // Update path
            databaseRepository.updateMeasurement(measurement)

            val existing = databaseRepository.getValuesForMeasurement(measurement.id).first()
            val newSetIds = values.mapNotNull { if (it.id != 0) it.id else null }.toSet()
            val existingIds = existing.map { it.id }.toSet()

            // Delete removed values
            val toDelete = existingIds - newSetIds
            toDelete.forEach { id -> databaseRepository.deleteMeasurementValueById(id) }

            // Update or insert values
            values.forEach { v ->
                val exists = existing.any { it.id == v.id && v.id != 0 }
                if (exists) {
                    databaseRepository.updateMeasurementValue(v.copy(measurementId = measurement.id))
                } else {
                    databaseRepository.insertMeasurementValue(v.copy(measurementId = measurement.id))
                }
            }

            sync.triggerSyncUpdate(measurement, values, "com.health.openscale.sync")
            sync.triggerSyncUpdate(measurement, values,"com.health.openscale.sync.oss")

            MeasurementWidget.refreshAll(appContext)

            measurement.id
        }
    }

    /**
     * Deletes the given [measurement].
     *
     * Note: If value rows aren't configured to cascade-delete in the schema,
     * the repository is expected to handle value cleanup as needed.
     *
     * @return [Result.success] on success or [Result.failure] on error.
     */
    suspend fun deleteMeasurement(
        measurement: Measurement
    ): Result<Unit> = runCatching {
        databaseRepository.deleteMeasurement(measurement)
        sync.triggerSyncDelete(Date(measurement.timestamp), "com.health.openscale.sync")
        sync.triggerSyncDelete(Date(measurement.timestamp), "com.health.openscale.sync.oss")

        MeasurementWidget.refreshAll(appContext)
    }

    suspend fun recalculateDerivedValuesForMeasurement(measurementId: Int) {
        databaseRepository.recalculateDerivedValuesForMeasurement(measurementId)
    }

    private suspend fun maybeVibrateOnMeasurement() {
        val enabled = runCatching { settingsFacade.hapticOnMeasurement.first() }.getOrDefault(false)
        if (!enabled) return

        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < 1500) {
            return
        }
        lastVibrateTime = now

        val vm = appContext.getSystemService(VibratorManager::class.java)
        val vibrator: Vibrator = vm.defaultVibrator
        if (!vibrator.hasVibrator()) return

        val effect = VibrationEffect.createOneShot(
            500L,
            255
        )
        vibrator.vibrate(effect)
    }
}
