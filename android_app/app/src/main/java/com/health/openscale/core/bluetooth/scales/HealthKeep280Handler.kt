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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

class HealthKeep280Handler : ScaleDeviceHandler() {

    companion object {
        /** Command byte marking the final measurement indication (weight + heart rate + impedance). */
        const val CMD_FINAL_MEASUREMENT = 0xA3

        /** Shortest live notification frame that carries a weight. */
        const val MIN_LIVE_FRAME_LEN = 9

        /** Shortest final indication frame that carries the full measurement. */
        const val MIN_FINAL_FRAME_LEN = 11

        /** Command byte at index 3 of an indication frame. */
        fun commandOf(frame: ByteArray): Int = frame[3].toInt() and 0xFF

        /** Status byte at index 4 of a live frame; 0x01 and 0x02 mean a weighing is in progress. */
        fun liveStatusOf(frame: ByteArray): Int = frame[4].toInt() and 0xFF

        /** Big-endian 24-bit weight in grams at bytes[6..8] of a live notification frame. */
        fun liveWeightKg(frame: ByteArray): Float = weightKgAt(frame, 6)

        /** Big-endian 24-bit weight in grams at bytes[5..7] of a final indication frame. */
        fun finalWeightKg(frame: ByteArray): Float = weightKgAt(frame, 5)

        /** Heart rate in bpm at byte[8] of a final indication frame; 0 means not measured. */
        fun finalHeartRateBpm(frame: ByteArray): Int = frame[8].toInt() and 0xFF

        /** Big-endian impedance at bytes[9..10] of a final indication frame, in ohms. */
        fun finalImpedanceOhm(frame: ByteArray): Int =
            ((frame[9].toInt() and 0xFF) shl 8) or (frame[10].toInt() and 0xFF)

        private fun weightKgAt(frame: ByteArray, offset: Int): Float {
            val grams = ((frame[offset].toInt() and 0xFF) shl 16) or
                        ((frame[offset + 1].toInt() and 0xFF) shl 8) or
                        (frame[offset + 2].toInt() and 0xFF)
            return grams / 1000.0f
        }
    }

    private val SERVICE_UUID: UUID = uuid16(0xFFB0)
    private val WRITE_CHAR: UUID = uuid16(0xFFB1)
    private val NOTIFY_CHAR: UUID = uuid16(0xFFB2)
    private val INDICATE_CHAR: UUID = uuid16(0xFFB3)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim().uppercase(Locale.US)

        if (name.startsWith("HEALTHKEEP")) {
            val caps = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            )
            return DeviceSupport(
                displayName = "Healthkeep Smart Scale",
                capabilities = caps,
                implemented = caps,
                linkMode = LinkMode.CONNECT_GATT
            )
        }

        return null
    }

    override fun onConnected(user: ScaleUser) {
        logI("Starting connection sequence for Healthkeep scale.")

        setNotifyOn(SERVICE_UUID, NOTIFY_CHAR)
        setNotifyOn(SERVICE_UUID, INDICATE_CHAR)

        val sex = if (user.gender.isMale()) 0x01 else 0x02
        val age = user.age
        val height = user.bodyHeight.toInt()

        val payload = byteArrayOf(
            0xAC.toByte(), 0x02.toByte(), 0xFB.toByte(),
            sex.toByte(), age.toByte(), height.toByte(),
            0xCC.toByte(), 0x00.toByte()
        )

        var checksum = 0
        for (i in 0..6) {
            checksum += payload[i].toInt() and 0xFF
        }
        payload[7] = (checksum and 0xFF).toByte()

        writeTo(SERVICE_UUID, WRITE_CHAR, payload, withResponse = true)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return

        // 1. Live weight streaming: show the current value without storing a record per frame.
        if (characteristic == NOTIFY_CHAR && data.size >= MIN_LIVE_FRAME_LEN) {
            val status = liveStatusOf(data)

            if (status == 0x01 || status == 0x02) {
                userInfo(R.string.bluetooth_scale_info_measuring_weight, liveWeightKg(data))
            }
        }

        // 2. Final measurement with BIA and impedance (indicated on FFB3).
        if (characteristic == INDICATE_CHAR && data.size >= MIN_FINAL_FRAME_LEN) {
            if (commandOf(data) == CMD_FINAL_MEASUREMENT) {
                logD("Final measurement indication received: ${data.joinToString { "%02X".format(it) }}")

                val weight = finalWeightKg(data)
                val heartRate = finalHeartRateBpm(data)
                val impedance = finalImpedanceOhm(data)

                val measurement = ScaleMeasurement().apply {
                    this.userId = user.id
                    this[MeasurementType.WEIGHT] = Kg(weight)
                    this.dateTime = Date()
                    if (heartRate > 0) {
                        this[MeasurementType.HEART_RATE] = Bpm(heartRate)
                    }
                }

                // Fat, water, muscle and bone are derived from the impedance via openScale's BIA library.
                if (impedance > 0 && user.bodyHeight > 0 && user.age > 0) {
                    measurement[MeasurementType.IMPEDANCE] = Ohm(impedance.toFloat())

                    val impedanceLib = StandardImpedanceLib(
                        gender = user.gender,
                        age = user.age,
                        weightKg = weight.toDouble(),
                        heightM = user.bodyHeight / 100.0,
                        impedance = impedance.toDouble()
                    )

                    measurement[MeasurementType.BODY_FAT] = Percent(impedanceLib.totalFatPercentage.toFloat().coerceIn(0f, 75f))
                    measurement[MeasurementType.WATER] = Percent(impedanceLib.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f))
                    measurement[MeasurementType.MUSCLE] = Percent(impedanceLib.skeletalMusclePercentage.toFloat().coerceIn(0f, 99f))
                    measurement[MeasurementType.BONE] = Kg(impedanceLib.boneMassKg.toFloat().coerceIn(0f, 10f))
                }

                logI("Publishing final measurement: $weight kg, Impedance: $impedance Ohm, HR: $heartRate bpm")
                publish(measurement)
                requestDisconnect()
            }
        }
    }
}
