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
import org.junit.Test

/**
 * Unit tests for the Omron WLP transfer framing.
 *
 * The session-control frames are asserted against the literal byte sequences observed on Omron
 * devices, so a change to the checksum or the frame layout fails here rather than on hardware.
 */
class OmronWlpFrameTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun String.asBytes() = hex(this)

    @Test
    fun `start and end frames match the sequences Omron devices expect`() {
        assertThat(OmronWlpFrame.startTransmission()).isEqualTo("0800000000100018".asBytes())
        assertThat(OmronWlpFrame.endTransmission()).isEqualTo("080f000000000007".asBytes())
    }

    @Test
    fun `an intact frame checksums to zero`() {
        assertThat(OmronWlpFrame.bcc(OmronWlpFrame.startTransmission()).toInt()).isEqualTo(0)
        assertThat(OmronWlpFrame.bcc(OmronWlpFrame.endTransmission()).toInt()).isEqualTo(0)
    }

    @Test
    fun `builds an eeprom read for the first record of user slot 1`() {
        assertThat(OmronWlpFrame.readEeprom(0x02C0, 0x20)).isEqualTo("080100 02c0 20 00 eb".asBytes())
    }

    @Test
    fun `rejects reads that exceed one block or address the flag bits`() {
        runCatching { OmronWlpFrame.readEeprom(0x02C0, 0x21) }.let { assertThat(it.isFailure).isTrue() }
        runCatching { OmronWlpFrame.readEeprom(0x4000, 0x20) }.let { assertThat(it.isFailure).isTrue() }
    }

    @Test
    fun `unlock channel commands carry the documented opcodes`() {
        val key = ByteArray(16) { (it + 1).toByte() }

        assertThat(OmronWlpFrame.unlock(key)[0].toInt()).isEqualTo(0x01)
        assertThat(OmronWlpFrame.unlock(key).copyOfRange(1, 17)).isEqualTo(key)

        assertThat(OmronWlpFrame.writeKey(key)[0].toInt()).isEqualTo(0x00)
        assertThat(OmronWlpFrame.writeKey(key).copyOfRange(1, 17)).isEqualTo(key)

        val enter = OmronWlpFrame.enterKeyProgramming()
        assertThat(enter.size).isEqualTo(17)
        assertThat(enter[0].toInt()).isEqualTo(0x02)
        assertThat(enter.drop(1).all { it.toInt() == 0 }).isTrue()
    }

    @Test
    fun `a short key is refused rather than padded`() {
        assertThat(runCatching { OmronWlpFrame.unlock(ByteArray(8)) }.isFailure).isTrue()
    }

    @Test
    fun `a 40 byte response spans three notify channels`() {
        assertThat(OmronWlpFrame.channelsForFrame(40)).isEqualTo(3)
        assertThat(OmronWlpFrame.channelsForFrame(8)).isEqualTo(1)
        assertThat(OmronWlpFrame.channelsForFrame(32)).isEqualTo(2)

        val chunks = OmronWlpFrame.toChannelChunks(ByteArray(40) { it.toByte() })
        assertThat(chunks.map { it.size }).containsExactly(16, 16, 8).inOrder()
    }

    /** Builds a well-formed read response carrying [payload] from EEPROM [address]. */
    private fun readResponse(address: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(payload.size + 8)
        frame[0] = frame.size.toByte()
        frame[1] = 0x81.toByte()
        frame[2] = 0x00
        frame[3] = ((address shr 8) and 0xFF).toByte()
        frame[4] = (address and 0xFF).toByte()
        frame[5] = payload.size.toByte()
        payload.copyInto(frame, 6)
        frame[frame.size - 1] = OmronWlpFrame.bcc(frame, frame.size - 1)
        return frame
    }

    @Test
    fun `parses a read response`() {
        val payload = ByteArray(32) { (0xA0 + it).toByte() }
        val parsed = OmronWlpFrame.parseResponse(readResponse(0x02C0, payload))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(OmronWlpFrame.TYPE_READ)
        assertThat(parsed.address).isEqualTo(0x02C0)
        assertThat(parsed.data).isEqualTo(payload)
        assertThat(parsed.result).isEqualTo(0)
    }

    @Test
    fun `parses the session end result code`() {
        val parsed = OmronWlpFrame.parseResponse("088f000000000087".asBytes())

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(OmronWlpFrame.TYPE_END)
        assertThat(parsed.result).isEqualTo(0)
    }

    @Test
    fun `rejects a frame whose checksum does not match`() {
        val frame = readResponse(0x02C0, ByteArray(32) { it.toByte() })
        frame[10] = (frame[10] + 1).toByte()

        assertThat(OmronWlpFrame.parseResponse(frame)).isNull()
    }

    @Test
    fun `rejects truncated frames`() {
        val frame = readResponse(0x02C0, ByteArray(32) { it.toByte() })

        assertThat(OmronWlpFrame.parseResponse(frame.copyOf(20))).isNull()
        assertThat(OmronWlpFrame.parseResponse(ByteArray(4))).isNull()
    }

    @Test
    fun `ignores padding after the declared frame length`() {
        // Channels are 16 bytes wide, so a 40-byte frame arrives inside 48 bytes of notifications.
        val payload = ByteArray(32) { (it * 3).toByte() }
        val padded = readResponse(0x0890, payload) + ByteArray(8) { 0x55 }

        val parsed = OmronWlpFrame.parseResponse(padded)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.data).isEqualTo(payload)
    }

    @Test
    fun `treats an under-filled read as erased memory rather than dropping the frame`() {
        // The device declares 32 payload bytes but the frame only has room for 8.
        val frame = byteArrayOf(0x10, 0x81.toByte(), 0x00, 0x02, 0xC0.toByte(), 0x20) +
            ByteArray(8) + byteArrayOf(0x00, 0x00)
        frame[frame.size - 1] = OmronWlpFrame.bcc(frame, frame.size - 1)

        val parsed = OmronWlpFrame.parseResponse(frame)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.data.size).isEqualTo(32)
        assertThat(parsed.data.all { it == 0xFF.toByte() }).isTrue()
    }
}
