package com.health.openscale.ui.screen.settings

import androidx.activity.result.launch
import androidx.navigation.NavController

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.ui.screen.SharedViewModel
import kotlinx.coroutines.launch

@Composable
fun ChartSettingsScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel
) {
    val chartSettingsScreenTitle = stringResource(R.string.settings_item_chart)
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(chartSettingsScreenTitle)
    }

    val coroutineScope = rememberCoroutineScope()

    val showDataPoints by sharedViewModel.userSettingRepository.showChartDataPoints.collectAsState(true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.setting_show_chart_points),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = showDataPoints,
                onCheckedChange = { newValue ->
                    coroutineScope.launch {
                        sharedViewModel.userSettingRepository.setShowChartDataPoints(newValue)
                    }
                }
            )
        }
    }
}
