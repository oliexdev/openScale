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
 * Wire protocol of the Chipsea "Chipsea-BLE" GATT body-fat scales — the house protocol of
 * Chipsea (芯海) scale chipsets, sold under many no-name brands and driven by the vendor app
 * "OKOK International". Not to be confused with the broadcast
 * OKOK advertisements handled by [com.health.openscale.core.bluetooth.scales.OkOkHandler],
 * nor with the Chipsea "WeChat scale" protocol of the Hoffen BBS-8107.
 *
 * GATT layout:
 *   service        0000fff0-0000-1000-8000-00805f9b34fb
 *   notifications  0000fff1-…  (scale -> phone)
 *   writes         0000fff2-…  (phone -> scale)
 *
 * Every frame starts with 0xCA. Byte 1 is the frame class:
 *
 *   0x10  single-frame measurement (scale -> phone) and, in the other direction,
 *         the v1.0 user/time sync (phone -> scale, see [buildUserSyncV10])
 *   0x11  v1.1 frames; byte 3 is the subtype:
 *           0 / 1  simple weight frame (subtype doubles as the lock flag)
 *           16     role-info sync (phone -> scale)
 *           17     history retrieve request (phone -> scale)
 *           18     multi-packet live measurement (scale -> phone)
 *           19     multi-packet history record (scale -> phone)
 *           20     user-match confirmation (scale -> phone)
 *           21     select user (phone -> scale, 9 bytes)
 *
 * Measurement frames carry a "property" byte that describes the weight field:
 *   bit  0     lock flag (1 = weight settled, body composition valid)
 *   bits 1..2  decimal digits of the raw weight: 0b01 -> 0, 0b10 -> 2, otherwise 1
 *   bits 3..4  unit of the raw weight: 0b01 -> jin, 0b10 -> lb, 0b11 -> stone, otherwise kg
 *
 * The weight itself is a big-endian uint16; body composition values (present when the
 * lock flag is set) are big-endian too: fat/water/muscle in 0.1 %, BMR in kcal,
 * visceral fat in 0.1 steps and bone mass as a single byte in 0.1 kg.
 *
 * Multi-packet frames (subtypes 18/19) reassemble like this: byte 2 is the chunk payload
 * length + 2, byte 4 packs (chunk index << 4) | total chunks (index is 1-based), and the
 * payload follows at byte 5. The assembled payload layout is:
 *   [0..3]  timestamp, unix seconds (history records only)
 *   [4]     property byte
 *   [6..9]  role/user id, big-endian
 *   [10..11] weight, then fat [12..13], water [14..15], muscle [16..17],
 *            BMR [18..19], visceral fat [20..21], bone [22]
 */
class ChipseaBleLib {

    /** One decoded measurement; composition fields are null when the scale did not send them. */
    data class Measurement(
        val weightKg: Float,
        val locked: Boolean,
        val bodyFatPercent: Float? = null,
        val waterPercent: Float? = null,
        val musclePercent: Float? = null,
        val bmrKcal: Int? = null,
        val visceralFat: Float? = null,
        val boneKg: Float? = null,
        val timestamp: Date? = null,
    )

    private val liveChunks = HashMap<Int, ByteArray>()
    private val historyChunks = HashMap<Int, ByteArray>()

    /**
     * Feed one notification frame. Returns the decoded [Measurement] when the frame (or the
     * multi-packet sequence it completes) carries one, or null for anything else.
     * Unlocked measurements are live previews; locked ones are final.
     */
    fun feed(frame: ByteArray): Measurement? {
        if (frame.size < 4 || frame[0] != HEADER) return null
        return when (frame[1]) {
            CLASS_SINGLE -> parseSingleFrame(frame)
            CLASS_V11 -> parseV11Frame(frame)
            else -> null
        }
    }

    /** 0x10: [4] property, [5..6] weight, composition at [7..17] when locked. */
    private fun parseSingleFrame(frame: ByteArray): Measurement? {
        if (frame.size < 7) return null
        val property = frame[4]
        val locked = isLocked(property)
        val weightKg = decodeWeightKg(frame[5], frame[6], property)
        if (!locked || frame.size < 18) {
            return Measurement(weightKg = weightKg, locked = locked)
        }
        return Measurement(
            weightKg = weightKg,
            locked = true,
            bodyFatPercent = u16be(frame, 7) / 10f,
            waterPercent = u16be(frame, 9) / 10f,
            musclePercent = u16be(frame, 11) / 10f,
            bmrKcal = u16be(frame, 13),
            visceralFat = u16be(frame, 15) / 10f,
            boneKg = (frame[17].toInt() and 0xFF) / 10f,
        )
    }

    private fun parseV11Frame(frame: ByteArray): Measurement? {
        return when (val subtype = frame[3].toInt() and 0xFF) {
            SUBTYPE_WEIGHT_UNLOCKED, SUBTYPE_WEIGHT_LOCKED -> {
                // [5..6] weight, property at [11]; the subtype itself is the lock flag.
                if (frame.size < 12) return null
                Measurement(
                    weightKg = decodeWeightKg(frame[5], frame[6], frame[11]),
                    locked = subtype == SUBTYPE_WEIGHT_LOCKED,
                )
            }
            SUBTYPE_MULTI_LIVE, SUBTYPE_MULTI_HISTORY -> {
                val assembled = reassemble(
                    frame,
                    if (subtype == SUBTYPE_MULTI_LIVE) liveChunks else historyChunks,
                ) ?: return null
                parseAssembled(assembled, history = subtype == SUBTYPE_MULTI_HISTORY)
            }
            else -> null // user-match confirmations and our own echoes are not measurements
        }
    }

