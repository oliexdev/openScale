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
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import com.health.openscale.MainActivity
import com.health.openscale.R
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.IconResource
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
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.utils.LocaleUtils

// ---------------------------------------------------------------------------
// Widget entry point
// ---------------------------------------------------------------------------

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun userFacade(): UserFacade
    fun measurementFacade(): MeasurementFacade
}

// ---------------------------------------------------------------------------
// Display model — all data pre-computed on IO thread, ready for Glance UI
// ---------------------------------------------------------------------------

private data class DisplayData(
    val label: String,
    val icon: MeasurementTypeIcon,
    val badgeColor: ColorProvider?,
    val symbolRes: Int?,
    val symbolSize: Dp,
    val symbolColor: ColorProvider,
    val valueWithUnit: String,
    val deltaText: String,
    val deltaArrowRes: Int?,
)

// ---------------------------------------------------------------------------
// Widget
// ---------------------------------------------------------------------------

class MeasurementWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    companion object {
        /** Bump trigger key on all instances to force recomposition. */
        suspend fun refreshAll(context: Context) {
            withContext(Dispatchers.IO) {
                val gm = GlanceAppWidgetManager(context)
                val ids = gm.getGlanceIds(MeasurementWidget::class.java)
                if (ids.isEmpty()) return@withContext
                ids.forEach { glanceId ->
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[WidgetPrefs.KEY_TRIGGER] = (prefs[WidgetPrefs.KEY_TRIGGER] ?: 0) + 1
                    }
                    // update triggers provideGlance/provideContent recomposition
                    MeasurementWidget().update(context, glanceId)
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Strings must be loaded here — stringResource() is unavailable in Glance
        val txtNoUser = context.getString(R.string.no_user_selected_title)
        val txtNoMeas = context.getString(R.string.no_measurements_title)
        val txtNA     = context.getString(R.string.not_available)

        // Resolve Hilt entry point once per provideGlance call, not per recomposition
        val entry = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext, WidgetEntryPoint::class.java
            )
        }.getOrNull()
        val userFacade        = entry?.userFacade()
        val measurementFacade = entry?.measurementFacade()

