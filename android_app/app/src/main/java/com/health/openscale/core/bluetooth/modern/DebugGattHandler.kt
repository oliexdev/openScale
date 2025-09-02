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

import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.UUID

/**
 * Debug GATT handler (migration of the old BluetoothDebug).
 *
 * Notes:
 * - The modern handler API does not expose a full service/descriptor dump, nor does it deliver
 *   READ responses back to the handler. This debug handler therefore focuses on:
 *      * subscribing to a few common characteristics (best effort),
 *      * reading some typical DIS/BAS fields (best effort),
 *      * logging any incoming NOTIFY frames verbosely.
 * - It activates ONLY if the UI/scan sets `determinedHandlerDisplayName == "Debug"`, so it won’t
 *   steal devices from real drivers.
 * - It does not publish measurements; it’s for inspection/logging only.
 */
class DebugGattHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Enable this handler only when explicitly requested as "Debug"
        val display = device.determinedHandlerDisplayName ?: return null // TODO set optional Debug on UI
        if (!display.equals("Debug", ignoreCase = true)) return null

        return DeviceSupport(
            displayName = "Debug",
            capabilities = emptySet(),   // no functional features
            implemented = emptySet(),
            tuningProfile = TuningProfile.Balanced,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        logD("Connected in Debug mode. Subscribing/reading a few common characteristics…")

        // Best-effort reads (will silently no-op if the service/char does not exist)
        // Generic Access: Device Name
        readSafe(uuid16(0x1800), uuid16(0x2A00))
        // Device Information: Manufacturer, Model, FW, SW
        readSafe(uuid16(0x180A), uuid16(0x2A29))
        readSafe(uuid16(0x180A), uuid16(0x2A24))
        readSafe(uuid16(0x180A), uuid16(0x2A26))
        readSafe(uuid16(0x180A), uuid16(0x2A28))
        // Battery
        readSafe(uuid16(0x180F), uuid16(0x2A19))

        // Common weight/body-composition notifications (subscribe if present)
        setNotifySafe(uuid16(0x181D), uuid16(0x2A9D)) // Weight Scale / Weight Measurement
        setNotifySafe(uuid16(0x181B), uuid16(0x2A9C)) // Body Composition / Measurement

        logD("Debug handler armed. Incoming NOTIFY frames will be logged; no data is stored.")
        // Keep the connection open to observe notifications.
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        val hex = data.toHexPreview(64)
        val text = data.decodePrintablePreview()
        LogManager.d(
            TAG,
            "NOTIFY chr=${prettyUuid(characteristic)}  $hex  ascii=$text"
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun setNotifySafe(service: UUID, chr: UUID) {
        logD("→ setNotifyOn svc=${prettyUuid(service)} chr=${prettyUuid(chr)}")
        runCatching { setNotifyOn(service, chr) }
    }

    private fun readSafe(service: UUID, chr: UUID) {
        // READ results are not delivered to handlers in the modern stack; this is for side-effects/logging
        logD("→ read svc=${prettyUuid(service)} chr=${prettyUuid(chr)} (best effort)")
        runCatching { readFrom(service, chr) }
    }

    private fun prettyUuid(u: UUID): String {
        // Compact 16-bit format when possible
        val s = u.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb"))
            "0x" + s.substring(4, 8)
        else s
    }

    private fun ByteArray.decodePrintablePreview(max: Int = 64): String {
        if (isEmpty()) return ""
        val show = kotlin.math.min(size, max)
        val sb = StringBuilder()
        for (i in 0 until show) {
            val ch = this[i].toInt().toChar()
            sb.append(if (ch.isISOControl()) '?' else ch)
        }
        if (size > max) sb.append("…(+").append(size - max).append("b)")
        return sb.toString()
    }
}
