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

import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.model.EnrichedMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.filter

/**
 * Bundles filtering-related use cases for enriched measurements.
 *
 * Contains:
 * - [getTimeFiltered]: filter a stream by a selected [TimeRangeFilter].
 * - [filterByTypes]: filter a list to measurements that contain any of the selected type ids.
 */
@Singleton
class MeasurementFilterUseCases @Inject constructor() {

    /**
     * Filters [enrichedFlow] by the given start and end timestamps.
     * If either timestamp is null, no filtering is applied.
     *
     * @param enrichedFlow The flow of measurements to filter.
     * @param startTimeMillis The start of the time range (inclusive).
     * @param endTimeMillis The end of the time range (inclusive).
     * @return A flow of lists of [EnrichedMeasurement] filtered by the time range.
     */
    fun getTimeFiltered(
        enrichedFlow: Flow<List<EnrichedMeasurement>>,
        startTimeMillis: Long?,
        endTimeMillis: Long?
    ): Flow<List<EnrichedMeasurement>> {
        return enrichedFlow
            .map { all ->
                // If there's no valid time range, return everything.
                if (startTimeMillis == null || endTimeMillis == null) {
                    all
                } else {
                    // Otherwise, filter the data. [6, 8]
                    all.filter { em ->
                        val ts = em.measurementWithValues.measurement.timestamp
                        ts in startTimeMillis..endTimeMillis
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /**
     * Keeps only measurements that include at least one value with a type id contained in [selectedTypeIds].
     *
     * Does not mutate the inner value lists; it filters on the **measurement** level.
     * If [selectedTypeIds] is empty, the original [measurements] are returned.
     */
    fun filterByTypes(
        measurements: List<EnrichedMeasurement>,
        selectedTypeIds: Set<Int>
    ): List<EnrichedMeasurement> {
        if (selectedTypeIds.isEmpty()) return measurements
        return measurements.filter { em ->
            em.valuesWithTrend.any { v -> v.currentValue.type.id in selectedTypeIds }
        }
    }
}
