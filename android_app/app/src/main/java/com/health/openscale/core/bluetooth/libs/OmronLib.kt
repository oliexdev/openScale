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
 * Decoder for the measurement records of Omron WLC-class body composition monitors.
 *
 * Each of the four on-device user slots is a ring buffer of fixed-size records in EEPROM. Fields are
 * packed at bit granularity: a field reads [OmronField.size] big-endian bytes at
 * [OmronField.offset], then takes [OmronField.bitSize] bits starting at [OmronField.startBit]
 * counted from the least significant bit. A raw value of zero means "not measured" for every field
 * except the weight, where it means the record slot has never been written.
 *
 * The bases, record sizes and bit positions come from Omron's own per-model device descriptors.
 */
object OmronBodyCompositionLib {

    /** One packed field inside a record. */
    data class OmronField(
        val offset: Int,
        val size: Int,
        val startBit: Int,
        val bitSize: Int
    )

    /**
     * Per-model geometry and the handful of field semantics that differ between models.
     *
     * @property recordSize bytes per measurement record.
     * @property slotBaseAddresses EEPROM address of record 0 for each of the four user slots.
     * @property recordsPerSlot ring buffer depth.
     * @property visceralFatStep level increment of one raw unit — the 48-byte models report half
     *   levels, the 32-byte models whole ones.
     * @property hasBodyAge some regional variants of the HBF-222T omit the body age field.
     */
    data class Profile(
        val recordSize: Int,
        val slotBaseAddresses: IntArray,
        val recordsPerSlot: Int,
        val visceralFatStep: Float,
        val hasBodyAge: Boolean
    ) {
        val userSlotCount: Int get() = slotBaseAddresses.size

        fun recordAddress(slot: Int, index: Int): Int =
            slotBaseAddresses[slot] + index * recordSize

        override fun equals(other: Any?): Boolean =
            other is Profile && recordSize == other.recordSize &&
                slotBaseAddresses.contentEquals(other.slotBaseAddresses) &&
                recordsPerSlot == other.recordsPerSlot &&
                visceralFatStep == other.visceralFatStep && hasBodyAge == other.hasBodyAge

        override fun hashCode(): Int =
            ((((recordSize * 31 + slotBaseAddresses.contentHashCode()) * 31 +
                recordsPerSlot) * 31 + visceralFatStep.hashCode()) * 31) + hasBodyAge.hashCode()
    }

    /** HBF-702T and its KRD-703T / Asia-Pacific siblings: 48-byte records, half visceral levels. */
    val PROFILE_HBF_702T = Profile(
        recordSize = 48,
        slotBaseAddresses = intArrayOf(0x02C0, 0x0890, 0x0E60, 0x1430),
        recordsPerSlot = 30,
        visceralFatStep = 0.5f,
        hasBodyAge = true
    )

    /** HBF-222T-AP, HBF-227T, HBF-228T, HBF-230T: 32-byte records, whole visceral levels. */
    val PROFILE_HBF_32 = Profile(
        recordSize = 32,
        slotBaseAddresses = intArrayOf(0x02C0, 0x06A0, 0x0A80, 0x0E60),
        recordsPerSlot = 30,
        visceralFatStep = 1.0f,
        hasBodyAge = true
    )

    /** HBF-222T in its European/Latin-American/BCM-500 trims, which do not report body age. */
    val PROFILE_HBF_32_NO_BODY_AGE = PROFILE_HBF_32.copy(hasBodyAge = false)

