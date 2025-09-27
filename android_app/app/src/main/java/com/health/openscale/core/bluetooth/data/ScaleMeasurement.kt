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
package com.health.openscale.core.bluetooth.data

import com.health.openscale.core.data.WeightUnit
import java.util.Date

/**
 * Represents a single measurement record from a scale, potentially combined from multiple BLE packets.
 */
data class ScaleMeasurement(
    var userId: Int = 0xFF, // openScale's internal app user ID
    var dateTime: Date? = null,
    var weight: Float = 0.0f,    // In units specified by `weightUnit`
    var fat: Float = 0.0f,   // Percentage
    var water: Float = 0.0f,
    var muscle: Float = 0.0f, // Absolute mass, in units specified by `weightUnit`
    var visceralFat: Float = 0.0f, // Rating or percentage
    var bone: Float = 0.0f,  // Absolute mass, in units specified by `weightUnit`
    var lbm : Float = 0.0f,
    var bmr: Float = 0.0f,       // Basal Metabolic Rate in kcal
    var impedance: Double = 0.0, // Ohms
    var height: Float = 0.0f,    // Internally stored in meters
    var weightUnit: WeightUnit? = null, // Uses the existing enum from core.data
) {

    // --- Utility methods ---

    fun hasWeight(): Boolean = this.weight > 0f

    fun hasAnyBodyCompositionValue(): Boolean {
        return this.fat > 0f ||
                this.muscle > 0f ||
                this.water > 0f ||
                this.bone > 0f ||
                this.visceralFat > 0f ||
                this.bmr > 0f
    }

    fun mergeWith(other: ScaleMeasurement) = apply {
        if (other.weight > 0f && this.weight <= 0f) this.weight = other.weight
        if (other.weightUnit != null && this.weightUnit == null) this.weightUnit = other.weightUnit
        if (other.fat > 0f && this.fat <= 0f) this.fat = other.fat
        if (other.water > 0f && this.water <= 0f) this.water = other.water
        if (other.muscle > 0f && this.muscle <= 0f) this.muscle = other.muscle
        if (other.visceralFat > 0f && this.visceralFat <= 0f) this.visceralFat = other.visceralFat
        if (other.bone > 0f && this.bone <= 0f) this.bone = other.bone
        if (other.lbm > 0f && this.lbm <= 0f) this.lbm = other.lbm
        if (other.bmr > 0f && this.bmr <= 0f) this.bmr = other.bmr
        if (other.impedance > 0.0 && this.impedance <= 0.0) this.impedance = other.impedance
        if (other.height > 0f && this.height <= 0f) this.height = other.height

        if (other.userId != 0xFF &&
            (this.userId == 0xFF || this.userId == -1)) { // -1 was common init value
            this.userId = other.userId
        }

        if (this.dateTime == null && other.dateTime != null) this.dateTime = other.dateTime
    }
}

