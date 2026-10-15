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
 * Tuya BLE scale protocol (category "tzc1" and similar), implemented from the
 * wire format documented by the MIT-licensed ha-tuya-ble project
 * (https://github.com/ha-tuya-ble/ha_tuya_ble) and verified against a
 * Silvergear Smart Scale 2023 (product id v4ju89si, protocol 4.2).
 */
package com.health.openscale.core.bluetooth.libs

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a Tuya frame is malformed, fails its CRC, or uses an unknown security flag. */
class TuyaBleProtocolException(message: String) : Exception(message)

/** One datapoint decoded from a Tuya DP frame. [value] is a Number, Boolean, String or ByteArray. */
data class TuyaDataPoint(
    val id: Int,
    val type: Int,
    val value: Any?,
    val timestampSeconds: Long,
    val flags: Int,
)

/** A decrypted Tuya frame header plus its payload. */
data class TuyaBleFrame(
    val seqNum: Int,
    val responseTo: Int,
    val code: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TuyaBleFrame) return false
        return seqNum == other.seqNum && responseTo == other.responseTo &&
            code == other.code && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = seqNum
        result = 31 * result + responseTo
        result = 31 * result + code
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Parsed fields of a CODE_DEVICE_INFO response, including the session-key seed and auth key. */
data class TuyaDeviceInfo(
    val deviceVersion: String,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val hardwareVersion: String,
    val flags: Int,
    val bound: Boolean,
    val deviceRandom: ByteArray,
    val authKey: ByteArray,
) {
    val protocolVersionString: String get() = "$protocolMajor.$protocolMinor"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TuyaDeviceInfo) return false
        return deviceVersion == other.deviceVersion && protocolMajor == other.protocolMajor &&
            protocolMinor == other.protocolMinor && hardwareVersion == other.hardwareVersion &&
            flags == other.flags && bound == other.bound &&
            deviceRandom.contentEquals(other.deviceRandom) && authKey.contentEquals(other.authKey)
    }

    override fun hashCode(): Int {
        var result = deviceVersion.hashCode()
        result = 31 * result + protocolMajor
        result = 31 * result + protocolMinor
        result = 31 * result + hardwareVersion.hashCode()
        result = 31 * result + flags
        result = 31 * result + bound.hashCode()
        result = 31 * result + deviceRandom.contentHashCode()
        result = 31 * result + authKey.contentHashCode()
        return result
    }
}

/**
 * Key material for a Tuya BLE device.
 *
 * With only the 16-char local_key (legacy pairing), the login key is MD5(local_key[0..6)) and
 * the session key is MD5(local_key[0..6) + 6-byte device random). With an additional 16-char
 * sec_key (protocol v2 pairing) both use MD5(local_key + sec_key [+ device random]).
 */
class TuyaSecurity(localKey: String, secKey: String? = null) {

    private val localKeyBytes: ByteArray = localKey.toByteArray(Charsets.US_ASCII).also {
        require(it.size >= 6) { "Tuya local_key must be at least 6 characters" }
    }
    private val secKeyBytes: ByteArray? = secKey?.takeIf { it.isNotEmpty() }?.let {
        require(it.toByteArray(Charsets.US_ASCII).size == 16) { "Tuya sec_key must be exactly 16 characters" }
        it.toByteArray(Charsets.US_ASCII)
    }

    val protocolV2: Boolean get() = secKeyBytes != null

    /** First 6 bytes of the local_key — sent in clear inside the pair request. */
    val pairingLoginKey: ByteArray = localKeyBytes.copyOf(6)

    private val material: ByteArray =
        if (secKeyBytes != null) localKeyBytes + secKeyBytes else pairingLoginKey

    val loginKey: ByteArray = md5(material)

    fun sessionKey(deviceRandom: ByteArray): ByteArray {
        require(deviceRandom.size == 6) { "Tuya device random must be 6 bytes" }
        return md5(material + deviceRandom)
    }

    val loginFlag: Int get() = if (protocolV2) 14 else 4
    val sessionFlag: Int get() = if (protocolV2) 15 else 5

    private fun md5(data: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(data)
}

/**
 * Pure Kotlin helpers for the Tuya BLE scale protocol — framing, crypto and DP parsing.
 * No Android dependencies; unit-tested in `TuyaBleLibTest`. BLE wiring lives in
 * [com.health.openscale.core.bluetooth.scales.SilvergearTuyaHandler].
 *
 * Wire format (all integers big-endian):
 *  - Outgoing: `seq(4) responseTo(4) code(2) length(2) payload crc16(2)` padded to 16,
 *    AES-128-CBC encrypted behind `flag(1) iv(16)`, then split into ≤20-byte chunks where the
 *    first chunk carries a varint packet number, a varint total length and a protocol-version nibble byte.
 *  - Incoming: same chunking; the decrypted payload's CRC is verified before dispatch.
 *  - Datapoints: `id(1) type(1) length(1|2) value`, types RAW/BOOL/VALUE/STRING/ENUM/BITMAP.
 */
object TuyaBleLib {