    // Field positions shared by every WLC-class record seen so far. The 48-byte record extends the
    // 32-byte one with segmental values; openScale has nowhere to store those, so they are skipped.
    private val FIELD_WEIGHT = OmronField(offset = 26, size = 2, startBit = 4, bitSize = 12)
    private val FIELD_BODY_FAT = OmronField(offset = 2, size = 2, startBit = 6, bitSize = 10)
    private val FIELD_VISCERAL_FAT = OmronField(offset = 2, size = 2, startBit = 0, bitSize = 6)
    private val FIELD_BMR = OmronField(offset = 4, size = 2, startBit = 4, bitSize = 12)
    private val FIELD_SKELETAL_MUSCLE = OmronField(offset = 6, size = 2, startBit = 6, bitSize = 10)
    private val FIELD_BMI = OmronField(offset = 8, size = 2, startBit = 6, bitSize = 10)
    private val FIELD_BODY_AGE = OmronField(offset = 10, size = 1, startBit = 0, bitSize = 7)

    private val FIELD_YEAR = OmronField(offset = 7, size = 1, startBit = 0, bitSize = 6)
    private val FIELD_MINUTE = OmronField(offset = 9, size = 1, startBit = 0, bitSize = 6)
    private val FIELD_MONTH = OmronField(offset = 11, size = 1, startBit = 0, bitSize = 4)
    private val FIELD_DAY = OmronField(offset = 12, size = 1, startBit = 3, bitSize = 5)
    private val FIELD_HOUR = OmronField(offset = 12, size = 2, startBit = 6, bitSize = 5)
    private val FIELD_SECOND = OmronField(offset = 13, size = 1, startBit = 0, bitSize = 6)

    /** Weight is stored in 50 g units. */
    private const val WEIGHT_KG_PER_UNIT = 0.05f

    /** A decoded measurement. Nullable members were not measured for this record. */
    data class Record(
        val timestamp: Date,
        val weightKg: Float,
        val bodyFatPercent: Float?,
        val skeletalMusclePercent: Float?,
        val bmi: Float?,
        val bmrKcal: Int?,
        val visceralFatLevel: Float?,
        val bodyAgeYears: Int?
    )

    /**
     * Reads a packed field. Values are big-endian; [OmronField.startBit] counts from the least
     * significant bit of the assembled value.
     */
    fun readField(record: ByteArray, field: OmronField): Int {
        var value = 0L
        for (i in 0 until field.size) {
            value = (value shl 8) or (record[field.offset + i].toLong() and 0xFF)
        }
        val mask = (1L shl field.bitSize) - 1
        return ((value ushr field.startBit) and mask).toInt()
    }

    /**
     * Decodes a single record, or returns `null` if the slot is empty or the contents are not a
     * plausible measurement.
     *
     * Unused ring buffer slots read back as erased EEPROM (0xFF) or as zeros; both decode to
     * impossible dates, but they are rejected explicitly so a partially erased record cannot slip
     * through on a date that happens to validate.
     */
    fun decodeRecord(record: ByteArray, profile: Profile): Record? {
        if (record.size < profile.recordSize) return null
        if (isBlank(record, profile.recordSize)) return null

        val rawWeight = readField(record, FIELD_WEIGHT)
        if (rawWeight == 0) return null

        val timestamp = decodeTimestamp(record) ?: return null

        return Record(
            timestamp = timestamp,
            weightKg = rawWeight * WEIGHT_KG_PER_UNIT,
            bodyFatPercent = scaledTenths(readField(record, FIELD_BODY_FAT)),
            skeletalMusclePercent = scaledTenths(readField(record, FIELD_SKELETAL_MUSCLE)),
            bmi = scaledTenths(readField(record, FIELD_BMI)),
            bmrKcal = readField(record, FIELD_BMR).takeIf { it != 0 },
            visceralFatLevel = readField(record, FIELD_VISCERAL_FAT)
                .takeIf { it != 0 }
                ?.let { it * profile.visceralFatStep },
            bodyAgeYears = if (profile.hasBodyAge) {
                readField(record, FIELD_BODY_AGE).takeIf { it != 0 }
            } else {
                null
            }
        )
    }

    private fun scaledTenths(raw: Int): Float? = raw.takeIf { it != 0 }?.let { it / 10.0f }

