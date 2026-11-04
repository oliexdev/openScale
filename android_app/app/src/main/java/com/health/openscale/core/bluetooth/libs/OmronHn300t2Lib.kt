/*
 * openScale
 * Copyright (C) 2026 nadelacruz
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
 * Protocol helpers for the Omron HN-300T2 body composition monitor.
 *
 * The frame codec is [OmronWlpFrame]: the observed command/response frames on this scale match the
 * WLP wire format, over a bonded link. Before any WLP exchange the client writes a 4-byte session
 * token on the b305b680 characteristic and waits for the scale to echo it back.
 */
object OmronHn300t2Lib {

    /**
     * The observed opcode of the session-token write on b305b680; purpose unknown. The scale was
     * observed to answer with `0x91 0x00` plus the same token.
     */
    // TODO(unconfirmed): docs/wld-protocol.md (0x001B handshake)
    const val OP_SESSION_TOKEN: Int = 0x11

    private const val TOKEN_SIZE = 4
    private const val SESSION_FRAME_SIZE = 20
    private const val ECHO_OPCODE = 0x91
    // Byte 1 of the echo as observed in both traces; its meaning is unknown.
    // TODO(unconfirmed): docs/wld-protocol.md (0x001B handshake)
    private const val ECHO_BYTE1_OBSERVED = 0x00
    private const val ECHO_TOKEN_OFFSET = 2
    private const val TIMESTAMP_SIZE = 6
    private const val EARLIEST_YEAR = 2020
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000

