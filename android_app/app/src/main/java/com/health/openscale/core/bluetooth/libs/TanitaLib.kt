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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.libs

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * RD-545 protocol primitives, independent of Android and Bluetooth transport.
 *
 * A message is: length (BE16, excludes itself), command (BE16), payload, checksum.
 * Its unsigned bytes sum to 0xff modulo 256. Each characteristic value contains
 * a byte offset (BE16), last fragment index (u8), payload length (u8), and up to
 * 16 message bytes. Notifications can have zero padding after that payload.
 *
 * Confirmed against an RD-545EU capture and the protocol behavior of its app.
 * No application identifiers or personal capture data belong in this library.
 */
object TanitaLib {
    const val VERIFY_UUID = 0x0003
    const val REGISTER_UUID = 0x0002
    const val WRITE_DATE = 0x0010
    const val RETRIEVE_DEVICE_INFO = 0x0020
    const val RETRIEVE_USER_INFO = 0x1000
    const val EXCHANGE_USER_INFO = 0x1002
    const val MEASURE = 0x2010
    const val RETRIEVE_MEASUREMENT_COUNT = 0x3000
    const val RETRIEVE_MEASUREMENT = 0x3010
    const val UNIT_KG = 1

    private const val CHUNK_SIZE = 16
    private const val MAX_MESSAGE_SIZE = CHUNK_SIZE * 256
    private val DATE_EPOCH = LocalDate.of(2000, 1, 1)

    data class Response(val command: Int, val status: Int, val payload: ByteArray)

    /** Fixed-width encodings used by the RD-545 result tags. */
    enum class Encoding(val width: Int) {
        HUNDREDTHS(2), TENTHS(2), UNSIGNED_SHORT(2), UNSIGNED_BYTE(1),
        SIGNED_SCORE(1), SIGNED_TENTHS(2), SIGNED_HUNDREDTHS(2), VARIABLE_INTEGER(0),
    }

