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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel

private const val TAG = "BluetoothUserInteractionDialog"

/**
 * Global dialog driven by a pending Bluetooth user-interaction event:
 * either picking a user slot on the scale (CHOOSE_USER) or entering a consent
 * code (ENTER_CONSENT). Shown app-wide above the navigation host.
 */
@Composable
fun BluetoothUserInteractionDialog(
    bluetoothViewModel: BluetoothViewModel,
    sharedViewModel: SharedViewModel
) {
    val pendingInteractionEvent by bluetoothViewModel.pendingUserInteractionEvent.collectAsState()

    pendingInteractionEvent?.let { interactionEvent ->
        val dialogTitle: String
        val dialogIcon: @Composable (() -> Unit)

        var consentCodeInput by rememberSaveable(interactionEvent.interactionType) { mutableStateOf("") }
        var selectedUserIndexState by rememberSaveable(interactionEvent.interactionType) { mutableIntStateOf(Int.MIN_VALUE) }

        when (interactionEvent.interactionType) {
            UserInteractionType.CHOOSE_USER -> {
                dialogTitle = stringResource(R.string.dialog_bt_interaction_title_choose_user)
                dialogIcon = { Icon(Icons.Filled.People, contentDescription = stringResource(R.string.dialog_bt_icon_desc_choose_user)) }
            }
            UserInteractionType.ENTER_CONSENT -> {
                dialogTitle = stringResource(R.string.dialog_bt_interaction_title_enter_consent)
                dialogIcon = { Icon(Icons.Filled.HowToReg, contentDescription = stringResource(R.string.dialog_bt_icon_desc_enter_consent)) }
            }
            // else -> { /* Handle unknown types or provide defaults */ } // Optional
        }

        val isConsentInputValid = remember(consentCodeInput) { // Recalculate only when consentCodeInput changes
            consentCodeInput.isNotEmpty() && consentCodeInput.all { it.isDigit() }
        }

        AlertDialog(
            onDismissRequest = {
                bluetoothViewModel.clearPendingUserInteraction()
            },
            icon = dialogIcon,
            title = { Text(text = dialogTitle) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            if (interactionEvent.interactionType == UserInteractionType.CHOOSE_USER)
                                R.string.dialog_bt_interaction_desc_choose_user_default
                            else
                                R.string.dialog_bt_interaction_desc_enter_consent_default
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    when (interactionEvent.interactionType) {
                        UserInteractionType.CHOOSE_USER -> {
                            val choicesData = interactionEvent.data
                            LogManager.d(TAG, "CHOOSE_USER interaction received. Data: $choicesData")

                            // Expecting Pair<Array<String>, IntArray> or Pair<Array<CharSequence>, IntArray>
                            if (choicesData is Pair<*, *> && choicesData.first is Array<*> && choicesData.second is IntArray) {
                                @Suppress("UNCHECKED_CAST")
                                val choices = choicesData as Pair<Array<CharSequence>, IntArray>
                                val choiceDisplayNames = choices.first
                                val choiceIndices = choices.second

                                LogManager.d(TAG, "CHOOSE_USER: DisplayNames (length ${choiceDisplayNames.size}): ${choiceDisplayNames.joinToString { "'$it'" }}")
                                LogManager.d(TAG, "CHOOSE_USER: Indices (length ${choiceIndices.size}): ${choiceIndices.joinToString()}")

                                if (choiceDisplayNames.isNotEmpty() && choiceDisplayNames.size == choiceIndices.size) {
                                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                                        itemsIndexed(choiceDisplayNames) { itemIndex, choiceName ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .selectable(
                                                        selected = (choiceIndices[itemIndex] == selectedUserIndexState),
                                                        onClick = {
                                                            selectedUserIndexState =
                                                                choiceIndices[itemIndex]
                                                        }
                                                    )
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = (choiceIndices[itemIndex] == selectedUserIndexState),
                                                    onClick = { selectedUserIndexState = choiceIndices[itemIndex] }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(text = choiceName.toString())
                                            }
                                        }
                                    }
                                } else {
                                    Text(stringResource(R.string.dialog_bt_error_loading_user_list_empty))
                                }
                            } else {
                                Text(stringResource(R.string.dialog_bt_error_loading_user_list_format))
                            }
                        }
                        UserInteractionType.ENTER_CONSENT -> {
                            OutlinedTextField(
                                value = consentCodeInput,
                                onValueChange = { consentCodeInput = it.filter { char -> char.isDigit() } },
                                label = { Text(stringResource(R.string.dialog_bt_label_consent_code)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // else -> { /* Handle unknown types or provide defaults */ } // Optional
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val interactionType = interactionEvent.interactionType
                        var feedbackData: Any? = null // Any, da der Typ variieren kann

                        when (interactionType) {
                            UserInteractionType.CHOOSE_USER -> {
                                if (selectedUserIndexState != Int.MIN_VALUE) {
                                    feedbackData = selectedUserIndexState // Ist ein Int
                                } else {
                                    sharedViewModel.showSnackbar(messageResId = R.string.dialog_bt_select_user_prompt)
                                }
                            }
                            UserInteractionType.ENTER_CONSENT -> {
                                if (isConsentInputValid) {
                                    feedbackData = consentCodeInput.toInt()
                                } else {
                                    sharedViewModel.showSnackbar(messageResId = R.string.dialog_bt_enter_valid_code_prompt)
                                }
                            }
                            // else -> { /* Handle unknown types or provide defaults */ } // Optional
                        }

                        feedbackData?.let { data ->
                            bluetoothViewModel.provideUserInteractionFeedback(interactionType, data)
                        }
                    },
                    enabled = when (interactionEvent.interactionType) {
                        UserInteractionType.CHOOSE_USER -> selectedUserIndexState != Int.MIN_VALUE
                        UserInteractionType.ENTER_CONSENT -> isConsentInputValid
                    }
                ) {
                    Text(stringResource(R.string.confirm_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        bluetoothViewModel.clearPendingUserInteraction()
                    }
                ) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }
}
