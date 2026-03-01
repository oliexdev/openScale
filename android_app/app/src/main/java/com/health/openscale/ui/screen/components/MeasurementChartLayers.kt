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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.theme.White
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.LayeredComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

// Helper data class for holding 4 partitioned series
private data class Quad<T>(val first: T, val second: T, val third: T, val fourth: T)

/**
 * Creates, remembers, and updates a [CartesianChartModelProducer].
 * Transforms [ChartSeries] data into Vico's chart model, separating raw, smoothed and projected layers.
 */
@Composable
internal fun rememberChartModelProducer(
    chartSeries: List<ChartSeries>,
    rawChartSeries: List<ChartSeries>,
    isSmoothingActive: Boolean,
    showDataPointsSetting: Boolean
): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(chartSeries, rawChartSeries, isSmoothingActive, showDataPointsSetting) {
        // Partition once instead of 4× filter
        val (smoothedSeries, projectedSeries) = chartSeries.partition { !it.isProjected }
        val (smoothedSeriesStart, smoothedSeriesEnd) = smoothedSeries.partition { !it.type.isOnRightYAxis }
        val (projectedSeriesStart, projectedSeriesEnd) = projectedSeries.partition { !it.type.isOnRightYAxis }

        // Partition raw series only when smoothing active
        val (rawSeriesStart, rawSeriesEnd) = if (isSmoothingActive) {
            val (start, end) = rawChartSeries.filter { !it.isProjected }.partition { !it.type.isOnRightYAxis }
            start to end
        } else {
            emptyList<ChartSeries>() to emptyList()
        }

        modelProducer.runTransaction {
            // Layer 0: Raw points START (only when smoothing active and data points enabled)
            if (rawSeriesStart.isNotEmpty() && showDataPointsSetting) {
                lineSeries { rawSeriesStart.forEach { series(x = it.points.map { p -> p.x }, y = it.points.map { p -> p.y }) } }
            }
            // Layer 1: Raw points END
            if (rawSeriesEnd.isNotEmpty() && showDataPointsSetting) {
                lineSeries { rawSeriesEnd.forEach { series(x = it.points.map { p -> p.x }, y = it.points.map { p -> p.y }) } }
            }
            // Layer 2: Smoothed/plain line START
            if (smoothedSeriesStart.isNotEmpty()) {
                lineSeries { smoothedSeriesStart.forEach { series(x = it.points.map { p -> p.x }, y = it.points.map { p -> p.y }) } }
            }
            // Layer 3: Smoothed/plain line END
            if (smoothedSeriesEnd.isNotEmpty()) {
                lineSeries { smoothedSeriesEnd.forEach { series(x = it.points.map { p -> p.x }, y = it.points.map { p -> p.y }) } }
            }
            // Layer 4: Projected START
            if (projectedSeriesStart.isNotEmpty()) {
                lineSeries { projectedSeriesStart.forEach { series(x = it.points.map { p -> p.x }, y = it.points.map { p -> p.y }) } }
            }
            // Layer 5: Projected END
            if (projectedSeriesEnd.isNotEmpty()) {
                lineSeries { projectedSeriesEnd.forEach { series(x = it.points.map { p -> p.x }, y = it.points.map { p -> p.y }) } }
            }
        }
    }
    return modelProducer
}

/**
 * Creates and remembers the Vico layers for drawing lines on the chart.
 * Handles raw point layers, smoothed line layers, and projected (dashed) layers.
 *
 * @param chartSeries Smoothed/plain series data.
 * @param rawChartSeries Raw (unsmoothed) series data, shown as dots when smoothing is active.
 * @param isSmoothingActive Whether a smoothing algorithm is currently active.
 * @param showDataPointsSetting Whether to show data point dots (when smoothing is off).
 * @param targetMeasurementTypeId If non-null, enables statistics mode (area fill, no points).
 * @param goalValuesForScaling Goal values used to scale the Y-axis range.
 */
