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

import java.util.UUID

/**
 * Wire protocol of the iHealth/Jiuan HS2S body-composition scale family, kept free of Android
 * types so it can be unit tested.
 *
 * Covers the iHealth HS2S, iHealth HS2S Pro and the Wyze Scale (WHSCL1, an iHealth OEM build).
 *
 * Frames: `hdr len seqHi seqLo A9 cmd payload… sum`, hdr = B0 (host→scale) / A0 (scale→host),
 * len = bytes from seqHi to the end of the payload, sum = their sum & 0xFF. Frames longer than
 * 20 bytes are split across writes/notifications.
 *
 * Authentication (iHealth `IdentifyIns2`): host sends 16 random ASCII bytes R1 (FA); the scale
 * answers R2(16) · XXTEA(R1, K)(20) · seed(16) (FB), seed = MAC + 10 zero bytes; the host checks
 * R1 and replies XXTEA(R2, K) (FC); the scale acks FD or rejects FE.
 * K = XXTEA(seed, Ka) with a per-model key Ka. iHealth ships Ka in its app; Wyze keeps it on its
 * servers and hands each scale's K ("device_key") to the app, so the Wyze Scale needs that key
 * entered by the user.
 */
object IHealthHs2sLib {

    const val DEVICE_BYTE = 0xA9
    const val HDR_HOST = 0xB0
    const val HDR_SCALE = 0xA0
    const val MAX_CHUNK = 20

    const val CMD_AUTH_R1 = 0xFA
    const val CMD_AUTH_FB = 0xFB
    const val CMD_AUTH_FC = 0xFC
    const val CMD_AUTH_OK = 0xFD
    const val CMD_AUTH_FAIL = 0xFE
    const val CMD_DEVICE_INFO = 0x10
    const val CMD_USER_LIST = 0x20
    const val CMD_USER_UPDATE = 0x21
    const val CMD_USER_ONLINE = 0x23
    const val CMD_MEASURE_FINISHED = 0x24
    const val CMD_OFFLINE_DATA = 0x31
    const val CMD_OFFLINE_DELETE = 0x32
    const val CMD_LIVE_WEIGHT = 0x40
    const val CMD_STABLE_WEIGHT = 0x41
    const val CMD_RESULT = 0x42

    enum class Variant(
        val displayName: String,
        /** 16-character protocol name; the GATT UUIDs are its ASCII bytes. */
        val protocol: String,
        /** Per-model master key, or null when the scale's own key must be entered. */
        val masterKey: ByteArray?,
        val impedanceCount: Int,
        /** WHSCL1 inserts a body-type byte before the body-composition block. */
        val hasBodyTypeByte: Boolean,
        /** User records carry a trailing heart-rate flag (iHealth `createOrUpdateUserInfoHR`). */
        val userRecordHasHrFlag: Boolean,
    ) {
        HS2S(
            "iHealth HS2S", "com.jiuan.BFSV22",
            bytes(46, -27, -49, 66, -121, 28, -10, -101, -43, 1, 4, -56, 43, -128, -126, 122),
            impedanceCount = 6, hasBodyTypeByte = false, userRecordHasHrFlag = false
        ),
        HS2S_PRO(
            "iHealth HS2S Pro", "com.jiuan.HS2S02",
            bytes(-77, -55, -46, 61, 21, 95, -50, 94, 81, -25, -56, -94, -2, -44, -114, -119),
            impedanceCount = 6, hasBodyTypeByte = false, userRecordHasHrFlag = true
        ),
        WYZE_WHSCL1(
            "Wyze Scale (WHSCL1)", "com.jiuan.BFSV21", null,
            impedanceCount = 4, hasBodyTypeByte = true, userRecordHasHrFlag = true
        );

        val service: UUID get() = asciiUuid(protocol)
        val notifyChar: UUID get() = asciiUuid("sed" + protocol.substring(3))
        val writeChar: UUID get() = asciiUuid("rec" + protocol.substring(3))

        /** Record length without the leading status byte (offline records omit it). */
        val recordSize: Int get() = if (this == WYZE_WHSCL1) 35 else 56
    }

    fun variantForService(uuid: UUID): Variant? = Variant.entries.firstOrNull { it.service == uuid }

    fun asciiUuid(s: String): UUID {
        require(s.length == 16) { "protocol name must be 16 chars: $s" }
        val b = s.toByteArray(Charsets.US_ASCII)
        return UUID(beLong(b, 0), beLong(b, 8))
    }

    // --- Framing ---------------------------------------------------------------------------

    fun buildFrame(seq: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf((seq shr 8).toByte(), seq.toByte(), DEVICE_BYTE.toByte(), cmd.toByte()) + payload
        return byteArrayOf(HDR_HOST.toByte(), body.size.toByte()) + body + byteArrayOf(checksum(body))
    }

    fun chunk(frame: ByteArray): List<ByteArray> =
        (frame.indices step MAX_CHUNK).map { frame.copyOfRange(it, minOf(it + MAX_CHUNK, frame.size)) }

