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
import com.health.openscale.core.bluetooth.libs.KeepS3BodyComposition
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Pure codec for the capture-verified Keep S3 application protocol. */
internal object KeepS3Protocol {
    const val FRAME_REQUEST = 0x01
    const val FRAME_RESPONSE = 0x02
    const val FRAME_EVENT = 0x03
    const val FRAME_ACK = 0x04
    const val MAGIC = 0x53
    const val STATUS_OK = 0x80

    const val OP_SET_TIME = 0x01
    const val OP_BATTERY = 0x03
    const val OP_SET_UNIT = 0x05
    const val OP_READ_TIME = 0x0A
    const val OP_UNKNOWN_20 = 0x20
    const val OP_PROFILE = 0x32
    const val OP_MEASUREMENT_CONTROL = 0x36
    const val OP_NEGOTIATE = 0x38
    const val OP_MEASUREMENT_EVENT = 0x57
    const val OP_FINAL_RECORD = 0x58
    const val OP_DEVICE_INFO = 0xE7
    const val OP_UNKNOWN_F5 = 0xF5

    const val STAGE_FINAL = 0x29

    private const val PROFILE_PAYLOAD_SIZE = 63
    private const val CONTROL_PAYLOAD_SIZE = 50
    private const val TOKEN_SIZE = 24
    private const val TOKEN_PAIR_SIZE = TOKEN_SIZE * 2

    data class FrameHeader(val type: Int, val opcode: Int, val payloadLength: Int)
    data class Response(val opcode: Int, val data: ByteArray)
    data class MeasurementEvent(
        val stage: Int,
        val weightKg: Float,
        val impedanceOhm: Int,
        val heartRateBpm: Int,
    )

    data class FinalRecord(
        val weightKg: Float,
        val encodedImpedance50: Int,
        val encodedImpedance100: Int,
        val impedance50Ohm: Int?,
        val impedance100Ohm: Int?,
        val phaseAngle50Raw: Int,
        val phaseAngle100Raw: Int,
        val phaseAngle50Degrees: Float?,
        val phaseAngle100Degrees: Float?,
        val heartRateBpm: Int,
    )

    data class PreviousRecord(
        val weightKg: Float = 0f,
        val timestampSeconds: Long = 0L,
        val impedanceOhm: Double = 0.0,
    )

    data class PersistedDeviceImpedance(
        val timestampSeconds: Long,
        val weightRaw: Int,
        val impedanceOhm: Int,
    )

    /** Read only the non-sensitive frame identity. Payload validity is checked separately. */
    fun peekFrameHeader(frame: ByteArray): FrameHeader? {
        if (frame.size < 5) return null
        if (u8(frame[1]) != MAGIC || u8(frame[3]) != 0) return null
        val type = u8(frame[0])
        if (type !in FRAME_REQUEST..FRAME_ACK) return null
        return FrameHeader(type, u8(frame[2]), u8(frame[4]))
    }

    /** Validate the complete frame, including the type-specific declared payload length. */
    fun parseFrameHeader(frame: ByteArray): FrameHeader? {
        val header = peekFrameHeader(frame) ?: return null
        val expectedSize = when (header.type) {
            FRAME_RESPONSE -> 6 + header.payloadLength // status byte is not included in data length
            FRAME_ACK -> 6 + header.payloadLength
            else -> 5 + header.payloadLength
        }
        return header.takeIf { frame.size == expectedSize }
    }

    fun parseResponse(frame: ByteArray): Response? {
        val header = parseFrameHeader(frame) ?: return null
        if (header.type != FRAME_RESPONSE || u8(frame[5]) != STATUS_OK) return null
        return Response(header.opcode, frame.copyOfRange(6, frame.size))
    }

