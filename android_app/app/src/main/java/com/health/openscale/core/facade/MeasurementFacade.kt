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

import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.data.User
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.service.MeasurementEnricher
import com.health.openscale.core.usecase.MeasurementAggregationUseCase
import com.health.openscale.core.usecase.MeasurementCrudUseCases
import com.health.openscale.core.usecase.MeasurementFilterUseCases
import com.health.openscale.core.usecase.MeasurementQueryUseCases
import com.health.openscale.core.usecase.MeasurementSmoothingUseCases
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.usecase.MeasurementEvaluationUseCases
import com.health.openscale.core.usecase.MeasurementTransformationUseCase
import com.health.openscale.core.usecase.MeasurementTypeCrudUseCases
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level entry point that orchestrates the typical measurement flow:
 * query → enrich → filter → smooth → aggregate.
 *
 * Keeps ViewModels thin by providing ready-to-consume flows and simple delegates
 * for CRUD, selection helpers, and sync triggers.
 */
@Singleton
class MeasurementFacade @Inject constructor(
    private val query: MeasurementQueryUseCases,
    private val filter: MeasurementFilterUseCases,
    private val smooth: MeasurementSmoothingUseCases,
    private val transformation: MeasurementTransformationUseCase,
    private val crud: MeasurementCrudUseCases,
    private val typeCrud: MeasurementTypeCrudUseCases,
    private val enricher: MeasurementEnricher,
    private val evaluationUseCases: MeasurementEvaluationUseCases,
    private val aggregation: MeasurementAggregationUseCase,
) {

    private var pendingReferenceUser: User? = null

    fun getMeasurementsForUser(userId: Int): Flow<List<MeasurementWithValues>> {
        return query.getMeasurementsForUser(userId)
    }

    suspend fun recalculateDerivedValuesForMeasurement(measurementId: Int) {
        crud.recalculateDerivedValuesForMeasurement(measurementId)
    }

    /**
     * Returns an enriched flow for a user by combining raw measurements with the global type catalog.
     * This flow includes both historical trend/difference data and future projection data.
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
            if (measurements.isEmpty()) {
                return@combine emptyList()
            }

            val differenceValues = enricher.enrichWithDifferences(measurements, types)
            val projectedValues = enricher.enrichWithProjection(measurements, types)

            measurements.mapIndexed { index, currentMeasurement ->
                val trendsForCurrent = differenceValues.filter {
                    it.currentValue.value.measurementId == currentMeasurement.measurement.id
                }

                val projectedForCurrent = if (index == 0) projectedValues else emptyList()

                EnrichedMeasurement(
                    measurementWithValues = currentMeasurement,
                    valuesWithTrend = trendsForCurrent,
                    measurementWithValuesProjected = projectedForCurrent
                )
            }
        }
    }

    /**
     * Returns an enriched and aggregated flow for a user.
     * When [levelFlow] emits [AggregationLevel.NONE], this is identical to [enrichedFlowForUser].
     *
     * @param userId Database id of the user.
     * @param measurementTypesFlow Global catalog.
     * @param levelFlow Flow emitting the desired aggregation granularity.
     */
    fun aggregatedEnrichedFlowForUser(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        levelFlow: Flow<AggregationLevel>
    ): Flow<List<EnrichedMeasurement>> {
        return combine(
            enrichedFlowForUser(userId, measurementTypesFlow),
            levelFlow
        ) { enriched, level ->
            aggregation.aggregate(enriched, level)
        }
    }

    /**
     * Returns a time-filtered enriched flow based on start and end timestamps.
     *
     * @param userId Database id of the user.
     * @param measurementTypesFlow Global type catalog.
     * @param startTimeMillis The start of the time range (inclusive), or null for no start bound.
     * @param endTimeMillis The end of the time range (inclusive), or null for no end bound.
     */
    fun timeFilteredEnrichedFlow(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        startTimeMillis: Long?,
        endTimeMillis: Long?
    ): Flow<List<EnrichedMeasurement>> {
        val enriched = enrichedFlowForUser(userId, measurementTypesFlow)
        return filter.getTimeFiltered(enriched, startTimeMillis, endTimeMillis)
    }

    /**
     * Filters a list of enriched measurements to those that contain at least one of the given types.
     */
    fun filterByTypes(
        measurements: List<EnrichedMeasurement>,
        selectedTypeIds: Set<Int>
    ): List<EnrichedMeasurement> = filter.filterByTypes(measurements, selectedTypeIds)

    /**
     * Full pipeline: query → enrich → time filter → smooth → aggregate.
     *
     * Aggregation is applied as the final step, after smoothing.
     * [aggregationLevelFlow] defaults to NONE for full backwards compatibility
     * with existing callers that do not yet pass an aggregation level.
     *
     * @param userId Database id of the user.
     * @param measurementTypesFlow Global type catalog.
     * @param startTimeMillisFlow Flow emitting the start timestamp for filtering, or null for no start bound.
     * @param endTimeMillisFlow Flow emitting the end timestamp for filtering, or null for no end bound.
     * @param typesToSmoothFlow Set of type ids to smooth.
     * @param algorithmFlow Selected smoothing algorithm.
     * @param alphaFlow Alpha for exponential smoothing (0..1).
     * @param windowFlow Window for SMA (≥1).
     * @param maxGapDaysFlow The maximum number of days between measurements before smoothing is reset.
     * @param aggregationLevelFlow The desired aggregation granularity (default: NONE).
     */
    fun pipeline(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        startTimeMillisFlow: Flow<Long?>,
        endTimeMillisFlow: Flow<Long?>,
        typesToSmoothFlow: Flow<Set<Int>>,
        algorithmFlow: Flow<SmoothingAlgorithm>,
        alphaFlow: Flow<Float>,
        windowFlow: Flow<Int>,
        maxGapDaysFlow: Flow<Int>,
        aggregationLevelFlow: Flow<AggregationLevel> = flowOf(AggregationLevel.NONE),
    ): Flow<List<EnrichedMeasurement>> {
        val enriched = enrichedFlowForUser(userId, measurementTypesFlow)

        @OptIn(ExperimentalCoroutinesApi::class)
        val timeFiltered: Flow<List<EnrichedMeasurement>> =
            combine(
                enriched,
                startTimeMillisFlow,
                endTimeMillisFlow
            ) { list, startTime, endTime ->
                filter.getTimeFiltered(flowOf(list), startTime, endTime)
            }.flatMapLatest { it }

        val smoothed = smooth.applySmoothing(
            baseEnrichedFlow = timeFiltered,
            typesToSmoothFlow = typesToSmoothFlow,
            measurementTypesFlow = measurementTypesFlow,
            algorithmFlow = algorithmFlow,
            alphaFlow = alphaFlow,
            windowFlow = windowFlow,
            maxGapDaysFlow = maxGapDaysFlow
        )

        // Aggregation is the final step after smoothing
        return combine(smoothed, aggregationLevelFlow) { list, level ->
            aggregation.aggregate(list, level)
        }
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
     * Saves a measurement from a BLE device, with special handling for assisted weighing.
     */
    suspend fun saveMeasurementFromBleDevice(
        measurement: Measurement,
        values: List<MeasurementValue>
    ) {
        val currentReferenceUser = pendingReferenceUser

        if (currentReferenceUser != null) {
            val finalValues = transformation.applyAssistedWeighing(measurement, values, currentReferenceUser)
            crud.saveMeasurement(measurement, finalValues)
        } else {
            val finalMeasurement = transformation.applySmartUserAssignment(measurement, values)
            if (finalMeasurement != null) {
                crud.saveMeasurement(finalMeasurement, values)
            }
        }
    }

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
        type: MeasurementType,
        value: Float,
        userEvaluationContext: UserEvaluationContext,
        measuredAtMillis: Long
    ) = evaluationUseCases.evaluate(type, value, userEvaluationContext, measuredAtMillis)

    /**
     * Convenience wrapper to aggregate an already-fetched list without a full pipeline.
     * Used by SharedViewModel.aggregatedEnrichedMeasurementsFromOverview.
     */
    fun aggregateList(
        measurements: List<EnrichedMeasurement>,
        level: AggregationLevel
    ): List<EnrichedMeasurement> = aggregation.aggregate(measurements, level)

    fun plausiblePercentRangeFor(typeKey: MeasurementTypeKey) =
        evaluationUseCases.plausiblePercentRangeFor(typeKey)

    fun getAllMeasurementTypes(): Flow<List<MeasurementType>> =
        query.getAllMeasurementTypes()

    fun getMeasurementWithValuesById(id: Int): Flow<MeasurementWithValues?> =
        query.getMeasurementWithValuesById(id)

    /**
     * Sets or clears the pending reference user for the next BLE measurement
     * that might require assisted weighing.
     */
    fun setPendingReferenceUserForBle(referenceUser: User?) {
        pendingReferenceUser = referenceUser
    }

    /**
     * Create a new measurement type.
     */
    suspend fun addMeasurementType(type: MeasurementType): Result<Long> =
        typeCrud.add(type)

    /**
     * Update a measurement type without touching existing values.
     */
    suspend fun updateMeasurementType(type: MeasurementType): Result<Unit> =
        typeCrud.update(type)

    /**
     * Delete a measurement type.
     */
    suspend fun deleteMeasurementType(type: MeasurementType): Result<Unit> =
        typeCrud.delete(type)

    /**
     * Update a type and convert existing values if the unit changed.
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