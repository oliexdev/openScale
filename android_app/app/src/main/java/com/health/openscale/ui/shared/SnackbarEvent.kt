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
package com.health.openscale.ui.shared

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration

/**
 * Represents an event to display a Snackbar.
 * It supports internationalization through string resource IDs and formatted strings,
 * as well as direct string content as a fallback.
 *
 * @property messageResId The resource ID for the Snackbar message. Defaults to 0 if not used.
 * @property message A direct string for the Snackbar message. Used if [messageResId] is 0.
 * @property messageFormatArgs Optional arguments for formatting the [messageResId] string.
 * @property duration The [SnackbarDuration] for which the Snackbar is shown.
 * @property actionLabelResId Optional resource ID for the Snackbar's action button label.
 * @property actionLabel Optional direct string for the action button label. Used if [actionLabelResId] is null.
 * @property onAction Optional lambda to be executed when the action button is pressed.
 */
data class SnackbarEvent(
    @StringRes val messageResId: Int? = null,
    val message: String? = null,
    val messageFormatArgs: List<Any> = emptyList(),
    val duration: SnackbarDuration = SnackbarDuration.Short,
    @StringRes val actionLabelResId: Int? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
) {
    init {
        require(message != null || messageResId != null) {
            "SnackbarEvent requires either message or messageResId."
        }
    }
}

