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

import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.utils.CalculationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.map

/**
 * Bundles use cases that apply smoothing to time series contained in [EnrichedMeasurement] lists.
 *
 * The smoothing is applied **per measurement type** (e.g., weight, fat, …) across the
 * chronological series, then mapped back onto each measurement's timestamp. Only types
 * present in [typesToSmoothFlow] and numeric by [InputFieldType] are processed.
 */
@Singleton
class MeasurementSmoothingUseCases @Inject constructor(
    private val settingsFacade: SettingsFacade
) {

    fun observeAlgorithm(): Flow<SmoothingAlgorithm> =
        settingsFacade.chartSmoothingAlgorithm

    fun observeAlpha(): Flow<Float> =
        settingsFacade.chartSmoothingAlpha

    fun observeWindow(): Flow<Int> =
        settingsFacade.chartSmoothingWindowSize

    /**
     * Applies the selected smoothing configuration to the incoming [baseEnrichedFlow].
     *
     * Contract:
     * - If [algorithmFlow] == [SmoothingAlgorithm.NONE] or the input list is empty, the flow is passed through.
     * - For [SmoothingAlgorithm.SIMPLE_MOVING_AVERAGE], the smoothed series is right-aligned to a window
     *   (i.e., the first (window-1) samples don't receive a smoothed value).
     * - For [SmoothingAlgorithm.EXPONENTIAL_SMOOTHING], the series length is preserved.
     *
     * Notes:
     * - Only numeric types ([InputFieldType.FLOAT]/[InputFieldType.INT]) are smoothed.
     * - INT values are rounded after smoothing to keep type semantics when mapped back.
     *
     * @param baseEnrichedFlow The (optionally pre-filtered) enriched measurements (newest → oldest).
     * @param typesToSmoothFlow Set of type ids to smooth.
     * @param measurementTypesFlow Global catalog of measurement types (enabled state & input type).
     * @param algorithmFlow Selected smoothing algorithm.
     * @param alphaFlow Smoothing alpha for exponential smoothing (0..1).
     * @param windowFlow Window size for simple moving average (≥ 1).
     */
    fun applySmoothing(
        baseEnrichedFlow: Flow<List<EnrichedMeasurement>>,
        typesToSmoothFlow: Flow<Set<Int>>,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        algorithmFlow: Flow<SmoothingAlgorithm>,
        alphaFlow: Flow<Float>,
        windowFlow: Flow<Int>
    ): Flow<List<EnrichedMeasurement>> {

        data class Cfg(
            val algorithm: SmoothingAlgorithm,
            val alpha: Float,
            val window: Int
        )

        val cfgFlow: Flow<Cfg> = combine(algorithmFlow, alphaFlow, windowFlow) { algo, alpha, window ->
            Cfg(algo, alpha, window.coerceAtLeast(1))
        }

        return combine(
            baseEnrichedFlow,            // List<EnrichedMeasurement>
            typesToSmoothFlow,           // Set<Int>
            measurementTypesFlow,        // List<MeasurementType>
            cfgFlow                      // Cfg
        ) { measurements, typeIds, allTypes, cfg ->

            if (measurements.isEmpty() ||
                typeIds.isEmpty() ||
                cfg.algorithm == SmoothingAlgorithm.NONE
            ) {
                return@combine measurements
            }

            // Numeric + enabled types that we should smooth
            val candidates: Set<Int> = typeIds.filter { id ->
                allTypes.firstOrNull { it.id == id }?.let {
                    it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT)
                } ?: false
            }.toSet()

            if (candidates.isEmpty()) return@combine measurements

            // Build raw series per type: chronological over the input list (newest → oldest order assumed)
            // We collect (timestamp, numeric) pairs for each candidate type id.
            val rawSeries = mutableMapOf<Int, MutableList<Pair<Long, Float>>>()
            candidates.forEach { rawSeries[it] = mutableListOf() }

            measurements.forEach { em ->
                val ts = em.measurementWithValues.measurement.timestamp
                em.valuesWithTrend.forEach { v ->
                    val typeId = v.currentValue.type.id
                    if (typeId in candidates) {
                        val numeric = when (v.currentValue.type.inputType) {
                            InputFieldType.FLOAT -> v.currentValue.value.floatValue
                            InputFieldType.INT   -> v.currentValue.value.intValue?.toFloat()
                            else                 -> null
                        }
                        if (numeric != null) rawSeries[typeId]!!.add(ts to numeric)
                    }
                }
            }

            // Ensure chronological order by timestamp (in case upstream order changes)
            rawSeries.values.forEach { list -> list.sortBy { it.first } }

            // Smooth per type and map back to timestamp
            val smoothedMapByType: Map<Int, Map<Long, Float>> = rawSeries.mapValues { (_, series) ->
                if (series.isEmpty()) return@mapValues emptyMap()

                val values = series.map { it.second }
                val smoothed: List<Float> = when (cfg.algorithm) {
                    SmoothingAlgorithm.EXPONENTIAL_SMOOTHING ->
                        CalculationUtils.applyExponentialSmoothing(values, cfg.alpha)

                    SmoothingAlgorithm.SIMPLE_MOVING_AVERAGE ->
                        CalculationUtils.applySimpleMovingAverage(values, cfg.window)

                    else -> values
                }

                // For SMA, the result is shorter by (window-1); right-align it to timestamps.
                if (cfg.algorithm == SmoothingAlgorithm.SIMPLE_MOVING_AVERAGE && smoothed.size < series.size) {
                    val offset = series.size - smoothed.size // == window-1
                    smoothed.indices.associate { i ->
                        val ts = series[i + offset].first
                        ts to smoothed[i]
                    }
                } else {
                    // EMA (or NONE) → same length; align 1:1
                    series.indices.associate { i ->
                        val ts = series[i].first
                        ts to smoothed[i]
                    }
                }
            }

            // Write smoothed numbers back into the list (immutably).
            measurements.map { em ->
                val ts = em.measurementWithValues.measurement.timestamp

                // Replace numeric values if a smoothed value exists for the type & timestamp.
                val newValuesWithTrend = em.valuesWithTrend.map { v ->
                    val typeId = v.currentValue.type.id
                    val smoothedAtTs = smoothedMapByType[typeId]?.get(ts) ?: return@map v

                    val inputType = v.currentValue.type.inputType
                    val oldVal = v.currentValue.value
                    val newVal = when (inputType) {
                        InputFieldType.FLOAT -> oldVal.copy(floatValue = smoothedAtTs)
                        InputFieldType.INT   -> oldVal.copy(intValue = smoothedAtTs.roundToInt())
                        else -> oldVal
                    }

                    v.copy(currentValue = v.currentValue.copy(value = newVal))
                }

                em.copy(valuesWithTrend = newValuesWithTrend)
            }
        }.flowOn(Dispatchers.Default)
    }
}
