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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.health.openscale.R

/**
 * A reusable Material 3 dialog warning about unsaved changes before leaving a form.
 *
 * Mirrors [DeleteConfirmationDialog]: a centered icon, title, supporting text, and a destructive
 * confirm action ("Discard") styled with the error color, plus a neutral "Keep editing" dismiss.
 *
 * @param onDismissRequest Lambda invoked when the user dismisses the dialog (keeps editing).
 * @param onConfirm Lambda invoked when the user confirms discarding the changes.
 */
@Composable
fun DiscardChangesDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
        title = { Text(stringResource(R.string.dialog_title_discard_changes)) },
        text = { Text(stringResource(R.string.dialog_message_discard_changes)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismissRequest() // Dismiss the dialog after confirmation
                }
            ) {
                Text(
                    text = stringResource(R.string.action_discard),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_keep_editing))
            }
        }
    )
}