        provideContent {
            GlanceTheme {
                val prefs           = currentState<Preferences>()
                val selectedTypeId  = prefs[WidgetPrefs.KEY_TYPE]
                val selectedThemeIx = prefs[WidgetPrefs.KEY_THEME] ?: WidgetTheme.LIGHT.ordinal
                val trigger         = prefs[WidgetPrefs.KEY_TRIGGER]

                // Derive text colors from theme preference
                val isDark = selectedThemeIx == WidgetTheme.DARK.ordinal
                val baseColor = if (isDark) Color(0xFF111111) else Color(0xFFECECEC)
                val textColor = ColorProvider(day = baseColor, night = baseColor)
                val subTextColor = ColorProvider(day = baseColor, night = baseColor)

                var uiPayload   by remember { mutableStateOf<DisplayData?>(null) }
                var userMissing by remember { mutableStateOf(false) }

                // Reload data whenever trigger, type selection, or theme changes
                LaunchedEffect(trigger, selectedTypeId, selectedThemeIx) {
                    uiPayload   = null
                    userMissing = false
                    uiPayload   = withContext(Dispatchers.IO) {
                        loadDisplayData(context, userFacade, measurementFacade, selectedTypeId)
                            .also { if (it == null) userMissing = (userFacade == null) }
                    }
                }

                val themeColors = GlanceTheme.colors
                val scaleFactor = with(LocalSize.current) {
                    minOf(width.value / 110f, height.value / 40f, 4f).coerceAtLeast(0.5f)
                }

                Box(
                    modifier           = GlanceModifier
                        .fillMaxSize()
                        .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                    contentAlignment   = Alignment.Center,
                ) {
                    when {
                        userMissing ->
                            Text(text = txtNoUser, style = TextStyle(color = textColor))

                        uiPayload == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = txtNoMeas, style = TextStyle(color = textColor))
                            Text(text = txtNA,     style = TextStyle(color = subTextColor))
                        }

                        else -> {
                            val payload = uiPayload!!
                            ValueWithDeltaRow(
                                icon                   = payload.icon,
                                iconContentDescription = context.getString(
                                    R.string.measurement_type_icon_desc, payload.label
                                ),
                                label         = payload.label,
                                symbolRes     = payload.symbolRes,
                                symbolSize    = payload.symbolSize * scaleFactor,
                                symbolColor   = payload.symbolColor,
                                valueWithUnit = payload.valueWithUnit,
                                deltaText     = payload.deltaText,
                                deltaArrowRes = payload.deltaArrowRes,
                                circleColor   = payload.badgeColor ?: themeColors.secondary,
                                textColor     = textColor,
                                subTextColor  = subTextColor,
                                scaleFactor   = scaleFactor,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Data loading — pure IO, no Composable context needed
// ---------------------------------------------------------------------------

/**
 * Loads and pre-computes all data needed to render the widget.
 * Returns null if the user or measurement data is unavailable.
 */
private suspend fun loadDisplayData(
    context: Context,
    userFacade: UserFacade?,
    measurementFacade: MeasurementFacade?,
    selectedTypeId: Int?,
): DisplayData? {
    val userId = runCatching { userFacade?.observeSelectedUser()?.first()?.id }.getOrNull()
        ?: return null
    if (measurementFacade == null) return null

    val userEvalContext = runCatching {
        userFacade?.observeUserEvaluationContext()?.first()
    }.getOrNull()

    val allTypes   = measurementFacade.getAllMeasurementTypes().first()
    val all        = measurementFacade.getMeasurementsForUser(userId).first()

    // Resolve target type: explicit selection → weight → first enabled numeric
    val targetType = selectedTypeId?.let { sel -> allTypes.firstOrNull { it.id == sel } }
        ?: allTypes.firstOrNull { it.key == MeasurementTypeKey.WEIGHT }
        ?: allTypes.firstOrNull { it.isEnabled && (it.inputType == InputFieldType.FLOAT || it.inputType == InputFieldType.INT) }
        ?: return null

    // Extract numeric values for the target type, newest first
    val valuesDesc = all.mapNotNull { mwv ->
        val v   = mwv.values.firstOrNull { it.type.id == targetType.id } ?: return@mapNotNull null
        val num = v.value.floatValue ?: v.value.intValue?.toFloat() ?: return@mapNotNull null
        mwv.measurement.timestamp to num
    }.sortedByDescending { it.first }

    if (valuesDesc.isEmpty()) return null

    val current   = valuesDesc[0].second
    val previous  = valuesDesc.getOrNull(1)?.second
    val timestamp = valuesDesc[0].first

    val valueWithUnit = LocaleUtils.formatValueForDisplay(
        value = current.toString(),
        unit  = targetType.unit,
    )

    // Evaluate against clinical reference bands when context is available
    val evalResult = if (userEvalContext != null) {
        runCatching {
            measurementFacade.evaluate(targetType, current, userEvalContext, timestamp)
        }.getOrNull()
    } else null

    // Match Overview flagging logic exactly
    val noAgeBand = evalResult?.let { it.lowLimit < 0f || it.highLimit < 0f } ?: false
    val plausible = measurementFacade.plausiblePercentRangeFor(targetType.key)
    val outOfPlausibleRange = plausible?.let { current < it.start || current > it.endInclusive }
        ?: (targetType.unit == UnitType.PERCENT && (current < 0f || current > 100f))

    val flagged   = noAgeBand || outOfPlausibleRange
    val evalState = evalResult?.state ?: EvaluationState.UNDEFINED

    // Match Overview icon/size/color logic exactly
    val symbolRes: Int? = if (flagged) R.drawable.ic_widget_warning
    else when (evalState) {
        EvaluationState.LOW, EvaluationState.NORMAL, EvaluationState.HIGH -> R.drawable.ic_widget_circle
        EvaluationState.UNDEFINED -> null
    }
    val symbolSize  = if (flagged) 10.dp else 6.dp
    val sColor = if (flagged) Color(0xFFB00020) else evalState.toColor()
    val symbolColor = ColorProvider(day = sColor, night = sColor)

    // Delta arrow matches Overview trend arrow direction
    val deltaArrowRes = previous?.let {
        when {
            current > it + 1e-4f -> R.drawable.ic_widget_arrow_up
            current < it - 1e-4f -> R.drawable.ic_widget_arrow_down
            else                 -> null
        }
    }
    val deltaText = previous?.let {
        LocaleUtils.formatValueForDisplay(
            value        = (current - it).toString(),
            unit         = targetType.unit,
            includeSign  = true,
        )
    } ?: ""

    return DisplayData(
        label         = targetType.getDisplayName(context),
        icon          = targetType.icon,
        badgeColor = if (targetType.color != 0) {
            ColorProvider(day = Color(targetType.color), night = Color(targetType.color))
        } else null,
        symbolRes     = symbolRes,
        symbolSize    = symbolSize,
        symbolColor   = symbolColor,
        valueWithUnit = valueWithUnit,
        deltaText     = deltaText,
        deltaArrowRes = deltaArrowRes,
    )
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

@Composable
private fun ValueWithDeltaRow(
    icon: MeasurementTypeIcon,
    iconContentDescription: String,
    label: String,
    symbolRes: Int?,
    symbolSize: Dp,
    symbolColor: ColorProvider,
    valueWithUnit: String,
    deltaText: String,
    deltaArrowRes: Int?,
    circleColor: ColorProvider,
    textColor: ColorProvider,
    subTextColor: ColorProvider,
    scaleFactor: Float,
) {
    val fontSize = 8.sp
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlanceRoundMeasurementIcon(
            icon               = icon,
            contentDescription = iconContentDescription,
            size               = 21.dp * scaleFactor,
            circleColor        = circleColor,
            fallbackDrawable   = R.drawable.ic_widget_warning,
        )
        Spacer(GlanceModifier.size(10.dp * scaleFactor))
        Column {
            Text(
                text  = label,
                style = TextStyle(fontSize = fontSize * scaleFactor, fontWeight = FontWeight.Medium, color = textColor),
            )
            // Value row with optional evaluation state icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = valueWithUnit,
                    style = TextStyle(fontSize = fontSize * scaleFactor, color = textColor),
                )
                if (symbolRes != null) {
                    Spacer(GlanceModifier.size(4.dp * scaleFactor))
                    Image(
                        provider     = ImageProvider(symbolRes),
                        contentDescription = null,
                        modifier     = GlanceModifier.size(symbolSize),
                        colorFilter  = ColorFilter.tint(symbolColor),
                    )
                }
            }
            // Delta row with optional trend arrow — only shown when a previous value exists
            if (deltaText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (deltaArrowRes != null) {
                        Image(
                            provider           = ImageProvider(deltaArrowRes),
                            contentDescription = null,
                            modifier           = GlanceModifier.size(8.dp * scaleFactor),
                            colorFilter        = ColorFilter.tint(subTextColor),
                        )
                        Spacer(GlanceModifier.size(3.dp * scaleFactor))
                    }
                    Text(
                        text  = deltaText,
                        style = TextStyle(fontSize = fontSize * scaleFactor, color = subTextColor),
                    )
                }
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
    fallbackDrawable: Int,
) {
    // VectorResource cannot be rendered in Glance — fall back to a static drawable
    val resId = when (val r = icon.resource) {
        is IconResource.PainterResource -> r.id
        is IconResource.VectorResource  -> fallbackDrawable
    }
    Box(
        modifier         = GlanceModifier
            .size(size + 24.dp)
            .cornerRadius((size + 18.dp) / 2f)
            .background(circleColor),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider           = ImageProvider(resId),
            contentDescription = contentDescription,
            modifier           = GlanceModifier.size(size),
        )
    }
}

// ---------------------------------------------------------------------------
// Receiver
// ---------------------------------------------------------------------------

class MeasurementWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MeasurementWidget()
}