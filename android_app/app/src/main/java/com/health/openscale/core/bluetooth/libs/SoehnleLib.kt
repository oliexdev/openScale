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
package com.health.openscale.core.bluetooth.libs

/**
 * One frame of the live weight stream a Soehnle scale sends on 352e3001 after the
 * app writes `30 FF 01` to 352e3002 (and stops with `30 FF 00`).
 *
 * Layout, as captured from the vendor app (Shape Sense Connect 50 and 200):
 * ```
 * [0]    0x30  live frame
 * [1]    scale user index (1..7) the scale attributes the weighing to
 * [2]    status: 0x00 while the weight settles, non-zero on the final frame
 * [3..4] weight, uint16 BE, 0.1 kg
 * [5..6] impedance (same position as imp5 in the 0x09 history frame), uint16 BE
 * [7..8] impedance (same position as imp50 in the 0x09 history frame), uint16 BE
 * ```
 * Intermediate frames carry zero impedances; only the final frame carries them.
 */
data class SoehnleLiveFrame(
    val userIndex: Int,
    val status: Int,
    val weightKg: Float,
    val imp5: Int,
    val imp50: Int,
) {
    val isFinal: Boolean get() = status != 0

    companion object {
        const val LENGTH = 9
        const val OPCODE = 0x30

        fun parse(value: ByteArray): SoehnleLiveFrame? {
            if (value.size != LENGTH || (value[0].toInt() and 0xFF) != OPCODE) return null
            fun u16(i: Int) = ((value[i].toInt() and 0xFF) shl 8) or (value[i + 1].toInt() and 0xFF)
            return SoehnleLiveFrame(
                userIndex = value[1].toInt() and 0xFF,
                status = value[2].toInt() and 0xFF,
                weightKg = u16(3) / 10.0f,
                imp5 = u16(5),
                imp50 = u16(7),
            )
        }
    }
}

/**
 * Current Time characteristic (0x2A2B) payload in the full 10-byte layout the vendor app
 * writes: year (uint16 LE), month, day, hours, minutes, seconds, day of week (1 = Monday ..
 * 7 = Sunday), fractions256, adjust reason. After the previous 9-byte write the scale
 * still reported its default 2016-01-01 12:00 when read back.
 */
fun soehnleCurrentTime(
    year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int,
    calendarDayOfWeek: Int,
): ByteArray {
    // java.util.Calendar: SUNDAY = 1 .. SATURDAY = 7; Bluetooth: MONDAY = 1 .. SUNDAY = 7
    val bleDayOfWeek = ((calendarDayOfWeek + 5) % 7) + 1
    return byteArrayOf(
        (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
        month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte(),
        bleDayOfWeek.toByte(), 0x00, 0x00,
    )
}

class SoehnleLib(// male = 1; female = 0
    private val isMale: Boolean,
    private val age: Int,
    private val height: Float,
    private val activityLevel: Int
) {
    fun getFat(weight: Float, imp50: Float): Float { // in %
        var activityCorrFac = 0.0f

        when (activityLevel) {
            4 -> {
                if (isMale) {
                    activityCorrFac = 2.5f
                } else {
                    activityCorrFac = 2.3f
                }
            }

            5 -> {
                if (isMale) {
                    activityCorrFac = 4.3f
                } else {
                    activityCorrFac = 4.1f
                }
            }
        }

        val sexCorrFac: Float
        val activitySexDiv: Float

        if (isMale) {
            sexCorrFac = 0.250f
            activitySexDiv = 65.5f
        } else {
            sexCorrFac = 0.214f
            activitySexDiv = 55.1f
        }

        return 1.847f * weight * 10000.0f / (height * height) + sexCorrFac * age + 0.062f * imp50 - (activitySexDiv - activityCorrFac)
    }

    fun computeBodyMassIndex(weight: Float): Float {
        return 10000.0f * weight / (height * height)
    }

    fun getWater(weight: Float, imp50: Float): Float { // in %
        var activityCorrFac = 0.0f

        when (activityLevel) {
            1, 2, 3 -> {
                if (isMale) {
                    activityCorrFac = 2.83f
                } else {
                    activityCorrFac = 0.0f
                }
            }

            4 -> {
                if (isMale) {
                    activityCorrFac = 3.93f
                } else {
                    activityCorrFac = 0.4f
                }
            }

            5 -> {
                if (isMale) {
                    activityCorrFac = 5.33f
                } else {
                    activityCorrFac = 1.4f
                }
            }
        }
        return (0.3674f * height * height / imp50 + 0.17530f * weight - 0.11f * age + (6.53f + activityCorrFac)) / weight * 100.0f
    }

    fun getMuscle(weight: Float, imp50: Float, imp5: Float): Float { // in %
        var activityCorrFac = 0.0f

        when (activityLevel) {
            1, 2, 3 -> {
                if (isMale) {
                    activityCorrFac = 3.6224f
                } else {
                    activityCorrFac = 0.0f
                }
            }

            4 -> {
                if (isMale) {
                    activityCorrFac = 4.3904f
                } else {
                    activityCorrFac = 0.0f
                }
            }

            5 -> {
                if (isMale) {
                    activityCorrFac = 5.4144f
                } else {
                    activityCorrFac = 1.664f
                }
            }
        }
        return ((0.47027f / imp50 - 0.24196f / imp5) * height * height + 0.13796f * weight - 0.1152f * age + (5.12f + activityCorrFac)) / weight * 100.0f
    }
}