@Composable
internal fun rememberChartLayers(
    chartSeries: List<ChartSeries>,
    rawChartSeries: List<ChartSeries>,
    isSmoothingActive: Boolean,
    showDataPointsSetting: Boolean,
    targetMeasurementTypeId: Int?,
    goalValuesForScaling: List<Float> = emptyList()
): List<LineCartesianLayer> {
    // Partition once instead of 6× filter
    val (rawStart, rawEnd) = remember(rawChartSeries, isSmoothingActive) {
        if (isSmoothingActive) {
            val (start, end) = rawChartSeries.filter { !it.isProjected }.partition { !it.type.isOnRightYAxis }
            start to end
        } else {
            emptyList<ChartSeries>() to emptyList()
        }
    }

    val (smoothedStart, smoothedEnd, projectedStart, projectedEnd) = remember(chartSeries) {
        val (smoothed, projected) = chartSeries.partition { !it.isProjected }
        val (sStart, sEnd) = smoothed.partition { !it.type.isOnRightYAxis }
        val (pStart, pEnd) = projected.partition { !it.type.isOnRightYAxis }
        Quad(sStart, sEnd, pStart, pEnd)
    }

    val rawColorsStart       = remember(rawStart) { rawStart.map { Color(it.type.color) } }
    val rawColorsEnd         = remember(rawEnd) { rawEnd.map { Color(it.type.color) } }
    val smoothedColorsStart  = remember(smoothedStart) { smoothedStart.map { Color(it.type.color) } }
    val smoothedColorsEnd    = remember(smoothedEnd) { smoothedEnd.map { Color(it.type.color) } }
    val projectedColorsStart = remember(projectedStart) { projectedStart.map { Color(it.type.color) } }
    val projectedColorsEnd   = remember(projectedEnd) { projectedEnd.map { Color(it.type.color) } }

    val goalValuesDouble = remember(goalValuesForScaling) {
        goalValuesForScaling.map { it.toDouble() }
    }

    val rangeProvider = remember(goalValuesDouble) {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val effectiveMin = (goalValuesDouble + minY).minOrNull() ?: minY
                val delta = maxY - minY
                return if (delta == 0.0) effectiveMin - 1.0 else floor(effectiveMin - 0.1 * delta)
            }
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val effectiveMax = (goalValuesDouble + maxY).maxOrNull() ?: maxY
                val delta = maxY - minY
                return if (delta == 0.0) effectiveMax + 1.0 else ceil(effectiveMax + 0.1 * delta)
            }
        }
    }

    val layers = mutableListOf<LineCartesianLayer>()

    @Composable
    fun addLayer(colors: List<Color>, axisPosition: Axis.Position.Vertical, showPoints: Boolean, isProjection: Boolean, isPointConnected: Boolean = true) {
        layers.add(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    colors.map { color ->
                        createLineSpec(
                            color = color,
                            statisticsMode = targetMeasurementTypeId != null && !isProjection,
                            showPoints = showPoints,
                            isProjection = isProjection,
                            isPointConnected = isPointConnected
                        )
                    }
                ),
                verticalAxisPosition = axisPosition,
                rangeProvider = rangeProvider
            )
        )
    }

    // Layer 0 & 1: RAW points (only when smoothing active and data points enabled)
    if (rawStart.isNotEmpty() && showDataPointsSetting) addLayer(rawColorsStart, Axis.Position.Vertical.Start, showPoints = true, isProjection = false, isPointConnected = false)
    if (rawEnd.isNotEmpty() && showDataPointsSetting)   addLayer(rawColorsEnd,   Axis.Position.Vertical.End,   showPoints = true, isProjection = false, isPointConnected = false)

    // Layer 2 & 3: Smoothed/plain line
    // - Smoothing ON  → line only (no points)
    // - Smoothing OFF → line + optional points via showDataPointsSetting
    if (smoothedStart.isNotEmpty()) addLayer(smoothedColorsStart, Axis.Position.Vertical.Start, showPoints = !isSmoothingActive && showDataPointsSetting, isProjection = false)
    if (smoothedEnd.isNotEmpty())   addLayer(smoothedColorsEnd,   Axis.Position.Vertical.End,   showPoints = !isSmoothingActive && showDataPointsSetting, isProjection = false)

    // Layer 4 & 5: Projected (dashed)
    if (projectedStart.isNotEmpty()) addLayer(projectedColorsStart, Axis.Position.Vertical.Start, showPoints = false, isProjection = true)
    if (projectedEnd.isNotEmpty())   addLayer(projectedColorsEnd,   Axis.Position.Vertical.End,   showPoints = false, isProjection = true)

    return layers
}

/**
 * Creates a [LineCartesianLayer.Line] specification for a single chart series.
 *
 * @param color The line and point color.
 * @param statisticsMode Adds area fill, hides points. Used when [targetMeasurementTypeId] is set.
 * @param showPoints Whether to show dots on data points.
 * @param isProjection Creates a dashed line for future projection data.
 * @param isPointConnected Whether to connect points with a bezier curve.
 */
internal fun createLineSpec(
    color: Color,
    statisticsMode: Boolean,
    showPoints: Boolean,
    isProjection: Boolean = false,
    isPointConnected: Boolean = true,
): LineCartesianLayer.Line {
    val lineStroke = when {
        !isPointConnected -> LineCartesianLayer.LineStroke.Dashed(dashLength = 0.dp)
        isProjection      -> LineCartesianLayer.LineStroke.Dashed(thickness = 2.dp, dashLength = 4.dp, gapLength = 4.dp)
        else              -> LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp)
    }

    val lineFill = LineCartesianLayer.LineFill.single(
        fill = if (isPointConnected) Fill(color) else Fill(color.copy(alpha = 0.5f))
    )

    return LineCartesianLayer.Line(
        fill = lineFill,
        stroke = lineStroke,
        areaFill = if (statisticsMode && !isProjection) LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.2f))) else null,
        pointProvider = if (showPoints && !statisticsMode && !isProjection) {
            LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(ShapeComponent(Fill(color.copy(alpha = 0.7f)),shape = RoundedCornerShape(50)), size = 6.dp)
            )
        } else null,
        pointConnector = LineCartesianLayer.PointConnector.cubic()
    )
}