    /** Protocol field identifiers; no Android types, personal data or estimated biomarkers. */
    enum class Metric(val tag: Int, val encoding: Encoding) {
        WEIGHT(0x6021, Encoding.HUNDREDTHS),
        BODY_FAT(0x6022, Encoding.TENTHS),
        MUSCLE_MASS(0x6023, Encoding.HUNDREDTHS),
        MUSCLE_SCORE(0x6024, Encoding.SIGNED_SCORE),
        VISCERAL_FAT(0x6025, Encoding.TENTHS),
        BMR(0x6027, Encoding.UNSIGNED_SHORT),
        METABOLIC_AGE(0x6028, Encoding.UNSIGNED_BYTE),
        BONE_MASS(0x6029, Encoding.HUNDREDTHS),
        WATER_MASS(0x602a, Encoding.HUNDREDTHS),
        WATER_PERCENT(0x602b, Encoding.TENTHS),
        LEAN_MASS(0x602d, Encoding.HUNDREDTHS),
        LEG_SCORE(0x602e, Encoding.UNSIGNED_SHORT),
        BMR_RATING(0x602f, Encoding.UNSIGNED_SHORT),
        RIGHT_LEG_FAT(0x6030, Encoding.TENTHS),
        RIGHT_LEG_FAT_MASS(0x6031, Encoding.HUNDREDTHS),
        RIGHT_LEG_LEAN_MASS(0x6032, Encoding.HUNDREDTHS),
        RIGHT_LEG_MUSCLE_MASS(0x6033, Encoding.HUNDREDTHS),
        RIGHT_LEG_FAT_SCORE(0x6034, Encoding.SIGNED_SCORE),
        RIGHT_LEG_MUSCLE_SCORE(0x6035, Encoding.SIGNED_SCORE),
        LEFT_LEG_FAT(0x6036, Encoding.TENTHS),
        LEFT_LEG_FAT_MASS(0x6037, Encoding.HUNDREDTHS),
        LEFT_LEG_LEAN_MASS(0x6038, Encoding.HUNDREDTHS),
        LEFT_LEG_MUSCLE_MASS(0x6039, Encoding.HUNDREDTHS),
        LEFT_LEG_FAT_SCORE(0x603a, Encoding.SIGNED_SCORE),
        LEFT_LEG_MUSCLE_SCORE(0x603b, Encoding.SIGNED_SCORE),
        RIGHT_ARM_FAT(0x603c, Encoding.TENTHS),
        RIGHT_ARM_FAT_MASS(0x603d, Encoding.HUNDREDTHS),
        RIGHT_ARM_LEAN_MASS(0x603e, Encoding.HUNDREDTHS),
        RIGHT_ARM_MUSCLE_MASS(0x603f, Encoding.HUNDREDTHS),
        RIGHT_ARM_FAT_SCORE(0x6040, Encoding.SIGNED_SCORE),
        RIGHT_ARM_MUSCLE_SCORE(0x6041, Encoding.SIGNED_SCORE),
        LEFT_ARM_FAT(0x6042, Encoding.TENTHS),
        LEFT_ARM_FAT_MASS(0x6043, Encoding.HUNDREDTHS),
        LEFT_ARM_LEAN_MASS(0x6044, Encoding.HUNDREDTHS),
        LEFT_ARM_MUSCLE_MASS(0x6045, Encoding.HUNDREDTHS),
        LEFT_ARM_FAT_SCORE(0x6046, Encoding.SIGNED_SCORE),
        LEFT_ARM_MUSCLE_SCORE(0x6047, Encoding.SIGNED_SCORE),
        TORSO_FAT(0x6048, Encoding.TENTHS),
        TORSO_FAT_MASS(0x6049, Encoding.HUNDREDTHS),
        TORSO_LEAN_MASS(0x604a, Encoding.HUNDREDTHS),
        TORSO_MUSCLE_MASS(0x604b, Encoding.HUNDREDTHS),
        TORSO_FAT_SCORE(0x604c, Encoding.SIGNED_SCORE),
        TORSO_MUSCLE_SCORE(0x604d, Encoding.SIGNED_SCORE),
        FAT_MASS(0x6050, Encoding.HUNDREDTHS),
        BMI(0x6056, Encoding.TENTHS),
        STANDARD_WEIGHT(0x6057, Encoding.HUNDREDTHS),
        OBESITY_PERCENT(0x6058, Encoding.SIGNED_HUNDREDTHS),
        BONE_RATING(0x605a, Encoding.UNSIGNED_BYTE),
        MUSCLE_QUALITY(0x605b, Encoding.UNSIGNED_BYTE),
        LEFT_ARM_MUSCLE_QUALITY(0x605c, Encoding.UNSIGNED_BYTE),
        RIGHT_ARM_MUSCLE_QUALITY(0x605d, Encoding.UNSIGNED_BYTE),
        LEFT_LEG_MUSCLE_QUALITY(0x605e, Encoding.UNSIGNED_BYTE),
        RIGHT_LEG_MUSCLE_QUALITY(0x605f, Encoding.UNSIGNED_BYTE),
        HEART_RATE(0x606e, Encoding.UNSIGNED_BYTE),
        HEART_RATE_STATE(0x606f, Encoding.UNSIGNED_BYTE),
        FAT_RATING(0x6070, Encoding.UNSIGNED_BYTE),
        RIGHT_LEG_FAT_RATING(0x6071, Encoding.UNSIGNED_BYTE),
        LEFT_LEG_FAT_RATING(0x6072, Encoding.UNSIGNED_BYTE),
        RIGHT_ARM_FAT_RATING(0x6073, Encoding.UNSIGNED_BYTE),
        LEFT_ARM_FAT_RATING(0x6074, Encoding.UNSIGNED_BYTE),
        TORSO_FAT_RATING(0x6075, Encoding.UNSIGNED_BYTE),
        BMI_RATING(0x6076, Encoding.UNSIGNED_BYTE),
        MUSCLE_RATING(0x6077, Encoding.UNSIGNED_BYTE),
        RIGHT_LEG_MUSCLE_RATING(0x6078, Encoding.UNSIGNED_BYTE),
        LEFT_LEG_MUSCLE_RATING(0x6079, Encoding.UNSIGNED_BYTE),
        RIGHT_ARM_MUSCLE_RATING(0x607a, Encoding.UNSIGNED_BYTE),
        LEFT_ARM_MUSCLE_RATING(0x607b, Encoding.UNSIGNED_BYTE),
        TORSO_MUSCLE_RATING(0x607c, Encoding.UNSIGNED_BYTE),
        VISCERAL_RATING(0x607d, Encoding.UNSIGNED_BYTE),
        MUSCLE_QUALITY_RATING(0x607e, Encoding.UNSIGNED_BYTE),
        LEFT_ARM_MUSCLE_QUALITY_RATING(0x6621, Encoding.UNSIGNED_BYTE),
        RIGHT_ARM_MUSCLE_QUALITY_RATING(0x6622, Encoding.UNSIGNED_BYTE),
        LEFT_LEG_MUSCLE_QUALITY_RATING(0x6623, Encoding.UNSIGNED_BYTE),
        RIGHT_LEG_MUSCLE_QUALITY_RATING(0x6624, Encoding.UNSIGNED_BYTE),
        LEFT_BODY_50KHZ_RESISTANCE(0x6123, Encoding.SIGNED_TENTHS),
        LEFT_BODY_50KHZ_REACTANCE(0x6124, Encoding.SIGNED_TENTHS),
        LEFT_BODY_6_25KHZ_RESISTANCE(0x6729, Encoding.SIGNED_TENTHS),
        LEFT_BODY_6_25KHZ_REACTANCE(0x6730, Encoding.SIGNED_TENTHS),
        RIGHT_BODY_50KHZ_RESISTANCE(0x6721, Encoding.SIGNED_TENTHS),
        RIGHT_BODY_50KHZ_REACTANCE(0x6723, Encoding.SIGNED_TENTHS),
        RIGHT_BODY_6_25KHZ_RESISTANCE(0x672a, Encoding.SIGNED_TENTHS),
        RIGHT_BODY_6_25KHZ_REACTANCE(0x6731, Encoding.SIGNED_TENTHS),
        LEFT_ARM_50KHZ_RESISTANCE(0x6143, Encoding.SIGNED_TENTHS),
        LEFT_ARM_50KHZ_REACTANCE(0x6144, Encoding.SIGNED_TENTHS),
        LEFT_ARM_6_25KHZ_RESISTANCE(0x6725, Encoding.SIGNED_TENTHS),
        LEFT_ARM_6_25KHZ_REACTANCE(0x672c, Encoding.SIGNED_TENTHS),
        RIGHT_ARM_50KHZ_RESISTANCE(0x613b, Encoding.SIGNED_TENTHS),
        RIGHT_ARM_50KHZ_REACTANCE(0x613c, Encoding.SIGNED_TENTHS),
        RIGHT_ARM_6_25KHZ_RESISTANCE(0x6726, Encoding.SIGNED_TENTHS),
        RIGHT_ARM_6_25KHZ_REACTANCE(0x672d, Encoding.SIGNED_TENTHS),
        LEFT_LEG_50KHZ_RESISTANCE(0x6133, Encoding.SIGNED_TENTHS),
        LEFT_LEG_50KHZ_REACTANCE(0x6134, Encoding.SIGNED_TENTHS),
        LEFT_LEG_6_25KHZ_RESISTANCE(0x6727, Encoding.SIGNED_TENTHS),
        LEFT_LEG_6_25KHZ_REACTANCE(0x672e, Encoding.SIGNED_TENTHS),
        RIGHT_LEG_50KHZ_RESISTANCE(0x612b, Encoding.SIGNED_TENTHS),
        RIGHT_LEG_50KHZ_REACTANCE(0x612c, Encoding.SIGNED_TENTHS),
        RIGHT_LEG_6_25KHZ_RESISTANCE(0x6728, Encoding.SIGNED_TENTHS),
        RIGHT_LEG_6_25KHZ_REACTANCE(0x672f, Encoding.SIGNED_TENTHS),
        ARMS_50KHZ_RESISTANCE(0x6722, Encoding.SIGNED_TENTHS),
        ARMS_50KHZ_REACTANCE(0x6724, Encoding.SIGNED_TENTHS),
        ARMS_6_25KHZ_RESISTANCE(0x672b, Encoding.SIGNED_TENTHS),
        ARMS_6_25KHZ_REACTANCE(0x6732, Encoding.SIGNED_TENTHS),
        LEGS_50KHZ_RESISTANCE(0x614b, Encoding.SIGNED_TENTHS),
        LEGS_50KHZ_REACTANCE(0x614c, Encoding.SIGNED_TENTHS),
        LEGS_6_25KHZ_RESISTANCE(0x6152, Encoding.SIGNED_TENTHS),
        LEGS_6_25KHZ_REACTANCE(0x6151, Encoding.SIGNED_TENTHS),
        PHYSIQUE_RATING(0x7e2a, Encoding.VARIABLE_INTEGER),;

