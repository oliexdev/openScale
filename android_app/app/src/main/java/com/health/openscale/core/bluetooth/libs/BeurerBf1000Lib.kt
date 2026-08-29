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
 * Parser for the Beurer BF1000 Super Precision private measurement packets.
 *
 * Standard Weight Scale (2A9D) and Body Composition (2A9C) packets are handled
 * by StandardWeightProfileHandler. The BF1000 also sends Beurer-private packets
 * on service FFFF: characteristic 0009 for visceral/segmental fat and 000A for
 * segmental muscle. These functions intentionally stay free of Android
 * dependencies so captured packets can be asserted in JVM tests.
 */
object BeurerBf1000Lib {
    data class SegmentalFatMeasurement(
        val visceralFat: Float,
        val leftArm: Float,
        val rightArm: Float,
        val torso: Float,
        val leftLeg: Float,
        val rightLeg: Float
    )

    data class SegmentalMuscleMeasurement(
        val leftArm: Float,
        val rightArm: Float,
        val torso: Float,
        val leftLeg: Float,
        val rightLeg: Float
    )

    fun parseSegmentalFatMeasurement(value: ByteArray): SegmentalFatMeasurement? {
        if (!hasBytes(value, 0, 12) || u8(value, 0) != 0x7E) return null
        val fat = readTenths(value, offset = 2, count = 5)
        return SegmentalFatMeasurement(
            visceralFat = u8(value, 1) * 0.1f,
            leftArm = fat[0],
            rightArm = fat[1],
            torso = fat[2],
            leftLeg = fat[3],
            rightLeg = fat[4]
        )
    }

    fun parseSegmentalMuscleMeasurement(value: ByteArray): SegmentalMuscleMeasurement? {
        if (!hasBytes(value, 0, 11) || u8(value, 0) != 0x3E) return null
        val muscle = readTenths(value, offset = 1, count = 5)
        return SegmentalMuscleMeasurement(
            leftArm = muscle[0],
            rightArm = muscle[1],
            torso = muscle[2],
            leftLeg = muscle[3],
            rightLeg = muscle[4]
        )
    }

    private fun readTenths(data: ByteArray, offset: Int, count: Int): List<Float> =
        List(count) { index -> u16le(data, offset + index * 2) * 0.1f }

    private fun hasBytes(data: ByteArray, offset: Int, count: Int): Boolean =
        offset >= 0 && count >= 0 && offset + count <= data.size

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
