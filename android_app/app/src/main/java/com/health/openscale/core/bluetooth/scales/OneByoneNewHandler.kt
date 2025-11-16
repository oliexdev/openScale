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
import com.health.openscale.core.bluetooth.libs.OneByoneNewLib
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

/**
 * OneByone (new) handler
 *
 * Service  : 0xFFB0
 * Notify   : 0xFFB2 (20-byte frames starting with AB 2A)
 * Write    : 0xFFB1 (20-byte commands)
 *
 * Message overview:
 * - Header: AB 2A
 * - data[2] = type:
 *     0x80  → final weight message (weight only); impedance arrives in a separate 0x01 frame
 *     0x01  → impedance frame for the pending final measurement
 *     0x00  → history/real-time unified frame:
 *              * if data[7] == 0x80 → historic entry (contains timestamp, weight, impedance)
 *              * else               → real-time (ignored)
 *
 * Flow:
 * 1) Subscribe NOTIFY on 0xFFB2.
 * 2) Send "weight request" (D7 checksum) with current user & last result.
 * 3) Send users' history (D4 checksum) for all app users (selected first).
 * 4) Parse incoming frames. On final (0x80 + 0x01) → compute body-comp & publish.
 * 5) After publish → re-send users' history to update the scale.
 */
class OneByoneNewHandler : ScaleDeviceHandler() {

    // ---- UUIDs ---------------------------------------------------------------

    private val SVC_FFB0 = uuid16(0xFFB0)
    private val CHR_FFB2 = uuid16(0xFFB2) // notify frames
    private val CHR_FFB1 = uuid16(0xFFB1) // write commands

    // ---- Frame constants -----------------------------------------------------

    private val MSG_LEN = 20
    private val HDR_A  = 0xAB.toByte()
    private val HDR_B  = 0x2A.toByte()

    // temp holder for the current final measurement (weight first, then impedance)
    private var pending: ScaleMeasurement? = null

    // ---- Capability declaration ---------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()
        val supports = name.equals("1byone scale".lowercase())
        if (!supports) return null

        val caps = buildSet {
            add(DeviceCapability.BODY_COMPOSITION)
            add(DeviceCapability.HISTORY_READ)
            add(DeviceCapability.UNIT_CONFIG)
            add(DeviceCapability.LIVE_WEIGHT_STREAM)
        }
        return DeviceSupport(
            displayName = "1byone (new)",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // ---- Link lifecycle ------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // Subscribe first
        setNotifyOn(SVC_FFB0, CHR_FFB2)

        // Kick off a measurement window and seed the device with known history
        sendWeightRequest(user)
        sendUsersHistory(priorityUserId = user.id)

        // Hint for the user
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_FFB2) return
        if (data.size < MSG_LEN || data[0] != HDR_A || data[1] != HDR_B) {
            logD("Ignoring frame: ${data.toHexPreview(32)}")
            return
        }

