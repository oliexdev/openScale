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

import android.R.attr.level
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.data.User
import com.health.openscale.core.model.AggregatedMeasurement
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.service.MeasurementEnricher
import com.health.openscale.core.usecase.MeasurementAggregationUseCase
import com.health.openscale.core.usecase.MeasurementCrudUseCases
import com.health.openscale.core.usecase.MeasurementEvaluationUseCases
import com.health.openscale.core.usecase.MeasurementFilterUseCases
import com.health.openscale.core.usecase.MeasurementQueryUseCases
import com.health.openscale.core.usecase.MeasurementSmoothingUseCases
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
 * [pipeline] is the primary entry point for screens. It returns
 * [Flow<List<AggregatedMeasurement>>] — each entry carries period metadata
 * ([AggregatedMeasurement.periodStartMillis], [AggregatedMeasurement.periodEndMillis],
 * [AggregatedMeasurement.periodKey], [AggregatedMeasurement.aggregatedFromCount])
 * so screens never need to recompute these values themselves.
 *
 * When [AggregationLevel.NONE] is used every raw measurement is wrapped individually
 * with [AggregatedMeasurement.aggregatedFromCount] == 1.
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

    fun getMeasurementsForUser(userId: Int): Flow<List<MeasurementWithValues>> =
        query.getMeasurementsForUser(userId)

    suspend fun recalculateDerivedValuesForMeasurement(measurementId: Int) =
        crud.recalculateDerivedValuesForMeasurement(measurementId)

    // -------------------------------------------------------------------------
    // Enriched flow (no aggregation)
    // -------------------------------------------------------------------------

    /**
     * Returns an enriched flow for a user combining raw measurements with the global
     * type catalogue. Includes historical trend/difference data and future projections.
     */
    fun enrichedFlowForUser(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>,
    ): Flow<List<EnrichedMeasurement>> {
        val base = query.getMeasurementsForUser(userId)
        return combine(base, measurementTypesFlow) { measurements, types ->
            if (measurements.isEmpty()) return@combine emptyList()

            val differenceValues = enricher.enrichWithDifferences(measurements, types)
            val projectedValues  = enricher.enrichWithProjection(measurements, types)

            measurements.mapIndexed { index, current ->
                val trendsForCurrent = differenceValues.filter {
                    it.currentValue.value.measurementId == current.measurement.id
                }
                val projectedForCurrent = if (index == 0) projectedValues else emptyList()
                EnrichedMeasurement(
                    measurementWithValues          = current,
                    valuesWithTrend                = trendsForCurrent,
                    measurementWithValuesProjected = projectedForCurrent,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Time-filtered enriched flow (no aggregation, used internally)
    // -------------------------------------------------------------------------

    fun timeFilteredEnrichedFlow(
        userId: Int,
        measurementTypesFlow: Flow<List<MeasurementType>>,
        startTimeMillis: Long?,
        endTimeMillis: Long?,
    ): Flow<List<EnrichedMeasurement>> =
        filter.getTimeFiltered(
            enrichedFlowForUser(userId, measurementTypesFlow),
            startTimeMillis,
            endTimeMillis,
        )

    // -------------------------------------------------------------------------
    // Full pipeline: query → enrich → filter → smooth → aggregate
    // -------------------------------------------------------------------------

    /**
     * Full pipeline returning [AggregatedMeasurement] entries ready for UI consumption.
     *
     * Aggregation is the final step, applied after smoothing.
     * When [aggregationLevelFlow] emits [AggregationLevel.NONE] every measurement is
     * wrapped individually ([AggregatedMeasurement.aggregatedFromCount] == 1).
     *
     * @param userId                Database id of the user.
     * @param measurementTypesFlow  Global type catalogue.
     * @param startTimeMillisFlow   Start of the time range (inclusive), null = no bound.
     * @param endTimeMillisFlow     End of the time range (exclusive), null = no bound.
     * @param typesToSmoothFlow     Type ids to apply smoothing to.
     * @param algorithmFlow         Selected smoothing algorithm.
     * @param alphaFlow             Alpha for exponential smoothing (0..1).
     * @param windowFlow            Window size for SMA (≥ 1).
     * @param maxGapDaysFlow        Max days between measurements before smoothing resets.
     * @param aggregationLevelFlow  Desired aggregation granularity (default: NONE).
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
    ): Flow<List<AggregatedMeasurement>> {
        val enriched = enrichedFlowForUser(userId, measurementTypesFlow)

        @OptIn(ExperimentalCoroutinesApi::class)
        val timeFiltered: Flow<List<EnrichedMeasurement>> =
            combine(enriched, startTimeMillisFlow, endTimeMillisFlow) { list, startMs, endMs ->
                filter.getTimeFiltered(flowOf(list), startMs, endMs)
            }.flatMapLatest { it }

        // 1. Aggregate first — smoothing then operates on fewer, more meaningful points
        val aggregated: Flow<List<AggregatedMeasurement>> =
            combine(timeFiltered, aggregationLevelFlow) { list, level ->
                aggregation.aggregate(list, level)
            }

        // 2. Smooth after aggregation
        return smooth.applySmoothing(
            baseAggregatedFlow   = aggregated,
            typesToSmoothFlow    = typesToSmoothFlow,
            measurementTypesFlow = measurementTypesFlow,
            algorithmFlow        = algorithmFlow,
            alphaFlow            = alphaFlow,
            windowFlow           = windowFlow,
            maxGapDaysFlow       = maxGapDaysFlow,
        )
    }

    // -------------------------------------------------------------------------
    // Convenience wrapper (used by callers that already have a list)
    // -------------------------------------------------------------------------

    fun aggregateList(
        measurements: List<EnrichedMeasurement>,
        level: AggregationLevel,
    ): List<AggregatedMeasurement> = aggregation.aggregate(measurements, level)

    // -------------------------------------------------------------------------
    // BLE
    // -------------------------------------------------------------------------

    suspend fun saveMeasurementFromBleDevice(
        measurement: Measurement,
        values: List<MeasurementValue>,
    ) {
        val ref = pendingReferenceUser
        if (ref != null) {
            val finalValues = transformation.applyAssistedWeighing(measurement, values, ref)
            crud.saveMeasurement(measurement, finalValues)
        } else {
            val finalMeasurement = transformation.applySmartUserAssignment(measurement, values)
            if (finalMeasurement != null) crud.saveMeasurement(finalMeasurement, values)
        }
    }

    fun setPendingReferenceUserForBle(referenceUser: User?) {
        pendingReferenceUser = referenceUser
    }

    // -------------------------------------------------------------------------
    // CRUD delegates
    // -------------------------------------------------------------------------

    suspend fun saveMeasurement(
        measurement: Measurement,
        values: List<MeasurementValue>,
    ) = crud.saveMeasurement(measurement, values)

    suspend fun deleteMeasurement(measurement: Measurement) =
        crud.deleteMeasurement(measurement)

    // -------------------------------------------------------------------------
    // Measurement types
    // -------------------------------------------------------------------------

    fun getAllMeasurementTypes(): Flow<List<MeasurementType>> =
        query.getAllMeasurementTypes()

    fun getMeasurementWithValuesById(id: Int): Flow<MeasurementWithValues?> =
        query.getMeasurementWithValuesById(id)

    suspend fun addMeasurementType(type: MeasurementType): Result<Long> =
        typeCrud.add(type)

    suspend fun updateMeasurementType(type: MeasurementType): Result<Unit> =
        typeCrud.update(type)

    suspend fun deleteMeasurementType(type: MeasurementType): Result<Unit> =
        typeCrud.delete(type)

    suspend fun updateTypeWithUnitConversion(
        original: MeasurementType,
        updated: MeasurementType,
    ): Result<MeasurementTypeCrudUseCases.UnitConversionReport> =
        typeCrud.updateTypeAndConvertValues(original, updated)

    // -------------------------------------------------------------------------
    // Evaluation / plausibility
    // -------------------------------------------------------------------------

    fun evaluate(
        type: MeasurementType,
        value: Float,
        userEvaluationContext: UserEvaluationContext,
        measuredAtMillis: Long,
    ) = evaluationUseCases.evaluate(type, value, userEvaluationContext, measuredAtMillis)

    fun plausiblePercentRangeFor(typeKey: MeasurementTypeKey) =
        evaluationUseCases.plausiblePercentRangeFor(typeKey)

    // -------------------------------------------------------------------------
    // Selection helper
    // -------------------------------------------------------------------------

    fun findClosestMeasurement(
        selectedTimestamp: Long,
        items: List<MeasurementWithValues>,
    ) = query.findClosestMeasurement(selectedTimestamp, items)

    fun filterByTypes(
        measurements: List<EnrichedMeasurement>,
        selectedTypeIds: Set<Int>,
    ): List<EnrichedMeasurement> = filter.filterByTypes(measurements, selectedTypeIds)
}