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
 * An InBody calibration record for a user.
 *
 * Holds the raw reference inputs entered from an InBody test result sheet,
 * along with the calculated additive intercept offsets (Δ) in kilograms (Kg).
 *
 * A UNIQUE index on [userId] guarantees that each user profile has at most one
 * active calibration profile.
 */
@Entity(
    tableName = "inbody_calibration",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId"], unique = true)
    ]
)
data class InBodyCalibration(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** Timestamp (epoch millis) of the home scale measurement paired with this calibration. */
    val scaleMeasurementTimestamp: Long,
    /** Name of the scale paired at calibration time (e.g. "Mi Body Composition Scale 2"). */
    val scaleName: String? = null,

    // --- Raw InBody reference inputs from the sheet ---
    val inBodyWeightKg: Float,
    val inBodyFatPercent: Float? = null,
    val inBodyMuscleKg: Float? = null,
    val inBodyWaterLiters: Float? = null,
    val inBodyBoneKg: Float? = null,
    val inBodyVisceralFat: Float? = null,
    val inBodyProteinKg: Float? = null,

    // --- Calibrated Additive Intercept Offsets (ALL mass offsets stored in Kg) ---
    val offsetFfmKg: Float? = null,
    val offsetMuscleKg: Float? = null,
    val offsetWaterKg: Float? = null,
    val offsetBoneKg: Float? = null,
    /** Dimensionless rating/index offset. */
    val offsetVisceralFat: Float? = null,
    val offsetProteinKg: Float? = null
)

