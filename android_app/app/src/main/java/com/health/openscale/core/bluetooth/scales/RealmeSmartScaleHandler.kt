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
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

/**
 * Handler for the Realme Smart Scale (Lifesense lineage).
 * Protocol architecture:
 * - Requires a 6-step active handshake written to 0xA624 immediately upon connection.
 * - Handshake automatically triggers an unacknowledged dump of the offline history buffer.
 * - Requires a continuous 0x0001D9 keep-alive ping written to 0xA622 every 1 second.
 * - Live and history measurement streams to 0xA621.
 * - Final locked measurement packet identifiable by prefix [0x10, 0x11].
 */
class RealmeScaleHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "RealmeScaleHandler"

        // Service & Characteristics
        private val SVC_A602 = UUID.fromString("0000a602-0000-1000-8000-00805f9b34fb")
        private val CHR_A621 = UUID.fromString("0000a621-0000-1000-8000-00805f9b34fb") // Notify (Measurements)
        private val CHR_A622 = UUID.fromString("0000a622-0000-1000-8000-00805f9b34fb") // Write (Keep-Alive)
        private val CHR_A624 = UUID.fromString("0000a624-0000-1000-8000-00805f9b34fb") // Write (Handshake)
        private val CHR_A625 = UUID.fromString("0000a625-0000-1000-8000-00805f9b34fb") // Notify (Echos/Keep-Alive)

        private val KEEP_ALIVE_CMD = byteArrayOf(0x00, 0x01, 0xD9.toByte())

        private val HANDSHAKE_CMDS = listOf(
            byteArrayOf(0x10, 0x0b, 0xd8.toByte(), 0x03, 0xca.toByte(), 0x14, 0x0e, 0xc4.toByte(), 0xd8.toByte(), 0x0b, 0xcb.toByte(), 0x14, 0x0c),
            byteArrayOf(0x10, 0x08, 0xd8.toByte(), 0x01, 0xd3.toByte(), 0x7d, 0x97.toByte(), 0x14, 0x61, 0x37),
            byteArrayOf(0x10, 0x04, 0x90.toByte(), 0x0a, 0xcb.toByte(), 0x15),
            byteArrayOf(0x10, 0x0b, 0xc8.toByte(), 0x0a, 0xca.toByte(), 0x14, 0x1a, 0xc4.toByte(), 0x77, 0x0b, 0xcb.toByte(), 0x0d, 0x6a),
            byteArrayOf(0x10, 0x03, 0xc8.toByte(), 0x0f, 0xcb.toByte()),
            byteArrayOf(0x10, 0x03, 0xc8.toByte(), 0x0c, 0xca.toByte())
        )
    }

    private var keepAliveTimer: Timer? = null

    // Time Anchoring Variables
    private val historyBuffer = mutableListOf<Pair<Long, ScaleMeasurement>>()
    private var isBuffering = true
    private var timeOffset: Long = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()
        val hasSvc = device.serviceUuids.any { it == SVC_A602 }

        if (!name.contains("realme") && !hasSvc) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM
        )

        return DeviceSupport(
            displayName = "Realme Smart Scale",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        historyBuffer.clear()
        timeOffset = 0
        isBuffering = true

        setNotifyOn(SVC_A602, CHR_A621)
        setNotifyOn(SVC_A602, CHR_A625)

        LogManager.d(TAG, "Sending handshake sequence...")
        HANDSHAKE_CMDS.forEach { cmd ->
            writeTo(SVC_A602, CHR_A624, cmd)
        }

        // Start the Keep-Alive loop
        keepAliveTimer = Timer()
        keepAliveTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    writeTo(SVC_A602, CHR_A622, KEEP_ALIVE_CMD)
                } catch (e: Exception) {
                    LogManager.e(TAG, "Keep-alive write failed: ${e.message}")
                }
            }
        }, 500, 1000)

        // Close the buffer 2.5 seconds after connection to process the archive dump
        Timer().schedule(object : TimerTask() {
            override fun run() {
                flushHistoryBuffer()
            }
        }, 2500)

        LogManager.i(TAG, "Realme scale connected and active.")
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_A621) return

        if (data.size >= 19 && data[0] == 0x10.toByte() && data[1] == 0x11.toByte()) {
            parseMeasurement(data, user)
        }
    }

    override fun onDisconnected() {
        keepAliveTimer?.cancel()
        keepAliveTimer = null
        LogManager.d(TAG, "Disconnected. Stopped keep-alive timer.")
    }

    private fun flushHistoryBuffer() {
        synchronized(historyBuffer) {
            if (historyBuffer.isNotEmpty()) {
                // Find the newest timestamp in the buffer
                val maxScaleTime = historyBuffer.maxOf { it.first }
                val currentUnixTime = System.currentTimeMillis() / 1000L

                // Calculate the delta to shift the scale's broken 2024 clock to the present
                timeOffset = currentUnixTime - maxScaleTime
                LogManager.i(TAG, "History buffer closed. Calculated clock offset: +$timeOffset seconds.")

                historyBuffer.forEach { (scaleTime, measurement) ->
                    measurement.dateTime = Date((scaleTime + timeOffset) * 1000L)
                    LogManager.i(TAG, "Offline Measurement: Weight=${measurement.weight} kg, Date=${measurement.dateTime}")
                    publish(measurement)
                }
                historyBuffer.clear()
            } else {
                LogManager.i(TAG, "History buffer closed. No offline records found.")
            }
            isBuffering = false
        }
    }

    private fun parseMeasurement(data: ByteArray, user: ScaleUser) {
        // Reverse engineered XOR decoding
        val weightRaw = u16be(data, 10)
        val weightKg = (weightRaw xor 0xCB14) / 100.0f

        val impRaw = u16be(data, 16)
        val impedance = impRaw xor 0xCB14

        val scaleTime = u32be(data, 12)

        // Sanity check
        if (weightKg <= 0.5f || weightKg > 300f) return

        val measurement = ScaleMeasurement().apply {
            this.userId = user.id
            this.weight = weightKg
        }

        // --- LOCAL BIA CALCULATION ENGINE ---
        // If impedance > 0, the user was barefoot. Run the local math.
        if (impedance > 0) {
            measurement.impedance = impedance.toDouble()

            val sex = if (user.gender.isMale()) 1 else 0
            val calc = com.health.openscale.core.bluetooth.libs.YunmaiLib(sex, user.bodyHeight, user.activityLevel)

            val fatPct = calc.getFat(user.age, weightKg, impedance)

            if (fatPct > 0f) {
                measurement.fat = fatPct
                measurement.muscle = calc.getMuscle(fatPct) / weightKg * 100.0f
                measurement.water = calc.getWater(fatPct)
                measurement.bone = calc.getBoneMass(measurement.muscle, weightKg)
                measurement.lbm = calc.getLeanBodyMass(weightKg, fatPct)
                measurement.visceralFat = calc.getVisceralFat(fatPct, user.age)
            }
        }

        synchronized(historyBuffer) {
            if (isBuffering) {
                historyBuffer.add(Pair(scaleTime, measurement))
            } else {
                measurement.dateTime = Date((scaleTime + timeOffset) * 1000L)
                LogManager.i(TAG, "Live Measurement: Weight=${measurement.weight} kg, Fat=${measurement.fat}%, Date=${measurement.dateTime}")
                publish(measurement)
            }
        }
    }

    private fun u16be(b: ByteArray, off: Int): Int {
        if (off + 1 >= b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun u32be(b: ByteArray, off: Int): Long {
        if (off + 3 >= b.size) return 0
        return ((b[off].toLong() and 0xFF) shl 24) or
               ((b[off + 1].toLong() and 0xFF) shl 16) or
               ((b[off + 2].toLong() and 0xFF) shl 8) or
               (b[off + 3].toLong() and 0xFF)
    }
}
