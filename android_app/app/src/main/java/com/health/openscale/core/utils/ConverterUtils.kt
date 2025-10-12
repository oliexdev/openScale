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
package com.health.openscale.core.utils

import com.health.openscale.core.data.MeasureUnit
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import kotlin.math.floor
import kotlin.math.roundToInt


object ConverterUtils {
    private const val KG_LB: Float = 2.20462f
    private const val KG_ST: Float = 0.157473f
    private const val CM_IN: Float = 0.393701f

    private const val LB_PER_ST_DOUBLE: Double = 14.0

    @JvmStatic
    fun toKilogram(value: Float, unit: WeightUnit): Float {
        when (unit) {
            WeightUnit.LB -> return value / KG_LB
            WeightUnit.ST -> return value / KG_ST
            WeightUnit.KG -> return value
        }
    }

    @JvmStatic
    fun fromKilogram(kg: Float, unit: WeightUnit): Float {
        when (unit) {
            WeightUnit.LB -> return kg * KG_LB
            WeightUnit.ST -> return kg * KG_ST
            WeightUnit.KG -> return kg
        }
    }

    @JvmStatic
    fun toCentimeter(value: Float, unit: MeasureUnit): Float {
        when (unit) {
            MeasureUnit.INCH -> return value / CM_IN
            MeasureUnit.CM -> return value
        }
    }

    @JvmStatic
    fun fromCentimeter(cm: Float, unit: MeasureUnit): Float {
        when (unit) {
            MeasureUnit.INCH -> return cm * CM_IN
            MeasureUnit.CM -> return cm
        }
    }

    @JvmStatic
    fun decimalStToStLb(stDec: Double): Pair<Int, Int> {
        val totalLb = stDec * LB_PER_ST_DOUBLE
        var st = floor(totalLb / LB_PER_ST_DOUBLE).toInt()
        var lb = (totalLb - st * LB_PER_ST_DOUBLE).roundToInt()
        if (lb == 14) { st += 1; lb = 0 } // normalize carry
        return st to lb
    }

