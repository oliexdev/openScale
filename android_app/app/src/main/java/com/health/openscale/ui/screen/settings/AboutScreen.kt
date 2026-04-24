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

import android.R.attr.contentDescription
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.health.openscale.BuildConfig
import com.health.openscale.R
import com.health.openscale.core.usecase.MeasurementDemoUseCase
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.shared.SharedViewModel

/**
 * Composable function for the "About" screen.
 * Displays information about the application, project, license, and provides diagnostic tools.
 *
 * @param navController The NavController for navigation.
 * @param sharedViewModel The SharedViewModel for accessing shared data and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val projectHomepageUrl = "https://github.com/oliexdev/openScale"
    val licenseUrl = "https://www.gnu.org/licenses/gpl-3.0.html"

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(context.getString(R.string.about_screen_title))
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val launcherIconRes = when (BuildConfig.BUILD_TYPE) {
                "beta", "oss" -> R.drawable.ic_launcher_beta_foreground
                "debug" -> R.drawable.ic_launcher_dev_foreground
                else -> R.drawable.ic_launcher_foreground
            }

            Image(
                painter = painterResource(id = launcherIconRes),
                contentDescription = stringResource(R.string.app_logo_content_description),
                modifier = Modifier
                    .size(128.dp)
                    .padding(bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.app_name), // Using a string resource for app name display
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.version_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }

        // --- Project Information ---
        Text(
            text = stringResource(R.string.project_information_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            fontWeight = FontWeight.Bold
        )
        InfoListItem(
            headlineText = "olie.xdev",
            supportingText = stringResource(R.string.maintainer_label),
            leadingIconVector = Icons.Filled.Business,
            leadingIconContentDescription = stringResource(R.string.maintainer_icon_content_description)
        )
        InfoListItem(
            headlineText = stringResource(R.string.project_homepage_display),
            supportingText = stringResource(R.string.official_project_page_label),
            leadingIconVector = Icons.Filled.Home,
            leadingIconContentDescription = stringResource(R.string.homepage_icon_content_description),
            url = projectHomepageUrl,
            uriHandler = uriHandler
        )
        InfoListItem(
            headlineText = "GNU GPL v3.0 or newer",
            supportingText = stringResource(R.string.software_license_details_label),
            leadingIconVector = Icons.Filled.Copyright,
            leadingIconContentDescription = stringResource(R.string.license_icon_content_description),
            url = licenseUrl,
            uriHandler = uriHandler
        )


        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Danger Zone Title
            Text(
                text = stringResource(R.string.dev_tools_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                fontWeight = FontWeight.Bold
            )

            var selectedScenario by remember { mutableStateOf(MeasurementDemoUseCase.DemoScenario.TREND_PROGRESS) }
            var selectedRange by remember { mutableStateOf(MeasurementDemoUseCase.TimeRange.LAST_6_MONTHS) }
            var wipeExisting by remember { mutableStateOf(false) }
            var showConfirmDialog by remember { mutableStateOf(false) }

            // --- Scenario Dropdown ---
            var scenarioExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = scenarioExpanded,
                onExpandedChange = { scenarioExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dev_tools_scenario_label), color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(selectedScenario.getDisplayName(context), color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) },
                    leadingContent = { Icon(Icons.Default.Timeline, null, tint = MaterialTheme.colorScheme.error) },
                    trailingContent = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = scenarioExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).clickable { scenarioExpanded = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ExposedDropdownMenu(expanded = scenarioExpanded, onDismissRequest = { scenarioExpanded = false }) {
                    MeasurementDemoUseCase.DemoScenario.entries.forEach { scenario ->
                        DropdownMenuItem(
                            text = { Text(scenario.getDisplayName(context), color = MaterialTheme.colorScheme.error) },
                            onClick = { selectedScenario = scenario; scenarioExpanded = false }
                        )
                    }
                }
            }

            // --- Time Range Dropdown ---
            var rangeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = rangeExpanded,
                onExpandedChange = { rangeExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dev_tools_time_range_label), color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(selectedRange.getDisplayName(context), color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) },
                    leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.error) },
                    trailingContent = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rangeExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).clickable { rangeExpanded = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ExposedDropdownMenu(expanded = rangeExpanded, onDismissRequest = { rangeExpanded = false }) {
                    MeasurementDemoUseCase.TimeRange.entries.forEach { range ->
                        DropdownMenuItem(
                            text = { Text(range.getDisplayName(context), color = MaterialTheme.colorScheme.error) },
                            onClick = { selectedRange = range; rangeExpanded = false }
                        )
                    }
                }
            }

            // --- Wipe Toggle ---
            ListItem(
                headlineContent = { Text(stringResource(R.string.dev_tools_wipe_data_label), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                trailingContent = {
                    Switch(
                        checked = wipeExisting,
                        onCheckedChange = { wipeExisting = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.error,
                            checkedTrackColor = MaterialTheme.colorScheme.errorContainer
                        )
                    )
                },
                modifier = Modifier.clickable { wipeExisting = !wipeExisting },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- Generate Button ---
            TextButton(
                onClick = { showConfirmDialog = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.dev_tools_generate_button).uppercase(), fontWeight = FontWeight.Bold)
            }

            // --- Confirmation Dialog ---
            if (showConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showConfirmDialog = false },
                    icon = { Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text(stringResource(R.string.dev_tools_dialog_title)) },
                    text = {
                        Text(if (wipeExisting) stringResource(R.string.dev_tools_dialog_wipe_warning)
                        else stringResource(R.string.dev_tools_dialog_add_warning))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                sharedViewModel.generateDemoData(selectedScenario, selectedRange, wipeExisting)
                                showConfirmDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(stringResource(R.string.dev_tools_dialog_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmDialog = false }) { Text(stringResource(R.string.cancel_button)) }
                    }
                )
            }
        }
    }
}

/**
 * A private composable function to display an information list item.
 * It can show a headline, supporting text, a leading icon, and can be clickable if a URL is provided.
 *
 * @param headlineText The main text of the list item.
 * @param supportingText Optional supporting text displayed below the headline.
 * @param leadingIconVector Optional vector graphic for the leading icon.
 * @param leadingIconContentDescription Content description for the leading icon, for accessibility.
 * @param url Optional URL to open when the item is clicked.
 * @param uriHandler Optional UriHandler to handle opening the URL.
 */
