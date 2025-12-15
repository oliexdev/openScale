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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.AmputationPart
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.IconResource
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.data.UserIcon
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import com.health.openscale.ui.screen.components.UserGoalChip
import com.health.openscale.ui.screen.dialog.AmputationInputDialog
import com.health.openscale.ui.screen.dialog.IconPickerDialog
import com.health.openscale.ui.screen.dialog.UserGoalDialog
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.shared.TopBarAction
import kotlinx.coroutines.launch
import java.text.DateFormat
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

    var selectedIcon by remember { mutableStateOf(user?.icon ?: UserIcon.IC_DEFAULT) }
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
    var genderExpanded by remember { mutableStateOf(false) }
    var heightInputUnit by remember { mutableStateOf(UnitType.CM) }
    var heightValueString by remember { mutableStateOf("") }
    val heightUnitsOptions = listOf(UnitType.CM, UnitType.INCH)

    var activityLevel by remember { mutableStateOf(user?.activityLevel ?: ActivityLevel.SEDENTARY) }
    var useAssistedWeighing by remember(user) { mutableStateOf(user?.useAssistedWeighing ?: false) }
    var amputations by remember(user) { mutableStateOf(user?.amputations ?: emptyMap()) }
    var showAmputationDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dateFormatter = remember {
        DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = birthDate)
    var showDatePicker by remember { mutableStateOf(false) }
    var activityLevelExpanded by remember { mutableStateOf(false) }

    val allMeasurementTypes by sharedViewModel.measurementTypes.collectAsState()
    val goalDialogContextData by sharedViewModel.userGoalDialogContext.collectAsState()
    val userGoals by if (user?.id != 0) {
        sharedViewModel.getAllGoalsForUser(user?.id ?: -1).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<UserGoals>()) }
    }

    var pendingUserGoals by remember { mutableStateOf(emptyList<UserGoals>()) }

    LaunchedEffect(userGoals, isEdit) {
        if (pendingUserGoals != userGoals) {
            pendingUserGoals = userGoals
        }
    }

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

    if (showAmputationDialog) {
        AmputationInputDialog(
            currentAmputations = amputations,
            onDismiss = { showAmputationDialog = false },
            onSave = { newAmputations ->
                amputations = newAmputations
                showAmputationDialog = false
            }
        )
    }

    LaunchedEffect(user, heightInputUnit) {
        user?.heightCm?.let { cmValue ->
            if (cmValue > 0f) {
                heightValueString = if (heightInputUnit == UnitType.CM) {
                    String.format(Locale.US, "%.1f", cmValue)
                } else { // heightInputUnit == UnitType.INCH
                    val inchesValue = ConverterUtils.convertFloatValueUnit(cmValue, UnitType.CM, UnitType.INCH)
                    String.format(Locale.US, "%.1f", inchesValue)
                }
            } else {
                heightValueString = ""
            }
        } ?: run {
            heightValueString = ""
        }
    }


    /**
     * Adds a new user and their pending goals to the database.
     */
    fun addUser() {
        val numericHeight = heightValueString.replace(',', '.').toFloatOrNull()
        val finalHeightCm = if (numericHeight != null && numericHeight > 0f) {
            if (heightInputUnit == UnitType.CM) numericHeight
            else ConverterUtils.convertFloatValueUnit(numericHeight, UnitType.INCH, UnitType.CM)
        } else null

        if (name.isBlank() || finalHeightCm == null || finalHeightCm <= 0f) {
            Toast.makeText(context, R.string.user_detail_error_invalid_data, Toast.LENGTH_SHORT).show()
            return
        }

        settingsViewModel.viewModelScope.launch {
            val newUserToSave = User(
                id = 0, name = name, icon = selectedIcon, birthDate = birthDate,
                gender = gender, heightCm = finalHeightCm, activityLevel = activityLevel,
                useAssistedWeighing = useAssistedWeighing, amputations = amputations
            )
            val newGeneratedUserIdLong = settingsViewModel.addUser(newUserToSave)
            if (newGeneratedUserIdLong > 0) {
                val newGeneratedUserIdInt = newGeneratedUserIdLong.toInt()
                pendingUserGoals.forEach { currentPendingGoal ->
                    val finalGoalToSave = currentPendingGoal.copy(userId = newGeneratedUserIdInt)
                    sharedViewModel.insertUserGoal(finalGoalToSave)
                }
                sharedViewModel.selectUser(newGeneratedUserIdInt)
                navController.popBackStack()
            } else {
                Toast.makeText(context, R.string.user_detail_error_invalid_data, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Updates an existing user's profile and synchronizes their goals with the database.
     */
    fun updateUser() {
        val currentLoadedUser = user ?: return // Should not happen in edit mode

        val numericHeight = heightValueString.replace(',', '.').toFloatOrNull()
        val finalHeightCm = if (numericHeight != null && numericHeight > 0f) {
            if (heightInputUnit == UnitType.CM) numericHeight
            else ConverterUtils.convertFloatValueUnit(numericHeight, UnitType.INCH, UnitType.CM)
        } else null

        if (name.isBlank() || finalHeightCm == null || finalHeightCm <= 0f) {
            Toast.makeText(context, R.string.user_detail_error_invalid_data, Toast.LENGTH_SHORT).show()
            return
        }

        settingsViewModel.viewModelScope.launch {
            val updatedUser = currentLoadedUser.copy(
                name = name, icon = selectedIcon, birthDate = birthDate, gender = gender,
                heightCm = finalHeightCm, activityLevel = activityLevel, useAssistedWeighing = useAssistedWeighing,
                amputations = amputations
            )
            settingsViewModel.updateUser(updatedUser)

            val originalDbSnapshot = userGoals // Snapshot of goals from DB when screen loaded

            // Goals to delete
            val goalsToDelete = originalDbSnapshot.filter { originalGoal ->
                pendingUserGoals.none { pendingGoal -> pendingGoal.measurementTypeId == originalGoal.measurementTypeId }
            }
            goalsToDelete.forEach { goalToDel ->
                sharedViewModel.deleteUserGoal(currentLoadedUser.id, goalToDel.measurementTypeId)
            }

            // Goals to insert or update
            pendingUserGoals.forEach { currentPendingGoal ->
                val goalForDb = currentPendingGoal.copy(userId = currentLoadedUser.id)
                val originalGoalInSnapshot = originalDbSnapshot.find { it.measurementTypeId == goalForDb.measurementTypeId }

                if (originalGoalInSnapshot != null) { // Goal existed
                    if (originalGoalInSnapshot.goalValue != goalForDb.goalValue) { // And value changed
                        sharedViewModel.updateUserGoal(goalForDb)
                    }
                } else { // New goal for this user
                    sharedViewModel.insertUserGoal(goalForDb)
                }
            }
            navController.popBackStack()
        }
    }


    // --- TopBar Save Action ---
    LaunchedEffect(key1 = userId) {
        sharedViewModel.setTopBarTitle(
            if (isEdit) context.getString(R.string.user_detail_edit_user_title)
            else context.getString(R.string.user_detail_add_user_title)
        )
        sharedViewModel.setTopBarAction(
            TopBarAction(icon = Icons.Default.Save, onClick = {
                if (!isEdit) {
                    addUser()
                } else {
                    updateUser()
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
        UserIconPicker(
            selectedIcon = selectedIcon.resource,
            onIconSelected = { selectedResource ->
                selectedIcon = UserIcon.entries.first { it.resource == selectedResource }
            }
        )

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
                        val convertedValue = ConverterUtils.convertFloatValueUnit(currentNumericValue, heightInputUnit, newUnit)
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

        ExposedDropdownMenuBox(
            expanded = genderExpanded,
            onExpandedChange = { genderExpanded = !genderExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = gender.getDisplayName(LocalContext.current),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(id = R.string.user_detail_label_gender)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderExpanded)
                },
                modifier = Modifier
                    .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = genderExpanded,
                onDismissRequest = { genderExpanded = false }
            ) {
                GenderType.entries.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption.getDisplayName(LocalContext.current)) },
                        onClick = {
                            gender = selectionOption
                            genderExpanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
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

        Box {
            OutlinedTextField(
                value = AmputationPart.toSummaryString(amputations),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.amputation_correction_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.amputation_correction_label)
                    )
                }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { showAmputationDialog = true }
            )
        }

        Box {
            OutlinedTextField(
                value = stringResource(if (useAssistedWeighing) R.string.switch_on else R.string.switch_off),
                onValueChange = {},
                label = { Text(stringResource(R.string.user_detail_label_assisted_weighting)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = true,
                readOnly = true,
                trailingIcon = {
                    Switch(
                        checked = useAssistedWeighing,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { useAssistedWeighing = !useAssistedWeighing }
            )
        }

        Box {
            OutlinedTextField(
                value = dateFormatter.format(Date(birthDate)),
                onValueChange = {},
                label = { Text(stringResource(R.string.user_detail_label_birth_date)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = true,
                readOnly = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.user_detail_label_birth_date)
                    )
                }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { showDatePicker = true }
            )
        }

        // My Goals
        Column {
            OutlinedTextField(
                value = if (pendingUserGoals.isNotEmpty()) {
                    stringResource(R.string.user_detail_goals,pendingUserGoals.size)
                } else {
                    stringResource(R.string.user_detail_no_goals)
                },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.my_goals_label)) },
                trailingIcon = {
                    IconButton(onClick = {
                        val firstTargetableType = allMeasurementTypes.firstOrNull {
                            (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) && !it.isDerived &&
                                    pendingUserGoals.none { ug -> ug.measurementTypeId == it.id }
                        }
                        if (firstTargetableType != null) {
                            sharedViewModel.showUserGoalDialogWithContext(
                                type = firstTargetableType,
                                existingGoal = null,
                            )
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.AddCircleOutline,
                            contentDescription = stringResource(R.string.action_add_measurement_desc),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
            )

            if (pendingUserGoals.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    LazyRow(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pendingUserGoals, key = { it.userId.toString() + "_" + it.measurementTypeId.toString() }) { goal ->
                            val measurementType = allMeasurementTypes.find { it.id == goal.measurementTypeId }
                            if (measurementType != null) {
                                UserGoalChip(
                                    userGoal = goal,
                                    measurementType = measurementType,
                                    referenceMeasurement = null,
                                    onClick = {
                                        sharedViewModel.showUserGoalDialogWithContext(
                                            type = measurementType,
                                            existingGoal = goal
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

    }

    if (goalDialogContextData.showDialog) {

        UserGoalDialog(
            navController = navController,
            existingUserGoal = goalDialogContextData.existingGoalForDialog,
            allMeasurementTypes = allMeasurementTypes,
            allGoalsOfCurrentUser = pendingUserGoals,
            onDismiss = { sharedViewModel.dismissUserGoalDialogWithContext() },
            onConfirm = { measurementTypeId, goalValueString, goalTargetDate ->
                val goalValueFloat = goalValueString.replace(',', '.').toFloatOrNull()

                if (goalValueFloat == null) {
                    if (goalDialogContextData.existingGoalForDialog == null) {  return@UserGoalDialog }
                    else {
                        pendingUserGoals = pendingUserGoals.filterNot { it.measurementTypeId == measurementTypeId && it.userId == goalDialogContextData.existingGoalForDialog!!.userId }
                        sharedViewModel.dismissUserGoalDialogWithContext()
                        return@UserGoalDialog
                    }
                }

                val targetUserIdForPendingGoal = if (!isEdit) -1 else user!!.id

                val newOrUpdatedPendingGoal = UserGoals(
                    userId = targetUserIdForPendingGoal,
                    measurementTypeId = measurementTypeId,
                    goalValue = goalValueFloat,
                    goalTargetDate = goalTargetDate
                )

                val existingIndex = pendingUserGoals.indexOfFirst { it.measurementTypeId == newOrUpdatedPendingGoal.measurementTypeId && it.userId == newOrUpdatedPendingGoal.userId }
                if (existingIndex != -1) {
                    pendingUserGoals = pendingUserGoals.toMutableList().apply { set(existingIndex, newOrUpdatedPendingGoal) }
                } else {
                    pendingUserGoals = pendingUserGoals + newOrUpdatedPendingGoal
                }
                sharedViewModel.dismissUserGoalDialogWithContext()
            },
            onDelete = { userIdFromDialog, measurementTypeIdFromDialog ->
                pendingUserGoals = pendingUserGoals.filterNot { it.measurementTypeId == measurementTypeIdFromDialog && it.userId == userIdFromDialog }
                sharedViewModel.dismissUserGoalDialogWithContext()
            }
        )
    }

}

@Composable
fun UserIconPicker(
    selectedIcon: IconResource,
    iconBackgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    onIconSelected: (IconResource) -> Unit
) {
    var showIconPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RoundMeasurementIcon(
            icon = selectedIcon,
            size = 48.dp,
            backgroundTint = iconBackgroundColor,
            iconTint = LocalContentColor.current,
            modifier = Modifier
                .clickable { showIconPicker = true }
        )

        if (showIconPicker) {
            IconPickerDialog(
                iconTintColor = LocalContentColor.current,
                iconBackgroundColor = iconBackgroundColor,
                availableIcons = UserIcon.entries.map { it.resource },
                onIconSelected = {
                    onIconSelected(it)
                    showIconPicker = false
                },
                onDismiss = { showIconPicker = false }
            )
        }
    }
}
