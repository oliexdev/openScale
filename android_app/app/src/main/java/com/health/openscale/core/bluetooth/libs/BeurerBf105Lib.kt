/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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

/**
 * Parser for the Beurer BF105 private half-body composition packet.
 *
 * The standard 2A9D (weight) and 2A9C (body composition) packets are handled by
 * StandardWeightProfileHandler. The BF105 additionally sends one half-body frame per recorded
 * weigh-in on Beurer-private characteristic 0008, carrying upper/lower body fat and
 * muscle percentages in tenths of a percent.
 */
object BeurerBf105Lib {
    data class HalfBodyMeasurement(
        val upperBodyFat: Float,
        val lowerBodyFat: Float,
        val upperBodyMuscle: Float,
        val lowerBodyMuscle: Float
    )

    fun parseHalfBodyMeasurement(value: ByteArray): HalfBodyMeasurement? {
        if (!hasBytes(value, 0, 9) || u8(value, 0) != 0x1E) return null
        val percents = readTenths(value, offset = 1, count = 4)
        return HalfBodyMeasurement(
            upperBodyFat = percents[0],
            lowerBodyFat = percents[1],
            upperBodyMuscle = percents[2],
            lowerBodyMuscle = percents[3]
        )
    }

    private fun readTenths(data: ByteArray, offset: Int, count: Int): List<Float> =
        List(count) { index -> u16le(data, offset + index * 2) * 0.1f }

    private fun hasBytes(data: ByteArray, offset: Int, count: Int): Boolean =
        offset >= 0 && count >= 0 && offset + count <= data.size

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16le(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)
}
