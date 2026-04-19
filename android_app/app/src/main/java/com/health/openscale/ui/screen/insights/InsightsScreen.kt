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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MultilineChart
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.graphics.Color
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
import com.health.openscale.core.model.BodyCompositionShift
import com.health.openscale.core.model.CompositionPatternType
import com.health.openscale.core.model.InsightConfidence
import com.health.openscale.core.model.MeasurementAnomaly
import com.health.openscale.core.model.MeasurementInsight
import com.health.openscale.core.model.SeasonalPattern
import com.health.openscale.core.model.ShiftTrend
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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.collections.isNotEmpty
import kotlin.math.max
import kotlin.math.abs

/** Maximum anomalies shown before the "Show more" button appears. */
private const val ANOMALIES_INITIAL_COUNT = 5

/** Animation duration in milliseconds for bar and heatmap reveals. */
private const val ANIM_DURATION_MS = 600

/**
 * Displays computed [MeasurementInsight]s for the selected user in a story-style
 * scrollable layout. Each insight section is rendered as a card with a title,
 * a visualization, and a generated summary sentence.
 *
 * The [MeasurementTypeFilterRow] at the top allows the user to select which
 * measurement type drives all insight sections. Only enabled numeric types are shown.
 *
 * Sections with insufficient data show a descriptive placeholder rather than
 * being hidden — the user always knows why a section is unavailable.
 *
 * @param navController      Used for back navigation and action button routing.
 * @param sharedViewModel    Provides [SharedViewModel.insightsFlow] and top bar control.
 * @param bluetoothViewModel Provides the Bluetooth top bar action.
 */
