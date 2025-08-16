package com.health.openscale.ui.screen.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.ui.components.RoundMeasurementIcon


@Composable
fun IconPickerDialog(
    iconBackgroundColor: Color,
    onIconSelected: (MeasurementTypeIcon) -> Unit,
    onDismiss: () -> Unit
) {
    val availableIcons = remember {
        MeasurementTypeIcon.values().filter { it != MeasurementTypeIcon.IC_DEFAULT }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_select_icon)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                items(availableIcons) { iconEnum ->
                    RoundMeasurementIcon(
                        icon = iconEnum,
                        backgroundTint = iconBackgroundColor,
                        size = 28.dp,
                        modifier = Modifier.clickable { onIconSelected(iconEnum) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        }
    )
}
