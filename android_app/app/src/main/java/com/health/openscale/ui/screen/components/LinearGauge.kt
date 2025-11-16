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
package com.health.openscale.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.ConstraintLayoutBaseScope
import androidx.constraintlayout.compose.Dimension
import com.health.openscale.core.data.EvaluationState
import kotlin.math.max
import kotlin.math.ceil

@Composable
fun LinearGauge(
    value: Float,
    lowLimit: Float?,
    highLimit: Float,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(110.dp), // a bit taller: top labels + bar + bottom label
    labelProvider: (Float) -> String = { "%.1f".format(it) }
) {
    val barHeight = 10f
    val EPS = 1e-3f

    val lowColor    = EvaluationState.LOW.toColor()
    val normalColor = EvaluationState.NORMAL.toColor()
    val highColor   = EvaluationState.HIGH.toColor()
    val indicatorColor = MaterialTheme.colorScheme.onSurface
    val guideColor     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    val hasFirst = lowLimit != null
    val lo = lowLimit
    var hi = highLimit
    if (hasFirst && lo!! > hi) hi = lo

    var span = if (hasFirst) (hi - lo!!) / 2f else 0.3f * hi
    val margin = 0.05f * span
    span = when {
        hasFirst && value - margin < lo!! - span -> lo - value + margin
        !hasFirst && value - margin < hi - span  -> hi - value + margin
        value + margin > hi + span               -> value - hi + margin
        else                                     -> span
    }
    span = when {
        span <= 1f   -> ceil(span * 10.0) / 10.0
        span <= 10f  -> ceil(span.toDouble())
        else         -> 5.0 * ceil(span / 5.0)
    }.toFloat().let { if (it <= EPS) 1f else it }

    val minV = if (lo == null && value >= 0f && hi >= 0f) 0f
    else ((lo ?: hi) - span).coerceAtLeast(0f)
    val maxV = (hi + span).let { if (it <= minV + EPS) minV + 1f else it }
    val denom = (maxV - minV).let { if (it <= EPS) 1f else it }

    fun frac(v: Float) = ((v - minV) / denom).coerceIn(0f, 1f)
    fun map(v: Float, w: Float) = frac(v) * w

    val fMin  = 0f
    val fLow  = if (hasFirst) frac(lo!!) else null
    val fHigh = frac(hi)
    val fMax  = 1f
    val fVal  = frac(value) // current value position (for bottom label)

    Column(modifier = modifier) {

        // ----- Top labels -----
        ConstraintLayout(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .padding(horizontal = 4.dp)
        ) {
            val gMin  = createGuidelineFromAbsoluteLeft(fraction = fMin)
            val gLow  = fLow?.let { createGuidelineFromAbsoluteLeft(fraction = it) }
            val gHigh = createGuidelineFromAbsoluteLeft(fraction = fHigh)
            val gMax  = createGuidelineFromAbsoluteLeft(fraction = fMax)

            val style = MaterialTheme.typography.labelSmall

            @Composable
            fun Label(text: String, guide: ConstraintLayoutBaseScope.VerticalAnchor) {
                val ref = createRef()
                Text(
                    text = text,
                    style = style,
                    maxLines = 1,
                    modifier = Modifier.constrainAs(ref) {
                        width = Dimension.wrapContent
                        start.linkTo(guide)
                        end.linkTo(guide)
                        bottom.linkTo(parent.bottom) // closer to bar
                    },
                    textAlign = TextAlign.Center
                )
            }

            Label(labelProvider(minV), gMin)
            if (hasFirst) Label(labelProvider(lo!!), gLow!!)
            Label(labelProvider(hi), gHigh)
            Label(labelProvider(maxV), gMax)
        }

        // ----- Bar + guides + indicator -----
        Box(Modifier.fillMaxWidth().height(42.dp)) {
            val density = LocalDensity.current
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val yTop = (size.height - barHeight) / 2f

                val xLow = if (hasFirst) map(lo, w) else 0f
                val xHigh = map(hi, w)
                val xVal  = map(value, w)

                if (hasFirst) {
                    drawRect(
                        color = lowColor,
                        topLeft = Offset(0f, yTop),
                        size = Size(max(0f, xLow), barHeight)
                    )
                }
                drawRect(
                    color = normalColor,
                    topLeft = Offset(xLow, yTop),
                    size = Size(max(0f, xHigh - xLow), barHeight)
                )
                drawRect(
                    color = highColor,
                    topLeft = Offset(xHigh, yTop),
                    size = Size(max(0f, w - xHigh), barHeight)
                )

                val extraPx   = with(density) { 10.dp.toPx() }
                val tickStroke = with(density) { 1.dp.toPx() }

                fun drawGuide(x: Float) {
                    drawLine(
                        color = guideColor,
                        start = Offset(x, yTop - extraPx),
                        end   = Offset(x, yTop + barHeight + extraPx),
                        strokeWidth = tickStroke
                    )
                }

                drawGuide(0f)
                if (hasFirst) drawGuide(xLow)
                drawGuide(xHigh)
                drawGuide(w)

                // Bigger indicator triangle
                val triH = 26f
                val triW = 24f
                drawPath(
                    path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(xVal, yTop + barHeight)
                        lineTo(xVal - triW / 2f, yTop + barHeight + triH)
                        lineTo(xVal + triW / 2f, yTop + barHeight + triH)
                        close()
                    },
                    color = indicatorColor
                )
            }
        }

        // ----- Current value label centered under marker -----
        ConstraintLayout(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 4.dp)
        ) {
            val gVal = createGuidelineFromAbsoluteLeft(fraction = fVal)
            val style = MaterialTheme.typography.labelMedium
            val ref = createRef()

            Text(
                text = labelProvider(value),
                style = style,
                modifier = Modifier.constrainAs(ref) {
                    width = Dimension.wrapContent
                    start.linkTo(gVal)
                    end.linkTo(gVal) // horizontally center to marker
                    top.linkTo(parent.top)
                },
                textAlign = TextAlign.Center
            )
        }
    }
}
