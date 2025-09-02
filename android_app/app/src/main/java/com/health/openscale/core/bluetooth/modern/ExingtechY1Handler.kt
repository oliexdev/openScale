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

import android.util.Log
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Handler for Exingtech Y1 scales (often advertising as "VScale").
 *
 * Protocol:
 *  - Custom Service:      f433bd80-75b8-11e2-97d9-0002a5d5c51b
 *  - Notify Characteristic: 1a2ea400-75b9-11e2-be05-0002a5d5c51b
 *  - Write Characteristic:  29f11080-75b9-11e2-8bf6-0002a5d5c51b
 *
 * Flow:
 *  1) Enable NOTIFY on data characteristic.
 *  2) Write user block: [0x10, userId, gender(0=male/1=female), age, height(cm)].
 *  3) Wait for a 20-byte result frame; the first one may only contain weight.
 *     Publish when body composition (fat) is present (data[6] != 0xFF).
 */
class ExingtechY1Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID =
        UUID.fromString("f433bd80-75b8-11e2-97d9-0002a5d5c51b")
    private val CHAR_NOTIFY: UUID =
        UUID.fromString("1a2ea400-75b9-11e2-be05-0002a5d5c51b")
    private val CHAR_CMD: UUID =
        UUID.fromString("29f11080-75b9-11e2-8bf6-0002a5d5c51b")

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.US)
        val byName = (name == "vscale")

        val byService = device.serviceUuids.any {
            it.equals(SERVICE)
        }

        if (!byName && !byService) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC
        )

        return DeviceSupport(
            displayName = "Exingtech Y1 (VScale)",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        // Enable notifications for result frames
        setNotifyOn(SERVICE, CHAR_NOTIFY)

        // Send user block (id is truncated to 1 byte like legacy driver)
        val userIdOneByte = (user.id and 0xFF).toByte()
        val gender = if (user.gender.isMale()) 0x00 else 0x01
        val age = (user.age and 0xFF).toByte()
        val height = (user.bodyHeight.toInt() and 0xFF).toByte()

        val cmd = byteArrayOf(
            0x10,
            userIdOneByte,
            gender.toByte(),
            age,
            height
        )
        writeTo(SERVICE, CHAR_CMD, cmd, withResponse = true)

        // Prompt user
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return
        if (data.size != 20) return

        // The first notify can be "weight only"; full composition follows.
        // In legacy code we waited until fat != 0xFF.
        val fatHi = data[6]
        if (fatHi.toInt() and 0xFF == 0xFF) {
            logD("VScale: weight-only frame, waiting for full composition…")
            return
        }

        publish(parseMeasurement(data))
    }

    // --- Parsing --------------------------------------------------------------

    private fun parseMeasurement(frame: ByteArray): ScaleMeasurement {
        // Big-endian 16-bit fields, matching legacy ConverterUtils.fromUnsignedInt16Be
        val weight = ConverterUtils.fromUnsignedInt16Be(frame, 4) / 10.0f
        val fat = ConverterUtils.fromUnsignedInt16Be(frame, 6) / 10.0f
        val water = ConverterUtils.fromUnsignedInt16Be(frame, 8) / 10.0f
        val bone = ConverterUtils.fromUnsignedInt16Be(frame, 10) / 10.0f
        val muscle = ConverterUtils.fromUnsignedInt16Be(frame, 12) / 10.0f
        val visceralIndex = (frame[14].toInt() and 0xFF).toFloat()
        // calorie (offset 15) and BMI (offset 17) exist but are computed by app; skip.

        return ScaleMeasurement().apply {
            dateTime = Date()
            this.weight = weight
            this.fat = fat
            this.water = water
            this.muscle = muscle
            this.bone = bone
            this.visceralFat = visceralIndex
        }.also {
            logD("VScale result kg=${it.weight} fat=${it.fat} water=${it.water} muscle=${it.muscle} bone=${it.bone} visc=${it.visceralFat}"
            )
        }
    }
}
