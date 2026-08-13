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
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

/**
 * Vitafit "Body Fat" smart scale (VT701), sold with the Vitafit / Fitdays app.
 *
 * Protocol reverse-engineered from an HCI snoop of two Vitafit-app weigh-ins and confirmed
 * against the scale's displayed readings (85.00 kg and 84.95 kg):
 *  - Service 0xFFF0, notify characteristic 0xFFF1, write characteristic 0xFFF2.
 *  - Both directions use the frame `[hdr][len][0x26][type][data…][chk][0xAA]`, where
 *    `hdr` = 0x5A for scale→phone and 0xA5 for phone→scale, `len` counts every byte after
 *    itself (0x26 through 0xAA), and `chk` = XOR of `len` through the last data byte.
 *  - Type 0x10 frames stream live weight; byte 4 is a stability flag that settles to 0x02, and
 *    the weight is a 16-bit big-endian value in 0.01 kg at bytes 8..9.
 *  - After the phone acks the stable weight (`a5 05 26 10 02 …`) the scale reports the raw
 *    whole-body impedance in a type 0x11 frame (16-bit big-endian Ω at bytes 9..10, ~390 Ω).
 *
 * The scale itself sends only weight + impedance; body composition (fat/water/muscle) is left
 * to openScale's estimator, which needs the raw impedance published here.
 */
class VitafitVT701Handler : ScaleDeviceHandler() {

    private val service = uuid16(0xFFF0)
    private val notifyCharacteristic = uuid16(0xFFF1)
    private val writeCharacteristic = uuid16(0xFFF2)

