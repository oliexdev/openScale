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

import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Huawei Hagrid / WSP framing, authentication and payload helpers.
 *
 * The CAK/C1/C2 values are supplied by the caller. This library intentionally
 * does not contain any Huawei Health key-extraction logic.
 */
object HuaweiHagridWspLib {
    const val FRAME_WRITE_PLAIN = 0xDB
    const val FRAME_WRITE_ENCRYPTED = 0xDC
    const val FRAME_NOTIFY_PLAIN = 0xBD
    const val FRAME_NOTIFY_ENCRYPTED = 0xCD
    const val CAPABILITY_BIT_HIGH_FREQUENCY_IMPEDANCE = 18

    private const val MAX_SIMPLE_PAYLOAD_BYTES = 240
    private const val SIMPLE_CHUNK_BYTES = 15
    private const val USER_INFO_PAYLOAD_BYTES = 69
    private const val MANAGER_HUID_BYTES = 30
    private const val MANAGER_DEVICE_ID_BYTES = 40
    private val AUTH_TOKEN_SUFFIX_TO_SCALE = byteArrayOf(0x31, 0x31, 0x32, 0x33) // "1123"
    private val AUTH_TOKEN_SUFFIX_FROM_SCALE = byteArrayOf(0x39, 0x38, 0x35, 0x36) // "9856"

    private val crcTable = intArrayOf(
        0, 4129, 8258, 12387, 16516, 20645, 24774, 28903, 33032, 37161, 41290, 45419, 49548, 53677, 57806, 61935,
        4657, 528, 12915, 8786, 21173, 17044, 29431, 25302, 37689, 33560, 45947, 41818, 54205, 50076, 62463, 58334,
        9314, 13379, 1056, 5121, 25830, 29895, 17572, 21637, 42346, 46411, 34088, 38153, 58862, 62927, 50604, 54669,
        13907, 9842, 5649, 1584, 30423, 26358, 22165, 18100, 46939, 42874, 38681, 34616, 63455, 59390, 55197, 51132,
        18628, 22757, 26758, 30887, 2112, 6241, 10242, 14371, 51660, 55789, 59790, 63919, 35144, 39273, 43274, 47403,
        23285, 19156, 31415, 27286, 6769, 2640, 14899, 10770, 56317, 52188, 64447, 60318, 39801, 35672, 47931, 43802,
        27814, 31879, 19684, 23749, 11298, 15363, 3168, 7233, 60846, 64911, 52716, 56781, 44330, 48395, 36200, 40265,
        32407, 28342, 24277, 20212, 15891, 11826, 7761, 3696, 65439, 61374, 57309, 53244, 48923, 44858, 40793, 36728,
        37256, 33193, 45514, 41451, 53516, 49453, 61774, 57711, 4224, 97, 12482, 8419, 20484, 16421, 28742, 24679,
        33721, 37784, 41979, 46042, 49981, 54044, 58239, 62302, 689, 4752, 8947, 13010, 16949, 21012, 25207, 29270,
        46570, 42443, 38312, 34185, 62830, 58703, 54572, 50445, 13538, 9411, 5280, 1153, 29798, 25671, 21540, 17413,
        42971, 47098, 34713, 38840, 59231, 63358, 50973, 55100, 9939, 14066, 1681, 5808, 26199, 30326, 17941, 22068,
        55628, 51565, 63758, 59695, 39368, 35305, 47498, 43435, 22596, 18533, 30726, 26663, 6336, 2273, 14466, 10403,
        52093, 56156, 60223, 64286, 35833, 39896, 43963, 48026, 19061, 23124, 27191, 31254, 2801, 6864, 10931, 14994,
        64814, 60687, 56684, 52557, 48554, 44427, 40424, 36297, 31782, 27655, 23652, 19525, 15522, 11395, 7392, 3265,
        61215, 65342, 53085, 57212, 44955, 49082, 36825, 40952, 28183, 32310, 20053, 24180, 11923, 16050, 3793, 7920
    )

