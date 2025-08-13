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
package com.health.openscale.core.data

import android.content.Context
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity
data class MeasurementType(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: MeasurementTypeKey = MeasurementTypeKey.CUSTOM,
    val name: String? = null,
    val color: Int = 0,
    val icon : String = "ic_weight",
    val unit: UnitType = UnitType.NONE,
    val inputType: InputFieldType = InputFieldType.FLOAT,
    val displayOrder: Int = 0,
    val isDerived: Boolean = false,
    val isEnabled : Boolean = true,
    val isPinned : Boolean = false,
    val isOnRightYAxis : Boolean = false
){
    /**
     * Gets the appropriate display name for UI purposes.
     * If the key points to a predefined type with a localized resource ID, that resource is used
     * to ensure the name is displayed in the current device language.
     * Otherwise (e.g., for CUSTOM types or if no specific resource ID is set for the key),
     * the stored 'name' property is returned.
     *
     * @param context The context needed to resolve string resources.
     * @return The display name for this measurement type.
     */
    @Ignore // Room should not try to map this helper function to a DB column
    fun getDisplayName(context: Context): String {
        return if (key == MeasurementTypeKey.CUSTOM) {
            if (!name.isNullOrBlank()) {
                name
            } else {
                context.getString(key.localizedNameResId)
            }
        } else {
            context.getString(key.localizedNameResId)
        }
    }
}
