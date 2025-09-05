package com.health.openscale.ui.components

import android.R.attr.resource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.health.openscale.core.data.IconResource
import com.health.openscale.core.data.MeasurementTypeIcon

/**
 * Displays a [MeasurementTypeIcon] directly.
 * This is the core component for rendering the icon graphic.
 *
 * @param icon The [MeasurementTypeIcon] enum constant to display.
 * @param modifier [Modifier] to be applied to the Icon composable.
 * @param size The size of the icon graphic.
 * @param tint The tint color for the icon graphic.
 */
@Composable
fun MeasurementIcon(
    icon: IconResource,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current
) {
    when (icon) {
        is IconResource.PainterResource -> {
            Icon(
                painter = painterResource(id = icon.id),
                contentDescription = icon.id.toString(),
                modifier = modifier.size(size),
                tint = tint
            )
        }
        is IconResource.VectorResource -> {
            Icon(
                imageVector = icon.imageVector,
                contentDescription = icon.imageVector.name,
                modifier = modifier.size(size),
                tint = tint
            )
        }
    }
}

/**
 * Displays a [MeasurementTypeIcon] centered within a circular background.
 *
 * @param icon The [MeasurementTypeIcon] enum constant to display.
 * @param modifier [Modifier] to be applied to the outer Box that forms the circle.
 * @param size The size of the actual icon graphic itself (e.g., 24.dp).
 * @param backgroundTint The background color of the circle. Defaults to `MaterialTheme.colorScheme.surfaceVariant`.
 * @param iconTint The tint color for the icon graphic. Defaults to Black.
 */
@Composable
fun RoundMeasurementIcon(
    icon: IconResource,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    backgroundTint : Color = MaterialTheme.colorScheme.surfaceVariant,
    iconTint: Color = Color.Black
) {
    Box(
        modifier = modifier
            .size(size + 18.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(backgroundTint),
        contentAlignment = Alignment.Center
    ) {
        MeasurementIcon(
            icon = icon,
            size = size,
            tint = iconTint
        )
    }
}
