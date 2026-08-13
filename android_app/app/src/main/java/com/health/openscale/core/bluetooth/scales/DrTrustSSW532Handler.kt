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
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Dr. Trust SSW532 / ICOMON FG2211WB body composition scale.
 *
 * Service 0xFFB0:
 *   0xFFB1 – command write (App → Scale, 20 bytes)
 *   0xFFB2 – live weight NOTIFY  (byte[1]=0x07, byte[3]=0xA2; weight BE 24-bit gram÷1000 at [6-8])
 *   0xFFB3 – result INDICATE     (byte[1]=0x18 setup; byte[1]=0x23 measurement)
 *
 * Startup sequence (state machine):
 *   1. Subscribe INDICATE on FFB3 → scale sends setup frame (byte[1]=0x18, byte[2]=0x00)
 *      with a dynamic session ID at byte[0].
 *   2. Subscribe NOTIFY on FFB2 → scale sends second setup frame (byte[1]=0x18, byte[2]=0x01).
 *   3. Write user profile to FFB1 (3 packets: pktA init, B0 demographics, B1 app-id).
 *
 * Measurement frames (byte[1]=0x23, keyed by byte[2]):
 *   0x00 – validity at byte[14] (0x01=ok), weight BE 24-bit gram÷1000 at [9-11],
 *            whole-body impedance channels A/B at [15-16] and [17-18] (LE uint16÷10=Ω, ~400-500Ω)
 *   0x01 – 8 segmental impedances at bytes[3-18], LE uint16÷10=Ω (Z1-Z8 in order):
 *            Z3[7-8]=Trunk, Z4[9-10]=Right Leg, Z5[11-12]=Left Leg (foot-to-foot path = Z3+Z4+Z5)
 *            Z6[13-14] and Z7[15-16] are cross-body diagonals (~300 Ω, not used)
 *   0x02 – end marker; triggers publish (weight-only if body comp not available)
 *
 * B0 demographics encoding (confirmed across 4 HCI captures):
 *   byte[3]    = 0xB8 (constant)
 *   bytes[4-7] = Unix timestamp (big-endian uint32, seconds since epoch)
 *   byte[11]   = height_cm (direct)
 *   byte[14]   = 0x80 | age_years
 *   Gender is not encoded; scale measures raw impedance, app computes BIA.
 *   Checksum   = sum(bytes[3..18]) % 32
 */
