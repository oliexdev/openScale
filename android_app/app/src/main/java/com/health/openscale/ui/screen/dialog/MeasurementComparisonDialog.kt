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
package com.health.openscale.ui.screen.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.health.openscale.R
import com.health.openscale.core.data.Trend
import com.health.openscale.core.model.MeasurementComparison
import com.health.openscale.core.model.MeasurementComparisonItem
import com.health.openscale.core.model.MeasurementWithValues
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Material 3 dialog displaying a side-by-side comparison of two measurements.
 *
 * Shows:
 * - Header with both measurement dates, elapsed-time chip, and swap direction button.
 * - Scrollable list of all compared metrics: baseline, comparison, delta badge, and weekly rate.
 *
 * @param comparison The [MeasurementComparison] result.
 * @param onDismiss Called when the dialog is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementComparisonDialog(
    comparison: MeasurementComparison,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    }

    // Allow the user to swap base <-> target direction
    var isSwapped by remember { mutableStateOf(false) }

    val displayBase: MeasurementWithValues
    val displayTarget: MeasurementWithValues
    val displayItems: List<MeasurementComparisonItem>

    if (!isSwapped) {
        displayBase = comparison.baseMeasurement
        displayTarget = comparison.targetMeasurement
        displayItems = comparison.items
    } else {
        displayBase = comparison.targetMeasurement
        displayTarget = comparison.baseMeasurement
        displayItems = comparison.items.map { item ->
            val negDiff = item.difference?.let { -it }
            val negPct  = item.percentChange?.let { -it }
            val negRate = item.ratePerWeek?.let { -it }
            val swappedTrend = when (item.trend) {
                Trend.UP   -> Trend.DOWN
                Trend.DOWN -> Trend.UP
                else       -> item.trend
            }
            item.copy(
                baseValue            = item.targetValue,
                targetValue          = item.baseValue,
                baseFormatted        = item.targetFormatted,
                targetFormatted      = item.baseFormatted,
                difference           = negDiff,
                differenceFormatted  = negDiff?.let { d ->
                    val sign = if (d > 0.001f) "+" else if (d < -0.001f) "\u2212" else ""
                    val base2 = item.differenceFormatted?.trimStart('+', '\u2212', '-') ?: ""
                    "$sign$base2"
                },
                percentChange        = negPct,
                percentChangeFormatted = negPct?.let { p ->
                    val sign = if (p > 0.001f) "+" else if (p < -0.001f) "\u2212" else ""
                    val base2 = item.percentChangeFormatted?.trimStart('+', '\u2212', '-') ?: ""
                    "$sign$base2"
                },
                ratePerWeek          = negRate,
                ratePerWeekFormatted = negRate?.let { r ->
                    val sign = if (r > 0.001f) "+" else if (r < -0.001f) "\u2212" else ""
                    val base2 = item.ratePerWeekFormatted?.trimStart('+', '\u2212', '-') ?: ""
                    "$sign$base2"
                },
                trend = swappedTrend,
            )
        }
    }

    val baseDate   = dateFormat.format(Date(displayBase.measurement.timestamp))
    val targetDate = dateFormat.format(Date(displayTarget.measurement.timestamp))

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier      = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape         = RoundedCornerShape(28.dp),
            color         = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {

                // ── Header ─────────────────────────────────────────────────
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector     = Icons.Filled.CompareArrows,
                        contentDescription = null,
                        tint            = MaterialTheme.colorScheme.primary,
                        modifier        = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text     = stringResource(R.string.title_measurement_comparison),
                        style    = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { isSwapped = !isSwapped }) {
                        Icon(
                            imageVector     = Icons.Filled.SwapHoriz,
                            contentDescription = stringResource(R.string.comparison_swap),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector     = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cancel_button),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Date row + elapsed chip ─────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = stringResource(R.string.comparison_baseline),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text       = baseDate,
                                style      = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines   = 2,
                                overflow   = TextOverflow.Ellipsis,
                            )
                        }
                        Column(
                            modifier              = Modifier.weight(1f),
                            horizontalAlignment   = Alignment.End,
                        ) {
                            Text(
                                text      = stringResource(R.string.comparison_target),
                                style     = MaterialTheme.typography.labelSmall,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                            Text(
                                text       = targetDate,
                                style      = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                textAlign  = TextAlign.End,
                                maxLines   = 2,
                                overflow   = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val elapsedLabel = if (comparison.daysBetween == 0L) {
                        stringResource(R.string.comparison_same_day)
                    } else {
                        stringResource(R.string.comparison_days_apart, comparison.daysBetween)
                    }
                    AssistChip(
                        onClick = {},
                        label   = { Text(elapsedLabel, style = MaterialTheme.typography.labelMedium) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))

                // ── Empty state ─────────────────────────────────────────────
                if (displayItems.isEmpty()) {
                    Box(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment  = Alignment.Center,
                    ) {
                        Text(
                            text  = stringResource(R.string.comparison_no_metrics),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // ── Column headers ──────────────────────────────────────
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf(
                            "Metric" to Modifier.weight(1.4f),
                            stringResource(R.string.comparison_baseline) to Modifier.weight(1f),
                            stringResource(R.string.comparison_target)   to Modifier.weight(1f),
                            "\u0394 Change" to Modifier.weight(1.2f),
                        ).forEach { (label, mod) ->
                            Text(
                                text      = label,
                                style     = MaterialTheme.typography.labelSmall,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier  = mod,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // ── Metric rows ─────────────────────────────────────────
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        items(displayItems, key = { it.type.id }) { item ->
                            ComparisonMetricRow(item = item)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Footer close button ─────────────────────────────────────
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonMetricRow(item: MeasurementComparisonItem) {
    val context = LocalContext.current
    val trendColor: Color = when (item.trend) {
        Trend.UP   -> MaterialTheme.colorScheme.tertiary
        Trend.DOWN -> MaterialTheme.colorScheme.error
        else       -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trendIcon = when (item.trend) {
        Trend.UP   -> Icons.Filled.TrendingUp
        Trend.DOWN -> Icons.Filled.TrendingDown
        else       -> Icons.Filled.TrendingFlat
    }

    // Display name using the type's localization-aware display name
    val displayName = remember(item.type.id) {
        item.type.getDisplayName(context)
    }

    Column {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Metric name
            Text(
                text     = displayName,
                style    = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1.4f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Base value
            Text(
                text      = item.baseFormatted,
                style     = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier  = Modifier.weight(1f),
                maxLines  = 1,
            )
            // Target value
            Text(
                text      = item.targetFormatted,
                style     = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier  = Modifier.weight(1f),
                maxLines  = 1,
            )
            // Delta badge
            Box(
                modifier         = Modifier.weight(1.2f),
                contentAlignment = Alignment.Center,
            ) {
                if (item.hasDifference && item.differenceFormatted != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector     = trendIcon,
                                contentDescription = null,
                                tint            = trendColor,
                                modifier        = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text       = item.differenceFormatted,
                                style      = MaterialTheme.typography.labelSmall,
                                color      = trendColor,
                                fontWeight = FontWeight.SemiBold,
                                maxLines   = 1,
                            )
                        }
                        if (item.ratePerWeekFormatted != null) {
                            Text(
                                text  = item.ratePerWeekFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = trendColor.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    Text(
                        text  = "\u2013",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier  = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color     = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