        when (val msgType = data[2].toInt() and 0xFF) {
            0x80 -> { // Final weight (impedance follows in 0x01)
                val weightKg = (u24be(data, 3) and 0x03_FFFF) / 1000.0f
                logD("Final weight: $weightKg kg")
                pending = ScaleMeasurement().apply {
                    this.userId = user.id
                    this.dateTime = Date()
                    this.weight = weightKg
                }
            }

            0x01 -> { // Impedance for the pending final measurement
                val imp = u16be(data, 4)
                val m = pending
                if (m == null) {
                    logW("Impedance frame without pending weight, dropping")
                    return
                }
                logD("Impedance: $imp Ω")

                populateBodyComp(m, imp, currentAppUser())
                publish(m)
                pending = null

                // After successful publish, refresh the scale’s cached history
                sendUsersHistory(priorityUserId = user.id)
            }

            0x00 -> {
                // 0x00 is "history or real-time" unified frame.
                // data[7] == 0x80 → historic entry.
                if (data[7] != 0x80.toByte()) {
                    // Real-time frame; safe to ignore.
                    return
                }

                val ts = getTimestamp32(data, 3)
                val weightKg = (u24be(data, 8) and 0x03_FFFF) / 1000.0f
                val imp = u16be(data, 15)

                logD("Historic entry: w=$weightKg kg, Z=$imp Ω @ $ts")

                // Legacy behavior: assign history to currently selected user.
                // (If you want "intelligent recognition", implement it here using usersForDevice().)
                val hist = ScaleMeasurement().apply {
                    userId = user.id
                    dateTime = ts
                    weight = weightKg
                }
                populateBodyComp(hist, imp, currentAppUser())
                publish(hist)
            }

            else -> logD("Unknown msgType=0x${msgType.toString(16)} ${data.toHexPreview(32)}")
        }
    }

    // ---- Body-comp population -------------------------------------------------

    private fun populateBodyComp(m: ScaleMeasurement, impedanceOhm: Int, u: ScaleUser) {
        val gender = if (u.gender.isMale()) 1 else 0

        // The legacy driver used ConverterUtils.fromCentimeter(heightCm, user.measureUnit) here.
        // We keep the same call to preserve parity with historical values expected by the vendor lib.
        val heightForLib: Float =u.bodyHeight

        val lib = OneByoneNewLib(gender, u.age, heightForLib, u.activityLevel.toInt())

        m.fat         = lib.getBodyFatPercentage(m.weight, impedanceOhm)
        m.water       = lib.getWaterPercentage(m.weight, impedanceOhm)
        m.bone        = lib.getBoneMass(m.weight, impedanceOhm)
        m.visceralFat = lib.getVisceralFat(m.weight)
        m.muscle      = lib.getSkeletonMusclePercentage(m.weight, impedanceOhm)
        m.lbm         = lib.getLBM(m.weight, impedanceOhm)
    }

    // ---- Outbound commands ----------------------------------------------------

    /** Initial kick message (D7 checksum), includes current timestamp, unit, and one entry for the current user. */
    private fun sendWeightRequest(user: ScaleUser) {
        val msg = ByteArray(MSG_LEN)
        setupMeasurementMessage(msg, 0, user)
        logD("→ sendWeightRequest ${msg.toHexPreview(32)}")
        writeTo(SVC_FFB0, CHR_FFB1, msg, withResponse = true)
    }

    /**
     * Uploads recent history snapshot to the scale (D4 checksum).
     * Puts the selected user first, then others ordered by last measurement date (oldest first),
     * two entries per frame.
     */
    private fun sendUsersHistory(priorityUserId: Int) {
        val users = usersForDevice().toMutableList()
        if (users.isEmpty()) return

        // Sort: selected user first, then by last-measurement time ascending
        users.sortWith { a, b ->
            when {
                a.id == priorityUserId && b.id != priorityUserId -> -1
                b.id == priorityUserId && a.id != priorityUserId -> 1
                else -> {
                    val la = lastMeasurementFor(a.id)?.dateTime?.time ?: Long.MIN_VALUE
                    val lb = lastMeasurementFor(b.id)?.dateTime?.time ?: Long.MIN_VALUE
                    la.compareTo(lb)
                }
            }
        }

        var frameCounter = 0
        var i = 0
        while (i < users.size) {
            val msg = ByteArray(MSG_LEN)
            msg[0] = HDR_A
            msg[1] = HDR_B
            msg[2] = users.size.coerceAtMost(0xFF).toByte()
            msg[3] = (++frameCounter).toByte()

            // Two entries per message
            for (slot in 0 until 2) {
                val idx = i + slot
                if (idx >= users.size) break
                val u = users[idx]
                val last = lastMeasurementFor(u.id)

                val weight = last?.weight ?: 0f
                val imp = if (last != null) getImpedanceFromLBM(u, last) else 0

                setMeasurementEntry(
                    msg = msg,
                    offset = 4 + slot * 7,
                    entryNum = (idx + 1),
                    heightCm = u.bodyHeight.roundToInt(),
                    weight = weight,
                    sex = if (u.gender.isMale()) 1 else 0,
                    age = u.age,
                    impedance = imp,
                    impedanceLe = true // history frames: LE impedance
                )
            }

            // Footer D4 + checksum
            msg[18] = 0xD4.toByte()
            msg[19] = d4Checksum(msg, 0, MSG_LEN)
            logD("→ sendUsersHistory ${msg.toHexPreview(40)}")
            writeTo(SVC_FFB0, CHR_FFB1, msg, withResponse = true)

            i += 2
        }
    }

    // ---- Message builders / helpers ------------------------------------------

    private fun setupMeasurementMessage(msg: ByteArray, offset: Int, user: ScaleUser): Boolean {
        if (offset + MSG_LEN > msg.size) return false

        msg[offset] = HDR_A
        msg[offset + 1] = HDR_B
        setTimestamp32(msg, offset + 2)

        // reserved
        msg[offset + 6] = 0x00

        // weight unit as per user setting (relies on WeightUnit.toInt() mapping)
        val wu: Int = try { user.scaleUnit.toInt() } catch (_: Throwable) {
            // Fallback: 0 = KG (based on vendor apps)
            0
        }
        msg[offset + 7] = wu.toByte()

        // seed with current user's last known values (if any)
        val last = lastMeasurementFor(user.id)
        val weight = last?.weight ?: 0f
        val imp = if (last != null) getImpedanceFromLBM(user, last) else 0

        setMeasurementEntry(
            msg = msg,
            offset = offset + 8,
            entryNum = user.id,
            heightCm = user.bodyHeight.roundToInt(),
            weight = weight,
            sex = if (user.gender.isMale()) 1 else 0,
            age = user.age,
            impedance = imp,
            impedanceLe = false // setup frame: BE/packed variant expected by device
        )

        // D7 footer + checksum over [offset+2 .. offset+18)
        msg[offset + 18] = 0xD7.toByte()
        msg[offset + 19] = d7Checksum(msg, offset + 2, 17)
        return true
    }

    /**
     * Packs one user entry.
     * Layout (7 bytes):
     *   [0]=entry#, [1]=height(cm),
     *   [2..3]=rounded weight (int16 BE, in 0.1 kg → sent as X10),
     *   [4]= (sex << 7) | (age & 0x7F),
     *   [5..6]=impedance (LE or BE depending on frame).
     */
    private fun setMeasurementEntry(
        msg: ByteArray,
        offset: Int,
        entryNum: Int,
        heightCm: Int,
        weight: Float,
        sex: Int,
        age: Int,
        impedance: Int,
        impedanceLe: Boolean
    ) {
        // Round to one decimal place, then multiply by 10 (legacy quirk to avoid arrows on scale)
        val roundedTimes10 = (weight * 10f).roundToInt() * 10
        msg[offset] = (entryNum and 0xFF).toByte()
        msg[offset + 1] = (heightCm and 0xFF).toByte()

        // weight int16 BE
        ConverterUtils.toInt16Be(msg, offset + 2, roundedTimes10)

        // sex in MSB, age in lower 7 bits
        msg[offset + 4] = (((sex and 0x01) shl 7) or (age and 0x7F)).toByte()

        if (impedanceLe) {
            msg[offset + 5] = ((impedance ushr 8) and 0xFF).toByte()
            msg[offset + 6] = (impedance and 0xFF).toByte()
        } else {
            msg[offset + 5] = (impedance and 0xFF).toByte()
            msg[offset + 6] = ((impedance ushr 8) and 0xFF).toByte()
        }
    }

    private fun setTimestamp32(msg: ByteArray, offset: Int) {
        val epochSec = System.currentTimeMillis() / 1000L
        ConverterUtils.toInt32Be(msg, offset, epochSec)
    }

    private fun getTimestamp32(msg: ByteArray, offset: Int): Date {
        val epochSec = ConverterUtils.fromUnsignedInt32Be(msg, offset)
        return Date(epochSec * 1000)
    }

    // ---- Checksums (exact legacy behavior) -----------------------------------

    private fun d4Checksum(msg: ByteArray, offset: Int, length: Int): Byte {
        var sum = sumChecksum(msg, offset + 2, length - 2)

        // Remove impedance MSB first entry
        sum = (sum - msg[offset + 9]).toByte()

        // Remove second entry weight (two bytes)
        sum = (sum - msg[offset + 13]).toByte()
        sum = (sum - msg[offset + 14]).toByte()

        // Remove impedance MSB second entry
        sum = (sum - msg[offset + 16]).toByte()
        return sum
    }

    private fun d7Checksum(msg: ByteArray, offset: Int, length: Int): Byte {
        var sum = sumChecksum(msg, offset, length)
        // Remove impedance MSB of the single entry
        sum = (sum - msg[offset + 12]).toByte() // offset+8 entry → +12 is impedance MSB position here
        return sum
    }

    private fun sumChecksum(msg: ByteArray, offset: Int, length: Int): Byte {
        var s = 0
        for (i in 0 until length) s = (s + (msg[offset + i].toInt() and 0xFF)) and 0xFF
        return s.toByte()
    }

    // ---- Byte utils -----------------------------------------------------------

    private fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u24be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 16) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                (b[off + 2].toInt() and 0xFF)

    // ---- Impedance back-calculation (as in legacy) ---------------------------

    /**
     * Since the device expects an impedance with the last known weight entry,
     * estimate it from LBM the app already has.
     */
    private fun getImpedanceFromLBM(user: ScaleUser, measurement: ScaleMeasurement): Int {
        val finalLbm = measurement.lbm
        val postImpedanceLbm = finalLbm + user.age * 0.0542f
        val hM = user.bodyHeight / 100f
        val preImpedanceLbm = hM * hM * 9.058f + 12.226f + measurement.weight * 0.32f
        return ((preImpedanceLbm - postImpedanceLbm) / 0.0068f).roundToInt()
    }
}