    fun parseMeasurementEvent(frame: ByteArray): MeasurementEvent? {
        val header = parseFrameHeader(frame) ?: return null
        if (header.type != FRAME_EVENT || header.opcode != OP_MEASUREMENT_EVENT) return null
        if (header.payloadLength != 8 || frame.size != 13) return null

        return MeasurementEvent(
            stage = u8(frame[5]),
            weightKg = decodeU16BE(frame, 6) / 200.0f,
            impedanceOhm = decodeU16BE(frame, 10),
            heartRateBpm = u8(frame[12]),
        )
    }

    fun parseFinalRecord(frame: ByteArray): FinalRecord? {
        val header = parseFrameHeader(frame) ?: return null
        if (header.type != FRAME_EVENT || header.opcode != OP_FINAL_RECORD) return null
        if (header.payloadLength != 61 || frame.size != 66) return null

        val encodedImpedance50 = decodeU24BE(frame, 55)
        val encodedImpedance100 = decodeU24BE(frame, 58)
        val phaseAngle50Raw = decodeI16BE(frame, 61)
        val phaseAngle100Raw = decodeI16BE(frame, 63)
        return FinalRecord(
            weightKg = decodeU16BE(frame, 53) / 200.0f,
            encodedImpedance50 = encodedImpedance50,
            encodedImpedance100 = encodedImpedance100,
            impedance50Ohm = decodeEncodedImpedance(encodedImpedance50),
            impedance100Ohm = decodeEncodedImpedance(encodedImpedance100),
            phaseAngle50Raw = phaseAngle50Raw,
            phaseAngle100Raw = phaseAngle100Raw,
            phaseAngle50Degrees = decodePhaseAngleDegrees(phaseAngle50Raw),
            phaseAngle100Degrees = decodePhaseAngleDegrees(phaseAngle100Raw),
            heartRateBpm = u8(frame[65]),
        )
    }

    fun buildRequest(opcode: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(opcode in 0..0xFF) { "opcode must fit in one byte" }
        require(payload.size <= 0xFF) { "payload is too large" }
        return byteArrayOf(
            FRAME_REQUEST.toByte(),
            MAGIC.toByte(),
            opcode.toByte(),
            0x00,
            payload.size.toByte(),
        ) + payload
    }

    fun buildAck(opcode: Int): ByteArray {
        require(opcode in 0..0xFF) { "opcode must fit in one byte" }
        return byteArrayOf(
            FRAME_ACK.toByte(),
            MAGIC.toByte(),
            opcode.toByte(),
            0x00,
            0x00,
            STATUS_OK.toByte(),
        )
    }

    fun buildProfilePayload(
        token: String,
        previous: PreviousRecord?,
        heightCm: Int,
        birthYear: Int,
        birthMonth: Int,
        birthDay: Int,
    ): ByteArray {
        require(validateToken(token)) { "invalid Keep S3 ID24 token" }
        val payload = ByteArray(PROFILE_PAYLOAD_SIZE)
        repeatedTokenBytes(token).copyInto(payload)

        val record = previous ?: PreviousRecord()
        encodeU16BE(payload, 48, encodeWeightRaw(record.weightKg))
        encodeU32BE(payload, 50, record.timestampSeconds.coerceIn(0L, 0xFFFF_FFFFL))
        encodeU16BE(payload, 54, encodeImpedanceRaw(record.impedanceOhm))
        encodeU16BE(payload, 56, 0) // Capture-verified reserved field; semantic is unknown.
        payload[58] = heightCm.coerceIn(0, 0xFF).toByte()
        encodeU16BE(payload, 59, birthYear.coerceIn(0, 0xFFFF))
        payload[61] = birthMonth.coerceIn(1, 12).toByte()
        payload[62] = birthDay.coerceIn(1, 31).toByte()
        return payload
    }

    fun buildMeasurementControl(token: String, start: Boolean): ByteArray {
        require(validateToken(token)) { "invalid Keep S3 ID24 token" }
        val payload = repeatedTokenBytes(token) + byteArrayOf(0x00, if (start) 0x01 else 0x00)
        check(payload.size == CONTROL_PAYLOAD_SIZE)
        return payload
    }