    private var savedWeightKg = -1f
    private var published = false
    private var fallbackJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.startsWith("Vitafit", ignoreCase = true)) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION
        )
        return DeviceSupport(
            displayName = "Vitafit VT701",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        savedWeightKg = -1f
        published = false
        fallbackJob?.cancel()
        fallbackJob = null

        setNotifyOn(service, notifyCharacteristic)

        // Replay the app's static hello sequence. These frames carry no timestamp/user data,
        // so they are safe to send verbatim; the scale then streams type 0x10 weight frames.
        writeTo(service, writeCharacteristic, buildCommand(0x33, byteArrayOf(0x00)))
        writeTo(service, writeCharacteristic, buildCommand(0x44, byteArrayOf()))
        writeTo(service, writeCharacteristic, buildCommand(0x17, byteArrayOf(0x01)))

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic) return
        if (published) return

        parseStableWeightRaw(data)?.let { raw ->
            if (savedWeightKg >= 0f) return  // already latched; wait for impedance
            savedWeightKg = raw / 100.0f
            logD("Vitafit stable weight=$savedWeightKg kg")

            // Ack the stable weight; this prompts the scale to run the impedance measurement.
            writeTo(service, writeCharacteristic, buildCommand(TYPE_WEIGHT, byteArrayOf(STABLE_FLAG.toByte())))

            // Safety net: if the impedance frame never arrives (e.g. user steps off), publish
            // weight-only so the reading is not lost.
            fallbackJob = scope.launch {
                delay(6000)
                if (!published && savedWeightKg >= 0f) {
                    logD("No impedance within 6s; publishing weight-only")
                    publishMeasurement(savedWeightKg, 0, user)
                }
            }
            return
        }

        parseImpedanceOhm(data)?.let { ohm ->
            if (savedWeightKg < 0f) return  // impedance without a weight — ignore
            // Ack the impedance frame like the app does.
            writeTo(service, writeCharacteristic, buildCommand(TYPE_IMPEDANCE, byteArrayOf(0x00)))
            publishMeasurement(savedWeightKg, ohm, user)
        }
    }

    private fun publishMeasurement(weightKg: Float, impedanceOhm: Int, user: ScaleUser) {
        if (published) return
        published = true
        fallbackJob?.cancel()
        fallbackJob = null

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            weight = weightKg
        }

        // The scale reports only weight + raw impedance; derive body composition the same way the
        // other impedance scales in this package do. The guard mirrors StandardImpedanceLib's note
        // that its formulas only hold for whole-body impedance in a sane range.
        if (impedanceOhm in 1 until 1500) {
            measurement.impedance = impedanceOhm.toDouble()
            val lib = StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = weightKg.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = impedanceOhm.toDouble(),
            )
            measurement.fat = lib.totalFatPercentage.toFloat()
            measurement.water = lib.totalBodyWaterPercentage.toFloat()
            measurement.muscle = lib.skeletalMusclePercentage.toFloat()
            measurement.bone = lib.boneMassKg.toFloat()
            measurement.bmr = lib.basalMetabolicRate.toFloat()
        }

        publish(measurement)
        logI("Vitafit publish weight=$weightKg kg impedance=$impedanceOhm Ω")
        requestDisconnect()
    }

    companion object {
        private const val HEADER_SCALE = 0x5A
        private const val HEADER_PHONE = 0xA5
        private const val PRODUCT_ID = 0x26
        private const val TRAILER = 0xAA
        private const val TYPE_WEIGHT = 0x10
        private const val TYPE_IMPEDANCE = 0x11
        private const val STABLE_FLAG = 0x02

        /**
         * Weight in raw 0.01 kg units from a valid, *stable* type 0x10 frame, or `null` if the
         * frame is malformed, not a weight frame, or still settling.
         *
         * Layout: `5a 0a 26 10 [flag] 00 00 [w_hi_extra] [w_hi] [w_lo] [chk] aa`; the weight is
         * the big-endian pair at bytes 8..9 (e.g. `21 34` = 8500 = 85.00 kg).
         */
        fun parseStableWeightRaw(frame: ByteArray): Int? {
            if (!isValidScaleFrame(frame)) return null
            if ((frame[3].toInt() and 0xFF) != TYPE_WEIGHT) return null
            if (frame.size < 12) return null
            if ((frame[4].toInt() and 0xFF) != STABLE_FLAG) return null
            return ((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF)
        }

        /**
         * Whole-body impedance in ohms from a valid type 0x11 frame, or `null` if the frame is
         * malformed, not an impedance frame, or reports zero.
         *
         * Layout: `5a 0b 26 11 00 00 00 00 00 [z_hi] [z_lo] [chk] aa`; impedance is the big-endian
         * pair at bytes 9..10 (e.g. `01 89` = 393 Ω).
         */
        fun parseImpedanceOhm(frame: ByteArray): Int? {
            if (!isValidScaleFrame(frame)) return null
            if ((frame[3].toInt() and 0xFF) != TYPE_IMPEDANCE) return null
            if (frame.size < 13) return null
            val ohm = ((frame[9].toInt() and 0xFF) shl 8) or (frame[10].toInt() and 0xFF)
            return if (ohm > 0) ohm else null
        }

        /** True if [frame] is a well-formed scale frame with a matching XOR checksum. */
        fun isValidScaleFrame(frame: ByteArray): Boolean {
            if (frame.size < 6) return false
            if ((frame[0].toInt() and 0xFF) != HEADER_SCALE) return false
            if ((frame[2].toInt() and 0xFF) != PRODUCT_ID) return false
            if ((frame[frame.size - 1].toInt() and 0xFF) != TRAILER) return false
            // len counts every byte after itself, i.e. index 2 through the trailer.
            if ((frame[1].toInt() and 0xFF) != frame.size - 2) return false
            return checksum(frame, 1, frame.size - 2) == (frame[frame.size - 2].toInt() and 0xFF)
        }

        /** Build a phone→scale command frame with the correct length and checksum. */
        fun buildCommand(type: Int, data: ByteArray): ByteArray {
            val len = data.size + 4 // 0x26, type, chk, trailer
            val frame = ByteArray(data.size + 6)
            frame[0] = HEADER_PHONE.toByte()
            frame[1] = len.toByte()
            frame[2] = PRODUCT_ID.toByte()
            frame[3] = type.toByte()
            data.copyInto(frame, 4)
            frame[frame.size - 2] = checksum(frame, 1, frame.size - 2).toByte()
            frame[frame.size - 1] = TRAILER.toByte()
            return frame
        }

        /** XOR of bytes in `[from, toExclusive)`. */
        private fun checksum(frame: ByteArray, from: Int, toExclusive: Int): Int {
            var acc = 0
            for (i in from until toExclusive) acc = acc xor (frame[i].toInt() and 0xFF)
            return acc and 0xFF
        }
    }
}
