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
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Calendar
import java.util.Date
import java.util.UUID

/**
 * SenssunHandler
 * --------------------
 * Modern Kotlin handler for Senssun body composition scales.
 *
 * This handler supports two GATT layouts:
 *  - Model A: svc 0xFFF0, notify 0xFFF1, write 0xFFF2
 *  - Model B: svc 0xFFB0, notify 0xFFB2, write 0xFFB2
 *
 * Protocol highlights:
 *  - On connect: sync date, then time; subscribe to notify characteristics.
 *  - Notifications start with 0xFF padding byte; then the real frame starts.
 *  - Frames:
 *      A5 … -> weight/live/stable indicator (data[5] = 0xA0 live, 0xAA stable)
 *      B0 … -> fat & water
 *      C0 … -> muscle & bone
 *      D0 … -> kcal (ignored for publishing)
 *      BE … -> fat-test error (disconnect)
 *
 * When all 4 value groups are available (bitmask == 0b1111), a measurement is published.
 */
class SenssunHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "SenssunHandler"
    }

    // Model A
    private val SVC_A = uuid16(0xFFF0)
    private val CHR_A_NOTIFY = uuid16(0xFFF1)
    private val CHR_A_WRITE  = uuid16(0xFFF2)

    // Model B
    private val SVC_B = uuid16(0xFFB0)
    private val CHR_B_NOTIFY = uuid16(0xFFB2)
    private val CHR_B_WRITE  = uuid16(0xFFB2)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Legacy mapping used exact name "SENSSUN FAT"
        val ok = device.name?.equals("SENSSUN FAT", ignoreCase = true) == true
        return if (ok) {
            DeviceSupport(
                displayName = "Senssun Fat",
                capabilities = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC
                ),
                implemented = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC
                ),
                tuningProfile = TuningProfile.Balanced,
                linkMode = LinkMode.CONNECT_GATT
            )
        } else null
    }

    // Chosen write target once we know which model is notifying
    private var activeWriteService: UUID? = null
    private var activeWriteChr: UUID? = null

    // State fields replicated from legacy
    private var lastWeight = 0
    private var lastFat = 0
    private var lastHydration = 0
    private var lastMuscle = 0
    private var lastBone = 0
    private var lastKcal = 0

    private var weightStabilized = false
    private var stepMessageDisplayed = false

    // Bitmask: 1=weight, 2=fat/water, 4=muscle/bone, 8=kcal
    private var valuesMask = 0

    override fun onConnected(user: ScaleUser) {
        // Subscribe to both models; the one that exists will work, the other will just warn once.
        setNotifyOn(SVC_A, CHR_A_NOTIFY)
        setNotifyOn(SVC_B, CHR_B_NOTIFY)

        // Reset parsing state
        resetState()

        // Sync date & time early (before user data), try both models until we learn the active one.
        synchroniseDate(writeToActiveOrBoth = false)
        synchroniseTime(writeToActiveOrBoth = false)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.isEmpty() || data[0] != 0xFF.toByte()) return

        // Decide model from the notifying characteristic (first hit locks the write target)
        if (activeWriteService == null) {
            if (characteristic == CHR_A_NOTIFY) {
                activeWriteService = SVC_A
                activeWriteChr = CHR_A_WRITE
                LogManager.d(TAG, "Found a Model A")
            } else if (characteristic == CHR_B_NOTIFY) {
                activeWriteService = SVC_B
                activeWriteChr = CHR_B_WRITE
                LogManager.d(TAG, "Found a Model B")
            }
        }

        // Shift away the 0xFF prefix like legacy does
        val frame = ByteArray(data.size - 1).also { System.arraycopy(data, 1, it, 0, it.size) }

        when (frame[0]) {
            0xA5.toByte() -> parseMeasurement(frame, user)
            else -> Unit
        }
    }

    override fun onDisconnected() {
        resetState()
        activeWriteService = null
        activeWriteChr = null
    }

    // --------------------------------------------------------------------------------------------
    // Frame parsing & actions
    // --------------------------------------------------------------------------------------------

    private fun parseMeasurement(frame: ByteArray, user: ScaleUser) {
        // frame[5]: 0xA0 live, 0xAA stable
        when (frame.getOrNull(5)) {
            0xAA.toByte(), 0xA0.toByte() -> {
                if (!stepMessageDisplayed) {
                    userInfo(R.string.bt_info_step_on_scale, 0)
                    stepMessageDisplayed = true
                }
                if (weightStabilized) return

                weightStabilized = (frame[5] == 0xAA.toByte())
                lastWeight = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)

                if (weightStabilized) {
                    valuesMask = valuesMask or 0x01
                    userInfo(R.string.bluetooth_scale_info_measuring_weight, lastWeight / 10.0f)
                    synchroniseUser(user)
                }
            }

            0xBE.toByte() -> {
                userError(R.string.bt_error_generic, t = null) // or a dedicated string if you have one
                requestDisconnect()
            }

            0xB0.toByte() -> {
                lastFat = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
                lastHydration = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
                valuesMask = valuesMask or 0x02
            }

            0xC0.toByte() -> {
                lastMuscle = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
                // Note legacy swapped [3] and [4] for bone
                lastBone = ((frame[4].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
                valuesMask = valuesMask or 0x04
            }

            0xD0.toByte() -> {
                lastKcal = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
                // val unknown = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
                valuesMask = valuesMask or 0x08
            }
        }

        if (valuesMask == 0x0F) {
            val m = ScaleMeasurement().apply {
                weight = lastWeight / 10.0f
                fat = lastFat / 10.0f
                water = lastHydration / 10.0f
                bone = lastBone / 10.0f
                muscle = lastMuscle / 10.0f
                dateTime = Date()
            }
            publish(m)
            requestDisconnect()
        }
    }

    // --------------------------------------------------------------------------------------------
    // Commands
    // --------------------------------------------------------------------------------------------

    private fun synchroniseDate(writeToActiveOrBoth: Boolean = true) {
        val cal = Calendar.getInstance()
        val msg = ByteArray(9).apply {
            this[0] = 0xA5.toByte()
            this[1] = 0x30
            // year (two digits)
            this[2] = (cal.get(Calendar.YEAR) % 100).toByte()
            // day-of-year (2 bytes, big-endian like legacy's hex split)
            val doy = cal.get(Calendar.DAY_OF_YEAR)
            this[3] = ((doy shr 8) and 0xFF).toByte()
            this[4] = (doy and 0xFF).toByte()
            // rest zeros; checksum at [7]; [8] left as-is per legacy
        }
        addChecksum(msg)
        writeSenssun(msg, writeToActiveOrBoth)
    }

    private fun synchroniseTime(writeToActiveOrBoth: Boolean = true) {
        val cal = Calendar.getInstance()
        val msg = ByteArray(9).apply {
            this[0] = 0xA5.toByte()
            this[1] = 0x31
            this[2] = cal.get(Calendar.HOUR_OF_DAY).toByte()
            this[3] = cal.get(Calendar.MINUTE).toByte()
            this[4] = cal.get(Calendar.SECOND).toByte()
        }
        addChecksum(msg)
        writeSenssun(msg, writeToActiveOrBoth)
    }

    private fun synchroniseUser(user: ScaleUser) {
        val msg = ByteArray(9).apply {
            this[0] = 0xA5.toByte()
            this[1] = 0x10
            // (male? 15 : 0) * 16 + user.id
            this[2] = (((if (user.gender.isMale()) 15 else 0) * 16) + user.id).toByte()
            this[3] = user.age.toByte()
            this[4] = user.bodyHeight.toInt().toByte()
        }
        addChecksum(msg)
        writeSenssun(msg, writeToActiveOrBoth = true)
    }

    private fun addChecksum(message: ByteArray) {
        var verify = 0
        for (i in 1 until message.size - 2) {
            verify = (verify + (message[i].toInt() and 0xFF)) and 0xFF
        }
        message[message.size - 2] = verify.toByte()
        // Last byte left unchanged (legacy left it at default 0x00)
    }

    /**
     * Writes to the active model (once known) or to both model write endpoints as fallback.
     * This mimics legacy behavior where discovery latched the model before writing.
     */
    private fun writeSenssun(bytes: ByteArray, writeToActiveOrBoth: Boolean) {
        val svc = activeWriteService
        val chr = activeWriteChr
        if (writeToActiveOrBoth && svc != null && chr != null) {
            writeTo(svc, chr, bytes, withResponse = true)
            return
        }
        // Model unknown: try both; the one that doesn't exist will warn once via adapter
        writeTo(SVC_A, CHR_A_WRITE, bytes, withResponse = true)
        writeTo(SVC_B, CHR_B_WRITE, bytes, withResponse = true)
    }

    private fun resetState() {
        lastWeight = 0
        lastFat = 0
        lastHydration = 0
        lastMuscle = 0
        lastBone = 0
        lastKcal = 0
        weightStabilized = false
        stepMessageDisplayed = false
        valuesMask = 0
    }
}