    fun repeatedTokenBytes(token: String): ByteArray {
        require(validateToken(token)) { "invalid Keep S3 ID24 token" }
        val bytes = (token + token).encodeToByteArray()
        check(bytes.size == TOKEN_PAIR_SIZE)
        return bytes
    }

    fun validateToken(token: String): Boolean =
        token.length == TOKEN_SIZE && token.all { it in '0'..'9' || it in 'a'..'f' }

    /** Generate an opaque token from exactly 12 random bytes; the vendor algorithm is unknown. */
    fun generateToken(randomBytes: ByteArray): String {
        require(randomBytes.size == 12) { "Keep S3 ID24 requires 12 random bytes" }
        return randomBytes.joinToString(separator = "") {
            String.format(Locale.ROOT, "%02x", u8(it))
        }
    }

    fun tokenSettingKey(userId: Int): String = "id24-user-$userId"

    fun deviceImpedanceSettingKey(userId: Int): String = "previous-device-impedance-user-$userId"

    fun serializeDeviceImpedance(
        timestampSeconds: Long,
        weightKg: Float,
        impedanceOhm: Int,
    ): String? {
        if (timestampSeconds !in 1L..0xFFFF_FFFFL || impedanceOhm !in 1..0xFFFF) return null
        val weightRaw = encodeWeightRaw(weightKg).takeIf { it > 0 } ?: return null
        return "$timestampSeconds:$weightRaw:$impedanceOhm"
    }

    fun parseDeviceImpedance(value: String?): PersistedDeviceImpedance? {
        val fields = value?.split(':') ?: return null
        if (fields.size != 3) return null
        val timestampSeconds = fields[0].toLongOrNull()?.takeIf { it in 1L..0xFFFF_FFFFL }
            ?: return null
        val weightRaw = fields[1].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null
        val impedanceOhm = fields[2].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null
        return PersistedDeviceImpedance(timestampSeconds, weightRaw, impedanceOhm)
    }

    fun deviceImpedanceMatches(
        stored: PersistedDeviceImpedance,
        timestampSeconds: Long,
        weightKg: Float,
    ): Boolean = stored.timestampSeconds == timestampSeconds &&
        stored.weightRaw == encodeWeightRaw(weightKg)

    fun encodeU16BE(target: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset + 2 <= target.size)
        val clamped = value.coerceIn(0, 0xFFFF)
        target[offset] = (clamped ushr 8).toByte()
        target[offset + 1] = clamped.toByte()
    }

    fun encodeU32BE(target: ByteArray, offset: Int, value: Long) {
        require(offset >= 0 && offset + 4 <= target.size)
        val clamped = value.coerceIn(0L, 0xFFFF_FFFFL)
        target[offset] = (clamped ushr 24).toByte()
        target[offset + 1] = (clamped ushr 16).toByte()
        target[offset + 2] = (clamped ushr 8).toByte()
        target[offset + 3] = clamped.toByte()
    }

    fun decodeU16BE(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= data.size)
        return (u8(data[offset]) shl 8) or u8(data[offset + 1])
    }

    fun decodeU32BE(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= data.size)
        return (u8(data[offset]).toLong() shl 24) or
            (u8(data[offset + 1]).toLong() shl 16) or
            (u8(data[offset + 2]).toLong() shl 8) or
            u8(data[offset + 3]).toLong()
    }

    fun decodeU24BE(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 3 <= data.size)
        return (u8(data[offset]) shl 16) or
            (u8(data[offset + 1]) shl 8) or
            u8(data[offset + 2])
    }

    fun decodeI16BE(data: ByteArray, offset: Int): Int {
        val unsigned = decodeU16BE(data, offset)
        return if (unsigned and 0x8000 != 0) unsigned - 0x1_0000 else unsigned
    }

    /** Decoder recovered from the APK's BhGetBodyComposition_TwoLegs240 input path. */
    fun decodeEncodedImpedance(encoded: Int): Int? {
        if (encoded !in 0..0xFF_FFFF || encoded == 0xFF_FFFF) return null
        val upper = (encoded and 0x0F00) or ((encoded ushr 16) and 0xFF)
        val lower = ((encoded and 0xFF) shl 2) + ((encoded ushr 12) and 0x0F)
        val decoded = (upper - lower) / 2 // Kotlin division truncates toward zero, like the SDK.
        return decoded.takeIf { it in 200..1200 }
    }

    /** Both captures and the APK's native call path encode phase angle as negative tenths. */
    fun decodePhaseAngleDegrees(raw: Int): Float? =
        if (raw in Short.MIN_VALUE until 0) -raw / 10.0f else null

    private fun encodeWeightRaw(weightKg: Float): Int {
        if (!weightKg.isFinite() || weightKg <= 0f) return 0
        return (weightKg.toDouble() * 200.0).roundToInt().coerceIn(0, 0xFFFF)
    }

    private fun encodeImpedanceRaw(impedanceOhm: Double): Int {
        if (!impedanceOhm.isFinite() || impedanceOhm <= 0.0) return 0
        return impedanceOhm.roundToInt().coerceIn(0, 0xFFFF)
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}