    // ── frame codes ───────────────────────────────────────────────────────────
    const val CODE_DEVICE_INFO = 0x0000
    const val CODE_PAIR = 0x0001
    const val CODE_DPS = 0x0002
    const val CODE_DEVICE_STATUS = 0x0003
    const val CODE_RECEIVE_DP = 0x8001
    const val CODE_RECEIVE_TIME_DP = 0x8003
    const val CODE_RECEIVE_SIGN_DP = 0x8004
    const val CODE_RECEIVE_SIGN_TIME_DP = 0x8005
    const val CODE_RECEIVE_DP_V4 = 0x8006
    const val CODE_RECEIVE_TIME_DP_V4 = 0x8007
    const val CODE_TIME1_REQ = 0x8011
    const val CODE_TIME2_REQ = 0x8012

    // ── datapoint types ────────────────────────────────────────────────────────
    const val DP_RAW = 0
    const val DP_BOOL = 1
    const val DP_VALUE = 2
    const val DP_STRING = 3
    const val DP_ENUM = 4
    const val DP_BITMAP = 5

    /** Pair response: 2 = device is already paired to someone else (treated as success). */
    const val PAIR_ALREADY_PAIRED = 2

    const val GATT_MTU = 20

    private val SEC_FLAG_AUTH = 1
    private val SEC_FLAG_LOGIN = setOf(4, 14)
    private val SEC_FLAG_SESSION = setOf(5, 15)

    // ── crc16 (Modbus polynomial 0xA001) ──────────────────────────────────────

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc and 0xFFFF
    }

    // ── varint ─────────────────────────────────────────────────────────────────

    fun packVarint(value: Int): ByteArray {
        val out = ArrayList<Byte>(5)
        var v = value
        while (true) {
            var curr = v and 0x7F
            v = v ushr 7
            if (v != 0) curr = curr or 0x80
            out.add(curr.toByte())
            if (v == 0) break
        }
        return out.toByteArray()
    }

    /** Returns (value, newPos). Throws [TuyaBleProtocolException] if truncated or >5 bytes. */
    fun unpackVarint(data: ByteArray, pos: Int): Pair<Int, Int> {
        var result = 0
        var offset = 0
        while (offset < 5) {
            if (pos + offset >= data.size) throw TuyaBleProtocolException("varint truncated")
            val curr = data[pos + offset].toInt() and 0xFF
            result = result or ((curr and 0x7F) shl (offset * 7))
            offset++
            if ((curr and 0x80) == 0) return result to pos + offset
        }
        throw TuyaBleProtocolException("varint too long")
    }

    // ── AES-128-CBC (no padding — Tuya plaintext is zero-padded to 16) ────────

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(data)

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(data)

    // ── outgoing frames ────────────────────────────────────────────────────────

    /**
     * Encrypt one logical frame into GATT-sized chunks.
     *
     * @param iv 16 random bytes; tests inject a fixed IV so packets are reproducible.
     */
    fun buildPackets(
        seqNum: Int,
        code: Int,
        data: ByteArray,
        responseTo: Int,
        key: ByteArray,
        flag: Int,
        protocolVersion: Int,
        iv: ByteArray,
    ): List<ByteArray> {
        require(iv.size == 16) { "iv must be 16 bytes" }
        val raw = ByteArray(12 + data.size + 2)
        writeU32BE(raw, 0, seqNum)
        writeU32BE(raw, 4, responseTo)
        writeU16BE(raw, 8, code)
        writeU16BE(raw, 10, data.size)
        data.copyInto(raw, 12)
        writeU16BE(raw, 12 + data.size, crc16(raw, 0, 12 + data.size))
        val paddedLen = if (raw.size % 16 == 0) raw.size else raw.size + (16 - raw.size % 16)
        val padded = raw.copyOf(paddedLen)

        val encrypted = ByteArray(1 + 16 + padded.size)
        encrypted[0] = flag.toByte()
        iv.copyInto(encrypted, 1)
        aesCbcEncrypt(key, iv, padded).copyInto(encrypted, 17)

        val packets = ArrayList<ByteArray>()
        var pos = 0
        var packetNum = 0
        while (pos < encrypted.size) {
            val header = ArrayList<Byte>(3)
            packVarint(packetNum).forEach { header.add(it) }
            if (packetNum == 0) {
                packVarint(encrypted.size).forEach { header.add(it) }
                header.add((((protocolVersion and 0x0F) shl 4).toByte()))
            }
            val take = minOf(GATT_MTU - header.size, encrypted.size - pos)
            val packet = ByteArray(header.size + take)
            header.toByteArray().copyInto(packet, 0)
            encrypted.copyInto(packet, header.size, pos, pos + take)
            packets.add(packet)
            pos += take
            packetNum++
        }
        return packets
    }

