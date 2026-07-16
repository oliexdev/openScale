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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.screen.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.openscale.R
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.PhysiqueRating
import com.health.openscale.core.model.EvaluationReferenceTables
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.service.DerivedValuesCalculator
import com.health.openscale.core.utils.CalculationUtils
import com.health.openscale.core.utils.ConverterUtils
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders a physique-rating value as "5 — Standard": the 1–9 index plus its
 * localized Tanita body-type name. Falls back to the plain rounded index if the
 * value is out of range.
 */
fun physiqueRatingDisplayText(context: Context, rawValue: Float): String {
    val index = rawValue.roundToInt()
    val label = PhysiqueRating.fromInt(index)?.getDisplayName(context)
    return if (label != null) "$index — $label" else index.toString()
}

/**
 * Renders a physique rating as the shortened body-type name only ("Std. musc."),
 * without the number — used where horizontal space is tight (overview rows).
 */
fun physiqueRatingShortText(context: Context, rawValue: Float): String {
    val index = rawValue.roundToInt()
    return PhysiqueRating.fromInt(index)?.getShortDisplayName(context) ?: index.toString()
}

/** A single measurement's position on the physique plane, with its band thresholds. */
data class PhysiquePlaneData(
    val fatPercent: Float,
    val musclePercent: Float,
    val fatLow: Float,
    val fatHigh: Float,
    val muscleLow: Float,
    val muscleHigh: Float,
    val rating: Int,
)

/**
 * Builds [PhysiquePlaneData] for a single measurement from its weight/body-fat/
 * muscle values (normalising mass to %), the user's age at that measurement, and
 * the sex-specific reference bands. Returns null when a metric or valid band is
 * missing. Mirrors DerivedValuesCalculator.processPhysiqueRatingCalculation so the
 * overview plane matches the stored rating.
 */
fun computePhysiquePlaneData(
    mwv: MeasurementWithValues,
    gender: GenderType,
    birthDateMillis: Long,
): PhysiquePlaneData? {
    val byKey = mwv.values.associateBy { it.type.key }
    val weightVt = byKey[MeasurementTypeKey.WEIGHT]   ?: return null
    val fatVt    = byKey[MeasurementTypeKey.BODY_FAT] ?: return null
    val muscleVt = byKey[MeasurementTypeKey.MUSCLE]   ?: return null

    val weightRaw = weightVt.value.floatValue ?: return null
    val weightKg  = if (weightVt.type.unit.isWeightUnit())
        ConverterUtils.toKilogram(weightRaw, weightVt.type.unit.toWeightUnit()) else weightRaw
    val fat    = DerivedValuesCalculator.toPercentOfWeight(fatVt.value.floatValue, fatVt.type.unit, weightKg) ?: return null
    val muscle = DerivedValuesCalculator.toPercentOfWeight(muscleVt.value.floatValue, muscleVt.type.unit, weightKg) ?: return null

    val age = CalculationUtils.ageOn(mwv.measurement.timestamp, birthDateMillis)
    val fatBounds = (if (gender == GenderType.MALE) EvaluationReferenceTables.fatMale
        else EvaluationReferenceTables.fatFemale).evaluate(fat, age)
    val muscleBounds = (if (gender == GenderType.MALE) EvaluationReferenceTables.muscleMale
        else EvaluationReferenceTables.muscleFemale).evaluate(muscle, age)
    if (fatBounds.lowLimit < 0f || muscleBounds.lowLimit < 0f) return null

    val fatLow = fatBounds.lowLimit; val fatHigh = fatBounds.highLimit
    val muscleLow = muscleBounds.lowLimit; val muscleHigh = muscleBounds.highLimit
    val fatIdx    = when { fat > fatHigh -> 0; fat >= fatLow -> 1; else -> 2 }
    val muscleIdx = when { muscle > muscleHigh -> 2; muscle >= muscleLow -> 1; else -> 0 }

    return PhysiquePlaneData(
        fatPercent = fat, musclePercent = muscle,
        fatLow = fatLow, fatHigh = fatHigh, muscleLow = muscleLow, muscleHigh = muscleHigh,
        rating = fatIdx * 3 + muscleIdx + 1,
    )
}

/**
 * Static physique-rating plane for a single measurement: body-fat% (Y, high at
 * top) × muscle% (X, high at right), partitioned into the nine body-type zones by
 * the measurement's band thresholds, with the current point plotted and its zone
 * highlighted. No history/trend/description — the Insights screen carries the rich
 * version; this is the compact per-measurement view.
 */