internal enum class KeepS3InitStep(val opcode: Int) {
    NEGOTIATE(KeepS3Protocol.OP_NEGOTIATE),
    READ_TIME(KeepS3Protocol.OP_READ_TIME),
    SET_TIME(KeepS3Protocol.OP_SET_TIME),
    SET_UNIT(KeepS3Protocol.OP_SET_UNIT),
    DEVICE_INFO(KeepS3Protocol.OP_DEVICE_INFO),
    UNKNOWN_F5_FIRST(KeepS3Protocol.OP_UNKNOWN_F5),
    UNKNOWN_F5_SECOND(KeepS3Protocol.OP_UNKNOWN_F5),
    BATTERY(KeepS3Protocol.OP_BATTERY),
    UNKNOWN_20(KeepS3Protocol.OP_UNKNOWN_20),
    PROFILE(KeepS3Protocol.OP_PROFILE),
    START(KeepS3Protocol.OP_MEASUREMENT_CONTROL),
}

/** Response-driven initialization state. No transition occurs for a foreign opcode. */
internal class KeepS3InitStateMachine {
    private var index = 0

    val expectedStep: KeepS3InitStep?
        get() = KeepS3InitStep.entries.getOrNull(index)

    fun reset(): KeepS3InitStep {
        index = 0
        return KeepS3InitStep.entries[index]
    }

    fun acceptSuccessfulResponse(opcode: Int): KeepS3InitStep? {
        val expected = expectedStep ?: return null
        if (expected.opcode != opcode) return null
        index++
        return expectedStep
    }
}

/**
 * Keep S3 handler for the capture-verified 0x00FF / FF01 / FF02 protocol.
 *
 * The device also exposes 0xFFF0, but both captured official-app sessions exclusively used
 * FF01 notifications and FF02 Write With Response. Body-composition values are calculated offline
 * by the SDK-compatible routine bundled in Keep 9.0.80; Keep's cloud report differs slightly.
 */
class KeepS3Handler : ScaleDeviceHandler() {
    private val service: UUID = uuid16(0x00FF)
    private val notifyCharacteristic: UUID = uuid16(0xFF01)
    private val writeCharacteristic: UUID = uuid16(0xFF02)