        companion object {
            internal val byTag = entries.associateBy { it.tag }
        }
    }

    data class Reading(
        val weightKg: Float,
        val bodyFatPercent: Float?,
        val measuredAt: LocalDateTime?,
        val metrics: Map<Metric, Float> = emptyMap(),
    ) {
        /** The vendor app's nine-category lookup from reported fat assessment and muscle score. */
        val physiqueRating: Int?
            get() {
                metrics[Metric.PHYSIQUE_RATING]?.toInt()?.takeIf { it in 1..9 }?.let { return it }
                val fat = metrics[Metric.FAT_RATING]?.toInt() ?: return null
                val muscle = metrics[Metric.MUSCLE_SCORE]?.toInt() ?: return null
                val row = when (fat) { in 4..5 -> 0; in 2..3 -> 1; in 0..1 -> 2; else -> return null }
                val column = when (muscle) { in -4..-2 -> 1; in -1..1 -> 2; in 2..4 -> 3; else -> return null }
                return row * 3 + column
            }
    }

    class Profile internal constructor(internal val fields: Map<Int, ByteArray>, val unit: Int) {
        val nickname: String?
            get() = (fields[0x7e22]?.toString(Charsets.UTF_8)
                ?: fields[0x7e36]?.toString(Charset.forName("Shift_JIS"))
                ?: fields[0x6a3d]?.toString(Charsets.US_ASCII))?.trimEnd('\u0000', ' ')
    }

