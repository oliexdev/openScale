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
import com.health.openscale.core.data.User
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.service.MeasurementEnricher
import com.health.openscale.core.usecase.MeasurementCrudUseCases
import com.health.openscale.core.usecase.MeasurementFilterUseCases
import com.health.openscale.core.usecase.MeasurementQueryUseCases
import com.health.openscale.core.usecase.MeasurementSmoothingUseCases
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
    private val evaluationUseCases: MeasurementEvaluationUseCases,
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
     * Full pipeline: query + enrich + time filter + (optional) smoothing for selected types.
     *
     * This orchestrates the entire data flow for the charts, including robust smoothing that
     * handles irregular time intervals by splitting the data into blocks.
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
        maxGapDaysFlow: Flow<Int>
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

        return smooth.applySmoothing(
            baseEnrichedFlow = timeFiltered,
            typesToSmoothFlow = typesToSmoothFlow,
            measurementTypesFlow = measurementTypesFlow,
            algorithmFlow = algorithmFlow,
            alphaFlow = alphaFlow,
            windowFlow = windowFlow,
            maxGapDaysFlow = maxGapDaysFlow
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
     * Saves a measurement from a BLE device, with special handling for assisted weighing.
     *
     * If a `pendingReferenceUser` (e.g., a person or known reference weight) is set,
     * calculates the target's weight (e.g., a pet or infant) by subtracting the
     * `pendingReferenceUser`'s last known weight from the total weight received.
     * It then saves only this calculated difference as the target's weight.
     * The `typeId` for the saved weight is `MeasurementTypeKey.WEIGHT.id` and the
     * value is stored directly in `floatValue` of a new [MeasurementValue].
     *
     * If no `pendingReferenceUser` is set, saves the [measurement] and [values] as is.
     *
     * @param measurement The [Measurement] object (for the target entity in assisted mode).
     * @param values The list of [MeasurementValue] objects from the BLE device
     *               (representing total weight in assisted mode).
     */
    suspend fun saveMeasurementFromBleDevice(
        measurement: Measurement,
        values: List<MeasurementValue>
    ) {
        val currentReferenceUser = pendingReferenceUser
        val currentWeight = values.find { it.typeId == MeasurementTypeKey.WEIGHT.id }?.floatValue ?: 0f

        if (currentReferenceUser != null) {
            val lastReferenceMeasurement = query.getMeasurementsForUser(currentReferenceUser.id).first()
            val lastReferenceWeight = lastReferenceMeasurement.firstNotNullOfOrNull { measurementWithValues ->
                measurementWithValues.values.find { mv -> mv.type.key == MeasurementTypeKey.WEIGHT}?.value?.floatValue } ?: 0f

            val diffWeight = currentWeight - lastReferenceWeight

            val diffWeightMeasurementValue = MeasurementValue(
                measurementId = measurement.id,
                typeId = MeasurementTypeKey.WEIGHT.id,
                floatValue = diffWeight,
            )

            crud.saveMeasurement(measurement, listOf(diffWeightMeasurementValue))
        } else {
            crud.saveMeasurement(measurement, values)
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

    /**
     * Sets or clears the pending reference user for the next BLE measurement
     * that might require assisted weighing.
     * Call with a User object to set, or null to clear.
     *
     * @param referenceUser The user selected as the reference, or null to clear the context.
     */
    fun setPendingReferenceUserForBle(referenceUser: User?) {
        pendingReferenceUser = referenceUser
    }

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