    /** Session-token frame: opcode, the 4 token bytes, zero padding to 20 bytes. */
    fun sessionToken(token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE) { "token must be $TOKEN_SIZE bytes" }
        val frame = ByteArray(SESSION_FRAME_SIZE)
        frame[0] = OP_SESSION_TOKEN.toByte()
        token.copyInto(frame, destinationOffset = 1)
        return frame
    }

    /** True iff [notify] is the scale's acknowledgement carrying exactly [token]. */
    fun echoMatches(notify: ByteArray, token: ByteArray): Boolean {
        if (token.size != TOKEN_SIZE || notify.size < ECHO_TOKEN_OFFSET + TOKEN_SIZE) return false
        if (notify[0] != ECHO_OPCODE.toByte() || notify[1] != ECHO_BYTE1_OBSERVED.toByte()) return false
        return (0 until TOKEN_SIZE).all { notify[ECHO_TOKEN_OFFSET + it] == token[it] }
    }

    /**
     * Decodes six binary bytes `year-2000, month, day, hour, minute, second` at [offset] into a
     * local-time [Date]. Returns null for short input, out-of-range fields, an impossible calendar
     * date, a year before 2020, or a date more than one day in the future.
     */
    fun decodeTimestamp(bytes: ByteArray, offset: Int): Date? {
        if (offset < 0 || bytes.size < offset + TIMESTAMP_SIZE) return null
        val v = IntArray(TIMESTAMP_SIZE) { bytes[offset + it].toInt() and 0xFF }
        val year = 2000 + v[0]
        val month = v[1]
        val day = v[2]
        val hour = v[3]
        val minute = v[4]
        val second = v[5]
        if (year < EARLIEST_YEAR || month !in 1..12 || day !in 1..31 ||
            hour !in 0..23 || minute !in 0..59 || second !in 0..59
        ) return null

        val date = try {
            Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute, second)
            }.time // getTime() throws on an impossible date when not lenient
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (date.time > System.currentTimeMillis() + ONE_DAY_MS) null else date
    }

    const val ADDR_INDEX_BLOCK: Int = 0x01A0
    const val INDEX_BLOCK_SIZE: Int = 12
    const val ADDR_UNSENT_INFO: Int = 0x01AC
    const val ADDR_STATUS: Int = 0x01B8
    const val SLOT0_BASE: Int = 0x02C0
    const val RECORD_SIZE: Int = 16
    const val POINTER_MASK: Int = 0x3F
    const val USER_SLOTS: Int = 4

    private const val INDEX_BLOCK_MIN_SIZE = 8
    private const val UNSENT_OFFSET = 4
    private const val RECORD_TIMESTAMP_OFFSET = 2
    private const val RECORD_WEIGHT_OFFSET = 0
    private const val RING_MODULUS = 64

    /**
     * Per-slot view of the `0x01A0` index block. Bytes 0-3 are write pointers (low six bits; the
     * pointer names the next slot to fill) and bytes 4-7 are unsent counts (low six bits).
     * [newestRecord] holds the index of the newest stored record per slot, or -1 when the pointer is 0.
     *
     * TODO(unconfirmed): whether [unsent] may gate which records are fetched is not established;
     * see docs/wld-protocol.md "Open questions" in the protocol notes repo (item 1 and 5).
     */
    data class IndexBlock(val newestRecord: IntArray, val unsent: IntArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is IndexBlock &&
                newestRecord.contentEquals(other.newestRecord) && unsent.contentEquals(other.unsent))

        override fun hashCode(): Int = newestRecord.contentHashCode() * 31 + unsent.contentHashCode()
    }

    /** Decodes the index block; null if [block] is shorter than 8 bytes. */
    fun decodeIndexBlock(block: ByteArray): IndexBlock? {
        if (block.size < INDEX_BLOCK_MIN_SIZE) return null
        val newest = IntArray(USER_SLOTS) {
            val pointer = block[it].toInt() and POINTER_MASK
            // TODO(unconfirmed): ring depth unknown on this model
            if (pointer == 0) -1 else (pointer - 1 + RING_MODULUS) % RING_MODULUS
        }
        val unsent = IntArray(USER_SLOTS) { block[UNSENT_OFFSET + it].toInt() and POINTER_MASK }
        return IndexBlock(newest, unsent)
    }

    /** Memory-map address of record [index] in [slot]; null for slots whose base is not known. */
    fun recordAddress(slot: Int, index: Int): Int? {
        // TODO(unconfirmed): slot 1-3 base addresses unknown on this model (docs/wld-protocol.md,
        // "Open questions" item 5)
        if (index < 0 || slot !in 0 until USER_SLOTS) return null
        if (slot != 0) return null
        return SLOT0_BASE + index * RECORD_SIZE
    }

    /** Kilograms per raw unit of the record weight (50 g). */
    const val WEIGHT_KG_PER_UNIT: Float = 0.05f

    /**
     * A stored measurement. [weightRaw] is record bytes 0-1, big-endian, in 50 g units: confirmed
     * on 2026-11-04 by three live weigh-ins at two loads that matched the scale's display.
     *
     * TODO(unconfirmed): whether the top bits of bytes 0-1 carry flags (every observed value fits
     * in 12 bits); bytes 8-15 are undecoded (docs/wld-protocol.md, "Open questions" item 1).
     */
    data class MeasurementRecord(val timestamp: Date, val weightRaw: Int) {
        val weightKg: Float get() = weightRaw * WEIGHT_KG_PER_UNIT
    }

    /** Decodes a 16-byte record; null if short or the timestamp at offset 2 does not decode. */
    fun decodeRecord(bytes: ByteArray): MeasurementRecord? {
        if (bytes.size < RECORD_SIZE) return null
        val timestamp = decodeTimestamp(bytes, RECORD_TIMESTAMP_OFFSET) ?: return null
        val raw = ((bytes[RECORD_WEIGHT_OFFSET].toInt() and 0xFF) shl 8) or
            (bytes[RECORD_WEIGHT_OFFSET + 1].toInt() and 0xFF)
        return MeasurementRecord(timestamp, raw)
    }
}

/**
 * Pure, Android-free state machine for one read-only HN-300T2 transfer. The caller feeds it
 * notifications and performs the returned [Action]s; it never writes anything but the session token
 * and read/start/end commands.
 */
class Hn300t2Session(private val token: ByteArray) {

    sealed interface Action {
        data class WriteHandshake(val bytes: ByteArray) : Action
        data class WriteCommand(val bytes: ByteArray) : Action
        data class Publish(val record: OmronHn300t2Lib.MeasurementRecord, val slot: Int) : Action
        data object NoNewRecord : Action
        data class Fail(val reason: String) : Action
        /** Diagnostic for the caller's log; changes nothing in the session. */
        data class Warn(val message: String) : Action
        data object Done : Action
    }

    private enum class Phase {
        HANDSHAKE, STARTING, READ_INDEX, READ_UNSENT, READ_STATUS, READ_RECORD, AWAITING_FINISH, ENDING, DONE, FAILED
    }

    private var current = Phase.HANDSHAKE
    private var expectedType = 0
    private var expectedAddress = 0
    private var index: OmronHn300t2Lib.IndexBlock? = null

    val phase: String get() = current.name

    /** True once the session is DONE or FAILED; it then ignores every further input. */
    val isTerminal: Boolean get() = current == Phase.DONE || current == Phase.FAILED

