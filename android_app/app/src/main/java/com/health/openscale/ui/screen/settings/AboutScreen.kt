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

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.SharedViewModel

/**
 * Composable function for the "About" screen.
 * Displays information about the application, project, license, and provides diagnostic tools.
 *
 * @param navController The NavController for navigation.
 * @param sharedViewModel The SharedViewModel for accessing shared data and actions.
 */
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
            Image(
                painter = if (BuildConfig.BUILD_TYPE == "beta") painterResource(id = R.drawable.ic_launcher_beta_foreground) else painterResource(id = R.drawable.ic_launcher_foreground) ,
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
