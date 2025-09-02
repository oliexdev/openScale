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

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.util.Date
import java.util.UUID
import kotlin.math.min

/**
 * RENPHO ES-26BB-B GATT handler (legacy: BluetoothES26BBB).
 *
 * Service: 0x1A10
 *  - Notify: 0x2A10  (measurements, state)
 *  - Write : 0x2A11  (commands)
 *
 * Frames use a simple checksum = sum(all bytes except last) & 0xFF.
 * Action byte: data[2]
 *   0x14 -> live/final measurement
 *   0x15 -> offline (historical) measurement
 *   0x11 -> scale info (power, unit, battery, ...)
 *   0x10 -> generic op callback (success/fail)
 */
class RenphoES26BBHandler : ScaleDeviceHandler() {

    private val SVC get() = uuid16(0x1A10)
    private val CHR_NOTIFY get() = uuid16(0x2A10)
    private val CHR_WRITE get() = uuid16(0x2A11)

    // Start/enable stream “magic” from legacy implementation (fixed, includes checksum)
    private val START_CMD = byteArrayOf(
        0x55, 0xAA.toByte(), 0x90.toByte(), 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x94.toByte()
    )

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name
        if (!name.equals("ES-26BB-B", ignoreCase = true)) return null

        val capabilities = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.BATTERY_LEVEL // device reports a battery/status frame
        )
        // Implemented today: live + offline read (when pushed by device)
        val implemented = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.HISTORY_READ
        )

        return DeviceSupport(
            displayName = "RENPHO ES-26BB-B",
            capabilities = capabilities,
            implemented = implemented,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        logD("onConnected -> enable notify & send start command")
        setNotifyOn(SVC, CHR_NOTIFY)
        writeTo(SVC, CHR_WRITE, START_CMD, withResponse = true)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_NOTIFY) return
        if (data.isEmpty()) return

        val hex = data.toHexPreview(48)
        logD("notify action=${String.format("%02X", data.getOrNull(2) ?: -1)} $hex")

        if (!isChecksumValid(data)) {
            logD("checksum invalid -> drop frame")
            return
        }

        when (data[2].toInt() and 0xFF) {
            0x14 -> handleLiveMeasurement(data)                // final/realtime, we only save finals
            0x15 -> handleOfflineMeasurement(data)             // includes timestamp delta
            0x11 -> parseScaleInfo(data)                       // power/unit/precision/offlineCount/battery
            0x10 -> parseOpCallback(data)                      // success/failure of a prior op
            else -> logD("unknown action=${String.format("%02X", data[2])}")
        }
    }

    // --- Parsers ---------------------------------------------------------------

    /** 0x14 live/final packet. Save only finals (type 0x01 or 0x11). */
    private fun handleLiveMeasurement(data: ByteArray) {
        if (data.size < 12) return
        val type = data[5]
        val isFinal = (type == 0x01.toByte() || type == 0x11.toByte())
        if (!isFinal) {
            logD("live measurement (non-final) ignored, type=${String.format("%02X", type)}")
            return
        }

        val weightX100 = ConverterUtils.fromUnsignedInt32Be(data, 6) // kg * 100
        val resistance = ConverterUtils.fromUnsignedInt16Be(data, 10)

        logD("final weight=${weightX100/100f}kg, impedance=$resistance")
        saveMeasurement(weightX100, resistance, timestampMs = null)
    }

    /** 0x15 offline packet. Includes seconds elapsed since measurement. */
    private fun handleOfflineMeasurement(data: ByteArray) {
        if (data.size < 15) return
        val weightX100 = ConverterUtils.fromUnsignedInt32Be(data, 5)   // kg * 100
        val resistance = ConverterUtils.fromUnsignedInt16Be(data, 9)
        val secondsAgo = ConverterUtils.fromUnsignedInt32Be(data, 11)
        val ts = System.currentTimeMillis() - secondsAgo * 1000L

        logD("offline weight=${weightX100/100f}kg, impedance=$resistance, ts=$ts")
        saveMeasurement(weightX100, resistance, ts)

        acknowledgeOfflineMeasurement()
    }

    /** 0x11 scale info frame (power/unit/precision/offlineCount/battery). */
    private fun parseScaleInfo(data: ByteArray) {
        // Ensure enough bytes
        if (data.size < 10) return
        val power = data[5].toInt() and 0xFF      // 1=on, 0=shutting down
        val unit = data[6].toInt() and 0xFF       // 1=kg (others unknown)
        val precision = data[7].toInt() and 0xFF  // usually 1
        val offlineCount = data[8].toInt() and 0xFF
        val battery = data[9].toInt() and 0xFF    // empirical: often 0; treat as unknown if 0

        logD("scale info: power=$power unit=$unit precision=$precision offlineCount=$offlineCount battery=$battery")
        // (Optional) you could surface battery as a userInfo or store it in settings if needed.
    }

    /** 0x10 generic callback for some operation. */
    private fun parseOpCallback(data: ByteArray) {
        val ok = data.getOrNull(5) == 0x01.toByte()
        logD(if (ok) "operation success" else "operation failure")
    }

    // --- I/O helpers -----------------------------------------------------------

    private fun acknowledgeOfflineMeasurement() {
        // payload = 55 AA 95 00 01 01 <sum>
        val p = byteArrayOf(0x55, 0xAA.toByte(), 0x95.toByte(), 0x00, 0x01, 0x01, 0x00)
        p[p.lastIndex] = sumChecksum(p, 0, p.size - 1)
        writeTo(SVC, CHR_WRITE, p, withResponse = true)
        logD("offline measurement ack sent")
    }

    private fun saveMeasurement(weightX100: Long, resistance: Int, timestampMs: Long?) {
        val m = ScaleMeasurement().apply {
            weight = weightX100 / 100f
            if (timestampMs != null) dateTime = Date(timestampMs)
            // Resistance is available but no BIA library hooked here;
            // leave composition fields empty for now.
        }
        publish(m)
    }

    // --- Checksums & utils -----------------------------------------------------

    /** Last byte is checksum = sum(all previous) & 0xFF. */
    private fun isChecksumValid(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val expected = data.last()
        val computed = sumChecksum(data, 0, data.size - 1)
        return expected == computed
    }

    private fun sumChecksum(src: ByteArray, start: Int, endExclusive: Int): Byte {
        var sum = 0
        val end = min(endExclusive, src.size)
        for (i in start until end) sum = (sum + (src[i].toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }
}
