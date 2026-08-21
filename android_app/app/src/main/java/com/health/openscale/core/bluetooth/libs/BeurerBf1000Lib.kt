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

import java.util.Calendar
import java.util.Date

/**
 * Parser for the Beurer BF1000 Super Precision measurement packets.
 *
 * The scale uses the standard Weight Scale (2A9D) and Body Composition (2A9C)
 * characteristics, then adds two Beurer-private segmental packets on service
 * FFFF: characteristic 0009 for visceral/segmental fat and 000A for segmental
 * muscle. These functions intentionally stay free of Android dependencies so
 * captured packets can be asserted in JVM tests.
 */
object BeurerBf1000Lib {
    private const val KG_STEP = 0.005f
    private const val LB_STEP = 0.01f
    private const val LB_TO_KG = 0.45359237f

    data class WeightMeasurement(
        val weightKg: Float,
        val dateTime: Date?,
        val scaleUserIndex: Int?,
        val bmi: Float?,
        val heightMeters: Float?,
        val isKg: Boolean
    )

    data class BodyCompositionMeasurement(
        val bodyFatPercent: Float,
        val dateTime: Date?,
        val scaleUserIndex: Int?,
        val bmrKcal: Float?,
        val musclePercent: Float?,
        val softLeanMassKg: Float?,
        val waterMassKg: Float?,
        val impedanceOhm: Double?,
        val weightKg: Float?,
        val leanBodyMassKg: Float?,
        val boneMassKg: Float?,
        val isKg: Boolean,
        val isMultiPacket: Boolean
    )

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

    fun parseWeightMeasurement(value: ByteArray): WeightMeasurement? {
        if (!hasBytes(value, 0, 3)) return null
        var offset = 0

        val flags = u8(value, offset); offset += 1
        val isKg = (flags and 0x01) == 0
        val tsPresent = (flags and 0x02) != 0
        val userPresent = (flags and 0x04) != 0
        val bmiHeightPresent = (flags and 0x08) != 0

        if (!hasBytes(value, offset, 2)) return null
        val weightKg = massKg(u16le(value, offset), isKg); offset += 2

        val dateTime = if (tsPresent) {
            val parsed = parseDateTime(value, offset) ?: return null
            offset += 7
            parsed
        } else {
            null
        }

        val scaleUserIndex = if (userPresent) {
            if (!hasBytes(value, offset, 1)) return null
            u8(value, offset).also { offset += 1 }
        } else {
            null
        }

        var bmi: Float? = null
        var heightMeters: Float? = null
        if (bmiHeightPresent) {
            if (!hasBytes(value, offset, 4)) return null
            bmi = u16le(value, offset) * 0.1f; offset += 2
            heightMeters = u16le(value, offset) * 0.001f
        }

        return WeightMeasurement(
            weightKg = weightKg,
            dateTime = dateTime,
            scaleUserIndex = scaleUserIndex,
            bmi = bmi,
            heightMeters = heightMeters,
            isKg = isKg
        )
    }

