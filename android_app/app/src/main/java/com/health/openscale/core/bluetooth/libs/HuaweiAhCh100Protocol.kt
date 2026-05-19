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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Wire-protocol primitives for the Huawei AH100 / CH100 body-fat scale.
 *
 * This is a faithful Kotlin port of the v2.5.4 Java handler that was the
 * last known-good implementation (see openScale issues #1206, #1276, #1280
 * for the regression history in the 3.x rewrite). All bit-fiddling lives
 * here and is exercised by [HuaweiAhCh100ProtocolTest], so the
 * `HuaweiAH100Handler` / `HuaweiCH100Handler` classes can stay focused on
 * the BLE state machine.
 *
 * Protocol summary:
 * - Service 0xFAA0; write TX 0xFAA1; notify RX 0xFAA2.
 * - Every payload is XOR-obfuscated with the scale's BLE MAC (repeating
 *   6-byte key); see [obfuscate]. This is symmetric.
 * - Some commands and all measurement frames are additionally AES-CTR
 *   encrypted with a session-derived [deriveMagicKey] and the fixed
 *   [INITIAL_IV]. AES is also symmetric in CTR mode.
 * - Frames host->scale: `[start, lengthByte, cmd, ...obfuscated payload...]`
 *   - start = [FRAME_PLAIN] (0xDB) for non-encrypted commands. v2.5.4 wrote
 *     `lengthByte = payload.size + 1`. The 3.x rewrite changed this to
 *     `payload.size`, which appears to be one of the regressions.
 *   - start = [FRAME_ENCRYPTED] (0xDC) for encrypted commands (only USER_INFO
 *     in practice). v2.5.4 wrote `lengthByte = payload.size`.
 * - Frames scale->host: `[start, lengthByte, op, ...obfuscated tail...]`
 *   - start = [FRAME_NOTIFY_PLAIN] (0xBD) for plain notifications.
 *   - start = [FRAME_NOTIFY_ENCRYPTED] (0xBC) for both halves of an
 *     encrypted measurement / history record. The first half carries op
 *     0x0E (live) or 0x10 (history); the second half carries 0x8E / 0x90.
 *
 * Measurement decoding:
 * - **Decrypt only the first half's deobfuscated tail** (this is what
 *   v2.5.4 does and what the captures confirm). The keystream of AES-CTR
 *   is contiguous so decrypting the merged buffer would give the same
 *   bytes for the first 16, but the second half then contains junk; we
 *   keep things simple and decrypt just what we need.
 * - The decrypted layout is documented on [parseMeasurement].
 *
 * @see HuaweiAhCh100ProtocolTest
 */
object HuaweiAhCh100Protocol {

    // ---------- Hard-coded keys / IV (v2.5.4) ------------------------------
    //
    // ByteArray is mutable so we keep the canonical bytes in private backing
    // fields and hand out fresh copies. Internal call sites work on the copy
    // they receive; mis-use by tests / future contributors cannot corrupt
    // the values for subsequent calls.

    private val INITIAL_KEY_BYTES: ByteArray =
        hexToBytes("3D A2 78 4A FB 87 B1 2A 98 0F DE 34 56 73 21 56")

    private val INITIAL_IV_BYTES: ByteArray =
        hexToBytes("4E F7 64 32 2F DA 76 32 12 3D EB 87 90 FE A2 19")

    /**
     * AES-128 key fragment. Returns a fresh copy on every access:
     * bytes [0..6] of `magicKey` come from the session (obfuscated auth
     * token); bytes [7..15] are the tail of this constant.
     */
    val INITIAL_KEY: ByteArray get() = INITIAL_KEY_BYTES.copyOf()

    /** AES-CTR IV. Returns a fresh copy on every access. */
    val INITIAL_IV: ByteArray get() = INITIAL_IV_BYTES.copyOf()

    // ---------- Frame start bytes -----------------------------------------

    /** Host->scale plain frame start (XOR-obfuscated payload only). */
    const val FRAME_PLAIN: Byte = 0xDB.toByte()

    /** Host->scale AES-encrypted frame start (USER_INFO etc). */
    const val FRAME_ENCRYPTED: Byte = 0xDC.toByte()

    /** Scale->host: first byte for the two halves of an encrypted measurement. */
    const val FRAME_NOTIFY_ENCRYPTED: Byte = 0xBC.toByte()

    /** Scale->host: first byte for plain notifications. */
    const val FRAME_NOTIFY_PLAIN: Byte = 0xBD.toByte()

    // ---------- Notification opcodes (data[2] from the scale) -------------

