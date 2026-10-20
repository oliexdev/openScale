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
import com.health.openscale.core.bluetooth.libs.TerraillonLib
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/**
 * Terraillon Smart Connect (ref. 14754 / 14874) and other Terraillon "Web Coach" scales
 * built on the Transtek A3 module (vendor apps: Wellness Coach, MyHealth).
 *
 * Advertises as a 14-digit number such as "01376012345678" with service 0x7892.
 * Device Information: manufacturer "Transtek", model "380", firmware "A3".
 *
 * Service 0x7892:
 * - 0x8A24 weight, 32-bit FLOAT (indicate)
 * - 0x8A22 body composition, 16-bit SFLOATs (indicate)
 * - 0x8A20 feature (read)
 * - 0x8A81 commands, host to device (write)
 * - 0x8A82 events, device to host (indicate)
 *
 * Session captured from MyHealth (BTSnoop):
 * ```
 * enable indications on 0x8A24, 0x8A22, 0x8A82
 * ← 8A82  a1 11 11 11 11          random
 * → 8A81  20 <password xor random>
 * → 8A81  02 <local time, s since 2010-01-01, LE>
 * ← 8A82  c0 …                    profile echo
 * → 8A81  22                      done configuring
 * ← 8A24 / 8A22 pairs             every stored weigh-in, oldest first
 * ```
 *
 * Unlike the Weight Gurus variant of this protocol, the vendor app never pairs or writes a
 * profile in a normal session, and the password is not a secret handed out at pairing: it is
 * the low four bytes of the scale's MAC address, in reverse order (see [TerraillonLib]).
 * A password the scale does hand out (0xA0, pairing mode) takes precedence.
 *
 * Body composition is computed on the scale from the profile stored on it by the vendor app;
 * bone mass is sent in kg (Weight Gurus sends a percentage).
 */
class TerraillonWebCoachHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name
        val byName = NAME_PATTERN.matches(name)
        val byService = device.serviceUuids.contains(SVC) && DIGITS_PATTERN.matches(name)
        if (!byName && !byService) return null
        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.HISTORY_READ
        )
        return DeviceSupport(
            displayName = "Terraillon Smart Connect",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- UUIDs ----------------------------------------------------------------

    private val CHR_WEIGHT = uuid16(0x8A24) // device → host, weight
    private val CHR_BODY   = uuid16(0x8A22) // device → host, body composition
    private val CHR_DNLD   = uuid16(0x8A81) // host → device, commands
    private val CHR_UPLD   = uuid16(0x8A82) // device → host, events

    // --- Opcodes --------------------------------------------------------------

    private val EVT_PASSWORD: Byte     = 0xA0.toByte()
    private val EVT_RANDOM: Byte       = 0xA1.toByte()
    private val EVT_PROFILE_ECHO: Byte = 0xC0.toByte()

    private val CMD_TIME: Byte              = 0x02
    private val CMD_VERIFICATION: Byte      = 0x20
    private val CMD_ENABLE_DISCONNECT: Byte = 0x22

    // --- Session state --------------------------------------------------------

    private var pendingWeight: ScaleMeasurement? = null
    private var pendingTimestamp: Int? = null
    private val publishedAtMs = mutableSetOf<Long>()
    private var setupFinished = false

    // --- Lifecycle ------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        pendingWeight = null
        pendingTimestamp = null
        publishedAtMs.clear()
        setupFinished = false

        setNotifyOn(SVC, CHR_WEIGHT)
        setNotifyOn(SVC, CHR_BODY)
        setNotifyOn(SVC, CHR_UPLD)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_UPLD   -> onEvent(data)
            CHR_WEIGHT -> onWeight(data)
            CHR_BODY   -> onBody(data)
            else       -> logW("Unknown characteristic: $characteristic")
        }
    }

    override fun onDisconnected() {
        flushPendingWeight()
    }

    // --- Events (device → host) -----------------------------------------------

    private fun onEvent(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            EVT_PASSWORD     -> onPassword(data)
            EVT_RANDOM       -> onRandom(data)
            EVT_PROFILE_ECHO -> finishSetup()
            else             -> logD("Event ${data.toHexPreview(20)}")
        }
    }

    private fun onPassword(data: ByteArray) {
        if (data.size < 5) return
        val pw = data.copyOfRange(1, 5)
        settingsPutString(KEY_PASSWORD, pw.joinToString("") { String.format("%02x", it) })
        logI("Scale handed out a password; stored for later sessions")
    }

    private fun onRandom(data: ByteArray) {
        if (data.size < 5) {
            logW("Random frame too short: ${data.size}b")
            return
        }
        val pw = settingsGetString(KEY_PASSWORD)?.let(::hexToBytes)
            ?: getPeripheral()?.address?.let(TerraillonLib::passwordFromMac)
            ?: run {
                logE("No password and no MAC address available")
                userWarn(R.string.bluetooth_scale_trisa_message_not_paired_instruction)
                requestDisconnect()
                return
            }
        val random = data.copyOfRange(1, 5)
        writeCommand(CMD_VERIFICATION, TerraillonLib.verificationCode(pw, random))
        writeCommand(CMD_TIME, ConverterUtils.toInt32Le(javaTimeToDevice(System.currentTimeMillis()).toLong()))

        // The vendor app sends 0x22 once the scale echoes its profile (0xC0). Should the echo
        // never come, send it anyway: the stored readings only flow after it.
        scope.launch {
            delay(ECHO_FALLBACK_MS)
            if (!setupFinished) logW("No profile echo; finishing setup anyway")
            finishSetup()
        }
    }

    private fun finishSetup() {
        if (setupFinished) return
        setupFinished = true
        writeTo(SVC, CHR_DNLD, byteArrayOf(CMD_ENABLE_DISCONNECT), withResponse = true)
    }

    // --- Measurement frames ---------------------------------------------------

    private fun onWeight(data: ByteArray) {
        flushPendingWeight()
        val frame = TerraillonLib.parseWeightFrame(data) ?: run {
            logW("Unusable weight frame: ${data.toHexPreview(20)}")
            return
        }
        val m = ScaleMeasurement().apply {
            dateTime = Date(frame.timestamp?.let(::deviceTimeToJava) ?: System.currentTimeMillis())
            this[MeasurementType.WEIGHT] = Kg(frame.weightKg)
            frame.impedanceOhm?.let { this[MeasurementType.IMPEDANCE] = Ohm(it) }
        }
        logD("Weight ${frame.weightKg} kg at ${m.dateTime}, impedance=${frame.impedanceOhm}, body follows=${frame.bodyFollows}")
        if (frame.bodyFollows && frame.timestamp != null) {
            pendingWeight = m
            pendingTimestamp = frame.timestamp
        } else {
            publishIfNew(m)
        }
    }

    private fun onBody(data: ByteArray) {
        val m = pendingWeight ?: run {
            logW("Body frame without a weight frame - dropping")
            return
        }
        val body = TerraillonLib.parseBodyFrame(data) ?: return
        if (body.timestamp != pendingTimestamp) {
            logW("Body timestamp ${body.timestamp} does not match weight $pendingTimestamp")
            flushPendingWeight()
            return
        }
        body.bmrKcal?.let { m[MeasurementType.BMR] = Kcal(it) }
        body.fatPercent?.let { m[MeasurementType.BODY_FAT] = Percent(it) }
        body.waterPercent?.let { m[MeasurementType.WATER] = Percent(it) }
        body.visceralFat?.let { m[MeasurementType.VISCERAL_FAT] = it }
        body.musclePercent?.let { m[MeasurementType.MUSCLE] = Percent(it) }
        body.boneKg?.let { m[MeasurementType.BONE] = Kg(it) }
        pendingWeight = null
        pendingTimestamp = null
        publishIfNew(m)
    }

    private fun flushPendingWeight() {
        pendingWeight?.let { publishIfNew(it) }
        pendingWeight = null
        pendingTimestamp = null
    }

    private fun publishIfNew(m: ScaleMeasurement) {
        val at = m.dateTime?.time ?: 0L
        if (at != 0L && !publishedAtMs.add(at)) return
        logD(
            "Publishing ${m.dateTime}: weight=${m[MeasurementType.WEIGHT]} fat=${m[MeasurementType.BODY_FAT]} " +
                "water=${m[MeasurementType.WATER]} muscle=${m[MeasurementType.MUSCLE]} bone=${m[MeasurementType.BONE]} " +
                "visceral=${m[MeasurementType.VISCERAL_FAT]} bmr=${m[MeasurementType.BMR]}"
        )
        publish(m)
    }

    private fun hexToBytes(hex: String): ByteArray? =
        if (hex.length != 8) null
        else runCatching { ByteArray(4) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()

    private fun writeCommand(opcode: Byte, payload: ByteArray) {
        writeTo(SVC, CHR_DNLD, byteArrayOf(opcode) + payload, withResponse = true)
    }

    // --- Time: the scale keeps local time, seconds since 2010-01-01 ---------------

    private fun tzOffsetSeconds(ms: Long): Int = TimeZone.getDefault().getOffset(ms) / 1000

    private fun javaTimeToDevice(ms: Long): Int =
        (((ms + 500) / 1000) - TS_OFFSET + tzOffsetSeconds(ms)).toInt()

    private fun deviceTimeToJava(seconds: Int): Long {
        val approx = 1000L * (TS_OFFSET + seconds.toLong())
        return approx - 1000L * tzOffsetSeconds(approx)
    }

    companion object {
        private val SVC: UUID = UUID.fromString("00007892-0000-1000-8000-00805f9b34fb")

        /**
         * Observed name "0" + "1376" + serial. Other Transtek scales use the first digit as a
         * pairing flag (0 paired, 1 pairing), so both are accepted.
         */
        private val NAME_PATTERN = Regex("^[01]1376\\d+$")
        private val DIGITS_PATTERN = Regex("^\\d{8,}$")

        private const val KEY_PASSWORD = "terraillon/password"
        private const val TS_OFFSET = 1262304000L
        private const val ECHO_FALLBACK_MS = 1_500L
    }
}
