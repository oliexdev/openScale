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
import com.health.openscale.core.model.AggregatedMeasurement
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
 * Bundles use cases that apply smoothing to time series contained in [AggregatedMeasurement] lists.
 *
 * Smoothing is applied **after** aggregation so that the algorithm operates on the
 * already-averaged period values (week, month, …) rather than on raw daily entries.
 * At [com.health.openscale.core.data.AggregationLevel.NONE] the behaviour is identical
 * to the previous implementation because every entry represents a single raw measurement.
 *
 * The smoothing is applied **per measurement type** across the chronological series,
 * then mapped back onto each entry's timestamp. Only types present in [typesToSmoothFlow]
 * and numeric by [InputFieldType] are processed.
 *
 * To handle irregular measurements, the time series is split into blocks if the gap
 * between consecutive points exceeds [maxGapDaysFlow]. Each block is smoothed independently.
 */
@Singleton
class MeasurementSmoothingUseCases @Inject constructor() {

    /**
     * Applies the selected smoothing configuration to [baseAggregatedFlow].
     *
     * Steps:
     * 1. Collect algorithm config (algorithm, alpha, window, gap threshold).
     * 2. For each numeric type in [typesToSmoothFlow], build a chronological series
     *    from the aggregated values.
     * 3. Split each series into blocks at gaps > [maxGapDaysFlow].
     * 4. Smooth each block independently.
     * 5. Replace the matching values inside each [AggregatedMeasurement] with the
     *    smoothed counterparts, leaving everything else untouched.
     *
     * @param baseAggregatedFlow  Aggregated measurements (newest → oldest) to smooth.
     * @param typesToSmoothFlow   Set of type ids to smooth.
     * @param measurementTypesFlow Global catalogue of measurement types.
     * @param algorithmFlow       The selected smoothing algorithm.
     * @param alphaFlow           Smoothing factor for exponential smoothing (0..1).
     * @param windowFlow          Window size for simple moving average (≥ 1).
     * @param maxGapDaysFlow      Max days between entries before the series is split.
     * @return Flow of [AggregatedMeasurement] lists with smoothed values applied.
     */
    fun applySmoothing(
        baseAggregatedFlow: Flow<List<AggregatedMeasurement>>,
        typesToSmoothFlow: Flow<Set<Int>>,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        algorithmFlow: Flow<SmoothingAlgorithm>,
        alphaFlow: Flow<Float>,
        windowFlow: Flow<Int>,
        maxGapDaysFlow: Flow<Int>,
    ): Flow<List<AggregatedMeasurement>> {

        data class Cfg(
            val algorithm: SmoothingAlgorithm,
            val alpha: Float,
            val window: Int,
            val maxGapMillis: Long,
        )

        val cfgFlow: Flow<Cfg> = combine(
            algorithmFlow, alphaFlow, windowFlow, maxGapDaysFlow,
        ) { algo, alpha, window, gapDays ->
            Cfg(
                algorithm    = algo,
                alpha        = alpha,
                window       = window.coerceAtLeast(1),
                maxGapMillis = TimeUnit.DAYS.toMillis(gapDays.toLong()),
            )
        }

        return combine(
            baseAggregatedFlow,
            typesToSmoothFlow,
            measurementTypesFlow,
            cfgFlow,
        ) { measurements, typeIds, allTypes, cfg ->

            if (measurements.isEmpty() || typeIds.isEmpty() || cfg.algorithm == SmoothingAlgorithm.NONE) {
                return@combine measurements
            }

            // Identify numeric, enabled types that should be smoothed
            val candidates: Set<Int> = typeIds.filter { id ->
                allTypes.firstOrNull { it.id == id }?.let {
                    it.isEnabled &&
                            (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT)
                } ?: false
            }.toSet()

            if (candidates.isEmpty()) return@combine measurements

            // Build raw series per type (oldest → newest) from aggregated values
            val rawSeriesByType = mutableMapOf<Int, MutableList<Pair<Long, Float>>>()
            candidates.forEach { rawSeriesByType[it] = mutableListOf() }

            measurements.forEach { agg ->
                val ts = agg.enriched.measurementWithValues.measurement.timestamp
                agg.enriched.valuesWithTrend.forEach { v ->
                    val typeId = v.currentValue.type.id
                    if (typeId in candidates) {
                        val numeric = when (v.currentValue.type.inputType) {
                            InputFieldType.FLOAT -> v.currentValue.value.floatValue
                            InputFieldType.INT   -> v.currentValue.value.intValue?.toFloat()
                            else                 -> null
                        }
                        if (numeric != null) rawSeriesByType[typeId]!!.add(ts to numeric)
                    }
                }
            }

            // Ensure chronological order (oldest → newest)
            rawSeriesByType.values.forEach { list -> list.sortBy { it.first } }

            // Apply smoothing per type → map of Timestamp → SmoothedValue
            val smoothedMapByType: Map<Int, Map<Long, Float>> = rawSeriesByType.mapValues { (_, series) ->
                if (series.isEmpty()) return@mapValues emptyMap()

                val blocks = splitSeriesIntoBlocks(series, cfg.maxGapMillis)

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

                    finalSmoothedMap.putAll(
                        alignSmoothedValuesToTimestamps(smoothedValues, block, cfg.algorithm)
                    )
                }
                finalSmoothedMap
            }

            // Reconstruct AggregatedMeasurement list with smoothed values injected
            measurements.map { agg ->
                val ts = agg.enriched.measurementWithValues.measurement.timestamp
                val hasSmoothed = smoothedMapByType.values.any { it.containsKey(ts) }

                if (!hasSmoothed) {
                    agg
                } else {
                    val newValuesWithTrend = agg.enriched.valuesWithTrend.map { v ->
                        val typeId       = v.currentValue.type.id
                        val smoothedAtTs = smoothedMapByType[typeId]?.get(ts)

                        if (smoothedAtTs == null) {
                            v
                        } else {
                            val oldVal = v.currentValue.value
                            val newVal = when (v.currentValue.type.inputType) {
                                InputFieldType.FLOAT -> oldVal.copy(floatValue = smoothedAtTs)
                                InputFieldType.INT   -> oldVal.copy(intValue = smoothedAtTs.roundToInt())
                                else                 -> oldVal
                            }
                            v.copy(currentValue = v.currentValue.copy(value = newVal))
                        }
                    }
                    agg.copy(enriched = agg.enriched.copy(valuesWithTrend = newValuesWithTrend))
                }
            }
        }.flowOn(Dispatchers.Default)
    }

    private fun splitSeriesIntoBlocks(
        series: List<Pair<Long, Float>>,
        maxGapMillis: Long,
    ): List<List<Pair<Long, Float>>> {
        if (series.isEmpty()) return emptyList()

        val blocks = mutableListOf<MutableList<Pair<Long, Float>>>()
        blocks.add(mutableListOf(series.first()))

        for (i in 1 until series.size) {
            val prev    = series[i - 1]
            val current = series[i]
            if (current.first - prev.first > maxGapMillis) blocks.add(mutableListOf())
            blocks.last().add(current)
        }
        return blocks
    }

    private fun alignSmoothedValuesToTimestamps(
        smoothed: List<Float>,
        originalBlock: List<Pair<Long, Float>>,
        algorithm: SmoothingAlgorithm,
    ): Map<Long, Float> {
        return if (algorithm == SmoothingAlgorithm.SIMPLE_MOVING_AVERAGE &&
            smoothed.size < originalBlock.size
        ) {
            val offset = originalBlock.size - smoothed.size
            smoothed.indices.associate { i ->
                originalBlock[i + offset].first to smoothed[i]
            }
        } else {
            originalBlock.indices.associate { i ->
                originalBlock[i].first to smoothed[i]
            }
        }
    }
}