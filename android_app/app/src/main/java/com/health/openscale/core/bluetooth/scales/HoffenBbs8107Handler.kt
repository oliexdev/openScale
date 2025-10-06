/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Handler for the Hoffen BBS-8107 scale.
 *
 * Protocol (single custom characteristic):
 *  - Service 0xFFB0, Characteristic 0xFFB2 (notify + write with response)
 *  - Packets start with 0xFA, then [cmdOrResp], [len], [payload], [checksum]
 *  - Checksum = XOR of bytes 1..(n-2); some final measurement frames deliver checksum in a follow-up notify.
 */
class HoffenBbs8107Handler : ScaleDeviceHandler() {

    // --- GATT UUIDs -----------------------------------------------------------

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR: UUID = uuid16(0xFFB2)

    // --- DeviceSupport --------------------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.lowercase(Locale.US) ?: return null
        if (name != "hoffen bs-8107") return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
            DeviceCapability.UNIT_CONFIG
        )
        // We implement everything we claim above.
        return DeviceSupport(
            displayName = "Hoffen BBS-8107",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Wire constants -------------------------------------------------------

    private val MAGIC: Byte = 0xFA.toByte()

    // Responses (value[1])
    private val RESP_INTERMEDIATE: Byte = 0x01
    private val RESP_FINAL: Byte = 0x02
    private val RESP_ACK: Byte = 0x03

    // Commands to send (value[1] when we transmit)
    private val CMD_MEASUREMENT_DONE: Byte = 0x82.toByte()
    private val CMD_CHANGE_UNIT: Byte = 0x83.toByte()
    private val CMD_SEND_USER: Byte = 0x85.toByte()

    // --- Session state --------------------------------------------------------

    private enum class Phase { IDLE, SENT_USER, SENT_UNIT, WAIT_MEAS, SENT_DONE }
    private var phase: Phase = Phase.IDLE
    private lateinit var sessionUser: ScaleUser

    // --- Lifecycle ------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        sessionUser = user
        setNotifyOn(SERVICE, CHAR)

        // Phase 1: send user profile
        sendUser()
        phase = Phase.SENT_USER
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR || data.size < 2) return

        // Some final measurement frames announce with 0xFA 0x02 and checksum may follow;
        // accept those even if XOR check fails.
        val checksumOk = verify(data)
        if (!checksumOk && !(data[0] == MAGIC && data[1] == RESP_FINAL)) {
            LogManager.d(TAG, "Checksum incorrect – drop")
            return
        }

        if (data[0] != MAGIC) {
            LogManager.d(TAG, "Unexpected but well-formed packet: ${data.joinToString { "%02X".format(it) }}")
            return
        }

        when (data[1]) {
            RESP_ACK -> {
                LogManager.d(TAG, "ACK")
                when (phase) {
                    Phase.SENT_USER -> {
                        // Now send preferred unit
                        sendUnit(sessionUser.scaleUnit)
                        phase = Phase.SENT_UNIT
                    }
                    Phase.SENT_UNIT -> {
                        // Ready to measure
                        userInfo(R.string.bt_info_step_on_scale)
                        phase = Phase.WAIT_MEAS
                    }
                    Phase.SENT_DONE -> {
                        // We’re done; close link
                        requestDisconnect()
                        phase = Phase.IDLE
                    }
                    else -> { /* ignore */ }
                }
            }

            RESP_INTERMEDIATE -> {
                // Live/unstable weight preview
                val w = ConverterUtils.fromUnsignedInt16Le(data, 4) / 10.0f
                LogManager.d(TAG, "Intermediate weight: %.1f %s"
                    .format(Locale.US, w, sessionUser.scaleUnit.toString()))
                userInfo(R.string.bluetooth_scale_info_measuring_weight, w)
            }

            RESP_FINAL -> {
                // Final composition / weight
                val m = parseFinalMeasurement(data, sessionUser.scaleUnit)
                publish(m)

                // Tell scale we're done; it powers down shortly after
                sendPacket(CMD_MEASUREMENT_DONE, byteArrayOf(0x00))
                phase = Phase.SENT_DONE
            }

            else -> {
                LogManager.d(TAG, "Unknown response 0x%02X".format(data[1]))
            }
        }
    }

    // --- Packet builders ------------------------------------------------------

    private fun sendUser() {
        val u = sessionUser
        val payload = byteArrayOf(
            0x00, // "plan" id? (legacy sent 0)
            if (u.gender.isMale()) 0x01 else 0x00,
            u.age.toByte(),
            u.bodyHeight.toInt().toByte(),
        )
        sendPacket(CMD_SEND_USER, payload)
    }

    private fun sendUnit(unit: WeightUnit) {
        // Legacy used: (0x01 + unit.toInt()), second byte 0x00
        val code = (1 + unit.toInt()).toByte()
        val payload = byteArrayOf(code, 0x00)
        sendPacket(CMD_CHANGE_UNIT, payload)
    }

    private fun sendPacket(cmd: Byte, payload: ByteArray) {
        val out = ByteArray(payload.size + 4)
        out[0] = MAGIC
        out[1] = cmd
        out[2] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        // checksum over bytes 1..n-2
        out[out.lastIndex] = xorChecksum(out, 1, out.size - 2)
        writeTo(SERVICE, CHAR, out, withResponse = true)
    }

    private fun verify(buf: ByteArray): Boolean {
        if (buf.size < 4) return false
        val sum = xorChecksum(buf, 1, buf.size - 1)
        return sum.toInt() == 0
    }

    private fun xorChecksum(buf: ByteArray, start: Int, endExclusive: Int): Byte {
        var x = 0
        for (i in start until endExclusive) x = x xor (buf[i].toInt() and 0xFF)
        return (x and 0xFF).toByte()
    }

    // --- Parsing --------------------------------------------------------------

    private fun parseFinalMeasurement(frame: ByteArray, prefUnit: WeightUnit): ScaleMeasurement {
        // Weight at LE16 offset 3 (kg or lb depending on scale mode)
        var weight = ConverterUtils.fromUnsignedInt16Le(frame, 3) / 10.0f
        userInfo(R.string.bluetooth_scale_info_measuring_weight, weight)

        // If user prefers LB/ST the scale *still* sends result in LB; convert to kg for storage.
        if (prefUnit != WeightUnit.KG) {
            weight = ConverterUtils.toKilogram(weight, WeightUnit.LB)
        }

        val m = ScaleMeasurement().apply {
            dateTime = Date()
            this.weight = max(0f, weight)
        }

        // If barefoot (contact) → extra composition values
        when (frame[5]) {
            0x00.toByte() -> {
                m.fat = ConverterUtils.fromUnsignedInt16Le(frame, 6) / 10.0f
                m.water = ConverterUtils.fromUnsignedInt16Le(frame, 8) / 10.0f
                m.muscle = ConverterUtils.fromUnsignedInt16Le(frame, 10) / 10.0f
                // Bone mass is returned in deci-kg at index 14 (single byte)
                m.bone = (frame[14].toInt() and 0xFF) / 10.0f
                m.visceralFat = ConverterUtils.fromUnsignedInt16Le(frame, 17) / 10.0f
            }
            0x04.toByte() -> {
                LogManager.d(TAG, "No impedance/contact data")
            }
            else -> {
                LogManager.d(TAG, "Unexpected composition marker: %02X".format(frame[5]))
            }
        }
        return m
    }
}
