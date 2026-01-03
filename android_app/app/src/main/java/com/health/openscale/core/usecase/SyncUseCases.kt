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
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.utils.ConverterUtils
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
    private val application: Application,
    private val measurementTypeUseCases: MeasurementTypeCrudUseCases
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
    suspend fun triggerSyncInsert(
        measurement: Measurement,
        values: List<MeasurementValue>,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "insert")
            putExtra("id", measurement.id)
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
    suspend fun triggerSyncUpdate(
        measurement: Measurement,
        values: List<MeasurementValue>,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "update")
            putExtra("id", measurement.id)
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

    private suspend fun Intent.putBodyCompositionExtras(values: List<MeasurementValue>) {
        val keyById = MeasurementTypeKey.values().associateBy { it.id }
        val valuesByType = values.associateBy { v -> keyById[v.typeId] }

        val weightType = measurementTypeUseCases.getByKey(MeasurementTypeKey.WEIGHT)
        val fatType = measurementTypeUseCases.getByKey(MeasurementTypeKey.BODY_FAT)
        val waterType = measurementTypeUseCases.getByKey(MeasurementTypeKey.WATER)
        val muscleType = measurementTypeUseCases.getByKey(MeasurementTypeKey.MUSCLE)

        val weightValue = valuesByType[MeasurementTypeKey.WEIGHT]?.floatValue
        val weightInKg = if (weightValue != null && weightType != null) {
            ConverterUtils.convertFloatValueUnit(weightValue, weightType.unit, UnitType.KG)
        } else {
            null
        }

        weightInKg?.let { putExtra("weight", it) }

        fun convertToPercent(
            value: Float?,
            fromUnit: UnitType?,
            totalWeightInKg: Float?
        ): Float? {
            if (value == null || fromUnit == null || totalWeightInKg == null || totalWeightInKg == 0f) {
                return null
            }

            if (fromUnit == UnitType.PERCENT) {
                return value
            }

            if (fromUnit.isWeightUnit()) {
                val valueInKg = ConverterUtils.convertFloatValueUnit(value, fromUnit, UnitType.KG)
                return (valueInKg / totalWeightInKg) * 100f
            }

            return null
        }

        val fatPercent = convertToPercent(
            valuesByType[MeasurementTypeKey.BODY_FAT]?.floatValue,
            fatType?.unit,
            weightInKg
        )
        fatPercent?.let { putExtra("fat", it) }

        val waterPercent = convertToPercent(
            valuesByType[MeasurementTypeKey.WATER]?.floatValue,
            waterType?.unit,
            weightInKg
        )
        waterPercent?.let { putExtra("water", it) }

        val musclePercent = convertToPercent(
            valuesByType[MeasurementTypeKey.MUSCLE]?.floatValue,
            muscleType?.unit,
            weightInKg
        )
        musclePercent?.let { putExtra("muscle", it) }
    }

    private companion object {
        const val SYNC_SERVICE_CLASS = "com.health.openscale.sync.core.service.SyncService"
    }
}
