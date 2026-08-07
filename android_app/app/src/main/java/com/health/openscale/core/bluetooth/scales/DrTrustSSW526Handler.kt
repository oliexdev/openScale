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
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Dr. Trust SSW526 smart scale.
 *
 * SSW526 advertises the MGB family's 0xFFB0 service, but its 0xFFB2 payload is
 * an AC 27 stream rather than the AC 02/03 + 01 00 pair consumed by MGBHandler.
 * The 24-bit value at bytes 3..5 is an offset gram counter:
 *
 *   weightKg = (u24be(bytes[3..5]) - 0x680000) / 1000
 *
 * The captured frames contain no impedance/body-composition payload, so this
 * handler publishes weight only and uses repeated frames to detect stability.
 */
class DrTrustSSW526Handler : ScaleDeviceHandler() {

    companion object {
        private const val WEIGHT_RAW_BASE = 0x680000
        private const val STABLE_FRAMES = 4
        private const val FALLBACK_DELAY_MS = 8000L

        /** Recognise the fixed header/markers observed in SSW526 notifications. */
        fun isMeasurementFrame(data: ByteArray): Boolean =
            data.size >= 20 &&
                u8(data[0]) == 0xAC &&
                u8(data[1]) == 0x27 &&
                u8(data[16]) == 0x03 &&
                u8(data[17]) == 0xD5

        /** Decode the offset 24-bit gram value used by SSW526. */
        fun decodeWeightKg(channel: Byte, high: Byte, low: Byte): Float {
            val raw = (u8(channel) shl 16) or
                (u8(high) shl 8) or
                u8(low)
            return (raw - WEIGHT_RAW_BASE).coerceAtLeast(0) / 1000.0f
        }

        private fun u8(value: Byte): Int = value.toInt() and 0xFF
    }

    private val service = uuid16(0xFFB0)
    private val commandCharacteristic = uuid16(0xFFB1)
    private val notifyCharacteristic = uuid16(0xFFB2)

    private var pendingWeightKg = 0f
    private var lastRawWeight: Int? = null
    private var stableCount = 0
    private var published = false
    private var fallbackJob: Job? = null
    private var currentUserId = 0xFF

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim().uppercase(Locale.ROOT)
        val nameMatch = name.contains("SSW526") ||
            (name.contains("DR.TRUST") && name.contains("526"))
        if (!nameMatch) return null
        if (device.serviceUuids.isNotEmpty() && service !in device.serviceUuids) return null

        return DeviceSupport(
            displayName = "Dr. Trust SSW526",
            capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            implemented = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        currentUserId = user.id
        pendingWeightKg = 0f
        lastRawWeight = null
        stableCount = 0
        published = false
        fallbackJob?.cancel()
        fallbackJob = null

        setNotifyOn(service, notifyCharacteristic)
        writeConfig(0xF7, 0, 0, 0)
        writeConfig(0xFA, 0, 0, 0)
        writeConfig(
            0xFB,
            if (user.gender.isMale()) 1 else 2,
            user.age,
            user.bodyHeight.toInt().coerceAtLeast(0)
        )

        val now = java.util.Calendar.getInstance()
        writeConfig(
            0xFD,
            (now.get(java.util.Calendar.YEAR) - 2000).coerceIn(0, 99),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH)
        )
        writeConfig(
            0xFC,
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            now.get(java.util.Calendar.SECOND)
        )
        writeConfig(0xFE, 6, user.scaleUnit.toInt(), 0)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        fallbackJob?.cancel()
        fallbackJob = null
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic || !isMeasurementFrame(data)) return

        val rawWeight = u24be(data, 3)
        val weightKg = decodeWeightKg(data[3], data[4], data[5])
        if (rawWeight <= WEIGHT_RAW_BASE || weightKg <= 0f || weightKg > 300f) return

        stableCount = if (rawWeight == lastRawWeight) stableCount + 1 else 1
        lastRawWeight = rawWeight
        pendingWeightKg = weightKg

        val explicitlyStable = (data[2].toInt() and 0xFF) == 0x80
        if (explicitlyStable || stableCount >= STABLE_FRAMES) {
            publishWeight(weightKg)
        } else {
            armFallback()
        }
    }

    private fun publishWeight(weightKg: Float) {
        if (published || weightKg <= 0f) return
        published = true
        fallbackJob?.cancel()
        fallbackJob = null

        publish(ScaleMeasurement().apply {
            userId = currentUserId
            dateTime = Date()
            weight = weightKg
        })
        requestDisconnect()
    }

    private fun armFallback() {
        if (fallbackJob != null || published) return
        fallbackJob = scope.launch {
            delay(FALLBACK_DELAY_MS)
            if (!published && pendingWeightKg > 0f) {
                publishWeight(pendingWeightKg)
            }
        }
    }

    private fun writeConfig(b2: Int, b3: Int, b4: Int, b5: Int) {
        val payload = byteArrayOf(
            0xAC.toByte(),
            0x02.toByte(),
            (b2 and 0xFF).toByte(),
            (b3 and 0xFF).toByte(),
            (b4 and 0xFF).toByte(),
            (b5 and 0xFF).toByte(),
            0xCC.toByte(),
            0x00.toByte()
        )
        payload[7] = (
            (payload[2].toInt() and 0xFF) +
                (payload[3].toInt() and 0xFF) +
                (payload[4].toInt() and 0xFF) +
                (payload[5].toInt() and 0xFF) +
                (payload[6].toInt() and 0xFF)
            ).toByte()
        writeTo(service, commandCharacteristic, payload, withResponse = true)
    }

    private fun u24be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
}
