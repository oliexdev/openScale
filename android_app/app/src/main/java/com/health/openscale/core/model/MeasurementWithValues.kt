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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.model

import androidx.room.Embedded
import androidx.room.Relation
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementValue

/**
 * Combines a [Measurement] entity with its related [MeasurementValueWithType] list.
 *
 * This is a Room relation projection that joins:
 * - a measurement row
 * - all its child measurement values
 */
data class MeasurementWithValues(
    @Embedded val measurement: Measurement,
    @Relation(
        parentColumn = "id",
        entityColumn = "measurementId",
        entity = MeasurementValue::class
    )
    val values: List<MeasurementValueWithType>
)