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
package com.health.openscale.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasureUnit
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.utils.Converters
import com.health.openscale.ui.screen.SharedViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Composable screen for adding a new user or editing an existing user's details.
 *
 * This screen provides input fields for user's name, height, gender, activity level,
 * and birth date. It interacts with [SettingsViewModel] to save or update user data
 * and with [SharedViewModel] to manage top bar actions and titles.
 *
 * @param navController The NavController used for navigation, e.g., to go back after saving.
 * @param userId The ID of the user to edit. If -1, a new user is being added.
 * @param sharedViewModel The ViewModel shared across different screens, used here for top bar configuration and user selection.
 * @param settingsViewModel The ViewModel responsible for user data operations like adding or updating users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    navController: NavController,
    userId: Int,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel
) {
    val isEdit = userId != -1

    // Retrieve the user from SharedViewModel if editing, or prepare for a new user.
    val user by remember(userId) {
        mutableStateOf(sharedViewModel.allUsers.value.find { it.id == userId })
    }

    var name by remember { mutableStateOf(user?.name.orEmpty()) }
    var birthDate by remember {
        val initialBirthDate = user?.birthDate
        if (initialBirthDate != null) {
            mutableStateOf(initialBirthDate)
        } else {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.add(Calendar.YEAR, -18)
            mutableStateOf(calendar.timeInMillis)
        }
    }
    var gender by remember { mutableStateOf(user?.gender ?: GenderType.MALE) }
    var heightInputUnit by remember { mutableStateOf(UnitType.CM) }
    var heightValueString by remember { mutableStateOf("") }
    val heightUnitsOptions = listOf(UnitType.CM, UnitType.INCH)

    var activityLevel by remember { mutableStateOf(user?.activityLevel ?: ActivityLevel.SEDENTARY) }

    val context = LocalContext.current
    val dateFormatter = remember {
        DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = birthDate)
    var showDatePicker by remember { mutableStateOf(false) }
    var activityLevelExpanded by remember { mutableStateOf(false) }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        birthDate = it
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(id = R.string.dialog_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(id = R.string.cancel_button))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val editUserTitle = stringResource(R.string.user_detail_edit_user_title)
    val addUserTitle = stringResource(R.string.user_detail_add_user_title)

    LaunchedEffect(user, heightInputUnit) {
        user?.heightCm?.let { cmValue -> // user.heightCm ist die in der DB gespeicherte Höhe in CM
            if (cmValue > 0f) {
                heightValueString = if (heightInputUnit == UnitType.CM) {
                    String.format(Locale.US, "%.1f", cmValue)
                } else { // heightInputUnit == UnitType.INCH
                    // Konvertiere den CM-Wert aus der DB in Zoll für die Anzeige
                    val inchesValue = Converters.convertFloatValueUnit(cmValue, UnitType.CM, UnitType.INCH)
                    String.format(Locale.US, "%.1f", inchesValue)
                }
            } else {
                heightValueString = "" // Wenn keine valide gespeicherte Höhe, Feld leeren
            }
        } ?: run {
            heightValueString = "" // Neuer User, Feld leer
        }
    }

    // Effect to set the top bar title and save action.
    // This runs when userId changes or the screen is first composed.
    LaunchedEffect(userId) {
        sharedViewModel.setTopBarTitle(
            if (isEdit) editUserTitle
            else addUserTitle
        )
        sharedViewModel.setTopBarAction(
            SharedViewModel.TopBarAction(icon = Icons.Default.Save, onClick = {
                val validNumericHeight = heightValueString.toFloatOrNull()
                var finalHeightCm: Float? = null

                if (validNumericHeight != null && validNumericHeight > 0f) {
                    finalHeightCm = if (heightInputUnit == UnitType.CM) {
                        validNumericHeight // Wert ist bereits in CM
                    } else { // heightInputUnit == UnitType.INCH
                        // Konvertiere den in Zoll eingegebenen Wert zurück zu CM für die Speicherung
                        Converters.convertFloatValueUnit(validNumericHeight, UnitType.INCH, UnitType.CM)
                    }
                }
                if (name.isNotBlank() && finalHeightCm != null) {
                    val newUser = User(
                        id = user?.id ?: 0, // Use existing ID if editing, or 0 for Room to auto-generate
                        name = name,
                        birthDate = birthDate,
                        gender = gender,
                        heightCm = finalHeightCm,
                        activityLevel = activityLevel
                    )
                    settingsViewModel.viewModelScope.launch {
                        if (isEdit) {
                            settingsViewModel.updateUser(newUser)
                        } else {
                            val newUserId = settingsViewModel.addUser(newUser)
                            if (newUserId > 0) {
                                // If a new user was added, select them in SharedViewModel
                                sharedViewModel.selectUser(newUserId.toInt())
                            }
                        }
                    }
                    navController.popBackStack() // Navigate back after saving
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.user_detail_error_invalid_data), // "Please enter valid data"
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        )
    }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(id = R.string.user_detail_label_name)) }, // "Name"
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = heightValueString,
            onValueChange = { newValue ->
                val filteredValue = newValue.filter { it.isDigit() || it == '.' }
                if (filteredValue.count { it == '.' } <= 1) {
                    heightValueString = filteredValue
                }
            },
            label = { Text(stringResource(R.string.user_detail_label_height)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = {
                    val currentIndex = heightUnitsOptions.indexOf(heightInputUnit)
                    val nextIndex = (currentIndex + 1) % heightUnitsOptions.size
                    val newUnit = heightUnitsOptions[nextIndex]

                    val currentNumericValue = heightValueString.toFloatOrNull()
                    if (currentNumericValue != null && currentNumericValue > 0f) {
                        val convertedValue = Converters.convertFloatValueUnit(currentNumericValue, heightInputUnit, newUnit)
                        heightValueString = String.format(Locale.US, "%.1f", convertedValue)
                    } else {
                        heightValueString = ""
                    }
                    heightInputUnit = newUnit
                }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = heightInputUnit.displayName.uppercase(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Icon(
                            imageVector = Icons.Filled.UnfoldMore,
                            contentDescription = stringResource(R.string.user_detail_content_description_change_unit),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GenderType.entries.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { gender = option }
                        .padding(end = 8.dp)
                ) {
                    RadioButton(
                        selected = gender == option,
                        onClick = { gender = option }
                    )
                    // Display gender options with first letter capitalized.
                    Text(option.name.lowercase().replaceFirstChar { it.uppercaseChar().toString() })
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = activityLevelExpanded,
            onExpandedChange = { activityLevelExpanded = !activityLevelExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = activityLevel.name.lowercase().replaceFirstChar { it.uppercaseChar().toString() },
                onValueChange = {}, // Input is read-only, selection via dropdown
                readOnly = true,
                label = { Text(stringResource(id = R.string.user_detail_label_activity_level)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = activityLevelExpanded)
                },
                modifier = Modifier
                    .menuAnchor(type = MenuAnchorType.PrimaryNotEditable) // Anchors the dropdown menu to this text field
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = activityLevelExpanded,
                onDismissRequest = { activityLevelExpanded = false }
            ) {
                ActivityLevel.entries.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption.name.lowercase().replaceFirstChar { it.uppercaseChar().toString() }) },
                        onClick = {
                            activityLevel = selectionOption
                            activityLevelExpanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }

        OutlinedTextField(
            value = dateFormatter.format(Date(birthDate)),
            label = { Text(stringResource(R.string.user_detail_label_birth_date)) },
            onValueChange = {}, // Input is read-only, selection via DatePicker
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDatePicker = true }, // Show DatePicker on click
            enabled = false, // Visually indicates it's not directly editable
            readOnly = true  // Ensures it's not directly editable
        )
    }
}
