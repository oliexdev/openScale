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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Round-trip tests for [TuyaBleLib], ported from the reference Python implementation's
 * protocol test suite. Synthetic credentials only — never real device keys.
 *
 * A "device frame" below is a frame the scale would send, produced with the same builder the
 * handler uses for outgoing frames, then re-chunked and fed through [TuyaBleLib.TuyaFrameReassembler]
 * + [TuyaBleLib.parseFrame] exactly as live notifications arrive.
 */
class TuyaBleLibTest {

    private val localKey = "testkey_123456"
    private val deviceId = "testdevice01"
    private val uuid = "0123456789abcdef"
    private val security = TuyaSecurity(localKey)
    private val srand = byteArrayOf(1, 2, 3, 4, 5, 6)
    private val fixedIv = ByteArray(16) { it.toByte() }

    /** Strip chunk headers from buildPackets output, yielding the raw encrypted frame. */
    private fun reassemble(packets: List<ByteArray>): ByteArray {
        val reassembler = TuyaBleLib.TuyaFrameReassembler()
        var frame: ByteArray? = null
        for (packet in packets) {
            reassembler.feed(packet)?.let { frame = it }
        }
        return requireNotNull(frame) { "packets did not reassemble into a frame" }
    }

    /** Build a scale→client frame and deliver it chunk-by-chunk through the reassembler. */
    private fun deviceFrame(
        code: Int,
        data: ByteArray,
        responseTo: Int,
        key: ByteArray,
        flag: Int,
        seq: Int,
        protocolVersion: Int = 3,
    ): TuyaBleFrame {
        val packets = TuyaBleLib.buildPackets(seq, code, data, responseTo, key, flag, protocolVersion, fixedIv)
        assertThat(packets.all { it.size <= TuyaBleLib.GATT_MTU }).isTrue()
        return TuyaBleLib.parseFrame(reassemble(packets), TuyaBleLib.keyLookup(security, key, null))
    }

    private fun deviceInfoPayload(): ByteArray {
        val payload = ByteArray(46)
        payload[0] = 1; payload[1] = 2          // device version 1.2
        payload[2] = 3; payload[3] = 1          // protocol 3.1
        payload[4] = 0                          // flags
        payload[5] = 1                          // bound
        srand.copyInto(payload, 6)
        payload[12] = 1; payload[13] = 0        // hardware 1.0
        // auth key bytes 14..46 stay zero
        return payload
    }

    // ── security material ─────────────────────────────────────────────────────

    @Test
    fun loginKeyIs16BytesAndLegacyFlags() {
        assertThat(security.loginKey).hasLength(16)
        assertThat(security.pairingLoginKey).hasLength(6)
        assertThat(security.protocolV2).isFalse()
        assertThat(security.loginFlag).isEqualTo(4)
        assertThat(security.sessionFlag).isEqualTo(5)
    }

    @Test
    fun secKeySwitchesToProtocolV2Flags() {
        val v2 = TuyaSecurity(localKey, "0123456789abcdef")
        assertThat(v2.protocolV2).isTrue()
        assertThat(v2.loginFlag).isEqualTo(14)
        assertThat(v2.sessionFlag).isEqualTo(15)
        assertThat(v2.loginKey).isNotEqualTo(security.loginKey)
    }

    @Test
    fun sessionKeyDerivationMatchesMaterial() {
        val key = security.sessionKey(srand)
        assertThat(key).hasLength(16)
        assertThat(key).isNotEqualTo(security.loginKey)
    }

