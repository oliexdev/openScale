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
package com.health.openscale.core.facade

import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.service.MeasurementEnricher
import com.health.openscale.core.usecase.MeasurementCrudUseCases
import com.health.openscale.core.usecase.MeasurementFilterUseCases
import com.health.openscale.core.usecase.MeasurementQueryUseCases
import com.health.openscale.core.usecase.MeasurementSmoothingUseCases
import com.health.openscale.core.usecase.SyncUseCases
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.usecase.MeasurementEvaluationUseCases
import com.health.openscale.core.usecase.MeasurementTypeCrudUseCases
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level entry point that orchestrates the typical measurement flow:
 * query → enrich → filter → smooth.
 *
 * Keeps ViewModels thin by providing ready-to-consume flows and simple delegates
 * for CRUD, selection helpers, and sync triggers.
 */
@Singleton
class MeasurementFacade @Inject constructor(
    private val query: MeasurementQueryUseCases,
    private val filter: MeasurementFilterUseCases,
    private val smooth: MeasurementSmoothingUseCases,
    private val crud: MeasurementCrudUseCases,
    private val typeCrud: MeasurementTypeCrudUseCases,
    private val enricher: MeasurementEnricher,
    private val evaluationUseCases: MeasurementEvaluationUseCases
) {

    fun getMeasurementsForUser(userId: Int): Flow<List<MeasurementWithValues>> {
        return query.getMeasurementsForUser(userId)
    }

    suspend fun recalculateDerivedValuesForMeasurement(measurementId: Int) {
        crud.recalculateDerivedValuesForMeasurement(measurementId)
    }

    /**
     * Returns an enriched flow for a user by combining raw measurements with the global type catalog.
     *
     * @param userId Database id of the user.
     * @param measurementTypesFlow Global catalog (typically newest config) used for ordering/enabled state.
     */
    fun enrichedFlowForUser(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>
    ): Flow<List<EnrichedMeasurement>> {
        val base = query.getMeasurementsForUser(userId)
        return combine(base, measurementTypesFlow) { measurements, types ->
            enricher.enrich(measurements, types)
        }
    }

    /**
     * Returns a time-filtered enriched flow.
     *
     * @param userId Database id of the user.
     * @param measurementTypesFlow Global type catalog.
     * @param range Selected time window.
     */
    fun timeFilteredEnrichedFlow(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        range: TimeRangeFilter
    ): Flow<List<EnrichedMeasurement>> {
        val enriched = enrichedFlowForUser(userId, measurementTypesFlow)
        return filter.getTimeFiltered(enriched, range)
    }

    /**
     * Filters a list of enriched measurements to those that contain at least one of the given types.
     */
    fun filterByTypes(
        measurements: List<EnrichedMeasurement>,
        selectedTypeIds: Set<Int>
    ): List<EnrichedMeasurement> = filter.filterByTypes(measurements, selectedTypeIds)

    /**
     * Full pipeline: query + enrich + time filter + (optional) smoothing for selected types.
     *
     * @param userId Database id of the user.
     * @param measurementTypesFlow Global type catalog.
     * @param timeRangeFlow Chosen time window as a flow.
     * @param typesToSmoothFlow Set of type ids to smooth.
     * @param algorithmFlow Selected smoothing algorithm.
     * @param alphaFlow Alpha for exponential smoothing (0..1).
     * @param windowFlow Window for SMA (≥1).
     */
    fun pipeline(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        timeRangeFlow: Flow<TimeRangeFilter>,
        typesToSmoothFlow: Flow<Set<Int>>,
        algorithmFlow: Flow<SmoothingAlgorithm>,
        alphaFlow: Flow<Float>,
        windowFlow: Flow<Int>
    ): Flow<List<EnrichedMeasurement>> {
        val enriched = enrichedFlowForUser(userId, measurementTypesFlow)

        val timeFiltered: Flow<List<EnrichedMeasurement>> =
            combine(enriched, timeRangeFlow) { list, range ->
                filter.getTimeFiltered(flowOf(list), range) // returns Flow<List<...>>
            }.flattenLatest()

        return smooth.applySmoothing(
            baseEnrichedFlow = timeFiltered,
            typesToSmoothFlow = typesToSmoothFlow,
            measurementTypesFlow = measurementTypesFlow,
            algorithmFlow = algorithmFlow,
            alphaFlow = alphaFlow,
            windowFlow = windowFlow
        )
    }

    /**
     * Saves (insert/update) a measurement with its values.
     *
     * @return Result of the operation with the final measurement id on success.
     */
    suspend fun saveMeasurement(
        measurement: Measurement,
        values: List<MeasurementValue>
    ) = crud.saveMeasurement(measurement, values)

    /**
     * Deletes a measurement.
     */
    suspend fun deleteMeasurement(
        measurement: Measurement
    ) = crud.deleteMeasurement(measurement)

    /**
     * Finds the closest item to a timestamp (prefers same-day matches).
     */
    fun findClosestMeasurement(
        selectedTimestamp: Long,
        items: List<MeasurementWithValues>
    ) = query.findClosestMeasurement(selectedTimestamp, items)

    fun evaluate(
        typeKey: MeasurementTypeKey,
        value: Float,
        userEvaluationContext: UserEvaluationContext,
        measuredAtMillis: Long
    ) = evaluationUseCases.evaluate(typeKey, value, userEvaluationContext, measuredAtMillis)

    fun plausiblePercentRangeFor(typeKey: MeasurementTypeKey) =
        evaluationUseCases.plausiblePercentRangeFor(typeKey)

    fun getAllMeasurementTypes(): Flow<List<MeasurementType>> =
        query.getAllMeasurementTypes()

    fun getMeasurementWithValuesById(id: Int): Flow<MeasurementWithValues?> =
        query.getMeasurementWithValuesById(id)

    fun observeSmoothingAlgorithm(): Flow<SmoothingAlgorithm> =
        smooth.observeAlgorithm()

    fun observeSmoothingAlpha(): Flow<Float> =
        smooth.observeAlpha()

    fun observeSmoothingWindow(): Flow<Int> =
        smooth.observeWindow()

    /**
     * Create a new measurement type.
     * Delegates to [MeasurementTypeCrudUseCases.add].
     *
     * @return [Result] with the newly inserted type id.
     */
    suspend fun addMeasurementType(type: MeasurementType): Result<Long> =
        typeCrud.add(type)

    /**
     * Update a measurement type without touching existing values.
     * Delegates to [MeasurementTypeCrudUseCases.update].
     */
    suspend fun updateMeasurementType(type: MeasurementType): Result<Unit> =
        typeCrud.update(type)

    /**
     * Delete a measurement type. Caller must ensure cascading semantics are OK.
     * Delegates to [MeasurementTypeCrudUseCases.delete].
     */
    suspend fun deleteMeasurementType(type: MeasurementType): Result<Unit> =
        typeCrud.delete(type)

    /**
     * Update a type and convert existing values if the unit changed.
     * Delegates to [MeasurementTypeCrudUseCases.updateTypeAndConvertValues].
     *
     * @param original The persisted type before edits (required for unit-change detection).
     * @param updated  The edited type to persist.
     * @return [Result] with a [UnitConversionReport] for concise UI messaging.
     */
    suspend fun updateTypeWithUnitConversion(
        original: MeasurementType,
        updated: MeasurementType
    ): Result<MeasurementTypeCrudUseCases.UnitConversionReport> =
        typeCrud.updateTypeAndConvertValues(original, updated)
}

/**
 * Flattens a Flow<Flow<T>> by always collecting only the latest inner flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> Flow<Flow<T>>.flattenLatest(): Flow<T> =
    this.flatMapLatest { inner -> inner }
