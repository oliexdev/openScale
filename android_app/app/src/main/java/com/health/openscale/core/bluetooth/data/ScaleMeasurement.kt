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
    var weight: Float = 0.0f,    // must be in kg
    var fat: Float = 0.0f,   // must be in percentage
    var water: Float = 0.0f, // must be in percentage
    var muscle: Float = 0.0f, // must be in percentage
    var visceralFat: Float = 0.0f, // must be in percentage
    var bone: Float = 0.0f,  // must be in kg
    var lbm : Float = 0.0f, // must be in kg
    var bmr: Float = 0.0f,       // Basal Metabolic Rate in kcal
    var heartRate: Int = 0, // must be bpm
    var impedance: Double = 0.0, // Ohms — high-frequency band when the scale is dual-band
    var impedanceLow: Double = 0.0, // Ohms — low-frequency band; 0 when not reported
    var ecw: Float = 0.0f, // Extracellular water, % of body weight
    var icw: Float = 0.0f, // Intracellular water, % of body weight
    var protein: Float = 0.0f, // Protein, % of body weight
    var bcm: Float = 0.0f, // Body cell mass, kg
    var fatLeftArm: Float = 0.0f, // must be in percentage
    var fatRightArm: Float = 0.0f, // must be in percentage
    var fatTorso: Float = 0.0f, // must be in percentage
    var fatLeftLeg: Float = 0.0f, // must be in percentage
    var fatRightLeg: Float = 0.0f, // must be in percentage
    var muscleLeftArm: Float = 0.0f, // must be in percentage
    var muscleRightArm: Float = 0.0f, // must be in percentage
    var muscleTorso: Float = 0.0f, // must be in percentage
    var muscleLeftLeg: Float = 0.0f, // must be in percentage
    var muscleRightLeg: Float = 0.0f, // must be in percentage
) {

    // --- Utility methods ---

    fun hasWeight(): Boolean = this.weight > 0f

    fun mergeWith(other: ScaleMeasurement) = apply {
        if (other.weight > 0f && this.weight <= 0f) this.weight = other.weight
        if (other.fat > 0f && this.fat <= 0f) this.fat = other.fat
        if (other.water > 0f && this.water <= 0f) this.water = other.water
        if (other.muscle > 0f && this.muscle <= 0f) this.muscle = other.muscle
        if (other.visceralFat > 0f && this.visceralFat <= 0f) this.visceralFat = other.visceralFat
        if (other.fatLeftArm > 0f && this.fatLeftArm <= 0f) this.fatLeftArm = other.fatLeftArm
        if (other.fatRightArm > 0f && this.fatRightArm <= 0f) this.fatRightArm = other.fatRightArm
        if (other.fatTorso > 0f && this.fatTorso <= 0f) this.fatTorso = other.fatTorso
        if (other.fatLeftLeg > 0f && this.fatLeftLeg <= 0f) this.fatLeftLeg = other.fatLeftLeg
        if (other.fatRightLeg > 0f && this.fatRightLeg <= 0f) this.fatRightLeg = other.fatRightLeg
        if (other.muscleLeftArm > 0f && this.muscleLeftArm <= 0f) this.muscleLeftArm = other.muscleLeftArm
        if (other.muscleRightArm > 0f && this.muscleRightArm <= 0f) this.muscleRightArm = other.muscleRightArm
        if (other.muscleTorso > 0f && this.muscleTorso <= 0f) this.muscleTorso = other.muscleTorso
        if (other.muscleLeftLeg > 0f && this.muscleLeftLeg <= 0f) this.muscleLeftLeg = other.muscleLeftLeg
        if (other.muscleRightLeg > 0f && this.muscleRightLeg <= 0f) this.muscleRightLeg = other.muscleRightLeg
        if (other.bone > 0f && this.bone <= 0f) this.bone = other.bone
        if (other.lbm > 0f && this.lbm <= 0f) this.lbm = other.lbm
        if (other.bmr > 0f && this.bmr <= 0f) this.bmr = other.bmr
        if (other.heartRate > 0f && this.heartRate <= 0f) this.heartRate = other.heartRate
        if (other.impedance > 0.0 && this.impedance <= 0.0) this.impedance = other.impedance
        if (other.impedanceLow > 0.0 && this.impedanceLow <= 0.0) this.impedanceLow = other.impedanceLow
        if (other.ecw > 0f && this.ecw <= 0f) this.ecw = other.ecw
        if (other.icw > 0f && this.icw <= 0f) this.icw = other.icw
        if (other.protein > 0f && this.protein <= 0f) this.protein = other.protein
        if (other.bcm > 0f && this.bcm <= 0f) this.bcm = other.bcm

        if (other.userId != 0xFF &&
            (this.userId == 0xFF || this.userId == -1)) { // -1 was common init value
            this.userId = other.userId
        }

        if (this.dateTime == null && other.dateTime != null) this.dateTime = other.dateTime
    }
}