    private val profileWidths = mapOf(
        0x6a32 to 2, 0x6a33 to 3, 0x6a3d to 5, 0x6a3c to 2,
        0x6a37 to 1, 0x6a38 to 1, 0x6a3e to 2, 0x6a3b to 1,
        0x604f to 2, 0x6a15 to 4, 0x6a13 to 1,
    )

    fun parseProfile(response: Response): Profile? {
        if (response.command !in setOf(0x9000, 0x9002) || response.status != 0) return null
        val data = response.payload
        val fields = linkedMapOf<Int, ByteArray>()
        var offset = 0
        while (offset < data.size) {
            if (offset + 2 > data.size) return null
            val tag = uint16(data, offset)
            offset += 2
            val size = if (tag in setOf(0x7e22, 0x7e23, 0x7e2f, 0x7e36, 0x7e37)) {
                if (offset >= data.size) return null
                data[offset++].unsigned()
            } else profileWidths[tag] ?: return null
            if (offset + size > data.size || tag in fields) return null
            fields[tag] = data.copyOfRange(offset, offset + size)
            offset += size
        }
        val required = setOf(0x6a3c, 0x6a37, 0x6a38, 0x6a3e, 0x6a3b, 0x604f, 0x6a15, 0x6a13)
        if (!fields.keys.containsAll(required)) return null
        if (uint16(fields.getValue(0x6a3c), 0) == 0 || uint16(fields.getValue(0x6a3e), 0) in setOf(0, 0xffff)) return null
        val unitBytes = fields.getValue(0x6a15)
        val unit = unitBytes.fold(0) { value, byte -> (value shl 8) or byte.unsigned() }
        if (unit !in setOf(1, 2, 4)) return null
        return Profile(fields, unit)
    }

