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
package com.health.openscale.ui.screen.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.health.openscale.core.database.UserPreferenceKeys
import com.health.openscale.ui.screen.SharedViewModel
import com.health.openscale.ui.screen.components.LineChart
import com.health.openscale.ui.screen.components.provideFilterTopBarAction


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(sharedViewModel: SharedViewModel) {
    val isLoading by sharedViewModel.isBaseDataLoading.collectAsState()
    val allMeasurementsWithValuesRaw by sharedViewModel.allMeasurementsForSelectedUser.collectAsState()

    val timeFilterAction = provideFilterTopBarAction(
        sharedViewModel = sharedViewModel,
        screenContextName = UserPreferenceKeys.GRAPH_SCREEN_CONTEXT
    )

    LaunchedEffect(timeFilterAction) {
        sharedViewModel.setTopBarTitle("Graph")

        val actions = mutableListOf<SharedViewModel.TopBarAction>()
        timeFilterAction?.let { actions.add(it) }

        sharedViewModel.setTopBarActions(actions)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading && allMeasurementsWithValuesRaw.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LineChart(
                modifier = Modifier.fillMaxSize(),
                sharedViewModel = sharedViewModel,
                screenContextName = UserPreferenceKeys.GRAPH_SCREEN_CONTEXT,
                showFilterControls = true
            )
        }
    }
}

