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
 * Wire protocol of the ALPHA Smart Scale PRO 3 (model SCP-03), a FitDays/icomon scale that
 * advertises as "MY_SCALE" with service 0xFFB0 (0xFFB1 write, 0xFFB2 notify). Reverse-engineered
 * from HCI snoop captures of the vendor's FitDays app; see openScale issue #1358.
 *
 * Every frame is exactly 20 bytes, in both directions:
 *
 *     [0xAC][0x27][body @2..17][wire type @18][checksum @19]
 *
 * The scale's checksum is `sum(raw[2..18]) & 0x1F` (all 100+ captured frames validate). The phone
 * writes the full `sum(raw[2..18]) & 0xFF`, which the scale accepts; both are used as captured.
 *
 * Wire types seen on the air:
 *
 *   0xD5  live weight (scale -> phone), ~4 Hz while someone stands on the scale:
 *           [AC 27][state][weight24][02 00 30 01 00 00 00 00 00 00 00][24][D5][chk]
 *         state 0x00 = settling, 0x80 = locked (the scale's display has stopped).
 *   0xD6  final result (scale -> phone), ~2 s after the weight locked:
 *           [AC 27][01][00][impedance16][01][state][weight24][00 x6][24][D6][chk]
 *         impedance is 0 when it could not be measured (socks). Streams stop after it.
 *   0xD8  stored record (scale -> phone), pushed right after connecting for weigh-ins done
 *         without the app; the phone ACKs each one:
 *           [AC 27][00][unix32][state][weight24][00 00][00 01 00 00][24][D8][chk]
 *         The two bytes after the weight were 0 in every captured record; whether they carry
 *         the impedance is unverified, so they are not decoded.
 *   0xD0  time + user profile (phone -> scale), sent on connect:
 *           [AC 27][unix32][08 00][00][height cm][00 00][age][sex][00 00 03 00][D0][chk]
 *         sex: 1 = male, 2 = female (FitDays' profile bytes matched the user's account).
 *   0xDF  control (phone -> scale):
 *           [AC 27][sub-command][argument][00 x15][DF][chk]
 *         sub-command 0x04 = ACK of the wire type given as argument (0xD6, 0xD8);
 *         sub-command 0x01 with no argument is sent once by FitDays after the profile.
 *
 * Weight is a 24-bit big-endian word whose low 18 bits are grams (0x695B8A -> 88 970 g); the upper
 * six bits were constant (0b011010) in every capture. FitDays displays the value rounded to the
 * scale's 0.05 kg display resolution. Timestamps are Unix seconds, big-endian.
 *
 * Pure Kotlin, no Android dependencies, so it can be unit tested on the JVM.
 */
object AlphaSmartScalePro3Lib {

    const val FRAME_SIZE = 20
    const val HEADER_0 = 0xAC
    const val HEADER_1 = 0x27

    // Wire types (byte 18).
    const val WIRE_LIVE_WEIGHT = 0xD5     // scale -> phone
    const val WIRE_FINAL_RESULT = 0xD6    // scale -> phone
    const val WIRE_STORED_RECORD = 0xD8   // scale -> phone
    const val WIRE_PROFILE = 0xD0         // phone -> scale
    const val WIRE_CONTROL = 0xDF         // phone -> scale

    // Control sub-commands (byte 2 of a 0xDF frame).
    const val CMD_SESSION_START = 0x01    // sent once by FitDays after the profile; purpose unknown
    const val CMD_ACK = 0x04              // byte 3 = wire type being acknowledged

    const val SEX_MALE = 1
    const val SEX_FEMALE = 2

    /** Bit set in the state byte once the scale considers the weight final. */
    const val STATE_LOCKED = 0x80

    /** Weight lives in the low 18 bits of the 24-bit weight word. */
    const val WEIGHT_G_MASK = 0x3FFFF

    sealed class ScaleFrame {
        /** Live weight while someone stands on the scale (wire type 0xD5). */
        data class LiveWeight(val weightGrams: Int, val locked: Boolean) : ScaleFrame()

        /** Final result of a weigh-in (wire type 0xD6); [impedanceOhms] is 0 when not measured. */
        data class FinalResult(val weightGrams: Int, val impedanceOhms: Int, val locked: Boolean) : ScaleFrame()

        /** A weigh-in done without the app, replayed on connect (wire type 0xD8). */
        data class StoredRecord(val timestampEpochSeconds: Long, val weightGrams: Int, val locked: Boolean) : ScaleFrame()

        /** A well-formed frame of a wire type this library does not decode. */
        data class Unknown(val wireType: Int) : ScaleFrame()
    }

    // --- Frame validation ------------------------------------------------------

    /** The scale's 5-bit checksum over raw[2..18]. */
    fun scaleChecksum(raw: ByteArray): Int = sum(raw) and 0x1F

    /** The phone's 8-bit checksum over raw[2..18]. */
    fun phoneChecksum(raw: ByteArray): Int = sum(raw) and 0xFF

    private fun sum(raw: ByteArray): Int {
        var s = 0
        for (i in 2 until 19) s += raw[i].toInt() and 0xFF
        return s
    }

    private fun hasHeader(raw: ByteArray): Boolean =
        raw.size == FRAME_SIZE &&
            (raw[0].toInt() and 0xFF) == HEADER_0 &&
            (raw[1].toInt() and 0xFF) == HEADER_1

    /** True for a 20-byte frame with the AC 27 header and a valid scale checksum. */
    fun isValidScaleFrame(raw: ByteArray): Boolean =
        hasHeader(raw) && (raw[19].toInt() and 0xFF) == scaleChecksum(raw)

    /** Wire type of a frame (byte 18), or -1 if the frame is too short. */
    fun wireTypeOf(raw: ByteArray): Int = if (raw.size == FRAME_SIZE) raw[18].toInt() and 0xFF else -1

    // --- Parsing (scale -> phone) ----------------------------------------------

    /**
     * Decode one notification. Returns null for anything that is not a valid scale frame
     * (wrong length, wrong header, bad checksum).
     */
    fun parse(raw: ByteArray): ScaleFrame? {
        if (!isValidScaleFrame(raw)) return null
        return when (wireTypeOf(raw)) {
            WIRE_LIVE_WEIGHT -> ScaleFrame.LiveWeight(
                weightGrams = weightGrams(raw, 3),
                locked = isLocked(raw[2]),
            )
            WIRE_FINAL_RESULT -> ScaleFrame.FinalResult(
                weightGrams = weightGrams(raw, 8),
                impedanceOhms = u16(raw, 4),
                locked = isLocked(raw[7]),
            )
            WIRE_STORED_RECORD -> ScaleFrame.StoredRecord(
                timestampEpochSeconds = u32(raw, 3),
                weightGrams = weightGrams(raw, 8),
                locked = isLocked(raw[7]),
            )
            else -> ScaleFrame.Unknown(wireTypeOf(raw))
        }
    }

    private fun isLocked(state: Byte): Boolean = (state.toInt() and STATE_LOCKED) != 0

    private fun u16(raw: ByteArray, at: Int): Int =
        ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)

    private fun u32(raw: ByteArray, at: Int): Long =
        (u16(raw, at).toLong() shl 16) or u16(raw, at + 2).toLong()

    /** Low 18 bits of the big-endian 24-bit word at [at], in grams. */
    fun weightGrams(raw: ByteArray, at: Int): Int =
        (((raw[at].toInt() and 0xFF) shl 16) or u16(raw, at + 1)) and WEIGHT_G_MASK

    // --- Commands (phone -> scale) ---------------------------------------------

    /**
     * AC 27 <unix32> 08 00 00 <height> 00 00 <age> <sex> 00 00 03 00 D0 <chk> — the time/profile
     * frame FitDays writes right after enabling notifications. [sex] is [SEX_MALE] or [SEX_FEMALE].
     */
    fun buildProfile(nowEpochSeconds: Long, heightCm: Int, age: Int, sex: Int): ByteArray {
        val body = ByteArray(16)
        putU32(body, 0, nowEpochSeconds)
        body[4] = 0x08
        body[5] = 0x00
        body[6] = 0x00
        body[7] = heightCm.coerceIn(0, 255).toByte()
        body[8] = 0x00
        body[9] = 0x00
        body[10] = age.coerceIn(0, 255).toByte()
        body[11] = sex.toByte()
        body[12] = 0x00
        body[13] = 0x00
        body[14] = 0x03
        body[15] = 0x00
        return buildPhoneFrame(body, WIRE_PROFILE)
    }

    /** AC 27 04 <wireType> 00... DF <chk> — acknowledges a final result or stored record. */
    fun buildAck(wireType: Int): ByteArray =
        buildPhoneFrame(byteArrayOf(CMD_ACK.toByte(), wireType.toByte()) + ByteArray(14), WIRE_CONTROL)

    /** AC 27 01 00... DF <chk> — sent once per session by FitDays after the profile. */
    fun buildSessionStart(): ByteArray =
        buildPhoneFrame(byteArrayOf(CMD_SESSION_START.toByte()) + ByteArray(15), WIRE_CONTROL)

    private fun buildPhoneFrame(body: ByteArray, wireType: Int): ByteArray {
        require(body.size == 16) { "body must be 16 bytes, got ${body.size}" }
        val f = ByteArray(FRAME_SIZE)
        f[0] = HEADER_0.toByte()
        f[1] = HEADER_1.toByte()
        body.copyInto(f, 2)
        f[18] = wireType.toByte()
        f[19] = phoneChecksum(f).toByte()
        return f
    }

    private fun putU32(dst: ByteArray, at: Int, value: Long) {
        dst[at] = ((value ushr 24) and 0xFF).toByte()
        dst[at + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 3] = (value and 0xFF).toByte()
    }
}
