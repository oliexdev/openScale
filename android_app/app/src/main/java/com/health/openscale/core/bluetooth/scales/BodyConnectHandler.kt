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
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils

import java.util.Date
import java.util.UUID
import kotlin.random.Random

/**
 * BodyConnectHandler
 * ------------------
 * Modern Kotlin handler for the **1BODY CONNECT** smart scale (Transtek family).
 *
 * Protocol highlights (per BTSnoop analysis):
 * - GATT Service:      0x7892
 * - Weight:     0x8A24  (0x1F frames — weight records)
 * - Body Comp: 0x8A22  (0x7F frames — body composition)
 * - Download (host→dev): 0x8A81  (commands)
 * - Upload   (dev→host): 0x8A82  (notifications)
 *
 * Device→host opcodes:
 * - 0xA0 = Password        (32-bit, unknown; persisted per device)
 * - 0xA1 = Challenge       (always 0x11111111; host XORs with password and replies)
 * - 0x83 = Slot Status     (8 user slots, each with a 16-char name)
 * - 0xC0 = Profile Echo    (confirms user profile after time set)
 *
 * Host→device opcodes:
 * - 0x02 = Set Time        (UTC timestamp as seconds since 2010-01-01)
 * - 0x03 = Slot Ack        (echo of a slot status record)
 * - 0x20 = Challenge Resp  (challenge XOR password)
 * - 0x21 = Broadcast ID    (sent during pairing)
 * - 0x22 = Enable Disconnect
 * - 0x51 = User Profile    (gender, age, height)
 *
 * @see TrisaBodyAnalyzeHandler similar challenge-response protocol
 */
class BodyConnectHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name
        if (!name.startsWith("1BODY CONNECT") && !name.startsWith("0BODY CONNECT")) return null
        return DeviceSupport(
            displayName = "1BODY CONNECT",
            capabilities = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC, DeviceCapability.USER_SYNC, DeviceCapability.HISTORY_READ),
            implemented = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC, DeviceCapability.HISTORY_READ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- UUIDs (Bluetooth Base UUID, 16-bit short codes) -----------------------

    private val SVC = uuid16(0x7892)
    private val CHR_WEIGHT = uuid16(0x8A24) // 0x1F weight frames
    private val CHR_BODY  = uuid16(0x8A22) // 0x7F body comp frames
    private val CHR_DNLD = uuid16(0x8A81) // host → device
    private val CHR_UPLD = uuid16(0x8A82) // device → host

    // --- Upload (device → host) opcodes ----------------------------------------

    private val CMD_PASSWORD: Byte     = 0xA0.toByte()
    private val CMD_CHALLENGE: Byte    = 0xA1.toByte()
    private val CMD_SLOT_STATUS: Byte  = 0x83.toByte()
    private val CMD_PROFILE_ECHO: Byte = 0xC0.toByte()

    // --- Download (host → device) opcodes --------------------------------------

    private val CMD_ACK: Byte                = 0x03
    private val CMD_TIME: Byte               = 0x02
    private val CMD_CHALLENGE_RESPONSE: Byte = 0x20
    private val CMD_BROADCAST: Byte          = 0x21
    private val CMD_ENABLE_DISCONNECT: Byte  = 0x22

    // Non-zero broadcast ID required for pairing to succeed; generated randomly per device instance
    private val BROADCAST_ID = Random.nextInt(Int.MAX_VALUE - 1) + 1

    // Timestamp base: 2010-01-01 00:00:00 UTC; device stores seconds since this epoch
    private val TS_OFFSET = 1262304000L

    // --- Pairing state ---------------------------------------------------------

    private var pairing = false
    private var password: Int? = null
    private var statusAcked = false

    // --- Frame matching --------------------------------------------------------
    // 0x1F and 0x7F frames share a device timestamp; we cache the weight from
    // 0x1F and match it when 0x7F arrives with the same timestamp.

    private var lastTS: Int? = null
    private var lastWeight: Float? = null

    // --- Lifecycle -------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        setNotifyOn(SVC, CHR_WEIGHT)
        setNotifyOn(SVC, CHR_BODY)
        setNotifyOn(SVC, CHR_UPLD)

        // Restore password persisted from a previous pairing session
        password = settingsGetInt("bodyconnect/password", -1).takeIf { it != -1 }
        pairing = false
        statusAcked = false
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_UPLD             -> onUpload(data)
            CHR_WEIGHT   -> onWeight(data)
            CHR_BODY     -> onBody(data, user)
            else                 -> logW("Unknown characteristic: $characteristic")
        }
    }

    // --- Upload (device → host) processing -------------------------------------

    private fun onUpload(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            CMD_PASSWORD    -> onPassword(data)
            CMD_CHALLENGE   -> onChallenge(data)
            CMD_PROFILE_ECHO -> onProfileEcho()
            CMD_SLOT_STATUS -> onSlotStatus(data)
        }
    }

    private fun onPassword(data: ByteArray) {
        if (data.size < 5) return
        val pw = ConverterUtils.fromSignedInt32Le(data, 1)
        password = pw
        settingsPutInt("bodyconnect/password", pw)

        userInfo(R.string.bluetooth_scale_trisa_success_pairing)
        pairing = true
        // Broadcast ID must be set before the scale accepts further commands
        writeCommand(CMD_BROADCAST, BROADCAST_ID)
    }

    private fun onChallenge(data: ByteArray) {
        if (data.size < 5) return
        val pw = password ?: run {
            userWarn(R.string.bluetooth_scale_trisa_message_not_paired_instruction)
            requestDisconnect()
            return
        }
        val challenge = ConverterUtils.fromSignedInt32Le(data, 1)
        writeCommand(CMD_CHALLENGE_RESPONSE, challenge xor pw)

        if (!pairing) {
            // Already paired: send profile + time directly (scale skips slot negotiation)
            writeProfile(currentAppUser())
            writeCommand(CMD_TIME, javaTimeToDevice(System.currentTimeMillis()))
        }
    }

    private fun onProfileEcho() {
        // Scale confirms the user profile; we signal that setup is complete.
        writeCommand(CMD_ENABLE_DISCONNECT)
    }

    private fun onSlotStatus(data: ByteArray) {
        // Scale lists its 8 user slots (first non‑empty one must be acknowledged)
        if (data.size < 3 || statusAcked) return
        val hasName = (2 until data.size).any { i ->
            val b = data[i].toInt() and 0xFF
            b != 0x00 && b != 0x20
        }
        if (!hasName) return
        statusAcked = true

        // Echo the slot record with opcode 0x03 to acknowledge it
        val ack = data.copyOf().also { it[0] = CMD_ACK }
        writeTo(SVC, CHR_DNLD, ack, withResponse = true)

        // Now send profile, time, and signal setup complete
        val user = currentAppUser()
        writeProfile(user)
        writeCommand(CMD_TIME, javaTimeToDevice(System.currentTimeMillis()))
        writeCommand(CMD_ENABLE_DISCONNECT)
    }

    // --- Frame parsing ---------------------------------------------------------

    private fun onWeight(data: ByteArray) {
        if (data.size < 20 || data[0] != 0x1F.toByte()) return

        // 0x1F frame layout:
        //   off 0:       opcode 0x1F
        //   off 1-2:     weight (LE uint16, /100 = kg)
        //   off 5-8:     device timestamp (LE int32)
        lastWeight = ConverterUtils.fromUnsignedInt16Le(data, 1) / 100f
        lastTS = ConverterUtils.fromSignedInt32Le(data, 5)
    }

    private fun onBody(data: ByteArray, user: ScaleUser) {
        if (data.size < 20 || data[0] != 0x7F.toByte()) return

        // 0x7F frame layout:
        //   off 0:       opcode 0x7F
        //   off 1-4:     device timestamp (LE int32, matches paired 0x1F)
        //   off 5:       (0x01 ?)
        //   off 8-9:     fat %     (if hi nibble == 0xF)
        //   off 10-11:   water %   (if hi nibble == 0xF)
        //   off 14-15:   muscle %  (if hi nibble == 0xF)
        //   off 16-17:   bone %    (if hi nibble == 0xF)

        val fat = parseBodyComp(data, 8)
        val water = parseBodyComp(data, 10)
        val muscle = parseBodyComp(data, 14)
        val bone = parseBodyComp(data, 16)

        if (fat == null && water == null && muscle == null && bone == null) return

        val ts = ConverterUtils.fromSignedInt32Le(data, 1)
        val weight = if (lastTS == ts) lastWeight else null
        if (weight == null || weight <= 0f) return

        val m = ScaleMeasurement().apply {
            dateTime = Date(deviceTimeToJava(ts))
            this.weight = weight
        }
        fat?.let { m.fat = it }
        water?.let { m.water = it }
        muscle?.let { m.muscle = it }
        bone?.let { m.bone = it }
        publish(m)
    }

    // --- Download (host → device) helpers --------------------------------------

    private fun writeProfile(user: ScaleUser) {
        val b = ByteArray(15)
        b[0] = 0x51.toByte()
        b[1] = 0xDF.toByte()
        b[2] = 0x01.toByte()
        b[3] = if (user.gender.isMale()) 0x01.toByte() else 0x00.toByte()
        b[4] = user.age.toByte()
        b[5] = user.bodyHeight.toInt().toByte()
        b[6] = 0xE0.toByte()
        b[7] = 0x00.toByte()
        b[8] = 0x00.toByte()
        b[9] = 0x40.toByte()
        b[10] = 0x1F.toByte()
        b[11] = 0x00.toByte()
        b[12] = 0xFE.toByte()
        b[13] = 0x00.toByte()
        b[14] = 0x00.toByte()
        writeTo(SVC, CHR_DNLD, b, withResponse = true)
    }

    private fun parseBodyComp(data: ByteArray, off: Int): Float? {
        // Two bytes: hi nibble of second byte must be 0xF to indicate valid data
        if (off + 1 >= data.size) return null
        val lo = data[off].toInt() and 0xFF
        val hi = data[off + 1].toInt() and 0xFF
        return if (hi and 0xF0 == 0xF0) ((hi and 0x0F) shl 8 or lo) / 10f else null
    }

    private fun writeCommand(opcode: Byte) {
        writeTo(SVC, CHR_DNLD, byteArrayOf(opcode), withResponse = true)
    }

    private fun writeCommand(opcode: Byte, arg: Int) {
        val b = ByteArray(5).also {
            it[0] = opcode
            ConverterUtils.toInt32Le(it, 1, arg.toLong())
        }
        writeTo(SVC, CHR_DNLD, b, withResponse = true)
    }

    // --- Timestamp conversion --------------------------------------------------

    private fun javaTimeToDevice(ms: Long): Int {
        return (((ms + 500) / 1000) - TS_OFFSET).toInt()
    }

    private fun deviceTimeToJava(s: Int): Long {
        return 1000L * (TS_OFFSET + s.toLong())
    }
}