    fun start(): List<Action> =
        listOf(Action.WriteHandshake(OmronHn300t2Lib.sessionToken(token)))

    fun onHandshakeNotify(bytes: ByteArray): List<Action> {
        if (isTerminal) return emptyList()
        if (current != Phase.HANDSHAKE) return fail("unexpected handshake notify")
        if (!OmronHn300t2Lib.echoMatches(bytes, token)) return fail("handshake echo mismatch")
        current = Phase.STARTING
        expect(OmronWlpFrame.TYPE_START, 0)
        return listOf(Action.WriteCommand(OmronWlpFrame.startTransmission()))
    }

    fun onNotify(bytes: ByteArray): List<Action> {
        if (isTerminal) return emptyList()
        if (current == Phase.HANDSHAKE) return fail("unexpected response")
        if (current == Phase.AWAITING_FINISH) return emptyList()
        val r = OmronWlpFrame.parseResponse(bytes) ?: return emptyList()
        if (r.type != expectedType || (current != Phase.ENDING && r.address != expectedAddress)) {
            return fail("unexpected response")
        }
        // TODO(unconfirmed): result codes on failure — docs/wld-protocol.md open question 4
        if (r.result != 0) return fail("non-zero result 0x%02x".format(r.result))
        return when (current) {
            Phase.STARTING -> read(Phase.READ_INDEX, OmronHn300t2Lib.ADDR_INDEX_BLOCK, OmronHn300t2Lib.INDEX_BLOCK_SIZE)
            Phase.READ_INDEX -> {
                val ib = OmronHn300t2Lib.decodeIndexBlock(r.data) ?: return fail("bad index block")
                index = ib
                // Only the newest slot-0 record is read; say so when the counts suggest more.
                val skipped = ib.unsent[0] > 1 || (1 until OmronHn300t2Lib.USER_SLOTS).any { ib.unsent[it] != 0 }
                val note = if (skipped) {
                    listOf(Action.Warn("unsent counts ${ib.unsent.joinToString(",")}: only the newest slot-0 record is read"))
                } else emptyList()
                note + read(Phase.READ_UNSENT, OmronHn300t2Lib.ADDR_UNSENT_INFO, OmronHn300t2Lib.INDEX_BLOCK_SIZE)
            }
            Phase.READ_UNSENT -> read(Phase.READ_STATUS, OmronHn300t2Lib.ADDR_STATUS, OmronHn300t2Lib.RECORD_SIZE)
            Phase.READ_STATUS -> {
                val ib = index ?: return fail("missing index block")
                val newest = ib.newestRecord[0]
                val address = OmronHn300t2Lib.recordAddress(0, newest)
                // TODO(unconfirmed): unsent count as the pending-record gate — docs/wld-protocol.md
                // open questions 1 and 5; a record with unsent == 0 is reported as NoNewRecord
                if (ib.unsent[0] > 0 && address != null) {
                    read(Phase.READ_RECORD, address, OmronHn300t2Lib.RECORD_SIZE)
                } else {
                    listOf(Action.NoNewRecord) + end()
                }
            }
            Phase.READ_RECORD -> {
                val record = OmronHn300t2Lib.decodeRecord(r.data)
                if (record != null) {
                    current = Phase.AWAITING_FINISH
                    listOf(Action.Publish(record, 0))
                } else {
                    val why = if (r.data.size < OmronHn300t2Lib.RECORD_SIZE) "record too short" else "record timestamp invalid"
                    listOf(Action.Warn(why), Action.NoNewRecord) + end()
                }
            }
            Phase.ENDING -> {
                current = Phase.DONE
                listOf(Action.Done)
            }
            else -> fail("unexpected response")
        }
    }

    /** Ends the transfer once the caller is done with a published record; no-op in any other phase. */
    fun finish(): List<Action> = if (current == Phase.AWAITING_FINISH) end() else emptyList()

    private fun expect(type: Int, address: Int) {
        expectedType = type
        expectedAddress = address
    }

    private fun read(next: Phase, address: Int, length: Int): List<Action> {
        current = next
        expect(OmronWlpFrame.TYPE_READ, address)
        return listOf(Action.WriteCommand(OmronWlpFrame.readEeprom(address, length)))
    }

    private fun end(): List<Action> {
        current = Phase.ENDING
        expect(OmronWlpFrame.TYPE_END, 0)
        return listOf(Action.WriteCommand(OmronWlpFrame.endTransmission()))
    }

    private fun fail(reason: String): List<Action> {
        current = Phase.FAILED
        return listOf(Action.Fail(reason))
    }
}
