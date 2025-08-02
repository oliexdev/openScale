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

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.ui.graphics.vector.ImageVector
import com.health.openscale.R
import com.health.openscale.ui.navigation.Routes.NO_TITLE_RESOURCE_ID

object Routes {
    // Main screens
    const val OVERVIEW = "overview"
    const val GRAPH = "graph"
    const val TABLE = "table"
    const val STATISTICS = "statistics"
    const val SETTINGS = "settings"

    const val MEASUREMENT_DETAIL = "measurementDetail" // Not a main navigation item, but a route

    // Sub-pages (Settings Subgraph)
    const val GENERAL_SETTINGS = "settings/general"
    const val USER_SETTINGS = "settings/users"
    const val USER_DETAIL = "settings/userDetail"
    const val MEASUREMENT_TYPES = "settings/types"
    const val MEASUREMENT_TYPE_DETAIL = "settings/typeDetail"
    const val BLUETOOTH_SETTINGS = "settings/bluetooth"
    const val DATA_MANAGEMENT_SETTINGS = "settings/dataManagement"
    const val ABOUT_SETTINGS = "settings/about"

    // Special constant for no title
    const val NO_TITLE_RESOURCE_ID = 0

    // Routes with parameters
    fun userDetail(userId: Int?) = "$USER_DETAIL?id=${userId ?: -1}"
    fun measurementTypeDetail(typeId: Int?) = "$MEASUREMENT_TYPE_DETAIL?id=${typeId ?: -1}"

    fun measurementDetail(measurementId: Int?, userId: Int?): String =
        "$MEASUREMENT_DETAIL?measurementId=${measurementId ?: -1}&userId=$userId"

    /**
     * Gets the string resource ID for the title of a given route.
     * Intended for main navigation items displayed in the TopAppBar or NavigationDrawer.
     *
     * @param route The route string.
     * @return The string resource ID for the title, or [NO_TITLE_RESOURCE_ID] if no title is defined.
     */
    @StringRes
    fun getTitleResourceId(route: String?): Int = when {
        route == null -> NO_TITLE_RESOURCE_ID
        route.startsWith(OVERVIEW) -> R.string.route_title_overview
        route.startsWith(GRAPH) -> R.string.route_title_graph
        route.startsWith(TABLE) -> R.string.route_title_table
        route.startsWith(STATISTICS) -> R.string.route_title_statistics
        route.startsWith(SETTINGS) -> R.string.route_title_settings
        else -> NO_TITLE_RESOURCE_ID // No specific title for other routes via this function
    }

    fun getIconForRoute(route: String): ImageVector {
        return when (route) {
            OVERVIEW -> Icons.Filled.Home
            GRAPH -> Icons.AutoMirrored.Filled.ShowChart
            TABLE -> Icons.Filled.TableRows
            STATISTICS -> Icons.Filled.Analytics
            SETTINGS -> Icons.Filled.Settings
            else -> Icons.Filled.QuestionMark // Default icon for routes not explicitly handled
        }
    }
}
