/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
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
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Runstar R5 handler.
 *
 * Protocol derived from the captured openScale session log:
 * - Service 0xFFB0
 * - Write characteristic 0xFFB1
 * - Notify characteristic 0xFFB2
 * - Initialization sequence matches the observed Runstar session exactly
 * - Notification frames are 20 bytes and carry a 24-bit big-endian weight value in bytes 6..8
 * - Stable measurements are flagged by mode 0x02 in byte 4
 * - Captured BLE traffic exposed weight only; body-composition values were not observed on the
 *   accessible characteristics, so this handler intentionally implements weight-only support
 *
 * The scale appears to be related to other ICOMON-branded devices, but the payload layout
 * differs from the existing MGB handler, so it gets a dedicated implementation.
 */
class RunstarR5Handler : ScaleDeviceHandler() {
    companion object {
        private const val WEIGHT_RAW_BASE = 0x680000
    }

    private val service = uuid16(0xFFB0)
    private val writeCharacteristic = uuid16(0xFFB1)
    private val notifyCharacteristic = uuid16(0xFFB2)

    private var lastPublishedWeightRaw: Int? = null
    private var lastPreviewWeightKg = -1f

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.US)
        val matchesKnownAdvertisingName =
            name == "RUNSTAR-R5" || name == "RUNSTAR-RX" || name.startsWith("RUNSTAR-")
        if (!matchesKnownAdvertisingName) return null

        return DeviceSupport(
            displayName = "Runstar R5",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        lastPublishedWeightRaw = null
        lastPreviewWeightKg = -1f
        setNotifyOn(service, notifyCharacteristic)

        writeConfig(0xF7, 0x00, 0x00, 0x00)
        writeConfig(0xFA, 0x00, 0x00, 0x00)
        writeConfig(
            0xFB,
            if (user.gender.isMale()) 0x01 else 0x02,
            user.age.coerceAtLeast(0),
            user.bodyHeight.toInt().coerceAtLeast(0)
        )

        val now = Calendar.getInstance()
        writeConfig(
            0xFD,
            (now.get(Calendar.YEAR) - 2000).coerceIn(0, 99),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        )
        writeConfig(
            0xFC,
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND)
        )
        writeConfig(0xFE, 0x06, unitCode(user.scaleUnit), 0x00)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic) return
        if (data.size != 20) return
        if (data[3] != 0xA2.toByte()) {
            logD("Unhandled Runstar frame ${data.toHexPreview(20)}")
            return
        }

        val mode = data[4].toInt() and 0xFF
        val rawWeight = u24be(data, 6)
        val weightKg = decodeWeightKg(rawWeight)

        when (mode) {
            0x01 -> {
                if (kotlin.math.abs(weightKg - lastPreviewWeightKg) >= 0.05f) {
                    userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
                    lastPreviewWeightKg = weightKg
                }
                logD("Runstar measuring frame weight=$weightKg kg raw=$rawWeight")
            }

            0x02 -> {
                if (lastPublishedWeightRaw == rawWeight) return

                val measurement = ScaleMeasurement().apply {
                    dateTime = Date()
                    weight = weightKg
                }
                publish(measurement)
                lastPublishedWeightRaw = rawWeight
                logI("Runstar final weight=$weightKg kg raw=$rawWeight")
                requestDisconnect()
            }

            else -> logD("Runstar unknown frame mode=$mode ${data.toHexPreview(20)}")
        }
    }

    private fun writeConfig(b2: Int, b3: Int, b4: Int, b5: Int) {
        val payload = byteArrayOf(
            0xAC.toByte(),
            0x02,
            (b2 and 0xFF).toByte(),
            (b3 and 0xFF).toByte(),
            (b4 and 0xFF).toByte(),
            (b5 and 0xFF).toByte(),
            0xCC.toByte(),
            0x00
        )

        val checksum =
            ((payload[2].toInt() and 0xFF) +
                (payload[3].toInt() and 0xFF) +
                (payload[4].toInt() and 0xFF) +
                (payload[5].toInt() and 0xFF) +
                (payload[6].toInt() and 0xFF)) and 0xFF
        payload[7] = checksum.toByte()

        writeTo(service, writeCharacteristic, payload, withResponse = true)
    }

    private fun unitCode(unit: WeightUnit): Int = when (unit) {
        WeightUnit.KG -> 0x00
        WeightUnit.LB -> 0x01
        WeightUnit.ST -> 0x02
    }

    private fun u24be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)

    private fun decodeWeightKg(rawWeight: Int): Float {
        val shifted = rawWeight - WEIGHT_RAW_BASE
        return shifted.coerceAtLeast(0) / 1000.0f
    }
}
