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
package com.health.openscale.ui.screen.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.ui.components.RoundMeasurementIcon
import java.util.Calendar

@Composable
fun TimeInputDialog(
    title: String,
    initialTimestamp: Long,
    measurementIcon: MeasurementTypeIcon,
    iconBackgroundColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val calendar = remember { Calendar.getInstance().apply { timeInMillis = initialTimestamp } }

    var hour by remember { mutableStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(calendar.get(Calendar.MINUTE)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val updatedCal = Calendar.getInstance().apply {
                    timeInMillis = initialTimestamp
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                }
                onConfirm(updatedCal.timeInMillis)
                onDismiss()
            }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundMeasurementIcon(
                    icon = measurementIcon.resource,
                    backgroundTint = iconBackgroundColor,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TimeField(
                    label = stringResource(R.string.dialog_title_hour),
                    value = hour,
                    onValueChange = { hour = it.coerceIn(0, 23) },
                    onIncrement = { hour = (hour + 1) % 24 },
                    onDecrement = { hour = (hour + 23) % 24 }
                )
                TimeField(
                    label = stringResource(R.string.dialog_title_minute),
                    value = minute,
                    onValueChange = { minute = it.coerceIn(0, 59) },
                    onIncrement = { minute = (minute + 1) % 60 },
                    onDecrement = { minute = (minute + 59) % 60 }
                )
            }
        }
    )
}

@Composable
private fun TimeField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)

        OutlinedTextField(
            value = value.toString().padStart(2, '0'),
            onValueChange = {
                it.toIntOrNull()?.let { newVal -> onValueChange(newVal) }
            },
            modifier = Modifier.width(80.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
        )

        Row {
            IconButton(onClick = onIncrement) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.trend_increased_desc))
            }
            IconButton(onClick = onDecrement) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.trend_decreased_desc))
            }
        }
    }
}
