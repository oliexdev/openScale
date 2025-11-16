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

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.health.openscale.MainActivity
import com.health.openscale.R
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.UserFacade
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import androidx.compose.ui.graphics.Color
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.state.updateAppWidgetState
import com.health.openscale.core.data.IconResource
import kotlin.collections.firstOrNull

class MeasurementWidget : GlanceAppWidget() {
    // Enable currentState<Preferences>() inside provideContent
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    companion object {
        /** Recompose all widget instances. */
        suspend fun refreshAll(context: Context) {
            withContext(Dispatchers.IO) {
                val gm = GlanceAppWidgetManager(context)
                val ids = gm.getGlanceIds(MeasurementWidget::class.java)
                if (ids.isEmpty()) return@withContext

                ids.forEach { glanceId ->
                    updateAppWidgetState(
                        context = context,
                        glanceId = glanceId
                    ) { prefs ->
                        prefs[WidgetPrefs.KEY_TRIGGER] = (prefs[WidgetPrefs.KEY_TRIGGER] ?: 0) + 1
                    }
                    // update triggers provideGlance/provideContent recomposition
                    MeasurementWidget().update(context, glanceId)
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Preload strings (no stringResource in Glance content)
        val txtNoUser = context.getString(R.string.no_user_selected_title)
        val txtNoMeas = context.getString(R.string.no_measurements_title)
        val txtNA = context.getString(R.string.not_available)

        // Hilt entry points once, outside of Composable
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        }.getOrNull()
        val userFacade = entry?.userFacade()
        val measurementFacade = entry?.measurementFacade()

        provideContent {
            GlanceTheme {
                // 1) Read per-instance Glance state
                val prefs = currentState<Preferences>()
                val selectedTypeId = prefs[WidgetPrefs.KEY_TYPE]
                val selectedThemeIx = prefs[WidgetPrefs.KEY_THEME] ?: WidgetTheme.LIGHT.ordinal
                val trigger = prefs[WidgetPrefs.KEY_TRIGGER]

                // Text colors based on selected Light/Dark theme
                val isDark = selectedThemeIx == WidgetTheme.DARK.ordinal
                val textColor = if (isDark)
                    ColorProvider(Color(0xFF111111))
                else
                    ColorProvider(Color(0xFFECECEC))
                val subTextColor = if (isDark)
                    ColorProvider(Color(0xFF111111))
                else
                    ColorProvider(Color(0xFFECECEC))

                // 2) UI state
                var uiPayload by remember { mutableStateOf<DisplayData?>(null) }
                var userMissing by remember { mutableStateOf(false) }

                // 3) Reload when state changes
                LaunchedEffect(trigger,selectedTypeId, selectedThemeIx) {
                    uiPayload = null
                    userMissing = false

                    withContext(Dispatchers.IO) {
                        val userId: Int? = runCatching { userFacade?.observeSelectedUser()?.first()?.id }.getOrNull()
                        if (userId == null || measurementFacade == null) {
                            userMissing = (userId == null)
                            return@withContext
                        }

                        val allTypes = measurementFacade.getAllMeasurementTypes().first()
                        val all = measurementFacade.getMeasurementsForUser(userId).first()

                        val targetType = selectedTypeId?.let { sel -> allTypes.firstOrNull { it.id == sel } }
                            ?: allTypes.firstOrNull { it.key == MeasurementTypeKey.WEIGHT }
                            ?: allTypes.firstOrNull { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) }

                        uiPayload = targetType?.let { t ->
                            val valuesDesc = all.mapNotNull { mwv ->
                                val v = mwv.values.firstOrNull { it.type.id == t.id } ?: return@mapNotNull null
                                val num = v.value.floatValue ?: v.value.intValue?.toFloat() ?: return@mapNotNull null
                                mwv.measurement.timestamp to num
                            }.sortedByDescending { it.first }

                            if (valuesDesc.isEmpty()) null else {
                                val current = valuesDesc[0].second
                                val previous = valuesDesc.getOrNull(1)?.second

                                val df = DecimalFormat("#0.0")
                                val dfSigned = DecimalFormat("+#0.0;-#0.0")

                                val unitLabel = t.unit.displayName
                                val valueWithUnit = buildString {
                                    append(df.format(current))
                                    if (unitLabel.isNotBlank()) append(" ").append(unitLabel)
                                }

                                val deltaArrow = previous?.let { prev ->
                                    when {
                                        current > prev + 1e-4 -> "↗"
                                        current < prev - 1e-4 -> "↘"
                                        else -> ""
                                    }
                                }
                                val deltaText = previous?.let {
                                    val signed = dfSigned.format(current - it)
                                    listOfNotNull(deltaArrow?.takeIf { it.isNotBlank() }, signed).joinToString(" ") +
                                            if (unitLabel.isNotBlank()) " $unitLabel" else ""
                                } ?: ""

                                val implausiblePercent = unitLabel == "%" && (current < 0f || current > 100f)
                                val (symbol, evalState) = when {
                                    implausiblePercent -> "!" to EvaluationState.UNDEFINED
                                    previous == null   -> "●" to EvaluationState.UNDEFINED
                                    current > previous + 1e-4 -> "▲" to EvaluationState.HIGH
                                    current < previous - 1e-4 -> "▼" to EvaluationState.LOW
                                    else -> "●" to EvaluationState.NORMAL
                                }

                                DisplayData(
                                    label = t.getDisplayName(context),
                                    icon = t.icon,
                                    badgeColor = if (t.color != 0)
                                        ColorProvider(Color(t.color)) else null,
                                    symbol = symbol,
                                    evaluationState = evalState,
                                    valueWithUnit = valueWithUnit,
                                    deltaText = deltaText
                                )
                            }
                        }
                    }
                }

                val themeColors = GlanceTheme.colors
                val launch = Intent(context, MainActivity::class.java)

                val size = LocalSize.current

                val scaleFactor = minOf(
                    size.width.value / 110f,
                    size.height.value / 40f,
                    4f
                ).coerceAtLeast(0.5f)

                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .clickable(actionStartActivity(launch)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        userMissing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = txtNoUser, style = TextStyle(color = textColor))
                        }
                        uiPayload == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = txtNoMeas, style = TextStyle(color = textColor))
                            Text(text = txtNA, style = TextStyle(color = subTextColor))
                        }
                        else -> ValueWithDeltaRow(
                            icon = uiPayload!!.icon,
                            iconContentDescription = context.getString(
                                R.string.measurement_type_icon_desc, uiPayload!!.label
                            ),
                            label = uiPayload!!.label,
                            symbol = uiPayload!!.symbol,
                            evaluationState = uiPayload!!.evaluationState,
                            valueWithUnit = uiPayload!!.valueWithUnit,
                            deltaText = uiPayload!!.deltaText,
                            circleColor = uiPayload!!.badgeColor ?: themeColors.secondary,
                            textColor = textColor,
                            subTextColor = subTextColor,
                            symbolColor = ColorProvider(uiPayload!!.evaluationState.toColor()),
                            scaleFactor = scaleFactor
                        )
                    }
                }
            }
        }
    }
}

class MeasurementWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MeasurementWidget()
}

/* Hilt entry point */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun userFacade(): UserFacade
    fun measurementFacade(): MeasurementFacade
}

/* Payload & UI */
private data class DisplayData(
    val label: String,
    val icon: MeasurementTypeIcon,
    val badgeColor: ColorProvider?,
    val symbol: String,
    val evaluationState: EvaluationState,
    val valueWithUnit: String,
    val deltaText: String
)

@Composable
private fun ValueWithDeltaRow(
    icon: MeasurementTypeIcon,
    iconContentDescription: String,
    label: String,
    symbol: String,
    evaluationState: EvaluationState,
    valueWithUnit: String,
    deltaText: String,
    circleColor: ColorProvider,
    textColor: ColorProvider,
    subTextColor: ColorProvider,
    symbolColor: ColorProvider,
    scaleFactor : Float
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val fontSize = 8.sp
        GlanceRoundMeasurementIcon(icon, iconContentDescription, 21.dp * scaleFactor, circleColor, R.drawable.ic_weight)
        Spacer(GlanceModifier.size(10.dp * scaleFactor))
        Column {
            Text(text = label, style = TextStyle(fontSize = fontSize * scaleFactor, fontWeight = FontWeight.Medium, color = textColor))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = valueWithUnit, style = TextStyle(fontSize = fontSize * scaleFactor, color = textColor))
                Spacer(GlanceModifier.size(6.dp*scaleFactor))
                Text(text = symbol, style = TextStyle(fontSize = fontSize * scaleFactor, color = symbolColor))
            }
            if (deltaText.isNotBlank()) {
                Text(text = deltaText, style = TextStyle(fontSize = fontSize * scaleFactor, color = subTextColor))
            }
        }
    }
}


@Composable
private fun GlanceRoundMeasurementIcon(
    icon: MeasurementTypeIcon,
    contentDescription: String,
    size: Dp,
    circleColor: ColorProvider,
    @DrawableRes fallbackDrawable: Int
) {
    val resId = when (val r = icon.resource) {
        is IconResource.PainterResource -> r.id
        is IconResource.VectorResource -> fallbackDrawable
    }
    Box(
        modifier = GlanceModifier
            .size(size + 24.dp)
            .cornerRadius((size + 18.dp) / 2f)
            .background(circleColor),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(resId),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(size)
        )
    }
}
