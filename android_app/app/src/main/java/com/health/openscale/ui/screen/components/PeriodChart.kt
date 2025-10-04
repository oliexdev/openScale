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

import android.R.attr.textSize
import android.text.Layout
import android.text.TextUtils
import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.stacked
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore

private val BottomAxisLabelKey = ExtraStore.Key<List<String>>()

/**
 * Data class representing a single period column in the chart.
 *
 * @property label Display label for the period (e.g., "Jan 25").
 * @property count Value of the column.
 * @property startTimestamp Start timestamp for the period.
 * @property endTimestamp End timestamp for the period.
 */
data class PeriodDataPoint(
    val label: String,
    val count: Int,
    val startTimestamp: Long,
    val endTimestamp: Long
)

/**
 * Composable displaying a selectable stacked column chart of periods.
 *
 * Selection/deselection triggers only on pointer release to prevent repeated firing
 * when the mouse is held down.
 *
 * @param modifier Modifier for layout/styling
 * @param data List of [PeriodDataPoint] to display
 * @param selectedPeriod Currently selected period, or null
 * @param onPeriodClick Callback when a period is selected or deselected
 */
@Composable
fun PeriodChart(
    modifier: Modifier = Modifier,
    data: List<PeriodDataPoint>,
    selectedPeriod: PeriodDataPoint?,
    onPeriodClick: (PeriodDataPoint?) -> Unit
) {
    // Fill colors for unselected and selected bars
    val unselectedColor = Fill(MaterialTheme.colorScheme.primaryContainer.toArgb())
    val selectedColor = Fill(MaterialTheme.colorScheme.primary.toArgb())

    // Chart model producer that holds and updates the dataset
    val modelProducer = remember { CartesianChartModelProducer() }

    // Define a stacked column layer: one series for unselected, one for selected items
    val columnLayer = rememberColumnCartesianLayer(
        ColumnCartesianLayer.ColumnProvider.series(
            listOf(
                LineComponent(fill = unselectedColor, thicknessDp = 12f),
                LineComponent(fill = selectedColor, thicknessDp = 12f)
            )
        ),
        mergeMode = { ColumnCartesianLayer.MergeMode.stacked() },
    )

    // Update the chart model when data or selected period changes
    LaunchedEffect(data, selectedPeriod) {
        if (data.isNotEmpty()) {
            modelProducer.runTransaction {
                columnSeries {
                    // First series: all unselected items
                    series(data.map { if (it != selectedPeriod) it.count.toDouble() else 0.0 })
                    // Second series: the selected item
                    series(data.map { if (it == selectedPeriod) it.count.toDouble() else 0.0 })
                    // Store labels for axis
                    extras { it[BottomAxisLabelKey] = data.map { it.label } }
                }
            }
        }
    }

    // Track the currently hovered column index
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }

    // Reset hovered/selected state if data changes or selection is invalid
    LaunchedEffect(data) {
        hoveredIndex = null
        if (selectedPeriod != null && selectedPeriod !in data) {
            onPeriodClick(null)
        }
    }

    // Build the Cartesian chart with a bottom axis and marker
    val chart = rememberCartesianChart(
        columnLayer,
        startAxis = null,
        bottomAxis = HorizontalAxis.rememberBottom(
            itemPlacer = HorizontalAxis.ItemPlacer.segmented(),
            valueFormatter = CartesianValueFormatter { context, x, _ ->
                val labels = context.model.extraStore[BottomAxisLabelKey]
                if (labels.isNotEmpty() && x.toInt() in labels.indices) labels[x.toInt()] else ""
            },
            guideline = null,
            label = rememberAxisLabelComponent(
                lineCount = 2,       // allow wrapping if needed
                textSize = 9.sp,
                truncateAt = TextUtils.TruncateAt.MARQUEE
            )
        ),
        marker = rememberMarker(
            DefaultCartesianMarker.ValueFormatter { _, targets ->
                val column = (targets.getOrNull(0) as? ColumnCartesianLayerMarkerTarget)
                    ?.columns?.firstOrNull()
                hoveredIndex = column?.entry?.x?.toInt()
                val hoveredData = hoveredIndex?.let { if (it in data.indices) data[it] else null }
                hoveredData?.let { "${it.label} (${it.count})" } ?: ""
            }
        )
    )

    // Chart host that handles pointer interactions (tap to select/deselect)
    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier.pointerInput(data) {
            while (true) {
                awaitPointerEventScope {
                    val event = awaitPointerEvent()
                    if (event.changes.all { it.changedToUp() }) {
                        hoveredIndex?.let { index ->
                            if (index in data.indices) {
                                val clickedData = data[index]
                                // Toggle selection
                                if (clickedData == selectedPeriod) {
                                    onPeriodClick(null)
                                } else {
                                    onPeriodClick(clickedData)
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}