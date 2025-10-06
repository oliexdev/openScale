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
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class InlifeHandler : ScaleDeviceHandler() {

    // ---- GATT UUIDs (Bluetooth base UUID with 16-bit short codes) ----
    private val SVC: UUID     = uuid16(0xFFF0)
    private val CHR_NOTIFY: UUID = uuid16(0xFFF1) // notify
    private val CHR_CMD: UUID    = uuid16(0xFFF2) // write

    // ---- Wire format constants ----
    private val START: Byte = 0x02
    private val END: Byte   = 0xAA.toByte()
    private val FRAME_LEN   = 14

    // Commands
    private val CMD_SET_USER = 0xD2
    private val CMD_WEIGHT   = 0xD8
    private val CMD_RESULT   = 0xDD
    private val CMD_USER_ACK = 0xDF
    private val CMD_FINISH   = 0xD4

    // De-duplication of repeated notifications
    private var lastFrame: ByteArray? = null

    // ---- Support detection ----
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = (device.name ?: "").lowercase(Locale.ROOT)
        val byName = name in setOf("000fatscale01", "000fatscale02", "042fatscale01")
        val bySvc  = device.serviceUuids.any { it == SVC }

        if (!byName && !bySvc) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC
        )

        return DeviceSupport(
            displayName = "Inlife",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // ---- Session lifecycle ----
    override fun onConnected(user: ScaleUser) {
        // Subscribe and send user profile to the scale
        setNotifyOn(SVC, CHR_NOTIFY)

        val level = (athleteLevel(user) + 1)      // proto: 1=general, 2=amateur, 3=pro
        val sex   = if (user.gender.isMale()) 0 else 1
        val id    = user.id and 0xFF
        val age   = user.age and 0xFF
        val hCm   = user.bodyHeight.toInt() and 0xFF

        sendCommand(CMD_SET_USER, level, sex, id, age, hCm)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_NOTIFY) return
        if (data.size != FRAME_LEN) return
        if (data[0] != START || data.last() != END) {
            logE("Bad start/end byte in frame")
            return
        }
        // Verify XOR checksum over [1..(len-2)] must be 0x00
        if (xorRange(data, 1, FRAME_LEN - 2) != 0.toByte()) {
            logE("Checksum invalid")
            return
        }
        // Drop duplicates
        if (lastFrame?.contentEquals(data) == true) {
            logD("Duplicate frame ignored")
            return
        }
        lastFrame = data.copyOf()

        when (data[1].toInt() and 0xFF) {
            0x0F -> {
                logD("Scale indicates disconnect/idle")
                // optional: requestDisconnect()
            }
            CMD_WEIGHT -> {
                val w = u16Be(data, 2) / 10.0f
                logD("Live weight = %.2f kg".format(w))
                userInfo(R.string.bluetooth_scale_info_measuring_weight, w)
            }
            CMD_RESULT -> {
                // Two protocol variants: old (derived values) vs. new (weight+impedance)
                val flag = data[11].toInt() and 0xFF
                if (flag == 0x80 || flag == 0x81) {
                    processMeasurementNew(data)   // weight + impedance (experimental)
                } else {
                    processMeasurementLegacy(data) // weight + LBM + visceral factor + BMR
                }
            }
            CMD_USER_ACK -> {
                val ok = data[2].toInt() == 0
                logD("User data ack: ${if (ok) "OK" else "error"}")
            }
            else -> logD("Unknown command 0x%02X".format(data[1]))
        }
    }

    // ---- Parsing: legacy result frame (no impedance) ----
    private fun processMeasurementLegacy(d: ByteArray) {
        val weight = u16Be(d, 2) / 10.0f
        var lbm    = u24Be(d, 4) / 1000.0f
        val viscF  = u16Be(d, 7) / 10.0f
        val bmr    = u16Be(d, 9) / 10.0f // currently unused, kept for parity

        // Sentinel for invalid LBM
        if (lbm >= 0xFFFFFF / 1000.0f) {
            logW("Measurement failed; feet not correctly placed on scale?");
            return
        }

        val u = currentAppUser()

        // Athlete correction (matches legacy logic)
        when (athleteLevel(u)) {
            1 -> lbm *= 1.0427f
            2 -> lbm *= 1.0958f
        }

        val fatKg  = weight - lbm
        val fatPct = (fatKg / weight) * 100.0
        val water  = (0.73 * (weight - fatKg) / weight) * 100.0
        val muscle = (0.548 * lbm / weight) * 100.0
        val boneKg = 0.05158 * lbm

        // Visceral fat from "visceral factor" + anthropometric heuristics
        val height = u.bodyHeight // in cm
        var visceral = viscF - 50.0
        if (u.gender.isMale()) {
            if (height >= 1.6 * weight + 63) {
                visceral += (0.765 - 0.002 * height) * weight
            } else {
                visceral += 380 * weight / (((0.0826 * height * height) - 0.4 * height) + 48)
            }
        } else {
            if (weight <= height / 2 - 13) {
                visceral += (0.691 - 0.0024 * height) * weight
            } else {
                visceral += 500 * weight / (((0.1158 * height * height) + 1.45 * height) - 120)
            }
        }

        // Athlete post-adjustments
        val lvl = athleteLevel(u)
        if (lvl != 0) {
            if (visceral >= 21) visceral *= 0.85
            if (visceral >= 10) visceral *= 0.8
            visceral -= lvl * 2
        }

        val m = ScaleMeasurement().apply {
            this.weight = weight
            this.fat = clamp(fatPct, 5.0, 80.0)
            this.water = clamp(water, 5.0, 80.0)
            this.muscle = clamp(muscle, 5.0, 80.0)
            this.bone = clamp(boneKg, 0.5, 8.0)        // bone mass in kg (legacy kept kg here)
            this.lbm = lbm
            this.visceralFat = clamp(visceral, 1.0, 50.0)
        }

        publish(m)
        // Finish/ack
        sendCommand(CMD_FINISH)
    }

    // ---- Parsing: new result frame (weight + impedance) ----
    private fun processMeasurementNew(d: ByteArray) {
        val weight = u16Be(d, 2) / 10.0f
        val impedance = u32Be(d, 4).toLong()
        logD("Result (new): weight=%.2f kg, impedance=%d".format(weight, impedance))
        // Legacy left this as TODO; to keep behavior, publish at least weight.
        publish(ScaleMeasurement().apply { this.weight = weight })
        // (Optional) Hook a BIA library here if available later.
        sendCommand(CMD_FINISH)
    }

    // ---- Helpers ----

    private fun athleteLevel(u: ScaleUser): Int = when (u.activityLevel) {
        ActivityLevel.SEDENTARY, ActivityLevel.MILD     -> 0 // General
        ActivityLevel.MODERATE                          -> 1 // Amateur
        ActivityLevel.HEAVY, ActivityLevel.EXTREME      -> 2 // Professional
        else                                            -> 0
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Float =
        min(hi, max(lo, v)).toFloat()

    private fun sendCommand(command: Int, vararg params: Int) {
        val frame = ByteArray(FRAME_LEN) { 0 }
        frame[0] = START
        frame[1] = (command and 0xFF).toByte()
        var i = 2
        for (p in params) {
            if (i >= FRAME_LEN - 2) break
            frame[i++] = (p and 0xFF).toByte()
        }
        // checksum is XOR over [1..(len-3)], placed at [len-2]
        frame[FRAME_LEN - 2] = xorRange(frame, 1, FRAME_LEN - 3)
        frame[FRAME_LEN - 1] = END
        writeTo(SVC, CHR_CMD, frame, withResponse = true)
    }

    private fun xorRange(b: ByteArray, from: Int, toInclusive: Int): Byte {
        var x = 0
        for (i in from..toInclusive) x = x xor (b[i].toInt() and 0xFF)
        return (x and 0xFF).toByte()
    }

    private fun u16Be(d: ByteArray, off: Int): Float =
        (((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)).toFloat()

    private fun u24Be(d: ByteArray, off: Int): Float =
        (((d[off].toInt() and 0xFF) shl 16) or
                ((d[off + 1].toInt() and 0xFF) shl 8) or
                (d[off + 2].toInt() and 0xFF)).toFloat()

    private fun u32Be(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0xFF) shl 24) or
                ((d[off + 1].toInt() and 0xFF) shl 16) or
                ((d[off + 2].toInt() and 0xFF) shl 8) or
                (d[off + 3].toInt() and 0xFF)
}
