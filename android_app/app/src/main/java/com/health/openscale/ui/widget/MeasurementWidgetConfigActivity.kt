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
package com.health.openscale.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.usecase.MeasurementQueryUseCases
import com.health.openscale.ui.components.RoundMeasurementIcon
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WidgetTheme { LIGHT, DARK }

/** Shared widget preference keys */
object WidgetPrefs {
    val KEY_TYPE  = androidx.datastore.preferences.core.intPreferencesKey("widget_selectedTypeId")
    val KEY_THEME = androidx.datastore.preferences.core.intPreferencesKey("widget_selectedTheme")
    val KEY_TRIGGER = androidx.datastore.preferences.core.intPreferencesKey("widget_trigger")

}

@AndroidEntryPoint
class MeasurementWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the appWidgetId from host (Launcher)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Immediately set result to CANCELED, will be changed to OK when user confirms
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )

        setContent {
            MaterialTheme {
                WidgetConfigScreen(
                    onCancel = { finish() },
                    onConfirm = { selectedTypeId, selectedTheme ->
                        saveSelectionAndFinish(appWidgetId, selectedTypeId, selectedTheme)
                    }
                )
            }
        }
    }

    /** Save user choice, update widget instance immediately, and return result to host */
    private fun saveSelectionAndFinish(
        appWidgetId: Int?,
        selectedTypeId: Int?,
        selectedTheme: WidgetTheme
    ) {
        val ctx = this
        lifecycleScope.launch {
            val gm = GlanceAppWidgetManager(ctx)
            val glanceId = gm.getGlanceIds(MeasurementWidget::class.java)
                .firstOrNull { it != null && gm.getAppWidgetId(it) == appWidgetId }

            if (glanceId == null || appWidgetId == null ||
                appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
            ) {
                setResult(RESULT_CANCELED)
                finish()
                return@launch
            }

            // Write per-widget Glance state
            updateAppWidgetState(
                context = ctx,
                definition = PreferencesGlanceStateDefinition,
                glanceId = glanceId
            ) { prefs ->
                prefs.toMutablePreferences().apply {
                    if (selectedTypeId != null) {
                        this[WidgetPrefs.KEY_TYPE] = selectedTypeId
                    } else {
                        this.remove(WidgetPrefs.KEY_TYPE)
                    }
                    this[WidgetPrefs.KEY_THEME] = selectedTheme.ordinal
                    this[WidgetPrefs.KEY_TRIGGER] = 0
                }
            }

            // Trigger immediate update of this widget instance
            MeasurementWidget().update(ctx, glanceId)

            // Return OK to host
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }
}

@HiltViewModel
class MeasurementWidgetConfigViewModel @Inject constructor(
    query: MeasurementQueryUseCases
) : androidx.lifecycle.ViewModel() {
    // Only expose enabled numeric measurement types
    val types = query.getAllMeasurementTypes()
        .map {
            it.filter { t -> t.isEnabled && (t.inputType == InputFieldType.FLOAT || t.inputType == InputFieldType.INT) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    onCancel: () -> Unit,
    onConfirm: (selectedTypeId: Int?, selectedTheme: WidgetTheme) -> Unit
) {
    val vm: MeasurementWidgetConfigViewModel = hiltViewModel()
    val types by vm.types.collectAsState()

    var selectedId by remember { mutableStateOf<Int?>(null) }
    var selectedTheme by remember { mutableStateOf(WidgetTheme.LIGHT) }

    // Default selection: WEIGHT if available, else first entry
    LaunchedEffect(types) {
        selectedId = types.firstOrNull { it.key == MeasurementTypeKey.WEIGHT }?.id
            ?: types.firstOrNull()?.id
        selectedTheme = WidgetTheme.LIGHT
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.measurement_type_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_button))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onConfirm(selectedId, selectedTheme) },
                        enabled = selectedId != null
                    ) {
                        Icon(Icons.Default.Done, contentDescription = stringResource(R.string.confirm_button))
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            ThemePicker(
                selected = selectedTheme,
                onSelected = { selectedTheme = it },
                modifier = Modifier.fillMaxWidth()
            )
            Divider(Modifier.padding(vertical = 12.dp))
            if (types.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(R.string.no_entries_found))
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(types, key = { it.id }) { type ->
                        TypeRow(
                            type = type,
                            selected = selectedId == type.id,
                            onClick = { selectedId = type.id }
                        )
                        HorizontalDivider(
                            Modifier,
                            DividerDefaults.Thickness,
                            DividerDefaults.color
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePicker(
    selected: WidgetTheme,
    onSelected: (WidgetTheme) -> Unit,
    modifier: Modifier = Modifier
) {
    // Segmented buttons to pick Light/Dark
    val options = listOf(WidgetTheme.LIGHT, WidgetTheme.DARK)
    SingleChoiceSegmentedButtonRow(modifier = modifier.padding(horizontal = 16.dp)) {
        options.forEachIndexed { index, item ->
            val label = when (item) {
                WidgetTheme.LIGHT -> stringResource(R.string.theme_light)
                WidgetTheme.DARK  -> stringResource(R.string.theme_dark)
            }
            SegmentedButton(
                selected = selected == item,
                onClick = { onSelected(item) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { if (selected == item) Icon(Icons.Default.Check, null) }
            ) { Text(text = label) }
        }
    }
}

@Composable
private fun TypeRow(type: MeasurementType, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Use existing RoundMeasurementIcon and colorize background with type.color if available
        RoundMeasurementIcon(
            icon = type.icon.resource,
            size = 24.dp,
            backgroundTint = if (type.color != 0) Color(type.color) else MaterialTheme.colorScheme.secondaryContainer
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = type.getDisplayName(LocalContext.current))
            Text(
                text = type.unit.displayName,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}