    /** Assembled multi-packet payload; see the layout table in the class doc. */
    private fun parseAssembled(payload: ByteArray, history: Boolean): Measurement? {
        if (payload.size < 23) return null
        val property = payload[4]
        if (!isLocked(property)) {
            // Weight has not settled yet — live preview only, like parseSingleFrame;
            // the composition fields are not valid until the lock flag is set.
            return Measurement(
                weightKg = decodeWeightKg(payload[10], payload[11], property),
                locked = false,
                timestamp = if (history) Date(u32be(payload, 0) * 1000L) else null,
            )
        }
        return Measurement(
            weightKg = decodeWeightKg(payload[10], payload[11], property),
            locked = true,
            bodyFatPercent = u16be(payload, 12) / 10f,
            waterPercent = u16be(payload, 14) / 10f,
            musclePercent = u16be(payload, 16) / 10f,
            bmrKcal = u16be(payload, 18),
            visceralFat = u16be(payload, 20) / 10f,
            boneKg = (payload[22].toInt() and 0xFF) / 10f,
            timestamp = if (history) Date(u32be(payload, 0) * 1000L) else null,
        )
    }

    /**
     * Collect one chunk of a multi-packet frame; returns the assembled payload once the
     * last chunk arrives, or null while the sequence is incomplete.
     */
    private fun reassemble(frame: ByteArray, chunks: HashMap<Int, ByteArray>): ByteArray? {
        val payloadLength = (frame[2].toInt() and 0xFF) - 2
        if (payloadLength < 0 || frame.size < 5 + payloadLength) return null
        val index = (frame[4].toInt() and 0xFF) ushr 4
        val total = frame[4].toInt() and 0x0F
        if (total == 0 || index == 0 || index > total) return null
        if (index == 1) chunks.clear()
        chunks[index] = frame.copyOfRange(5, 5 + payloadLength)
        if (index != total) return null
        // Chunk totals can disagree within one sequence (a later frame may carry a
        // smaller total), so the count alone does not prove every index is present.
        if (chunks.size != total || (1..total).any { it !in chunks }) {
            chunks.clear()
            return null
        }
        var assembled = ByteArray(0)
        for (i in 1..total) assembled += chunks.getValue(i)
        chunks.clear()
        return assembled
    }

    companion object {
        const val HEADER: Byte = 0xCA.toByte()

        private const val CLASS_SINGLE: Byte = 0x10
        private const val CLASS_V11: Byte = 0x11

        private const val SUBTYPE_WEIGHT_UNLOCKED = 0
        private const val SUBTYPE_WEIGHT_LOCKED = 1
        private const val SUBTYPE_MULTI_LIVE = 18
        private const val SUBTYPE_MULTI_HISTORY = 19

        /** Property bit 0: the weight has settled and composition values are valid. */
        fun isLocked(property: Byte): Boolean = (property.toInt() and 0x01) != 0

        /**
         * Decode a raw weight (two big-endian bytes) to kilograms using the property byte.
         */
        fun decodeWeightKg(hi: Byte, lo: Byte, property: Byte): Float {
            val p = property.toInt()
            // Stone is a two-part value (stone byte + pounds byte in tenths), units/digits
            // bits do not apply.
            if (((p shr 3) and 0x03) == 0x03) {
                val pounds = (hi.toInt() and 0xFF) * 14f + (lo.toInt() and 0xFF) / 10f
                return pounds * LB_TO_KG
            }
            val raw = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
            val digits = when ((p shr 1) and 0x03) {
                0x01 -> 0
                0x02 -> 2
                else -> 1
            }
            val value = raw / pow10(digits)
            return when ((p shr 3) and 0x03) {
                0x01 -> value * JIN_TO_KG
                0x02 -> value * LB_TO_KG
                else -> value
            }
        }

        /**
         * The v1.0 user sync the vendor app sends on connect: a 20-byte 0x10 frame carrying
         * the current date/time (which also sets the scale's clock), the user id, sex
         * (1 = male, 0 = female), age and height, closed by an XOR checksum over bytes 1..16.
         */
        fun buildUserSyncV10(
            userId: Int,
            isMale: Boolean,
            age: Int,
            heightCm: Int,
            now: Date,
        ): ByteArray {
            val cal = Calendar.getInstance().apply { time = now }
            val frame = ByteArray(20)
            frame[0] = HEADER
            frame[1] = CLASS_SINGLE
            frame[2] = 14
            frame[3] = 1
            frame[4] = (cal.get(Calendar.YEAR) % 100).toByte()
            frame[5] = (cal.get(Calendar.MONTH) + 1).toByte()
            frame[6] = cal.get(Calendar.DAY_OF_MONTH).toByte()
            frame[7] = cal.get(Calendar.HOUR_OF_DAY).toByte()
            frame[8] = cal.get(Calendar.MINUTE).toByte()
            frame[9] = cal.get(Calendar.SECOND).toByte()
            frame[10] = (userId ushr 24).toByte()
            frame[11] = (userId ushr 16).toByte()
            frame[12] = (userId ushr 8).toByte()
            frame[13] = userId.toByte()
            frame[14] = if (isMale) 1 else 0
            frame[15] = age.toByte()
            frame[16] = heightCm.toByte()
            var xor = 0
            for (i in 1..16) xor = xor xor (frame[i].toInt() and 0xFF)
            frame[17] = xor.toByte()
            return frame
        }

        private const val LB_TO_KG = 0.4535924f
        private const val JIN_TO_KG = 0.5f

        private fun pow10(digits: Int): Float = when (digits) {
            0 -> 1f
            2 -> 100f
            else -> 10f
        }

        private fun u16be(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun u32be(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }
}
