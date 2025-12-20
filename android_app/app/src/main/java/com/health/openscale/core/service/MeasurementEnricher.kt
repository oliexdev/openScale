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
package com.health.openscale.core.service

import android.util.Log
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.Trend
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.ValueWithDifference
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.text.Typography.degree

/**
 * Enriches a chronological list of [MeasurementWithValues] by calculating
 * per-type differences and trend directions against the previous measurement.
 */
@Singleton
class MeasurementEnricher @Inject constructor(
    private val settings : SettingsFacade,
    private val trendCalculator: TrendCalculator
) {
    /**
     * Projects a future trend based on the last N measurements.
     * It performs a linear regression on the recent raw data for each measurement type
     * and extrapolates it for a specified number of future days.
     *
     * @param measurements The complete, chronologically sorted list of recent measurements (newest to oldest).
     * @param allTypes Global type catalog to identify numeric types.
     * @return A list of virtual [MeasurementWithValues] representing the projected points,
     *         or an empty list if not possible.
     */
    suspend fun enrichWithProjection(
        measurements: List<MeasurementWithValues>,
        allTypes: List<MeasurementType>
    ): List<MeasurementWithValues> {
        if (!settings.chartProjectionEnabled.first()) {
            return emptyList()
        }

        val daysInThePast = settings.chartProjectionDaysInThePast.first()
        val daysToProject = settings.chartProjectionDaysToProject.first()
        val polyDegree = settings.chartProjectionPolynomialDegree.first()

        // We need at least 'polyDegree + 1' points for the calculation.
        val requiredPoints = polyDegree + 1

        if (measurements.size < requiredPoints) {
            // Not enough data overall to even attempt a projection.
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val basisCutoffTimestamp = now - (daysInThePast * 24 * 60 * 60 * 1000L)

        // 1. Filter for recent measurements to form the basis of the projection
        val recentMeasurements = measurements.filter {
            it.measurement.timestamp >= basisCutoffTimestamp
        }

        // Re-check after filtering
        if (recentMeasurements.size < requiredPoints) {
            LogManager.w("PolynomialRegression", "Not enough data for degree $polyDegree projection in the last $daysInThePast days. Found ${recentMeasurements.size}, need at least $requiredPoints.")
            return emptyList()
        }

        // 2. Build raw time series for each numeric type from the recent measurements
        val typesById = allTypes.associateBy { it.id }
        val rawSeriesByType = mutableMapOf<Int, MutableList<Pair<Long, Float>>>()

        recentMeasurements.forEach { mwv ->
            val ts = mwv.measurement.timestamp
            mwv.values.forEach { vwt ->
                val type = typesById[vwt.type.id] ?: return@forEach
                if (type.isEnabled && (type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT)) {
                    val numericValue = extractNumeric(vwt)
                    if (numericValue != null) {
                        rawSeriesByType.getOrPut(type.id) { mutableListOf() }.add(ts to numericValue)
                    }
                }
            }
        }

        val lastRealMeasurementId = measurements.first().measurement.id
        val userId = measurements.first().measurement.userId

        // 3. Calculate the projection for each series and flatten them into a single list
        val projectedValues = rawSeriesByType.flatMap { (typeId, series) ->
            if (series.size < requiredPoints) {
                return@flatMap emptyList<MeasurementWithValues>()
            }
            series.sortBy { it.first } // Ensure chronological order

            val coefficients = calculatePolynomialRegression(series, polyDegree)
            if (coefficients.isEmpty()) {
                return@flatMap emptyList<MeasurementWithValues>()
            }

            // Helper function to correctly evaluate the polynomial for any degree
            fun evalPolynomial(x: Double): Double {
                var result = 0.0
                for (i in coefficients.indices) {
                    result += coefficients[i] * x.pow(i)
                }
                return result
            }

            val type = typesById[typeId]!!
            val firstTimestamp = series.first().first
            val lastTimestamp = series.last().first

            val projectionPoints = (1..daysToProject).map { day ->
                val futureTimestamp = lastTimestamp + (day * 24 * 60 * 60 * 1000L)
                val futureX = (futureTimestamp - firstTimestamp).toDouble()
                val projectedValue = evalPolynomial(futureX).toFloat()

                // Create virtual objects directly
                val virtualMeasurement = Measurement(id = -1, userId = userId, timestamp = futureTimestamp)
                val virtualValue = MeasurementValue(id = -1, measurementId = lastRealMeasurementId, typeId = type.id, floatValue = projectedValue)
                val virtualValueWithType = MeasurementValueWithType(value = virtualValue, type = type)
                MeasurementWithValues(measurement = virtualMeasurement, values = listOf(virtualValueWithType))
            }

            // Start the projection from the last known trend point for a seamless line
            val lastRealValue = series.last().second

            val startMeasurement = Measurement(id = -1, userId = userId, timestamp = lastTimestamp)
            val startValue = MeasurementValue(id = -1, measurementId = lastRealMeasurementId, typeId = typeId, floatValue = lastRealValue) // <-- Echter Wert wird verwendet
            val startValueWithType = MeasurementValueWithType(value = startValue, type = type)
            val startPoint = MeasurementWithValues(measurement = startMeasurement, values = listOf(startValueWithType))

            listOf(startPoint) + projectionPoints
        }

        // 5. Return the flat list of all projected points for all types
        return projectedValues
    }

    /**
     * Calculates the coefficients of a polynomial of degree n that best fits the given data series.
     * This uses the method of least squares.
     *
     * @param series The list of (timestamp, value) pairs.
     * @param degree The degree of the polynomial (e.g., 1 for linear, 2 for quadratic).
     * @return A list of coefficients [c0, c1, c2, ...], where the polynomial is y = c0 + c1*x + c2*x^2 + ...
     *         Returns an empty list if the calculation is not possible.
     */
    private fun calculatePolynomialRegression(series: List<Pair<Long, Float>>, degree: Int): List<Double> {
        val firstTimestamp = series.first().first
        val x = series.map { (it.first - firstTimestamp).toDouble() }
        val y = series.map { it.second.toDouble() }

        val a = Array(degree + 1) { DoubleArray(degree + 1) }
        val b = DoubleArray(degree + 1)

        for (i in 0..degree) {
            for (j in 0..degree) {
                a[i][j] = x.sumOf { it.pow(i + j) }
            }
            b[i] = x.zip(y).sumOf { (xi, yi) -> xi.pow(i) * yi }
        }

        // Löse das lineare Gleichungssystem (Gauss-Elimination)
        for (i in 0..degree) {
            var max = i
            for (j in i + 1..degree) {
                if (kotlin.math.abs(a[j][i]) > kotlin.math.abs(a[max][i])) {
                    max = j
                }
            }
            a[i] = a[max].also { a[max] = a[i] }
            b[i] = b[max].also { b[max] = b[i] }

            if (kotlin.math.abs(a[i][i]) <= 1e-10) {
                LogManager.w("PolynomialRegression", "Matrix is singular for degree $degree. Cannot solve.")
                return emptyList()
            } // Matrix ist singulär

            for (j in i + 1..degree) {
                val factor = a[j][i] / a[i][i]
                b[j] -= factor * b[i]
                for (k in i..degree) {
                    a[j][k] -= factor * a[i][k]
                }
            }
        }

        val coefficients = DoubleArray(degree + 1)
        for (i in degree downTo 0) {
            var sum = 0.0
            for (j in i + 1..degree) {
                sum += a[i][j] * coefficients[j]
            }
            coefficients[i] = (b[i] - sum) / a[i][i]
        }

        val coefficientsString = coefficients.map { "%.4f".format(it) }.joinToString()
        LogManager.d("PolynomialRegression", "Calculated coefficients for degree $degree: [$coefficientsString]")

        return coefficients.toList()
    }

    /**
     * Enriches [measurements] with differences and trends against the previous measurement entry.
     * It also sorts the values within each measurement by [MeasurementType.displayOrder].
     *
     * @param measurements List ordered from newest → oldest (the previous item is at index+1).
     * @param allTypes Global type catalog used to resolve canonical ordering and enabled state.
     * @return A flat list of all [ValueWithDifference] objects calculated for the entire list of measurements.
     */
    fun enrichWithDifferences(
        measurements: List<MeasurementWithValues>,
        allTypes: List<MeasurementType>
    ): List<ValueWithDifference> {
        if (measurements.isEmpty()) return emptyList()

        // Quick lookup of type metadata by id
        val typesById = allTypes.associateBy { it.id }

        // Use flatMap to process each measurement and combine the resulting lists of ValueWithDifference.
        return measurements.flatMapIndexed { index, current ->
            val previous: MeasurementWithValues? = measurements.getOrNull(index + 1)

            // Build enriched values for the current measurement.
            // This mapNotNull block correctly returns a List<ValueWithDifference> for a single 'current' measurement.
            current.values.mapNotNull { currV ->
                val type = typesById[currV.type.id] ?: return@mapNotNull null
                if (!type.isEnabled) return@mapNotNull null

                val prevV: MeasurementValueWithType? =
                    previous?.values?.firstOrNull { it.type.id == type.id }

                toValueWithDifference(currV.copy(type = type), prevV)
            }.sortedBy { it.currentValue.type.displayOrder }
        }
    }


    /**
     * Produces a [ValueWithDifference] for one value versus its previous counterpart (same type).
     */
    private fun toValueWithDifference(
        current: MeasurementValueWithType,
        previous: MeasurementValueWithType?
    ): ValueWithDifference {
        val currNum = extractNumeric(current)
        val prevNum = previous?.let { extractNumeric(it) }

        val diff: Float?
        val trend: Trend

        if (currNum != null && prevNum != null) {
            diff = currNum - prevNum
            trend = trendCalculator.calculate(currNum, prevNum)
        } else {
            diff = null
            trend = Trend.NOT_APPLICABLE
        }

        return ValueWithDifference(
            currentValue = current,
            difference = diff,
            trend = trend
        )
    }

    /**
     * Extracts a numeric representation as Float when the input type is numeric.
     * Returns null for non-numeric types.
     */
    private fun extractNumeric(v: MeasurementValueWithType): Float? = when (v.type.inputType) {
        InputFieldType.FLOAT -> v.value.floatValue
        InputFieldType.INT   -> v.value.intValue?.toFloat()
        else -> null
    }
}
