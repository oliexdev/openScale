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
package com.health.openscale.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores calibration offsets computed from a high-accuracy reference body scan
 * (such as InBody, Tanita, or DEXA) paired with a home smart scale weigh-in taken on the same day.
 *
 * Offsets are stored in physical kilograms (additive delta: Reference - Scale)
 * after normalizing for day-to-day bodyweight and clothing differences via the ratio
 * W_scale / W_scan.
 *
 * Measurements taken on or after [scaleMeasurementTimestamp] will have these offsets applied
 * when body scan calibration is enabled.
 */
@Entity(
    tableName = "body_scan_calibration",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId", unique = true)
    ]
)
data class BodyScanCalibration(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val userId: Int,

    /** The timestamp of the home scale measurement used as baseline. */
    val scaleMeasurementTimestamp: Long,

    /** Optional name/model of the home scale used for calibration. */
    val scaleName: String? = null,

    /** Uncalibrated total body weight on the home scale at calibration baseline (W0). */
    val scaleBaselineWeightKg: Float = 0f,

    /** Total body weight recorded during the reference body scan (kg). */
    val scanWeightKg: Float,

    /** Body fat percentage recorded on the reference scan (%). */
    val scanFatPercent: Float? = null,

    /** Skeletal muscle mass recorded on the reference scan (kg). */
    val scanMuscleKg: Float? = null,

    /** Total body water recorded on the reference scan (liters / kg). */
    val scanWaterLiters: Float? = null,

    /** Bone / osseous mineral mass recorded on the reference scan (kg). */
    val scanBoneKg: Float? = null,

    /** Visceral fat level/rating recorded on the reference scan. */
    val scanVisceralFat: Float? = null,

    /** Protein mass recorded on the reference scan (kg). */
    val scanProteinKg: Float? = null,

    /**
     * Additive offset in kg for Fat-Free Mass:
     * FFM_offset = Reference_FFM_normalized - Scale_FFM
     */
    val offsetFfmKg: Float? = null,

    /**
     * Additive offset in kg for Skeletal Muscle Mass:
     * Muscle_offset = Reference_Muscle_normalized - Scale_Muscle
     */
    val offsetMuscleKg: Float? = null,

    /**
     * Additive offset in kg for Total Body Water:
     * Water_offset = Reference_Water_normalized - Scale_Water
     */
    val offsetWaterKg: Float? = null,

    /**
     * Additive offset in kg for Bone Mass:
     * Bone_offset = Reference_Bone_normalized - Scale_Bone
     */
    val offsetBoneKg: Float? = null,

    /**
     * Additive offset for Visceral Fat rating:
     * VF_offset = Reference_VF - Scale_VF
     */
    val offsetVisceralFat: Float? = null,

    /**
     * Additive offset in kg for Protein Mass:
     * Protein_offset = Reference_Protein_normalized - Scale_Protein
     */
    val offsetProteinKg: Float? = null,

    /** Timestamp when this calibration was created or updated. */
    val createdAt: Long = System.currentTimeMillis()
)