    const val NTFY_WAKEUP: Byte = 0x00
    const val NTFY_GO_SLEEP: Byte = 0x01
    const val NTFY_UNITS_SET: Byte = 0x02
    const val NTFY_SCALE_CLOCK: Byte = 0x08
    const val NTFY_SCALE_VERSION: Byte = 0x0C
    const val NTFY_MEASUREMENT: Byte = 0x0E
    const val NTFY_MEASUREMENT_WEIGHT: Byte = 0x0F
    const val NTFY_MEASUREMENT2: Byte = 0x8E.toByte()
    const val NTFY_HISTORY_RECORD: Byte = 0x10
    const val NTFY_HISTORY_RECORD2: Byte = 0x90.toByte()
    const val NTFY_HISTORY_UPLOAD_DONE: Byte = 0x19
    const val NTFY_USER_CHANGED: Byte = 0x20
    const val NTFY_AUTH_RESULT: Byte = 0x26
    const val NTFY_BIND_OK: Byte = 0x27

    // ---------- Command opcodes (cmd byte we send) ------------------------

    const val CMD_SET_UNIT: Byte = 2
    const val CMD_SET_SCALE_CLOCK: Byte = 8
    const val CMD_USER_INFO: Byte = 9
    const val CMD_GET_RECORD: Byte = 11
    const val CMD_GET_VERSION: Byte = 12
    const val CMD_FAT_RESULT_ACK: Byte = 19
    const val CMD_HEARTBEAT: Byte = 32
    const val CMD_AUTH: Byte = 36
    const val CMD_BIND_USER: Byte = 37

    // ---------- Primitives -----------------------------------------------

    /**
     * XOR every byte of [data] with the scale's MAC bytes (repeating).
     *
     * Self-inverse: `obfuscate(obfuscate(x, mac), mac) == x`.
     *
     * @param mac 6-byte BLE address in display order (i.e. for "AA:BB:CC:DD:EE:FF",
     *   the bytes [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]).
     */
    fun obfuscate(data: ByteArray, mac: ByteArray): ByteArray {
        if (mac.isEmpty()) return data.copyOf()
        val out = data.copyOf()
        var m = 0
        for (i in out.indices) {
            if (m >= mac.size) m = 0
            out[i] = (out[i].toInt() xor (mac[m].toInt() and 0xFF)).toByte()
            m++
        }
        return out
    }

    /** AES/CTR/NoPadding. Symmetric: encrypt is the same operation as decrypt. */
    fun aesCtr(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val iv16 = ByteArray(16).apply { System.arraycopy(iv, 0, this, 0, min(16, iv.size)) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
        return cipher.doFinal(data)
    }

    /** XOR checksum of [buf]'s bytes — used by AUTH token construction. */
    fun xorChecksum(buf: ByteArray, off: Int = 0, len: Int = buf.size): Byte {
        var x = 0
        for (i in off until (off + len)) x = x xor (buf[i].toInt() and 0xFF)
        return (x and 0xFF).toByte()
    }

    /**
     * Build the 7-byte AUTH token used in the handshake:
     *   `[0x11, 0x22, 0x33, 0x44, 0x55, chk, userId]`
     * where `chk` is chosen so the whole array XORs to zero.
     */
    fun buildAuthToken(userId: Int): ByteArray {
        val auth = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x00, (userId and 0xFF).toByte())
        auth[5] = xorChecksum(auth)
        return auth
    }

    /**
     * Derive the AES-128 magicKey for the session.
     *
     * v2.5.4 layout: `obfuscate(authCode, mac) || INITIAL_KEY[7..15]`.
     * Result is always 16 bytes (7 + 9).
     */
    fun deriveMagicKey(authCode: ByteArray, mac: ByteArray): ByteArray {
        require(authCode.size == 7) { "authCode must be 7 bytes; got ${authCode.size}" }
        val obfAuth = obfuscate(authCode, mac)
        val tail = INITIAL_KEY_BYTES.copyOfRange(7, INITIAL_KEY_BYTES.size)
        return obfAuth + tail
    }

    // ---------- Frame writers --------------------------------------------

    /**
     * Build a host->scale plain command frame (start byte [FRAME_PLAIN]).
     *
     * IMPORTANT: matches v2.5.4's `lengthByte = payload.size + 1`. The 3.x
     * rewrite used `payload.size` which appears to break the AUTH handshake
     * on at least some firmware revisions and is one of the regressions
     * tracked by issues #1206 / #1280.
     *
     * @param explicitLen overrides the length byte (legacy callers used
     *   this for `GET_RECORD` to encode 0x06 even though payload was 7 bytes).
     */
    fun buildPlainCommand(
        cmd: Byte,
        payload: ByteArray,
        mac: ByteArray,
        explicitLen: Int? = null
    ): ByteArray {
        val len = explicitLen ?: (payload.size + 1)
        val header = byteArrayOf(FRAME_PLAIN, len.toByte(), cmd)
        return header + obfuscate(payload, mac)
    }

