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
package com.health.openscale.ui.screen.insights

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.facade.SettingsPreferenceKeys.INSIGHTS_SCREEN_CONTEXT
import com.health.openscale.core.model.BodyCompositionPattern
import com.health.openscale.core.model.CompositionPatternType
import com.health.openscale.core.model.InsightConfidence
import com.health.openscale.core.model.MeasurementAnalysis
import com.health.openscale.core.model.MeasurementAnomaly
import com.health.openscale.core.model.MeasurementInsight
import com.health.openscale.core.model.SeasonalPattern
import com.health.openscale.core.model.TrendDirection
import com.health.openscale.core.model.Volatility
import com.health.openscale.core.model.WeekdayPattern
import com.health.openscale.core.usecase.MeasurementInsightsUseCase
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.components.MeasurementTypeFilterRow
import com.health.openscale.ui.screen.components.SELECTED_TYPES_SUFFIX
import com.health.openscale.ui.screen.components.rememberAddMeasurementActionButton
import com.health.openscale.ui.screen.components.rememberBluetoothActionButton
import com.health.openscale.ui.screen.components.rememberContextualSelectedTypeIds
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private const val ANOMALIES_INITIAL_COUNT = 5
private const val ANIM_DURATION_MS = 600

// ---------------------------------------------------------------------------
// InsightsScreen
// ---------------------------------------------------------------------------

/**
 * Displays computed [MeasurementInsight]s for the selected user in a story-style
 * scrollable layout. Each insight section is rendered as a card with a title,
 * a visualization, and a generated summary sentence.
 */
