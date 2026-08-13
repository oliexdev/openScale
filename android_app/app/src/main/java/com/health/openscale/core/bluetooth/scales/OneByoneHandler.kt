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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID
import kotlin.math.max

/**
 * OneByone (classic) handler (Service 0xFFF0, notify on 0xFFF4, write cmds on 0xFFF1).
 *
 * Protocol summary (based on legacy driver behavior):
 * - Subscribe NOTIFY on 0xFFF4.
 * - Send "mode/unit" command `FD 37 <unit> <group> ... XOR`.
 * - Send clock `F1 <YYYY be><MM><dd><HH><mm><ss>` → expect 2-byte ACK "F1 00".
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
    private var clockAckFallbackJob: Job? = null
    private var promptedForMeasurement = false

    // prevent saving measurements too close in time (ms)
    private val DATE_TIME_THRESHOLD_MS = 3000
    private var lastSavedAt: Long = 0L

    // --- Capability declaration -----------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()

        val model = when {
            "t9146" in name -> "Eufy C1"
            "t9147" in name -> "Eufy P1"
            "t9120" in name -> "Eufy A1"
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
        // Not every scale in this family answers F1 (the 1byone "Health Scale" never does), so
        // don't let the whole session hang on an ACK that may never come.
        armClockAckFallback()
    }

    /**
     * Prompt for a live measurement if the `F1 00` clock ACK does not arrive.
     *
     * Both the history request and the "step on the scale" prompt hang off that ACK, but not every
     * scale in this family sends one -- the 1byone "Health Scale" never does, and the vendor app
     * never even sends `F1` on that model. Without this fallback such a scale produces a fully
     * working connection with no user-visible feedback at all, which is indistinguishable from a
     * failed one.
     *
     * [waitAckClock] is deliberately left set: a late ACK should still start the history read.
     * Only the prompt is forced, and [promptedForMeasurement] keeps it to one per connection.
     */
    private fun armClockAckFallback() {
        clockAckFallbackJob?.cancel()
        clockAckFallbackJob = scope.launch {
            delay(CLOCK_ACK_TIMEOUT_MS)
            if (!waitAckClock) return@launch

            logI("No F1 clock ACK after ${CLOCK_ACK_TIMEOUT_MS}ms - prompting for a live measurement")
            promptForMeasurement()
        }
    }

    /** Show the "step on the scale" prompt at most once per connection. */
    private fun promptForMeasurement() {
        if (promptedForMeasurement) return
        promptedForMeasurement = true
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        clockAckFallbackJob?.cancel()
        clockAckFallbackJob = null
        waitAckClock = false
        historicMode = false
        promptedForMeasurement = false
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
                    clockAckFallbackJob?.cancel()
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
                        promptForMeasurement()
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
        // Weight is uint16 LE at bytes 3..4 in 0.01 kg
        val weightKg = ((bytes[3].toInt() and 0xFF) or ((bytes[4].toInt() and 0xFF) shl 8)) / 100.0f

        // Impedance is ((b2 << 8) + b1) * 0.1 Ω (note the byte order used by original driver)
        val impedanceOhm = (((bytes[2].toInt() and 0xFF) shl 8) + (bytes[1].toInt() and 0xFF)) * 0.1f

        // A flag in b9 == 1 means "impedance not present" (legacy observation)
        val impedancePresent = (bytes[9].toInt() != 1) && (impedanceOhm != 0f)

        // Historic entries include a timestamp at bytes 11..17 (length >= 18)
        val hasTimestamp = hasHistoryTimestamp(bytes)

        // A history entry without its timestamp cannot be placed on the graph, so drop it.
        if (isHistoric && !hasTimestamp) return

        // Only record settled readings. Byte 9 is the lock status: 0x00 and 0x36 mean the scale has
        // finished weighing, anything else is still in progress. Historic entries are settled by
        // definition, so the gate applies to live frames only.
        if (!isHistoric && !isFinalReading(bytes)) {
            logD("Ignoring in-progress frame (status=0x%02X, %.2f kg)"
                .format(bytes[9].toInt() and 0xFF, weightKg))
            return
        }

        // Frames with no usable weight carry nothing worth saving.
        if (weightKg <= 0f) return

        // Timestamp (BE year + plain month/day/time), used when provided
        val whenCal = Calendar.getInstance()
        if (hasTimestamp) {
            // Year is uint16 BE at bytes 11..12
            val year  = ((bytes[11].toInt() and 0xFF) shl 8) or (bytes[12].toInt() and 0xFF)
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
            // Store the raw impedance so body composition can be recomputed later.
            if (impedancePresent) impedance = impedanceOhm.toDouble()
        }

        // Body composition needs impedance. The scale reports zero when it could not run the
        // bioimpedance measurement (socks or shoes, poor foot contact, a weight-only model), and
        // the weight is still perfectly good — record it rather than losing the weigh-in entirely.
        if (impedancePresent) {
            try {
                val fatPct = lib.getBodyFat(m.weight, impedanceOhm)
                m.fat = fatPct
                m.water = lib.getWater(fatPct)
                m.bone = lib.getBoneMass(m.weight, impedanceOhm)
                m.visceralFat = lib.getVisceralFat(m.weight)
                m.muscle = lib.getMuscle(m.weight, impedanceOhm)
                m.lbm = lib.getLBM(m.weight, m.fat)
            } catch (t: Throwable) {
                // If the library throws on impossible inputs, keep the weight and drop the rest.
                logW("OneByoneLib failed, publishing weight only: ${t.message}")
            }
        } else {
            // No user-facing notice here on purpose: a snackbar emitted at this point is dismissed
            // by BleConnector's saved-measurement snackbar ~700 ms later, so it never really shows.
            // Surfacing this properly needs a change in the save path; tracked separately.
            logI("No impedance in frame - publishing weight only (%.2f kg)".format(weightKg))
        }

        publish(m)
    }

    // --- Command builders ------------------------------------------------------

    /** `FD 37 <unit> <group> 00..00 XX`, where XX is XOR of all previous bytes. */
    private fun buildModeUnitCmd(user: ScaleUser): ByteArray {
        val unit: Byte = when (user.scaleUnit) {
            WeightUnit.KG -> 0x00
            WeightUnit.LB -> 0x01
            WeightUnit.ST -> 0x02
        }
        val group: Byte = 0x01
        val payload = byteArrayOf(
            0xFD.toByte(), 0x37.toByte(), unit, group,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )
        payload[payload.lastIndex] = xorChecksum(payload, payload.size - 1)
        return payload
    }

    /** `F1 <YYYY be><MM><dd><HH><mm><ss>` (2-byte ACK "F1 00" expected). */
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

    companion object {
        /**
         * Grace period for the `F1 00` clock ACK before prompting anyway.
         *
         * Generous on purpose: the `F1` write itself only leaves the queue ~600 ms after connect
         * (notify setup and the `FD 37` write are paced ahead of it), so a tight timeout would fire
         * before a scale that does ACK had a fair chance to answer.
         */
        private const val CLOCK_ACK_TIMEOUT_MS = 3000L

        /** Length of a live measurement frame: `CF …` payload plus the XOR byte at index 10. */
        const val LIVE_FRAME_LEN = 11

        /** Frame type marker for a body-fat measurement. */
        private const val TYPE_BODY_FAT = 0xCF.toByte()

        fun xorChecksum(b: ByteArray, len: Int): Byte {
            var x = 0
            for (i in 0 until len) x = x xor (b[i].toInt() and 0xFF)
            return (x and 0xFF).toByte()
        }

        /**
         * True when [bytes] begins with a complete live measurement frame, i.e. the XOR checksum
         * at byte 10 covers bytes 0..9.
         */
        fun isLiveFrame(bytes: ByteArray): Boolean =
            bytes.size >= LIVE_FRAME_LEN && bytes[10] == xorChecksum(bytes, 10)

        /**
         * True when byte 9 marks the reading as settled ("locked" in the vendor app, which treats
         * 0x00 and 0x36 as final and everything else as still in progress).
         */
        fun isFinalReading(bytes: ByteArray): Boolean {
            if (bytes.size < LIVE_FRAME_LEN) return false
            return when (bytes[9].toInt() and 0xFF) {
                0x00, 0x36 -> true
                else -> false
            }
        }

        /**
         * True when [bytes] carries a history timestamp in bytes 11..17.
         *
         * Length alone is not enough to decide this. The scale sends its final measurement twice,
         * and the two copies can arrive coalesced into one notification -- the ATT payload caps at
         * 20 bytes, so the buffer is a whole 11-byte frame followed by the first 9 bytes of its
         * duplicate. That is >= 18 bytes but is not history, and reading bytes 11..17 as a
         * timestamp yields garbage (year 53138, day 156, hour 39) that gets the reading discarded.
         *
         * A genuine history frame stores the year at bytes 11..12, so its byte 11 is the year's
         * high byte (0x07 for 2026) and its byte 10 is measurement data rather than a checksum
         * over bytes 0..9. Requiring both a valid live-frame checksum *and* a second frame marker
         * at byte 11 separates the two cases without disturbing history reads on the Eufy models
         * that share this handler.
         */
        fun hasHistoryTimestamp(bytes: ByteArray): Boolean {
            if (bytes.size < 18) return false
            val isCoalescedDuplicate = isLiveFrame(bytes) && bytes[11] == TYPE_BODY_FAT
            return !isCoalescedDuplicate
        }
    }

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
