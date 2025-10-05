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
package com.health.openscale.ui.navigation

import kotlin.Pair
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.health.openscale.BuildConfig
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.data.IconResource
import com.health.openscale.core.data.User
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.navigation.Routes.getIconForRoute
import com.health.openscale.ui.shared.SharedViewModel
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.screen.graph.GraphScreen
import com.health.openscale.ui.screen.overview.MeasurementDetailScreen
import com.health.openscale.ui.screen.overview.OverviewScreen
import com.health.openscale.ui.screen.settings.AboutScreen
import com.health.openscale.ui.screen.settings.BluetoothScreen
import com.health.openscale.ui.screen.settings.ChartSettingsScreen
import com.health.openscale.ui.screen.settings.DataManagementSettingsScreen
import com.health.openscale.ui.screen.settings.GeneralSettingsScreen
import com.health.openscale.ui.screen.settings.MeasurementTypeDetailScreen
import com.health.openscale.ui.screen.settings.MeasurementTypeSettingsScreen
import com.health.openscale.ui.screen.settings.SettingsScreen
import com.health.openscale.ui.screen.settings.SettingsViewModel
import com.health.openscale.ui.screen.settings.UserDetailScreen
import com.health.openscale.ui.screen.settings.UserSettingsScreen
import com.health.openscale.ui.screen.statistics.StatisticsScreen
import com.health.openscale.ui.screen.table.TableScreen
import com.health.openscale.ui.theme.Black
import com.health.openscale.ui.theme.Blue
import com.health.openscale.ui.theme.White
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Main composable function that sets up the application's navigation structure.
 * This includes a modal navigation drawer, a top app bar, a snackbar host for displaying
 * messages, and a [NavHost] for handling screen transitions based on defined routes.
 *
 * It observes [SharedViewModel] for shared UI state like the top bar title, actions,
 * user information, and snackbar messages.
 *
 * @param sharedViewModel The [SharedViewModel] instance shared across multiple screens,
 *                        providing access to shared data and UI event channels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(sharedViewModel: SharedViewModel) {
    val TAG = "AppNavigation"
    val context = LocalContext.current
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize ViewModels that might be needed by screens within this navigation structure
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    val bluetoothViewModel: BluetoothViewModel = hiltViewModel()

    // Observe the current navigation route
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Define the main navigation routes that appear in the navigation drawer
    val mainRoutes = listOf(
        Routes.OVERVIEW,
        Routes.GRAPH,
        Routes.TABLE,
        Routes.STATISTICS,
        Routes.SETTINGS
    )

    // Collect UI states from SharedViewModel
    val topBarTitleFromVM by sharedViewModel.topBarTitle.collectAsState()
    val topBarActions by sharedViewModel.topBarActions.collectAsState()
    val isInContextualSelectionMode by sharedViewModel.isInContextualSelectionMode.collectAsState()
    val allUsers by sharedViewModel.allUsers.collectAsState()
    val selectedUser by sharedViewModel.selectedUser.collectAsState()

    // Resolve the title for the TopAppBar.
    // The title can be provided as a direct String or as a @StringRes Int.
    val topBarTitle = when (val titleData = topBarTitleFromVM) {
        is String -> titleData
        is Int -> if (titleData != Routes.NO_TITLE_RESOURCE_ID) stringResource(id = titleData) else ""
        else -> "" // Default to empty string if title data is null or unexpected type
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(snackbarHostState, sharedViewModel, settingsViewModel, bluetoothViewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            kotlinx.coroutines.flow.merge(
                sharedViewModel.snackbarEvents,
                settingsViewModel.snackbarEvents,
                bluetoothViewModel.snackbarEvents
            )
            .distinctUntilChanged { a, b ->
                    a.messageResId == b.messageResId && a.message == b.message && a.messageFormatArgs == b.messageFormatArgs
            }
            .debounce(150)
            .collect { evt ->
                val msg = evt.message ?: context.getString(
                    requireNotNull(evt.messageResId),
                    *evt.messageFormatArgs.toTypedArray()
                )
                val action = evt.actionLabel ?: evt.actionLabelResId?.let { context.getString(it) }
                val res = snackbarHostState.run {
                    currentSnackbarData?.dismiss()
                    showSnackbar(
                        message = msg,
                        actionLabel = action,
                        duration = evt.duration
                    )
                }
                if (res == SnackbarResult.ActionPerformed) evt.onAction?.invoke()
            }
        }
    }

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



    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Black, // Custom drawer background color
                drawerContentColor = White    // Custom drawer content color for icons and text
            ) {
                // Drawer Header: Displays the app logo and name.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topEnd = 24.dp)) // Specific rounding for visual style
                        .background(Blue) // Themed background for the header
                        .padding(8.dp)
                        .fillMaxWidth()
                ) {
                    Image(
                        painter = if (BuildConfig.BUILD_TYPE == "beta" || BuildConfig.BUILD_TYPE == "oss") painterResource(id = R.drawable.ic_launcher_beta_foreground) else painterResource(id = R.drawable.ic_launcher_foreground) ,
                        contentDescription = stringResource(R.string.app_logo_content_description),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp)) // Spacing after the header

                // Drawer Items: Dynamically created for each main route.
                mainRoutes.forEach { route ->
                    // Add a divider before the "Settings" item for visual separation.
                    if (route == Routes.SETTINGS) {
                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    val titleResId = Routes.getTitleResourceId(route)
                    val titleText = if (titleResId != Routes.NO_TITLE_RESOURCE_ID) {
                        stringResource(id = titleResId)
                    } else {
                        route // Fallback to the raw route string if no title resource ID is defined.
                    }

                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = getIconForRoute(route),
                                contentDescription = titleText // Provides accessibility for the icon.
                            )
                        },
                        label = { Text(titleText) },
                        selected = currentRoute == route, // Highlights the item if it's the current route.
                        onClick = {
                            navController.navigate(route) {
                                // Pop up to the start destination of the graph to avoid building up a large back stack.
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true // Save the state of popped destinations.
                                }
                                // Avoid multiple copies of the same destination when reselecting the same item.
                                launchSingleTop = true
                                // Restore state when reselecting a previously visited item.
                                restoreState = true
                            }
                            scope.launch { drawerState.close() } // Close the drawer after selection.
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            // Custom colors for selected and unselected drawer items.
                            selectedIconColor = Blue,
                            selectedTextColor = Blue,
                            selectedContainerColor = Color.Transparent, // No background for the selected item itself.

                            unselectedIconColor = White,
                            unselectedTextColor = White,
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { snackbarData ->
                    // Custom Snackbar appearance defined here.
                    Snackbar(
                        modifier = Modifier.padding(8.dp), // Padding around the snackbar.
                        shape = RoundedCornerShape(8.dp), // Rounded corners for the snackbar.
                        containerColor = Blue, // Custom background color.
                        contentColor = White,    // Custom text and icon color.
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = stringResource(R.string.app_logo_content_description), // Accessibility.
                                tint = LocalContentColor.current // Uses the contentColor from Snackbar.
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(snackbarData.visuals.message)
                        }
                    }
                }
            },
            topBar = {
                TopAppBar(
                    title = { Text(topBarTitle) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Black,
                        titleContentColor = White,
                        navigationIconContentColor = White,
                        actionIconContentColor = White
                    ),
                    navigationIcon = {
                        if (currentRoute in mainRoutes) {
                            // Show menu icon for main routes to open the drawer.
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.content_desc_open_menu)
                                )
                            }
                        } else {
                            // Show back arrow for non-main (detail or sub-page) routes.
                            IconButton(onClick = {
                                navController.popBackStack()
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.content_desc_back)
                                )
                            }
                        }
                    },
                    actions = {
                        // Display actions defined in SharedViewModel.
                        topBarActions.forEach { action ->
                            val contentDesc = action.contentDescriptionResId?.let { stringResource(id = it) }
                                ?: action.contentDescription
                            IconButton(onClick = action.onClick) {
                                Icon(imageVector = action.icon, contentDescription = contentDesc)
                            }
                            // If the action has associated dropdown content, invoke it here.
                            // This allows TopAppBar actions to also host DropdownMenus.
                            action.dropdownContent?.invoke()
                        }

                        // Show user switcher dropdown if on a main route and users exist.
                        if (!isInContextualSelectionMode && currentRoute in mainRoutes && allUsers.isNotEmpty() && currentRoute != Routes.SETTINGS) {
                            UserDropdownAsAction(
                                users = allUsers,
                                selectedUser = selectedUser,
                                onUserSelected = { userId ->
                                    sharedViewModel.selectUser(userId)
                                    // Consider closing the drawer if open, or other UI updates.
                                },
                                onManageUsersClicked = {
                                    navController.navigate(Routes.USER_SETTINGS)
                                    // Consider closing the drawer if open.
                                }
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.OVERVIEW,
                    modifier = Modifier
                        .padding(innerPadding) // Apply padding from Scaffold.
                        .weight(1f)      // NavHost takes the remaining space in the Column.
                ) {
                    // Define all composable screens for navigation routes.
                    composable(Routes.OVERVIEW) {
                        OverviewScreen(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                            bluetoothViewModel = bluetoothViewModel
                        )
                    }
                    composable(Routes.GRAPH) {
                        GraphScreen(
                            navController = navController,
                            sharedViewModel = sharedViewModel
                        )
                    }
                    composable(Routes.TABLE) {
                        TableScreen(
                            navController = navController,
                            sharedViewModel = sharedViewModel
                        )
                    }
                    composable(Routes.STATISTICS) {
                        StatisticsScreen(sharedViewModel)
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                            settingsViewModel = settingsViewModel
                        )
                    }
                    composable(Routes.GENERAL_SETTINGS) {
                        GeneralSettingsScreen(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                            settingsViewModel = settingsViewModel
                        )
                    }
                    composable(Routes.USER_SETTINGS) {
                        UserSettingsScreen(
                            sharedViewModel = sharedViewModel,
                            settingsViewModel = settingsViewModel,
                            onEditUser = { userId ->
                                navController.navigate(Routes.userDetail(userId))
                            }
                        )
                    }
                    composable(
                        route = "${Routes.USER_DETAIL}?id={id}", // Argument in route pattern
                        arguments = listOf(navArgument("id") {
                            type = NavType.IntType
                            defaultValue = -1 // Indicates a new user if ID is -1 (or not passed)
                        })
                    ) { backStackEntry ->
                        val userId = backStackEntry.arguments?.getInt("id") ?: -1
                        UserDetailScreen(
                            navController = navController,
                            userId = userId,
                            sharedViewModel = sharedViewModel,
                            settingsViewModel = settingsViewModel
                        )
                    }
                    composable(Routes.MEASUREMENT_TYPES) {
                        MeasurementTypeSettingsScreen(
                            sharedViewModel = sharedViewModel,
                            settingsViewModel = settingsViewModel,
                            onEditType = { typeId ->
                                navController.navigate(Routes.measurementTypeDetail(typeId))
                            }
                        )
                    }
                    composable(
                        route = "${Routes.MEASUREMENT_DETAIL}?measurementId={measurementId}&userId={userId}",
                        arguments = listOf(
                            navArgument("measurementId") {
                                type = NavType.IntType
                                defaultValue = -1 // Default if not provided
                            },
                            navArgument("userId") {
                                type = NavType.IntType
                                defaultValue = -1 // Default if not provided, might also fetch from selectedUser if appropriate
                            }
                        )
                    ) { backStackEntry ->
                        val measurementId = backStackEntry.arguments?.getInt("measurementId") ?: -1
                        val userId = backStackEntry.arguments?.getInt("userId") ?: -1
                        MeasurementDetailScreen(
                            navController = navController,
                            measurementId = measurementId,
                            userId = userId,
                            sharedViewModel = sharedViewModel
                        )
                    }
                    composable(
                        route = "${Routes.MEASUREMENT_TYPE_DETAIL}?id={id}",
                        arguments = listOf(navArgument("id") {
                            type = NavType.IntType
                            defaultValue = -1 // Indicates a new type if ID is -1
                        })
                    ) { backStackEntry ->
                        val typeId = backStackEntry.arguments?.getInt("id") ?: -1
                        MeasurementTypeDetailScreen(
                            navController = navController,
                            typeId = typeId,
                            sharedViewModel = sharedViewModel,
                            settingsViewModel = settingsViewModel
                        )
                    }
                    composable(Routes.BLUETOOTH_SETTINGS) {
                        BluetoothScreen(
                            sharedViewModel = sharedViewModel,
                            bluetoothViewModel = bluetoothViewModel
                        )
                    }
                    composable(Routes.CHART_SETTINGS) {
                        ChartSettingsScreen(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                            settingsViewModel = settingsViewModel
                        )
                    }
                    composable(Routes.DATA_MANAGEMENT_SETTINGS) {
                        DataManagementSettingsScreen(
                            navController = navController,
                            settingsViewModel = settingsViewModel
                        )
                    }
                    composable(Routes.ABOUT_SETTINGS) {
                        AboutScreen(
                            navController = navController,
                            sharedViewModel = sharedViewModel
                        )
                    }
                }
                // Box to fill the space behind the system navigation bar, if visible.
                // This prevents UI elements from being drawn under a translucent navigation bar,
                // ensuring consistent background color.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            WindowInsets.navigationBars // Get insets for the system navigation bar.
                                .asPaddingValues()
                                .calculateBottomPadding() // Calculate its height.
                        )
                        .background(Black) // Match TopAppBar color or general theme background.
                )
            }
        }
    }
}

