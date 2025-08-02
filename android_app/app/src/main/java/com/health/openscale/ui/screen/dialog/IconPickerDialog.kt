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
package com.health.openscale.ui.screen.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R

fun getIconResIdByName(name: String): Int {
    return when (name) {
        "ic_weight" -> R.drawable.ic_weight
        "ic_bmi" -> R.drawable.ic_bmi
        "ic_body_fat" -> R.drawable.ic_fat
        "ic_water" -> R.drawable.ic_water
        "ic_muscle" -> R.drawable.ic_muscle
        "ic_lbm" -> R.drawable.ic_lbm
        "ic_bone" -> R.drawable.ic_bone
        "ic_waist" -> R.drawable.ic_waist
        "ic_whr" -> R.drawable.ic_whr
        "ic_hips" -> R.drawable.ic_hip
        "ic_visceral_fat" -> R.drawable.ic_visceral_fat
        "ic_chest" -> R.drawable.ic_chest
        "ic_thigh" -> R.drawable.ic_thigh
        "ic_biceps" -> R.drawable.ic_biceps
        "ic_neck" -> R.drawable.ic_neck
        "ic_caliper1" -> R.drawable.ic_caliper1
        "ic_caliper2" -> R.drawable.ic_caliper2
        "ic_caliper3" -> R.drawable.ic_caliper3
        "ic_fat_caliper" -> R.drawable.ic_fat_caliper
        "ic_bmr" -> R.drawable.ic_bmr
        "ic_tdee" -> R.drawable.ic_tdee
        "ic_calories" -> R.drawable.ic_calories
        "ic_comment" -> R.drawable.ic_comment
        "ic_time" -> R.drawable.ic_time
        "ic_date" -> R.drawable.ic_date
        else -> R.drawable.ic_weight // Fallback
    }
}


@Composable
fun IconPickerDialog(
    onIconSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val icons = listOf(
        "ic_weight", "ic_bmi", "ic_body_fat", "ic_water", "ic_muscle", "ic_lbm", "ic_bone",
        "ic_waist", "ic_whr", "ic_hips", "ic_visceral_fat", "ic_chest", "ic_thigh", "ic_biceps",
        "ic_neck", "ic_caliper", "ic_bmr", "ic_tdee", "ic_calories", "ic_comment", "ic_time", "ic_date"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Icon auswählen") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.height(200.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(icons) { iconName ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onIconSelected(iconName) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = getIconResIdByName(iconName)),
                            contentDescription = iconName,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