    /** Device-info request payload used by scales that need the FD50 quirk (`00 f3`). */
    fun buildDeviceInfoRequestPayload(): ByteArray = byteArrayOf(0x00, 0xF3.toByte())

    /** Pair request payload: uuid + first-6-of-local_key + device_id, zero-padded to ≥44 bytes. */
    fun buildPairPayload(uuid: String, pairingLoginKey: ByteArray, deviceId: String): ByteArray {
        val uuidBytes = uuid.toByteArray(Charsets.US_ASCII)
        val idBytes = deviceId.toByteArray(Charsets.US_ASCII)
        val payload = uuidBytes + pairingLoginKey + idBytes
        return if (payload.size < 44) payload.copyOf(44) else payload
    }

    fun randomIv(): ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    // ── incoming frames ────────────────────────────────────────────────────────

    /**
     * Reassembles the ≤20-byte GATT chunks of one logical frame.
     * Chunk layout: varint packetNumber [, varint totalLength + versionByte when number==0] payload.
     */
    class TuyaFrameReassembler {
        private var buffer: ByteArray? = null
        private var expectedLength = 0
        private var expectedPacket = 0

        fun reset() {
            buffer = null
            expectedLength = 0
            expectedPacket = 0
        }

        /** Feed one notification; returns the complete encrypted frame (flag+iv+ciphertext) or null. */
        fun feed(chunk: ByteArray): ByteArray? {
            var (packetNum, pos) = unpackVarint(chunk, 0)
            if (packetNum < expectedPacket) {
                if (packetNum != 0) return null // stray tail of an old frame
                reset()
            }
            if (packetNum == expectedPacket) {
                var buf: ByteArray
                if (packetNum == 0) {
                    val (length, afterLen) = unpackVarint(chunk, pos)
                    expectedLength = length
                    pos = afterLen + 1 // skip the protocol-version nibble byte
                    buf = ByteArray(0)
                } else {
                    buf = buffer ?: return null
                }
                buffer = buf + chunk.copyOfRange(pos, chunk.size)
                expectedPacket++
            } else {
                reset()
                return null
            }
            val current = buffer ?: return null
            if (current.size > expectedLength) {
                reset()
                return null
            }
            if (current.size == expectedLength) {
                reset()
                return current
            }
            return null
        }
    }

    /** Decrypt one complete frame; [keyLookup] maps a security flag to its AES key (or null). */
    fun parseFrame(frame: ByteArray, keyLookup: (Int) -> ByteArray?): TuyaBleFrame {
        if (frame.size < 18) throw TuyaBleProtocolException("frame too short (${frame.size} bytes)")
        val flag = frame[0].toInt() and 0xFF
        val key = keyLookup(flag) ?: throw TuyaBleProtocolException("unknown security flag $flag")
        val iv = frame.copyOfRange(1, 17)
        val raw = aesCbcDecrypt(key, iv, frame.copyOfRange(17, frame.size))
        val seqNum = readU32BE(raw, 0)
        val responseTo = readU32BE(raw, 4)
        val code = readU16BE(raw, 8)
        val length = readU16BE(raw, 10)
        val endPos = 12 + length
        if (raw.size < endPos) throw TuyaBleProtocolException("payload shorter than announced")
        if (raw.size >= endPos + 2 && crc16(raw, 0, endPos) != readU16BE(raw, endPos)) {
            throw TuyaBleProtocolException("CRC mismatch in frame")
        }
        return TuyaBleFrame(seqNum, responseTo, code, raw.copyOfRange(12, endPos))
    }

    /** Security-flag → key lookup for a live session. [authKey] may be null before device-info. */
    fun keyLookup(security: TuyaSecurity, sessionKey: ByteArray?, authKey: ByteArray?): (Int) -> ByteArray? =
        { flag ->
            when {
                flag == SEC_FLAG_AUTH -> authKey
                flag in SEC_FLAG_LOGIN -> security.loginKey
                flag in SEC_FLAG_SESSION -> sessionKey
                else -> null
            }
        }

