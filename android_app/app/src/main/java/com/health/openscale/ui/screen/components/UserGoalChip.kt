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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.usecase.GoalProgress
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.theme.success
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One tracked goal in the overview's goal row.
 *
 * Leads with what has been achieved rather than what is missing: the card exists to keep someone
 * going, and the current value is already the headline of the list right below it. The measurement
 * type is carried by its icon alone — the same coloured badge the rest of the app identifies it by.
 *
 * Without [progress] — a goal on a type that is switched off, or one not weighed yet — the chip
 * falls back to reporting what was set: the target and the dates framing it.
 */
@Composable
fun UserGoalChip(
    userGoal: UserGoals,
    measurementType: MeasurementType,
    modifier: Modifier = Modifier,
    progress: GoalProgress? = null,
    onClick: () -> Unit
) {
    val typeColor = Color(measurementType.color)

    fun format(value: Float, signed: Boolean = false) =
        LocaleUtils.formatValueForDisplay(
            value = value.toString(),
            unit = measurementType.unit,
            includeSign = signed,
        )

    Card(
        modifier = modifier
            .width(CHIP_WIDTH)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundMeasurementIcon(
                    icon = measurementType.icon.resource,
                    backgroundTint = typeColor,
                    size = ICON_SIZE,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (progress != null) {
                            format(progress.delta, signed = true)
                        } else {
                            format(userGoal.goalValue)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = HERO_FONT_SIZE,
                            lineHeight = HERO_LINE_HEIGHT,
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (progress == null) {
                        // Nothing measured yet, so the chip reports what was set instead.
                        Caption(
                            text = stringResource(
                                R.string.progress_since_date,
                                LocaleUtils.formatCompactDate(
                                    LocaleUtils.toLocalDate(userGoal.startDate),
                                ),
                            ),
                            modifier = Modifier.padding(start = CAPTION_INDENT),
                        )
                        Caption(
                            text = userGoal.goalTargetDate?.let {
                                stringResource(
                                    R.string.progress_until_date,
                                    LocaleUtils.formatCompactDate(LocaleUtils.toLocalDate(it)),
                                )
                            } ?: "",
                            modifier = Modifier.padding(start = CAPTION_INDENT),
                        )
                    } else {
                        val captionColor = if (progress.inGoalSince != null) {
                            MaterialTheme.colorScheme.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                        val held = progress.inGoalSince?.let {
                            LocaleUtils.formatElapsed(it, progress.today)
                        }
                        Caption(
                            text = if (held != null) {
                                overshootText(progress, ::format)
                            } else {
                                stringResource(
                                    R.string.progress_elapsed_in,
                                    LocaleUtils.formatElapsed(
                                        progress.startDate, progress.today,
                                    ),
                                )
                            },
                            modifier = Modifier.padding(start = CAPTION_INDENT),
                            color = captionColor,
                        )
                        Caption(
                            text = when {
                                held != null -> stringResource(R.string.progress_held_for, held)
                                progress.daysToTarget?.let { it > 0 } == true -> stringResource(
                                    R.string.progress_time_left,
                                    LocaleUtils.formatDays(progress.daysToTarget),
                                    LocaleUtils.formatCompactDate(
                                        progress.today.plusDays(progress.daysToTarget.toLong()),
                                    ),
                                )
                                else -> format(abs(progress.remainingToGoal), false).let {
                                    stringResource(R.string.progress_remaining_to_go, it)
                                }
                            },
                            modifier = Modifier.padding(start = CAPTION_INDENT),
                            color = captionColor,
                        )
                    }
                }
            }

            if (progress != null) {
                val fraction = progress.goalFraction
                if (fraction != null) {
                    val percent = (fraction * 100f).roundToInt()
                    val description =
                        stringResource(R.string.content_description_goal_progress, percent)

                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BAR_HEIGHT)
                            .semantics { contentDescription = description },
                        color = typeColor,
                        trackColor = typeColor.copy(alpha = 0.18f),
                        // The default stop dot reads as a stray pixel at this size.
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(2.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The bar runs from the start to the target, so its ends are what is named.
                    Caption(
                        text = format(progress.startValue),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Caption(
                        text = format(progress.goalValue),
                        modifier = Modifier.weight(1f, fill = false),
                        align = TextAlign.End,
                    )
                }

            }
        }
    }
}

/** How far past the goal, the goal value itself standing in the footnote. */
@Composable
private fun overshootText(progress: GoalProgress, format: (Float, Boolean) -> String): String {
    val remaining = progress.remainingToGoal
    val overshoot = abs(remaining)
    if (overshoot < 1e-6f) return stringResource(R.string.progress_goal_reached)
    return stringResource(
        if (remaining > 0f) R.string.progress_below_goal else R.string.progress_above_goal,
        format(overshoot, false),
    )
}

@Composable
private fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    // Same muted ink the measurement rows use for their trend line.
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = CAPTION_LINE_HEIGHT),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

private val CHIP_WIDTH = 200.dp
// Sized to the three text lines next to it, which the measurement rows cap at 28dp.
private val ICON_SIZE = 28.dp
// Clears the leading +/- sign of the hero line above.
private val CAPTION_INDENT = 4.dp
private val HERO_FONT_SIZE = 15.sp
private val HERO_LINE_HEIGHT = 20.sp
private val CAPTION_LINE_HEIGHT = 15.sp
private val BAR_HEIGHT = 6.dp