@Composable
fun InsightsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val selectedTypeIdsStrings by rememberContextualSelectedTypeIds(
        screenContextName      = INSIGHTS_SCREEN_CONTEXT,
        observeStringSet       = { key, default -> sharedViewModel.observeSetting(key, default) },
        defaultSelectedTypeIds = emptySet(),
    )
    val primaryTypeId: Int? = remember(selectedTypeIdsStrings) {
        selectedTypeIdsStrings.firstOrNull()?.toIntOrNull()
    }

    val insightsState by sharedViewModel.insightsFlow(primaryTypeId).collectAsStateWithLifecycle()
    val allTypes      by sharedViewModel.measurementTypes.collectAsStateWithLifecycle()

    val bluetoothAction      = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    val addMeasurementAction = rememberAddMeasurementActionButton(sharedViewModel, navController)
    val title                = stringResource(R.string.route_title_insights)

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(title)
        sharedViewModel.setTopBarActions(listOfNotNull(bluetoothAction, addMeasurementAction))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MeasurementTypeFilterRow(
            allMeasurementTypesProvider = { allTypes },
            selectedTypeIdsFlowProvider = {
                sharedViewModel.observeSetting(
                    "${INSIGHTS_SCREEN_CONTEXT}${SELECTED_TYPES_SUFFIX}", emptySet(),
                )
            },
            onPersistSelectedTypeIds = { ids ->
                scope.launch {
                    sharedViewModel.saveSetting(
                        "${INSIGHTS_SCREEN_CONTEXT}${SELECTED_TYPES_SUFFIX}", ids,
                    )
                }
            },
            filterLogic = { types ->
                types.filter {
                    it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT)
                }
            },
            defaultSelectionLogic = { types -> types.firstOrNull()?.let { listOf(it.id) } ?: emptyList() },
            onSelectionChanged    = {},
            singleSelect          = true,
            allowEmptySelection   = false,
        )

        when (val state = insightsState) {
            is SharedViewModel.UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is SharedViewModel.UiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text      = state.message ?: stringResource(R.string.error_loading_data),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            is SharedViewModel.UiState.Success -> {
                val insight     = state.data
                val primaryType = insight.measurementAnalysis?.type
                    ?: insight.weekdayPattern?.type
                    ?: insight.seasonalPattern?.type

                LazyColumn(
                    modifier            = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        val analysis = insight.measurementAnalysis
                        if (analysis != null && analysis.confidence != InsightConfidence.INSUFFICIENT) {
                            MeasurementAnalysisCard(analysis)
                        } else {
                            InsightPlaceholderCard(
                                title   = stringResource(R.string.insights_section_measurement_analysis, primaryType?.getDisplayName(context) ?: ""),
                                message = stringResource(R.string.insights_placeholder_measurement_analysis, MeasurementInsightsUseCase.MIN_TOTAL_MEASUREMENTS),
                            )
                        }
                    }
                    item {
                        val pattern = insight.bodyCompositionPattern
                        if (pattern != null && pattern.confidence != InsightConfidence.INSUFFICIENT) {
                            BodyCompositionPlaneCard(pattern)
                        } else {
                            InsightPlaceholderCard(
                                title   = stringResource(R.string.insights_section_body_pattern),
                                message = stringResource(R.string.insights_placeholder_body_pattern, MeasurementInsightsUseCase.CORRELATION_MIN_MEASUREMENTS, MeasurementInsightsUseCase.CORRELATION_WINDOW_DAYS),
                            )
                        }
                    }
                    item {
                        val pattern = insight.weekdayPattern
                        if (pattern != null && pattern.confidence != InsightConfidence.INSUFFICIENT) {
                            WeekdayPatternCard(pattern)
                        } else {
                            InsightPlaceholderCard(
                                title   = stringResource(R.string.insights_section_weekday),
                                message = stringResource(R.string.insights_placeholder_weekday, WeekdayPattern.MIN_MEASUREMENTS_PER_DAY),
                            )
                        }
                    }
                    item {
                        val pattern = insight.seasonalPattern
                        if (pattern != null && pattern.confidence != InsightConfidence.INSUFFICIENT) {
                            SeasonalPatternCard(pattern)
                        } else {
                            InsightPlaceholderCard(
                                title   = stringResource(R.string.insights_section_seasonal),
                                message = stringResource(R.string.insights_placeholder_seasonal, SeasonalPattern.MIN_YEARS_FOR_PATTERN),
                            )
                        }
                    }
                    item {
                        AnomaliesCard(
                            anomalies      = insight.anomalies,
                            basedOnCount   = insight.basedOnCount,
                            onAnomalyClick = { anomaly ->
                                val uid = sharedViewModel.selectedUserId.value ?: return@AnomaliesCard
                                navController.navigate(Routes.measurementDetail(measurementId = anomaly.measurementId, userId = uid))
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// BodyCompositionShiftCard
// ---------------------------------------------------------------------------

/**
 * Displays a rich visual analysis of [MeasurementAnalysis]:
 * - 4 stat tiles: start, now, rate/month, best period
 * - Sparkline with glow markers for start/min/max/now and plateau zone
 * - Date axis labels
 * - Compact insight chips for trend and volatility
 */
@Composable
private fun MeasurementAnalysisCard(analysis: MeasurementAnalysis) {
    val locale  = Locale.getDefault()
    val context = LocalContext.current

    fun fmt(v: Float)       = LocaleUtils.formatValueForDisplay(v.toString(), analysis.type.unit)
    fun fmtSigned(v: Float) = LocaleUtils.formatValueForDisplay(v.toString(), analysis.type.unit, includeSign = true)

    val dateFormatter  = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val monthFormatter = remember { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMM yyyy"), locale) }

    InsightCard(
        title      = stringResource(R.string.insights_section_measurement_analysis, analysis.type.getDisplayName(context)),
        confidence = analysis.confidence,
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Stat tiles ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnalysisStatTile(
                label    = stringResource(R.string.insights_stat_start),
                value    = fmt(analysis.firstValue),
                sub      = analysis.firstMeasuredOn.format(dateFormatter),
                subColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnalysisStatTile(
                label    = stringResource(R.string.insights_stat_now),
                value    = fmt(analysis.lastValue),
                sub      = fmtSigned(analysis.deltaAbsolute),
                subColor = when {
                    analysis.deltaAbsolute < 0f -> MaterialTheme.colorScheme.tertiary
                    analysis.deltaAbsolute > 0f -> MaterialTheme.colorScheme.error
                    else                     -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            AnalysisStatTile(
                label    = stringResource(R.string.insights_stat_rate_month),
                value    = fmtSigned(analysis.ratePerMonth),
                sub      = stringResource(R.string.insights_stat_on_average),
                subColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnalysisStatTile(
                label    = stringResource(R.string.insights_best_period_label),
                value    = analysis.bestPeriodStart?.format(monthFormatter) ?: "-",
                sub      = analysis.bestPeriodDelta?.let { fmtSigned(it) } ?: "",
                subColor = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Sparkline with timeline markers ───────────────────────────────
        AnalysisSparkline(
            analysis = analysis,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
        )

        Spacer(Modifier.height(4.dp))

        // ── Date axis labels below the sparkline ──────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = analysis.firstMeasuredOn.format(monthFormatter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                text  = analysis.lastMeasuredOn.format(monthFormatter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Insight chips ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Short-term Trend Chip
            InsightChip(
                label = stringResource(R.string.insights_short_term_trend),
                sentiment = analysis.shortTermTrend.toSentiment(),
                icon = analysis.shortTermTrend.toIcon()
            )

            // Long-term Trend Chip
            InsightChip(
                label = stringResource(R.string.insights_long_term_trend),
                sentiment = analysis.longTermTrend.toSentiment(),
                icon = analysis.longTermTrend.toIcon()
            )

            // Volatility Chip
            val (volLabelRes, volSentiment) = when (analysis.volatility) {
                Volatility.STABLE -> R.string.insights_chip_volatility_stable to InsightChipSentiment.GOOD
                Volatility.MODERATE -> R.string.insights_chip_volatility_moderate to InsightChipSentiment.WARN
                Volatility.HIGH -> R.string.insights_chip_volatility_high to InsightChipSentiment.BAD
            }
            InsightChip(
                label = stringResource(volLabelRes),
                sentiment = volSentiment)

            // Plateau Chip
            if (analysis.plateauDays != null && analysis.plateauDays > 0) {
                InsightChip(
                    label = stringResource(R.string.insights_chip_plateau_days, analysis.plateauDays),
                    sentiment = InsightChipSentiment.NEUTRAL)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Summary ───────────────────────────────────────────────────
        val summary: String? = when {
            analysis.plateauDays != null &&
                    analysis.plateauDays > 14 &&
                    java.time.temporal.ChronoUnit.DAYS.between(analysis.lastMeasuredOn, LocalDate.now()) <= 7 ->
                stringResource(R.string.insights_summary_plateau, analysis.plateauDays)

            analysis.shortTermTrend != analysis.longTermTrend && analysis.longTermTrend != TrendDirection.STABLE -> when (analysis.shortTermTrend) {
                TrendDirection.DOWN   -> stringResource(R.string.insights_summary_trend_change_down)
                TrendDirection.UP     -> stringResource(R.string.insights_summary_trend_change_up)
                TrendDirection.STABLE -> null
            }

            analysis.volatility == Volatility.HIGH ->
                stringResource(R.string.insights_summary_volatility_high)

            analysis.volatility == Volatility.STABLE ->
                stringResource(R.string.insights_summary_volatility_stable)

            else -> null
        }

        summary?.let { InsightSummaryText(it) }
    }
}

// ---------------------------------------------------------------------------
// ShiftStatTile
// ---------------------------------------------------------------------------

/** Compact stat tile showing a label, primary value, and optional colored sub-text. */
@Composable
private fun AnalysisStatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String,
    subColor: Color,
) {
    Surface(
        modifier       = modifier.widthIn(min = 80.dp),
        shape          = MaterialTheme.shapes.small,
        color          = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text     = label,
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text       = value,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
            )
            if (sub.isNotBlank()) {
                Text(
                    text  = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = subColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ShiftSparkline
// ---------------------------------------------------------------------------

/**
 * Sparkline combining a line chart with timeline markers for start, min, max, and now.
 * Plateau zones are highlighted as a tinted background region.
 * Label placement is dynamic — markers near the top of the graph get labels below,
 * markers near the bottom get labels above, to avoid overlap with the curve.
 */
@Composable
private fun AnalysisSparkline(
    analysis: MeasurementAnalysis,
    modifier: Modifier = Modifier,
) {
    val labelStart = stringResource(R.string.insights_stat_start)
    val labelNow   = stringResource(R.string.insights_stat_now)
    val labelMin   = stringResource(R.string.statistics_label_min)
    val labelMax   = stringResource(R.string.statistics_label_max)

    val primaryColor  = MaterialTheme.colorScheme.primary
    val errorColor    = MaterialTheme.colorScheme.error
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val neutralColor  = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor  = MaterialTheme.colorScheme.surface
    val plateauColor  = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)

    // Animate line draw from left to right on first composition
    val progress = remember { Animatable(0f) }
    LaunchedEffect(analysis) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = EaseOutCubic))
    }
    val prog = progress.value

    Canvas(modifier = modifier.padding(horizontal = 10.dp)) {
        val w = size.width
        val h = size.height
        val values = analysis.valueHistory
        if (values.size < 2) return@Canvas

        val minV = values.minOf { it.second }
        val maxV = values.maxOf { it.second }
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f
        val minTs = values.first().first
        val maxTs = values.last().first
        val tsRange = (maxTs - minTs).takeIf { it > 0L } ?: 1L

        // Reserve vertical space for labels above and below the curve
        val padTop = 36.dp.toPx()
        val padBottom = 36.dp.toPx()
        val drawH = h - padTop - padBottom

        fun xOf(ts: Long) = ((ts - minTs).toFloat() / tsRange) * w
        fun yOf(v: Float) = padTop + (1f - (v - minV) / range) * drawH

        // ── Plateau background zone ───────────────────────────────────────
        if (analysis.plateauStartDate != null) {
            val plateauMillis = analysis.plateauStartDate
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val plateauX = xOf(plateauMillis).coerceIn(0f, w)
            drawRoundRect(
                color = plateauColor,
                topLeft = Offset(plateauX, padTop),
                size = Size(w - plateauX, drawH),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )
        }

        // ── Sparkline path — animated draw progress ───────────────────────
        val visibleCount = (values.size * prog).toInt().coerceAtLeast(2)
        val path = Path()
        values.take(visibleCount).forEachIndexed { i, (ts, v) ->
            val x = xOf(ts);
            val y = yOf(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            primaryColor,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        if (prog < 1f) return@Canvas

        // ── Markers ───────────────────────────────────────────────────────
        data class MarkerDef(
            val ts: Long, val v: Float,
            val label: String, val sub: String, val date: LocalDate,
            val color: Color,
            var above: Boolean,
        )

        fun tsOf(date: LocalDate) =
            date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun isHigh(v: Float) = (v - minV) > (range / 2f)

        val markers = mutableListOf(
            MarkerDef(
                ts = values.first().first,
                v = analysis.firstValue,
                label = LocaleUtils.formatValueForDisplay(
                    analysis.firstValue.toString(),
                    analysis.type.unit
                ),
                sub = labelStart,
                date = analysis.firstMeasuredOn,
                color = neutralColor,
                above = !isHigh(analysis.firstValue),
            ),
            MarkerDef(
                ts = tsOf(analysis.maxValueDate),
                v = analysis.maxValue,
                label = LocaleUtils.formatValueForDisplay(
                    analysis.maxValue.toString(),
                    analysis.type.unit
                ),
                sub = labelMax,
                date = analysis.maxValueDate,
                color = errorColor,
                above = false,
            ),
            MarkerDef(
                ts = tsOf(analysis.minValueDate),
                v = analysis.minValue,
                label = LocaleUtils.formatValueForDisplay(
                    analysis.minValue.toString(),
                    analysis.type.unit
                ),
                sub = labelMin,
                date = analysis.minValueDate,
                color = tertiaryColor,
                above = true,
            ),
            MarkerDef(
                ts = values.last().first,
                v = analysis.lastValue,
                label = LocaleUtils.formatValueForDisplay(
                    analysis.lastValue.toString(),
                    analysis.type.unit
                ),
                sub = labelNow,
                date = analysis.lastMeasuredOn,
                color = primaryColor,
                above = !isHigh(analysis.lastValue),
            ),
        ).sortedBy { it.ts }

        val minDistance = 65.dp.toPx()
        for (i in 1 until markers.size) {
            val dist = abs(xOf(markers[i].ts) - xOf(markers[i - 1].ts))
            if (dist < minDistance) {
                markers[i].above = !markers[i - 1].above
            }
        }

        val dotRadius = 3.5.dp.toPx()
        val stemLength = 36.dp.toPx()

        markers.forEach { m ->
            val mx = xOf(m.ts)
            val my = yOf(m.v)

            val stemEnd = if (m.above) {
                (my - stemLength).coerceAtLeast(45.dp.toPx())
            } else {
                (my + stemLength).coerceAtMost(h - 45.dp.toPx())
            }

            drawLine(
                color = m.color.copy(alpha = 0.6f),
                start = Offset(mx, my),
                end = Offset(mx, stemEnd),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )

            drawGlowDot(
                center = Offset(mx, my),
                color = m.color,
                surfaceColor = surfaceColor,
                radius = dotRadius
            )

            drawMarkerLabels(
                mx = mx,
                stemEnd = stemEnd,
                above = m.above,
                label = m.label,
                sub = m.sub,
                date = m.date,
                labelColor = m.color.toArgb(),
                subColor = neutralColor.copy(alpha = 0.6f).toArgb(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// BodyCompositionStatePlaneCard
// ---------------------------------------------------------------------------

@Composable
private fun BodyCompositionPlaneCard(pattern: BodyCompositionPattern) {
    val history  = pattern.history
    val locale   = Locale.getDefault()
    val shortFmt = DateTimeFormatter.ofPattern("MMM yy", locale)

    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val transition = remember { Animatable(1f) }
    LaunchedEffect(pattern.windowStartDate) {
        transition.snapTo(0f)
        transition.animateTo(1f, tween(800, easing = EaseOutCubic))
    }

    val pulseAnim = remember { Animatable(0f) }
    LaunchedEffect(selectedIndex) {
        pulseAnim.snapTo(0f)
        while (true) {
            pulseAnim.animateTo(1f, tween(1100, easing = EaseOutCubic))
            pulseAnim.animateTo(0f, tween(700))
        }
    }

    val colorScheme = MaterialTheme.colorScheme

    val mapped = remember(pattern, history) {
        val allPoints   = history + pattern
        val maxAbsDelta = allPoints.maxOfOrNull { p ->
            max(abs(p.fatDelta), abs(p.muscleDelta))
        }?.takeIf { it > 0.1f } ?: 1.0f
        val edgePadding = 0.2f
        val scale       = (0.5f - edgePadding) / maxAbsDelta

        fun map(p: BodyCompositionPattern): Pair<Float, Float> {
            val x = 0.5f + (p.fatDelta * scale)
            val y = 0.5f - (p.muscleDelta * scale)
            return x.coerceIn(0.05f, 0.95f) to y.coerceIn(0.05f, 0.95f)
        }
        history.map { map(it) } to map(pattern)
    }

    val (historyMappedPoints, currentPoint) = mapped
    val (currentX, currentY) = currentPoint

    val displayedPattern = selectedIndex?.let { history.getOrNull(it)?.pattern } ?: pattern.pattern
    val shownEntry       = selectedIndex?.let { history.getOrNull(it) }

    val summaryText = when {
        shownEntry != null && shownEntry.pattern == CompositionPatternType.UNDEFINED ->
            stringResource(R.string.insights_pattern_summary_undefined_with_data, shownEntry.windowStartDate.format(shortFmt))
        else -> when (displayedPattern) {
            CompositionPatternType.FAT_GAIN          -> stringResource(R.string.insights_pattern_summary_fat_gain)
            CompositionPatternType.MUSCLE_AND_FAT_GAIN -> stringResource(R.string.insights_pattern_summary_fat_gain_with_muscle)
            CompositionPatternType.MUSCLE_GAIN       -> stringResource(R.string.insights_pattern_summary_muscle_gain)
            CompositionPatternType.WEIGHT_LOSS_MIXED -> stringResource(R.string.insights_pattern_summary_weight_loss_mixed)
            CompositionPatternType.FAT_LOSS          -> stringResource(R.string.insights_pattern_summary_fat_loss)
            CompositionPatternType.RECOMPOSITION     -> stringResource(R.string.insights_pattern_summary_recomposition)
            CompositionPatternType.STABLE            -> stringResource(R.string.insights_pattern_summary_stable)
            CompositionPatternType.UNDEFINED         -> stringResource(R.string.insights_pattern_summary_undefined)
        }
    }

    InsightCard(title = stringResource(R.string.insights_section_body_pattern), confidence = pattern.confidence) {
        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height

                // Grid lines — center axes drawn thicker
                for (i in 1..5) {
                    val isAxis      = i == 3
                    val alpha       = if (isAxis) 0.4f else 0.2f
                    val strokeWidth = if (isAxis) 1.5f else 0.8f
                    val color       = colorScheme.onSurface.copy(alpha = alpha)
                    drawLine(color, Offset(w * i / 6f, 0f), Offset(w * i / 6f, h), strokeWidth)
                    drawLine(color, Offset(0f, h * i / 6f), Offset(w, h * i / 6f), strokeWidth)
                }

                // History points — glow if selected, dimmed otherwise
                historyMappedPoints.forEachIndexed { index, (hx, hy) ->
                    val center   = Offset(hx * w, hy * h)
                    val isActive = selectedIndex == index
                    if (isActive) {
                        drawCircle(colorScheme.primary.copy(alpha = 0.12f + 0.13f * (1f - pulseAnim.value)), 22f + pulseAnim.value * 12f, center)
                        drawCircle(colorScheme.primary.copy(alpha = 0.28f), 18f, center)
                        drawCircle(colorScheme.primary, 9f, center)
                        drawCircle(colorScheme.surface.copy(alpha = 0.55f), 3.5f, center)
                    } else {
                        drawCircle(colorScheme.onSurface.copy(alpha = 0.10f), 9f, center)
                        drawCircle(colorScheme.onSurface.copy(alpha = 0.22f), 5f, center)
                    }
                }

                // Current point — animated from last history position, pulsing when active
                val lastHistoryOffset = historyMappedPoints.lastOrNull()?.let { (hx, hy) -> Offset(hx * w, hy * h) }
                val target   = Offset(currentX * w, currentY * h)
                val animated = Offset(
                    lerp(lastHistoryOffset?.x ?: target.x, target.x, transition.value),
                    lerp(lastHistoryOffset?.y ?: target.y, target.y, transition.value),
                )
                if (selectedIndex == null) {
                    drawCircle(colorScheme.primary.copy(alpha = 0.10f + 0.12f * (1f - pulseAnim.value)), 26f + pulseAnim.value * 14f, animated)
                    drawCircle(colorScheme.primary.copy(alpha = 0.25f), 20f, animated)
                    drawCircle(colorScheme.primary, 10f, animated)
                    drawCircle(colorScheme.surface.copy(alpha = 0.55f), 4f, animated)
                } else {
                    drawCircle(colorScheme.onSurface.copy(alpha = 0.08f), 20f, animated)
                    drawCircle(colorScheme.onSurface.copy(alpha = 0.20f), 10f, animated)
                }
            }

            // Zone corner labels
            Column(
                modifier            = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TrendLabel(stringResource(R.string.insights_label_recomposition), TrendDirection.UP,   invertColor = true,  tooltipText = stringResource(R.string.insights_zone_tooltip_recomposition))
                    TrendLabel(stringResource(R.string.insights_label_bulking),        TrendDirection.UP,   invertColor = true,  tooltipText = stringResource(R.string.insights_zone_tooltip_bulking))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TrendLabel(stringResource(R.string.insights_label_mixed_loss),    TrendDirection.DOWN, invertColor = true,  tooltipText = stringResource(R.string.insights_zone_tooltip_mixed_loss))
                    TrendLabel(stringResource(R.string.insights_label_fat_gain),      TrendDirection.UP,   invertColor = false, tooltipText = stringResource(R.string.insights_zone_tooltip_fat_gain))
                }
            }
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier              = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                SuggestionChip(
                    onClick = { selectedIndex = null },
                    label   = { Text(stringResource(R.string.insights_stat_now), maxLines = 1, style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis) },
                    colors  = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (selectedIndex == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        labelColor     = if (selectedIndex == null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                    ),
                )
                history.asReversed().forEachIndexed { reversedIdx, p ->
                    val originalIdx = history.size - 1 - reversedIdx
                    val isSelected  = selectedIndex == originalIdx
                    SuggestionChip(
                        onClick = { selectedIndex = if (isSelected) null else originalIdx },
                        label   = { Text(p.windowStartDate.format(shortFmt), maxLines = 1, style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis) },
                        colors  = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            labelColor     = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        InsightSummaryText(summaryText)
    }
}

// ---------------------------------------------------------------------------
// WeekdayPatternCard
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekdayPatternCard(pattern: WeekdayPattern) {
    val locale = Locale.getDefault()
    var animationPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(pattern) { animationPlayed = true }

    InsightCard(title = stringResource(R.string.insights_section_weekday), confidence = pattern.confidence) {
        Spacer(Modifier.height(8.dp))
        val maxAbs = pattern.deviationByDay.values.maxOfOrNull { abs(it) }?.takeIf { it > 0f } ?: 1f

        DayOfWeek.entries.forEachIndexed { index, day ->
            val deviation   = pattern.deviationByDay[day] ?: 0f
            val count       = pattern.measurementCountByDay[day] ?: 0
            val targetWidth = (abs(deviation) / maxAbs).coerceIn(0f, 1f)

            val animatedWidth by animateFloatAsState(
                targetValue   = if (animationPlayed) targetWidth else 0f,
                animationSpec = tween(durationMillis = ANIM_DURATION_MS, delayMillis = index * 60, easing = EaseOutCubic),
                label         = "weekday_bar_$index",
            )
            val barColor = when {
                deviation > 0f -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f + 0.4f * animatedWidth)
                deviation < 0f -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f + 0.4f * animatedWidth)
                else           -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(day.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))

                Box(modifier = Modifier.weight(1f).height(14.dp)) {
                    val tooltipState = rememberTooltipState(isPersistent = true)
                    val tooltipScope = rememberCoroutineScope()
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(
                                    text  = buildString {
                                        appendLine(day.getDisplayName(TextStyle.FULL, locale))
                                        appendLine("Ø ${LocaleUtils.formatValueForDisplay((pattern.overallMean + deviation).toString(), pattern.type.unit)}")
                                        append(LocaleUtils.formatValueForDisplay(deviation.toString(), pattern.type.unit, includeSign = true))
                                        append(" ($count)")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        state    = tooltipState,
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedWidth.coerceAtLeast(0.02f)).clickable { tooltipScope.launch { tooltipState.show() } },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) { drawRoundRect(color = barColor, cornerRadius = CornerRadius(4f)) }
                    }
                }

                Spacer(Modifier.width(8.dp))
                Text(LocaleUtils.formatValueForDisplay(deviation.toString(), pattern.type.unit, includeSign = true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text("($count)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }

        Spacer(Modifier.height(8.dp))
        InsightSummaryText(
            when (pattern.confidence) {
                InsightConfidence.HIGH -> stringResource(R.string.insights_weekday_summary_high, pattern.heaviestDay?.getDisplayName(TextStyle.FULL, locale) ?: "-", pattern.lightestDay?.getDisplayName(TextStyle.FULL, locale) ?: "-")
                else                  -> stringResource(R.string.insights_weekday_summary_low)
            }
        )
    }
}

// ---------------------------------------------------------------------------
// SeasonalPatternCard
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonalPatternCard(pattern: SeasonalPattern) {
    val locale = Locale.getDefault()
    var animationPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(pattern) { animationPlayed = true }

    InsightCard(title = stringResource(R.string.insights_section_seasonal), confidence = pattern.confidence) {
        Spacer(Modifier.height(8.dp))

        val allValues       = pattern.averageValueByMonthAndYear.values.flatMap { it.values }
        val minVal          = allValues.minOrNull() ?: 0f
        val maxVal          = allValues.maxOrNull() ?: 1f
        val range           = (maxVal - minVal).takeIf { it > 0f } ?: 1f
        val sortedYears     = pattern.averageValueByMonthAndYear.keys.sorted()
        val primaryColor    = MaterialTheme.colorScheme.primary
        val surfaceColor    = MaterialTheme.colorScheme.surfaceVariant
        val noDataLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(36.dp))
            Month.entries.forEach { month ->
                Text(month.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))

        sortedYears.forEachIndexed { yearIndex, year ->
            val monthMap = pattern.averageValueByMonthAndYear[year] ?: emptyMap()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(year.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

                Month.entries.forEachIndexed { monthIndex, month ->
                    val avg       = monthMap[month]
                    val intensity = avg?.let { ((it - minVal) / range).coerceIn(0f, 1f) } ?: -1f
                    val target    = if (intensity < 0f) surfaceColor.copy(alpha = 0.3f) else primaryColor.copy(alpha = 0.2f + 0.8f * intensity)
                    val cellColor by animateColorAsState(
                        targetValue   = if (animationPlayed) target else surfaceColor.copy(alpha = 0f),
                        animationSpec = tween(durationMillis = ANIM_DURATION_MS, delayMillis = (yearIndex * 12 + monthIndex) * 20, easing = EaseOutCubic),
                        label         = "heatmap_${year}_${month.value}",
                    )

                    Box(modifier = Modifier.weight(1f).height(18.dp).padding(1.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRoundRect(color = cellColor, cornerRadius = CornerRadius(3f))
                            if (avg == null) drawLine(noDataLineColor, Offset(0f, size.height), Offset(size.width, 0f), 1.dp.toPx())
                        }
                        if (avg != null) {
                            val tooltipState = rememberTooltipState(isPersistent = true)
                            val tooltipScope = rememberCoroutineScope()
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("${month.getDisplayName(TextStyle.SHORT, locale)} $year\n${LocaleUtils.formatValueForDisplay(avg.toString(), pattern.type.unit)}", style = MaterialTheme.typography.labelSmall) } },
                                state    = tooltipState,
                                modifier = Modifier.fillMaxSize().clickable { tooltipScope.launch { tooltipState.show() } },
                            ) {}
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        InsightSummaryText(
            when (pattern.confidence) {
                InsightConfidence.HIGH -> stringResource(R.string.insights_seasonal_summary_high, pattern.highestMonth?.getDisplayName(TextStyle.FULL, locale) ?: "-", pattern.lowestMonth?.getDisplayName(TextStyle.FULL, locale) ?: "-")
                else                  -> stringResource(R.string.insights_seasonal_summary_low)
            }
        )
    }
}

// ---------------------------------------------------------------------------
// AnomaliesCard
// ---------------------------------------------------------------------------

@Composable
private fun AnomaliesCard(
    anomalies: List<MeasurementAnomaly>,
    basedOnCount: Int,
    onAnomalyClick: (MeasurementAnomaly) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = remember(anomalies, showAll) { if (showAll) anomalies else anomalies.take(ANOMALIES_INITIAL_COUNT) }

    InsightCard(title = stringResource(R.string.insights_section_anomalies), confidence = null) {
        when {
            basedOnCount < MeasurementInsightsUseCase.ANOMALY_WINDOW_SIZE -> {
                Spacer(Modifier.height(8.dp))
                InsightSummaryText(stringResource(R.string.insights_placeholder_anomalies, MeasurementInsightsUseCase.ANOMALY_WINDOW_SIZE))
            }
            anomalies.isEmpty() -> {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.insights_anomalies_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                visible.forEachIndexed { index, anomaly ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    AnomalyRow(anomaly, onAnomalyClick)
                }
                if (anomalies.size > ANOMALIES_INITIAL_COUNT) {
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (showAll) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AnomalyRow(anomaly: MeasurementAnomaly, onAnomalyClick: (MeasurementAnomaly) -> Unit) {
    val locale        = Locale.getDefault()
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onAnomalyClick(anomaly) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(anomaly.date.format(dateFormatter), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(LocaleUtils.formatValueForDisplay(anomaly.value.toString(), anomaly.type.unit), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text     = stringResource(R.string.insights_anomaly_deviation, LocaleUtils.formatValueForDisplay(anomaly.deviation.toString(), anomaly.type.unit, includeSign = true), LocaleUtils.formatValueForDisplay(anomaly.expectedValue.toString(), anomaly.type.unit)),
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp),
        )
        anomaly.comment?.let { comment ->
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.insights_anomaly_comment, comment), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared layout components
// ---------------------------------------------------------------------------

/**
 * Base card wrapping all insight sections.
 * Shows a LOW confidence chip in the header when data is limited.
 */
@Composable
private fun InsightCard(
    title: String,
    confidence: InsightConfidence?,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (confidence == InsightConfidence.LOW) {
                    Spacer(Modifier.width(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label   = { Text(stringResource(R.string.insights_confidence_low), style = MaterialTheme.typography.labelSmall) },
                        colors  = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), labelColor = MaterialTheme.colorScheme.onErrorContainer),
                    )
                }
            }
            content()
        }
    }
}

/** Dimmed placeholder card shown when a section lacks sufficient data. */
@Composable
private fun InsightPlaceholderCard(title: String, message: String) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Italic summary sentence at the bottom of each insight card. */
@Composable
private fun InsightSummaryText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------------------
// TrendLabel (used in BodyCompositionStatePlaneCard zone corners)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendLabel(
    label: String,
    trend: TrendDirection,
    invertColor: Boolean = false,
    tooltipText: String,
) {
    val icon  = trend.toIcon()
    val color = when (trend) {
        TrendDirection.UP     -> if (invertColor) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
        TrendDirection.DOWN   -> if (invertColor) MaterialTheme.colorScheme.error    else MaterialTheme.colorScheme.tertiary
        TrendDirection.STABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope        = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltipText, style = MaterialTheme.typography.bodySmall) } },
        state   = tooltipState,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { scope.launch { tooltipState.show() } }) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

// ---------------------------------------------------------------------------
// InsightChip
// ---------------------------------------------------------------------------

enum class InsightChipSentiment { GOOD, BAD, WARN, NEUTRAL }

/**
 * Compact sentiment chip used in insight cards.
 * Uses [Surface] instead of Material3 [SuggestionChip] for tighter padding.
 * Supports an optional [icon] (e.g., for trend arrows).
 */
@Composable
fun InsightChip(
    label: String,
    sentiment: InsightChipSentiment = InsightChipSentiment.NEUTRAL,
    icon: ImageVector? = null,
) {
    val containerColor = when (sentiment) {
        InsightChipSentiment.GOOD    -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        InsightChipSentiment.BAD     -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        InsightChipSentiment.WARN    -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        InsightChipSentiment.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val labelColor = when (sentiment) {
        InsightChipSentiment.GOOD    -> MaterialTheme.colorScheme.onTertiaryContainer
        InsightChipSentiment.BAD     -> MaterialTheme.colorScheme.onErrorContainer
        InsightChipSentiment.WARN    -> MaterialTheme.colorScheme.onSecondaryContainer
        InsightChipSentiment.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape  = MaterialTheme.shapes.extraLarge,
        color  = containerColor,
        border = BorderStroke(0.5.dp, labelColor.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared DrawScope extensions
// ---------------------------------------------------------------------------

/**
 * Draws a glow dot — used for sparkline markers and composition plane points.
 * Three concentric circles create a soft glow effect matching the composition plane style.
 */
fun DrawScope.drawGlowDot(
    center: Offset,
    color: Color,
    surfaceColor: Color,
    radius: Float,
) {
    drawCircle(color = color.copy(alpha = 0.15f), radius = radius * 3f, center = center)
    drawCircle(color = color.copy(alpha = 0.30f), radius = radius * 2f, center = center)
    drawCircle(color = surfaceColor,              radius = radius + 1.5.dp.toPx(), center = center)
    drawCircle(color = color,                     radius = radius, center = center)
}

/**
 * Draws value and sub-label text for a sparkline marker using the native canvas.
 * Placement is determined by [above] — labels go on the opposite side of the stem end.
 */
fun DrawScope.drawMarkerLabels(
    mx: Float,
    stemEnd: Float,
    above: Boolean,
    label: String,
    sub: String,
    date: LocalDate,
    labelColor: Int,
    subColor: Int,
) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textAlign   = android.graphics.Paint.Align.CENTER
    }

    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
    val dateText = date.format(dateFormatter)
    val lineGap  = 12.dp.toPx()

    val subY: Float
    val labelY: Float
    val dateY: Float

    if (above) {
        dateY  = stemEnd - 6.dp.toPx()
        labelY = dateY - lineGap
        subY   = labelY - lineGap
    } else {
        subY   = stemEnd + 6.dp.toPx() + 8.sp.toPx()
        labelY = subY + lineGap
        dateY  = labelY + lineGap
    }

    paint.textSize = 9.sp.toPx()
    paint.color    = subColor
    paint.isFakeBoldText = false
    drawContext.canvas.nativeCanvas.drawText(sub, mx, subY, paint)

    paint.textSize = 10.sp.toPx()
    paint.color    = labelColor
    paint.isFakeBoldText = true
    drawContext.canvas.nativeCanvas.drawText(label, mx, labelY, paint)

    paint.textSize = 8.sp.toPx()
    paint.color    = subColor
    paint.alpha    = (0.7f * 255).toInt()
    drawContext.canvas.nativeCanvas.drawText(dateText, mx, dateY, paint)
}

// ---------------------------------------------------------------------------
// Extension helpers
// ---------------------------------------------------------------------------

/** Maps [TrendDirection] to its corresponding icon vector. */
private fun TrendDirection.toIcon(): ImageVector = when (this) {
    TrendDirection.UP     -> Icons.AutoMirrored.Filled.TrendingUp
    TrendDirection.DOWN   -> Icons.AutoMirrored.Filled.TrendingDown
    TrendDirection.STABLE -> Icons.AutoMirrored.Filled.TrendingFlat
}

/**
 * Maps [TrendDirection] to [InsightChipSentiment] for weight/fat loss context.
 * DOWN = good (losing weight), UP = bad, STABLE = neutral.
 */
private fun TrendDirection.toSentiment(): InsightChipSentiment = when (this) {
    TrendDirection.DOWN   -> InsightChipSentiment.GOOD
    TrendDirection.UP     -> InsightChipSentiment.BAD
    TrendDirection.STABLE -> InsightChipSentiment.NEUTRAL
}