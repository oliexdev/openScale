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
import com.health.openscale.core.bluetooth.libs.OneByoneLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Calendar
import java.util.UUID
import kotlin.math.max

/**
 * OneByone (classic) handler (Service 0xFFF0, notify on 0xFFF4, write cmds on 0xFFF1).
 *
 * Protocol summary (based on legacy driver behavior):
 * - Subscribe NOTIFY on 0xFFF4.
 * - Send "mode/unit" command FD 37 [unit] [group] ... XOR.
 * - Send clock F1 [YYYY be][MM][dd][HH][mm][ss] → expect 2-byte ACK "F1 00".
 * - Request history F2 00 → historic packets (starting with CF ...) follow, end with 2-byte "F2 00".
 *   If any history received, send F2 01 to clear.
 * - Real-time measurements also arrive as CF ... frames (11 or 18+ bytes).
 *
 * We parse CF frames, compute impedance, validate timestamps for history,
 * derive body composition via OneByoneLib, and publish ScaleMeasurement.
 */
class OneByoneHandler : ScaleDeviceHandler() {

    // --- UUIDs (16-bit under Bluetooth Base UUID) ------------------------------

    private val SVC_FFF0  = uuid16(0xFFF0)
    private val CHR_FFF4  = uuid16(0xFFF4) // NOTIFY: mixed weight/body payloads (CF ...)
    private val CHR_FFF1  = uuid16(0xFFF1) // WRITE: command pipe (FD/ F1/ F2 ...)
    private val SVC_180F = uuid16(0x180F) //battery service
    private val CHR_2A19 = uuid16(0x2A19) //battery characteristic

    // --- Small runtime state ---------------------------------------------------

    private var waitAckClock = false          // true after sending F1 until we receive "F1 00"
    private var historicMode = false          // true while reading history (F2 00 .. F2 00)
    private var historyCount = 0              // number of historic measurements seen

    // prevent saving measurements too close in time (ms)
    private val DATE_TIME_THRESHOLD_MS = 3000
    private var lastSavedAt: Long = 0L

    // --- Capability declaration -----------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()

        val model = when {
            "t9146" in name -> "Eufy C1"
            "t9147" in name -> "Eufy P1"
            "Health Scale".lowercase() in name -> "1byone (classic)"
            else -> return null
        }

        val caps = buildSet {
            add(DeviceCapability.BODY_COMPOSITION)
            add(DeviceCapability.TIME_SYNC)
            add(DeviceCapability.HISTORY_READ)
            add(DeviceCapability.UNIT_CONFIG)
            add(DeviceCapability.LIVE_WEIGHT_STREAM)
        }

        return DeviceSupport(
            displayName = model,
            capabilities = caps,
            implemented  = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Link lifecycle --------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // 1) Subscribe to notifications on 0xFFF4
        setNotifyOn(SVC_FFF0, CHR_FFF4)

        // 2) Configure unit/group (matches legacy magic FD 37 ...)
        writeTo(SVC_FFF0, CHR_FFF1, buildModeUnitCmd(user))

        // 3) Sync device clock, then wait for ACK "F1 00"
        val clock = buildClockCmd()
        waitAckClock = true
        writeTo(SVC_FFF0, CHR_FFF1, clock)

        // 1) Battery: subscribe + read once
        setNotifyOn(SVC_180F, CHR_2A19)
        readFrom(SVC_180F, CHR_2A19)

        // NOTE: After we receive the ACK, we will request history (F2 00) in onNotification().
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_FFF4 && characteristic != CHR_2A19) {
            logD("Unexpected notify from $characteristic ${data.toHexPreview(24)}")
            return
        }

        if (characteristic == CHR_2A19) {
            val level = (data.first().toInt() and 0xFF)
            logD("Reported battery level: $level%")
            if (level <= 10) {
                userWarn(R.string.bluetooth_scale_warning_low_battery, level)
            }
            return
        }

        // Two-byte ACKs appear during setup:
        if (data.size == 2) {
            when {
                // Clock ACK: proceed to request history
                waitAckClock && data[0] == 0xF1.toByte() && data[1] == 0x00.toByte() -> {
                    waitAckClock = false
                    historicMode = true
                    historyCount = 0
                    writeTo(SVC_FFF0, CHR_FFF1, byteArrayOf(0xF2.toByte(), 0x00.toByte())) // request history
                    return
                }

                // End-of-history marker (F2 00). If we received any, send clear (F2 01).
                data[0] == 0xF2.toByte() && data[1] == 0x00.toByte() -> {
                    if (historicMode) {
                        historicMode = false
                        if (historyCount > 0) {
                            writeTo(SVC_FFF0, CHR_FFF1, byteArrayOf(0xF2.toByte(), 0x01.toByte())) // clear history
                        }
                        // Prompt user for a live measurement
                        userInfo(R.string.bt_info_step_on_scale)
                    }
                    return
                }
            }
        }

