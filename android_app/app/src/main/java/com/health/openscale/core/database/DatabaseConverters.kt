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
package com.health.openscale.core.database

import androidx.room.TypeConverter
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType

class DatabaseConverters {

    @TypeConverter
    fun fromTypeKey(value: MeasurementTypeKey): String = value.name

    @TypeConverter
    fun toTypeKey(value: String): MeasurementTypeKey = MeasurementTypeKey.valueOf(value)

    // UnitType
    @TypeConverter
    fun fromUnitType(value: UnitType): String = value.name

    @TypeConverter
    fun toUnitType(value: String): UnitType = UnitType.valueOf(value)

    // InputFieldType
    @TypeConverter
    fun fromInputType(value: InputFieldType): String = value.name

    @TypeConverter
    fun toInputType(value: String): InputFieldType = InputFieldType.valueOf(value)

    @TypeConverter
    fun fromGender(value: GenderType): String = value.name

    @TypeConverter
    fun toGender(value: String): GenderType = GenderType.valueOf(value)
}