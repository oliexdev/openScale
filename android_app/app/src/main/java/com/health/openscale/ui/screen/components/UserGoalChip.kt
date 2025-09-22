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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LocaleUtils
import com.health.openscale.ui.components.RoundMeasurementIcon

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
    var percentageDifference: Float? = null // For coloring the difference text

    // Calculate difference string and percentage only if needed and possible
    if (showDifference && currentValue != null && targetValue != 0f) {
        val differenceNum = currentValue - targetValue
        displayDifferenceStr = LocaleUtils.formatValueForDisplay(
            value = differenceNum.toString(),
            unit = measurementType.unit,
            includeSign = true
        )
        percentageDifference = (kotlin.math.abs(differenceNum) / targetValue) * 100f
    }

    val differenceTextColor = when {
        percentageDifference == null -> LocalContentColor.current // Default color or if no difference shown
        percentageDifference <= 4f -> Color(0xFF66BB6A)   // Green
        percentageDifference <= 15f -> Color(0xFFFFCA28)  // Yellow
        else -> Color(0xFFEF5350)                         // Red
    }

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

            if (showDifference && displayDifferenceStr != null) {
                // Layout WITH difference: Icon | Column (Goal, Difference)
                Column(verticalArrangement = Arrangement.Center) {
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
                    // Difference text is only shown in this branch
                    Text(
                        text = displayDifferenceStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = differenceTextColor
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.Flag,
                    contentDescription = stringResource(R.string.measurement_type_label_goal),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Spacer(modifier = Modifier.width(4.dp)) // Optional: for fine-tuning space
                Text(
                    text = displayTargetStr,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}