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
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID
import kotlin.math.abs

/**
 * Digoo DG-SO38H GATT handler (legacy: BluetoothDigooDGSO38H).
 *
 * Service: 0xFFF0
 *  - Notify: 0xFFF1  (20B measurement frames)
 *  - Write : 0xFFF2  (request "all values" with user profile + unit)
 *
 * Frame (20 bytes):
 *  - ctrl @ [5]
 *      bit0 -> weight stabilized
 *      bit1 -> "all values" present (fat/water/muscle/bone/viscFat)
 *
 * If only stabilized is set, we push a config block with gender/height/age/unit
 * to trigger a full BIA result in subsequent notifications.
 */
class DigooDGSO38HHandler : ScaleDeviceHandler() {

    private val SVC get() = uuid16(0xFFF0)
    private val CHR_MEAS get() = uuid16(0xFFF1) // notify
    private val CHR_EXTRA get() = uuid16(0xFFF2) // write

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name ?: return null
        // Historically these scales often advertise as "Mengii". Keep both labels.
        val supported = name.equals("Mengii", true)
        if (!supported) return null

        val capabilities = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
            DeviceCapability.UNIT_CONFIG
        )
        // Implemented fully here:
        val implemented = capabilities

        return DeviceSupport(
            displayName = if (name.equals("Mengii", true)) "Digoo DG-SO38H (Mengii)" else "Digoo DG-SO38H",
            capabilities = capabilities,
            implemented = implemented,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        logD("onConnected → enable notifications on FFF1")
        setNotifyOn(SVC, CHR_MEAS)
        userInfo(R.string.bt_info_step_on_scale, 0)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_MEAS) return
        if (data.size != 20) return

        val ctrl = data[5]
        val weightStabilized = isBitSet(ctrl, 0)
        val allValues = isBitSet(ctrl, 1)

        if (weightStabilized) {
            // Send user profile + unit to trigger a full analysis frame next
            writeAllValuesRequest(user)
            return
        }

        if (allValues) {
            publishMeasurement(data)
        }
    }

    // --- Protocol helpers ------------------------------------------------------

    private fun writeAllValuesRequest(user: ScaleUser) {
        val gender: Byte = if (user.gender.isMale()) 0x00 else 0x01
        val height: Byte = user.bodyHeight.toInt().toByte()
        val age: Byte = user.age.toByte()
        val unit: Byte = when (user.scaleUnit) {
            WeightUnit.KG -> 0x01
            WeightUnit.LB -> 0x02
            WeightUnit.ST -> 0x08.toByte()
        }

        // From legacy driver:
        // 0x09 0x10 0x12 0x11 0x0D 0x01 [height] [age] [gender] [unit] 0x00 0x00 0x00 0x00 0x00 [chk]
        val payload = ByteArray(16)
        payload[0] = 0x09
        payload[1] = 0x10
        payload[2] = 0x12
        payload[3] = 0x11
        payload[4] = 0x0D
        payload[5] = 0x01
        payload[6] = height
        payload[7] = age
        payload[8] = gender
        payload[9] = unit
        // [10..14] are 0x00
        // checksum = sum(bytes[3..14]) & 0xFF (per original)
        var sum = 0
        for (i in 3..14) sum = (sum + (payload[i].toInt() and 0xFF)) and 0xFF
        payload[15] = sum.toByte()

        logD("→ write EXTRA (FFF2): ${payload.toHexPreview(48)}")
        writeTo(SVC, CHR_EXTRA, payload, withResponse = true)
    }

    private fun publishMeasurement(frame: ByteArray) {
        // Weight in kg*0.01 at [3..4] big-endian
        val weight = (((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)) / 100.0f
        // Fat % at [6..7] /10
        val fat = (((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)) / 10.0f

        val m = ScaleMeasurement().apply {
            this.weight = weight
            dateTime = Date()
        }

        if (abs(fat - 0.0f) < 0.00001f) {
            // Device sometimes signals "all values" but fat==0 → only weight is reliable.
            logD("DG-SO38H: all-values flag set but fat==0, storing weight only")
        } else {
            // viscFat index at [10] /10
            val visceral = (frame[10].toInt() and 0xFF) / 10.0f
            // water % [11..12] /10
            val water = (((frame[11].toInt() and 0xFF) shl 8) or (frame[12].toInt() and 0xFF)) / 10.0f
            // muscle % [16..17] /10
            val muscle = (((frame[16].toInt() and 0xFF) shl 8) or (frame[17].toInt() and 0xFF)) / 10.0f
            // bone kg [18] /10
            val bone = (frame[18].toInt() and 0xFF) / 10.0f

            m.fat = fat
            m.water = water
            m.muscle = muscle
            m.bone = bone
            m.visceralFat = visceral
        }

        publish(m)
    }

    // --- Utils -----------------------------------------------------------------

    private fun isBitSet(b: Byte, bit: Int): Boolean =
        ((b.toInt() shr bit) and 1) == 1
}
