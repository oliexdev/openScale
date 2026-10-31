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

import java.math.BigInteger
import java.util.UUID

/**
 * Wire protocol of the Wyze Scale X (WSCALX, advertised as `WL_SC3`), kept free of Android types
 * so it can be unit tested. Reverse engineered by Ben Gruver in
 * [wyze_scale_tool](https://github.com/JesusFreke/wyze_scale_tool); no cloud key is involved.
 *
 * One characteristic carries both directions. The link is set up with a Diffie–Hellman exchange
 * (`g = 5`, `p = 0xFFFFFFC5`, 32-bit keys): the host writes `frame F0 00 08 pubLE32 00000000`, the
 * scale answers `4x F0 00 08 pubLE32 00000000`, and the session key is the 8 lowercase hex digits
 * of the shared secret, zero padded to 16 bytes.
 *
 * Everything after that is an envelope `hdr 01 00 len` followed by the message, zero padded to a
 * multiple of 8 and XXTEA encrypted 8 bytes at a time; `hdr` is `1x` from the host (x = a 4-bit
 * frame counter the host also used for the key exchange) and `5x` from the scale, `len` the
 * unpadded message length.
 *
 * Messages are `16 00 lenLE16 cmd A8 payload` from the host and `22 01 lenLE16 cmd A8 payload`
 * from the scale, `len` counting from `cmd`. Status replies carry one byte, 0 = success.
 */
object WyzeScaleXLib {

