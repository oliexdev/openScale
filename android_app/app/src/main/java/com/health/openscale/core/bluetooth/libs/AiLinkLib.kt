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
 * Decoder for the **AiLink / eLink broadcast body-fat scale** protocol
 * (vendor apps `com.pingwang.elink` / "AiLink", chipsets branded Aicare, Pingwang, eLink).
 *
 * These scales are *non-connectable*: they never accept a GATT link and instead repeat the whole
 * measurement inside the manufacturer-specific data of an `ADV_NONCONN_IND` advertisement, with
 * the interesting part encrypted.
 *
 * ## Provenance
 * The protocol described below was determined for interoperability, by observing live
 * advertisements from an "EL1" scale and by studying the behaviour of the vendor's own
 * implementation (`BleValueBean.init`, `MainModel.BroadCast`, `BroadcastScaleDeviceData.dataCheck`
 * and the native `libAILinkBle-lib.so`). What was recovered are facts about the wire format —
 * field offsets, the checksum, the cipher and its key schedule — not source text. The
 * implementation here is original: TEA itself is a published algorithm, and every routine below
 * was written from the documented layout rather than transcribed from any decompiler output.
 *
 * ## Advertisement layout
 * The scale advertises service `0000F0A0` and one manufacturer record. AiLink abuses the
 * manufacturer field: the two bytes Android parses as the *company id* are really the vendor's
 * CID and VID, so `ScanRecord.getManufacturerSpecificData(companyId)` hands back the record
 * already stripped of them. Indices below are into that stripped array:
 *
 * ```
 * companyId = VID << 8 | CID          (0x0301 -> CID 0x01, VID 0x03)
 *   [0]      PID
 *   [1..6]   MAC address, little-endian (reverse for display)
 *   [7]      checksum: sum of [8..17] & 0xFF
 *   [8..17]  10-byte payload, TEA-encrypted
 * ```
 *
 * ## Payload encryption
 * `AiLinkPwdUtil.decryptBroadcast` is plain 32-round [TEA](https://en.wikipedia.org/wiki/Tiny_Encryption_Algorithm)
 * over **only the first 8 bytes** — bytes [8..9] of the payload are passed through untouched, and
 * this decoder keeps that quirk so frames decode the same way the scale meant them. The 128-bit key
 * is built inline from the device ids rather than from `get_ailink_key`'s MD5 path
 * (see [broadcastKey]).
 *
 * ## Decrypted payload
 * ```
 *   [0]      rolling sequence counter
 *   [1]      status (see STATUS_* below)
 *   [2]      flags: bits 3..5 = weight unit, bits 1..2 = decimal places
 *   [3..4]   weight, big-endian; bit 7 of [3] is a negative-value flag
 *   [5..6]   impedance in ohms, big-endian
 *   [7]      body-composition algorithm id
 *   [8..9]   not decrypted, meaning unverified — deliberately not decoded here
 * ```
 *
 * Only the fields above were observed on real hardware. Body composition is intentionally *not*
 * computed: the raw impedance is reported and openScale derives the rest.
 */
object AiLinkLib {

    // --- Status byte (payload[1]), named after the vendor app's own UI branches ---------------

    /** Live weight while the user is still settling; also sent when the scale is idle. */
    const val STATUS_MEASURING = 0x00

    /** Impedance measurement running ("data analyzing" in the vendor UI). */
    const val STATUS_ANALYSING = 0x01

    /** Impedance result available. */
    const val STATUS_IMPEDANCE_READY = 0x02

    /** Impedance measurement failed; the vendor app zeroes the impedance in this case. */
    const val STATUS_IMPEDANCE_FAILED = 0x03

    /** Vendor "data mode 4" frame. Branched on by the vendor app but never observed here. */
    const val STATUS_DATA_MODE = 0x04

    /** Measurement finished — final weight and impedance. This is the frame worth recording. */
    const val STATUS_COMPLETE = 0xFF

    /**
     * Every status the vendor app branches on.
     *
     * The advertisement's checksum covers the *still-encrypted* payload, so it proves the record
     * arrived intact but not that it is AiLink at all — an all-zero record from an unrelated
     * vendor checksums perfectly. Requiring the decrypted status byte to be one of these is the
     * cheapest way to tell "decrypted with the right key" from "decrypted into noise".
     */
    private val KNOWN_STATUSES = setOf(
        STATUS_MEASURING,
        STATUS_ANALYSING,
        STATUS_IMPEDANCE_READY,
        STATUS_IMPEDANCE_FAILED,
        STATUS_DATA_MODE,
        STATUS_COMPLETE,
    )

    // --- Weight unit (payload[2], bits 3..5) --------------------------------------------------

    /** Kilograms. The only unit seen on real hardware, so the only one [Broadcast.weightKg] trusts. */
    const val UNIT_KG = 0

    /** Chinese jin, per the vendor's `UnitUtil.weightUnitToString`. Never observed; not decoded. */
    const val UNIT_JIN = 1

    /** Pounds, per the vendor's `UnitUtil.weightUnitToString`. Never observed; not decoded. */
    const val UNIT_LB = 6

    // --- Frame geometry -----------------------------------------------------------------------

    /** Manufacturer record length once Android has stripped the two company-id bytes. */
    private const val RECORD_LENGTH = 18
    private const val MAC_OFFSET = 1
    private const val MAC_LENGTH = 6
    private const val CHECKSUM_INDEX = 7
    private const val PAYLOAD_OFFSET = 8
    private const val PAYLOAD_LENGTH = 10

    /** TEA covers only the first 8 bytes of the payload; see the class docs. */
    private const val ENCRYPTED_LENGTH = 8

    // --- TEA parameters -----------------------------------------------------------------------

    private const val TEA_ROUNDS = 32

    /** TEA's golden-ratio delta, 0x9E3779B9 as a signed [Int]. */
    private val DELTA = 0x9E3779B9.toInt()

    /** Decryption starts from delta * 32, i.e. 0xC6EF3720. */
    private val SUM_INITIAL = 0xC6EF3720.toInt()

    /**
     * Per-word constants the native library adds to the device ids to form the TEA key.
     * Their bytes spell fragments of "AILink"/"1x1"/"1el0", which is presumably the point.
     */
    private const val KEY_BASE_PID = 0x41493000
    private const val KEY_BASE_VID = 0x4C327900
    private const val KEY_BASE_CID = 0x31783100
    private const val KEY_WORD_3 = 0x306C6531

    /** Device ids at or above this are folded back down before being mixed into the key. */
    private const val ID_FOLD_THRESHOLD = 0x10000
    private const val ID_FOLD_AMOUNT = 0xFFFF

    /**
     * One decoded advertisement.
     *
     * @property macAddress  the scale's MAC in display order, e.g. `0A:1B:2C:3D:4E:5F`.
     * @property sequence    rolling counter; the vendor app uses it with [status] to drop repeats.
     * @property status      one of the `STATUS_*` constants.
     * @property weightUnit  raw unit code; see the `UNIT_*` constants.
     * @property decimals    number of implied decimal places in [rawWeight].
     * @property isNegative  the scale flagged the reading as negative (the vendor app rejects those).
     * @property rawWeight   weight before the decimal point is applied.
     * @property weightKg    [rawWeight] scaled to kilograms, or `null` when [weightUnit] is not
     *                       [UNIT_KG] — the other unit codes have never been observed, so this
     *                       library refuses to guess rather than record a wrong weight.
     * @property impedance   body impedance in ohms; 0 when the scale reports none.
     * @property algorithm   vendor body-composition algorithm id (informational).
     */
    data class Broadcast(
        val macAddress: String,
        val sequence: Int,
        val status: Int,
        val weightUnit: Int,
        val decimals: Int,
        val isNegative: Boolean,
        val rawWeight: Int,
        val weightKg: Float?,
        val impedance: Int,
        val algorithm: Int,
    ) {
        /** True once the scale has finished weighing *and* measuring impedance. */
        val isComplete: Boolean get() = status == STATUS_COMPLETE

        /**
         * The weight in kg, but only when the reading passes the vendor app's own plausibility
         * gate: positive, non-zero, and in a unit we understand. `null` otherwise.
         *
         * Single source of truth for "is there a weight worth recording here", so callers unwrap
         * once instead of testing [isUsable] and then re-checking [weightKg] for null.
         */
        val usableWeightKg: Float?
            get() = weightKg?.takeIf { !isNegative && rawWeight != 0 }

        /** Whether this frame carries a weight worth recording; see [usableWeightKg]. */
        val isUsable: Boolean get() = usableWeightKg != null
    }

    /**
     * Decodes one manufacturer record, or returns `null` if it is not a well-formed AiLink
     * broadcast: too short, checksum mismatch over the encrypted payload, or a decrypted status
     * outside [KNOWN_STATUSES] (which is what rejects another vendor's record that happens to
     * satisfy the checksum).
     *
     * @param companyId the id Android keyed the record by; really `VID << 8 | CID`.
     * @param data      the record with the company-id bytes already stripped.
     */
    fun parse(companyId: Int, data: ByteArray): Broadcast? {
        if (data.size < RECORD_LENGTH) return null

        val cid = companyId and 0xFF
        val vid = (companyId shr 8) and 0xFF
        val pid = data[0].toInt() and 0xFF

        val encrypted = data.copyOfRange(PAYLOAD_OFFSET, PAYLOAD_OFFSET + PAYLOAD_LENGTH)
        if (checksum8(encrypted) != (data[CHECKSUM_INDEX].toInt() and 0xFF)) return null

        val p = decryptBroadcast(encrypted, cid, vid, pid)

        val status = p[1].toInt() and 0xFF
        if (status !in KNOWN_STATUSES) return null

        val flags = p[2].toInt() and 0xFF
        val unit = (flags shr 3) and 0x07
        val decimals = (flags shr 1) and 0x03
        val rawWeight = ((p[3].toInt() and 0x7F) shl 8) or (p[4].toInt() and 0xFF)

        return Broadcast(
            macAddress = macOf(data),
            sequence = p[0].toInt() and 0xFF,
            status = status,
            weightUnit = unit,
            decimals = decimals,
            isNegative = (p[3].toInt() and 0x80) != 0,
            rawWeight = rawWeight,
            weightKg = if (unit == UNIT_KG) applyDecimals(rawWeight, decimals) else null,
            impedance = ((p[5].toInt() and 0xFF) shl 8) or (p[6].toInt() and 0xFF),
            algorithm = p[7].toInt() and 0xFF,
        )
    }

    /**
     * Builds the 128-bit TEA key the broadcast payload is encrypted with.
     *
     * Derived from the observed behaviour of
     * `Java_com_pinwang_ailinkble_AiLinkPwdUtil_decryptBroadcast`, which builds the key inline
     * from the device ids instead of going through `get_ailink_key`'s MD5 path. Ids at or above
     * 0x10000 are first reduced by 0xFFFF, which is how the vendor's device *types* (65537,
     * 65557, …) collapse into small numbers.
     */
    fun broadcastKey(cid: Int, vid: Int, pid: Int): IntArray = intArrayOf(
        fold(pid) + KEY_BASE_PID,
        fold(vid) + KEY_BASE_VID,
        fold(cid) + KEY_BASE_CID,
        KEY_WORD_3,
    )

    /**
     * TEA-decrypts the first 8 bytes of [payload] in a copy, leaving any trailing bytes as-is
     * (the native implementation does the same). The two 32-bit halves are read little-endian.
     */
    fun decryptBroadcast(payload: ByteArray, cid: Int, vid: Int, pid: Int): ByteArray {
        val out = payload.copyOf()
        if (out.size < ENCRYPTED_LENGTH) return out

        val k = broadcastKey(cid, vid, pid)
        var v0 = readLe32(out, 0)
        var v1 = readLe32(out, 4)
        var sum = SUM_INITIAL

        repeat(TEA_ROUNDS) {
            v1 -= ((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3])
            v0 -= ((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1])
            sum -= DELTA
        }

        writeLe32(out, 0, v0)
        writeLe32(out, 4, v1)
        return out
    }

    // --- Helpers ------------------------------------------------------------------------------

    private fun fold(id: Int) = if (id >= ID_FOLD_THRESHOLD) id - ID_FOLD_AMOUNT else id

    private fun checksum8(bytes: ByteArray): Int =
        bytes.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF

    /** Weight is transmitted as an integer with [decimals] implied decimal places. */
    private fun applyDecimals(raw: Int, decimals: Int): Float = when (decimals) {
        1 -> raw / 10f
        2 -> raw / 100f
        3 -> raw / 1000f
        else -> raw.toFloat()
    }

    /** The MAC sits little-endian in the advertisement; reverse it for display. */
    private fun macOf(data: ByteArray): String =
        (MAC_OFFSET until MAC_OFFSET + MAC_LENGTH)
            .map { data[it] }
            .reversed()
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    private fun readLe32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value shr 8).toByte()
        b[offset + 2] = (value shr 16).toByte()
        b[offset + 3] = (value shr 24).toByte()
    }
}
