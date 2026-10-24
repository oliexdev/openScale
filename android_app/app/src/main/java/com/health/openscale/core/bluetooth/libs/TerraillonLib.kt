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
 * Wire protocol of the Terraillon Smart Connect / Web Coach scales (Transtek A3 module),
 * reverse-engineered from BTSnoop captures of the MyHealth app.
 *
 * Weight frame (characteristic 0x8A24):
 * ```
 * off 0    flags
 * off 1-4  weight, IEEE-11073 32-bit FLOAT, kg
 * then, in flag order:
 *   0x01   timestamp, uint32 LE, local time in seconds since 2010-01-01
 *   0x02   weight delta (FLOAT, ignored)
 *   0x04   impedance (FLOAT, ohm)
 *   0x08   scale-side user slot (uint8, ignored)
 *   0x10   status (uint8; bit 4 announces a body composition frame)
 * ```
 *
 * Body composition frame (characteristic 0x8A22):
 * ```
 * off 0    flags
 * off 1-4  timestamp (same value as the weight frame it belongs to)
 * then, in flag order, IEEE-11073 16-bit SFLOAT unless noted:
 *   0x01   scale-side user slot (uint8)
 *   0x02   basal metabolism (uint16 LE, kcal)
 *   0x04   body fat %
 *   0x08   body water %
 *   0x10   visceral fat level
 *   0x20   muscle %
 *   0x40   bone mass, kg
 * ```
 * Every field above was checked against the values the vendor app displayed for the same
 * weigh-in. Bone arrives in kg here, unlike the Weight Gurus scale of the same family.
 *
 * Challenge: the scale indicates `a1 <random:4>` on 0x8A82 and expects `20 <password xor random>`
 * on 0x8A81. The password is the scale's MAC address, low four bytes, reversed.
 */
object TerraillonLib {

    data class WeightFrame(
        val weightKg: Float,
        val timestamp: Int?,
        val impedanceOhm: Float?,
        val bodyFollows: Boolean,
    )

    data class BodyFrame(
        val timestamp: Int,
        val bmrKcal: Float?,
        val fatPercent: Float?,
        val waterPercent: Float?,
        val visceralFat: Float?,
        val musclePercent: Float?,
        val boneKg: Float?,
    )

    /** "AA:BB:CC:DD:EE:FF" → ff ee dd cc. */
    fun passwordFromMac(address: String): ByteArray? {
        val parts = address.split(":")
        if (parts.size != 6) return null
        return runCatching {
            byteArrayOf(
                parts[5].toInt(16).toByte(),
                parts[4].toInt(16).toByte(),
                parts[3].toInt(16).toByte(),
                parts[2].toInt(16).toByte()
            )
        }.getOrNull()
    }

    fun verificationCode(password: ByteArray, random: ByteArray): ByteArray =
        ByteArray(minOf(password.size, random.size)) { (password[it].toInt() xor random[it].toInt()).toByte() }

    fun parseWeightFrame(d: ByteArray): WeightFrame? {
        if (d.size < 5) return null
        val flags = d[0].toInt() and 0xFF
        val weight = float32(d, 1) ?: return null
        if (weight <= 0f) return null
        var off = 5
        var ts: Int? = null
        if (flags and 0x01 != 0) {
            ts = int32(d, off) ?: return null
            off += 4
        }
        if (flags and 0x02 != 0) off += 4
        var impedance: Float? = null
        if (flags and 0x04 != 0) {
            impedance = (float32(d, off) ?: return null).takeIf { it > 0f }
            off += 4
        }
        if (flags and 0x08 != 0) off += 1
        val follows = flags and 0x10 != 0 && off < d.size && (d[off].toInt() and 0x10) != 0
        return WeightFrame(weight, ts, impedance, follows)
    }

    fun parseBodyFrame(d: ByteArray): BodyFrame? {
        if (d.size < 5) return null
        val flags = d[0].toInt() and 0xFF
        val ts = int32(d, 1) ?: return null
        var off = 5
        if (flags and 0x01 != 0) off += 1
        var bmr: Float? = null
        if (flags and 0x02 != 0) {
            bmr = uint16(d, off)?.toFloat()
            off += 2
        }
        fun next(bit: Int): Float? {
            if (flags and bit == 0) return null
            return sfloat16(d, off).also { off += 2 }
        }
        val fat = next(0x04)
        val water = next(0x08)
        val visceral = next(0x10)
        val muscle = next(0x20)
        val bone = next(0x40)
        return BodyFrame(ts, bmr, fat, water, visceral, muscle, bone)
    }

    // --- IEEE-11073 and little-endian helpers -------------------------------------

    private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

    private fun uint16(d: ByteArray, off: Int): Int? =
        if (off + 2 > d.size) null else u8(d, off) or (u8(d, off + 1) shl 8)

    private fun int32(d: ByteArray, off: Int): Int? =
        if (off + 4 > d.size) null
        else u8(d, off) or (u8(d, off + 1) shl 8) or (u8(d, off + 2) shl 16) or (u8(d, off + 3) shl 24)

    /** 24-bit unsigned mantissa, signed base-10 exponent in the top byte. */
    private fun float32(d: ByteArray, off: Int): Float? {
        if (off + 4 > d.size) return null
        val mantissa = u8(d, off) or (u8(d, off + 1) shl 8) or (u8(d, off + 2) shl 16)
        val exponent = d[off + 3].toInt()
        return (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
    }

    /** 12-bit two's-complement mantissa, 4-bit two's-complement base-10 exponent. */
    private fun sfloat16(d: ByteArray, off: Int): Float? {
        val raw = uint16(d, off) ?: return null
        var exponent = raw shr 12
        if (exponent >= 0x8) exponent -= 0x10
        var mantissa = raw and 0x0FFF
        if (mantissa >= 0x0800) mantissa -= 0x1000
        return (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
    }
}
