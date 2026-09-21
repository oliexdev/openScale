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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.screen.statistics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.openscale.R
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.model.AggregatedMeasurement
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.shared.SharedViewModel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Which reading of the change the Δ column shows. Only ever one at a time — see the class doc. */
private enum class DeltaMode(val labelResId: Int, val columnResId: Int) {
    ABSOLUTE(R.string.comparison_mode_absolute, R.string.comparison_column_delta),
    PERCENT(R.string.comparison_mode_percent, R.string.comparison_column_delta_percent),
    PER_WEEK(R.string.comparison_mode_per_week, R.string.comparison_column_delta_per_week),
}

/** Placeholder for a value the comparison does not have. */
private const val NO_VALUE = "—"

/**
 * Side-by-side comparison of two measurements, laid out as a table: one column per side plus the
 * change, so a reader can scan a single side down the list as easily as one metric across it.
 *
 * Nothing here is computed twice. The two entries are loaded through
 * [SharedViewModel.drillDownFlow], the same cached pipeline the table drill-down uses, and every
 * metric goes through [calculateStatisticsForType] — in a window holding exactly the two selected
 * entries, its first/last values *are* the two sides of the comparison.
 *
 * Two layout decisions carry the screen:
 *
 *  - **The unit sits in the row, the numbers do not.** With `kg` next to the metric's name the
 *    three columns hold bare, decimal-aligned numbers, which is what makes them comparable at a
 *    glance both down and across.
 *  - **Relative change and weekly rate are modes, not extra columns.** Shown together they invite
 *    a misreading — for a metric measured in percent, "−5.9 %" (relative) and "−0.8 %/week"
 *    (percentage points) look alike and mean different things. The segmented control shows one at
 *    a time in the same column.
 *
 * The change is computed from the *displayed* values, not the stored ones, because a table has to
 * add up: a waist of 88.74 cm and one of 88.66 cm both read "88.7", and a Δ of "−0.1" next to them
 * would contradict the two columns it belongs to.
 *
 * With an aggregation [level] the two sides are the period averages the table showed instead of
 * single weigh-ins, which is the more telling comparison: a month's mean has the day-to-day noise
 * already averaged out.
 *
 * @param startMillis Start of the older entry (inclusive) — its timestamp, or its period start.
 * @param endMillis End of the newer entry (inclusive) — its timestamp, or its period end.
 * @param level Aggregation the two entries were picked in.
 */
