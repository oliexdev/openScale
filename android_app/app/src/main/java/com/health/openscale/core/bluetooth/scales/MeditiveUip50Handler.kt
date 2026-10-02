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
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Meditive Smart Scale UIP-50 / Fitdays protocol.
 *
 * The UIP-50 uses the 20-byte AC 27 protocol on FFB0. Fitdays writes a D0 profile,
 * starts the session with DF 01, and writes the profile again. The scale streams D5
 * live-weight frames and later sends D6 with impedance; unlike some AC 27 devices, the
 * UIP-50 D6 frame does not repeat the weight, so the settled D5 value must be retained.
 */
class MeditiveUip50Handler : ScaleDeviceHandler() {

    private val service = uuid16(0xFFB0)
    private val configCharacteristic = uuid16(0xFFB1)
    private val dataCharacteristic = uuid16(0xFFB2)

    private var pendingWeightGrams = 0
    private var published = false
    private var disconnectJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.trim().equals("meditive", ignoreCase = true)) return null
        if (device.serviceUuids.none { it == service }) return null

        val capabilities = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
            DeviceCapability.TIME_SYNC,
        )
        return DeviceSupport(
            displayName = "Meditive Smart Scale UIP-50",
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        pendingWeightGrams = 0
        published = false
        disconnectJob?.cancel()
        disconnectJob = null

        setNotifyOn(service, dataCharacteristic)
        writeProfile(user)
        writeTo(service, configCharacteristic, buildSessionStart())
        writeProfile(user)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != dataCharacteristic) return

        when (val frame = parse(data)) {
            is Frame.LiveWeight -> {
                if (frame.weightGrams > 0) pendingWeightGrams = frame.weightGrams
            }

            is Frame.FinalResult -> {
                acknowledge(WIRE_FINAL_RESULT)
                val weightGrams = if (pendingWeightGrams > 0) pendingWeightGrams else frame.weightGrams
                if (published || weightGrams <= 0) return
                published = true
                publishMeasurement(user, weightGrams, frame.impedanceOhms)
                disconnectJob = scope.launch {
                    delay(DISCONNECT_DELAY_MS)
                    requestDisconnect()
                }
            }

            null -> logD("Ignoring invalid Meditive frame: ${data.toHexPreview(24)}")
        }
    }

    override fun onDisconnected() {
        disconnectJob?.cancel()
        disconnectJob = null
        if (!published && pendingWeightGrams > 0) {
            published = true
            publishMeasurement(currentAppUser(), pendingWeightGrams, impedanceOhms = 0)
        }
    }

    private fun publishMeasurement(user: ScaleUser, weightGrams: Int, impedanceOhms: Int) {
        val weightKg = weightGrams / 1000.0f
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            if (impedanceOhms > 0) {
                this[MeasurementType.IMPEDANCE] = Ohm(impedanceOhms.toFloat())
            }
        }
        publish(measurement)
    }

    private fun writeProfile(user: ScaleUser) {
        writeTo(
            service,
            configCharacteristic,
            buildProfile(
                nowEpochSeconds = System.currentTimeMillis() / 1000L,
                heightCm = user.bodyHeight.toInt(),
                age = user.age,
                sex = if (user.gender.isMale()) SEX_MALE else SEX_FEMALE,
            ),
        )
    }

    internal sealed class Frame {
        data class LiveWeight(val weightGrams: Int, val locked: Boolean) : Frame()
        data class FinalResult(val weightGrams: Int, val impedanceOhms: Int) : Frame()
    }

    companion object {
        private const val FRAME_SIZE = 20
        private const val HEADER_0 = 0xAC
        private const val HEADER_1 = 0x27
        private const val WIRE_LIVE_WEIGHT = 0xD5
        private const val WIRE_FINAL_RESULT = 0xD6
        private const val WIRE_PROFILE = 0xD0
        private const val WIRE_CONTROL = 0xDF
        private const val CMD_SESSION_START = 0x01
        private const val STATE_LOCKED = 0x80
        private const val WEIGHT_MASK = 0x3FFFF
        private const val SEX_MALE = 1
        private const val SEX_FEMALE = 2
        private const val DISCONNECT_DELAY_MS = 250L

        internal fun parse(raw: ByteArray): Frame? {
            if (raw.size != FRAME_SIZE) return null
            if ((raw[0].toInt() and 0xFF) != HEADER_0 || (raw[1].toInt() and 0xFF) != HEADER_1) return null
            if ((raw[19].toInt() and 0x1F) != checksum(raw)) return null

            return when (raw[18].toInt() and 0xFF) {
                WIRE_LIVE_WEIGHT -> Frame.LiveWeight(weightGrams(raw, 3), (raw[2].toInt() and STATE_LOCKED) != 0)
                WIRE_FINAL_RESULT -> Frame.FinalResult(weightGrams(raw, 8), u16(raw, 4))
                else -> null
            }
        }

        private fun checksum(raw: ByteArray): Int = (2 until 19).sumOf { raw[it].toInt() and 0xFF } and 0x1F

        private fun weightGrams(raw: ByteArray, at: Int): Int =
            ((((raw[at].toInt() and 0xFF) shl 16) or u16(raw, at + 1)) and WEIGHT_MASK)

        private fun u16(raw: ByteArray, at: Int): Int =
            ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)

        private fun buildProfile(nowEpochSeconds: Long, heightCm: Int, age: Int, sex: Int): ByteArray {
            val body = ByteArray(16)
            putU32(body, 0, nowEpochSeconds)
            body[4] = 0x08
            body[7] = heightCm.coerceIn(0, 255).toByte()
            body[10] = age.coerceIn(0, 255).toByte()
            body[11] = sex.toByte()
            body[14] = 0x03
            return buildPhoneFrame(body, WIRE_PROFILE)
        }

        private fun buildSessionStart(): ByteArray =
            buildPhoneFrame(byteArrayOf(CMD_SESSION_START.toByte()) + ByteArray(15), WIRE_CONTROL)

        private fun buildPhoneFrame(body: ByteArray, wireType: Int): ByteArray {
            val frame = ByteArray(FRAME_SIZE)
            frame[0] = HEADER_0.toByte()
            frame[1] = HEADER_1.toByte()
            body.copyInto(frame, 2)
            frame[18] = wireType.toByte()
            frame[19] = (2 until 19).sumOf { frame[it].toInt() and 0xFF }.toByte()
            return frame
        }

        private fun putU32(dst: ByteArray, at: Int, value: Long) {
            dst[at] = (value ushr 24).toByte()
            dst[at + 1] = (value ushr 16).toByte()
            dst[at + 2] = (value ushr 8).toByte()
            dst[at + 3] = value.toByte()
        }
    }

    private fun acknowledge(wireType: Int) {
        val body = byteArrayOf(0x04, wireType.toByte()) + ByteArray(14)
        writeTo(service, configCharacteristic, buildPhoneFrame(body, WIRE_CONTROL))
    }
}
