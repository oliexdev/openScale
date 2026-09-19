/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
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
import com.health.openscale.core.data.Trend
import com.health.openscale.core.model.MeasurementComparison
import com.health.openscale.core.model.MeasurementComparisonItem
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LocaleUtils
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Domain use case to perform side-by-side comparison between two [MeasurementWithValues] records.
 *
 * Computes:
 * - Absolute differences for numeric metrics (`target - base`).
 * - Percentage differences: `((target - base) / abs(base)) * 100%`.
 * - Weekly rates of change: `diff / (daysBetween / 7)`.
 * - Trend directions ([Trend.UP], [Trend.DOWN], [Trend.NONE], [Trend.NOT_APPLICABLE]).
 * - Graceful handling of missing metrics, text metrics, and 0-day elapsed times.
 */
@Singleton
class MeasurementComparisonUseCase @Inject constructor() {

    /**
     * Compares [base] and [target] measurements directly.
     *
     * @param base The baseline reference measurement.
     * @param target The comparison measurement.
     * @param types The catalog of measurement types.
     * @param locale Locale for formatting numbers and units.
     * @param zoneId Time zone for date calculations.
     */
    fun compare(
        base: MeasurementWithValues,
        target: MeasurementWithValues,
        types: List<MeasurementType>,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): MeasurementComparison {
        val baseDate = Instant.ofEpochMilli(base.measurement.timestamp).atZone(zoneId).toLocalDate()
        val targetDate = Instant.ofEpochMilli(target.measurement.timestamp).atZone(zoneId).toLocalDate()
        val daysBetween = abs(ChronoUnit.DAYS.between(baseDate, targetDate))

        val baseValuesByType = base.values.associateBy { it.type.id }
        val targetValuesByType = target.values.associateBy { it.type.id }

        // Find all types that appear in either measurement or in the catalog
        val typesInMeasurements = (base.values.map { it.type.id } + target.values.map { it.type.id }).toSet()

        // Filter and sort types
        val sortedTypes = types
            .filter { it.id in typesInMeasurements && it.isEnabled }
            .sortedWith(
                compareBy<MeasurementType> { it.key != MeasurementType.WEIGHT }
                    .thenBy { it.displayOrder }
                    .thenBy { it.id }
            )

        val items = sortedTypes.mapNotNull { type ->
            val baseVal = baseValuesByType[type.id]
            val targetVal = targetValuesByType[type.id]

            if (baseVal == null && targetVal == null) return@mapNotNull null

            compareItem(
                type = type,
                baseVal = baseVal,
                targetVal = targetVal,
                daysBetween = daysBetween,
                locale = locale,
            )
        }

        return MeasurementComparison(
            baseMeasurement = base,
            targetMeasurement = target,
            daysBetween = daysBetween,
            items = items,
        )
    }

    /**
     * Convenience method that automatically orders two measurements chronologically
     * (the older one as baseline, the newer one as target).
     */
    fun compareChronological(
        measurement1: MeasurementWithValues,
        measurement2: MeasurementWithValues,
        types: List<MeasurementType>,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): MeasurementComparison {
        val (base, target) = if (measurement1.measurement.timestamp <= measurement2.measurement.timestamp) {
            measurement1 to measurement2
        } else {
            measurement2 to measurement1
        }
        return compare(base, target, types, locale, zoneId)
    }

    private fun compareItem(
        type: MeasurementType,
        baseVal: MeasurementValueWithType?,
        targetVal: MeasurementValueWithType?,
        daysBetween: Long,
        locale: Locale,
    ): MeasurementComparisonItem {
        val isNumeric = type.inputType == InputFieldType.FLOAT || type.inputType == InputFieldType.INT

        if (!isNumeric) {
            val baseStr = baseVal?.value?.textValue?.takeIf { it.isNotBlank() } ?: "-"
            val targetStr = targetVal?.value?.textValue?.takeIf { it.isNotBlank() } ?: "-"
            val trend = when {
                baseStr == "-" || targetStr == "-" -> Trend.NOT_APPLICABLE
                baseStr == targetStr -> Trend.NONE
                else -> Trend.NOT_APPLICABLE
            }
            return MeasurementComparisonItem(
                type = type,
                baseValue = null,
                targetValue = null,
                baseFormatted = baseStr,
                targetFormatted = targetStr,
                difference = null,
                differenceFormatted = null,
                percentChange = null,
                percentChangeFormatted = null,
                ratePerWeek = null,
                ratePerWeekFormatted = null,
                trend = trend,
            )
        }

        val baseFloat = extractNumericValue(baseVal)
        val targetFloat = extractNumericValue(targetVal)

        val baseFormatted = baseFloat?.let {
            LocaleUtils.formatValueForDisplay(it.toString(), type.unit, includeSign = false, locale = locale)
        } ?: "-"

        val targetFormatted = targetFloat?.let {
            LocaleUtils.formatValueForDisplay(it.toString(), type.unit, includeSign = false, locale = locale)
        } ?: "-"

        val difference = if (baseFloat != null && targetFloat != null) {
            targetFloat - baseFloat
        } else {
            null
        }

        val differenceFormatted = difference?.let { diff ->
            LocaleUtils.formatValueForDisplay(diff.toString(), type.unit, includeSign = true, locale = locale)
        }

        val percentChange = if (baseFloat != null && targetFloat != null && abs(baseFloat) > 1e-5f) {
            ((targetFloat - baseFloat) / abs(baseFloat)) * 100f
        } else {
            null
        }

        val percentChangeFormatted = percentChange?.let { pct ->
            val sign = if (pct > 0.001f) "+" else if (pct < -0.001f) "−" else ""
            val formattedNum = LocaleUtils.formatNumber(abs(pct).toDouble(), maxFraction = 1, locale = locale)
            "$sign$formattedNum %"
        }

        val ratePerWeek = if (difference != null && daysBetween > 0) {
            difference / (daysBetween / 7.0f)
        } else {
            null
        }

        val ratePerWeekFormatted = ratePerWeek?.let { rate ->
            val formattedRate = LocaleUtils.formatValueForDisplay(rate.toString(), type.unit, includeSign = true, locale = locale)
            "$formattedRate/wk"
        }

        val trend = when {
            difference == null -> Trend.NOT_APPLICABLE
            abs(difference) < 0.001f -> Trend.NONE
            difference > 0f -> Trend.UP
            else -> Trend.DOWN
        }

        return MeasurementComparisonItem(
            type = type,
            baseValue = baseFloat,
            targetValue = targetFloat,
            baseFormatted = baseFormatted,
            targetFormatted = targetFormatted,
            difference = difference,
            differenceFormatted = differenceFormatted,
            percentChange = percentChange,
            percentChangeFormatted = percentChangeFormatted,
            ratePerWeek = ratePerWeek,
            ratePerWeekFormatted = ratePerWeekFormatted,
            trend = trend,
        )
    }

    private fun extractNumericValue(mwt: MeasurementValueWithType?): Float? {
        if (mwt == null) return null
        return when (mwt.type.inputType) {
            InputFieldType.FLOAT -> mwt.value.floatValue
            InputFieldType.INT -> mwt.value.intValue?.toFloat()
            else -> null
        }
    }
}
