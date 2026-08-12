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
 * blocks of at most 32 bytes.
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