    @JvmStatic
    fun fromSignedInt16Le(data: ByteArray, offset: Int): Int {
        var value = data[offset + 1].toInt() shl 8
        value += data[offset].toInt() and 0xFF
        return value
    }
    @JvmStatic
    fun fromSignedInt16Be(data: ByteArray, offset: Int): Int {
        var value = data[offset].toInt() shl 8
        value += data[offset + 1].toInt() and 0xFF
        return value
    }
    @JvmStatic
    fun fromUnsignedInt16Le(data: ByteArray, offset: Int): Int {
        return fromSignedInt16Le(data, offset) and 0xFFFF
    }
    @JvmStatic
    fun fromUnsignedInt16Be(data: ByteArray, offset: Int): Int {
        return fromSignedInt16Be(data, offset) and 0xFFFF
    }
    @JvmStatic
    fun toInt16Le(data: ByteArray, offset: Int, value: Int) {
        data[offset + 0] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
    @JvmStatic
    fun toInt16Be(data: ByteArray, offset: Int, value: Int) {
        data[offset + 0] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }
    @JvmStatic
    fun toInt16Le(value: Int): ByteArray {
        val data = ByteArray(2)
        toInt16Le(data, 0, value)
        return data
    }
    @JvmStatic
    fun toInt16Be(value: Int): ByteArray {
        val data = ByteArray(2)
        toInt16Be(data, 0, value)
        return data
    }
    @JvmStatic
    fun fromSignedInt24Le(data: ByteArray, offset: Int): Int {
        var value = data[offset + 2].toInt() shl 16
        value += (data[offset + 1].toInt() and 0xFF) shl 8
        value += data[offset].toInt() and 0xFF
        return value
    }
    @JvmStatic
    fun fromSignedInt24Be(data: ByteArray, offset: Int): Int {
        var value = data[offset].toInt() shl 16
        value += (data[offset + 1].toInt() and 0xFF) shl 8
        value += data[offset + 2].toInt() and 0xFF
        return value
    }
    @JvmStatic
    fun fromUnsignedInt24Le(data: ByteArray, offset: Int): Int {
        return fromSignedInt24Le(data, offset) and 0xFFFFFF
    }
    @JvmStatic
    fun fromUnsignedInt24Be(data: ByteArray, offset: Int): Int {
        return fromSignedInt24Be(data, offset) and 0xFFFFFF
    }
    @JvmStatic
    fun fromSignedInt32Le(data: ByteArray, offset: Int): Int {
        var value = data[offset + 3].toInt() shl 24
        value += (data[offset + 2].toInt() and 0xFF) shl 16
        value += (data[offset + 1].toInt() and 0xFF) shl 8
        value += data[offset].toInt() and 0xFF
        return value
    }
    @JvmStatic
    fun fromSignedInt32Be(data: ByteArray, offset: Int): Int {
        var value = data[offset].toInt() shl 24
        value += (data[offset + 1].toInt() and 0xFF) shl 16
        value += (data[offset + 2].toInt() and 0xFF) shl 8
        value += data[offset + 3].toInt() and 0xFF
        return value
    }
    @JvmStatic
    fun fromUnsignedInt32Le(data: ByteArray, offset: Int): Long {
        return fromSignedInt32Le(data, offset).toLong() and 0xFFFFFFFFL
    }
    @JvmStatic
    fun fromUnsignedInt32Be(data: ByteArray, offset: Int): Long {
        return fromSignedInt32Be(data, offset).toLong() and 0xFFFFFFFFL
    }
    @JvmStatic
    fun toInt32Le(data: ByteArray, offset: Int, value: Long) {
        data[offset + 3] = ((value shr 24) and 0xFFL).toByte()
        data[offset + 2] = ((value shr 16) and 0xFFL).toByte()
        data[offset + 1] = ((value shr 8) and 0xFFL).toByte()
        data[offset + 0] = (value and 0xFFL).toByte()
    }
    @JvmStatic
    fun toInt32Be(data: ByteArray, offset: Int, value: Long) {
        data[offset + 0] = ((value shr 24) and 0xFFL).toByte()
        data[offset + 1] = ((value shr 16) and 0xFFL).toByte()
        data[offset + 2] = ((value shr 8) and 0xFFL).toByte()
        data[offset + 3] = (value and 0xFFL).toByte()
    }
    @JvmStatic
    fun toInt32Le(value: Long): ByteArray {
        val data = ByteArray(4)
        toInt32Le(data, 0, value)
        return data
    }
    @JvmStatic
    fun toInt32Be(value: Long): ByteArray {
        val data = ByteArray(4)
        toInt32Be(data, 0, value)
        return data
    }

    /**
     * Converts a Float value from one UnitType to another, if a conversion is defined.
     * Returns the original value if no conversion is applicable or units are the same.
     *
     * @param value The float value to convert.
     * @param fromUnit The original UnitType of the value.
     * @param toUnit The target UnitType for the value.
     * @return The converted float value, or the original value if no conversion is done.
     */
    @JvmStatic
    fun convertFloatValueUnit(value: Float, fromUnit: UnitType, toUnit: UnitType): Float {
        if (fromUnit == toUnit) return value

        // KG -> Andere Gewichtseinheiten
        if (fromUnit == UnitType.KG) {
            return when (toUnit) {
                UnitType.LB -> fromKilogram(value, WeightUnit.LB)
                UnitType.ST -> fromKilogram(value, WeightUnit.ST)
                else -> value // Keine Umrechnung zu anderen Typen von KG aus
            }
        }
        // LB -> Andere Gewichtseinheiten (erst zu KG, dann zum Ziel)
        if (fromUnit == UnitType.LB) {
            val kgValue = toKilogram(value, WeightUnit.LB)
            return when (toUnit) {
                UnitType.KG -> kgValue
                UnitType.ST -> fromKilogram(kgValue, WeightUnit.ST)
                else -> value
            }
        }
        // ST -> Andere Gewichtseinheiten (erst zu KG, dann zum Ziel)
        if (fromUnit == UnitType.ST) {
            val kgValue = toKilogram(value, WeightUnit.ST)
            return when (toUnit) {
                UnitType.KG -> kgValue
                UnitType.LB -> fromKilogram(kgValue, WeightUnit.LB)
                else -> value
            }
        }

        // CM -> Andere Längeneinheiten
        if (fromUnit == UnitType.CM) {
            return when (toUnit) {
                UnitType.INCH -> fromCentimeter(value, MeasureUnit.INCH)
                else -> value
            }
        }

        if (fromUnit == UnitType.INCH) {
            val cmValue = toCentimeter(value, MeasureUnit.INCH)
            return when (toUnit) {
                UnitType.CM -> cmValue
                else -> value
            }
        }

        return value
    }

}