@Composable
fun PhysiqueRatingPlane(
    data: PhysiquePlaneData,
    highlightColor: Color,
    modifier: Modifier = Modifier,
) {
    val context      = LocalContext.current
    val colorScheme  = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()

    fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) "${v.toInt()}%" else String.format(Locale.US, "%.1f%%", v)

    val padX = ((data.muscleHigh - data.muscleLow) * 0.6f).coerceAtLeast(3f)
    val padY = ((data.fatHigh - data.fatLow) * 0.6f).coerceAtLeast(3f)
    val xMin = minOf(data.muscleLow, data.musclePercent) - padX
    val xMax = maxOf(data.muscleHigh, data.musclePercent) + padX
    val yMin = minOf(data.fatLow, data.fatPercent) - padY
    val yMax = maxOf(data.fatHigh, data.fatPercent) + padY

    val curFatIdx    = (data.rating - 1) / 3
    val curMuscleIdx = (data.rating - 1) % 3

    Column(modifier.fillMaxWidth()) {
        Text(
            text  = "▲ " + stringResourceCompat(context, R.string.physique_axis_body_fat),
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                fun px(muscle: Float) = (muscle - xMin) / (xMax - xMin) * w
                fun py(fat: Float)    = h - (fat - yMin) / (yMax - yMin) * h

                val xLow = px(data.muscleLow); val xHigh = px(data.muscleHigh)
                val yHiF = py(data.fatHigh);    val yLoF = py(data.fatLow)
                val xEdges = listOf(0f, xLow, xHigh, w)
                val yEdges = listOf(0f, yHiF, yLoF, h)

                drawRect(
                    color   = highlightColor.copy(alpha = 0.16f),
                    topLeft = Offset(xEdges[curMuscleIdx], yEdges[curFatIdx]),
                    size    = Size(xEdges[curMuscleIdx + 1] - xEdges[curMuscleIdx], yEdges[curFatIdx + 1] - yEdges[curFatIdx]),
                )

                val lineColor = colorScheme.onSurface.copy(alpha = 0.35f)
                drawLine(lineColor, Offset(xLow, 0f), Offset(xLow, h), 1.2f)
                drawLine(lineColor, Offset(xHigh, 0f), Offset(xHigh, h), 1.2f)
                drawLine(lineColor, Offset(0f, yHiF), Offset(w, yHiF), 1.2f)
                drawLine(lineColor, Offset(0f, yLoF), Offset(w, yLoF), 1.2f)

                for (fatIdx in 0..2) {
                    for (muscleIdx in 0..2) {
                        val cx = (xEdges[muscleIdx] + xEdges[muscleIdx + 1]) / 2f
                        val cy = (yEdges[fatIdx] + yEdges[fatIdx + 1]) / 2f
                        val isCurrent = fatIdx == curFatIdx && muscleIdx == curMuscleIdx
                        val label = PhysiqueRating.forZone(fatIdx, muscleIdx).getShortDisplayName(context)
                        val layout = textMeasurer.measure(
                            AnnotatedString(label),
                            style = TextStyle(
                                fontSize   = 10.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color      = if (isCurrent) highlightColor else colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            ),
                        )
                        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
                    }
                }

                // Threshold values, nudged off the grid lines (no background chip).
                val axisStyle = TextStyle(fontSize = 9.sp, color = colorScheme.onSurface.copy(alpha = 0.8f))
                fun label(text: String, left: Float, top: Float) {
                    val layout = textMeasurer.measure(AnnotatedString(text), style = axisStyle)
                    drawText(layout, topLeft = Offset(left.coerceIn(0f, w - layout.size.width), top.coerceIn(0f, h - layout.size.height)))
                }
                label(fmt(data.muscleLow),  xLow + 4f,  h - 14f)
                label(fmt(data.muscleHigh), xHigh + 4f, h - 14f)
                label(fmt(data.fatHigh), 3f, yHiF - 13f)
                label(fmt(data.fatLow),  3f, yLoF - 13f)

                val point = Offset(px(data.musclePercent), py(data.fatPercent))
                drawCircle(highlightColor.copy(alpha = 0.22f), 18f, point)
                drawCircle(highlightColor, 8f, point)
                drawCircle(colorScheme.surface.copy(alpha = 0.7f), 3f, point)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text      = stringResourceCompat(context, R.string.physique_axis_muscle) + " ▶",
            style     = MaterialTheme.typography.labelSmall,
            color     = colorScheme.onSurfaceVariant,
            modifier  = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

private fun stringResourceCompat(context: Context, resId: Int): String = context.getString(resId)