    private fun isBlank(record: ByteArray, length: Int): Boolean {
        var allErased = true
        var allZero = true
        for (i in 0 until length) {
            val b = record[i].toInt() and 0xFF
            if (b != 0xFF) allErased = false
            if (b != 0x00) allZero = false
            if (!allErased && !allZero) return false
        }
        return true
    }

    /**
     * Rebuilds the measurement timestamp, which is scattered across bytes 7, 9 and 11–13 with the
     * hour straddling the byte 12/13 boundary.
     */
    private fun decodeTimestamp(record: ByteArray): Date? {
        val year = 2000 + readField(record, FIELD_YEAR)
        val month = readField(record, FIELD_MONTH)
        val day = readField(record, FIELD_DAY)
        val hour = readField(record, FIELD_HOUR)
        val minute = readField(record, FIELD_MINUTE)
        val second = readField(record, FIELD_SECOND)

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 ||
            minute !in 0..59 || second !in 0..59
        ) {
            return null
        }

        val calendar = Calendar.getInstance().apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return runCatching { calendar.time }.getOrNull()
    }
}

/**
 * Omron "Wellness Link" (WLP) transfer protocol used by the WLC/WLS-class Omron devices.
 *
 * The device exposes a vendor service with four write channels and four notify channels. A command
 * is split into 16-byte chunks, one per write channel; a response is reassembled from the notify
 * channels in the same order. Every frame is self-describing (byte 0 is the total frame length) and
 * protected by an XOR checksum (BCC) over all preceding bytes, so the XOR over a complete, intact
 * frame is always zero.
 *
 * Frame layout:
 * ```
 *   request   [len][cmd][flags][addrHi][addrLo][size][payload…][00][bcc]
 *   response  [len][cmd|0x80][flags][addrHi][addrLo][size][payload…][result][bcc]
 * ```
 *
 * All device memory is addressed as a flat EEPROM space; measurement records are read out of it in
 * blocks of at most 32 bytes and decoded by [OmronBodyCompositionLib].
 */
object OmronWlpFrame {

    /** Maximum payload of a single EEPROM read; larger reads must be split. */
    const val MAX_READ_BLOCK = 0x20

    /** Bytes a channel can carry — the protocol predates MTU negotiation and is fixed at 16. */
    const val CHANNEL_WIDTH = 16

    /** Length of the unlock/pairing key, in bytes. */
    const val KEY_SIZE = 16

    // Response type words, taken from the two bytes that follow the frame length.
    const val TYPE_START = 0x8000
    const val TYPE_READ = 0x8100
    const val TYPE_WRITE = 0x81C0
    const val TYPE_END = 0x8F00

    // Unlock-channel opcodes and their acknowledgements.
    private const val OP_WRITE_KEY = 0x00
    private const val OP_UNLOCK = 0x01
    private const val OP_ENTER_KEY_PROGRAMMING = 0x02

    const val ACK_KEY_WRITTEN = 0x8000
    const val ACK_UNLOCKED = 0x8100
    const val ACK_KEY_PROGRAMMING = 0x8200

    /** XOR checksum over the first [length] bytes of [bytes]. */
    fun bcc(bytes: ByteArray, length: Int = bytes.size): Byte {
        var acc = 0
        for (i in 0 until length) acc = acc xor (bytes[i].toInt() and 0xFF)
        return acc.toByte()
    }