    /**
     * Build a host->scale AES-encrypted command frame (start byte
     * [FRAME_ENCRYPTED]).
     *
     * Length byte equals plaintext payload size (matches v2.5.4's
     * `lengthByte = payload.size + 0`).
     */
    fun buildEncryptedCommand(
        cmd: Byte,
        payload: ByteArray,
        magicKey: ByteArray,
        mac: ByteArray,
        iv: ByteArray = INITIAL_IV
    ): ByteArray {
        val encrypted = aesCtr(payload, magicKey, iv)
        val header = byteArrayOf(FRAME_ENCRYPTED, payload.size.toByte(), cmd)
        return header + obfuscate(encrypted, mac)
    }

    // ---------- Notification helpers -------------------------------------

    /**
     * Strip the 3-byte header and de-obfuscate the tail of a scale->host frame.
     * Returns the bytes that, for plain notifications, are the actual
     * payload, or, for the encrypted halves, the bytes that need to be
     * fed through [aesCtr] before parsing.
     */
    fun deobfuscateTail(frame: ByteArray, mac: ByteArray): ByteArray {
        if (frame.size <= 3) return ByteArray(0)
        return obfuscate(frame.copyOfRange(3, frame.size), mac)
    }

    // ---------- Measurement decoding -------------------------------------

    data class Measurement(
        val userId: Int,
        val weightKg: Float,
        val fatPct: Float,
        val impedanceOhm: Int,
        val dateTime: Date?,
        val rawDecrypted: ByteArray
    )

    /**
     * Parse the v2.5.4 byte layout from already-decrypted measurement bytes.
     *
     * Layout (little-endian unless noted):
     * ```
     * [0]      userId (1..10)
     * [1..2]   weight in tenth-kg (uint16 LE)
     * [3..4]   fat in tenth-percent (uint16 LE)
     * [5..6]   year (uint16 LE)
     * [7]      month, 1..12
     * [8]      day
     * [9]      hour
     * [10]     minute
     * [11]     second
     * [12]     weekday (informational, ignored)
     * [13..14] resistance / impedance in ohm (uint16 LE)
     * ```
     */
    fun parseMeasurement(decrypted: ByteArray): Measurement {
        require(decrypted.size >= 15) {
            "decrypted frame must be at least 15 bytes; got ${decrypted.size}"
        }
        val userId = decrypted[0].toInt() and 0xFF
        val weightTenth = u16le(decrypted, 1)
        val fatTenth = u16le(decrypted, 3)
        val year = u16le(decrypted, 5)
        val month = (decrypted[7].toInt() and 0xFF).coerceIn(1, 12)
        val day = decrypted[8].toInt() and 0xFF
        val hour = decrypted[9].toInt() and 0xFF
        val minute = decrypted[10].toInt() and 0xFF
        val second = decrypted[11].toInt() and 0xFF
        val impedance = u16le(decrypted, 13)

        val date: Date? = try {
            val cal = Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day, hour, minute, second)
            }
            cal.time
        } catch (_: Throwable) {
            null
        }

        return Measurement(
            userId = userId,
            weightKg = weightTenth / 10f,
            fatPct = fatTenth / 10f,
            impedanceOhm = impedance,
            dateTime = date,
            rawDecrypted = decrypted.copyOf()
        )
    }

    /**
     * Convenience: take the *first half* of a two-part encrypted measurement
     * exactly as it arrived from the scale (3-byte header + obfuscated tail),
     * de-obfuscate, AES-CTR-decrypt with [magicKey] / [INITIAL_IV], and
     * parse via [parseMeasurement]. The second half is intentionally not
     * required — v2.5.4 ignored it and that captures-as-tested produces the
     * correct numbers.
     */
    fun decodeFirstHalf(firstHalfFrame: ByteArray, magicKey: ByteArray, mac: ByteArray): Measurement {
        val deobf = deobfuscateTail(firstHalfFrame, mac)
        val plain = aesCtr(deobf, magicKey, INITIAL_IV)
        return parseMeasurement(plain)
    }

    // ---------- Internal utils -------------------------------------------

    fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    fun hexToBytes(s: String): ByteArray {
        val clean = s.replace(" ", "").replace(":", "").replace("-", "")
        val even = if (clean.length % 2 == 0) clean else "0$clean"
        return ByteArray(even.length / 2) { i ->
            even.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Convert "AA:BB:CC:DD:EE:FF" (or no separators) to 6 bytes in display order. */
    fun macStringToBytes(mac: String): ByteArray {
        val clean = mac.replace(":", "").replace("-", "")
        require(clean.length == 12) { "MAC must be 12 hex chars; got $mac" }
        return ByteArray(6) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