    fun parseBodyCompositionMeasurement(
        value: ByteArray,
        fallbackWeightKg: Float? = null
    ): BodyCompositionMeasurement? {
        if (!hasBytes(value, 0, 4)) return null
        var offset = 0

        val flags = u16le(value, offset); offset += 2
        val isKg = (flags and 0x0001) == 0
        val tsPresent = (flags and 0x0002) != 0
        val userPresent = (flags and 0x0004) != 0
        val bmrPresent = (flags and 0x0008) != 0
        val musclePctPresent = (flags and 0x0010) != 0
        val muscleMassPresent = (flags and 0x0020) != 0
        val fatFreeMassPresent = (flags and 0x0040) != 0
        val softLeanPresent = (flags and 0x0080) != 0
        val waterMassPresent = (flags and 0x0100) != 0
        val impedancePresent = (flags and 0x0200) != 0
        val weightPresent = (flags and 0x0400) != 0
        val heightPresent = (flags and 0x0800) != 0
        val multiPacket = (flags and 0x1000) != 0

        if (!hasBytes(value, offset, 2)) return null
        val bodyFatPercent = u16le(value, offset) * 0.1f; offset += 2

        val dateTime = if (tsPresent) {
            val parsed = parseDateTime(value, offset) ?: return null
            offset += 7
            parsed
        } else {
            null
        }

        val scaleUserIndex = if (userPresent) {
            if (!hasBytes(value, offset, 1)) return null
            u8(value, offset).also { offset += 1 }
        } else {
            null
        }

        val bmrKcal = if (bmrPresent) {
            if (!hasBytes(value, offset, 2)) return null
            val bmrKilojoules = u16le(value, offset); offset += 2
            ((bmrKilojoules / 4.1868f) * 10f).toInt() / 10f
        } else {
            null
        }

        val musclePercent = if (musclePctPresent) {
            if (!hasBytes(value, offset, 2)) return null
            (u16le(value, offset) * 0.1f).also { offset += 2 }
        } else {
            null
        }

        if (muscleMassPresent) {
            if (!hasBytes(value, offset, 2)) return null
            offset += 2
        }

        if (fatFreeMassPresent) {
            if (!hasBytes(value, offset, 2)) return null
            offset += 2
        }

        val softLeanMassKg = if (softLeanPresent) {
            if (!hasBytes(value, offset, 2)) return null
            massKg(u16le(value, offset), isKg).also { offset += 2 }
        } else {
            null
        }

        val waterMassKg = if (waterMassPresent) {
            if (!hasBytes(value, offset, 2)) return null
            massKg(u16le(value, offset), isKg).also { offset += 2 }
        } else {
            null
        }

        val impedanceOhm = if (impedancePresent) {
            if (!hasBytes(value, offset, 2)) return null
            (u16le(value, offset) * 0.1f).toDouble().also { offset += 2 }
        } else {
            null
        }

        val weightKg = if (weightPresent) {
            if (!hasBytes(value, offset, 2)) return null
            massKg(u16le(value, offset), isKg).also { offset += 2 }
        } else {
            fallbackWeightKg?.takeIf { it > 0f }
        }

        if (heightPresent) {
            if (!hasBytes(value, offset, 2)) return null
            offset += 2
        }

        val leanBodyMassKg = weightKg?.takeIf { it > 0f }?.let {
            it * (1f - bodyFatPercent / 100f)
        }
        val boneMassKg = leanBodyMassKg
            ?.let { leanBodyMass -> softLeanMassKg?.let { softLean -> leanBodyMass - softLean } }
            ?.takeIf { it > 0f }

        return BodyCompositionMeasurement(
            bodyFatPercent = bodyFatPercent,
            dateTime = dateTime,
            scaleUserIndex = scaleUserIndex,
            bmrKcal = bmrKcal?.takeIf { it > 0f },
            musclePercent = musclePercent,
            softLeanMassKg = softLeanMassKg,
            waterMassKg = waterMassKg,
            impedanceOhm = impedanceOhm?.takeIf { it > 0.0 },
            weightKg = weightKg,
            leanBodyMassKg = leanBodyMassKg,
            boneMassKg = boneMassKg,
            isKg = isKg,
            isMultiPacket = multiPacket
        )
    }

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

    private fun massKg(raw: Int, isKg: Boolean): Float =
        if (isKg) raw * KG_STEP else raw * LB_STEP * LB_TO_KG

    private fun parseDateTime(value: ByteArray, offset: Int): Date? {
        if (!hasBytes(value, offset, 7)) return null
        val year = u16le(value, offset)
        val month = u8(value, offset + 2)
        val day = u8(value, offset + 3)
        val hour = u8(value, offset + 4)
        val minute = u8(value, offset + 5)
        val second = u8(value, offset + 6)

        return Calendar.getInstance().apply {
            set(year, (month - 1).coerceAtLeast(0), day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.time
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
