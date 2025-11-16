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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.AmputationPart
import com.health.openscale.core.data.Limb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmputationInputDialog(
    currentAmputations: Map<Limb, AmputationPart>,
    onDismiss: () -> Unit,
    onSave: (Map<Limb, AmputationPart>) -> Unit
) {
    var selection by remember { mutableStateOf(currentAmputations) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.amputation_correction_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.amputation_dialog_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))

                val armLevels = AmputationPart.entries.filter { it.name.contains("ARM") || it.name.contains("HAND") || it.name.contains("FOREARM") }
                val legLevels = AmputationPart.entries.filter { it.name.contains("LEG") || it.name.contains("FOOT") }

                Limb.entries.forEach { limb ->
                    AmputationRow(
                        label = stringResource(limb.displayNameResId),
                        levels = if (limb.name.contains("ARM")) armLevels else legLevels,
                        selection = selection[limb], // Lese aus der Map
                        onSelectionChange = { part ->
                            val newSelection = selection.toMutableMap()
                            if (part != null) {
                                newSelection[limb] = part
                            } else {
                                newSelection.remove(limb)
                            }
                            selection = newSelection
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selection) }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmputationRow(
    label: String,
    levels: List<AmputationPart>,
    selection: AmputationPart?,
    onSelectionChange: (AmputationPart?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isChecked = selection != null

    val minRowHeight = 56.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (isChecked) {
                            onSelectionChange(null)
                        } else {
                            onSelectionChange(levels.first())
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = null
            )
            Spacer(Modifier.width(8.dp))
            Text(label)
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selection != null) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = stringResource(id = selection.displayNameResId),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        levels.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(stringResource(id = level.displayNameResId)) },
                                onClick = {
                                    onSelectionChange(level)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}