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

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles sync trigger use cases for the external SyncService.
 *
 * Starts a foreground service via explicit component to notify external sync packages
 * about inserts/updates/deletes of measurements.
 *
 * Expected service component: `com.health.openscale.sync.core.service.SyncService`
 * in the target package (e.g. `com.health.openscale.sync`, `com.health.openscale.sync.oss`).
 */
@Singleton
class SyncUseCases @Inject constructor(
    private val application: Application
) {

    /**
     * Triggers an **insert** sync event for [measurement] with its [values] to the given [pkgName].
     *
     * Extras:
     * - "mode" = "insert"
     * - "userId" = measurement.userId
     * - "date" = measurement.timestamp (epoch millis)
     * - optionally "weight", "fat", "water", "muscle" if present among [values]
     */
    fun triggerSyncInsert(
        measurement: Measurement,
        values: List<MeasurementValue>,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "insert")
            putExtra("userId", measurement.userId)
            putExtra("date", measurement.timestamp)
            putBodyCompositionExtras(values)
        }
        ContextCompat.startForegroundService(application.applicationContext, intent)
    }

    /**
     * Triggers an **update** sync event for [measurement] with its [values] to the given [pkgName].
     *
     * Extras:
     * - "mode" = "update"
     * - "userId" = measurement.userId
     * - "date" = measurement.timestamp (epoch millis)
     * - optionally "weight", "fat", "water", "muscle" if present among [values]
     */
    fun triggerSyncUpdate(
        measurement: Measurement,
        values: List<MeasurementValue>,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "update")
            putExtra("userId", measurement.userId)
            putExtra("date", measurement.timestamp)
            putBodyCompositionExtras(values)
        }
        ContextCompat.startForegroundService(application.applicationContext, intent)
    }

    /**
     * Triggers a **delete** sync event for the given [date] to the target [pkgName].
     *
     * Extras:
     * - "mode" = "delete"
     * - "date" = [Date.getTime] (epoch millis)
     */
    fun triggerSyncDelete(
        date: Date,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "delete")
            putExtra("date", date.time)
        }
        ContextCompat.startForegroundService(application.applicationContext, intent)
    }

    /**
     * Triggers a **clear** sync event to wipe all synced measurements on the target [pkgName].
     *
     * Extras:
     * - "mode" = "clear"
     */
    fun triggerSyncClear(
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "clear")
        }
        ContextCompat.startForegroundService(application.applicationContext, intent)
    }

    // --- helpers ---

    private fun Intent.putBodyCompositionExtras(values: List<MeasurementValue>) {
        // Map MeasurementValue.typeId -> MeasurementTypeKey
        val keyById = MeasurementTypeKey.values().associateBy { it.id }

        var weight: Float? = null
        var fat: Float? = null
        var water: Float? = null
        var muscle: Float? = null

        values.forEach { v ->
            when (keyById[v.typeId]) {
                MeasurementTypeKey.WEIGHT   -> weight = v.floatValue
                MeasurementTypeKey.BODY_FAT -> fat = v.floatValue
                MeasurementTypeKey.WATER    -> water = v.floatValue
                MeasurementTypeKey.MUSCLE   -> muscle = v.floatValue
                else -> Unit
            }
        }

        weight?.let { putExtra("weight", it) }
        fat?.let { putExtra("fat", it) }
        water?.let { putExtra("water", it) }
        muscle?.let { putExtra("muscle", it) }
    }

    private companion object {
        const val SYNC_SERVICE_CLASS = "com.health.openscale.sync.core.service.SyncService"
    }
}
