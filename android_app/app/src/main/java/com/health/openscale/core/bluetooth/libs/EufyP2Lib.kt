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
 * Ported from ble-scale-sync (GPL-3.0, © Kristián Partl): src/scales/eufy-p2.ts,
 * upstream commit (#... Eufy P2/P2 Pro). That adapter was itself ported from
 * bdr99/eufylife-ble-client (MIT, © bdr99). Both credited here per GPL/MIT terms.
 */
package com.health.openscale.core.bluetooth.libs

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A weight (+ optional impedance) decoded from an Eufy P2 frame. */
data class EufyReading(val weightKg: Float, val impedanceOhm: Int)

/**
 * Pure protocol helpers for the Eufy Smart Scale P2 (T9148) / P2 Pro (T9149).
 *
 * The scale uses service 0xFFF0 with FFF1 (write), FFF2 (weight notify) and FFF4 (auth notify).
 * Before it streams weight on FFF2, the client completes a C0/C1/C2/C3 handshake on FFF1→FFF4:
 *
 *   key = MD5(MAC without separators, uppercase ASCII)
 *   iv  = "0000000000000000" (16 ASCII '0' bytes, NOT 16 zero bytes)
 *   C0 client→scale: AES-128-CBC(random 15-char UUID), base64→hex, sub-contracted
 *   C1 scale→client: AES(device UUID); reassemble, base64-decode, AES-decrypt
 *   C2 client→scale: AES("{clientUuid}_{deviceUuid}")
 *   C3 scale→client: `C3 01 00 01 <status> <XOR>`, status 0 = authenticated
 *
 * All functions here are pure and unit-tested (see EufyP2LibTest); the BLE wiring lives in
 * [com.health.openscale.core.bluetooth.scales.EufyP2Handler].
 */
object EufyP2Lib {

    private val AES_IV = "0000000000000000".toByteArray(Charsets.US_ASCII) // 16 × 0x30
    private const val MIN_WEIGHT_KG = 2f
    private const val MAX_WEIGHT_KG = 200f

    /** Company ID in the Eufy P2/P2 Pro advertisement manufacturer data. */
    const val EUFY_COMPANY_ID = 0xff48

    // ── crypto ────────────────────────────────────────────────────────────────

    /** AES key = MD5 of the MAC (separators stripped, uppercased, ASCII). Throws on a bad MAC. */
    fun keyFromMac(mac: String): ByteArray {
        val clean = mac.replace(":", "").replace("-", "").uppercase()
        require(Regex("^[0-9A-F]{12}$").matches(clean)) { "Eufy: invalid MAC \"$mac\" (need 6 hex octets)" }
        return MessageDigest.getInstance("MD5").digest(clean.toByteArray(Charsets.UTF_8))
    }

    private fun cbc(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
        }

    /** AES-128-CBC encrypt → base64 → hex of the base64 ASCII string (matches the TS path). */
    fun encryptToHex(plaintext: String, key: ByteArray): String {
        val enc = cbc(Cipher.ENCRYPT_MODE, key).doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val base64 = Base64.getEncoder().encodeToString(enc)
        return toHex(base64.toByteArray(Charsets.US_ASCII))
    }

    /** AES-128-CBC decrypt of already-base64-decoded bytes → UTF-8 text. */
    fun decrypt(encrypted: ByteArray, key: ByteArray): String =
        String(cbc(Cipher.DECRYPT_MODE, key).doFinal(encrypted), Charsets.UTF_8)

    // ── sub-contract framing ────────────────────────────────────────────────────

    /** XOR of every byte (matches the Python/TS compute_checksum). */
    fun xorChecksum(bytes: ByteArray): Int {
        var x = 0
        for (b in bytes) x = x xor (b.toInt() and 0xFF)
        return x and 0xFF
    }

    /**
     * Split a hex-of-base64 payload into sub-contract frames (≤15 base64 ASCII = 30 hex chars
     * each): `<prefix> <numSegments> <segIdx> <base64TotalBytes> <payload…> <XOR>`.
     */
    fun buildSubContract(dataHex: String, prefix: Int): List<ByteArray> {
        val origLen = dataHex.length
        val numSegments = maxOf(1, (origLen + 29) / 30)
        val base64Bytes = origLen / 2
        return (0 until numSegments).map { i ->
            val slice = dataHex.substring(i * 30, minOf((i + 1) * 30, origLen))
            val header = byteArrayOf(prefix.toByte(), numSegments.toByte(), i.toByte(), base64Bytes.toByte())
            val body = header + hexToBytes(slice)
            body + byteArrayOf(xorChecksum(body).toByte())
        }
    }

    /** Reassembles segmented notifications for one prefix (e.g. 0xC1). */
    class SegmentReassembler(private val prefix: Int) {
        private var buffer = ByteArray(0)
        private var expectedTotalBytes = 0
        private var nextSegment = 0

        private fun reset() {
            buffer = ByteArray(0); expectedTotalBytes = 0; nextSegment = 0
        }

        /** Feed one frame; returns the full payload once the last segment arrives, else null. */
        fun feed(frame: ByteArray): ByteArray? {
            if (frame.size < 5 || (frame[0].toInt() and 0xFF) != prefix) return null
            val body = frame.copyOfRange(0, frame.size - 1)
            if (xorChecksum(body) != (frame[frame.size - 1].toInt() and 0xFF)) return null

            val numSegments = frame[1].toInt() and 0xFF
            val segIdx = frame[2].toInt() and 0xFF
            val totalBytes = frame[3].toInt() and 0xFF

            if (segIdx == 0) { reset(); expectedTotalBytes = totalBytes }
            if (segIdx != nextSegment) return null

            buffer += frame.copyOfRange(4, frame.size - 1)
            nextSegment += 1

            if (segIdx == numSegments - 1) {
                val out = buffer
                val expected = expectedTotalBytes
                reset()
                return if (out.size == expected) out else null
            }
            return null
        }
    }

    // ── frame parsers ────────────────────────────────────────────────────────────

    /** Parse a 16-byte FFF2 weight notification. Returns null unless it is a final reading. */
    fun parseWeightNotification(d: ByteArray): EufyReading? {
        if (d.size != 16 || (d[0].toInt() and 0xFF) != 0xCF || (d[2].toInt() and 0xFF) != 0x00) return null
        val weight = (((d[7].toInt() and 0xFF) shl 8) or (d[6].toInt() and 0xFF)) / 100f
        if (weight < MIN_WEIGHT_KG || weight > MAX_WEIGHT_KG) return null
        if ((d[12].toInt() and 0xFF) != 0x00) return null // not final
        val impedance = ((d[10].toInt() and 0xFF) shl 16) or
            ((d[9].toInt() and 0xFF) shl 8) or (d[8].toInt() and 0xFF)
        return EufyReading(weight, impedance)
    }

    /** Parse a 19-byte advertisement vendor payload. Returns null unless it is a final reading. */
    fun parseAdvertisement(v: ByteArray): EufyReading? {
        if (v.size < 19 || (v[6].toInt() and 0xFF) != 0xCF) return null
        if ((v[15].toInt() and 0xFF) != 0x00) return null // not final
        val weight = (((v[10].toInt() and 0xFF) shl 8) or (v[9].toInt() and 0xFF)) / 100f
        if (weight < MIN_WEIGHT_KG || weight > MAX_WEIGHT_KG) return null
        return EufyReading(weight, 0)
    }

    // ── hex helpers ──────────────────────────────────────────────────────────────

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Stateful C0/C1/C2/C3 authentication helper. One instance per connection.
 *
 * @param clientUuid optional fixed 15-char client UUID (tests inject it; production generates one).
 */
class EufyAuthHandler(mac: String, clientUuid: String? = null) {
    val key: ByteArray = EufyP2Lib.keyFromMac(mac)
    val clientUuid: String = clientUuid
        ?: java.util.UUID.randomUUID().toString().replace("-", "").take(15)

    private val c1Reassembler = EufyP2Lib.SegmentReassembler(0xc1)
    var deviceUuid: String? = null
        private set
    var isAuthenticated: Boolean = false
        private set

    /** Build the C0 handshake frames (client → scale on FFF1). */
    fun buildC0(): List<ByteArray> =
        EufyP2Lib.buildSubContract(EufyP2Lib.encryptToHex(clientUuid, key), 0xc0)

    /** Feed a C1 notification; returns true once the full device UUID is assembled. */
    fun handleC1(frame: ByteArray): Boolean {
        val payload = c1Reassembler.feed(frame) ?: return false
        val encrypted = Base64.getDecoder().decode(String(payload, Charsets.US_ASCII))
        deviceUuid = EufyP2Lib.decrypt(encrypted, key)
        return true
    }

    /** Build the C2 frames after C1 (client → scale on FFF1). Throws if C1 not yet received. */
    fun buildC2(): List<ByteArray> {
        val dev = deviceUuid ?: throw IllegalStateException("Eufy: buildC2 called before C1 received")
        return EufyP2Lib.buildSubContract(EufyP2Lib.encryptToHex("${clientUuid}_$dev", key), 0xc2)
    }

    /** Feed a C3 notification; returns true when a result byte is present (status 0 = success). */
    fun handleC3(frame: ByteArray): Boolean {
        if (frame.size < 5 || (frame[0].toInt() and 0xFF) != 0xc3) return false
        isAuthenticated = (frame[4].toInt() and 0xFF) == 0
        return true
    }
}