    private val initState = KeepS3InitStateMachine()
    private var currentUser: ScaleUser? = null
    private var token = ""
    private var published = false
    private var finishing = false
    private var latestImpedanceOhm = 0
    private var pendingFinalEvent: KeepS3Protocol.MeasurementEvent? = null
    private var finalPublishJob: Job? = null
    private var finishJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.equals(DEVICE_NAME, ignoreCase = true)) return null

        if (device.serviceUuids.contains(service)) {
            logD("Keep S3 matched by exact name with advertised 0x00FF service")
        } else {
            // Saved/incomplete scan results do not always carry advertised service UUIDs.
            logD("Keep S3 matched by exact name; advertised 0x00FF service was not present")
        }

        return DEVICE_SUPPORT
    }

    override fun onConnected(user: ScaleUser) {
        resetSession()
        currentUser = user
        token = loadOrCreateToken(user.id)

        // GattScaleAdapter queues these operations, so the first request follows notify setup.
        setNotifyOn(service, notifyCharacteristic)
        sendStep(initState.reset(), responseData = byteArrayOf())
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic) return

        val identity = KeepS3Protocol.peekFrameHeader(data)
        if (identity == null) {
            logMalformedFrame(data)
            return
        }

        when (identity.type) {
            KeepS3Protocol.FRAME_RESPONSE -> handleResponse(data, user)
            KeepS3Protocol.FRAME_EVENT -> handleEvent(identity, data, user)
            else -> logW("Ignoring unexpected frame type=${identity.type} opcode=0x${identity.opcode.toString(16)} len=${data.size}")
        }
    }

    override fun onDisconnected() {
        publishPendingFinalWithoutRecord()
        resetSession()
    }

    private fun handleResponse(frame: ByteArray, user: ScaleUser) {
        val response = KeepS3Protocol.parseResponse(frame)
        if (response == null) {
            logMalformedFrame(frame)
            return
        }

        val completedStep = initState.expectedStep
        if (completedStep == null) {
            logD("Ignoring response after initialization opcode=0x${response.opcode.toString(16)}")
            return
        }
        if (completedStep.opcode != response.opcode) {
            logW(
                "Ignoring out-of-order response opcode=0x${response.opcode.toString(16)} " +
                    "expected=0x${completedStep.opcode.toString(16)}",
            )
            return
        }

        val nextStep = initState.acceptSuccessfulResponse(response.opcode)
        if (completedStep == KeepS3InitStep.BATTERY && response.data.size == 1) {
            logI("Keep S3 battery level=${response.data[0].toInt() and 0xFF}% (no battery callback API)")
        }

        if (nextStep == null) {
            logI("Keep S3 initialization completed")
            return
        }
        sendStep(nextStep, response.data, user)
    }

    private fun sendStep(
        step: KeepS3InitStep,
        responseData: ByteArray,
        user: ScaleUser? = currentUser,
    ) {
        val activeUser = user ?: return
        val payload = when (step) {
            KeepS3InitStep.SET_TIME -> {
                if (responseData.size == 4) {
                    responseData
                } else {
                    logW("Scale time response had no four-byte timestamp; using current Unix time")
                    unixTimeBytes()
                }
            }

            KeepS3InitStep.SET_UNIT -> byteArrayOf(0x00) // Capture strongly supports 0x00 = kg.
            KeepS3InitStep.PROFILE -> buildProfilePayload(activeUser)
            KeepS3InitStep.START -> KeepS3Protocol.buildMeasurementControl(token, start = true)
            else -> byteArrayOf()
        }

        writeTo(
            service,
            writeCharacteristic,
            KeepS3Protocol.buildRequest(step.opcode, payload),
            withResponse = true,
        )

        if (step == KeepS3InitStep.START) {
            userInfo(R.string.bt_info_step_on_scale)
        }
    }

    private fun handleEvent(header: KeepS3Protocol.FrameHeader, frame: ByteArray, user: ScaleUser) {
        when (header.opcode) {
            KeepS3Protocol.OP_MEASUREMENT_EVENT -> {
                // ACK from the verified envelope before attempting to parse the event payload.
                sendAck(KeepS3Protocol.OP_MEASUREMENT_EVENT)
                val event = KeepS3Protocol.parseMeasurementEvent(frame)
                if (event == null) {
                    logMalformedFrame(frame)
                    return
                }
                handleMeasurementEvent(event, user)
            }

            KeepS3Protocol.OP_FINAL_RECORD -> {
                sendAck(KeepS3Protocol.OP_FINAL_RECORD)
                val record = KeepS3Protocol.parseFinalRecord(frame)
                if (record == null) {
                    logMalformedFrame(frame)
                    return
                }
                finalPublishJob?.cancel()
                finalPublishJob = null
                val finalEvent = pendingFinalEvent
                logD(
                    "Keep S3 final record impedance50=${record.impedance50Ohm ?: "invalid"}Ω " +
                        "impedance100=${record.impedance100Ohm ?: "invalid"}Ω " +
                        "phase50=${record.phaseAngle50Degrees ?: "invalid"}° " +
                        "phase100=${record.phaseAngle100Degrees ?: "invalid"}°",
                )
                publishOnce(
                    user = user,
                    weightKg = finalEvent?.weightKg ?: record.weightKg,
                    deviceImpedanceOhm = finalEvent?.impedanceOhm ?: latestImpedanceOhm,
                    heartRateBpm = finalEvent?.heartRateBpm?.takeIf { it > 0 }
                        ?: record.heartRateBpm,
                    phaseAngle50Degrees = record.phaseAngle50Degrees,
                    phaseAngle100Degrees = record.phaseAngle100Degrees,
                    compositionImpedance50Ohm = record.impedance50Ohm,
                    compositionImpedance100Ohm = record.impedance100Ohm,
                )
                finishSession()
            }

            else -> logD("Ignoring unknown event opcode=0x${header.opcode.toString(16)} len=${frame.size}")
        }
    }

    private fun handleMeasurementEvent(event: KeepS3Protocol.MeasurementEvent, user: ScaleUser) {
        if (event.impedanceOhm > 0) latestImpedanceOhm = event.impedanceOhm

        // Only 0x29 has a capture-verified final-result meaning. Other stage labels are unknown.
        if (event.stage == KeepS3Protocol.STAGE_FINAL) {
            pendingFinalEvent = event
            if (finalPublishJob == null) {
                finalPublishJob = scope.launch {
                    delay(FINAL_RECORD_WAIT_MS)
                    publishPendingFinalWithoutRecord()
                    finalPublishJob = null
                }
            }
        } else if (event.stage == 0x00 || event.stage == 0x01) {
            // These two progress stages carry live weight; their formal vendor names are unknown.
            if (event.weightKg > 0f) {
                userInfo(R.string.bluetooth_scale_info_measuring_weight, event.weightKg)
            }
        }
    }

    private fun publishPendingFinalWithoutRecord() {
        val user = currentUser ?: return
        val event = pendingFinalEvent ?: return
        publishOnce(user, event.weightKg, event.impedanceOhm, event.heartRateBpm)
    }

    private fun publishOnce(
        user: ScaleUser,
        weightKg: Float,
        deviceImpedanceOhm: Int,
        heartRateBpm: Int,
        phaseAngle50Degrees: Float? = null,
        phaseAngle100Degrees: Float? = null,
        compositionImpedance50Ohm: Int? = null,
        compositionImpedance100Ohm: Int? = null,
    ) {
        if (published || !weightKg.isFinite() || weightKg <= 0f) return

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            weight = weightKg
            if (heartRateBpm > 0) heartRate = heartRateBpm
            // openScale has no measurement type for the vendor impedance or the phase angles,
            // so they are decoded and logged but not published. Re-enabling any of these needs
            // a MeasurementTypeKey, a ScaleMeasurement field, a DB migration and BleConnector
            // wiring.
            // if (deviceImpedanceOhm > 0) deviceImpedance = deviceImpedanceOhm.toDouble()
            // phaseAngle50Degrees?.takeIf { it.isFinite() && it > 0f }?.let { phaseAngle = it }
            // phaseAngle100Degrees?.takeIf { it.isFinite() && it > 0f }?.let { phaseAngleHigh = it }

            val hasDualFrequencyImpedance =
                compositionImpedance50Ohm != null && compositionImpedance100Ohm != null
            if (hasDualFrequencyImpedance) {
                // ScaleMeasurement's established dual-band convention is high frequency in
                // impedance and low frequency in impedanceLow. Keep S3 reports 100/50 kHz.
                impedance = compositionImpedance100Ohm.toDouble()
                impedanceLow = compositionImpedance50Ohm.toDouble()
            }

            val composition = if (hasDualFrequencyImpedance) {
                KeepS3BodyComposition.calculate(
                    KeepS3BodyComposition.Input(
                        gender = user.gender,
                        age = user.age,
                        heightCm = if (user.bodyHeight.isFinite()) {
                            user.bodyHeight.roundToInt()
                        } else {
                            0
                        },
                        weightKg = weightKg,
                        impedance50Ohm = compositionImpedance50Ohm,
                        impedance100Ohm = compositionImpedance100Ohm,
                        // openScale's activity level is a TDEE multiplier, not the Keep SDK's
                        // athlete body-type flag. Capture/native API evidence does not establish
                        // an equivalence, so keep the vendor flag off until there is an explicit,
                        // user-controlled Keep setting.
                        athlete = false,
                    ),
                )
            } else {
                null
            }
            if (composition == null) {
                logW("Keep S3 body composition unavailable; missing or invalid dual-frequency input")
            } else {
                fat = composition.bodyFatPercent
                water = composition.waterPercent
                visceralFat = composition.visceralFatLevel.toFloat()
                bone = composition.boneKg
                lbm = composition.fatFreeMassKg
                bmr = composition.basalMetabolicRateKcal.toFloat()
                protein = composition.proteinPercent
                // openScale evaluates MUSCLE as a skeletal-muscle percentage (plausible range
                // 15-60%). Keep's broader "muscle" value is FFM minus bone and can exceed that
                // range, so publish the separately decoded skeletal-muscle percentage here.
                muscle = composition.skeletalMusclePercent
                // Keep's broader composition.musclePercent remains decoded by the model but is
                // not published because openScale has no lean-soft-tissue measurement type.
                // Not published — openScale has no measurement type for these.
                // subcutaneousFat = composition.subcutaneousFatPercent
                // bodyAge = composition.bodyAge
                // bmi22ReferenceWeight = composition.bmi22ReferenceWeightKg
                logI("Keep S3 body composition calculated with offline BHKeep SDK-compatible model")
            }
        }
        publish(measurement)
        rememberDeviceImpedance(user.id, measurement, deviceImpedanceOhm)
        published = true
    }

    private fun finishSession() {
        if (finishing) return
        finishing = true

        val stopPayload = KeepS3Protocol.buildMeasurementControl(token, start = false)
        val stopRequest = KeepS3Protocol.buildRequest(KeepS3Protocol.OP_MEASUREMENT_CONTROL, stopPayload)
        writeTo(service, writeCharacteristic, stopRequest, withResponse = true)
        writeTo(service, writeCharacteristic, stopRequest, withResponse = true)

        finishJob = scope.launch {
            // The scale can emit more than 140 measurement events per session and leave a
            // double-digit ACK backlog when the final record arrives. Allow that backlog, the
            // 0x58 ACK and both stop commands to drain before disconnecting. The stop command
            // is sent twice so a dropped one is not fatal.
            delay(DISCONNECT_DELAY_MS)
            requestDisconnect()
        }
    }

    /**
     * Isolates the first-measurement all-zero policy from the wire codec. A Keep S3 accepted
     * this fallback during hardware validation; the vendor-defined semantics remain unknown.
     */
    private fun previousRecordFor(user: ScaleUser): KeepS3Protocol.PreviousRecord? {
        val previous = lastMeasurementFor(user.id)
        if (previous == null) {
            logI("No previous measurement for user ${user.id}; using all-zero previous record")
            return null
        }
        val impedanceOhm = previousDeviceImpedance(user.id, previous)
        if (impedanceOhm == null) {
            // Reusing impedance/impedanceLow would send a decoded 100/50 kHz band, while the
            // captured official-app profile uses the distinct impedance from the 0x57 event.
            logW("No matching Keep S3 protocol impedance; using all-zero previous record")
            return null
        }
        logI("Keep S3 previous record uses matched protocol impedance=${impedanceOhm.roundToInt()}Ω")
        return KeepS3Protocol.PreviousRecord(
            weightKg = previous.weight,
            timestampSeconds = (previous.dateTime?.time ?: 0L) / 1000L,
            impedanceOhm = impedanceOhm,
        )
    }

    private fun rememberDeviceImpedance(
        userId: Int,
        measurement: ScaleMeasurement,
        deviceImpedanceOhm: Int,
    ) {
        val timestampSeconds = measurement.dateTime?.time?.div(1000L) ?: return
        val encoded = KeepS3Protocol.serializeDeviceImpedance(
            timestampSeconds = timestampSeconds,
            weightKg = measurement.weight,
            impedanceOhm = deviceImpedanceOhm,
        ) ?: return
        settingsPutString(KeepS3Protocol.deviceImpedanceSettingKey(userId), encoded)
    }

    private fun previousDeviceImpedance(userId: Int, previous: ScaleMeasurement): Double? {
        val timestampSeconds = previous.dateTime?.time?.div(1000L) ?: return null
        val stored = KeepS3Protocol.parseDeviceImpedance(
            settingsGetString(KeepS3Protocol.deviceImpedanceSettingKey(userId)),
        ) ?: return null
        return stored.impedanceOhm.toDouble().takeIf {
            KeepS3Protocol.deviceImpedanceMatches(stored, timestampSeconds, previous.weight)
        }
    }

    private fun buildProfilePayload(user: ScaleUser): ByteArray {
        val birthday = Calendar.getInstance().apply { time = user.birthday }
        val heightCm = if (user.bodyHeight.isFinite()) user.bodyHeight.roundToInt() else 0
        return KeepS3Protocol.buildProfilePayload(
            token = token,
            previous = previousRecordFor(user),
            heightCm = heightCm,
            birthYear = birthday.get(Calendar.YEAR),
            birthMonth = birthday.get(Calendar.MONTH) + 1,
            birthDay = birthday.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun loadOrCreateToken(userId: Int): String {
        val key = KeepS3Protocol.tokenSettingKey(userId)
        val stored = settingsGetString(key)
        val normalized = stored?.lowercase(Locale.ROOT)
        if (normalized != null && KeepS3Protocol.validateToken(normalized)) {
            if (stored != normalized) settingsPutString(key, normalized)
            return normalized
        }

        val randomBytes = ByteArray(12).also(SecureRandom()::nextBytes)
        return KeepS3Protocol.generateToken(randomBytes).also { settingsPutString(key, it) }
    }

    private fun sendAck(opcode: Int) {
        writeTo(
            service,
            writeCharacteristic,
            KeepS3Protocol.buildAck(opcode),
            withResponse = true,
        )
    }

    private fun unixTimeBytes(): ByteArray = ByteArray(4).also {
        KeepS3Protocol.encodeU32BE(it, 0, System.currentTimeMillis() / 1000L)
    }

    private fun logMalformedFrame(frame: ByteArray) {
        val type = frame.getOrNull(0)?.toInt()?.and(0xFF)
        val opcode = frame.getOrNull(2)?.toInt()?.and(0xFF)
        logW("Ignoring malformed Keep S3 frame len=${frame.size} type=$type opcode=$opcode")
    }

    private fun resetSession() {
        finalPublishJob?.cancel()
        finalPublishJob = null
        finishJob?.cancel()
        finishJob = null
        currentUser = null
        token = ""
        published = false
        finishing = false
        latestImpedanceOhm = 0
        pendingFinalEvent = null
    }

    companion object {
        private const val DEVICE_NAME = "Keep_S3"
        private const val FINAL_RECORD_WAIT_MS = 2_000L
        private const val DISCONNECT_DELAY_MS = 6_000L

        private val DEVICE_SUPPORT = DeviceSupport(
            displayName = "Keep S3",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.BATTERY_LEVEL,
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG,
                DeviceCapability.BODY_COMPOSITION,
            ),
            tuningProfile = TuningProfile.Balanced,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }
}
