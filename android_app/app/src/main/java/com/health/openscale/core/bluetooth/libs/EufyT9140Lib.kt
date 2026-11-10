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
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Protocol derived from bdr99/eufylife-ble-client (MIT, © bdr99),
 * eufylife_ble_client/client.py (T9140 model + frame splitting) and from the
 * 2021 reverse-engineering notes by particleflux
 * (gist.github.com/particleflux/2a9b9255b07631989f2e7096b12c8485), which
 * document the ac02 command framing and the history record layout.
 */
package com.health.openscale.core.bluetooth.libs

/**
 * Pure protocol helpers for the Eufy Smart Scale T9140 (marketed as "Smart Scale", early
 * "BodySense" units).
 *
 * The T9140 predates the fff0 family used by the Eufy C1/P1/A1 and the authenticated fff0
 * family of the P2/P2 Pro, so nothing in [OneByoneLib]'s driver or [EufyP2Lib] applies. It
 * talks over vendor service `4143f6b0-…` (some firmware exposes `4143f7b0-…` and a
 * `0000ffb0-…` fallback), notifying on `…f6b2` and accepting writes on `…f6b1`. There is no
 * pairing step and no encryption.
 *
 * Wire format (all commands):
 *
 *     AC 02 <body…> <checksum>        checksum = sum(body) & 0xFF
 *
 * Weigh-in notifications are 7-byte frames, sometimes wrapped in a 16/17-byte container that
 * holds two frames (see [splitFrames]):
 *
 *     AC 02 <weight BE, 0.1 kg> <impedance BE, Ω> <CA = settled | CE = still moving>
 *
 * The same weight/impedance pair is what the scale stores in its history record
 * (`AC 02 FF <unix time BE> <weight> <impedance> …`), which is where these offsets were
 * confirmed.
 *
 * Everything here is pure and covered by `EufyT9140LibTest`; the BLE wiring lives in
 * [com.health.openscale.core.bluetooth.scales.EufyT9140Handler].
 */
object EufyT9140Lib {

    /** One decoded weigh-in frame. [impedanceOhm] is 0 when the scale did not measure it. */
    data class Reading(val weightKg: Float, val impedanceOhm: Int, val isFinal: Boolean)

    /** Frame marker for a settled reading (the one worth saving). */
    const val MARKER_FINAL = 0xCA

    /** Frame marker for a reading still in progress. */
    const val MARKER_INTERIM = 0xCE

    private const val PREFIX_A = 0xAC
    private const val PREFIX_B = 0x02

    /** Plausible impedance window; outside it the bytes were not a bioimpedance result. */
    private val IMPEDANCE_RANGE = 100..3000

    /** Plausible weight window in kg, used to reject frames that only look like a reading. */
    private val WEIGHT_RANGE = 2.0f..300.0f

    // ── framing ────────────────────────────────────────────────────────────────

    /** Byte sum of [body], wrapped to one byte. */
    fun checksum(body: ByteArray): Int {
        var sum = 0
        for (b in body) sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        return sum
    }

    /** Wrap a command body into `AC 02 <body> <checksum>`. */
    fun command(body: ByteArray): ByteArray = ByteArray(body.size + 3).also { out ->
        out[0] = PREFIX_A.toByte()
        out[1] = PREFIX_B.toByte()
        body.copyInto(out, 2)
        out[out.size - 1] = checksum(body).toByte()
    }

    /**
     * Set the scale clock: `F2 <unix seconds, little endian> CC`.
     *
     * The 2021 notes show the four timestamp bytes in transmission order as `2b 20 b2 60` for
     * an epoch of `0x60b2202b`, i.e. little endian, although the same notes call it big
     * endian. A wrong clock only misdates the scale's own history, so this is low risk.
     */
    fun timeSyncCommand(epochSeconds: Long): ByteArray {
        val body = byteArrayOf(
            0xF2.toByte(),
            (epochSeconds and 0xFF).toByte(),
            ((epochSeconds shr 8) and 0xFF).toByte(),
            ((epochSeconds shr 16) and 0xFF).toByte(),
            ((epochSeconds shr 24) and 0xFF).toByte(),
            0xCC.toByte()
        )
        return command(body)
    }

    /**
     * Set the displayed weight unit: `FE 06 <unit> 00 CC`, where 0 = kg, 1 = lb, 2 = st.
     * The scale answers `AC 02 FE 06 FE 00 CC CE` on success.
     */
    fun unitCommand(unit: Int): ByteArray =
        command(byteArrayOf(0xFE.toByte(), 0x06, unit.toByte(), 0x00, 0xCC.toByte()))

    /**
     * Ask for the stored weigh-ins: `FF 00 00 00 CF`. The scale answers `AC 02 FE 01 00 00 CF CE`
     * followed by records; the record parser is not implemented yet (see
     * [com.health.openscale.core.bluetooth.scales.EufyT9140Handler]).
     */
    fun historyRequestCommand(): ByteArray =
        command(byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0xCF.toByte()))

    // ── parsing ─────────────────────────────────────────────────────────────────

    /**
     * Split a notification into the individual 7-byte frames it carries.
     *
     * Most notifications are a single frame and are returned as-is. A 16- or 17-byte frame is
     * the scale packing two frames into one notification — the second one lives in the tail
     * (`[8..14]` / `[9..15]`), the first one is the head (`[0..7]` / `[0..8]`).
     */
    fun splitFrames(data: ByteArray): List<ByteArray> {
        if (data.size >= 2 &&
            (data[0].toInt() and 0xFF) == PREFIX_A &&
            (data[1].toInt() and 0xFF) == PREFIX_B
        ) {
            when (data.size) {
                16 -> return listOf(data.copyOfRange(8, 15), data.copyOfRange(0, 8))
                17 -> return listOf(data.copyOfRange(9, 16), data.copyOfRange(0, 9))
            }
        }
        return listOf(data)
    }

    /**
     * Decode one frame, or return null when it is not a weigh-in frame.
     *
     * The weight is a big-endian count of 0.1 kg at bytes 2..3 and the impedance a big-endian
     * count of ohms at bytes 4..5; byte 6 marks a settled (0xCA) or in-progress (0xCE) reading.
     */
    fun parseReading(frame: ByteArray): Reading? {
        if (frame.size < 7) return null

        val marker = frame[6].toInt() and 0xFF
        if (marker != MARKER_FINAL && marker != MARKER_INTERIM) return null

        val rawWeight = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val weightKg = rawWeight / 10.0f
        if (weightKg !in WEIGHT_RANGE) return null

        val rawImpedance = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        val impedance = if (rawImpedance in IMPEDANCE_RANGE) rawImpedance else 0

        return Reading(weightKg = weightKg, impedanceOhm = impedance, isFinal = marker == MARKER_FINAL)
    }

    /** Lower-case hex dump for `logD` lines; no dependency on the app's formatting helpers. */
    fun toHex(data: ByteArray): String =
        data.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