@Composable
private fun InfoListItem(
    headlineText: String,
    supportingText: String? = null,
    leadingIconVector: ImageVector? = null,
    leadingIconContentDescription: String?,
    url: String? = null,
    uriHandler: UriHandler? = null
) {
    val itemModifier = if (url != null && uriHandler != null) {
        Modifier.clickable {
            try {
                uriHandler.openUri(url)
            } catch (e: Exception) {
                // Log the error, a Snackbar could also be shown to the user if desired.
                LogManager.e("AboutScreen", "Failed to open URL: $url", e)
                // Consider showing a Snackbar to the user:
                // scope.launch { sharedViewModel.showSnackbar(context.getString(R.string.error_opening_link)) }
            }
        }
    } else {
        Modifier
    }

    ListItem(
        headlineContent = { Text(headlineText, style = MaterialTheme.typography.bodyMedium) },
        modifier = itemModifier,
        supportingContent = if (supportingText != null) { { Text(supportingText, style = MaterialTheme.typography.bodySmall) } } else null,
        leadingContent = if (leadingIconVector != null) {
            {
                Icon(
                    imageVector = leadingIconVector,
                    contentDescription = leadingIconContentDescription
                )
            }
        } else null,
        trailingContent = if (url != null) {
            {
                Icon(
                    Icons.AutoMirrored.Filled.Launch,
                    contentDescription = stringResource(R.string.open_link_content_description), // Specific description
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent // Makes the list item background transparent
        )
    )
}
