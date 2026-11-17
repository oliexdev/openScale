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
 * Wire format of the **Eufy Smart Scale T9140** that advertises as `eufy T9140` (an AiCare/eLink
 * module, service `4143f6b0-5300-4900-4700-414943415245`).
 *
 * Not to be confused with T9140-labelled units that advertise `eufy T9120`: those speak the LeFu
 * protocol and are handled by `OneByoneHandler` ("Eufy A1").
 *
 * ## Provenance
 * Decoded from the three EufyLife-app HCI snoop logs attached to openScale issue #772 (true
 * weights 76.0, 79.9 and 84.0 kg, all in kg mode), cross-checked with the frame names in
 * particleflux's notes and cbondurant's eufy-protocol-reversal, and with the live-weight
 * handling in the eufylife-ble-client library used by Home Assistant. Confirmed on a second
 * unit (2026 firmware, "BK-BLE-1.0", service `4143f7b0`) with direct GATT captures, including a
 * weigh-in made while the scale displayed lb.
 *
 * ## Firmware differences
 * - The #772 unit (2021) exposes service `4143f6b0`; the 2026 unit exposes `4143f7b0` (and
 *   advertises the 16-bit UUIDs `0xF7B0` and `0xD618`). Characteristics are `…b1` (write) and
 *   `…b2` (notify) in both families.
 * - The 2026 unit sends two constant frames before every result, `AC 02 F4 23 10 0B CC ck` and
 *   `AC 02 F7 23 15 2A CC ck` (meaning unknown, see [Frame.Preamble]), and never sends the
 *   19-byte `FE` final record: its result ends with "measurement finished".
 * - The 2026 unit delivers a result that was measured before the connection right after
 *   subscribing, without any write, and stays connected for further weigh-ins.
 *
 * ## Framing
 * Every frame starts `AC 02`. Short frames are 8 bytes (9 for the timestamp frames):
 * ```
 *   AC 02 <b2> <b3> <b4> <b5> <chan> <ck>      ck = sum(bytes[2 .. size-2]) & 0xFF
 * ```
 * The same framing as the MGB family (see MGBHandler), but on a different service and with a
 * different weight step. A notification can carry two frames back to back: 16 bytes = 8 + 8 and
 * 17 bytes = 9 (`F1` timestamp) + 8.
 *
 * Scale -> app, all seen in the captures:
 * ```
 *   AC 02 <w hi> <w lo> 00 00 CE ck            live weight, 0.1 kg, big-endian
 *   AC 02 <w hi> <w lo> 00 00 CA ck            stable weight
 *   AC 02 FD 00 00 00 CB ck                    impedance measurement started
 *   AC 02 FD 01 <z hi> <z lo> CB ck            impedance result, ohms, big-endian
 *   AC 02 F1 <epoch LE32> CC ck                measurement time (scale clock, UTC epoch)
 *   AC 02 FE 10 00 00 CC ck                    measurement finished
 *   AC 02 FE <record> 00 x8                    19 bytes, no checksum: the measurement just taken
 *   AC 02 FE 01 00 00 CF ck / FE 02 ...        history start / history end
 *   AC 02 FF <record> <record>                 19 bytes, no checksum: stored weigh-ins,
 *                                              an all-zero record is an empty slot
 *   AC 02 FE 07 00 00 CC ck                    time sync accepted (08 = rejected)
 *   AC 02 FE 06 FE 00 CC ck                    unit accepted
 *   AC 02 FE 1C FF 00 CC ck / FE 1A ...        about to power off / powering off
 *
 *   record = <epoch LE32> <weight BE16, 0.1 kg> <impedance BE16, ohms>
 * ```
 * App -> scale (characteristic `…f6b1`, write without response), in this order:
 * ```
 *   AC 02 F2 <epoch LE32> CC ck                set clock (UTC epoch)
 *   AC 02 FE 06 <unit> 00 CC ck                display unit, 00 = kg
 *   AC 02 FF 00 00 00 CF ck                    request stored weigh-ins
 * ```
 * Weights are always sent in 0.1 kg, whatever unit the display shows (seen with the display in
 * lb). Only unit code 0 = kg has been seen acknowledged; vendor-app notes suggest 1 = lb and
 * 2 = st, but no capture confirms them, so they are not used.
 */
object EufyT9140Lib {

    const val HEADER_0 = 0xAC
    const val HEADER_1 = 0x02

    const val CHAN_COMMAND = 0xCC
    const val CHAN_HISTORY = 0xCF
    const val CHAN_WEIGHT_LIVE = 0xCE
    const val CHAN_WEIGHT_STABLE = 0xCA
    const val CHAN_IMPEDANCE = 0xCB

    const val STATUS_HISTORY_START = 0x01
    const val STATUS_HISTORY_END = 0x02
    const val STATUS_UNIT_SET = 0x06
    const val STATUS_TIME_SYNC_OK = 0x07
    const val STATUS_TIME_SYNC_FAILED = 0x08
    const val STATUS_MEASUREMENT_FINISHED = 0x10
    const val STATUS_POWER_OFF = 0x1A
    const val STATUS_READY_POWER_OFF = 0x1C

    /** The only display-unit code seen acknowledged; the codes for lb and st are not known. */
    const val UNIT_KG = 0x00

