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
import com.health.openscale.core.bluetooth.libs.ChipseaBleLib
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

/**
 * Handler for Chipsea "Chipsea-BLE" GATT body-fat scales (vendor app: OKOK International).
 *
 * Protocol summary (full documentation in [ChipseaBleLib]):
 *  - Service 0xFFF0, notifications on 0xFFF1, writes on 0xFFF2
 *  - On connect the phone sends a v1.0 user/time sync frame; the scale then streams
 *    live weight frames and finishes with a locked frame carrying body composition.
 *
 * The advertised name is matched exactly on purpose: 0xFFF0 is Chipsea's generic
 * UART service and shows up on unrelated gadgets, so the service UUID alone would
 * claim far too much.
 */
class ChipseaBleHandler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFF0)
    private val CHAR_RX: UUID = uuid16(0xFFF1)
    private val CHAR_TX: UUID = uuid16(0xFFF2)

    private val lib = ChipseaBleLib()
    private var published = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.trim().equals("Chipsea-BLE", ignoreCase = true)) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.LIVE_WEIGHT_STREAM,
        )
        return DeviceSupport(
            displayName = "Chipsea-BLE",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        published = false
        setNotifyOn(SERVICE, CHAR_RX)

        // The scale expects the user profile (which also sets its clock) before measuring.
        val sync = ChipseaBleLib.buildUserSyncV10(
            userId = if (user.id > 0) user.id else 1,
            isMale = user.gender.isMale(),
            age = user.age,
            heightCm = user.bodyHeight.toInt().coerceIn(0, 255),
            now = Date(),
        )
        logD("→ user sync ${sync.toHexPreview(20)}")
        writeTo(SERVICE, CHAR_TX, sync, withResponse = false)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_RX) return
        val reading = lib.feed(data)
        if (reading == null) {
            logD("unhandled frame ${data.toHexPreview(20)}")
            return
        }

        if (!reading.locked) {
            // Weight has not settled yet — preview only.
            logD("live weight ${reading.weightKg} kg")
            userInfo(R.string.bluetooth_scale_info_measuring_weight, reading.weightKg)
            return
        }

        if (published) return
        published = true

        val measurement = ScaleMeasurement().apply {
            dateTime = reading.timestamp ?: Date()
            this[MeasurementType.WEIGHT] = Kg(reading.weightKg)
            reading.bodyFatPercent?.let { this[MeasurementType.BODY_FAT] = Percent(it) }
            reading.waterPercent?.let { this[MeasurementType.WATER] = Percent(it) }
            reading.musclePercent?.let { this[MeasurementType.MUSCLE] = Percent(it) }
            reading.bmrKcal?.let { this[MeasurementType.BMR] = Kcal(it.toFloat()) }
            reading.visceralFat?.let { this[MeasurementType.VISCERAL_FAT] = it }
            reading.boneKg?.let { this[MeasurementType.BONE] = Kg(it) }
        }
        logI(
            "publishing → weight=${reading.weightKg}kg fat=${reading.bodyFatPercent}% " +
                "water=${reading.waterPercent}% muscle=${reading.musclePercent}% " +
                "bmr=${reading.bmrKcal}kcal visceral=${reading.visceralFat} bone=${reading.boneKg}kg",
        )
        publish(measurement)
        requestDisconnect()
    }
}