class DrTrustSSW532Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_CMD: UUID = uuid16(0xFFB1)
    private val CHAR_WEIGHT: UUID = uuid16(0xFFB2)
    private val CHAR_BC: UUID = uuid16(0xFFB3)

    private enum class State { WAITING_SESSION, WAITING_CONFIRM, MEASURING }
    private var state = State.WAITING_SESSION
    private var sessionId: Int = 0x00

    private var pendingWeightKg: Float = 0f
    private var savedWeightKg: Float = 0f  // weight-only fallback for onDisconnected
    private var bodyCompPublished = false   // guards onDisconnected against double-publish
    private var pkt0Valid = false
    private var z3 = 0.0  // pkt1 trunk impedance (Ω)
    private var z4 = 0.0  // pkt1 right-leg impedance (Ω)
    private var z5 = 0.0  // pkt1 left-leg impedance (Ω)
    private var gotImpedance = false

    private var isLiveWeightLocked = false
    private var isLiveMeasurement = false

    private var fallbackJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        val nameMatch = name == "ssw532" || name.startsWith("ssw") || name.contains("fg2211")
        val serviceMatch = device.serviceUuids.any { it == SERVICE }
        if (!nameMatch || !serviceMatch) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
        )
        return DeviceSupport(
            displayName = "Dr. Trust SSW532",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        state = State.WAITING_SESSION
        savedWeightKg = 0f
        bodyCompPublished = false
        isLiveWeightLocked = false
        isLiveMeasurement = false
        fallbackJob?.cancel()
        fallbackJob = null
        reset() // Defensive reset of all session fields on start
        setNotifyOn(SERVICE, CHAR_BC)
        // Remaining setup triggered by the scale's first FFB3 indication (onNotification)
    }

    override fun onDisconnected() {
        fallbackJob?.cancel()
        fallbackJob = null
        // Only publish if body comp was never published and we had a confirmed live reading.
        // pendingWeightKg alone is not safe — it could be from a cached replay (pkt0 before pkt2).
        if (!bodyCompPublished) {
            val fallback = when {
                savedWeightKg > 0f -> savedWeightKg
                isLiveWeightLocked && pendingWeightKg > 0f -> pendingWeightKg
                else -> 0f
            }
            if (fallback > 0f) {
                publish(ScaleMeasurement().apply {
                    dateTime = Date()
                    weight   = fallback
                })
            }
        }
        savedWeightKg = 0f
        bodyCompPublished = false
        isLiveWeightLocked = false
        isLiveMeasurement = false
        reset()
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHAR_BC     -> onBcFrame(data, user)
            CHAR_WEIGHT -> onWeightFrame(data)
        }
    }

    // --- FFB3 indication handler ---

    private fun onBcFrame(d: ByteArray, user: ScaleUser) {
        if (d.size < 20) return
        when (d[1].toUByte().toInt()) {
            0x18 -> onSetupFrame(d, user)
            0x23 -> if (state == State.MEASURING) onMeasurementFrame(d, user)
        }
    }

    private fun onSetupFrame(d: ByteArray, user: ScaleUser) {
        when (d[2].toUByte().toInt()) {
            0x00 -> {
                // First setup frame: scale provides dynamic session ID
                sessionId = d[0].toUByte().toInt()
                logD("setup frame 0: sessionId=0x${sessionId.toString(16)}")
                setNotifyOn(SERVICE, CHAR_WEIGHT)
                state = State.WAITING_CONFIRM
            }
            0x01 -> {
                // Second setup frame: safe to write user profile
                logD("setup frame 1: sending profile")
                sendUserProfile(user)
                userInfo(R.string.bt_info_step_on_scale)
                state = State.MEASURING
            }
        }
    }

    // --- FFB2 live weight ---

    private fun onWeightFrame(d: ByteArray) {
        if (d.size < 9) return
        if (d[1].toUByte().toInt() != 0x07) return
        if (d[3].toUByte().toInt() != 0xA2) return
        // byte[4]: 0x00=taring/live, 0x01=stabilising, 0x03=locked (blink = stable)
        val stability = d[4].toUByte().toInt()
        // Weight is a 3-byte BE gram value at [6-8]; readBE16 at [7-8] overflows for >65.5 kg.
        val kg = readBE24(d, 6) / 1000.0f
        if (kg > 0f && stability == 0x03) {
            pendingWeightKg = kg
            isLiveWeightLocked = true
        } else if (kg < 2.0f && savedWeightKg > 0f && !bodyCompPublished) {
            // User stepped off without picking up handles — publish saved weight and disconnect
            logD("step-off detected: publishing savedWeightKg=$savedWeightKg")
            bodyCompPublished = true
            publish(ScaleMeasurement().apply { dateTime = Date(); weight = savedWeightKg })
            savedWeightKg = 0f
            writeTeardownAck()
            requestDisconnect()
        }
    }

    // --- FFB3 measurement frames ---

    private fun onMeasurementFrame(d: ByteArray, user: ScaleUser) {
        when (d[2].toUByte().toInt()) {
            0x00 -> {
                val cmd = d[3].toUByte().toInt()
                isLiveMeasurement = (cmd == 0xA3 || cmd == 0xA7)
                if (!isLiveMeasurement) {
                    logD("Skipping cached measurement (cmd = 0x${cmd.toString(16)})")
                    pkt0Valid = false
                    return
                }
                pkt0Valid = d[14].toUByte().toInt() == 0x01
                if (!pkt0Valid) return
                // Same 3-byte gram encoding as the live weight frame.
                val kg = readBE24(d, 9) / 1000.0f
                if (kg > 0f) pendingWeightKg = kg
            }
            0x01 -> {
                if (!pkt0Valid || !isLiveMeasurement) return
                // Foot-to-foot path: Trunk (Z3) + Right Leg (Z4) + Left Leg (Z5)
                z3 = readLE16(d, 7) / 10.0
                z4 = readLE16(d, 9) / 10.0
                z5 = readLE16(d, 11) / 10.0
                gotImpedance = true
            }
            0x02 -> {
                if (!isLiveWeightLocked || !isLiveMeasurement) {
                    // Historical replay or non-live measurement — ACK to stop blinking, discard.
                    writeTeardownAck()
                    logD("Skipping cached/non-live measurement replay")
                } else if (pendingWeightKg > 0f) {
                    if (pkt0Valid && gotImpedance) {
                        publishWithBodyComp(user)  // sends ACK + disconnects internally
                    } else {
                        // Weight locked but no BIA yet — stay connected so user can pick up handles for body comp.
                        // We do NOT send writeTeardownAck() here because that shuts down the scale prematurely.
                        savedWeightKg = pendingWeightKg

                        // Start a 5-second timer. If no BIA arrives, publish weight-only and disconnect.
                        fallbackJob?.cancel()
                        fallbackJob = scope.launch {
                            delay(5000)
                            if (!bodyCompPublished && savedWeightKg > 0f) {
                                logD("No BIA received within 5s; publishing weight-only")
                                bodyCompPublished = true
                                publish(ScaleMeasurement().apply {
                                    dateTime = Date()
                                    weight   = savedWeightKg
                                })
                                savedWeightKg = 0f
                                writeTeardownAck()
                                requestDisconnect()
                            }
                        }
                    }
                }
                reset()
            }
        }
    }

    // --- Publish ---

    private fun publishWithBodyComp(user: ScaleUser) {
        fallbackJob?.cancel()
        fallbackJob = null
        // Foot-to-foot impedance via segmental path: Trunk + Right Leg + Left Leg (~500 Ω range)
        val wholeBodyZ = z3 + z4 + z5
        val lib = StandardImpedanceLib(
            gender    = user.gender,
            age       = user.age,
            weightKg  = pendingWeightKg.toDouble(),
            heightM   = user.bodyHeight / 100.0,
            impedance = wholeBodyZ
        )
        val fatPct = lib.totalFatPercentage.toFloat()
        if (fatPct <= 0f) {
            // Impedance out of calibration range — publish weight-only and disconnect
            logD("body comp sanity fail: fatPct=$fatPct wholeBodyZ=$wholeBodyZ")
            savedWeightKg = pendingWeightKg
            requestDisconnect()
            return
        }
        val gender = if (user.gender == GenderType.MALE) 1 else 0
        savedWeightKg = 0f
        bodyCompPublished = true
        publish(ScaleMeasurement().apply {
            dateTime    = Date()
            weight      = pendingWeightKg
            fat         = fatPct.coerceIn(0f, 75f)
            water       = lib.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f)
            muscle      = lib.skeletalMusclePercentage.toFloat().coerceIn(0f, 99f)
            bone        = lib.boneMassKg.toFloat().coerceIn(0f, 10f)
            bmr         = lib.basalMetabolicRate.toFloat().coerceIn(0f, 5000f)
            lbm         = lib.fatFreeMassKg.toFloat().coerceIn(0f, 150f)
            visceralFat = estimateVisceralFat(pendingWeightKg, z3, user.age, gender)
            impedance   = wholeBodyZ
        })
        writeTeardownAck()
        requestDisconnect()
    }

    private fun reset() {
        pendingWeightKg = 0f
        pkt0Valid = false
        z3 = 0.0
        z4 = 0.0
        z5 = 0.0
        gotImpedance = false
        isLiveMeasurement = false
    }

    // --- User profile write sequence ---

    private fun sendUserProfile(user: ScaleUser) {
        val ts = (System.currentTimeMillis() / 1000L).toInt()
        writePktA()
        writeB0(slot = 0x01, user = user, ts = ts)
        writeB1(slot = 0x01)
    }

    private fun writePktA() {
        val payload = byteArrayOf(
            0x00, 0x03, 0x00, 0xB0.toByte(), sessionId.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        payload[19] = checksum(payload)
        writeCmd(payload)
    }

    private fun writeB0(slot: Int, user: ScaleUser, ts: Int) {
        val h   = user.bodyHeight.toInt().coerceIn(100, 220)
        val age = user.age.coerceIn(0, 127)
        val payload = ByteArray(20)
        payload[0]  = slot.toByte()
        payload[1]  = 0x1A
        payload[2]  = 0x00
        payload[3]  = 0xB8.toByte()
        payload[4]  = (ts shr 24).toByte()
        payload[5]  = (ts shr 16).toByte()
        payload[6]  = (ts shr  8).toByte()
        payload[7]  = (ts        ).toByte()
        payload[8]  = 0x01
        payload[9]  = 0x4A
        payload[10] = 0x01
        payload[11] = h.toByte()
        payload[12] = 0x17
        payload[13] = 0x70
        payload[14] = (0x80 or age).toByte()
        payload[15] = 0x13
        payload[16] = 0x88.toByte()
        payload[17] = 0x0F
        payload[18] = 0x00
        payload[19] = checksum(payload)
        writeCmd(payload)
    }

    private fun writeB1(slot: Int) {
        val payload = byteArrayOf(
            slot.toByte(), 0x1A, 0x01, 0x00, 0x00, 0x00, 0x06,
            0x69, 0x63, 0x6F, 0x6D, 0x6F, 0x6E,  // "icomon"
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        payload[19] = checksum(payload)
        writeCmd(payload)
    }

    /**
     * Visceral fat % calibrated from trunk impedance Z3 (Ω).
     *
     * Uses trunk load ratio (weight/Z3) as the primary predictor.
     * Coefficient a=2.87 calibrated against: male, 54.80 kg, Z3=42.4 Ω, age=29,
     * 82 cm waist → ~4% of body weight visceral fat (~2.19 kg).
     * Female offset -1.0 is provisional; needs female calibration data.
     */
    private fun estimateVisceralFat(weightKg: Float, trunkZ: Double, age: Int, gender: Int): Float {
        val tlr = weightKg / trunkZ
        val genderOffset = if (gender == 1) 0.0f else -1.0f
        return (2.87f * tlr.toFloat() + 0.01f * age + genderOffset).coerceIn(1f, 25f)
    }

    /** Teardown ACK sent to FFB1 after the pkt2 end marker so the scale stops blinking. */
    private fun writeTeardownAck() {
        val payload = ByteArray(20)
        payload[0] = 0x04
        payload[1] = 0x03
        payload[2] = 0x00
        payload[3] = 0xB0.toByte()
        payload[4] = sessionId.toByte()
        // bytes[5-18] remain 0x00
        payload[19] = checksum(payload)
        writeCmd(payload)
    }

    /** sum(bytes[3..18]) mod 32 */
    private fun checksum(buf: ByteArray): Byte {
        var s = 0
        for (i in 3..18) s += buf[i].toUByte().toInt()
        return (s % 32).toByte()
    }

    private fun writeCmd(payload: ByteArray) = writeTo(SERVICE, CHAR_CMD, payload, withResponse = true)

    private fun readBE16(d: ByteArray, off: Int) =
        (d[off].toUByte().toInt() shl 8) or d[off + 1].toUByte().toInt()

    private fun readBE24(d: ByteArray, off: Int) =
        (d[off].toUByte().toInt() shl 16) or (d[off + 1].toUByte().toInt() shl 8) or d[off + 2].toUByte().toInt()

    private fun readLE16(d: ByteArray, off: Int) =
        d[off].toUByte().toInt() or (d[off + 1].toUByte().toInt() shl 8)
}
