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
import java.util.Calendar
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
     * Filters [enrichedFlow] by the given [selectedTimeRange].
     *
     * The range is computed in local time:
     * - `ALL_DAYS` → no filtering
     * - `LAST_7_DAYS` / `LAST_30_DAYS` / `LAST_365_DAYS` → from midnight of (today - N) to now
     */
    fun getTimeFiltered(
        enrichedFlow: Flow<List<EnrichedMeasurement>>,
        selectedTimeRange: TimeRangeFilter
    ): Flow<List<EnrichedMeasurement>> {
        return enrichedFlow
            .map { all ->
                if (selectedTimeRange == TimeRangeFilter.ALL_DAYS) {
                    all
                } else {
                    val cal = Calendar.getInstance()
                    val endTime = cal.timeInMillis

                    when (selectedTimeRange) {
                        TimeRangeFilter.LAST_7_DAYS -> cal.add(Calendar.DAY_OF_YEAR, -7)
                        TimeRangeFilter.LAST_30_DAYS -> cal.add(Calendar.DAY_OF_YEAR, -30)
                        TimeRangeFilter.LAST_365_DAYS -> cal.add(Calendar.DAY_OF_YEAR, -365)
                        else -> { /* no-op */ }
                    }

                    // normalize to local midnight
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val startTime = cal.timeInMillis

                    all.filter { em ->
                        val ts = em.measurementWithValues.measurement.timestamp
                        ts in startTime..endTime
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
