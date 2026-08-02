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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lenovo HS10 body scale.
 *
 * The HS10 capture shows the same Chipsea-style GATT layout used by the HS11:
 * service 0xFFF0, command writes on 0xFFF1 and notifications on 0xFFF4. A
 * history request (F2 00) returns ten-byte records followed by an F2 00 end
 * marker. The record layout is:
 *
 *   [year/month, day, hour, minute, second, type/weight-hi, weight-lo, Z0, Z1, Z2]
 *
 * The date stores the year as an offset from 2017, weight is in deci-kg and
 * impedance is a little-endian 24-bit value. The issue capture did not contain
 * a measurement notification, so this handler deliberately implements the
 * documented stream without guessing at additional live-frame formats.
 */
class LenovoHS10Handler : ScaleDeviceHandler() {

    companion object {
        private const val RECORD_SIZE = 10
        private const val RECORD_TYPE_MASK = 0xF0
        private const val RECORD_TYPE = 0xF0

        data class DecodedRecord(
            val dateTime: Date,
            val weightKg: Float,
            val impedanceOhm: Int
        )

        /** Return true for the two-byte end-of-history marker. */
        fun isHistoryEnd(data: ByteArray): Boolean =
            data.size >= 2 && u8(data[0]) == 0xF2 && u8(data[1]) == 0x00

        /**
         * Decode one ten-byte HS10 history record.
         *
         * This is pure so the packet layout can be tested independently of BLE.
         */
        fun decodeRecord(data: ByteArray): DecodedRecord? {
            if (data.size < RECORD_SIZE || isHistoryEnd(data)) return null

            val month = u8(data[0]) and 0x0F
            val year = 2017 + ((u8(data[0]) ushr 4) and 0x0F)
            if (month !in 1..12 || (u8(data[5]) and RECORD_TYPE_MASK) != RECORD_TYPE) {
                return null
            }

            val weightDeciKg = ((u8(data[5]) and 0x0F) shl 8) or u8(data[6])
            val weightKg = weightDeciKg / 10.0f
            if (weightKg <= 0f || weightKg > 300f) return null

            val calendar = Calendar.getInstance().apply {
                clear()
                isLenient = false
                set(
                    year,
                    month - 1,
                    u8(data[1]),
                    u8(data[2]),
                    u8(data[3]),
                    u8(data[4])
                )
            }
            val dateTime = runCatching { calendar.time }.getOrNull() ?: return null

            val impedanceOhm = u8(data[7]) or
                (u8(data[8]) shl 8) or
                (u8(data[9]) shl 16)

            return DecodedRecord(dateTime, weightKg, impedanceOhm)
        }

        private fun u8(value: Byte): Int = value.toInt() and 0xFF
    }

    private val service = uuid16(0xFFF0)
    private val commandCharacteristic = uuid16(0xFFF1)
    private val notifyCharacteristic = uuid16(0xFFF4)

    private var buffer = ByteArray(0)
    private var historyMode = false
    private var historyCount = 0
    private val publishedTimestamps = mutableSetOf<Long>()

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Use the name only so a saved HS10 can still be recognised when the
        // saved-device lookup has no advertised service UUIDs available.
        val name = device.name.trim().uppercase(Locale.ROOT)
        if (!name.contains("HS10") || (!name.contains("LENOVO") && name != "HS10")) {
            return null
        }
        if (device.serviceUuids.isNotEmpty() && service !in device.serviceUuids) return null

        return DeviceSupport(
            displayName = "Lenovo HS10",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.HISTORY_READ,
                DeviceCapability.BODY_COMPOSITION,
            ),
            // The protocol exposes an impedance value, but no validated
            // HS10-specific body-composition formula is available here.
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.HISTORY_READ,
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        buffer = ByteArray(0)
        historyMode = true
        historyCount = 0
        publishedTimestamps.clear()

        setNotifyOn(service, notifyCharacteristic)
        writeTo(
            service,
            commandCharacteristic,
            byteArrayOf(0xF2.toByte(), 0x00.toByte()),
            withResponse = true
        )
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic || data.isEmpty()) return

        buffer += data
        drainBuffer(user)
    }

    override fun onDisconnected() {
        buffer = ByteArray(0)
        historyMode = false
        historyCount = 0
        publishedTimestamps.clear()
    }

    private fun drainBuffer(user: ScaleUser) {
        while (buffer.isNotEmpty()) {
            if (isHistoryEnd(buffer)) {
                buffer = buffer.copyOfRange(2, buffer.size)
                if (!historyMode) continue
                historyMode = false
                if (historyCount > 0) {
                    // F2 01 clears records that were just consumed, preventing
                    // the same history from being imported on every reconnect.
                    writeTo(
                        service,
                        commandCharacteristic,
                        byteArrayOf(0xF2.toByte(), 0x01.toByte()),
                        withResponse = true
                    )
                }
                historyCount = 0
                userInfo(R.string.bt_info_step_on_scale)
                continue
            }

            if (buffer.size < RECORD_SIZE) return

            val recordBytes = buffer.copyOfRange(0, RECORD_SIZE)
            buffer = buffer.copyOfRange(RECORD_SIZE, buffer.size)
            val record = decodeRecord(recordBytes) ?: continue
            if (historyMode) historyCount++
            publishRecord(record, user)
        }
    }

    private fun publishRecord(record: DecodedRecord, user: ScaleUser) {
        if (!publishedTimestamps.add(record.dateTime.time)) return

        publish(ScaleMeasurement().apply {
            userId = user.id
            dateTime = record.dateTime
            weight = record.weightKg
            impedance = record.impedanceOhm.toDouble()
        })
    }
}