@Composable
fun InsightsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect outside any conditional branch to avoid recomposition flicker.
    val selectedTypeIdsStrings by rememberContextualSelectedTypeIds(
        screenContextName    = INSIGHTS_SCREEN_CONTEXT,
        observeStringSet     = { key, default -> sharedViewModel.observeSetting(key, default) },
        defaultSelectedTypeIds = emptySet(),
    )
    val primaryTypeId: Int? = remember(selectedTypeIdsStrings) {
        selectedTypeIdsStrings.firstOrNull()?.toIntOrNull()
    }

    val insightsState by sharedViewModel.insightsFlow(primaryTypeId)
        .collectAsStateWithLifecycle()
    val allTypes      by sharedViewModel.measurementTypes.collectAsStateWithLifecycle()

    val bluetoothAction      = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    val addMeasurementAction = rememberAddMeasurementActionButton(sharedViewModel, navController)
    val title                = stringResource(R.string.route_title_insights)



    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(title)
        sharedViewModel.setTopBarActions(listOfNotNull(bluetoothAction, addMeasurementAction))
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Type selector — always rendered to avoid Loading↔Success flicker ──
        MeasurementTypeFilterRow(
            allMeasurementTypesProvider = { allTypes },
            selectedTypeIdsFlowProvider = {
                sharedViewModel.observeSetting(
                    "${INSIGHTS_SCREEN_CONTEXT}${SELECTED_TYPES_SUFFIX}",
                    emptySet(),
                )
            },
            onPersistSelectedTypeIds = { ids ->
                scope.launch {
                    sharedViewModel.saveSetting(
                        "${INSIGHTS_SCREEN_CONTEXT}${SELECTED_TYPES_SUFFIX}",
                        ids,
                    )
                }
            },
            filterLogic = { types ->
                types.filter {
                    it.isEnabled &&
                            (it.inputType == InputFieldType.FLOAT ||
                                    it.inputType == InputFieldType.INT)
                }
            },
            defaultSelectionLogic = { types ->
                types.firstOrNull()?.let { listOf(it.id) } ?: emptyList()
            },
            onSelectionChanged  = {},
            singleSelect        = true,
            allowEmptySelection = false,
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
                val primaryType = insight.bodyCompositionShift?.type
                    ?: insight.weekdayPattern?.type
                    ?: insight.seasonalPattern?.type

                LazyColumn(
                    modifier            = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── Section 1: Body Composition Shift ─────────────────────
                    item {
                        val shift = insight.bodyCompositionShift
                        if (shift != null && shift.confidence != InsightConfidence.INSUFFICIENT) {
                            BodyCompositionShiftCard(shift)
                        } else {
                            InsightPlaceholderCard(
                                title   = stringResource(
                                    R.string.insights_section_body_shift,
                                    primaryType?.getDisplayName(context) ?: "",
                                ),
                                message = stringResource(
                                    R.string.insights_placeholder_body_shift,
                                    MeasurementInsightsUseCase.MIN_TOTAL_MEASUREMENTS,
                                ),
                            )
                        }
                    }

                    // ── Section X: Body Composition State ─────────────────────────
                    item {
                        val pattern = insight.bodyCompositionPattern

                        if (pattern != null && pattern.confidence != InsightConfidence.INSUFFICIENT) {
                            BodyCompositionStatePlaneCard(pattern)
                        } else {
                            InsightPlaceholderCard(
                                title = stringResource(R.string.insights_section_body_pattern),
                                message = stringResource(
                                    R.string.insights_placeholder_body_pattern,
                                    MeasurementInsightsUseCase.CORRELATION_MIN_MEASUREMENTS,
                                    MeasurementInsightsUseCase.CORRELATION_WINDOW_DAYS
                                )
                            )
                        }
                    }

                    // ── Section 2: Weekday Pattern ────────────────────────────
                    item {
                        val pattern = insight.weekdayPattern
                        if (pattern != null && pattern.confidence != InsightConfidence.INSUFFICIENT) {
                            WeekdayPatternCard(pattern)
                        } else {
                            InsightPlaceholderCard(
                                title   = stringResource(R.string.insights_section_weekday),
                                message = stringResource(
                                    R.string.insights_placeholder_weekday,
                                    WeekdayPattern.MIN_MEASUREMENTS_PER_DAY,
                                ),
                            )
                        }
                    }

                    // ── Section 3: Seasonal Pattern ───────────────────────────
                    item {
                        val pattern = insight.seasonalPattern
                        if (pattern != null && pattern.confidence != InsightConfidence.INSUFFICIENT) {
                            SeasonalPatternCard(pattern)
                        } else {
                            InsightPlaceholderCard(
                                title   = stringResource(R.string.insights_section_seasonal),
                                message = stringResource(
                                    R.string.insights_placeholder_seasonal,
                                    SeasonalPattern.MIN_YEARS_FOR_PATTERN,
                                ),
                            )
                        }
                    }

                    // ── Section 4: Anomalies ──────────────────────────────────
                    item {
                        AnomaliesCard(
                            anomalies      = insight.anomalies,
                            basedOnCount   = insight.basedOnCount,
                            onAnomalyClick = { anomaly ->
                                val uid = sharedViewModel.selectedUserId.value ?: return@AnomaliesCard
                                navController.navigate(
                                    Routes.measurementDetail(
                                        measurementId = anomaly.measurementId,
                                        userId        = uid,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section cards
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Displays a rich analysis of [BodyCompositionShift] using a vertical list of
 * labeled metric rows, each prefixed by a descriptive icon.
 *
 * Rows (top to bottom):
 * 1. First value → last value with delta
 * 2. All-time minimum
 * 3. All-time maximum
 * 4. Short-term trend (last 30 days)
 * 5. Long-term trend
 * 6. Monthly rate of change
 * 7. Volatility
 * 8. Plateau (only when active)
 * 9. Best calendar month (only when available)
 */
@Composable
private fun BodyCompositionShiftCard(shift: BodyCompositionShift) {
    val locale = Locale.getDefault()

    fun fmt(v: Float) =
        LocaleUtils.formatValueForDisplay(v.toString(), shift.type.unit)
    fun fmtSigned(v: Float) =
        LocaleUtils.formatValueForDisplay(v.toString(), shift.type.unit, includeSign = true)

    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }

    val deltaColor = when {
        shift.deltaAbsolute > 0f -> MaterialTheme.colorScheme.error
        shift.deltaAbsolute < 0f -> MaterialTheme.colorScheme.tertiary
        else                     -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun trendIcon(t: ShiftTrend): ImageVector = when (t) {
        ShiftTrend.UP     -> Icons.Filled.TrendingUp
        ShiftTrend.DOWN   -> Icons.Filled.TrendingDown
        ShiftTrend.STABLE -> Icons.Filled.TrendingFlat
    }

    @Composable
    fun trendColor(t: ShiftTrend): Color = when (t) {
        ShiftTrend.UP     -> MaterialTheme.colorScheme.error
        ShiftTrend.DOWN   -> MaterialTheme.colorScheme.tertiary
        ShiftTrend.STABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun trendLabelRes(t: ShiftTrend) = when (t) {
        ShiftTrend.UP     -> R.string.insights_trend_up
        ShiftTrend.DOWN   -> R.string.insights_trend_down
        ShiftTrend.STABLE -> R.string.insights_trend_stable
    }

    InsightCard(
        title      = stringResource(
            R.string.insights_section_body_shift,
            shift.type.getDisplayName(LocalContext.current),
        ),
        confidence = shift.confidence,
    ) {

        val rowSpacing = 8.dp

        // ── 1. First → Last ───────────────────────────────────────────────
        ShiftMetricRow(
            icon        = Icons.Filled.Timeline,
            iconTint    = deltaColor,
            label       = "${shift.firstMeasuredOn.format(dateFormatter)}  →  ${shift.lastMeasuredOn.format(dateFormatter)}",
            valueText   = "${fmt(shift.firstValue)}  →  ${fmt(shift.lastValue)}",
            subText     = "${fmtSigned(shift.deltaAbsolute)}  (${fmtSigned(shift.deltaPercent)} %)",
            subTextColor = deltaColor,
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 2. Min ────────────────────────────────────────────────────────
        ShiftMetricRow(
            icon      = Icons.Filled.SouthWest,
            iconTint  = MaterialTheme.colorScheme.primary,
            label     = stringResource(R.string.statistics_label_min),
            valueText = fmt(shift.minValue),
            subText   = shift.minValueDate.format(dateFormatter),
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 3. Max ────────────────────────────────────────────────────────
        ShiftMetricRow(
            icon      = Icons.Filled.NorthEast,
            iconTint  = MaterialTheme.colorScheme.error,
            label     = stringResource(R.string.statistics_label_max),
            valueText = fmt(shift.maxValue),
            subText   = shift.maxValueDate.format(dateFormatter),
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 4. Short-term trend ───────────────────────────────────────────
        ShiftMetricRow(
            icon      = trendIcon(shift.shortTermTrend),
            iconTint  = trendColor(shift.shortTermTrend),
            label     = stringResource(R.string.insights_short_term_trend),
            valueText = stringResource(trendLabelRes(shift.shortTermTrend)),
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 5. Long-term trend ────────────────────────────────────────────
        ShiftMetricRow(
            icon      = trendIcon(shift.longTermTrend),
            iconTint  = trendColor(shift.longTermTrend),
            label     = stringResource(R.string.insights_long_term_trend),
            valueText = stringResource(trendLabelRes(shift.longTermTrend)),
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 6. Rate per month ─────────────────────────────────────────────
        ShiftMetricRow(
            icon      = Icons.AutoMirrored.Filled.ShowChart,
            iconTint  = MaterialTheme.colorScheme.primary,
            label     = stringResource(R.string.insights_rate_per_month),
            valueText = fmtSigned(shift.ratePerMonth),
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 7. Volatility ─────────────────────────────────────────────────
        val volatilityColor = when (shift.volatility) {
            Volatility.STABLE   -> MaterialTheme.colorScheme.tertiary
            Volatility.MODERATE -> MaterialTheme.colorScheme.primary
            Volatility.HIGH     -> MaterialTheme.colorScheme.error
        }

        ShiftMetricRow(
            icon      = Icons.AutoMirrored.Filled.MultilineChart,
            iconTint  = volatilityColor,
            label     = stringResource(R.string.insights_volatility),
            valueText = stringResource(
                when (shift.volatility) {
                    Volatility.STABLE   -> R.string.insights_volatility_stable
                    Volatility.MODERATE -> R.string.insights_volatility_moderate
                    Volatility.HIGH     -> R.string.insights_volatility_high
                }
            ),
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 8. Plateau ────────────────────────────────────────────────────
        ShiftMetricRow(
            icon      = Icons.Filled.Remove,
            iconTint  = MaterialTheme.colorScheme.primary,
            label     = stringResource(R.string.insights_plateau_label),
            valueText = stringResource(R.string.insights_plateau_days, shift.plateauDays ?: 0),
            subText   = if (shift.plateauDays != null && shift.plateauStartDate != null)
                "${shift.plateauStartDate.format(dateFormatter)}  –  ${shift.lastMeasuredOn.format(dateFormatter)}"
            else null,
        )

        Spacer(Modifier.height(rowSpacing))

        // ── 9. Best period ────────────────────────────────────────────────
        ShiftMetricRow(
            icon      = Icons.Filled.Star,
            iconTint  = MaterialTheme.colorScheme.primary,
            label     = stringResource(R.string.insights_best_period_label),
            valueText = if (shift.bestPeriodStart != null)
                "${shift.bestPeriodStart.month.getDisplayName(TextStyle.FULL, locale)} ${shift.bestPeriodStart.year}"
            else "-",
            subText   = shift.bestPeriodDelta?.let { fmtSigned(it) },
        )

        Spacer(Modifier.height(rowSpacing))

        // ── Summary ───────────────────────────────────────────────────
        val summary: String? = when {
            shift.plateauDays != null &&
                    shift.plateauDays > 14 &&
                    ChronoUnit.DAYS.between(shift.lastMeasuredOn, LocalDate.now()) <= MeasurementInsightsUseCase.ANOMALY_GAP_RESET_DAYS ->
                stringResource(R.string.insights_summary_plateau, shift.plateauDays)

            shift.shortTermTrend != shift.longTermTrend -> when (shift.shortTermTrend) {
                ShiftTrend.DOWN   -> stringResource(R.string.insights_summary_trend_change_down)
                ShiftTrend.UP     -> stringResource(R.string.insights_summary_trend_change_up)
                ShiftTrend.STABLE -> null
            }

            shift.volatility == Volatility.HIGH ->
                stringResource(R.string.insights_summary_volatility_high)

            shift.volatility == Volatility.STABLE ->
                stringResource(R.string.insights_summary_volatility_stable)

            else -> null
        }

        summary?.let { InsightSummaryText(it) }
    }
}

/**
 * A single labeled metric row used inside [BodyCompositionShiftCard].
 * Shows an icon, a label on the left, and a value (+ optional sub-text) on the right.
 */
@Composable
private fun ShiftMetricRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    valueText: String,
    subText: String? = null,
    subTextColor: Color? = null,
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = iconTint,
            modifier           = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text       = valueText,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            subText?.let {
                Text(
                    text  = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = subTextColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BodyCompositionStatePlaneCard(
    pattern: BodyCompositionPattern,
) {
    val history  = pattern.history
    val locale   = Locale.getDefault()
    // Formatter is cheap to create and not composable-state — no remember needed.
    val shortFmt = DateTimeFormatter.ofPattern("MMM yy", locale)

    // null = "Now" chip active (current pattern shown)
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val transition = remember { Animatable(1f) }
    LaunchedEffect(pattern.windowStartDate) {
        transition.snapTo(0f)
        transition.animateTo(1f, tween(800, easing = EaseOutCubic))
    }

    // Continuous pulse for the active point — restarts whenever selection changes.
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
        val allPoints = history + pattern

        val maxAbsDelta = allPoints.maxOfOrNull { p ->
            max(abs(p.fatDelta), abs(p.muscleDelta))
        }?.takeIf { it > 0.1f } ?: 1.0f

        val edgePadding = 0.2f
        val scale = (0.5f - edgePadding) / maxAbsDelta

        fun map(p: BodyCompositionPattern): Pair<Float, Float> {
            val x = 0.5f + (p.fatDelta * scale)
            val y = 0.5f - (p.muscleDelta * scale)
            return x.coerceIn(0.05f, 0.95f) to y.coerceIn(0.05f, 0.95f)
        }

        history.map { map(it) } to map(pattern)
    }

    val (historyMappedPoints, currentPoint) = mapped
    val (currentX, currentY) = currentPoint

    // Summary text reflects the selected chip's pattern, not always the current one.
    val displayedPattern = selectedIndex
        ?.let { history.getOrNull(it)?.pattern }
        ?: pattern.pattern

    val shownEntry = selectedIndex?.let { history.getOrNull(it) }

    val summaryText = when {
        shownEntry != null && shownEntry.pattern == CompositionPatternType.UNDEFINED ->
            stringResource(
                R.string.insights_pattern_summary_undefined_with_data,
                shownEntry.windowStartDate.format(shortFmt),
            )
        else -> when (displayedPattern) {
            CompositionPatternType.FAT_GAIN -> stringResource(R.string.insights_pattern_summary_fat_gain)
            CompositionPatternType.MUSCLE_AND_FAT_GAIN -> stringResource(R.string.insights_pattern_summary_fat_gain_with_muscle)
            CompositionPatternType.MUSCLE_GAIN       -> stringResource(R.string.insights_pattern_summary_muscle_gain)
            CompositionPatternType.WEIGHT_LOSS_MIXED -> stringResource(R.string.insights_pattern_summary_weight_loss_mixed)
            CompositionPatternType.FAT_LOSS          -> stringResource(R.string.insights_pattern_summary_fat_loss)
            CompositionPatternType.RECOMPOSITION     -> stringResource(R.string.insights_pattern_summary_recomposition)
            CompositionPatternType.STABLE            -> stringResource(R.string.insights_pattern_summary_stable)
            CompositionPatternType.UNDEFINED         -> stringResource(R.string.insights_pattern_summary_undefined)
        }
    }

    InsightCard(
        title      = stringResource(R.string.insights_section_body_pattern),
        confidence = pattern.confidence,
    ) {
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Uniform 6-cell grid — center lines (i==3) are drawn thicker.
                // Equal spacing ensures the center cross aligns with the fine grid.
                for (i in 1..5) {
                    val isAxis      = i == 3
                    val alpha       = if (isAxis) 0.4f else 0.2f
                    val strokeWidth = if (isAxis) 1.5f  else 0.8f
                    val color       = colorScheme.onSurface.copy(alpha = alpha)
                    drawLine(color, Offset(w * i / 6f, 0f), Offset(w * i / 6f, h), strokeWidth)
                    drawLine(color, Offset(0f, h * i / 6f), Offset(w, h * i / 6f), strokeWidth)
                }

                // History points — glowing if selected, dimmed otherwise.
                historyMappedPoints.forEachIndexed { index, (hx, hy) ->
                    val center   = Offset(hx * w, hy * h)
                    val isActive = selectedIndex == index

                    if (isActive) {
                        drawCircle(
                            color  = colorScheme.primary.copy(alpha = 0.12f + 0.13f * (1f - pulseAnim.value)),
                            radius = 22f + pulseAnim.value * 12f,
                            center = center,
                        )
                        drawCircle(colorScheme.primary.copy(alpha = 0.28f), 18f, center)
                        drawCircle(colorScheme.primary, 9f, center)
                        drawCircle(colorScheme.surface.copy(alpha = 0.55f), 3.5f, center)
                    } else {
                        drawCircle(colorScheme.onSurface.copy(alpha = 0.10f), 9f, center)
                        drawCircle(colorScheme.onSurface.copy(alpha = 0.22f), 5f, center)
                    }
                }

                // Current point animates in from the last history position on first render.
                val lastHistoryOffset = historyMappedPoints.lastOrNull()
                    ?.let { (hx, hy) -> Offset(hx * w, hy * h) }
                val target    = Offset(currentX * w, currentY * h)
                val animated  = Offset(
                    lerp((lastHistoryOffset?.x ?: target.x), target.x, transition.value),
                    lerp((lastHistoryOffset?.y ?: target.y), target.y, transition.value),
                )

                if (selectedIndex == null) {
                    // Active — pulsing glow
                    drawCircle(
                        color  = colorScheme.primary.copy(alpha = 0.10f + 0.12f * (1f - pulseAnim.value)),
                        radius = 26f + pulseAnim.value * 14f,
                        center = animated,
                    )
                    drawCircle(colorScheme.primary.copy(alpha = 0.25f), 20f, animated)
                    drawCircle(colorScheme.primary, 10f, animated)
                    drawCircle(colorScheme.surface.copy(alpha = 0.55f), 4f, animated)
                } else {
                    // Inactive — dimmed while a history chip is selected
                    drawCircle(colorScheme.onSurface.copy(alpha = 0.08f), 20f, animated)
                    drawCircle(colorScheme.onSurface.copy(alpha = 0.20f), 10f, animated)
                }
            }

            // Zone corner labels positioned according to the axes
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TrendLabel(
                        label       = stringResource(R.string.insights_label_recomposition),
                        trend       = ShiftTrend.UP,
                        invertColor = true,
                        tooltipText = stringResource(R.string.insights_zone_tooltip_recomposition),
                    )
                    TrendLabel(
                        label       = stringResource(R.string.insights_label_bulking),
                        trend       = ShiftTrend.UP,
                        invertColor = true,
                        tooltipText = stringResource(R.string.insights_zone_tooltip_bulking),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TrendLabel(
                        label       = stringResource(R.string.insights_label_mixed_loss),
                        trend       = ShiftTrend.DOWN,
                        invertColor = true,
                        tooltipText = stringResource(R.string.insights_zone_tooltip_mixed_loss),
                    )
                    TrendLabel(
                        label       = stringResource(R.string.insights_label_fat_gain),
                        trend       = ShiftTrend.UP,
                        invertColor = false,
                        tooltipText = stringResource(R.string.insights_zone_tooltip_fat_gain),
                    )
                }
            }
        }

        // Chips are only rendered when history data is available.
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            // Enable horizontal scrolling for the chips
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()), // Makes the row scrollable
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // 1. "Now" chip is now FIRST (leftmost)
                SuggestionChip(
                    onClick = { selectedIndex = null },
                    label   = {
                        Text(
                            text     = stringResource(R.string.insights_chip_now),
                            maxLines = 1,
                            style    = MaterialTheme.typography.labelSmall,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (selectedIndex == null)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        labelColor = if (selectedIndex == null)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                    ),
                )

                // 2. History chips follow, reversed to show newest first
                // We use reversed() to go from most recent to oldest
                history.asReversed().forEachIndexed { reversedIdx, p ->
                    // Since we reversed the list for display, we need to map the index
                    // back to the original history list to keep selection logic working
                    val originalIdx = history.size - 1 - reversedIdx
                    val isSelected  = selectedIndex == originalIdx

                    SuggestionChip(
                        onClick = { selectedIndex = if (isSelected) null else originalIdx },
                        label   = {
                            Text(
                                text     = p.windowStartDate.format(shortFmt),
                                maxLines = 1,
                                style    = MaterialTheme.typography.labelSmall,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            labelColor = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        InsightSummaryText(summaryText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendLabel(
    label: String,
    trend: ShiftTrend,
    invertColor: Boolean = false,
    tooltipText: String,
) {
    val icon = when (trend) {
        ShiftTrend.UP     -> Icons.AutoMirrored.Filled.TrendingUp
        ShiftTrend.DOWN   -> Icons.AutoMirrored.Filled.TrendingDown
        ShiftTrend.STABLE -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    val color = when (trend) {
        ShiftTrend.UP     -> if (invertColor) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.error
        ShiftTrend.DOWN   -> if (invertColor) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.tertiary
        ShiftTrend.STABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope        = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    text  = tooltipText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        state = tooltipState,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.clickable { scope.launch { tooltipState.show() } },
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = color,
                modifier           = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

/**
 * Displays the average deviation per weekday as animated horizontal bars.
 * Bar widths animate from zero with a staggered delay per row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekdayPatternCard(pattern: WeekdayPattern) {
    val locale = Locale.getDefault()

    var animationPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(pattern) { animationPlayed = true }

    InsightCard(
        title      = stringResource(R.string.insights_section_weekday),
        confidence = pattern.confidence,
    ) {
        Spacer(Modifier.height(8.dp))

        val maxAbs      = pattern.deviationByDay.values
            .maxOfOrNull { kotlin.math.abs(it) }
            ?.takeIf { it > 0f } ?: 1f

        DayOfWeek.entries.forEachIndexed { index, day ->
            val deviation   = pattern.deviationByDay[day] ?: 0f
            val count       = pattern.measurementCountByDay[day] ?: 0
            val targetWidth = (kotlin.math.abs(deviation) / maxAbs).coerceIn(0f, 1f)

            val animatedWidth by animateFloatAsState(
                targetValue   = if (animationPlayed) targetWidth else 0f,
                animationSpec = tween(
                    durationMillis = ANIM_DURATION_MS,
                    delayMillis    = index * 60,
                    easing         = EaseOutCubic,
                ),
                label = "weekday_bar_$index",
            )

            val barColor = when {
                deviation > 0f -> MaterialTheme.colorScheme.error.copy(
                    alpha = 0.6f + 0.4f * animatedWidth,
                )
                deviation < 0f -> MaterialTheme.colorScheme.tertiary.copy(
                    alpha = 0.6f + 0.4f * animatedWidth,
                )
                else           -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            }

            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = day.getDisplayName(TextStyle.SHORT, locale),
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(36.dp),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp),
                ) {
                    val tooltipState = rememberTooltipState(isPersistent = true)
                    val tooltipScope = rememberCoroutineScope()

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(
                                    text  = buildString {
                                        appendLine(day.getDisplayName(TextStyle.FULL, locale))
                                        appendLine(
                                            "Ø ${LocaleUtils.formatValueForDisplay(
                                                (pattern.overallMean + deviation).toString(),
                                                pattern.type.unit,
                                            )}"
                                        )
                                        append(
                                            LocaleUtils.formatValueForDisplay(
                                                deviation.toString(),
                                                pattern.type.unit,
                                                includeSign = true,
                                            )
                                        )
                                        append(" ($count)")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        state    = tooltipState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedWidth.coerceAtLeast(0.02f))
                            .clickable { tooltipScope.launch { tooltipState.show() } },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRoundRect(color = barColor, cornerRadius = CornerRadius(4f))
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))
                Text(
                    text  = LocaleUtils.formatValueForDisplay(
                        deviation.toString(), pattern.type.unit, includeSign = true,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "($count)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        InsightSummaryText(
            when (pattern.confidence) {
                InsightConfidence.HIGH -> {
                    val heavy = pattern.heaviestDay?.getDisplayName(TextStyle.FULL, locale) ?: "-"
                    val light = pattern.lightestDay?.getDisplayName(TextStyle.FULL, locale) ?: "-"
                    stringResource(R.string.insights_weekday_summary_high, heavy, light)
                }
                else -> stringResource(R.string.insights_weekday_summary_low)
            }
        )
    }
}

/**
 * Displays a heatmap grid of average values per month and year.
 * Cell colors animate from transparent with staggered delay per cell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonalPatternCard(pattern: SeasonalPattern) {
    val locale = Locale.getDefault()

    var animationPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(pattern) { animationPlayed = true }

    InsightCard(
        title      = stringResource(R.string.insights_section_seasonal),
        confidence = pattern.confidence,
    ) {
        Spacer(Modifier.height(8.dp))

        val allValues    = pattern.averageValueByMonthAndYear.values.flatMap { it.values }
        val minVal       = allValues.minOrNull() ?: 0f
        val maxVal       = allValues.maxOrNull() ?: 1f
        val range        = (maxVal - minVal).takeIf { it > 0f } ?: 1f
        val sortedYears  = pattern.averageValueByMonthAndYear.keys.sorted()
        val primaryColor = MaterialTheme.colorScheme.primary
        val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
        val noDataLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

        // Month header row
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(36.dp))
            Month.entries.forEach { month ->
                Text(
                    text      = month.getDisplayName(TextStyle.NARROW, locale),
                    style     = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.weight(1f),
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        sortedYears.forEachIndexed { yearIndex, year ->
            val monthMap = pattern.averageValueByMonthAndYear[year] ?: emptyMap()
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = year.toString(),
                    style    = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(36.dp),
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Month.entries.forEachIndexed { monthIndex, month ->
                    val avg       = monthMap[month]
                    val intensity = avg?.let { ((it - minVal) / range).coerceIn(0f, 1f) } ?: -1f
                    val target    = when {
                        intensity < 0f -> surfaceColor.copy(alpha = 0.3f)
                        else           -> primaryColor.copy(alpha = 0.2f + 0.8f * intensity)
                    }
                    val cellColor by animateColorAsState(
                        targetValue   = if (animationPlayed) target else surfaceColor.copy(alpha = 0f),
                        animationSpec = tween(
                            durationMillis = ANIM_DURATION_MS,
                            delayMillis    = (yearIndex * 12 + monthIndex) * 20,
                            easing         = EaseOutCubic,
                        ),
                        label = "heatmap_${year}_${month.value}",
                    )

                    Box(
                        modifier         = Modifier
                            .weight(1f)
                            .height(18.dp)
                            .padding(1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRoundRect(color = cellColor, cornerRadius = CornerRadius(3f))
                            if (avg == null) {
                                drawLine(
                                    color       = noDataLineColor,
                                    start       = Offset(0f, size.height),
                                    end         = Offset(size.width, 0f),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                        }

                        if (avg != null) {
                            val tooltipState = rememberTooltipState(isPersistent = true)
                            val tooltipScope = rememberCoroutineScope()

                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip {
                                        Text(
                                            text  = "${month.getDisplayName(TextStyle.SHORT, locale)} $year\n${
                                                LocaleUtils.formatValueForDisplay(
                                                    avg.toString(),
                                                    pattern.type.unit,
                                                )
                                            }",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                },
                                state    = tooltipState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { tooltipScope.launch { tooltipState.show() } },
                            ) {}
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        InsightSummaryText(
            when (pattern.confidence) {
                InsightConfidence.HIGH -> {
                    val high = pattern.highestMonth?.getDisplayName(TextStyle.FULL, locale) ?: "-"
                    val low  = pattern.lowestMonth?.getDisplayName(TextStyle.FULL, locale)  ?: "-"
                    stringResource(R.string.insights_seasonal_summary_high, high, low)
                }
                else -> stringResource(R.string.insights_seasonal_summary_low)
            }
        )
    }
}

/**
 * Shows detected [MeasurementAnomaly]s, initially limited to [ANOMALIES_INITIAL_COUNT].
 * Shows a placeholder when [basedOnCount] is below the minimum required for detection.
 */
@Composable
private fun AnomaliesCard(
    anomalies: List<MeasurementAnomaly>,
    basedOnCount: Int,
    onAnomalyClick: (MeasurementAnomaly) -> Unit
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = remember(anomalies, showAll) {
        if (showAll) anomalies else anomalies.take(ANOMALIES_INITIAL_COUNT)
    }

    InsightCard(
        title      = stringResource(R.string.insights_section_anomalies),
        confidence = null,
    ) {
        when {
            basedOnCount < MeasurementInsightsUseCase.ANOMALY_WINDOW_SIZE -> {
                Spacer(Modifier.height(8.dp))
                InsightSummaryText(
                    stringResource(
                        R.string.insights_placeholder_anomalies,
                        MeasurementInsightsUseCase.ANOMALY_WINDOW_SIZE,
                    )
                )
            }
            anomalies.isEmpty() -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = stringResource(R.string.insights_anomalies_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                visible.forEachIndexed { index, anomaly ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    AnomalyRow(
                        anomaly,
                        onAnomalyClick,
                    )
                }
                if (anomalies.size > ANOMALIES_INITIAL_COUNT) {
                    TextButton(
                        onClick  = { showAll = !showAll },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector        = if (showAll) Icons.Filled.ExpandLess
                            else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier           = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Single anomaly row showing date, deviation, and optional user comment. */
@Composable
private fun AnomalyRow(
    anomaly: MeasurementAnomaly,
    onAnomalyClick: (MeasurementAnomaly) -> Unit
) {
    val locale = Locale.getDefault()
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onAnomalyClick(anomaly) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.error,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text       = anomaly.date.format(dateFormatter),
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = LocaleUtils.formatValueForDisplay(
                    anomaly.value.toString(),
                    anomaly.type.unit
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text  = stringResource(
                R.string.insights_anomaly_deviation,
                LocaleUtils.formatValueForDisplay(
                    anomaly.deviation.toString(), anomaly.type.unit, includeSign = true,
                ),
                LocaleUtils.formatValueForDisplay(
                    anomaly.expectedValue.toString(), anomaly.type.unit,
                ),
            ),
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp),
        )
        anomaly.comment?.let { comment ->
            Spacer(Modifier.height(2.dp))
            Text(
                text      = stringResource(R.string.insights_anomaly_comment, comment),
                style     = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier  = Modifier.padding(start = 22.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared layout components
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Base card layout shared by all insight sections.
 * A [InsightConfidence.LOW] chip is shown when data is limited.
 */
@Composable
private fun InsightCard(
    title: String,
    confidence: InsightConfidence?,
    content: @Composable () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f),
                )
                if (confidence == InsightConfidence.LOW) {
                    Spacer(Modifier.width(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label   = {
                            Text(
                                text  = stringResource(R.string.insights_confidence_low),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            labelColor     = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }
            content()
        }
    }
}

/**
 * Placeholder card shown when a section has [InsightConfidence.INSUFFICIENT] data.
 * Always visible so the user understands why the section is not yet populated.
 */
@Composable
private fun InsightPlaceholderCard(
    title: String,
    message: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Italic summary sentence at the bottom of each insight card. */
@Composable
private fun InsightSummaryText(text: String) {
    Text(
        text      = text,
        style     = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color     = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}