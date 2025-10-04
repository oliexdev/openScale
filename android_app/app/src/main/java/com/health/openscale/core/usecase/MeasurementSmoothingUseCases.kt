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
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.utils.CalculationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Bundles use cases that apply smoothing to time series contained in [EnrichedMeasurement] lists.
 *
 * The smoothing is applied **per measurement type** (e.g., weight, fat, …) across the
 * chronological series, then mapped back onto each measurement's timestamp. Only types
 * present in [typesToSmoothFlow] and numeric by [InputFieldType] are processed.
 *
 * To handle irregular measurements, it splits the time series into blocks if the time
 * gap between consecutive points exceeds a configurable threshold (`maxGapDaysFlow`).
 * Smoothing is then applied independently to each block.
 */
@Singleton
class MeasurementSmoothingUseCases @Inject constructor() {

    /**
     * Applies the selected smoothing configuration to the incoming [baseEnrichedFlow].
     *
     * This is the main pipeline function for smoothing. It orchestrates several steps:
     * 1. Gathers all necessary configuration (algorithm, parameters, gap detection).
     * 2. Filters out measurements or types that shouldn't be smoothed.
     * 3. For each numeric measurement type, it builds a chronological series.
     * 4. It splits this series into blocks based on the `maxGapDaysFlow` to handle long pauses in measurements.
     * 5. It applies the chosen smoothing algorithm to each block independently.
     * 6. Finally, it constructs a new list of [EnrichedMeasurement] containing only the data points
     *    for which a smoothed value could be calculated, effectively trimming the start of series for SMA.
     *
     * @param baseEnrichedFlow The (optionally pre-filtered) enriched measurements (newest → oldest).
     * @param typesToSmoothFlow Set of type ids to smooth.
     * @param measurementTypesFlow Global catalog of measurement types (for checking enabled state & input type).
     * @param algorithmFlow The selected smoothing algorithm.
     * @param alphaFlow Smoothing factor `alpha` for exponential smoothing (0..1).
     * @param windowFlow Window size for simple moving average (≥ 1).
     * @param maxGapDaysFlow The maximum number of days between measurements before a series is split into a new block.
     * @return A flow of lists of [EnrichedMeasurement] with smoothed values applied. The list will be shorter
     *         than the input for SMA, as initial points are discarded.
     */
    fun applySmoothing(
        baseEnrichedFlow: Flow<List<EnrichedMeasurement>>,
        typesToSmoothFlow: Flow<Set<Int>>,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        algorithmFlow: Flow<SmoothingAlgorithm>,
        alphaFlow: Flow<Float>,
        windowFlow: Flow<Int>,
        maxGapDaysFlow: Flow<Int>
    ): Flow<List<EnrichedMeasurement>> {

        data class Cfg(
            val algorithm: SmoothingAlgorithm,
            val alpha: Float,
            val window: Int,
            val maxGapMillis: Long
        )

        val cfgFlow: Flow<Cfg> = combine(algorithmFlow, alphaFlow, windowFlow, maxGapDaysFlow) { algo, alpha, window, gapDays ->
            Cfg(
                algorithm = algo,
                alpha = alpha,
                window = window.coerceAtLeast(1),
                maxGapMillis = TimeUnit.DAYS.toMillis(gapDays.toLong())
            )
        }

        return combine(
            baseEnrichedFlow,
            typesToSmoothFlow,
            measurementTypesFlow,
            cfgFlow
        ) { measurements, typeIds, allTypes, cfg ->

            if (measurements.isEmpty() || typeIds.isEmpty() || cfg.algorithm == SmoothingAlgorithm.NONE) {
                return@combine measurements
            }

            // Identify numeric and enabled types that we should smooth
            val candidates: Set<Int> = typeIds.filter { id ->
                allTypes.firstOrNull { it.id == id }?.let {
                    it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT)
                } ?: false
            }.toSet()

            if (candidates.isEmpty()) return@combine measurements

            // Build raw series per type from the input list
            val rawSeriesByType = mutableMapOf<Int, MutableList<Pair<Long, Float>>>()
            candidates.forEach { rawSeriesByType[it] = mutableListOf() }

            measurements.forEach { em ->
                val ts = em.measurementWithValues.measurement.timestamp
                em.valuesWithTrend.forEach { v ->
                    val typeId = v.currentValue.type.id
                    if (typeId in candidates) {
                        val numeric = when (v.currentValue.type.inputType) {
                            InputFieldType.FLOAT -> v.currentValue.value.floatValue
                            InputFieldType.INT -> v.currentValue.value.intValue?.toFloat()
                            else -> null
                        }
                        if (numeric != null) rawSeriesByType[typeId]!!.add(ts to numeric)
                    }
                }
            }

            // Ensure chronological order by timestamp (oldest to newest)
            rawSeriesByType.values.forEach { list -> list.sortBy { it.first } }

            // Apply smoothing for each type and collect results in a map of [Timestamp -> SmoothedValue]
            val smoothedMapByType: Map<Int, Map<Long, Float>> = rawSeriesByType.mapValues { (_, series) ->
                if (series.isEmpty()) return@mapValues emptyMap()

                // 1. Split the series into blocks based on the max gap setting.
                // This prevents smoothing across large time gaps (e.g., a multi-month break in measurements).
                val blocks = splitSeriesIntoBlocks(series, cfg.maxGapMillis)

                // 2. Apply smoothing to each block independently and combine the results.
                val finalSmoothedMap = mutableMapOf<Long, Float>()
                blocks.forEach { block ->
                    if (block.isEmpty()) return@forEach

                    val values = block.map { it.second }
                    val smoothedValues: List<Float> = when (cfg.algorithm) {
                        SmoothingAlgorithm.EXPONENTIAL_SMOOTHING ->
                            CalculationUtils.applyExponentialSmoothing(values, cfg.alpha)
                        SmoothingAlgorithm.SIMPLE_MOVING_AVERAGE ->
                            CalculationUtils.applySimpleMovingAverage(values, cfg.window)
                        else -> values
                    }

                    // Map the smoothed values back to their corresponding timestamps for this block.
                    val smoothedBlockMap = alignSmoothedValuesToTimestamps(
                        smoothed = smoothedValues,
                        originalBlock = block,
                        algorithm = cfg.algorithm
                    )
                    finalSmoothedMap.putAll(smoothedBlockMap)
                }
                finalSmoothedMap
            }

            // --- Construct final list of EnrichedMeasurements ---

            // Collect all timestamps for which we have at least one smoothed value.
            val smoothedTimestamps = smoothedMapByType.values.flatMap { it.keys }.toSet()

            // 1. Filter the original list to keep only measurements that have a smoothed value.
            //    This creates a new, shorter list, discarding initial data points for which
            //    no smoothed value could be calculated (e.g., those inside the initial SMA window).
            measurements.filter { em ->
                em.measurementWithValues.measurement.timestamp in smoothedTimestamps
            }
                // 2. Map over the filtered list to replace the original values with the smoothed ones.
                .map { em ->
                    val ts = em.measurementWithValues.measurement.timestamp
                    val newValuesWithTrend = em.valuesWithTrend.map { v ->
                        val typeId = v.currentValue.type.id
                        val smoothedAtTs = smoothedMapByType[typeId]?.get(ts)

                        if (smoothedAtTs == null) {
                            v // Keep original value if this type was not a candidate for smoothing
                        } else {
                            val inputType = v.currentValue.type.inputType
                            val oldVal = v.currentValue.value
                            val newVal = when (inputType) {
                                InputFieldType.FLOAT -> oldVal.copy(floatValue = smoothedAtTs)
                                InputFieldType.INT -> oldVal.copy(intValue = smoothedAtTs.roundToInt())
                                else -> oldVal
                            }
                            v.copy(currentValue = v.currentValue.copy(value = newVal))
                        }
                    }
                    em.copy(valuesWithTrend = newValuesWithTrend)
                }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Splits a time series into multiple blocks if the time between consecutive points
     * exceeds `maxGapMillis`.
     *
     * @param series The chronologically sorted list of (timestamp, value) pairs.
     * @param maxGapMillis The maximum allowed time in milliseconds between points in the same block.
     * @return A list of blocks, where each block is a list of (timestamp, value) pairs.
     */
    private fun splitSeriesIntoBlocks(
        series: List<Pair<Long, Float>>,
        maxGapMillis: Long
    ): List<List<Pair<Long, Float>>> {
        if (series.isEmpty()) return emptyList()

        val blocks = mutableListOf<MutableList<Pair<Long, Float>>>()
        blocks.add(mutableListOf(series.first())) // Start the first block

        for (i in 1 until series.size) {
            val prev = series[i - 1]
            val current = series[i]

            if (current.first - prev.first > maxGapMillis) {
                // Gap detected, start a new block.
                blocks.add(mutableListOf())
            }
            blocks.last().add(current) // Add the current point to the latest block.
        }
        return blocks
    }

    /**
     * Associates a list of smoothed values with the timestamps from their original data block.
     * Handles the alignment difference between SMA (right-aligned) and EMA (1-to-1).
     *
     * @param smoothed The list of calculated smoothed float values.
     * @param originalBlock The original block of (timestamp, value) pairs from which `smoothed` was calculated.
     * @param algorithm The smoothing algorithm that was used.
     * @return A map of [Timestamp -> SmoothedValue].
     */
    private fun alignSmoothedValuesToTimestamps(
        smoothed: List<Float>,
        originalBlock: List<Pair<Long, Float>>,
        algorithm: SmoothingAlgorithm
    ): Map<Long, Float> {
        // For SMA, the result is shorter. We right-align it, mapping the smoothed value
        // to the timestamp of the *last* item in the window.
        return if (algorithm == SmoothingAlgorithm.SIMPLE_MOVING_AVERAGE && smoothed.size < originalBlock.size) {
            val offset = originalBlock.size - smoothed.size // == window_size - 1
            smoothed.indices.associate { i ->
                val ts = originalBlock[i + offset].first
                ts to smoothed[i]
            }
        } else {
            // For EMA, the length is preserved, so we do a direct 1-to-1 mapping.
            originalBlock.indices.associate { i ->
                val ts = originalBlock[i].first
                ts to smoothed[i]
            }
        }
    }
}