    /** One stored or just-taken weigh-in. */
    data class Record(val epochSeconds: Long, val weightDeciKg: Int, val impedanceOhm: Int) {
        val weightKg: Float get() = weightDeciKg / 10.0f
    }

    sealed interface Frame {
        data class Weight(val deciKg: Int, val stable: Boolean) : Frame {
            val kg: Float get() = deciKg / 10.0f
        }
        object ImpedanceStarted : Frame
        data class Impedance(val ohm: Int) : Frame
        data class MeasurementTime(val epochSeconds: Long) : Frame
        data class Status(val code: Int, val arg: Int) : Frame
        data class FinalRecord(val record: Record) : Frame
        /** `AC 02 F4|F7 …` sent by 2026 firmware just before each result; payload not decoded. */
        data class Preamble(val code: Int) : Frame
        data class History(val records: List<Record>) : Frame
        data class Unknown(val bytes: ByteArray, val reason: String) : Frame
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    /** Checksum of a short frame: sum of every byte after `AC 02`, before the checksum byte. */
    fun checksum(frame: ByteArray): Int {
        var s = 0
        for (i in 2 until frame.size - 1) s += u8(frame, i)
        return s and 0xFF
    }

    private fun hasHeader(b: ByteArray, at: Int = 0) =
        b.size >= at + 2 && u8(b, at) == HEADER_0 && u8(b, at + 1) == HEADER_1

    /** Splits one notification into its frames. Bytes that do not form a frame are dropped. */
    fun split(notification: ByteArray): List<ByteArray> {
        if (notification.size == 19 && hasHeader(notification)) return listOf(notification)
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + 8 <= notification.size && hasHeader(notification, i)) {
            val len = if (u8(notification, i + 2) == 0xF1 || u8(notification, i + 2) == 0xF2) 9 else 8
            if (i + len > notification.size) break
            out += notification.copyOfRange(i, i + len)
            i += len
        }
        return out
    }

    private fun record(b: ByteArray, at: Int): Record? {
        if ((at until at + 8).all { b[it].toInt() == 0 }) return null
        return Record(le32(b, at), be16(b, at + 4), be16(b, at + 6))
    }

    /** Decodes a single frame produced by [split]. */
    fun parse(f: ByteArray): Frame {
        if (!hasHeader(f)) return Frame.Unknown(f, "no AC 02 header")
        if (f.size == 19) {
            return when (u8(f, 2)) {
                0xFE -> record(f, 3)?.let { Frame.FinalRecord(it) } ?: Frame.Unknown(f, "empty final record")
                0xFF -> Frame.History(listOfNotNull(record(f, 3), record(f, 11)))
                else -> Frame.Unknown(f, "unknown 19-byte frame")
            }
        }
        if (f.size != 8 && f.size != 9) return Frame.Unknown(f, "unexpected length ${f.size}")
        if (checksum(f) != u8(f, f.size - 1)) return Frame.Unknown(f, "bad checksum")

        val b2 = u8(f, 2)
        val b3 = u8(f, 3)
        val chan = u8(f, f.size - 2)
        if (f.size == 9) {
            return if (b2 == 0xF1 && chan == CHAN_COMMAND) Frame.MeasurementTime(le32(f, 3))
            else Frame.Unknown(f, "unknown 9-byte frame")
        }
        return when {
            chan == CHAN_WEIGHT_LIVE || chan == CHAN_WEIGHT_STABLE ->
                Frame.Weight(be16(f, 2), stable = chan == CHAN_WEIGHT_STABLE)
            chan == CHAN_IMPEDANCE && b2 == 0xFD && b3 == 0x00 -> Frame.ImpedanceStarted
            chan == CHAN_IMPEDANCE && b2 == 0xFD && b3 == 0x01 -> Frame.Impedance(be16(f, 4))
            b2 == 0xFE && (chan == CHAN_COMMAND || chan == CHAN_HISTORY) -> Frame.Status(b3, u8(f, 4))
            (b2 == 0xF4 || b2 == 0xF7) && chan == CHAN_COMMAND -> Frame.Preamble(b2)
            else -> Frame.Unknown(f, "unknown frame")
        }
    }

    /** Parses every frame in a notification. */
    fun parseNotification(notification: ByteArray): List<Frame> = split(notification).map(::parse)

    /** Builds `AC 02 <payload> <ck>`; [payload] includes the channel byte. */
    fun command(vararg payload: Int): ByteArray {
        val out = ByteArray(payload.size + 3)
        out[0] = HEADER_0.toByte()
        out[1] = HEADER_1.toByte()
        payload.forEachIndexed { i, v -> out[i + 2] = v.toByte() }
        out[out.size - 1] = (payload.sum() and 0xFF).toByte()
        return out
    }

    fun timeSync(epochSeconds: Long): ByteArray = command(
        0xF2,
        (epochSeconds and 0xFF).toInt(),
        ((epochSeconds shr 8) and 0xFF).toInt(),
        ((epochSeconds shr 16) and 0xFF).toInt(),
        ((epochSeconds shr 24) and 0xFF).toInt(),
        CHAN_COMMAND,
    )

    fun setUnit(unit: Int): ByteArray = command(0xFE, 0x06, unit and 0xFF, 0x00, CHAN_COMMAND)

    fun historyRequest(): ByteArray = command(0xFF, 0x00, 0x00, 0x00, CHAN_HISTORY)
}
