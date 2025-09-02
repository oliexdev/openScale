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
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Handler for Hesley scales that advertise as "YunChen".
 *
 * Protocol summary:
 *  - Service 0xFFF0
 *  - Write command to char 0xFFF1 (magic bytes) to arm measurement
 *  - Enable NOTIFY on char 0xFFF4 and parse a 20-byte frame with composition values
 */
class HesleyHandler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFF0)
    private val CHAR_CMD: UUID = uuid16(0xFFF1) // write-only
    private val CHAR_NOTIFY: UUID = uuid16(0xFFF4) // notify (+read on some firmwares)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.lowercase(Locale.US) ?: return null
        // Legacy mapping used exact "YunChen"
        if (name != "yunchen") return null

        val caps = setOf(DeviceCapability.BODY_COMPOSITION)
        return DeviceSupport(
            displayName = "Hesley (YunChen)",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: com.health.openscale.core.bluetooth.data.ScaleUser) {
        // 1) enable notifications
        setNotifyOn(SERVICE, CHAR_NOTIFY)

        // 2) send the "magic" arming command (same as legacy)
        val magic = byteArrayOf(0xA5.toByte(), 0x01, 0x2C, 0xAB.toByte(), 0x50, 0x5A, 0x29)
        writeTo(SERVICE, CHAR_CMD, magic, withResponse = true)

        // 3) ask the user to step on the scale
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: com.health.openscale.core.bluetooth.data.ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return
        if (data.size != 20) return

        // Parse the single-frame 20-byte payload
        parseAndPublish(data)
    }

    // --- Parsing --------------------------------------------------------------

    private fun parseAndPublish(frame: ByteArray) {
        // Indices follow the legacy Java driver
        // weight: bytes 2..3 (BE? actually code used (b2<<8 | b3) -> big endian inside 2..3)
        val weight = (((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)) / 100.0f
        val fat = (((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)) / 10.0f
        val water = (((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF)) / 10.0f
        val muscle = (((frame[10].toInt() and 0xFF) shl 8) or (frame[11].toInt() and 0xFF)) / 10.0f
        val bone = (((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)) / 10.0f
        // val bodyAge = frame[17].toInt() and 0xFF // 10..99 (unused)
        // val kcal = (((frame[14].toInt() and 0xFF) shl 8) or (frame[15].toInt() and 0xFF)) // not stored

        val m = ScaleMeasurement().apply {
            dateTime = Date()
            this.weight = max(0f, weight)
            this.fat = fat
            this.muscle = muscle
            this.water = water
            this.bone = bone
        }

        logD( "Hesley result kg=${m.weight} fat=${m.fat} water=${m.water} muscle=${m.muscle} bone=${m.bone}")
        publish(m)
    }
}
