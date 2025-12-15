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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UserGoalChip(
    userGoal: UserGoals,
    measurementType: MeasurementType,
    referenceMeasurement: MeasurementWithValues?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val targetValue = userGoal.goalValue
    val showDifference = referenceMeasurement != null

    val currentValue: Float? = if (showDifference) {
        referenceMeasurement?.values
            ?.find { it.type.id == measurementType.id }
            ?.value?.floatValue
    } else {
        null
    }

    val displayTargetStr = LocaleUtils.formatValueForDisplay(
        value = targetValue.toString(),
        unit = measurementType.unit
    )

    var displayDifferenceStr: String? = null
    var percentageDifference: Float? = null

    if (showDifference && currentValue != null && targetValue != 0f) {
        val differenceNum = currentValue - targetValue
        displayDifferenceStr = LocaleUtils.formatValueForDisplay(
            value = differenceNum.toString(),
            unit = measurementType.unit,
            includeSign = true
        )
        // We calculate percentageDifference to determine the evaluation state
        percentageDifference = (currentValue - targetValue) / targetValue
    }

    val formattedTargetDate = remember(userGoal.goalTargetDate) {
        userGoal.goalTargetDate?.let {
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(it))
        }
    }

    val goalEvalState = when {
        percentageDifference == null -> EvaluationState.UNDEFINED
        // Example logic: +/- 4% is "NORMAL", more is "HIGH" or "LOW"
        kotlin.math.abs(percentageDifference) <= 0.04f -> EvaluationState.NORMAL
        percentageDifference > 0.04f -> EvaluationState.HIGH
        else -> EvaluationState.LOW
    }

    val evalSymbol = when (goalEvalState) {
        EvaluationState.LOW -> "▼"
        EvaluationState.NORMAL -> "●"
        EvaluationState.HIGH -> "▲"
        EvaluationState.UNDEFINED -> null
    }
    val evalColor = goalEvalState.toColor()

    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoundMeasurementIcon(
                icon = measurementType.icon.resource,
                backgroundTint = Color(measurementType.color),
                size = 20.dp,
            )

            Column(verticalArrangement = Arrangement.Center) {
                // ROW 1: Target date (if available)
                if (formattedTargetDate != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = stringResource(R.string.goal_target_date_label),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formattedTargetDate,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ROW 2: Goal value (always shown)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Flag,
                        contentDescription = stringResource(R.string.measurement_type_label_goal),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = displayTargetStr,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ROW 3: Value difference (if available)
                if (displayDifferenceStr != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayDifferenceStr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (evalSymbol != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = evalSymbol,
                                color = evalColor,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
