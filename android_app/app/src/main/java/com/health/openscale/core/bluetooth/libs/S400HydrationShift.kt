/*
 * openScale
 * Copyright (C) 2026 Dany Mestas
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
package com.health.openscale.core.bluetooth.libs

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Change in body-water distribution between two weighings of the same person,
 * read from the S400's band ratio `|Z_50| / |Z_250|`.
 *
 * The band ratio carries almost no fat-free-mass information but tracks the
 * extracellular share of body water: on NHANES 1999-2004 adults it correlates
 * with full-spectrum ECW/TBW at r = -0.86, and +1 % of ratio corresponds to
 * about -0.018 ECW/TBW and to +0.35 points of 50 kHz body-fat error against DXA
 * (see `S400HydrationShiftTest`). A negative shift therefore means relatively
 * more extracellular water (fluid retention), under which the 50 kHz body fat
 * reads low; a positive shift the reverse.
 *
 * The shift is signed and in percent of the previous ratio. It compares two
 * weighings, not a weighing against a long-term baseline, so it flags a sudden
 * fluid shift in either of the two and ignores slow drift.
 */
object S400HydrationShift {

    /**
     * Shift at which the 50 kHz body fat is biased by about one point (ECW/TBW
     * moving by about 0.05). Day-to-day variation of the ratio in one S400 user
     * over four weighings is 0.18 % SD, so the difference of two weighings has an
     * SD near 0.25 % and the threshold sits about 12 SD above it.
     */
    const val WARNING_THRESHOLD_PCT = 3.0f

    /**
     * `|Z_50| / |Z_250|` from the two magnitudes in either order, or null when
     * either is missing or the bands are within 1 %, the contact failure that
     * [S400BodyComposition] rejects as UNRELIABLE.
     */
    fun bandRatio(first: Float?, second: Float?): Float? {
        if (first == null || second == null) return null
        val high = max(first, second)
        val low = min(first, second)
        if (low <= 0f || (high - low) / low < 0.01f) return null
        return high / low
    }

    /** Signed shift in percent from the previous weighing, or null if either ratio is unavailable. */
    fun shiftPct(currentLow: Float?, currentHigh: Float?, previousLow: Float?, previousHigh: Float?): Float? {
        val current = bandRatio(currentLow, currentHigh) ?: return null
        val previous = bandRatio(previousLow, previousHigh) ?: return null
        return (current / previous - 1f) * 100f
    }

    fun isWarning(shiftPct: Float): Boolean = abs(shiftPct) >= WARNING_THRESHOLD_PCT
}