        // CF ... frames carry weight/impedance (+ optional timestamp if length >= 18)
        if (data.isNotEmpty() && data[0] == 0xCF.toByte() && data.size >= 11) {
            if (historicMode) historyCount++
            parseMeasurementFrame(data, user, isHistoric = historicMode)
        } else {
            // For debugging: show other small frames
            if (data.size <= 6) logD("Short frame: ${data.toHexPreview(64)}")
        }
    }

    // --- Parsing & publishing --------------------------------------------------

    private fun parseMeasurementFrame(bytes: ByteArray, user: ScaleUser, isHistoric: Boolean) {
        // Weight is uint16 LE at [3..4] in 0.01 kg
        val weightKg = u16le(bytes, 3) / 100.0f

        // Impedance is ((b2 << 8) + b1) * 0.1 Ω (note the byte order used by original driver)
        val impedanceOhm = (((bytes[2].toInt() and 0xFF) shl 8) + (bytes[1].toInt() and 0xFF)) * 0.1f

        // A flag in b9 == 1 means "impedance not present" (legacy observation)
        val impedancePresent = (bytes[9].toInt() != 1) && (impedanceOhm != 0f)

        // Historic entries include timestamp (length >= 18)
        val hasTimestamp = bytes.size >= 18

        // Discard unwanted frames: history without time, or anything without impedance
        if (!impedancePresent || (isHistoric && !hasTimestamp)) return

        // Timestamp (BE year + plain month/day/time), used when provided
        val whenCal = Calendar.getInstance()
        if (hasTimestamp) {
            val year = u16be(bytes, 11)
            val month = (bytes[13].toInt() and 0xFF).coerceIn(1, 12)
            val day   = (bytes[14].toInt() and 0xFF).coerceAtLeast(1)
            val hh    = bytes[15].toInt() and 0xFF
            val mm    = bytes[16].toInt() and 0xFF
            val ss    = bytes[17].toInt() and 0xFF
            try {
                whenCal.set(year, month - 1, day, hh, mm, ss)
                whenCal.isLenient = false
                whenCal.time // throws if invalid → caught below
            } catch (_: Exception) {
                // Invalid history timestamp: drop the frame (matches legacy behavior)
                return
            }
        }

        // Rate-limit saves (avoid too-dense series)
        val nowMs = max(System.currentTimeMillis(), whenCal.timeInMillis)
        if (nowMs - lastSavedAt < DATE_TIME_THRESHOLD_MS) return
        lastSavedAt = nowMs

        // Build composition using OneByoneLib (same as legacy)
        val (sex, peopleType) = mapUserToLibParams(user)
        val lib = OneByoneLib(sex, user.age, user.bodyHeight, peopleType)

        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = if (hasTimestamp) whenCal.time else Calendar.getInstance().time
            weight = weightKg
        }

        try {
            // Derivations
            val fatPct = lib.getBodyFat(m.weight, impedanceOhm)
            m.fat = fatPct
            m.water = lib.getWater(fatPct)
            m.bone = lib.getBoneMass(m.weight, impedanceOhm)
            m.visceralFat = lib.getVisceralFat(m.weight)
            m.muscle = lib.getMuscle(m.weight, impedanceOhm)
            m.lbm = lib.getLBM(m.weight, m.fat)

            publish(m)
        } catch (t: Throwable) {
            // If library throws on impossible inputs, just log & ignore this frame
            logW("OneByoneLib failed: ${t.message}")
        }
    }

    // --- Command builders ------------------------------------------------------

    /** FD 37 [unit] [group] 00..00 XX, where XX is XOR of all previous bytes. */
    private fun buildModeUnitCmd(user: ScaleUser): ByteArray {
        val unit: Byte = when (user.scaleUnit) {
            WeightUnit.KG -> 0x00
            WeightUnit.LB -> 0x01
            WeightUnit.ST -> 0x02
            else          -> 0x00
        }
        val group: Byte = 0x01
        val payload = byteArrayOf(
            0xFD.toByte(), 0x37.toByte(), unit, group,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )
        payload[payload.lastIndex] = xorChecksum(payload, 0, payload.size - 1)
        return payload
    }

    /** F1 [YYYY be][MM][dd][HH][mm][ss] (2-byte ACK "F1 00" expected). */
    private fun buildClockCmd(): ByteArray {
        val dt = Calendar.getInstance()
        val year = dt.get(Calendar.YEAR)
        return byteArrayOf(
            0xF1.toByte(),
            ((year ushr 8) and 0xFF).toByte(),
            (year and 0xFF).toByte(),
            (dt.get(Calendar.MONTH) + 1).toByte(),
            dt.get(Calendar.DAY_OF_MONTH).toByte(),
            dt.get(Calendar.HOUR_OF_DAY).toByte(),
            dt.get(Calendar.MINUTE).toByte(),
            dt.get(Calendar.SECOND).toByte()
        )
    }

    // --- Helpers ---------------------------------------------------------------

    private fun xorChecksum(b: ByteArray, from: Int, len: Int): Byte {
        var x = 0
        for (i in 0 until len) x = x xor (b[from + i].toInt() and 0xFF)
        return (x and 0xFF).toByte()
    }

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun mapUserToLibParams(u: ScaleUser): Pair<Int, Int> {
        val sex = if (u.gender == GenderType.MALE) 1 else 0
        val peopleType = when (u.activityLevel) {
            // Matches legacy mapping:
            // SEDENTARY/MILD -> 0, MODERATE -> 1, HEAVY/EXTREME -> 2
            com.health.openscale.core.data.ActivityLevel.SEDENTARY -> 0
            com.health.openscale.core.data.ActivityLevel.MILD      -> 0
            com.health.openscale.core.data.ActivityLevel.MODERATE  -> 1
            com.health.openscale.core.data.ActivityLevel.HEAVY     -> 2
            com.health.openscale.core.data.ActivityLevel.EXTREME   -> 2
        }
        return sex to peopleType
    }
}
