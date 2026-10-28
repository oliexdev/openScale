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
import com.health.openscale.R
import java.util.Calendar

/**
 * Defines the historical scope of a body scan calibration profile:
 * - [ALL_HISTORY]: Applies offsets across all historical measurements with impedance (0L).
 * - [BASELINE_DAY_ONWARDS]: Applies offsets starting from the beginning of the baseline weigh-in day.
 * - [LAST_30_DAYS]: Applies offsets starting 30 days prior to the baseline weigh-in day.
 */
enum class CalibrationScope(val labelResId: Int) {
    ALL_HISTORY(R.string.calibration_scope_all_history),
    BASELINE_DAY_ONWARDS(R.string.calibration_scope_baseline_onwards),
    LAST_30_DAYS(R.string.calibration_scope_last_30_days);

    fun calculateApplyFromTimestamp(baselineTimestamp: Long): Long = when (this) {
        ALL_HISTORY -> 0L
        BASELINE_DAY_ONWARDS -> startOfDay(baselineTimestamp)
        LAST_30_DAYS -> startOfDay(baselineTimestamp) - 30L * 86_400_000L
    }
}

/**
 * Returns epoch millis for the start of the day (00:00:00.000) of [timestamp].
 */
fun startOfDay(timestamp: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

/**
 * Stores calibration offsets computed from a high-accuracy reference body scan
 * (such as InBody, Tanita, or DEXA) paired with a home smart scale weigh-in taken on the same day.
 *
 * Offsets are stored in physical kilograms (additive delta: Reference - Scale)
 * after normalizing for day-to-day bodyweight and clothing differences via the ratio
 * W_scale / W_scan.
 *
 * Measurements taken on or after [applyFromTimestamp] will have these offsets applied
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
    val scaleMeasurementTimestamp: Long,
    val applyFromTimestamp: Long = 0L,
    val scaleName: String? = null,
    val scaleBaselineWeightKg: Float = 0f,
    val scanWeightKg: Float,
    val scanFatPercent: Float? = null,
    val scanMuscleKg: Float? = null,
    val scanWaterLiters: Float? = null,
    val scanBoneKg: Float? = null,
    val scanVisceralFat: Float? = null,
    val scanProteinKg: Float? = null,
    val offsetFfmKg: Float? = null,
    val offsetMuscleKg: Float? = null,
    val offsetWaterKg: Float? = null,
    val offsetBoneKg: Float? = null,
    val offsetVisceralFat: Float? = null,
    val offsetProteinKg: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)