@Composable
fun MeasurementComparisonScreen(
    sharedViewModel: SharedViewModel,
    startMillis: Long,
    endMillis: Long,
    level: AggregationLevel = AggregationLevel.NONE,
) {
    val allTypes by sharedViewModel.measurementTypes.collectAsState()
    val uiState by sharedViewModel
        .drillDownFlow(startMillis, endMillis, level)
        .collectAsStateWithLifecycle(initialValue = SharedViewModel.UiState.Loading)

    var deltaMode by rememberSaveable { mutableStateOf(DeltaMode.ABSOLUTE) }

    val title = stringResource(R.string.route_title_measurement_comparison)
    LaunchedEffect(title) {
        sharedViewModel.setTopBarTitle(title)
        sharedViewModel.setTopBarActions(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is SharedViewModel.UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is SharedViewModel.UiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.message ?: stringResource(R.string.error_loading_data),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            is SharedViewModel.UiState.Success -> {
                val window: List<AggregatedMeasurement> = remember(state.data) {
                    state.data.sortedBy { it.enriched.measurementWithValues.measurement.timestamp }
                }
                val baseEntry = window.firstOrNull()
                val targetEntry = window.lastOrNull()

                if (baseEntry == null || targetEntry == null || baseEntry === targetEntry) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_data_available), textAlign = TextAlign.Center)
                    }
                    return@Column
                }

                val base = baseEntry.enriched
                val target = targetEntry.enriched
                val split = remember(allTypes, base, target) { splitTypes(allTypes, base, target) }

                // One span for the whole screen: the header states it and the weekly rate divides
                // by it, and the two must not be able to disagree.
                val baseTs = base.measurementWithValues.measurement.timestamp
                val targetTs = target.measurementWithValues.measurement.timestamp
                val daysBetween = remember(baseTs, targetTs) {
                    val zone = ZoneId.systemDefault()
                    ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(baseTs).atZone(zone).toLocalDate(),
                        Instant.ofEpochMilli(targetTs).atZone(zone).toLocalDate(),
                    )
                }

                DeltaModeSelector(selected = deltaMode, onSelect = { deltaMode = it })
                ComparisonTableHeader(
                    base = baseEntry,
                    target = targetEntry,
                    level = level,
                    deltaMode = deltaMode,
                    daysBetween = daysBetween,
                )

                if (split.inBoth.isEmpty() && split.inOneOnly.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.comparison_no_common_metrics),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    return@Column
                }

                LazyColumn(Modifier.fillMaxSize()) {
                    items(split.inBoth, key = { it.id }) { type ->
                        val stats = remember(base, target, type) {
                            calculateStatisticsForType(listOf(base, target), type)
                        }
                        ComparisonTableRow(
                            measurementType = type,
                            baseValue = stats.firstValue,
                            targetValue = stats.lastValue,
                            delta = displayedChange(stats, type.unit, deltaMode, daysBetween),
                            deltaUnit = if (deltaMode == DeltaMode.PERCENT) UnitType.PERCENT else type.unit,
                            baseIsMean = baseEntry.isAggregated,
                            targetIsMean = targetEntry.isAggregated,
                        )
                        HorizontalDivider()
                    }

                    // Metrics only one of the two entries recorded. Dropping them silently would
                    // leave the reader wondering where a metric went, so they are listed, dimmed,
                    // with a dash on the missing side.
                    if (split.inOneOnly.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.comparison_only_in_one),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                            )
                        }
                        items(split.inOneOnly, key = { it.type.id }) { entry ->
                            val stats = remember(entry) {
                                calculateStatisticsForType(listOf(entry.present), entry.type)
                            }
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                ComparisonTableRow(
                                    measurementType = entry.type,
                                    baseValue = if (entry.onBase) stats.firstValue else null,
                                    targetValue = if (entry.onBase) null else stats.firstValue,
                                    delta = null,
                                    deltaUnit = entry.type.unit,
                                    baseIsMean = baseEntry.isAggregated,
                                    targetIsMean = targetEntry.isAggregated,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The change the Δ column currently stands for, derived from the two values *as displayed*.
 *
 * Rounding first is what keeps the row self-consistent: the Δ a reader can verify by subtracting
 * the two columns is the Δ that is printed.
 */
private fun displayedChange(
    stats: MeasurementStatistics,
    unit: UnitType,
    mode: DeltaMode,
    daysBetween: Long,
): Float? {
    val base = stats.firstValue?.let { roundForDisplay(it, unit) } ?: return null
    val target = stats.lastValue?.let { roundForDisplay(it, unit) } ?: return null
    val difference = target - base

    return when (mode) {
        DeltaMode.ABSOLUTE -> difference
        DeltaMode.PERCENT -> if (abs(base) > 1e-5f) difference / abs(base) * 100f else null
        // Calendar days, so weighing in again twenty hours later already counts as one day; below
        // two days a weekly rate says more about the rounding than about the body.
        DeltaMode.PER_WEEK -> daysBetween
            .takeIf { it >= MIN_DAYS_FOR_WEEKLY_RATE }
            ?.let { days -> difference / (days / 7f) }
    }
}

/** The value as the table prints it, so arithmetic on it matches what the reader sees. */
private fun roundForDisplay(value: Float, unit: UnitType): Float {
    val factor = 10.0.pow(LocaleUtils.maxFractionFor(unit)).toFloat()
    return (value * factor).roundToInt() / factor
}

/** Below this many days apart, a weekly rate is mostly noise. */
private const val MIN_DAYS_FOR_WEEKLY_RATE = 2L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeltaModeSelector(selected: DeltaMode, onSelect: (DeltaMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        DeltaMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, DeltaMode.entries.size),
            ) {
                Text(stringResource(mode.labelResId), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The column headers: which entry each column holds, how far apart they are, and what the Δ
 * column is currently showing.
 */
@Composable
private fun ComparisonTableHeader(
    base: AggregatedMeasurement,
    target: AggregatedMeasurement,
    level: AggregationLevel,
    deltaMode: DeltaMode,
    daysBetween: Long,
) {
    // Read observably, so the header recomposes when the in-app language changes — same source
    // the table screen's drill-down title uses.
    val locale = ComposeLocale.current.platformLocale

    // Day, month and a two-digit year, in the order and punctuation the locale writes them
    // ("8/25/26", "25.08.26"). One line, so the column header stays one line.
    val dateFormat = remember(locale) {
        SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "yyMd"),
            locale,
        )
    }
    val calendarWeekAbbrev = stringResource(R.string.calendar_week_abbrev)
    val weekFields = remember { LocaleUtils.systemWeekFields()  }

    // An aggregated column stands for a whole period, so it is labelled like the table row it
    // came from; a raw one is simply its date. Both carry their year: a week label is the one
    // that can outgrow a column header, and it wraps to a second line when it does.
    fun label(entry: AggregatedMeasurement): String {
        val ts = entry.enriched.measurementWithValues.measurement.timestamp
        return if (level == AggregationLevel.NONE) {
            dateFormat.format(Date(ts))
        } else {
            level.periodLabel(
                timestamp = ts,
                calendarWeekAbbrev = calendarWeekAbbrev,
                locale = locale,
                weekFields = weekFields,
                short = true,
            )
        }
    }

    /**
     * The column header: the period, with the number of weigh-ins behind it in brackets the way
     * the table screen labels an aggregated row. A mean over twenty readings and one over two are
     * not equally trustworthy, and this is where that shows.
     */
    fun header(entry: AggregatedMeasurement): String {
        val text = label(entry)
        return if (entry.aggregatedFromCount > 1) "$text (${entry.aggregatedFromCount})" else text
    }

    Column {
        Row(
            // Top-aligned: the labels share a baseline while the sample counts hang below them,
            // and a period label that wraps grows downwards instead of shoving its neighbours.
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The span goes where the name column is empty anyway, which keeps the header to one
            // line and puts it next to the two dates it describes.
            HeaderLabel(
                text = if (daysBetween == 0L) {
                    stringResource(R.string.comparison_same_day)
                } else {
                    stringResource(R.string.comparison_days_apart, daysBetween)
                },
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .width(ICON_COLUMN_WIDTH)
                    .weight(NAME_COLUMN_WEIGHT + ICON_COLUMN_WEIGHT_SHARE),
            )
            HeaderLabel(
                text = header(base),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
            )
            HeaderLabel(
                text = header(target),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
            )
            HeaderLabel(
                text = stringResource(deltaMode.columnResId),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
            )
        }
        HorizontalDivider()
    }
}

/**
 * Column headers are styled like the table screen's — down to wrapping onto a second line rather
 * than being cut short, which is what a long period label ("2026 – CW 35") needs.
 */
@Composable
private fun HeaderLabel(text: String, textAlign: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * One metric: identity on the left, then the two readings and the change as bare numbers, so the
 * columns line up down the whole list. The unit is named once, next to the metric.
 */
@Composable
private fun ComparisonTableRow(
    measurementType: MeasurementType,
    baseValue: Float?,
    targetValue: Float?,
    delta: Float?,
    deltaUnit: UnitType,
    baseIsMean: Boolean,
    targetIsMean: Boolean,
) {
    val unit = measurementType.unit

    // fixedDecimals keeps the trailing zeros, which is what lets the column line up on the
    // decimal point: 80.61 above 78.60, not above 78.6.
    fun cell(value: Float?, unitOfValue: UnitType, signed: Boolean): String =
        value?.let {
            LocaleUtils.formatValueWithoutUnit(
                value = it.toString(),
                unit = unitOfValue,
                includeSign = signed,
                fixedDecimals = true,
            )
        } ?: NO_VALUE

    // A change that rounds away at display precision is no change: without this a −0.004 kg
    // difference prints as "−0" in the Δ column.
    val deltaText = when {
        delta == null -> NO_VALUE
        displaysAsZero(delta, deltaUnit) -> cell(0f, deltaUnit, signed = false)
        else -> cell(delta, deltaUnit, signed = true)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundMeasurementIcon(
            icon = measurementType.icon.resource,
            backgroundTint = Color(measurementType.color),
            size = 16.dp,
            modifier = Modifier.width(ICON_COLUMN_WIDTH),
        )
        // The unit belongs to all three numeric columns at once, so it is named here, in brackets,
        // the way a table names the unit of a column. Name and unit are one text run rather than
        // two composables: side by side they break apart at large font scales, together the text
        // layout wraps them as a unit.
        //
        // ST is the exception: it renders as "12 st 7 lb", carrying its units in the value itself.
        val showsUnitInValue = unit == UnitType.ST || unit == UnitType.NONE
        val unitStyle = SpanStyle(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
        )
        val name = measurementType.getDisplayName(LocalContext.current)
        Text(
            text = buildAnnotatedString {
                append(name)
                if (!showsUnitInValue) {
                    // An ordinary space on purpose: glued to the name with a non-breaking one the
                    // pair becomes a single over-long word, and the layout then breaks inside the
                    // bracket ("(k" / "g)"). Bracketed, the unit reads fine on a line of its own.
                    withStyle(unitStyle) { append(" (${unit.displayName})") }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(NAME_COLUMN_WEIGHT).padding(start = 8.dp),
        )
        ValueCell(
            text = cell(baseValue, unit, signed = false),
            isMean = baseIsMean && baseValue != null,
            modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
        )
        ValueCell(
            text = cell(targetValue, unit, signed = false),
            isMean = targetIsMean && targetValue != null,
            modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
        )
        Text(
            text = deltaText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
        )
    }
}

/**
 * One value. An aggregated column holds period means, and each of them says so: the Δ beside them
 * is a difference of two means, not a mean itself, so the mark has to sit on the values.
 */
@Composable
private fun ValueCell(text: String, isMean: Boolean, modifier: Modifier = Modifier) {
    val markStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
    )
    Text(
        text = buildAnnotatedString {
            if (isMean) withStyle(markStyle) { append("Ø ") }
            append(text)
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}

// Shared column geometry, so the header row and the metric rows cannot drift apart.
private val ICON_COLUMN_WIDTH = 34.dp
private const val NAME_COLUMN_WEIGHT = 1.3f
private const val VALUE_COLUMN_WEIGHT = 1f

/** The icon column is fixed-width in the rows; the header has no icon and takes its share as weight. */
private const val ICON_COLUMN_WEIGHT_SHARE = 0.45f

/**
 * True when a value rounds away to nothing at the precision it is displayed with.
 *
 * That precision lives in [LocaleUtils] alone, so the check asks the formatter instead of
 * repeating its per-unit rounding table here.
 */
private fun displaysAsZero(value: Float, unit: UnitType): Boolean =
    LocaleUtils.formatValueWithoutUnit(value.toString(), unit) ==
        LocaleUtils.formatValueWithoutUnit("0", unit)

/** A metric only one of the two entries recorded, plus the side that has it. */
private data class OneSidedType(
    val type: MeasurementType,
    val present: EnrichedMeasurement,
    val onBase: Boolean,
)

private data class TypeSplit(
    val inBoth: List<MeasurementType>,
    val inOneOnly: List<OneSidedType>,
)

/**
 * Sorts the comparable metrics out from the ones only one side recorded. The distinction matters:
 * [calculateStatisticsForType] would report first == last for a one-sided metric, i.e. a change of
 * zero that never happened.
 */
private fun splitTypes(
    allTypes: List<MeasurementType>,
    base: EnrichedMeasurement,
    target: EnrichedMeasurement,
): TypeSplit {
    val onBase = base.measurementWithValues.values.map { it.type.id }.toSet()
    val onTarget = target.measurementWithValues.values.map { it.type.id }.toSet()

    val candidates = allTypes
        .filter {
            it.isEnabled &&
                (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) &&
                (it.id in onBase || it.id in onTarget)
        }
        .sortedWith(
            compareBy<MeasurementType> { it.key != MeasurementType.WEIGHT }
                .thenBy { it.displayOrder }
                .thenBy { it.id }
        )

    return TypeSplit(
        inBoth = candidates.filter { it.id in onBase && it.id in onTarget },
        inOneOnly = candidates
            .filter { (it.id in onBase) != (it.id in onTarget) }
            .map { type ->
                val isOnBase = type.id in onBase
                OneSidedType(type, if (isOnBase) base else target, isOnBase)
            },
    )
}
