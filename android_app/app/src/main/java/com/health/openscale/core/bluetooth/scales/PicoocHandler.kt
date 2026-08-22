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
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Picooc (有品) body fat scales that speak the connection-oriented BLE protocol:
 * Latin-S, PICOOC-C1…CQ, PICOOC-14…25, PICOOC-IS and the S3 Lite V2.0.
 *
 * Protocol from a public analysis of a decompiled Picooc app v4.13.4
 * (https://garden.1900.live/22-knowledge/开发/picooc体脂秤ble协议分析):
 *  - Service 0xFFF0, notify characteristic 0xFFF1, write characteristic 0xFFF2.
 *  - Scale→app frames are `[opcode][length][payload…]`, big-endian throughout.
 *  - App→scale frames are prefixed with 0xF1 (Latin family) or 0xA1 (newer models, the ones
 *    that use opcodes 0x51/0x52 in place of 0x3A/0x39); `length` counts the prefix as well.
 *  - Handshake: scale sends 0x3A (or 0x51) asking for the time, the app answers with a
 *    10-byte time frame; the scale then asks for the user profile with 0x30, which the app
 *    acks and follows ~100 ms later with a 6-byte sex/height/age frame. Only then does the
 *    scale run its BIA engine and stream 0x39 (or 0x52) frames. It hangs up on its own once
 *    the measurement is done.
 *
 * The Picooc app computes body composition on the phone (FatModelCaculate) rather than reading
 * it off the scale, so 0x32 is not guaranteed to arrive. When it does we trust its values and
 * only fill the gaps from [StandardImpedanceLib]; when it does not, the whole composition is
 * derived from the streamed impedance.
 *
 * Several documented opcodes have no published byte layout (0x36/0x37 history, 0x3E
 * multi-frequency BIA, 0x3F balance test). Those are acked where an ack is expected and dumped
 * to the log verbatim so a real capture can be used to decode them later. Every frame in both
 * directions is logged in full hex under the "Picooc RX"/"Picooc TX" prefixes, so
 * `adb logcat | grep -i picooc` yields a complete session dump.
 */
class PicoocHandler : ScaleDeviceHandler() {

    private val service = uuid16(0xFFF0)
    private val notifyCharacteristic = uuid16(0xFFF1)
    private val writeCharacteristic = uuid16(0xFFF2)

    /** 0xF1 for the Latin family; latched to 0xA1 as soon as the scale uses a 0x5x opcode. */
    private var ackPrefix = PREFIX_LATIN

    private var pendingWeightKg = 0f
    private var pendingImpedance = 0.0
    private var pendingHeartRate = 0
    private var pendingComposition: Composition? = null
    private var published = false
    private var profilePushed = false
    private var settleJob: Job? = null

    /** Latched in [onConnected] so [onDisconnected] can still publish after the link is gone. */
    private var sessionUser: ScaleUser? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)

        // PICOOC-L (Mini and the other new models) is broadcast-only: it carries its payload in
        // the advertisement and never accepts a GATT connection. Leave the name unclaimed so a
        // future broadcast handler can take it, rather than failing to connect here.
        if (name.startsWith("picooc-l")) return null

        // The connection-oriented line advertises either "PICOOC-<model>" (C1…CQ, 14…25, IS,
        // and the S3 Lite V2.0 which shows up as PICOOC-CQ) or, for the Latin series, a bare
        // "Latin-…" with no vendor prefix at all. No other handler in this package claims
        // either prefix.
        val isPicooc = name.startsWith("picooc")
        val isLatin = name.startsWith("latin-")
        if (!isPicooc && !isLatin) return null

        return DeviceSupport(
            // Show the advertised model: the line spans a lot of hardware and the name is the
            // only thing that tells two of them apart in the device list.
            displayName = if (isPicooc) device.name else "Picooc ${device.name}",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG,
                DeviceCapability.HISTORY_READ,
                DeviceCapability.BATTERY_LEVEL
            ),
            // History frames are logged but not decoded yet, and the scale only reports that the
            // battery is low, never a level.
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG
            ),
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        resetSession()
        sessionUser = user
        logI("Picooc: connected, subscribing to 0xFFF1")
        setNotifyOn(service, notifyCharacteristic)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic || data.isEmpty()) return

        val opcode = data[0].toInt() and 0xFF
        logI("Picooc RX 0x%02X len=%d | %s".format(opcode, data.size, hex(data)))

        when (opcode) {
            OP_TIME_REQUEST_ALT -> {
                // Only the newer models use 0x5x opcodes, and those expect the 0xA1 prefix.
                ackPrefix = PREFIX_MODERN
                handleTimeRequest(user, OP_HANDSHAKE)
                pushUserProfileOnce(user)
            }

            OP_HANDSHAKE -> {
                parseBomInfo(data)?.let { logI("Picooc BOM info: $it") }
                handleTimeRequest(user, OP_HANDSHAKE)
                pushUserProfileOnce(user)
            }

            OP_TIME_SYNC -> handleTimeRequest(user, OP_TIME_SYNC)

            OP_USER_REQUEST -> {
                send(buildAck(ackPrefix, OP_USER_REQUEST), "ack 0x30")
                sendUserProfile(user)
            }

            OP_LIVE_WEIGHT_ALT -> {
                ackPrefix = PREFIX_MODERN
                handleLiveFrame(data, opcode, user)
            }

            OP_LIVE_WEIGHT -> handleLiveFrame(data, opcode, user)

            OP_BODY_COMPOSITION -> {
                send(buildAck(ackPrefix, OP_BODY_COMPOSITION), "ack 0x32")
                val composition = parseComposition(data)
                if (composition == null) {
                    logW("Picooc 0x32 too short to decode (len=${data.size})")
                    return
                }
                logI("Picooc composition: $composition")
                pendingComposition = composition
                if (composition.weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG) {
                    pendingWeightKg = composition.weightKg
                }
                if (composition.impedance > 0.0) pendingImpedance = composition.impedance
                armSettle(user, "0x32 body composition")
            }

            OP_HEART_RATE -> {
                // Acked like the weight frames are. A PICOOC-CQ capture without this ack shows
                // the scale resending a byte-identical `3C 05 00 00 01` ten times at exactly
                // 1 Hz and then hanging up, while the acked 0x39 arrives exactly once — the
                // signature of an unacknowledged retransmit. The scale's own display had a
                // heart rate the whole time, so the result frame is one the scale would only
                // move on to once the previous one is confirmed.
                send(buildAck(ackPrefix, OP_HEART_RATE), "ack 0x3C")
                // Only the PICOOC-CQ layout is confirmed; dump the alternatives so a capture
                // from another model shows at a glance where its reading sits.
                logD(
                    "Picooc 0x3C candidates: " +
                        heartRateCandidates(data).joinToString { "${it.first}=${it.second}" }
                )
                val bpm = parseHeartRate(data)
                if (bpm != null) {
                    logI("Picooc heart rate: $bpm bpm (status=${heartRateStatus(data)})")
                    pendingHeartRate = bpm
                } else {
                    logD("Picooc heart rate still measuring | ${hex(data)}")
                }
                // Re-arm either way: the reading is still running, and publishing now would
                // close the record before a result can arrive.
                armSettle(user, "0x3C heart rate")
            }

            OP_LOW_BATTERY -> {
                logW("Picooc reports a low battery | ${hex(data)}")
                userWarn(R.string.bt_warn_low_battery_unknown)
            }

            OP_HISTORY, OP_HISTORY_DONE -> {
                // Acked so the scale keeps going; the payload layout is undocumented.
                send(buildAck(ackPrefix, opcode), "ack 0x%02X".format(opcode))
                logI("Picooc history frame 0x%02X (layout unknown) | %s".format(opcode, hex(data)))
            }

            OP_MULTI_FREQ_BIA -> {
                send(buildAck(ackPrefix, OP_MULTI_FREQ_BIA), "ack 0x3E")
                logI("Picooc multi-frequency BIA 0x3E (layout unknown) | ${hex(data)}")
            }

            OP_BALANCE_TEST -> {
                send(buildAck(ackPrefix, OP_BALANCE_TEST), "ack 0x3F")
                logI("Picooc balance test 0x3F (layout unknown, no openScale field) | ${hex(data)}")
            }

            else -> logI("Picooc RX unhandled 0x%02X | %s".format(opcode, hex(data)))
        }
    }

    override fun onDisconnected() {
        // The scale hangs up as soon as it is done. If the completion flag was never seen — or
        // read wrongly — this is the last chance to keep the reading.
        val user = sessionUser
        if (!published && pendingWeightKg > 0f && user != null) {
            publishSession(user, "disconnect fallback")
        }
        resetSession()
    }

    // --- protocol steps --------------------------------------------------------------------

    /**
     * Push the profile without waiting for a 0x30 request. PICOOC-CQ never asks for it — it goes
     * straight from the handshake to the measurement — so waiting would leave its BIA engine
     * without sex/height/age. Sending it unprompted is the documented frame, just earlier.
     */
    private fun pushUserProfileOnce(user: ScaleUser) {
        if (profilePushed) return
        profilePushed = true
        sendUserProfile(user)
    }

    private fun sendUserProfile(user: ScaleUser) {
        // The scale needs a moment before the profile; the adapter's Conservative inter-write
        // gap is only 50 ms, so pace it explicitly.
        val profile = buildUserProfile(user)
        scope.launch {
            delay(PROFILE_DELAY_MS)
            logI(
                "Picooc user profile: sex=${if (user.gender.isMale()) "M" else "F"} " +
                    "height=${user.bodyHeight}cm age=${user.age}"
            )
            send(profile, "user profile")
        }
    }

    private fun handleTimeRequest(user: ScaleUser, replyOpcode: Int) {
        val unit = unitCode(user.scaleUnit)
        val epochSeconds = System.currentTimeMillis() / 1000L
        send(
            buildTimeReply(ackPrefix, replyOpcode, epochSeconds, unit),
            "time sync (epoch=$epochSeconds unit=$unit)"
        )
    }

    private fun handleLiveFrame(data: ByteArray, opcode: Int, user: ScaleUser) {
        send(buildAck(ackPrefix, opcode), "ack 0x%02X".format(opcode))

        val frame = parseLiveFrame(data)
        if (frame == null) {
            logW("Picooc live frame 0x%02X too short to decode (len=%d)".format(opcode, data.size))
            return
        }
        logI("Picooc live: $frame")

        if (frame.weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG) {
            pendingWeightKg = frame.weightKg
            userInfo(R.string.bluetooth_scale_info_measuring_weight, frame.weightKg)
        }
        if (frame.impedance > 0.0) pendingImpedance = frame.impedance

        if (frame.complete) armSettle(user, "live frame completion flag")
    }

    /**
     * (Re)start the settle window. The order in which the scale sends 0x32 and 0x3C after the
     * live stream completes is not documented, so instead of publishing on the first of them we
     * wait a moment for the rest and publish whatever arrived.
     */
    private fun armSettle(user: ScaleUser, reason: String) {
        if (published || pendingWeightKg <= 0f) return
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_WAIT_MS)
            publishSession(user, reason)
        }
    }

    /** Emits exactly one measurement per session. */
    private fun publishSession(user: ScaleUser, reason: String) {
        if (published || pendingWeightKg <= 0f) return
        published = true
        settleJob?.cancel()
        settleJob = null

        val composition = pendingComposition
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            weight = pendingWeightKg
            if (pendingImpedance > 0.0) impedance = pendingImpedance
            if (pendingHeartRate > 0) heartRate = pendingHeartRate
        }

        val usableImpedance = pendingImpedance in MIN_IMPEDANCE..MAX_IMPEDANCE
        val lib = if (usableImpedance && user.bodyHeight > 0f) {
            StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = pendingWeightKg.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = pendingImpedance,
            )
        } else {
            null
        }

        if (composition != null && composition.fatPercent in MIN_FAT_PERCENT..MAX_FAT_PERCENT) {
            // The scale did the maths for us — prefer its numbers over our estimate.
            measurement.fat = composition.fatPercent
            measurement.water = composition.waterPercent.coerceIn(0f, 80f)
            measurement.bone = composition.boneMassKg.coerceIn(0f, 10f)
            measurement.visceralFat = composition.visceralFat.toFloat()
            measurement.lbm = pendingWeightKg * (1f - composition.fatPercent / 100f)
            // 0x32 carries neither of these, so fall back to the estimator for them.
            lib?.let {
                measurement.muscle = it.skeletalMusclePercentage.toFloat().coerceIn(0f, 100f)
                measurement.bmr = it.basalMetabolicRate.toFloat().coerceIn(0f, 5000f)
            }
            logI("Picooc body composition source: 0x32 packet")
        } else if (lib != null) {
            measurement.fat = lib.totalFatPercentage.toFloat().coerceIn(0f, 75f)
            measurement.water = lib.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f)
            measurement.muscle = lib.skeletalMusclePercentage.toFloat().coerceIn(0f, 100f)
            measurement.bone = lib.boneMassKg.toFloat().coerceIn(0f, 10f)
            measurement.lbm = lib.fatFreeMassKg.toFloat().coerceIn(0f, 150f)
            measurement.bmr = lib.basalMetabolicRate.toFloat().coerceIn(0f, 5000f)
            logI("Picooc body composition source: StandardImpedanceLib (${pendingImpedance}Ω)")
        } else {
            logI("Picooc body composition unavailable: impedance=$pendingImpedance height=${user.bodyHeight}")
        }

        // openScale has no measurement type for the metabolic body age the 0x32 packet reports,
        // nor for the phase angle in the live frames. Re-enabling either needs a
        // MeasurementTypeKey, a ScaleMeasurement field, a DB migration and BleConnector wiring.
        composition?.bodyAge?.takeIf { it > 0 }?.let { logI("Picooc metabolic body age: $it (not stored)") }

        logI(
            "Picooc publishing ($reason) → weight=${measurement.weight}kg fat=${measurement.fat}% " +
                "water=${measurement.water}% muscle=${measurement.muscle}% bone=${measurement.bone}kg " +
                "visceral=${measurement.visceralFat} lbm=${measurement.lbm}kg bmr=${measurement.bmr}kcal " +
                "hr=${measurement.heartRate}bpm impedance=${measurement.impedance}Ω"
        )
        publish(measurement)
        // No requestDisconnect(): the scale terminates the link itself once it is done.
    }

    private fun send(payload: ByteArray, what: String) {
        logI("Picooc TX ${hex(payload)} ($what)")
        writeTo(service, writeCharacteristic, payload, withResponse = false)
    }

    private fun resetSession() {
        ackPrefix = PREFIX_LATIN
        pendingWeightKg = 0f
        pendingImpedance = 0.0
        pendingHeartRate = 0
        pendingComposition = null
        published = false
        profilePushed = false
        sessionUser = null
        settleJob?.cancel()
        settleJob = null
    }

    /** Decoded 0x39 / 0x52 live streaming frame. */
    data class LiveFrame(
        val timestamp: Long,
        val weightKg: Float,
        val impedance: Double,
        val weightLb: Float,
        val unit: Int,
        val phaseAngle: Float,
        val complete: Boolean,
        /** Top bit of byte 10, which is not part of the pound value. Meaning unknown. */
        val poundFlag: Boolean,
    )

    /** Decoded 0x32 body composition frame. */
    data class Composition(
        val weightKg: Float,
        val fatPercent: Float,
        val waterPercent: Float,
        val boneMassKg: Float,
        val visceralFat: Int,
        val bodyAge: Int,
        val impedance: Double,
        /** Bytes 8..9 — purpose not documented; logged so a real capture can identify them. */
        val unknown8: Int,
        /** Bytes 12..13 — purpose not documented. */
        val unknown12: Int,
    )

    /** Firmware / bill-of-materials block carried in the 0x3A handshake. */
    data class BomInfo(
        val version: Int,
        val bomVersion: Int,
        val year: Int,
        val month: Int,
        val modelId: Int,
        val shortBom: Int,
        val shortFactoryId: Int,
    )

    companion object {
        // App→scale frame prefixes.
        const val PREFIX_LATIN = 0xF1
        const val PREFIX_MODERN = 0xA1

        // Scale→app opcodes.
        const val OP_USER_REQUEST = 0x30
        const val OP_USER_PROFILE = 0x31
        const val OP_BODY_COMPOSITION = 0x32
        const val OP_TIME_SYNC = 0x35
        const val OP_HISTORY = 0x36
        const val OP_HISTORY_DONE = 0x37
        const val OP_LIVE_WEIGHT = 0x39
        const val OP_HANDSHAKE = 0x3A
        const val OP_LOW_BATTERY = 0x3B
        const val OP_HEART_RATE = 0x3C
        const val OP_MULTI_FREQ_BIA = 0x3E
        const val OP_BALANCE_TEST = 0x3F
        const val OP_TIME_REQUEST_ALT = 0x51
        const val OP_LIVE_WEIGHT_ALT = 0x52

        /** Gap the Picooc app leaves between acking 0x30 and sending the profile. */
        const val PROFILE_DELAY_MS = 100L

        /**
         * Quiet period before publishing, re-armed by every measurement frame.
         *
         * This is the fallback, not the normal path: PICOOC-CQ terminates the link itself once
         * it is done and [onDisconnected] publishes then. The timer only has to survive until
         * that happens, which means outlasting the two gaps a capture of that scale shows —
         * 3.9 s from the weight frame to the first 0x3C, then roughly 1 s between 0x3C frames
         * for another 8-12 s. Anything shorter closes the record before the heart rate can
         * arrive, which is exactly what a 1.5 s window did.
         */
        const val SETTLE_WAIT_MS = 8000L

        private const val MIN_WEIGHT_KG = 2.0f
        private const val MAX_WEIGHT_KG = 250.0f
        private const val MIN_IMPEDANCE = 100.0
        private const val MAX_IMPEDANCE = 1500.0
        private const val MIN_FAT_PERCENT = 3.0f
        private const val MAX_FAT_PERCENT = 75.0f

        private const val SEX_MALE = 0x01
        private const val SEX_FEMALE = 0x02

        /** Youngest age the scale's BIA engine accepts. */
        private const val MIN_AGE = 18

        fun hex(data: ByteArray): String = data.joinToString(" ") { "%02X".format(it) }

        private fun u8(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF

        private fun u16be(data: ByteArray, index: Int): Int =
            (u8(data, index) shl 8) or u8(data, index + 1)

        /** `[prefix] 03 [opcode]` — the generic three-byte acknowledgement. */
        fun buildAck(prefix: Int, opcode: Int): ByteArray =
            byteArrayOf(prefix.toByte(), 0x03, opcode.toByte())

        /**
         * `[prefix] 0A [opcode] [epoch BE] [flag] [unit] [extra]` — exactly ten bytes, which is
         * what the length field advertises.
         */
        fun buildTimeReply(prefix: Int, opcode: Int, epochSeconds: Long, unit: Int): ByteArray {
            val seconds = epochSeconds.toInt()
            return byteArrayOf(
                prefix.toByte(),
                0x0A,
                opcode.toByte(),
                ((seconds shr 24) and 0xFF).toByte(),
                ((seconds shr 16) and 0xFF).toByte(),
                ((seconds shr 8) and 0xFF).toByte(),
                (seconds and 0xFF).toByte(),
                0x00, // flag
                unit.toByte(),
                0x00, // extra
            )
        }

        /** Scale unit code: 0 = kg, 1 = jin, 2 = lb. Stone falls back to pounds. */
        fun unitCode(unit: WeightUnit): Int = if (unit == WeightUnit.KG) 0 else 2

        /**
         * Height is transmitted as `(cm * 10 - 1000) / 5`, i.e. 100…227.5 cm map onto 0…255.
         * A missing or non-finite height encodes as 0 rather than throwing, so a broken profile
         * cannot abort the handshake.
         */
        fun encodeHeight(heightCm: Float): Int {
            if (!heightCm.isFinite()) return 0
            return ((heightCm * 10f - 1000f) / 5f).roundToInt().coerceIn(0, 255)
        }

        /** `31 06 01 [sex] [height] [age]` — exactly six bytes, matching the length field. */
        fun buildUserProfile(user: ScaleUser): ByteArray = byteArrayOf(
            OP_USER_PROFILE.toByte(),
            0x06,
            0x01,
            (if (user.gender.isMale()) SEX_MALE else SEX_FEMALE).toByte(),
            encodeHeight(user.bodyHeight).toByte(),
            user.age.coerceIn(MIN_AGE, 255).toByte(),
        )

        /**
         * Decode a 0x39 / 0x52 streaming frame, or `null` if it is too short.
         *
         * Layout: `[op][len][ts BE 4B][weight/20][impedance/10][lb/10][unit][phase/100][flag]`,
         * where `flag == 0` marks the reading as settled and reportable.
         *
         * The pound field is only 15 bits wide: a PICOOC-CQ capture of 71.7 kg reports
         * `86 2C`, and 0x862C/10 = 3434.8 is nonsense while (0x862C & 0x7FFF)/10 = 158.0 lb
         * matches 71.7 kg exactly. The top bit is therefore a separate flag, not part of the
         * value. It is decoded into [LiveFrame.poundFlag] and logged pending an explanation.
         */
        fun parseLiveFrame(data: ByteArray): LiveFrame? {
            if (data.size < 16) return null
            return LiveFrame(
                timestamp = ((u8(data, 2).toLong() shl 24) or (u8(data, 3).toLong() shl 16) or
                    (u8(data, 4).toLong() shl 8) or u8(data, 5).toLong()),
                weightKg = u16be(data, 6) / 20.0f,
                impedance = u16be(data, 8) / 10.0,
                weightLb = (u16be(data, 10) and 0x7FFF) / 10.0f,
                unit = u8(data, 12),
                phaseAngle = u16be(data, 13) / 100.0f,
                complete = u8(data, 15) == 0,
                poundFlag = (u8(data, 10) and 0x80) != 0,
            )
        }

        /**
         * Decode a 0x32 body composition frame, or `null` if it is too short.
         *
         * Layout: `[op][len][weight/20][fat%/10][water%/10][?][bone kg/20][?][visceral][bodyAge]
         * [..][impedance]`.
         */
        fun parseComposition(data: ByteArray): Composition? {
            if (data.size < 20) return null
            return Composition(
                weightKg = u16be(data, 2) / 20.0f,
                fatPercent = u16be(data, 4) / 10.0f,
                waterPercent = u16be(data, 6) / 10.0f,
                boneMassKg = u16be(data, 10) / 20.0f,
                visceralFat = u8(data, 14),
                bodyAge = u8(data, 15),
                impedance = u16be(data, 18).toDouble(),
                unknown8 = u16be(data, 8),
                unknown12 = u16be(data, 12),
            )
        }

        /** 0x3C trailing byte: the measurement is still running. */
        const val HEART_RATE_MEASURING = 0x01

        /** 0x3C trailing byte: the reading in bytes 2..3 is final. */
        const val HEART_RATE_FINAL = 0x02

        /**
         * Every byte position in a 0x3C frame that could hold the heart rate, as `label to
         * value`. The layout is settled now (see [parseHeartRate]); this is kept purely as a
         * diagnostic dump for other Picooc models, whose frames may well be laid out
         * differently.
         */
        fun heartRateCandidates(data: ByteArray): List<Pair<String, Int>> = buildList {
            if (data.size >= 4) add("u16be[2..3]" to u16be(data, 2))
            if (data.size >= 3) add("u8[2]" to u8(data, 2))
            if (data.size >= 4) add("u8[3]" to u8(data, 3))
            if (data.size >= 5) add("u16be[3..4]" to u16be(data, 3))
            if (data.size >= 5) add("u8[4]" to u8(data, 4))
        }

        /**
         * Heart rate in bpm from a 0x3C frame, or `null` while the scale is still measuring.
         *
         * Layout confirmed against a PICOOC-CQ capture: `3C 05 [bpm BE 2B] [status]`, with
         * status 1 while measuring and 2 once the value is final. The measuring frame reads
         * `3C 05 00 00 01` and the result frame `3C 05 00 44 02` — 0x44 = 68 bpm, matching the
         * scale's own display.
         *
         * Shorter frames from other models fall back to a single byte at offset 2; that path is
         * unverified, hence the [heartRateCandidates] dump alongside it in the log.
         */
        fun parseHeartRate(data: ByteArray): Int? {
            val bpm = when {
                data.size >= 5 -> u16be(data, 2)
                data.size >= 3 -> u8(data, 2)
                else -> return null
            }
            return if (bpm in 30..250) bpm else null
        }

        /**
         * Trailing status byte of a 0x3C frame — [HEART_RATE_MEASURING] or [HEART_RATE_FINAL] —
         * or `null` if the frame is too short.
         */
        fun heartRateStatus(data: ByteArray): Int? =
            if (data.size >= 5) u8(data, 4) else null

        /**
         * Firmware/BOM block appended to the 0x3A handshake. Present only when the high nibble
         * of byte 11 is 1; returns `null` otherwise.
         */
        fun parseBomInfo(data: ByteArray): BomInfo? {
            if (data.size < 17) return null
            if ((u8(data, 11) and 0xF0) shr 4 != 1) return null
            return BomInfo(
                version = (u8(data, 11) and 0xF0) shr 4,
                bomVersion = u8(data, 11) and 0x0F,
                year = (u8(data, 12) and 0xF0) shr 4,
                month = u8(data, 12) and 0x0F,
                modelId = (u8(data, 13) shl 4) + ((u8(data, 14) and 0xF0) shr 4),
                shortBom = ((u8(data, 14) and 0x0F) shl 10) + (u8(data, 15) shl 2) + (u8(data, 16) shr 6),
                shortFactoryId = u8(data, 16) and 0x3F,
            )
        }
    }
}