    fun checksum(body: ByteArray): Byte = body.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }.toByte()

    data class Frame(val cmd: Int, val payload: ByteArray)

    /** Reassembles notification chunks into checksummed frames. */
    class FrameAssembler {
        private var buf = ByteArray(0)
        var badFrames = 0
            private set

        fun feed(data: ByteArray): List<Frame> {
            buf += data
            val out = mutableListOf<Frame>()
            while (buf.size >= 2) {
                val total = (buf[1].toInt() and 0xFF) + 3
                if (buf.size < total) break
                val f = buf.copyOfRange(0, total)
                buf = buf.copyOfRange(total, buf.size)
                val body = f.copyOfRange(2, total - 1)
                if (body.size < 4 || checksum(body) != f[total - 1]) {
                    badFrames++
                    continue
                }
                out += Frame(body[3].toInt() and 0xFF, body.copyOfRange(4, body.size))
            }
            return out
        }

        fun reset() { buf = ByteArray(0) }
    }

    // --- Authentication --------------------------------------------------------------------

    data class AuthChallenge(val r2: ByteArray, val r1Encrypted: ByteArray, val seed: ByteArray)

    fun parseAuthChallenge(payload: ByteArray): AuthChallenge? {
        if (payload.size < 52) return null
        return AuthChallenge(
            payload.copyOfRange(0, 16),
            payload.copyOfRange(16, 36),
            payload.copyOfRange(36, 52)
        )
    }

    /** Session key for scales whose master key is known; the first 16 bytes are used. */
    fun deriveKey(seed: ByteArray, masterKey: ByteArray): ByteArray = xxteaEncrypt(seed, masterKey).copyOf(16)

    /** Returns the FC payload, or null if the scale's R1' doesn't decrypt to [r1] (wrong key). */
    fun answerChallenge(challenge: AuthChallenge, r1: ByteArray, key: ByteArray): ByteArray? {
        val back = xxteaDecrypt(challenge.r1Encrypted, key) ?: return null
        if (!back.contentEquals(r1)) return null
        return xxteaEncrypt(challenge.r2, key)
    }

    fun parseHexKey(hex: String): ByteArray? {
        val h = hex.trim().lowercase()
        if (h.length != 32 || h.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // --- XXTEA as used by iHealth (XXTEA2): little-endian words, encrypt appends the plaintext
    // length as an extra word and decrypt strips it; keys are padded/truncated to 16 bytes. ---

    private const val DELTA = -0x61c88647 // 0x9E3779B9

    fun xxteaEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val v = toInts(data, appendLength = true)
        val k = toInts(key.copyOf(16), appendLength = false)
        val n = v.size - 1
        var rounds = 6 + 52 / (n + 1)
        var sum = 0
        var z = v[n]
        while (rounds-- > 0) {
            sum += DELTA
            val e = (sum ushr 2) and 3
            for (p in 0 until n) {
                val y = v[p + 1]
                v[p] += mx(sum, y, z, p, e, k)
                z = v[p]
            }
            val y = v[0]
            v[n] += mx(sum, y, z, n, e, k)
            z = v[n]
        }
        return toBytes(v, stripLength = false)!!
    }

    fun xxteaDecrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.isEmpty() || data.size % 4 != 0) return null
        val v = toInts(data, appendLength = false)
        val k = toInts(key.copyOf(16), appendLength = false)
        val n = v.size - 1
        if (n < 1) return null
        val rounds = 6 + 52 / (n + 1)
        var sum = rounds * DELTA
        var y = v[0]
        while (sum != 0) {
            val e = (sum ushr 2) and 3
            for (p in n downTo 1) {
                val z = v[p - 1]
                v[p] -= mx(sum, y, z, p, e, k)
                y = v[p]
            }
            val z = v[n]
            v[0] -= mx(sum, y, z, 0, e, k)
            y = v[0]
            sum -= DELTA
        }
        return toBytes(v, stripLength = true)
    }

    private fun mx(sum: Int, y: Int, z: Int, p: Int, e: Int, k: IntArray): Int =
        ((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4)) xor ((sum xor y) + (k[(p and 3) xor e] xor z))

    private fun toInts(b: ByteArray, appendLength: Boolean): IntArray {
        val n = (b.size + 3) / 4
        val out = IntArray(if (appendLength) n + 1 else n)
        for (i in b.indices) out[i ushr 2] = out[i ushr 2] or ((b[i].toInt() and 0xFF) shl ((i and 3) shl 3))
        if (appendLength) out[n] = b.size
        return out
    }

    private fun toBytes(v: IntArray, stripLength: Boolean): ByteArray? {
        var len = v.size shl 2
        if (stripLength) {
            val m = v[v.size - 1]
            val limit = len - 4
            if (m < limit - 3 || m > limit) return null
            len = m
        }
        return ByteArray(len) { (v[it ushr 2] ushr ((it and 3) shl 3)).toByte() }
    }

    // --- Commands ----------------------------------------------------------------------------

    /** 16-byte scale user id for an openScale user. */
    fun userIdFor(appUserId: Int): ByteArray = "openScale%07d".format(appUserId).toByteArray(Charsets.US_ASCII)

    fun buildUserRecord(
        variant: Variant,
        userId: ByteArray,
        epochSeconds: Long,
        weightKg: Float,
        male: Boolean,
        age: Int,
        heightCm: Int,
        athlete: Boolean = false,
    ): ByteArray {
        val w = Math.round(weightKg * 100).coerceIn(0, 0xFFFF)
        val base = userId.copyOf(16) + be32(epochSeconds) + byteArrayOf(
            (w shr 8).toByte(), w.toByte(),
            if (male) 1 else 0,
            age.coerceIn(0, 255).toByte(),
            heightCm.coerceIn(0, 255).toByte(),
            1,                           // measure impedance / body fat
            if (athlete) 1 else 0,
        )
        return if (variant.userRecordHasHrFlag) base + byteArrayOf(0) else base
    }

    fun offlineDataRequest(userId: ByteArray, alreadyRead: Int): ByteArray =
        userId.copyOf(16) + byteArrayOf((alreadyRead shr 8).toByte(), alreadyRead.toByte())

    fun be32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    // --- Records -----------------------------------------------------------------------------

    data class Record(
        val weightKg: Float,
        val impedances: List<Int>,
        val gender: Int,
        val age: Int,
        val heightCm: Int,
        val epochSeconds: Long,
        val timeValid: Boolean,
        val hasBodyComposition: Boolean,
        val fatPercent: Float? = null,
        val muscleKg: Float? = null,
        val boneKg: Float? = null,
        val waterPercent: Float? = null,
        val proteinPercent: Float? = null,
        val visceralFat: Float? = null,
    )

    /** Parses a record without its status byte (as stored offline). */
    fun parseRecord(variant: Variant, r: ByteArray): Record? {
        if (r.size < variant.recordSize) return null
        val bodyFat = r[0].toInt() == 1
        val imp = variant.impedanceCount
        val after = 3 + imp * 2              // user num, gender, age, height follow the impedances
        val time = after + 4
        var o = time + 6                     // time(4) · time valid · athlete
        if (variant.hasBodyTypeByte) o++
        val rec = Record(
            weightKg = u16(r, 1) / 100f,
            impedances = (0 until imp).map { u16(r, 3 + it * 2) },
            gender = r[after + 1].toInt() and 0xFF,
            age = r[after + 2].toInt() and 0xFF,
            heightCm = r[after + 3].toInt() and 0xFF,
            epochSeconds = u32(r, time),
            timeValid = r[time + 4].toInt() == 1,
            hasBodyComposition = bodyFat,
        )
        if (!bodyFat) return rec
        return rec.copy(
            fatPercent = u16(r, o) / 10f,
            muscleKg = u16(r, o + 2) / 10f,
            boneKg = (r[o + 4].toInt() and 0xFF) / 10f,
            waterPercent = u16(r, o + 5) / 10f,
            proteinPercent = u16(r, o + 7) / 10f,
            // The HS2S family reports visceral fat; the WHSCL1 tail is an encrypted impedance instead.
            visceralFat = if (variant.hasBodyTypeByte) null else u16(r, o + 13) / 10f,
        )
    }

    /** Parses a live result (cmd 0x42): status byte + record. */
    fun parseResult(variant: Variant, payload: ByteArray): Record? {
        if (payload.isEmpty() || payload[0].toInt() != 0) return null
        return parseRecord(variant, payload.copyOfRange(1, payload.size))
    }

    data class OfflineBatch(val records: List<Record>, val moreAvailable: Boolean)

    /** Parses an offline-data reply (cmd 0x31): 3-byte header (count at [2]), records, more-data flag. */
    fun parseOfflineData(variant: Variant, payload: ByteArray): OfflineBatch {
        if (payload.size < 4) return OfflineBatch(emptyList(), false)
        val count = payload[2].toInt() and 0xFF
        val records = (0 until count).mapNotNull {
            val start = 3 + it * variant.recordSize
            val end = start + variant.recordSize
            if (end > payload.size - 1) null else parseRecord(variant, payload.copyOfRange(start, end))
        }
        return OfflineBatch(records, payload.last().toInt() == 1)
    }

    /** Visceral fat grade and BMR the way the Wyze app derives them (Wyze 2.31, `rg.e`). */
    fun wyzeVisceralFat(weightKg: Float, fatPercent: Float, heightCm: Float): Float {
        val lbm = weightKg * (100f - fatPercent) / 100f
        val h = heightCm / 100f
        val bmi = weightKg / (h * h)
        return Math.round(lbm * 0.142f - 11.218f + bmi * 0.433f).toFloat().coerceIn(1f, 59f)
    }

    fun wyzeBmr(weightKg: Float, fatPercent: Float): Float = 370f + 21.6f * weightKg * (100f - fatPercent) / 100f

    // --- Helpers -----------------------------------------------------------------------------

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()
    private fun beLong(b: ByteArray, o: Int): Long = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[o + i].toLong() and 0xFF) }
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