    /** Select an existing slot without replacing its body-composition settings. */
    fun selectExistingProfile(slot: Int, profile: Profile, dateTime: LocalDateTime): ByteArray {
        require(slot in 0..3)
        val output = ByteArrayOutputStream()
        output.write(slot)
        val clock = writeDate(dateTime)
        output.write(clock, 4, clock.size - 5)
        // Older firmware also accepts a five-byte ASCII nickname alongside the
        // extended nickname. Preserve its existing prefix; never invent a name.
        val nickname = profile.fields[0x7e22]
        if (0x6a3d !in profile.fields && nickname != null && nickname.all { it.unsigned() < 128 }) {
            writeField(output, 0x6a3d, nickname.copyOf(5))
        }
        for ((tag, value) in profile.fields) {
            if (tag !in setOf(0x6a32, 0x6a33)) writeField(output, tag, value)
        }
        return command(EXCHANGE_USER_INFO, output.toByteArray())
    }

    fun command(command: Int, payload: ByteArray = byteArrayOf(0)): ByteArray {
        require(command in 0..0x7fff)
        require(payload.size <= MAX_MESSAGE_SIZE - 5)
        val result = ByteArray(payload.size + 5)
        put16(result, 0, result.size - 2)
        put16(result, 2, command)
        payload.copyInto(result, 4)
        result[result.lastIndex] = (0xff - checksum(result)).toByte()
        return result
    }

    fun verifyUuid(uuid: UUID): ByteArray = command(VERIFY_UUID, uuid.toString().toByteArray(Charsets.US_ASCII))

    fun registerUuid(uuid: UUID): ByteArray = command(REGISTER_UUID, uuid.toString().toByteArray(Charsets.US_ASCII))

    fun writeDate(dateTime: LocalDateTime): ByteArray {
        val days = dateTime.toLocalDate().toEpochDay() - DATE_EPOCH.toEpochDay()
        require(days in 0..0xffff)
        val ticks = dateTime.toLocalTime().toSecondOfDay() * 2
        val payload = byteArrayOf(
            0x6a, 0x32, (days.toInt() ushr 8).toByte(), days.toByte(),
            0x6a, 0x33, (ticks ushr 16).toByte(), (ticks ushr 8).toByte(), ticks.toByte(),
        )
        return command(WRITE_DATE, payload)
    }

    fun fragment(message: ByteArray): List<ByteArray> {
        require(validMessage(message))
        val lastFragment = (message.size - 1) / CHUNK_SIZE
        return message.indices.step(CHUNK_SIZE).map { offset ->
            val size = minOf(CHUNK_SIZE, message.size - offset)
            ByteArray(size + 4).also {
                put16(it, 0, offset)
                it[2] = lastFragment.toByte()
                it[3] = size.toByte()
                message.copyInto(it, 4, offset, offset + size)
            }
        }
    }

    /** Drops malformed sequences and resumes at the next fragment with offset zero. */
    class Assembler {
        private val buffer = ByteArray(MAX_MESSAGE_SIZE)
        private var received = 0
        private var expectedSize = 0

        fun reset() {
            received = 0
            expectedSize = 0
        }

        fun add(fragment: ByteArray): ByteArray? {
            if (fragment.size < 4) return reject()
            val offset = uint16(fragment, 0)
            val lastFragment = fragment[2].unsigned()
            val size = fragment[3].unsigned()
            if (offset == 0) reset()
            if (size !in 1..CHUNK_SIZE || fragment.size < size + 4 || offset != received) return reject()
            if (offset == 0) {
                if (size < 2) return reject()
                expectedSize = uint16(fragment, 4) + 2
                if (expectedSize !in 5..MAX_MESSAGE_SIZE) return reject()
            }
            if (expectedSize == 0 || lastFragment != (expectedSize - 1) / CHUNK_SIZE) return reject()
            if (size != minOf(CHUNK_SIZE, expectedSize - received)) return reject()
            fragment.copyInto(buffer, received, 4, size + 4)
            received += size
            if (received != expectedSize) return null
            val message = buffer.copyOf(received)
            reset()
            return message.takeIf(::validMessage)
        }

        private fun reject(): ByteArray? {
            reset()
            return null
        }
    }

    fun parseResponse(message: ByteArray): Response? {
        if (message.size < 7 || !validMessage(message)) return null
        val command = uint16(message, 2)
        if (command and 0x8000 == 0) return null
        return Response(command, message[4].unsigned(), message.copyOfRange(5, message.lastIndex))
    }

