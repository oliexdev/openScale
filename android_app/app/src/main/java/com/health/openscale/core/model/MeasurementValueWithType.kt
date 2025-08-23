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
package com.health.openscale.core.model

import androidx.room.Embedded
import androidx.room.Relation
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue

/**
 * Combines a [MeasurementValue] with its [MeasurementType].
 *
 * This allows UI/logic layers to directly access both the raw value and its
 * associated type metadata (like unit, display order, or category).
 */
data class MeasurementValueWithType(
    @Embedded val value: MeasurementValue,
    @Relation(
        parentColumn = "typeId",
        entityColumn = "id"
    )
    val type: MeasurementType
)