/**
 * Composable function for a dropdown menu in the TopAppBar to switch users or navigate to user management.
 * This provides a dedicated UI element for user selection and management access.
 *
 * @param users List of available [User]s to display in the dropdown.
 * @param selectedUser The currently selected [User], or null if no user is selected.
 *                     This is used to highlight the current user in the list.
 * @param onUserSelected Callback invoked with the user's ID when a user is selected from the dropdown.
 * @param onManageUsersClicked Callback invoked when the "Manage Users" option is clicked,
 *                             typically to navigate to a user management screen.
 * @param modifier Optional [Modifier] for this composable, allowing for custom styling or layout.
 */
@Composable
fun UserDropdownAsAction(
    users: List<User>,
    selectedUser: User?,
    onUserSelected: (Int) -> Unit,
    onManageUsersClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) } // State to control dropdown visibility.

    if (users.isEmpty()) {
        return // Do not show the dropdown if there are no users.
    }

    Box(modifier = modifier) { // Box is used to anchor the DropdownMenu.
        IconButton(onClick = { expanded = true }) {
            val iconResource = selectedUser?.icon?.resource ?: IconResource.VectorResource(Icons.Filled.AccountCircle)

            when (iconResource) {
                is IconResource.VectorResource -> {
                    Icon(
                        imageVector = iconResource.imageVector,
                        contentDescription = null
                    )
                }
                is IconResource.PainterResource -> {
                    Image(
                        painter = painterResource(id = iconResource.id),
                        contentDescription = null
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false } // Close dropdown when clicked outside.
        ) {
            users.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user.name) },
                    onClick = {
                        onUserSelected(user.id)
                        expanded = false // Close dropdown after selection.
                    },
                    leadingIcon = { // Show a checkmark next to the currently selected user.
                        if (user.id == selectedUser?.id) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.content_desc_selected_user_indicator)
                            )
                        }
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) // Visual separator.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manage_users)) },
                onClick = {
                    onManageUsersClicked()
                    expanded = false // Close dropdown after selection.
                },
                leadingIcon = { // Icon for the "Manage Users" option.
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null // Decorative icon, as text already describes the action.
                    )
                }
            )
        }
    }
}
