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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.User
import com.health.openscale.ui.components.RoundMeasurementIcon
import java.util.Locale

@Composable
fun UserInputDialog(
    title: String,
    users: List<User>,
    initialSelectedId: Int?,
    measurementIcon: MeasurementTypeIcon,
    iconBackgroundColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit
) {
    var selectedId by remember(users, initialSelectedId) { mutableStateOf(initialSelectedId) }

    val usersSorted = remember(users) {
        users.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedId) }) {
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
                    icon = measurementIcon,
                    backgroundTint = iconBackgroundColor
                )
                Spacer(Modifier.width(12.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Box(Modifier.heightIn(max = 360.dp)) {
                LazyColumn {
                    items(usersSorted, key = { it.id }) { user ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = user.id }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = (selectedId == user.id),
                                onClick = { selectedId = user.id }
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = user.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    )
}