/**
 * Remembers a [HorizontalLine] decoration for a given user goal.
 *
 * @param goal The goal to visualize.
 * @param type The corresponding [MeasurementType] for color and axis position.
 */
@Composable
internal fun rememberGoalLine(goal: UserGoals, type: MeasurementType?): HorizontalLine {
    val goalColor = type?.let { Color(it.color) } ?: MaterialTheme.colorScheme.onSurface
    val goalFill = Fill(goalColor.copy(alpha = 0.7f))
    val line = rememberLineComponent(fill = goalFill, thickness = 2.dp)
    val labelComponent = rememberTextComponent(
        style = TextStyle(color = White),
        margins = Insets(start = 6.dp),
        padding = Insets(start = 8.dp, end = 8.dp, bottom = 2.dp, top = 2.dp),
        background = ShapeComponent(goalFill, shape = RoundedCornerShape(50)),
    )

    return remember(goal, line) {
        HorizontalLine(
            y = { goal.goalValue.toDouble() },
            line = line,
            labelComponent = labelComponent,
            label = {
                LocaleUtils.formatValueForDisplay(
                    goal.goalValue.toString(),
                    type?.unit ?: UnitType.NONE
                )
            },
            verticalAxisPosition = if (type?.isOnRightYAxis == true) Axis.Position.Vertical.End else Axis.Position.Vertical.Start
        )
    }
}

/**
 * Remembers and configures a [CartesianMarker] for chart interaction.
 *
 * @param valueFormatter The formatter for the value in the marker label.
 * @param showIndicator Whether to show a dot indicator on the line at the marker's position.
 */
@Composable
fun rememberMarker(
    valueFormatter: DefaultCartesianMarker.ValueFormatter = DefaultCartesianMarker.ValueFormatter.default(),
    showIndicator: Boolean = true,
): CartesianMarker {
    val labelBackground = rememberShapeComponent(
        fill = Fill(MaterialTheme.colorScheme.background),
        shape = RoundedCornerShape(50),
        strokeThickness = 1.dp,
        strokeFill = Fill(MaterialTheme.colorScheme.outline),
    )
    val label = rememberTextComponent(
        style = TextStyle(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        padding = Insets(horizontal = 8.dp, vertical = 4.dp),
        background = labelBackground,
        minWidth = TextComponent.MinWidth.fixed(40.dp),
    )
    val indicatorFrontComponent = rememberShapeComponent(Fill(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(50))
    val guideline = rememberAxisGuidelineComponent()

    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = valueFormatter,
        indicator = if (showIndicator) {
            { color ->
                LayeredComponent(
                    back = ShapeComponent(Fill(color.copy(alpha = 0.15f)), shape = RoundedCornerShape(50)),
                    front = LayeredComponent(
                        back = ShapeComponent(fill = Fill(color), shape = RoundedCornerShape(50)),
                        front = indicatorFrontComponent,
                        padding = Insets(5.dp),
                    ),
                    padding = Insets(10.dp),
                )
            }
        } else null,
        indicatorSize = 36.dp,
        guideline = guideline,
    )
}

/**
 * Remembers a [CartesianMarkerVisibilityListener] that triggers [onPointSelected]
 * with the timestamp of the last interacted chart point.
 */
@Composable
internal fun rememberMarkerVisibilityListener(
    chartSeries: List<ChartSeries>,
    onPointSelected: (timestamp: Long) -> Unit
): CartesianMarkerVisibilityListener {
    val lastX = remember { mutableStateOf<Float?>(null) }

    return remember(chartSeries, onPointSelected) {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                lastX.value = targets.lastOrNull()?.x?.toFloat()
            }
            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                lastX.value = targets.lastOrNull()?.x?.toFloat()
            }
            override fun onHidden(marker: CartesianMarker) {
                val x = lastX.value ?: return
                val point = chartSeries.flatMap { it.points }.find { it.x == x }
                val date = point?.date ?: LocalDate.ofEpochDay(x.toLong())
                onPointSelected(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        }
    }
}

/**
 * Remembers a [CartesianValueFormatter] for the X-axis that formats epoch day floats
 * back to human-readable date strings.
 */
@Composable
internal fun rememberXAxisValueFormatter(chartSeries: List<ChartSeries>): CartesianValueFormatter =
    remember(chartSeries) {
        val xToDatesMap = chartSeries.flatMap { it.points }.associate { it.x to it.date }
        CartesianValueFormatter { _, value, _ ->
            (xToDatesMap[value.toFloat()] ?: LocalDate.ofEpochDay(value.toLong()))
                .format(DateTimeFormatter.ofPattern("d MMM"))
        }
    }