    /**
     * Decode structurally identified fields. Metric profiles use kilograms and kcal.
     * Stop at an unknown fixed-width tag; length-prefixed extensions can be skipped.
     * Unsigned all-ones sentinels remain absent. Signed impedance is a genuine signed
     * value; assessment scores use a separate sign-and-magnitude byte encoding.
     */
    fun parseMeasurement(response: Response, unit: Int): Reading? {
        if (response.command != 0xb010 || response.status != 0 || unit != UNIT_KG) return null
        val data = response.payload
        if (data.isEmpty()) return null
        var offset = 1 // record index, not a unit identifier
        var days: Int? = null
        var ticks: Int? = null
        val values = linkedMapOf<Metric, Float>()
        val seen = mutableSetOf<Int>()
        while (offset < data.size) {
            if (offset + 2 > data.size) return null
            val tag = uint16(data, offset)
            offset += 2
            val metric = Metric.byTag[tag]
            val extension = tag in 0x7e21..0x7e7e
            val size = when {
                extension -> {
                    if (offset >= data.size) return null
                    data[offset++].unsigned()
                }
                metric != null -> metric.encoding.width
                tag in profileWidths -> profileWidths.getValue(tag)
                tag in setOf(0x6051, 0x6052, 0x6054, 0x6055, 0x6f21, 0x6f22) -> 2
                else -> break
            }
            if (offset + size > data.size) return null
            if ((!extension || metric != null) && !seen.add(tag)) return null
            if (metric != null) {
                if (size !in 1..4 || metric.encoding != Encoding.VARIABLE_INTEGER && size != metric.encoding.width) return null
                decodeValue(metric.encoding, data, offset, size)?.let { value ->
                    if (metric != Metric.PHYSIQUE_RATING || value in 1f..9f) values[metric] = value
                }
            }
            when (tag) {
                0x6a32 -> days = uint16(data, offset).takeIf { it != 0xffff }
                0x6a33 -> ticks = (data[offset].unsigned() shl 16) or uint16(data, offset + 1)
                0x6a15 -> if (readUnsigned(data, offset, size) != unit.toLong()) return null
            }
            offset += size
        }
        val weight = values[Metric.WEIGHT]?.takeIf { it > 0f } ?: return null
        val measuredAt = if (days != null && ticks != null && ticks < 172800) {
            DATE_EPOCH.plusDays(days.toLong()).atStartOfDay().plusSeconds((ticks / 2).toLong())
        } else null
        return Reading(weight, values[Metric.BODY_FAT], measuredAt, values.toMap())
    }

    private fun readUnsigned(data: ByteArray, offset: Int, size: Int): Long =
        (offset until offset + size).fold(0L) { value, index -> (value shl 8) or data[index].unsigned().toLong() }

    private fun decodeValue(encoding: Encoding, data: ByteArray, offset: Int, size: Int): Float? {
        val raw = readUnsigned(data, offset, size)
        val signedWord = encoding in setOf(Encoding.SIGNED_TENTHS, Encoding.SIGNED_HUNDREDTHS)
        if (!signedWord && raw == (1L shl (size * 8)) - 1) return null
        return when (encoding) {
            Encoding.HUNDREDTHS -> raw / 100f
            Encoding.TENTHS -> raw / 10f
            Encoding.SIGNED_SCORE -> {
                val magnitude = (raw and 0x7f).toInt()
                (if (raw and 0x80 != 0L) -magnitude else magnitude).toFloat()
            }
            Encoding.SIGNED_TENTHS -> raw.toShort() / 10f
            Encoding.SIGNED_HUNDREDTHS -> raw.toShort() / 100f
            else -> raw.toFloat()
        }
    }

    private fun validMessage(bytes: ByteArray): Boolean =
        bytes.size in 5..MAX_MESSAGE_SIZE && uint16(bytes, 0) == bytes.size - 2 && checksum(bytes) == 0xff

    private fun checksum(bytes: ByteArray): Int = bytes.sumOf { it.unsigned() } and 0xff

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].unsigned() shl 8) or bytes[offset + 1].unsigned()

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeField(output: ByteArrayOutputStream, tag: Int, value: ByteArray) {
        output.write(tag ushr 8)
        output.write(tag and 0xff)
        if (tag in 0x7e21..0x7e7e) output.write(value.size)
        output.write(value)
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
