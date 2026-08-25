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
import java.util.Date
import java.util.Locale
import java.util.UUID

class HealthKeep280Handler : ScaleDeviceHandler() {

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

        // 1. Streaming peso live (Mostra a schermo il valore corrente senza salvare record multipli nel database)
        if (characteristic == NOTIFY_CHAR && data.size >= 9) {
            val status = data[4].toInt() and 0xFF
            val weightRaw = ((data[6].toInt() and 0xFF) shl 16) or
                            ((data[7].toInt() and 0xFF) shl 8) or
                            (data[8].toInt() and 0xFF)
            val weight = weightRaw / 1000.0f

            if (status == 0x01 || status == 0x02) {
                userInfo(R.string.bluetooth_scale_info_measuring_weight, weight)
            }
        }

        // 2. Misura finale completa con BIA e Impedenza (Indicate su FFB3)
        if (characteristic == INDICATE_CHAR && data.size >= 11) {
            val cmd = data[3].toInt() and 0xFF
            if (cmd == 0xA3) {
                logD("Final measurement indication received: ${data.joinToString { "%02X".format(it) }}")

                val weightRaw = ((data[5].toInt() and 0xFF) shl 16) or
                                ((data[6].toInt() and 0xFF) shl 8) or
                                (data[7].toInt() and 0xFF)
                val weight = weightRaw / 1000.0f
                val heartRate = data[8].toInt() and 0xFF
                val impedance = ((data[9].toInt() and 0xFF) shl 8) or (data[10].toInt() and 0xFF)

                val measurement = ScaleMeasurement().apply {
                    this.userId = user.id
                    this.weight = weight
                    this.dateTime = Date()
                    if (heartRate > 0) {
                        this.heartRate = heartRate
                    }
                }

                // Calcolo automatico di grasso, acqua, muscoli e ossa tramite la libreria BIA di openScale
                if (impedance > 0 && user.bodyHeight > 0 && user.age > 0) {
                    measurement.impedance = impedance.toDouble()
                    
                    val impedanceLib = StandardImpedanceLib(
                        gender = user.gender,
                        age = user.age,
                        weightKg = weight.toDouble(),
                        heightM = user.bodyHeight / 100.0,
                        impedance = impedance.toDouble()
                    )

                    measurement.fat = impedanceLib.totalFatPercentage.toFloat().coerceIn(0f, 75f)
                    measurement.water = impedanceLib.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f)
                    measurement.muscle = impedanceLib.skeletalMusclePercentage.toFloat().coerceIn(0f, 99f)
                    measurement.bone = impedanceLib.boneMassKg.toFloat().coerceIn(0f, 10f)
                }

                logI("Publishing final measurement: $weight kg, Impedance: $impedance Ohm, HR: $heartRate bpm")
                publish(measurement)
                requestDisconnect()
            }
        }
    }
}