    @Test
    fun shortLocalKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { TuyaSecurity("abc") }
    }

    // ── varint + crc ──────────────────────────────────────────────────────────

    @Test
    fun varintGranularity() {
        assertThat(TuyaBleLib.packVarint(0)).isEqualTo(byteArrayOf(0x00))
        assertThat(TuyaBleLib.packVarint(127)).isEqualTo(byteArrayOf(0x7F))
        assertThat(TuyaBleLib.packVarint(128)).isEqualTo(byteArrayOf(0x80.toByte(), 0x01))
        assertThat(TuyaBleLib.unpackVarint(byteArrayOf(0x80.toByte(), 0x01), 0)).isEqualTo(128 to 2)
    }

    @Test
    fun varintTruncationThrows() {
        assertThrows(TuyaBleProtocolException::class.java) {
            TuyaBleLib.unpackVarint(byteArrayOf(0x80.toByte()), 0)
        }
    }

    @Test
    fun crc16KnownVector() {
        // CRC-16/Modbus of "123456789" is 0x4B37
        assertThat(TuyaBleLib.crc16("123456789".toByteArray())).isEqualTo(0x4B37)
    }

    // ── outgoing frame structure ──────────────────────────────────────────────

    @Test
    fun outgoingPacketsChunkAndHeaderCorrectly() {
        val sessionKey = security.sessionKey(srand)
        val packets = TuyaBleLib.buildPackets(9, TuyaBleLib.CODE_DEVICE_STATUS, ByteArray(0), 0, sessionKey, 5, 3, fixedIv)

        var total = -1
        var buffer = ByteArray(0)
        packets.forEachIndexed { i, packet ->
            var (num, pos) = TuyaBleLib.unpackVarint(packet, 0)
            assertThat(num).isEqualTo(i)
            if (num == 0) {
                val (len, afterLen) = TuyaBleLib.unpackVarint(packet, pos)
                total = len
                val version = packet[afterLen].toInt() and 0xFF
                assertThat(version).isEqualTo(0x30) // protocol 3 << 4
                pos = afterLen + 1
            }
            buffer += packet.copyOfRange(pos, packet.size)
        }
        assertThat(buffer.size).isEqualTo(total)
        assertThat(buffer[0].toInt() and 0xFF).isEqualTo(5)

        val frame = TuyaBleLib.parseFrame(buffer) { flag ->
            if (flag == 5) sessionKey else null
        }
        assertThat(frame.seqNum).isEqualTo(9)
        assertThat(frame.responseTo).isEqualTo(0)
        assertThat(frame.code).isEqualTo(TuyaBleLib.CODE_DEVICE_STATUS)
        assertThat(frame.data).isEmpty()
    }

    @Test
    fun crcCorruptionIsDetected() {
        val sessionKey = security.sessionKey(srand)
        val packets = TuyaBleLib.buildPackets(10, TuyaBleLib.CODE_DEVICE_STATUS, ByteArray(0), 0, sessionKey, 5, 3, fixedIv)
        val frame = reassemble(packets).clone()
        // Flip one plaintext byte inside the CBC block by re-encrypting a mutated plaintext:
        // easier — flip a ciphertext byte; CRC will fail after decryption (or padding breaks).
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        assertThrows(TuyaBleProtocolException::class.java) {
            TuyaBleLib.parseFrame(frame) { flag -> if (flag == 5) sessionKey else null }
        }
    }

    @Test
    fun unknownSecurityFlagIsRejected() {
        val frame = ByteArray(32)
        frame[0] = 99
        assertThrows(TuyaBleProtocolException::class.java) {
            TuyaBleLib.parseFrame(frame) { null }
        }
    }

    // ── device info + pair handshake ──────────────────────────────────────────

    @Test
    fun deviceInfoRoundTripDerivesSessionKey() {
        val infoFrame = deviceFrame(
            code = TuyaBleLib.CODE_DEVICE_INFO,
            data = deviceInfoPayload(),
            responseTo = 1,
            key = security.loginKey,
            flag = 4,
            seq = 5,
        )
        assertThat(infoFrame.responseTo).isEqualTo(1)
        assertThat(infoFrame.code).isEqualTo(TuyaBleLib.CODE_DEVICE_INFO)

        val info = TuyaBleLib.parseDeviceInfo(infoFrame.data)
        assertThat(info.deviceVersion).isEqualTo("1.2")
        assertThat(info.protocolVersionString).isEqualTo("3.1")
        assertThat(info.hardwareVersion).isEqualTo("1.0")
        assertThat(info.bound).isTrue()
        assertThat(info.deviceRandom).isEqualTo(srand)

        val sessionKey = security.sessionKey(info.deviceRandom)
        assertThat(sessionKey).isEqualTo(security.sessionKey(srand))
    }

    @Test
    fun pairPayloadIsPaddedTo44Bytes() {
        val payload = TuyaBleLib.buildPairPayload(uuid, security.pairingLoginKey, deviceId)
        assertThat(payload.size).isEqualTo(44)
        assertThat(payload.copyOfRange(0, uuid.length)).isEqualTo(uuid.toByteArray())
        assertThat(payload.copyOfRange(uuid.length, uuid.length + 6))
            .isEqualTo(security.pairingLoginKey)
    }

    @Test
    fun pairAlreadyPairedResponseParses() {
        val sessionKey = security.sessionKey(srand)
        val frame = deviceFrame(
            code = TuyaBleLib.CODE_PAIR,
            data = byteArrayOf(TuyaBleLib.PAIR_ALREADY_PAIRED.toByte()),
            responseTo = 2,
            key = sessionKey,
            flag = 5,
            seq = 6,
        )
        assertThat(frame.code).isEqualTo(TuyaBleLib.CODE_PAIR)
        assertThat(frame.data).isEqualTo(byteArrayOf(0x02))
    }

    @Test
    fun deviceInfoRequestQuirkPayload() {
        assertThat(TuyaBleLib.buildDeviceInfoRequestPayload()).isEqualTo(byteArrayOf(0x00, 0xF3.toByte()))
    }

    // ── datapoints ────────────────────────────────────────────────────────────

    @Test
    fun classicDpFrameParsesWeightRealtimeAndImpedance() {
        val sessionKey = security.sessionKey(srand)
        val dpPayload = byteArrayOf(1, 2, 4) + int32(8960) +
            byteArrayOf(9, 2, 4) + int32(8810) +
            byteArrayOf(7, 2, 2) + int16(851)
        val frame = deviceFrame(
            code = TuyaBleLib.CODE_RECEIVE_DP,
            data = dpPayload,
            responseTo = 0,
            key = sessionKey,
            flag = 5,
            seq = 7,
        )
        val dps = TuyaBleLib.parseDatapoints(frame.data, 0, 1, 0, 1_700_000_000L)
        assertThat(dps).hasSize(3)
        assertThat(dps[0].id).isEqualTo(1)
        assertThat(dps[0].value).isEqualTo(8960)
        assertThat(dps[1].id).isEqualTo(9)
        assertThat(dps[1].value).isEqualTo(8810)
        assertThat(dps[2].id).isEqualTo(7)
        assertThat(dps[2].value).isEqualTo(851)
    }

    @Test
    fun signDpFrameParsesAfterSeqAndFlags() {
        val sessionKey = security.sessionKey(srand)
        val payload = int16(1234) + byteArrayOf(0) +
            byteArrayOf(1, 2, 4) + int32(8755)
        val frame = deviceFrame(
            code = TuyaBleLib.CODE_RECEIVE_SIGN_DP,
            data = payload,
            responseTo = 0,
            key = sessionKey,
            flag = 5,
            seq = 8,
        )
        val dps = TuyaBleLib.parseDatapoints(frame.data, 3, 1, 0, 1_700_000_000L)
        assertThat(dps).hasSize(1)
        assertThat(dps[0].id).isEqualTo(1)
        assertThat(dps[0].value).isEqualTo(8755)
    }

    @Test
    fun v4DpFrameUsesTwoByteLengths() {
        // v4 frames start at offset 7 and announce the value length with 2 bytes
        val payload = byteArrayOf(0, 0, 0, 0, 0, 0x80.toByte(), 0) +
            byteArrayOf(1, 2) + int16(4) + int32(8960)
        val dps = TuyaBleLib.parseDatapoints(payload, 7, 2, 0, 1_700_000_000L)
        assertThat(dps).hasSize(1)
        assertThat(dps[0].id).isEqualTo(1)
        assertThat(dps[0].value).isEqualTo(8960)
    }

    @Test
    fun truncatedDatapointThrows() {
        val payload = byteArrayOf(1, 2, 4, 0x11, 0x22) // announces 4 bytes, only 2 present
        assertThrows(TuyaBleProtocolException::class.java) {
            TuyaBleLib.parseDatapoints(payload, 0, 1, 0, 0L)
        }
    }

    // ── timestamps ────────────────────────────────────────────────────────────

    @Test
    fun timestampType0AsciiMillis() {
        val digits = "1700000000123".toByteArray()
        val data = byteArrayOf(0) + digits
        val (seconds, pos) = TuyaBleLib.parseTimestamp(data, 0)
        assertThat(seconds).isEqualTo(1700000000L)
        assertThat(pos).isEqualTo(14)
    }

    @Test
    fun timestampType1BeSeconds() {
        val data = byteArrayOf(1) + int32(1700000000)
        val (seconds, pos) = TuyaBleLib.parseTimestamp(data, 0)
        assertThat(seconds).isEqualTo(1700000000L)
        assertThat(pos).isEqualTo(5)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun int32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun int16(value: Int): ByteArray = byteArrayOf(
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