    /** Opens a read-out session. The device refuses EEPROM access until this is acknowledged. */
    fun startTransmission(): ByteArray = sealed(byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00))

    /** Closes the read-out session. The response carries a device-side result code. */
    fun endTransmission(): ByteArray = sealed(byteArrayOf(0x08, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00))

    /**
     * Reads [length] bytes (at most [MAX_READ_BLOCK]) starting at EEPROM [address].
     *
     * The flags byte is zero for reads; writes would set 0xC0 there, which is why the top two bits
     * of the address are not usable.
     */
    fun readEeprom(address: Int, length: Int): ByteArray {
        require(length in 1..MAX_READ_BLOCK) { "read block out of range: $length" }
        require(address in 0..0x3FFF) { "address out of range: $address" }
        return sealed(
            byteArrayOf(
                0x08,
                0x01,
                0x00,
                ((address shr 8) and 0xFF).toByte(),
                (address and 0xFF).toByte(),
                length.toByte(),
                0x00
            )
        )
    }

    /** Unlock-channel command proving we know the key the device was paired with. */
    fun unlock(key: ByteArray): ByteArray = keyCommand(OP_UNLOCK, key)

    /** Unlock-channel command that puts a device in pairing mode into key-programming mode. */
    fun enterKeyProgramming(): ByteArray = ByteArray(KEY_SIZE + 1).also {
        it[0] = OP_ENTER_KEY_PROGRAMMING.toByte()
    }

    /** Unlock-channel command storing [key] as the device's new pairing key. */
    fun writeKey(key: ByteArray): ByteArray = keyCommand(OP_WRITE_KEY, key)

    private fun keyCommand(opcode: Int, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "key must be $KEY_SIZE bytes, was ${key.size}" }
        return ByteArray(KEY_SIZE + 1).also {
            it[0] = opcode.toByte()
            key.copyInto(it, 1)
        }
    }

    /**
     * Appends the checksum to a frame whose last byte is still a placeholder.
     * The BCC covers everything before it, so an intact frame XORs to zero.
     */
    private fun sealed(withoutBcc: ByteArray): ByteArray =
        withoutBcc + bcc(withoutBcc)

    /** Splits a command into the fixed-width chunks the write channels accept. */
    fun toChannelChunks(command: ByteArray): List<ByteArray> =
        command.toList().chunked(CHANNEL_WIDTH) { it.toByteArray() }

    /** Number of notify channels a response of [frameLength] bytes will arrive on. */
    fun channelsForFrame(frameLength: Int): Int =
        (frameLength + CHANNEL_WIDTH - 1) / CHANNEL_WIDTH

    /** A decoded device response. [data] is empty for frames that carry no payload. */
    data class Response(
        val type: Int,
        val address: Int,
        val data: ByteArray,
        val result: Int
    ) {
        override fun equals(other: Any?): Boolean =
            other is Response && type == other.type && address == other.address &&
                result == other.result && data.contentEquals(other.data)

        override fun hashCode(): Int =
            (((type * 31 + address) * 31 + result) * 31) + data.contentHashCode()
    }

    /**
     * Validates and decodes a reassembled response frame.
     *
     * Returns `null` when the frame is truncated or the checksum does not match, so a corrupted
     * notification is retried rather than parsed into a bogus measurement.
     */
    fun parseResponse(frame: ByteArray): Response? {
        if (frame.size < 8) return null

        val declaredLength = frame[0].toInt() and 0xFF
        if (declaredLength < 8 || declaredLength > frame.size) return null

        val exact = frame.copyOf(declaredLength)
        if (bcc(exact).toInt() != 0) return null

        val type = ((exact[1].toInt() and 0xFF) shl 8) or (exact[2].toInt() and 0xFF)
        val address = ((exact[3].toInt() and 0xFF) shl 8) or (exact[4].toInt() and 0xFF)
        val declaredPayload = exact[5].toInt() and 0xFF
        val result = exact[declaredLength - 2].toInt() and 0xFF

        // The device pads short reads by declaring more payload than it sent; treat the surplus as
        // erased EEPROM (0xFF) rather than dropping the frame.
        val available = declaredLength - 8
        val data = if (declaredPayload > available) {
            ByteArray(declaredPayload) { 0xFF.toByte() }
        } else {
            exact.copyOfRange(6, 6 + declaredPayload)
        }

        return Response(type = type, address = address, data = data, result = result)
    }
}