    data class WspFrame(
        val encrypted: Boolean,
        val totalFrames: Int,
        val frameIndex: Int,
        val payload: ByteArray,
        val crcValid: Boolean
    ) {
        override fun equals(other: Any?): Boolean =
            other is WspFrame &&
                encrypted == other.encrypted &&
                totalFrames == other.totalFrames &&
                frameIndex == other.frameIndex &&
                crcValid == other.crcValid &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int {
            var result = encrypted.hashCode()
            result = 31 * result + totalFrames
            result = 31 * result + frameIndex
            result = 31 * result + crcValid.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    data class HagridWeightMeasurement(
        val rawLength: Int,
        val weightKg: Float,
        val fatPercent: Float,
        val timestamp: Date?,
        val lowFrequencyImpedance: List<Int>,
        val highFrequencyImpedance: List<Int>,
        val heartRateBpm: Int?,
        val trailingBytesHex: String?,
        val historySuspectedFlag: Int? = null,
        val historyCompleteFlag: Int? = null
    ) {
        val representativeLowOhm: Double?
            get() = lowFrequencyImpedance.firstNotNullOfOrNull { hagridImpedanceOhm(it) }

        val representativeHighOhm: Double?
            get() = highFrequencyImpedance.firstNotNullOfOrNull { hagridImpedanceOhm(it) }

        val lowFrequencyImpedanceOhm: List<Double?>
            get() = lowFrequencyImpedance.map { hagridImpedanceOhm(it) }

        val highFrequencyImpedanceOhm: List<Double?>
            get() = highFrequencyImpedance.map { hagridImpedanceOhm(it) }

        val historyDedupeKey: String?
            get() {
                val millis = timestamp?.time ?: return null
                return listOf(
                    millis.toString(),
                    (weightKg * 100.0f).roundToInt().toString(),
                    (fatPercent * 10.0f).roundToInt().toString(),
                    heartRateBpm?.toString().orEmpty(),
                    lowFrequencyImpedance.joinToString(separator = ","),
                    highFrequencyImpedance.joinToString(separator = ",")
                ).joinToString(separator = "|")
            }

        val isHistoryComplete: Boolean?
            get() = historyCompleteFlag?.let { it == 0 }

        private fun hagridImpedanceOhm(raw: Int): Double? =
            when (raw) {
                in 1..3999 -> raw.toDouble()
                in 4000..39999 -> raw / 10.0
                else -> null
            }
    }

    data class HagridScaleVersion(
        val version: String,
        val serialOrDataInfo: String,
        val rawLength: Int
    )

    data class HagridWeightUnit(
        val raw: Int,
        val parsed: Int,
        val label: String
    )

    data class HagridProductInfo(
        val rawText: String,
        val smartProductId: String,
        val variant: String,
        val rawLength: Int
    )

    enum class HagridProductFamily {
        SCALE_2_PRO,
        SCALE_3_PRO,
        SCALE_3,
        HONOR_SCALE_2,
        DOBBY,
        UNKNOWN
    }

    data class HagridProductProfile(
        val family: HagridProductFamily,
        val displayName: String,
        val smartProductId: String?,
        val expectedHighFrequencyImpedance: Boolean?
    )

    data class HagridCapabilityBits(
        val rawHex: String,
        val byteCount: Int,
        val supportedBits: Set<Int>
    ) {
        val supportsHighFrequencyImpedance: Boolean
            get() = supports(CAPABILITY_BIT_HIGH_FREQUENCY_IMPEDANCE)

        fun supports(bit: Int): Boolean =
            bit >= 0 && supportedBits.contains(bit)
    }

    data class StandardMeasurement(
        val weightKg: Float? = null,
        val fatPercent: Float? = null,
        val musclePercent: Float? = null,
        val bodyWaterMassKg: Float? = null,
        val impedanceOhm: Double? = null,
        val scaleUserIndex: Int? = null,
        val timestamp: Date? = null
    )

    data class HagridUserInfo(
        val huid: String,
        val uid: String,
        val gender: Int,
        val ageYears: Int,
        val heightCm: Int,
        val weightKg: Float,
        val userType: Int = 0
    )

    data class HagridManagerInfo(
        val huid: String,
        val deviceId: String,
        val accountInfo: String?,
        val rawLength: Int
    )

    data class HagridSecrets(
        val cak: ByteArray,
        val c1: ByteArray,
        val c2: ByteArray
    ) {
        init {
            require(cak.size == 16) { "CAK must be 16 bytes" }
            require(c1.size == 16) { "C1 must be 16 bytes" }
            require(c2.size == 16) { "C2 must be 16 bytes" }
        }

        override fun equals(other: Any?): Boolean =
            other is HagridSecrets &&
                cak.contentEquals(other.cak) &&
                c1.contentEquals(other.c1) &&
                c2.contentEquals(other.c2)

        override fun hashCode(): Int {
            var result = cak.contentHashCode()
            result = 31 * result + c1.contentHashCode()
            result = 31 * result + c2.contentHashCode()
            return result
        }
    }

    data class HagridSession(
        val randA: ByteArray,
        val randB: ByteArray,
        val rootKey: ByteArray,
        val workKey: ByteArray
    ) {
        init {
            require(randA.size == 16) { "randA must be 16 bytes" }
            require(randB.size == 16) { "randB must be 16 bytes" }
            require(rootKey.size == 16) { "rootKey must be 16 bytes" }
            require(workKey.size == 16) { "workKey must be 16 bytes" }
        }

        override fun equals(other: Any?): Boolean =
            other is HagridSession &&
                randA.contentEquals(other.randA) &&
                randB.contentEquals(other.randB) &&
                rootKey.contentEquals(other.rootKey) &&
                workKey.contentEquals(other.workKey)

        override fun hashCode(): Int {
            var result = randA.contentHashCode()
            result = 31 * result + randB.contentHashCode()
            result = 31 * result + rootKey.contentHashCode()
            result = 31 * result + workKey.contentHashCode()
            return result
        }
    }

    class FrameAccumulator(private val requireValidCrc: Boolean = true) {
        private var expectedFrames: Int = 0
        private var encrypted: Boolean = false
        private var allFramesCrcValid: Boolean = true
        private val chunks = mutableMapOf<Int, ByteArray>()

        var lastCompletedEncrypted: Boolean = false
            private set

        fun ingest(rawFrame: ByteArray): ByteArray? {
            val frame = parseNotificationFrame(rawFrame, requireValidCrc) ?: return null
            if (frame.totalFrames <= 1) {
                lastCompletedEncrypted = frame.encrypted
                reset()
                return frame.payload
            }

            if (expectedFrames != frame.totalFrames || encrypted != frame.encrypted) {
                reset()
                expectedFrames = frame.totalFrames
                encrypted = frame.encrypted
            }

            allFramesCrcValid = allFramesCrcValid && frame.crcValid
            chunks[frame.frameIndex] = frame.payload
            if (chunks.size < expectedFrames) return null

            val out = ByteArray(chunks.values.sumOf { it.size })
            var pos = 0
            for (i in 0 until expectedFrames) {
                val chunk = chunks[i] ?: run {
                    reset()
                    return null
                }
                chunk.copyInto(out, pos)
                pos += chunk.size
            }
            lastCompletedEncrypted = encrypted
            reset()
            return out
        }

        fun reset() {
            expectedFrames = 0
            encrypted = false
            allFramesCrcValid = true
            chunks.clear()
        }
    }

    fun buildWriteFrames(payload: ByteArray, encrypted: Boolean = false): List<ByteArray> {
        require(payload.size <= MAX_SIMPLE_PAYLOAD_BYTES) {
            "WSP long payloads > $MAX_SIMPLE_PAYLOAD_BYTES bytes are not implemented"
        }

        val total = maxOf(1, ceil(payload.size / SIMPLE_CHUNK_BYTES.toDouble()).toInt())
        val frames = ArrayList<ByteArray>(total)
        var offset = 0
        for (index in 0 until total) {
            val chunkLen = min(SIMPLE_CHUNK_BYTES, payload.size - offset).coerceAtLeast(0)
            val frame = ByteArray(chunkLen + 5)
            frame[0] = (if (encrypted) FRAME_WRITE_ENCRYPTED else FRAME_WRITE_PLAIN).toByte()
            frame[1] = (chunkLen + 3).toByte()
            frame[2] = ((((total - 1) and 0x0F) shl 4) or (index and 0x0F)).toByte()
            if (chunkLen > 0) {
                payload.copyInto(frame, destinationOffset = 3, startIndex = offset, endIndex = offset + chunkLen)
                offset += chunkLen
            }
            putCrc(frame)
            frames += frame
        }
        return frames
    }

    fun parseNotificationFrame(frame: ByteArray, requireValidCrc: Boolean = true): WspFrame? {
        if (frame.size < 5) return null
        val prefix = frame[0].toInt() and 0xFF
        if (prefix != FRAME_NOTIFY_PLAIN && prefix != FRAME_NOTIFY_ENCRYPTED) return null
        val crcValid = hasValidNotificationCrc(frame)
        if (requireValidCrc && !crcValid) return null

        val payloadLen = (frame[1].toInt() and 0xFF) - 3
        if (payloadLen < 0 || frame.size < payloadLen + 5) return null

        val totalFrames = ((frame[2].toInt() ushr 4) and 0x0F) + 1
        val frameIndex = frame[2].toInt() and 0x0F
        if (frameIndex >= totalFrames) return null

        return WspFrame(
            encrypted = prefix == FRAME_NOTIFY_ENCRYPTED,
            totalFrames = totalFrames,
            frameIndex = frameIndex,
            payload = frame.copyOfRange(3, 3 + payloadLen),
            crcValid = crcValid
        )
    }

    fun hasNotificationFramePrefix(frame: ByteArray): Boolean {
        val prefix = frame.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        return prefix == FRAME_NOTIFY_PLAIN || prefix == FRAME_NOTIFY_ENCRYPTED
    }

    fun hasValidNotificationCrc(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        val expected = u16le(frame, frame.size - 2)
        return crc16Modbus(frame.copyOfRange(0, frame.size - 2)) == expected
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = ((crc shl 8) xor crcTable[((byte.toInt() and 0xFF) xor (crc ushr 8)) and 0xFF]) and 0xFFFF
        }
        return crc
    }

    fun crc16Modbus(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    fun parseRealtimeMeasurement(payload: ByteArray): HagridWeightMeasurement? =
        parseHagridWeightMeasurement(
            payload = payload,
            allowedLengths = setOf(26, 38),
            timestampPresent = false
        )

    fun parseHistoryMeasurement(payload: ByteArray): HagridWeightMeasurement? =
        parseHagridWeightMeasurement(
            payload = payload,
            allowedLengths = setOf(27, 28, 39, 40),
            timestampPresent = true
        )

    fun parseScaleVersion(payload: ByteArray): HagridScaleVersion? {
        if (payload.size < 5 || payload.size > 20) return null

        val versionBits = bitArrayMsb(payload[1]) + bitArrayMsb(payload[2]) + bitArrayMsb(payload[3])
        val major = versionBits.subList(0, 4).bitsToInt()
        val minor = versionBits.subList(4, 8).bitsToInt()
        val patch = versionBits.subList(8, 12).bitsToInt()
        val build = versionBits.subList(12, 22).bitsToInt()
        val serial = decodeFixedAscii(payload.copyOfRange(4, payload.size))

        return HagridScaleVersion(
            version = "$major.$minor.$patch.$build",
            serialOrDataInfo = serial,
            rawLength = payload.size
        )
    }

    fun parseWeightUnit(payload: ByteArray): HagridWeightUnit? {
        val raw = payload.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        val parsed = raw.toString(16).toIntOrNull() ?: raw
        val label = when (parsed) {
            1 -> "kg"
            2 -> "lb"
            3 -> "g"
            else -> "unknown"
        }
        return HagridWeightUnit(raw = raw, parsed = parsed, label = label)
    }

    fun parseProductInfo(payload: ByteArray): HagridProductInfo? {
        if (payload.isEmpty()) return null
        val text = decodeFixedAscii(payload)
        if (text.isBlank() || text.startsWith("hex:")) return null

        return HagridProductInfo(
            rawText = text,
            smartProductId = text.take(4),
            variant = text.drop(4),
            rawLength = payload.size
        )
    }

    fun productProfileForMarker(marker: String?): HagridProductProfile {
        val normalized = marker
            ?.trim()
            ?.uppercase(Locale.US)
            .orEmpty()
        return when (normalized) {
            "007B", "HAG-B19", "HAGRID-B19", "HUAWEI SCALE 2 PRO" ->
                HagridProductProfile(
                    family = HagridProductFamily.SCALE_2_PRO,
                    displayName = "HUAWEI Scale 2 Pro",
                    smartProductId = "007B",
                    expectedHighFrequencyImpedance = false
                )

            "M00F", "HAGRID-B29", "HAGRID2021-B19", "HUAWEI SCALE 3 PRO" ->
                HagridProductProfile(
                    family = HagridProductFamily.SCALE_3_PRO,
                    displayName = "HUAWEI Scale 3 Pro",
                    smartProductId = "M00F",
                    expectedHighFrequencyImpedance = true
                )

            "M00D", "HEM-B19", "HERM-B19", "HUAWEI SCALE 3" ->
                HagridProductProfile(
                    family = HagridProductFamily.SCALE_3,
                    displayName = "HUAWEI Scale 3",
                    smartProductId = "M00D",
                    expectedHighFrequencyImpedance = false
                )

            "N001", "LUP-B19", "LUPIN-B19HN" ->
                HagridProductProfile(
                    family = HagridProductFamily.HONOR_SCALE_2,
                    displayName = "HONOR Scale 2",
                    smartProductId = "N001",
                    expectedHighFrequencyImpedance = false
                )

            "M0CJ", "DOBBY-B19" ->
                HagridProductProfile(
                    family = HagridProductFamily.DOBBY,
                    displayName = "Huawei/Honor WSP Scale",
                    smartProductId = "M0CJ",
                    expectedHighFrequencyImpedance = null
                )

            else ->
                HagridProductProfile(
                    family = HagridProductFamily.UNKNOWN,
                    displayName = "Huawei/Honor WSP Scale",
                    smartProductId = normalized.ifBlank { null },
                    expectedHighFrequencyImpedance = null
                )
        }
    }

    fun parseCapabilityBits(capacityHex: String?): HagridCapabilityBits? {
        val hex = capacityHex
            ?.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            ?.uppercase(Locale.US)
            .orEmpty()
        if (hex.isEmpty() || hex.length % 2 != 0) return null

        val bytes = runCatching {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull() ?: return null

        val supportedBits = mutableSetOf<Int>()
        bytes.forEachIndexed { byteIndex, byte ->
            val value = byte.toInt() and 0xFF
            for (bitInByte in 0 until 8) {
                val mask = 1 shl bitInByte
                if ((value and mask) == mask) {
                    supportedBits += byteIndex * 8 + bitInByte
                }
            }
        }

        return HagridCapabilityBits(
            rawHex = hex,
            byteCount = bytes.size,
            supportedBits = supportedBits
        )
    }

    fun buildAuthTokenPayload(randA: ByteArray, randB: ByteArray, cak: ByteArray): ByteArray {
        require(randA.size == 16) { "randA must be 16 bytes" }
        require(randB.size == 16) { "randB must be 16 bytes" }
        require(cak.size == 16) { "CAK must be 16 bytes" }

        return randB + hagridAuthToken(randA, randB, cak, AUTH_TOKEN_SUFFIX_TO_SCALE)
    }

    fun expectedAuthResponsePayload(randA: ByteArray, randB: ByteArray, cak: ByteArray): ByteArray {
        require(randA.size == 16) { "randA must be 16 bytes" }
        require(randB.size == 16) { "randB must be 16 bytes" }
        require(cak.size == 16) { "CAK must be 16 bytes" }

        return hagridAuthToken(randA, randB, cak, AUTH_TOKEN_SUFFIX_FROM_SCALE)
    }

    fun isValidAuthResponsePayload(
        response: ByteArray,
        randA: ByteArray,
        randB: ByteArray,
        cak: ByteArray
    ): Boolean =
        response.contentEquals(expectedAuthResponsePayload(randA, randB, cak))

    fun hagridC3FromBluetoothAddress(address: String): ByteArray {
        val compact = address.filter { it != ':' && it != '-' }.uppercase(Locale.US)
        require(compact.length == 12) { "Bluetooth address must contain 12 hex digits" }
        return (compact + "0000").encodeToByteArray()
    }

    fun deriveHagridRootKey(c1: ByteArray, c2: ByteArray, c3: ByteArray): ByteArray {
        require(c1.size == c2.size) { "C1 and C2 must have the same length" }
        require(c1.isNotEmpty()) { "C1 and C2 must not be empty" }
        require(c3.size == c1.size) { "C3 must be the same length as C1/C2" }

        val mixed = ByteArray(c1.size) { index ->
            ((((c1[index].toInt() and 0xFF) shl 4) xor (c2[index].toInt() and 0xFF)) and 0xFF).toByte()
        }
        val keyData = sha256First16(mixed)
        val folded = ByteArray(keyData.size) { index ->
            (((keyData[index].toInt() and 0xFF) ushr 6) xor (c3[index].toInt() and 0xFF)).toByte()
        }
        return sha256First16(folded)
    }

    fun encryptPayload(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-CTR key must be 16 bytes" }
        require(iv.size == 16) { "AES-CTR IV must be 16 bytes" }
        return aesCtr(plaintext, key, iv)
    }

    fun encryptedPayloadWithIv(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        iv + encryptPayload(plaintext, key, iv)

    fun decryptPayload(ciphertextWithIv: ByteArray, key: ByteArray): ByteArray {
        require(ciphertextWithIv.size >= 16) { "Encrypted payload must include a 16-byte IV" }
        val iv = ciphertextWithIv.copyOfRange(0, 16)
        val ciphertext = ciphertextWithIv.copyOfRange(16, ciphertextWithIv.size)
        return encryptPayload(ciphertext, key, iv)
    }

    fun buildEncryptedWriteFrames(plaintext: ByteArray, key: ByteArray, iv: ByteArray): List<ByteArray> =
        buildWriteFrames(encryptedPayloadWithIv(plaintext, key, iv), encrypted = true)

    fun buildSendWorkKeyFrames(workKey: ByteArray, rootKey: ByteArray, iv: ByteArray): List<ByteArray> {
        require(workKey.size == 16) { "workKey must be 16 bytes" }
        return buildEncryptedWriteFrames(workKey, rootKey, iv)
    }

    fun buildUserInfoPayload(info: HagridUserInfo): ByteArray {
        val payload = ByteArray(USER_INFO_PAYLOAD_BYTES)
        copyUtf8Fixed(info.huid, payload, offset = 0, length = 30)
        copyUtf8Fixed(info.uid, payload, offset = 30, length = 32)
        payload[62] = info.gender.coerceIn(0, 255).toByte()
        payload[63] = info.ageYears.coerceIn(0, 255).toByte()
        putU16Le(payload, 64, info.heightCm.coerceIn(0, 0xFFFF))

        val weightCentiKg = if (info.weightKg.isFinite() && info.weightKg > 0f) {
            (info.weightKg * 100f).roundToInt().coerceIn(0, 0xFFFF)
        } else {
            0
        }
        putU16Le(payload, 66, weightCentiKg)
        payload[68] = info.userType.coerceIn(0, 255).toByte()
        return payload
    }

    fun buildManagerInfoPayload(huid: String, deviceId: String, accountInfo: String? = null): ByteArray {
        val accountBytes = accountInfo?.encodeToByteArray()?.let { bytes ->
            bytes.copyOfRange(0, min(bytes.size, 255))
        }
        val payload = if (accountBytes == null) {
            ByteArray(MANAGER_HUID_BYTES + MANAGER_DEVICE_ID_BYTES)
        } else {
            ByteArray(MANAGER_HUID_BYTES + MANAGER_DEVICE_ID_BYTES + 1 + accountBytes.size)
        }
        copyUtf8Fixed(huid, payload, offset = 0, length = MANAGER_HUID_BYTES)
        copyUtf8Fixed(deviceId, payload, offset = MANAGER_HUID_BYTES, length = MANAGER_DEVICE_ID_BYTES)
        if (accountBytes != null) {
            val accountLengthOffset = MANAGER_HUID_BYTES + MANAGER_DEVICE_ID_BYTES
            payload[accountLengthOffset] = accountBytes.size.toByte()
            accountBytes.copyInto(payload, destinationOffset = accountLengthOffset + 1)
        }
        return payload
    }

    fun parseManagerInfo(payload: ByteArray): HagridManagerInfo? {
        if (payload.isEmpty()) {
            return HagridManagerInfo(huid = "", deviceId = "", accountInfo = null, rawLength = 0)
        }
        if (payload.size <= MANAGER_HUID_BYTES) return null

        val huid = decodeFixedText(payload.copyOfRange(0, MANAGER_HUID_BYTES))
        val extended = payload.size > MANAGER_HUID_BYTES + MANAGER_DEVICE_ID_BYTES
        val deviceIdEnd = if (extended) {
            MANAGER_HUID_BYTES + MANAGER_DEVICE_ID_BYTES
        } else {
            payload.size
        }
        val deviceId = decodeFixedText(payload.copyOfRange(MANAGER_HUID_BYTES, deviceIdEnd))

        val accountLengthOffset = MANAGER_HUID_BYTES + MANAGER_DEVICE_ID_BYTES
        val accountInfo = if (extended) {
            val declaredLength = u8(payload, accountLengthOffset)
            val accountEnd = min(payload.size, accountLengthOffset + 1 + declaredLength)
            if (accountEnd > accountLengthOffset + 1) {
                decodeFixedText(payload.copyOfRange(accountLengthOffset + 1, accountEnd))
                    .takeIf { it.isNotBlank() }
            } else {
                null
            }
        } else {
            null
        }

        return HagridManagerInfo(
            huid = huid,
            deviceId = deviceId,
            accountInfo = accountInfo,
            rawLength = payload.size
        )
    }

    fun parseStandardWeightMeasurement(payload: ByteArray): StandardMeasurement? {
        if (payload.size < 3) return null
        var offset = 0
        val flags = u8(payload, offset)
        offset += 1

        val imperial = (flags and 0x01) != 0
        val timestampPresent = (flags and 0x02) != 0
        val userPresent = (flags and 0x04) != 0
        val bmiAndHeightPresent = (flags and 0x08) != 0

        val weightRaw = u16leOrNull(payload, offset) ?: return null
        offset += 2
        val weightKg = massToKg(weightRaw, imperial)

        val timestamp = if (timestampPresent) {
            readTimestamp(payload, offset) ?: return null
        } else {
            null
        }
        if (timestampPresent) offset += 7

        val scaleUserIndex = if (userPresent) {
            u8OrNull(payload, offset) ?: return null
        } else {
            null
        }
        if (userPresent) offset += 1

        if (bmiAndHeightPresent) {
            u16leOrNull(payload, offset) ?: return null
            offset += 2
            u16leOrNull(payload, offset) ?: return null
        }

        return StandardMeasurement(
            weightKg = weightKg,
            scaleUserIndex = scaleUserIndex,
            timestamp = timestamp
        )
    }

    fun parseStandardBodyCompositionMeasurement(payload: ByteArray): StandardMeasurement? {
        if (payload.size < 4) return null
        var offset = 0
        val flags = u16le(payload, offset)
        offset += 2

        val imperial = (flags and 0x0001) != 0
        val timestampPresent = (flags and 0x0002) != 0
        val userPresent = (flags and 0x0004) != 0
        val basalMetabolismPresent = (flags and 0x0008) != 0
        val musclePercentPresent = (flags and 0x0010) != 0
        val muscleMassPresent = (flags and 0x0020) != 0
        val fatFreeMassPresent = (flags and 0x0040) != 0
        val softLeanMassPresent = (flags and 0x0080) != 0
        val bodyWaterMassPresent = (flags and 0x0100) != 0
        val impedancePresent = (flags and 0x0200) != 0
        val weightPresent = (flags and 0x0400) != 0
        val heightPresent = (flags and 0x0800) != 0

        val bodyFatRaw = u16leOrNull(payload, offset) ?: return null
        offset += 2
        val fatPercent = bodyFatRaw * 0.1f

        val timestamp = if (timestampPresent) {
            readTimestamp(payload, offset) ?: return null
        } else {
            null
        }
        if (timestampPresent) offset += 7

        val scaleUserIndex = if (userPresent) {
            u8OrNull(payload, offset) ?: return null
        } else {
            null
        }
        if (userPresent) offset += 1

        if (basalMetabolismPresent) {
            u16leOrNull(payload, offset) ?: return null
            offset += 2
        }

        val musclePercent = if (musclePercentPresent) {
            val raw = u16leOrNull(payload, offset) ?: return null
            offset += 2
            raw * 0.1f
        } else {
            null
        }

        if (muscleMassPresent) {
            u16leOrNull(payload, offset) ?: return null
            offset += 2
        }
        if (fatFreeMassPresent) {
            u16leOrNull(payload, offset) ?: return null
            offset += 2
        }
        if (softLeanMassPresent) {
            u16leOrNull(payload, offset) ?: return null
            offset += 2
        }

        val bodyWaterMassKg = if (bodyWaterMassPresent) {
            val raw = u16leOrNull(payload, offset) ?: return null
            offset += 2
            massToKg(raw, imperial)
        } else {
            null
        }

        val impedanceOhm = if (impedancePresent) {
            val raw = u16leOrNull(payload, offset) ?: return null
            offset += 2
            (raw * 0.1f).toDouble()
        } else {
            null
        }

        val weightKg = if (weightPresent) {
            val raw = u16leOrNull(payload, offset) ?: return null
            offset += 2
            massToKg(raw, imperial)
        } else {
            null
        }

        if (heightPresent) {
            u16leOrNull(payload, offset) ?: return null
        }

        return StandardMeasurement(
            weightKg = weightKg,
            fatPercent = fatPercent,
            musclePercent = musclePercent,
            bodyWaterMassKg = bodyWaterMassKg,
            impedanceOhm = impedanceOhm,
            scaleUserIndex = scaleUserIndex,
            timestamp = timestamp
        )
    }

    private fun parseHagridWeightMeasurement(
        payload: ByteArray,
        allowedLengths: Set<Int>,
        timestampPresent: Boolean
    ): HagridWeightMeasurement? {
        if (payload.size !in allowedLengths) return null

        val highResistancePresent = payload.size >= 38
        val baseLength = if (highResistancePresent) 38 else 26
        val weightKg = u16le(payload, 0) / 100.0f
        val fatPercent = u16le(payload, 2) / 10.0f
        val timestamp = if (timestampPresent) {
            readTimestamp(payload, 4) ?: return null
        } else {
            null
        }
        val low = (0 until 6).map { u16le(payload, 12 + it * 2) }
        val high = if (highResistancePresent) {
            (0 until 6).map { u16le(payload, 26 + it * 2) }
        } else {
            emptyList()
        }
        val heartRateRaw = u16le(payload, 24)
        val heartRate = heartRateRaw.takeIf { it in 1..240 }
        val trailing = if (payload.size > baseLength) {
            payload.copyOfRange(baseLength, payload.size).toHexString()
        } else {
            null
        }
        val historySuspectedFlag = if (timestampPresent && payload.size > baseLength) {
            u8(payload, baseLength)
        } else {
            null
        }
        val historyCompleteFlag = if (timestampPresent && payload.size > baseLength + 1) {
            u8(payload, baseLength + 1)
        } else {
            null
        }

        return HagridWeightMeasurement(
            rawLength = payload.size,
            weightKg = weightKg,
            fatPercent = fatPercent,
            timestamp = timestamp,
            lowFrequencyImpedance = low,
            highFrequencyImpedance = high,
            heartRateBpm = heartRate,
            trailingBytesHex = trailing,
            historySuspectedFlag = historySuspectedFlag,
            historyCompleteFlag = historyCompleteFlag
        )
    }

    fun currentTimePayload(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val year = calendar.get(Calendar.YEAR)
        val dayOfWeek = (((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1)
        return byteArrayOf(
            (year and 0xFF).toByte(),
            ((year ushr 8) and 0xFF).toByte(),
            (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(),
            calendar.get(Calendar.SECOND).toByte(),
            dayOfWeek.toByte()
        )
    }

    private fun putCrc(frame: ByteArray) {
        frame[frame.size - 2] = 0
        frame[frame.size - 1] = 0
        val crc = crc16(frame)
        frame[frame.size - 2] = (crc and 0xFF).toByte()
        frame[frame.size - 1] = ((crc ushr 8) and 0xFF).toByte()
    }

    private fun hagridAuthToken(randA: ByteArray, randB: ByteArray, cak: ByteArray, suffix: ByteArray): ByteArray {
        val rand = randA + randB
        val innerKey = hmacSha256(rand, cak + suffix)
        return hmacSha256(rand, innerKey)
    }

    private fun hmacSha256(message: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun sha256First16(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.copyOfRange(0, 16)
    }

    private fun aesCtr(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun putU16Le(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun copyUtf8Fixed(value: String, target: ByteArray, offset: Int, length: Int) {
        val bytes = value.encodeToByteArray()
        bytes.copyInto(target, destinationOffset = offset, startIndex = 0, endIndex = min(bytes.size, length))
    }

    private fun decodeFixedText(bytes: ByteArray): String {
        val nul = bytes.indexOf(0)
        val meaningful = if (nul >= 0) bytes.copyOfRange(0, nul) else bytes.copyOf()
        val trimmed = meaningful
            .dropLastWhile { byte -> byte == 0.toByte() || byte == 0x20.toByte() }
            .toByteArray()
        if (trimmed.isEmpty()) return ""

        val printableAscii = trimmed.all { byte ->
            val c = byte.toInt() and 0xFF
            c in 0x20..0x7E
        }
        return if (printableAscii) trimmed.decodeToString() else "hex:${trimmed.toHexString()}"
    }

    private fun decodeFixedAscii(bytes: ByteArray): String =
        decodeFixedText(bytes)

    private fun u16leOrNull(data: ByteArray, offset: Int): Int? =
        if (offset + 1 < data.size) u16le(data, offset) else null

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u8OrNull(data: ByteArray, offset: Int): Int? =
        if (offset < data.size) u8(data, offset) else null

    private fun readTimestamp(data: ByteArray, offset: Int): Date? {
        if (offset + 6 >= data.size) return null
        val year = u16le(data, offset)
        val month = u8(data, offset + 2)
        val day = u8(data, offset + 3)
        val hour = u8(data, offset + 4)
        val minute = u8(data, offset + 5)
        val second = u8(data, offset + 6)
        if (year < 2000 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }
        val cal = Calendar.getInstance()
        cal.set(year, (month - 1).coerceIn(0, 11), day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun massToKg(raw: Int, imperial: Boolean): Float {
        val value = if (imperial) raw * 0.01f else raw * 0.005f
        return if (imperial) value * 0.45359237f else value
    }

    private fun bitArrayMsb(byte: Byte): List<Int> {
        var value = byte.toInt()
        val bits = MutableList(8) { 0 }
        for (i in 7 downTo 0) {
            bits[i] = value and 1
            value = value shr 1
        }
        return bits
    }

    private fun List<Int>.bitsToInt(): Int {
        var value = 0
        forEach { bit ->
            value = (value shl 1) or (bit and 1)
        }
        return value
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }
}


/**
 * Replaceable source for Huawei Hagrid CAK/C1/C2 key material.
 *
 * Implementations must not log raw secret values. The upstream app only uses
 * explicitly configured settings here; private Huawei Health extraction tools
 * are outside this boundary.
 */
interface HuaweiHagridSecretProvider {
    val id: String

    fun load(
        getString: (String) -> String?,
        warn: (String) -> Unit
    ): HuaweiHagridWspLib.HagridSecrets?

    class DriverSettings(
        private val cakKey: String,
        private val c1Key: String,
        private val c2Key: String
    ) : HuaweiHagridSecretProvider {
        override val id: String = "driver-settings"

        override fun load(
            getString: (String) -> String?,
            warn: (String) -> Unit
        ): HuaweiHagridWspLib.HagridSecrets? {
            val cak = settingsHexOrNull(getString, warn, cakKey, 16)
            val c1 = settingsHexOrNull(getString, warn, c1Key, 16)
            val c2 = settingsHexOrNull(getString, warn, c2Key, 16)
            if (cak == null && c1 == null && c2 == null) return null
            if (cak == null || c1 == null || c2 == null) {
                warn("Incomplete Huawei Hagrid secrets; require CAK, C1, and C2 hex settings")
                return null
            }
            return runCatching { HuaweiHagridWspLib.HagridSecrets(cak, c1, c2) }
                .getOrElse { error ->
                    warn("Invalid Huawei Hagrid secrets: ${error.message}")
                    null
                }
        }

        private fun settingsHexOrNull(
            getString: (String) -> String?,
            warn: (String) -> Unit,
            key: String,
            expectedBytes: Int
        ): ByteArray? {
            val raw = getString(key)?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val hex = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (hex.length != expectedBytes * 2) {
                warn("Ignoring Huawei Hagrid setting $key: expected ${expectedBytes * 2} hex digits, got ${hex.length}")
                return null
            }
            return runCatching {
                ByteArray(expectedBytes) { index ->
                    hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }.getOrElse { error ->
                warn("Ignoring Huawei Hagrid setting $key: ${error.message}")
                null
            }
        }
    }
}