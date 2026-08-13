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

import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.model.MeasurementWithValues
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Bundles read/query use cases around measurements.
 *
 * Contains:
 * - [getMeasurementsForUser]: stream all measurements (with values) for a user.
 * - [getMeasurementWithValuesById]: stream a single measurement (with values) by id.
 * - [findClosestMeasurement]: pick the item closest to a timestamp (prefers same-day).
 */
@Singleton
class MeasurementQueryUseCases @Inject constructor(
    private val databaseRepository: DatabaseRepository
) {

    /**
     * Returns a cold [Flow] of all measurements (newest → oldest) for the given [userId].
     */
    fun getMeasurementsForUser(userId: Int): Flow<List<MeasurementWithValues>> =
        databaseRepository.getMeasurementsWithValuesForUser(userId)

    /**
     * Returns a cold [Flow] of a single measurement (with values) by its database [id].
     * Emits `null` if the id does not exist.
     */
    fun getMeasurementWithValuesById(id: Int): Flow<MeasurementWithValues?> =
        databaseRepository.getMeasurementWithValuesById(id)

    /** Streams the global measurement type catalog. */
    fun getAllMeasurementTypes(): Flow<List<MeasurementType>> =
        databaseRepository.getAllMeasurementTypes()

    /**
     * Finds the item closest to [selectedTimestamp].
     *
     * Preference:
     * 1) If there are items on the same **local** day, choose the nearest among them.
     * 2) Otherwise, choose the nearest across the full list.
     *
     * @return Pair of (index, item) or `null` if [items] is empty.
     */
    fun findClosestMeasurement(
        selectedTimestamp: Long,
        items: List<MeasurementWithValues>
    ): Pair<Int, MeasurementWithValues>? {
        if (items.isEmpty()) return null

        val zone = ZoneId.systemDefault()
        val selectedDate = Instant.ofEpochMilli(selectedTimestamp).atZone(zone).toLocalDate()

        // Candidates on the same local day
        val sameDay = items.withIndex().filter { (_, mwv) ->
            Instant.ofEpochMilli(mwv.measurement.timestamp).atZone(zone).toLocalDate() == selectedDate
        }

        val best = if (sameDay.isNotEmpty()) {
            sameDay.minBy { (_, mwv) ->
                abs(mwv.measurement.timestamp - selectedTimestamp)
            }
        } else {
            items.withIndex().minByOrNull { (_, mwv) ->
                abs(mwv.measurement.timestamp - selectedTimestamp)
            }
        }

        return best?.let { it.index to it.value }
    }
}