    val SERVICE: UUID = UUID.fromString("0000fd7b-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    const val ADVERTISED_NAME = "WL_SC3"
    const val MANUFACTURER_ID = 0x0870

    const val CMD_SYNC_TIME = 0x01
    const val CMD_SET_UNIT = 0x04
    const val CMD_CUR_WEIGHT_DATA = 0x08
    const val CMD_HISTORY_WEIGHT_DATA = 0x09
    const val CMD_UPDATE_USER = 0x0A
    const val CMD_USER_LIST_NEW = 0x0D
    const val CMD_CURRENT_USER_NEW = 0x0E

    const val USER_RECORD_SIZE = 25
    private const val METRICS_SIZE = 21
    private const val LIVE_PAYLOAD_SIZE = 2 + 16 + 6 + METRICS_SIZE
    private const val HISTORY_PAYLOAD_SIZE = 1 + 4 + 16 + 5 + METRICS_SIZE
    private const val MEASURE_STATE_FINAL = 2

    private val DH_BASE = BigInteger.valueOf(5)
    private val DH_MODULUS = BigInteger.valueOf(0xFFFFFFC5L)
    private val KEY_EXCHANGE_TAG = byteArrayOf(0xF0.toByte(), 0x00, 0x08)

    // --- Key exchange ----------------------------------------------------------------------

    fun publicKey(privateKey: Long): Long = DH_BASE.modPow(BigInteger.valueOf(privateKey), DH_MODULUS).toLong()

    fun sessionKey(peerPublicKey: Long, privateKey: Long): ByteArray {
        val shared = BigInteger.valueOf(peerPublicKey).modPow(BigInteger.valueOf(privateKey), DH_MODULUS).toLong()
        return "%08x".format(shared).toByteArray(Charsets.US_ASCII).copyOf(16)
    }

    fun keyExchangeFrame(frame: Int, publicKey: Long): ByteArray =
        byteArrayOf(frame.toByte()) + KEY_EXCHANGE_TAG + le32(publicKey) + ByteArray(4)

    fun isKeyExchangeReply(buf: ByteArray): Boolean =
        buf.size == 12 && (buf[0].toInt() and 0xF0) == 0x40 && buf.copyOfRange(1, 4).contentEquals(KEY_EXCHANGE_TAG)

    fun parseKeyExchangeReply(buf: ByteArray): Long? = if (isKeyExchangeReply(buf)) u32(buf, 4) else null

    // --- Envelope ----------------------------------------------------------------------------

    fun isEncrypted(buf: ByteArray): Boolean =
        buf.size >= 4 && (buf[0].toInt() and 0xF0) == 0x50 && buf[1].toInt() == 0x01

    fun seal(frame: Int, key: ByteArray, message: ByteArray): ByteArray {
        val padded = message.copyOf((message.size + 7) / 8 * 8)
        val body = ByteArray(padded.size)
        for (o in padded.indices step 8) {
            encryptBlock(padded.copyOfRange(o, o + 8), key).copyInto(body, o)
        }
        return byteArrayOf((0x10 + (frame and 0x0F)).toByte(), 0x01, 0x00, message.size.toByte()) + body
    }

    /** The decrypted message, or null when the envelope is malformed. */
    fun open(key: ByteArray, buf: ByteArray): ByteArray? {
        if (!isEncrypted(buf)) return null
        val len = buf[3].toInt() and 0xFF
        val body = buf.copyOfRange(4, buf.size)
        if (body.isEmpty() || body.size % 8 != 0 || len > body.size) return null
        val plain = ByteArray(body.size)
        for (o in body.indices step 8) {
            decryptBlock(body.copyOfRange(o, o + 8), key).copyInto(plain, o)
        }
        return plain.copyOf(len)
    }

    fun encryptBlock(block: ByteArray, key: ByteArray): ByteArray {
        require(block.size == 8) { "XXTEA block must be 8 bytes" }
        val v = toWords(block)
        val k = toWords(key.copyOf(16))
        var rounds = 6 + 52 / 2
        var sum = 0
        var z = v[1]
        while (rounds-- > 0) {
            sum += DELTA
            val e = (sum ushr 2) and 3
            v[0] += mx(sum, v[1], z, 0, e, k)
            z = v[0]
            v[1] += mx(sum, v[0], z, 1, e, k)
            z = v[1]
        }
        return toBytes(v)
    }

    fun decryptBlock(block: ByteArray, key: ByteArray): ByteArray {
        require(block.size == 8) { "XXTEA block must be 8 bytes" }
        val v = toWords(block)
        val k = toWords(key.copyOf(16))
        val rounds = 6 + 52 / 2
        var sum = rounds * DELTA
        var y = v[0]
        while (sum != 0) {
            val e = (sum ushr 2) and 3
            v[1] -= mx(sum, y, v[0], 1, e, k)
            y = v[1]
            v[0] -= mx(sum, y, v[1], 0, e, k)
            y = v[0]
            sum -= DELTA
        }
        return toBytes(v)
    }

    // --- XXTEA over one 8-byte block: little-endian words, 16-byte key, no length word. ---

    private const val DELTA = -0x61c88647 // 0x9E3779B9

    private fun mx(sum: Int, y: Int, z: Int, p: Int, e: Int, k: IntArray): Int =
        ((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4)) xor ((sum xor y) + (k[(p and 3) xor e] xor z))

    private fun toWords(b: ByteArray): IntArray {
        val out = IntArray((b.size + 3) / 4)
        for (i in b.indices) out[i ushr 2] = out[i ushr 2] or ((b[i].toInt() and 0xFF) shl ((i and 3) shl 3))
        return out
    }

    private fun toBytes(v: IntArray): ByteArray =
        ByteArray(v.size shl 2) { (v[it ushr 2] ushr ((it and 3) shl 3)).toByte() }

    // --- Host messages -----------------------------------------------------------------------

    fun command(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 2 + payload.size
        return byteArrayOf(0x16, 0x00, len.toByte(), (len shr 8).toByte(), cmd.toByte(), 0xA8.toByte()) + payload
    }

    fun syncTime(epochSeconds: Long): ByteArray = command(CMD_SYNC_TIME, le32(epochSeconds) + byteArrayOf(1))

    fun setUnit(pounds: Boolean): ByteArray = command(CMD_SET_UNIT, byteArrayOf(if (pounds) 1 else 0))

    fun userList(): ByteArray = command(CMD_USER_LIST_NEW)

    fun selectUser(user: UserRecord): ByteArray = command(CMD_CURRENT_USER_NEW, user.encode())

    fun updateUser(user: UserRecord): ByteArray = command(CMD_UPDATE_USER, user.encode())

    /** Acknowledges a history record; the scale then deletes it and sends the next one. */
    fun historyAck(): ByteArray = command(CMD_HISTORY_WEIGHT_DATA, byteArrayOf(0))

    /** 16-byte scale user id for an openScale user. */
    fun userIdFor(appUserId: Int): ByteArray = "openScale%07d".format(appUserId).toByteArray(Charsets.US_ASCII)

    /** The 25-byte profile the scale stores per user. */
    data class UserRecord(
        val userId: ByteArray,
        val weightKg: Float,
        val male: Boolean,
        val age: Int,
        val heightCm: Int,
        val athlete: Boolean = false,
        val weightOnly: Boolean = false,
        val lastImpedanceOhm: Float = 0f,
    ) {
        fun encode(): ByteArray {
            val w = Math.round(weightKg * 100).coerceIn(0, 0xFFFF)
            val imp = Math.round(lastImpedanceOhm * 10).coerceIn(0, 0xFFFF)
            return userId.copyOf(16) + byteArrayOf(
                w.toByte(), (w shr 8).toByte(),
                if (male) 1 else 0,
                age.coerceIn(0, 255).toByte(),
                heightCm.coerceIn(0, 255).toByte(),
                if (athlete) 1 else 0,
                if (weightOnly) 1 else 0,
                imp.toByte(), (imp shr 8).toByte(),
            )
        }

        override fun equals(other: Any?): Boolean = other is UserRecord && encode().contentEquals(other.encode())

        override fun hashCode(): Int = encode().contentHashCode()

        companion object {
            fun parse(b: ByteArray, o: Int = 0): UserRecord? {
                if (b.size < o + USER_RECORD_SIZE) return null
                return UserRecord(
                    userId = b.copyOfRange(o, o + 16),
                    weightKg = u16(b, o + 16) / 100f,
                    male = b[o + 18].toInt() == 1,
                    age = b[o + 19].toInt() and 0xFF,
                    heightCm = b[o + 20].toInt() and 0xFF,
                    athlete = b[o + 21].toInt() == 1,
                    weightOnly = b[o + 22].toInt() == 1,
                    lastImpedanceOhm = u16(b, o + 23) / 10f,
                )
            }
        }
    }

    // --- Scale messages ----------------------------------------------------------------------

    sealed interface Reply {
        val cmd: Int
    }

    data class Status(override val cmd: Int, val code: Int) : Reply {
        val ok: Boolean get() = code == 0
    }

    data class UserList(val users: List<UserRecord>) : Reply {
        override val cmd: Int get() = CMD_USER_LIST_NEW
    }

    data class LiveWeight(
        val batteryPercent: Int,
        val pounds: Boolean,
        val userId: ByteArray,
        val final: Boolean,
        val metrics: Metrics,
    ) : Reply {
        override val cmd: Int get() = CMD_CUR_WEIGHT_DATA
    }

    data class History(val epochSeconds: Long, val userId: ByteArray, val metrics: Metrics) : Reply {
        override val cmd: Int get() = CMD_HISTORY_WEIGHT_DATA
    }

    /**
     * The 21-byte block both weight messages end with. Weight is kg × 100; impedance and the body
     * composition fields are in tenths (verified against the Wyze app on a Scale X); a weight-only
     * reading has impedance 0 and all composition fields 0.
     */
    data class Metrics(
        val weightKg: Float,
        val impedanceOhm: Float,
        val fatPercent: Float,
        val muscleKg: Float,
        val boneKg: Float,
        val waterPercent: Float,
        val proteinPercent: Float,
        val lbmKg: Float,
        val visceralFat: Int,
        val bmrKcal: Int,
        val bodyAge: Int,
        val bmi: Float,
    ) {
        val hasBodyComposition: Boolean get() = impedanceOhm > 0f && fatPercent > 0f

        companion object {
            fun parse(b: ByteArray, o: Int): Metrics = Metrics(
                weightKg = u16(b, o) / 100f,
                impedanceOhm = u16(b, o + 2) / 10f,
                fatPercent = u16(b, o + 4) / 10f,
                muscleKg = u16(b, o + 6) / 10f,
                boneKg = (b[o + 8].toInt() and 0xFF) / 10f,
                waterPercent = u16(b, o + 9) / 10f,
                proteinPercent = u16(b, o + 11) / 10f,
                lbmKg = u16(b, o + 13) / 10f,
                visceralFat = b[o + 15].toInt() and 0xFF,
                bmrKcal = u16(b, o + 16),
                bodyAge = b[o + 18].toInt() and 0xFF,
                bmi = u16(b, o + 19) / 10f,
            )
        }
    }

    /** Parses a decrypted scale message; null when it is not a well-formed reply we know. */
    fun parseReply(m: ByteArray): Reply? {
        if (m.size < 6 || m[0].toInt() != 0x22 || m[1].toInt() != 0x01 || (m[5].toInt() and 0xFF) != 0xA8) return null
        val len = u16(m, 2)
        if (len < 2 || m.size < 4 + len) return null
        val cmd = m[4].toInt() and 0xFF
        val p = m.copyOfRange(6, 4 + len)
        return when (cmd) {
            CMD_USER_LIST_NEW -> {
                if (p.isEmpty() || (p.size - 1) % USER_RECORD_SIZE != 0) return null
                UserList((1 until p.size step USER_RECORD_SIZE).mapNotNull { UserRecord.parse(p, it) })
            }
            CMD_CUR_WEIGHT_DATA -> {
                if (p.size < LIVE_PAYLOAD_SIZE) return null
                LiveWeight(
                    batteryPercent = p[0].toInt() and 0xFF,
                    pounds = p[1].toInt() == 1,
                    userId = p.copyOfRange(2, 18),
                    final = p[23].toInt() == MEASURE_STATE_FINAL,
                    metrics = Metrics.parse(p, 24),
                )
            }
            CMD_HISTORY_WEIGHT_DATA -> {
                if (p.size < HISTORY_PAYLOAD_SIZE) return null
                History(epochSeconds = u32(p, 1), userId = p.copyOfRange(5, 21), metrics = Metrics.parse(p, 26))
            }
            else -> if (p.size == 1) Status(cmd, p[0].toInt() and 0xFF) else null
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