    // ── device info ────────────────────────────────────────────────────────────

    /** Parse a CODE_DEVICE_INFO payload (≥46 bytes). */
    fun parseDeviceInfo(data: ByteArray): TuyaDeviceInfo {
        if (data.size < 46) throw TuyaBleProtocolException("device info too short (${data.size} bytes)")
        return TuyaDeviceInfo(
            deviceVersion = "${data[0].toInt() and 0xFF}.${data[1].toInt() and 0xFF}",
            protocolMajor = data[2].toInt() and 0xFF,
            protocolMinor = data[3].toInt() and 0xFF,
            hardwareVersion = "${data[12].toInt() and 0xFF}.${data[13].toInt() and 0xFF}",
            flags = data[4].toInt() and 0xFF,
            bound = (data[5].toInt() and 0xFF) != 0,
            deviceRandom = data.copyOfRange(6, 12),
            authKey = data.copyOfRange(14, 46),
        )
    }

    // ── datapoints ─────────────────────────────────────────────────────────────

    /**
     * Parse consecutive `id type length value` entries starting at [start].
     * [lengthSize] is 1 for classic DP frames and 2 for the v4 (0x8006/0x8007) variant.
     */
    fun parseDatapoints(
        data: ByteArray,
        start: Int,
        lengthSize: Int,
        flags: Int,
        timestampSeconds: Long,
    ): List<TuyaDataPoint> {
        val out = ArrayList<TuyaDataPoint>()
        var pos = start
        val headerSize = 2 + lengthSize
        while (data.size - pos >= headerSize) {
            val dpId = data[pos].toInt() and 0xFF
            val dpType = data[pos + 1].toInt() and 0xFF
            if (dpType > DP_BITMAP) throw TuyaBleProtocolException("unknown datapoint type $dpType")
            var valueLen = 0
            for (i in 0 until lengthSize) {
                valueLen = (valueLen shl 8) or (data[pos + 2 + i].toInt() and 0xFF)
            }
            val valuePos = pos + 2 + lengthSize
            if (valuePos + valueLen > data.size) {
                throw TuyaBleProtocolException("datapoint value shorter than announced")
            }
            val raw = data.copyOfRange(valuePos, valuePos + valueLen)
            val value: Any? = when (dpType) {
                DP_RAW, DP_BITMAP -> raw
                DP_BOOL -> readU32BE(raw, 0, raw.size) != 0
                DP_VALUE, DP_ENUM -> readSignedBE(raw)
                else -> String(raw, Charsets.UTF_8)
            }
            out.add(TuyaDataPoint(dpId, dpType, value, timestampSeconds, flags))
            pos = valuePos + valueLen
        }
        return out
    }

    /** Parse a leading timestamp: type 0 = 13 ASCII ms digits, type 1 = 4-byte BE seconds. */
    fun parseTimestamp(data: ByteArray, startPos: Int): Pair<Long, Int> {
        if (startPos >= data.size) throw TuyaBleProtocolException("timestamp missing")
        val timeType = data[startPos].toInt() and 0xFF
        val pos = startPos + 1
        return when (timeType) {
            0 -> {
                if (pos + 13 > data.size) throw TuyaBleProtocolException("timestamp truncated")
                val ms = String(data, pos, 13, Charsets.US_ASCII).toLongOrNull()
                    ?: throw TuyaBleProtocolException("timestamp malformed")
                (ms / 1000) to (pos + 13)
            }
            1 -> {
                if (pos + 4 > data.size) throw TuyaBleProtocolException("timestamp truncated")
                readU32BE(data, pos).toLong() to (pos + 4)
            }
            else -> throw TuyaBleProtocolException("unknown timestamp type $timeType")
        }
    }

    // ── big-endian helpers ─────────────────────────────────────────────────────

    internal fun writeU32BE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    internal fun writeU16BE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 1] = (value and 0xFF).toByte()
    }

    internal fun readU32BE(data: ByteArray, offset: Int, length: Int = 4): Int {
        var result = 0
        for (i in 0 until length) {
            result = (result shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return result
    }

    internal fun readU16BE(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readSignedBE(data: ByteArray): Int {
        var result = 0
        for (b in data) {
            result = (result shl 8) or (b.toInt() and 0xFF)
        }
        // sign-extend from the bit width actually present
        val bits = data.size * 8
        if (bits < 32 && (result and (1 shl (bits - 1))) != 0) {
            result = result or (-1 shl bits)
        }
        return result
    }
}
