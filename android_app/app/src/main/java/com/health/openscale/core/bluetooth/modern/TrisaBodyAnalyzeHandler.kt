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
package com.health.openscale.core.bluetooth.modern

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.TrisaBodyAnalyzeLib
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.util.Date
import java.util.UUID

/**
 * TrisaBodyAnalyzeHandler
 * -----------------------
 * Modern Kotlin handler for the **Trisa Body Analyze 4.0** (aka Transtek GBF-1257-B).
 *
 * Protocol highlights (per legacy driver):
 * - GATT Service:      0x7802
 * - Measurement Char:  0x8A21  (indications/notifications)
 * - Download Command:  0x8A81  (host → device)
 * - Upload Command:    0x8A82  (device → host)
 *
 * Upload (device→host) command opcodes:
 * - 0xA0 = Password (device sends 32-bit password; should be persisted per device)
 * - 0xA1 = Challenge (host must xor with password and reply via Download-Result)
 *
 * Download (host→device) command opcodes:
 * - 0x02 = DOWNLOAD_INFORMATION_UTC_COMMAND (send current time)
 * - 0x20 = DOWNLOAD_INFORMATION_RESULT_COMMAND (send XOR response)
 * - 0x21 = DOWNLOAD_INFORMATION_BROADCAST_ID_COMMAND (set broadcast id during pairing)
 * - 0x22 = DOWNLOAD_INFORMATION_ENABLE_DISCONNECT_COMMAND (optional)
 */
class TrisaBodyAnalyzeHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Legacy detection used device names "01257B" / "11257B" prefixes
        val n = device.name ?: return null
        val supported = n.startsWith("01257B") || n.startsWith("11257B")
        return if (supported) {
            DeviceSupport(
                displayName = "Trisa Body Analyze 4.0",
                capabilities = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC),
                implemented  = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC),
                linkMode = LinkMode.CONNECT_GATT
            )
        } else null
    }

    // --- UUIDs (Bluetooth Base UUID, 16-bit short codes) ---------------------

    private val SVC_WEIGHT = uuid16(0x7802)
    private val CHR_MEAS   = uuid16(0x8A21)
    private val CHR_DNLD   = uuid16(0x8A81) // host → device
    private val CHR_UPLD   = uuid16(0x8A82) // device → host

    // --- Opcodes --------------------------------------------------------------

    private val UPLOAD_PASSWORD: Byte   = 0xA0.toByte()
    private val UPLOAD_CHALLENGE: Byte  = 0xA1.toByte()

    private val CMD_DOWNLOAD_INFORMATION_UTC: Byte           = 0x02
    private val CMD_DOWNLOAD_INFORMATION_RESULT: Byte         = 0x20
    private val CMD_DOWNLOAD_INFORMATION_BROADCAST_ID: Byte   = 0x21
    private val CMD_DOWNLOAD_INFORMATION_ENABLE_DISCONNECT: Byte = 0x22

    private val BROADCAST_ID = 0 // required to complete pairing; value seems arbitrary

    // Timestamp reference (2010-01-01 00:00:00 UTC)
    private val TIMESTAMP_OFFSET_SECONDS = 1262304000L

    // Pairing state
    private var pairing = false

    // Cached password (persisted via DriverSettings; see helpers below)
    private var password: Int? = null

    // --- Lifecycle ------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // Enable indications/notifications first; device starts pushing frames afterwards.
        setNotifyOn(SVC_WEIGHT, CHR_MEAS)
        setNotifyOn(SVC_WEIGHT, CHR_UPLD)

        // Load previously stored password for this device (if any)
        password = settingsGetInt("trisa/password", -1).takeIf { it != -1 }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_UPLD -> handleUploadCommand(data)
            CHR_MEAS -> handleMeasurement(data, user)
            else     -> logW("Unknown characteristic notify: $characteristic")
        }
    }

    // --- Upload (device → host) processing -----------------------------------

    private fun handleUploadCommand(data: ByteArray) {
        if (data.isEmpty()) {
            logW("Upload command: empty payload")
            return
        }
        when (data[0]) {
            UPLOAD_PASSWORD -> onPasswordReceived(data)
            UPLOAD_CHALLENGE -> onChallengeReceived(data)
            else -> logW("Upload: unknown opcode ${data[0].toUByte().toString(16)}")
        }
    }

    private fun onPasswordReceived(data: ByteArray) {
        if (data.size < 5) {
            logW("Password payload too short")
            return
        }
        val pw = ConverterUtils.fromSignedInt32Le(data, 1)
        password = pw
        settingsPutInt("trisa/password", pw)

        userInfo(R.string.bluetooth_scale_trisa_success_pairing)

        // Complete pairing: set broadcast ID then disconnect.
        pairing = true
        writeCommand(CMD_DOWNLOAD_INFORMATION_BROADCAST_ID, BROADCAST_ID)
        // We don't receive a write-complete callback, so disconnect right away.
        requestDisconnect()
    }

    private fun onChallengeReceived(data: ByteArray) {
        if (data.size < 5) {
            logW("Challenge payload too short")
            return
        }
        val pw = password ?: run {
            userWarn(R.string.bluetooth_scale_trisa_message_not_paired_instruction)
            requestDisconnect()
            return
        }
        val challenge = ConverterUtils.fromSignedInt32Le(data, 1)
        val response = challenge xor pw
        writeCommand(CMD_DOWNLOAD_INFORMATION_RESULT, response)

        val nowDevice = convertJavaTimestampToDevice(System.currentTimeMillis())
        writeCommand(CMD_DOWNLOAD_INFORMATION_UTC, nowDevice)
    }

    // --- Measurement parsing --------------------------------------------------

    private fun handleMeasurement(data: ByteArray, user: ScaleUser) {
        val m = parseScaleMeasurementData(data, user) ?: run {
            logW("Failed to parse measurement: ${data.toHexPreview(24)}")
            return
        }
        publish(m)
    }

    /**
     * Parse measurement payload.
     *
     * Layout:
     *  byte 0 : info flags (bit0 timestamp, bit1 resistance1, bit2 resistance2)
     *  bytes1-4 : weight (base10 float)
     *  bytes5-8 : timestamp (if bit0)
     *  +4       : resistance1 (if bit1)
     *  +4       : resistance2 (if bit2)
     */
    fun parseScaleMeasurementData(data: ByteArray, user: ScaleUser?): ScaleMeasurement? {
        if (data.size < 9) return null
        val info = data[0].toInt()
        val hasTs = (info and 0x01) != 0
        val hasR1 = (info and 0x02) != 0
        val hasR2 = (info and 0x04) != 0
        if (!hasTs) return null

        val weightKg = getBase10Float(data, 1)
        val deviceTs = ConverterUtils.fromSignedInt32Le(data, 5)

        val measurement = ScaleMeasurement().apply {
            dateTime = Date(convertDeviceTimestampToJava(deviceTs))
            weight = weightKg
        }

        // Only resistance2 is used for derived composition fields
        val r2Offset = 9 + if (hasR1) 4 else 0
        if (hasR2 && r2Offset + 4 <= data.size && isValidUser(user)) {
            val resistance2 = getBase10Float(data, r2Offset)
            val impedance = if (resistance2 < 410f) 3.0f else 0.3f * (resistance2 - 400f)
            val sexFlag = if (user!!.gender.isMale()) 1 else 0
            val lib = TrisaBodyAnalyzeLib(sexFlag, user.age, user.bodyHeight)
            measurement.fat = lib.getFat(weightKg, impedance)
            measurement.water = lib.getWater(weightKg, impedance)
            measurement.muscle = lib.getMuscle(weightKg, impedance)
            measurement.bone = lib.getBone(weightKg, impedance)
        }
        return measurement
    }

    // --- Command helpers (host → device) -------------------------------------

    private fun writeCommand(opcode: Byte) {
        writeTo(SVC_WEIGHT, CHR_DNLD, byteArrayOf(opcode), withResponse = true)
    }

    private fun writeCommand(opcode: Byte, arg: Int) {
        val bytes = ByteArray(5)
        bytes[0] = opcode
        ConverterUtils.toInt32Le(bytes, 1, arg.toLong())
        writeTo(SVC_WEIGHT, CHR_DNLD, bytes, withResponse = true)
    }

    // --- Utility --------------------------------------------------------------

    private fun getBase10Float(data: ByteArray, offset: Int): Float {
        val mantissa = ConverterUtils.fromUnsignedInt24Le(data, offset)
        val exponent = data[offset + 3].toInt() // signed
        return (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
    }

    private fun convertJavaTimestampToDevice(javaMillis: Long): Int {
        return (((javaMillis + 500) / 1000) - TIMESTAMP_OFFSET_SECONDS).toInt()
    }

    private fun convertDeviceTimestampToJava(deviceSeconds: Int): Long {
        return 1000L * (TIMESTAMP_OFFSET_SECONDS + deviceSeconds.toLong())
    }

    private fun isValidUser(user: ScaleUser?): Boolean =
        user != null && user.age > 0 && user.bodyHeight > 0
}
